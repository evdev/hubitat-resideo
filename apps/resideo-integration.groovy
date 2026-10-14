/*
 * Resideo Integration for Hubitat, v0.1.5
 * Based on sfcodes/ha-resideo 0e2947b8ad83608ccf212dd2638f2aa3eda06eaa.
 * Portions copyright (c) 2026 Semyon Fishman. MIT; see LICENSE.upstream.
 */
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.security.MessageDigest
import java.util.UUID
import java.time.Instant

definition(name: 'Resideo Integration', namespace: 'resideoHubitat', author: 'Ephrayim (evdev)',
    description: 'First Alert thermostats and room sensors; direct cloud integration.',
    category: 'Convenience', iconUrl: '', iconX2Url: '', singleThreaded: true, installOnOpen: true)

preferences { page(name: 'mainPage') }

Map constants() {
    [base: 'https://api.ha.resideo.com', auth: 'https://login.resideo.com',
     client: 'SRmiA7CaYi1JgivDZdzzoZu4X5VBogGt', subscription: 'b60885e8a9b44680a29ea1f03452878a',
     redirect: 'com.resideo.firstalert://login.resideo.com/ios/com.resideo.firstalert/callback',
     browserRedirect: 'https://login.resideo.com/ios/com.resideo.firstalert/callback',
     audience: 'https://resideo-prod.auth0.com/api/v2/', scope: 'openid profile email offline_access',
     authClient: 'eyJ2ZXJzaW9uIjoiMS4xNC4wIiwibmFtZSI6ImF1dGgwLWZsdXR0ZXIiLCJlbnYiOnsiY29yZSI6IjIuMTAuMCIsImlPUyI6IjI2LjEiLCJzd2lmdCI6IjUueCJ9fQ',
     browserClient: 'eyJuYW1lIjoiYXV0aDAuanMtdWxwIiwidmVyc2lvbiI6IjkuMTMuMiJ9',
     webUA: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)',
     appUA: 'First Alert/2440 CFNetwork/3860.600.12 Darwin/25.5.0',
     root: '/ris-public-api/api', channel: 'ds-notification-service', version: '0.1.5']
}

def mainPage() {
    Map pageOptions = [name: 'mainPage', title: 'Resideo / First Alert', install: true, uninstall: true]
    if (state.authUiAction || (state.authRequest && state.authRequest.step != 'refresh')) pageOptions.refreshInterval = 3
    dynamicPage(pageOptions) {
        section('Account') {
            paragraph authenticationBanner()
            input 'loginEmail', 'text', title: 'First Alert email', required: false, submitOnChange: true
            input 'loginPassword', 'password', title: 'Password (cleared after sign-in)', required: false, submitOnChange: true
            input 'signIn', 'button', title: 'Sign in with email and password'
            paragraph authenticationBanner()
            input 'browserSignIn', 'button', title: 'Start browser sign-in'
            if (state.browserAttempt) {
                paragraph '1. Open First Alert sign-in below and sign in on Resideo’s website. Keep this Hubitat page open.'
                href url: state.browserAttempt.url, style: 'external', title: 'Open First Alert sign-in', description: 'Opens the secure sign-in page in another tab'
                paragraph '2. When sign-in finishes, copy the full web address from the browser’s address bar. A blank page or “Cannot GET” message is expected on that final page. You can copy this address like any website address.'
                input 'browserRedirect', 'password', title: '3. Paste the final web address here', required: false, submitOnChange: true
                input 'finishBrowser', 'button', title: 'Connect my account'
                paragraph authenticationBanner()
                paragraph escapeHtml(browserLoginDeadline())
                paragraph 'After signing in, paste the final address and select Connect my account immediately. First Alert controls how quickly that result expires. If you see the sign-in form again, finish signing in before copying the address.'
                if (state.browserAttempt.redirectUri != constants().browserRedirect) paragraph 'This link was created by an older version. Choose Start browser sign-in again to use the simpler flow.'
            }
            input 'cancelLogin', 'button', title: 'Cancel sign-in'
        }
        section('Devices') {
            input 'discover', 'button', title: 'Discover / refresh account'
            Map options = (state.discovered ?: [:]).collectEntries { mac, d -> [(mac): "${d.locationName} — ${d.name}"] }
            if (options) input 'selectedThermostats', 'enum', title: 'Thermostats to include', options: options, multiple: true, required: false
            input 'applyDevices', 'button', title: 'Apply device selection'
            paragraph 'Room sensors are added automatically. Removed or deselected devices remain available for explicit removal below.'
            Map orphaned = getChildDevices().findAll { it.getDataValue('orphaned') == 'true' }.collectEntries { [(it.deviceNetworkId): it.displayName] }
            if (orphaned) {
                input 'removeDevices', 'enum', title: 'Unavailable devices to remove (check automations first)', options: orphaned, multiple: true, required: false
                input 'removeOrphans', 'button', title: 'Remove the selected unavailable devices'
            }
        }
        section('Operation') {
            input 'integrationEnabled', 'bool', title: 'Enable integration', defaultValue: true
            input 'fallbackMinutes', 'number', title: 'Polling interval during stream failure (1–30 minutes)', range: '1..30', defaultValue: 5
            input 'logEnable', 'bool', title: 'Status and warning logs', defaultValue: true
            input 'debugEnable', 'bool', title: 'Debug logging for 30 minutes', defaultValue: false
            paragraph escapeHtml(operationSummary())
            input 'showDiagnostics', 'button', title: 'Prepare redacted diagnostics'
            input 'clearDiagnostics', 'button', title: 'Clear diagnostic history and counters'
            input 'testSignInConnection', 'button', title: 'Check First Alert sign-in connection (no password needed)'
            paragraph authenticationBanner()
            if (state.diagnostics) paragraph '<pre>' + escapeHtml(state.diagnostics) + '</pre>'
        }
    }
}

String authenticationBanner() {
    String status = state.authStatus ?: 'Sign in to your First Alert account.'
    boolean failed = !!state.lastError || status in ['Sign-in failed', 'Sign-in expired', 'Authentication required']
    boolean good = !failed && (status == 'Connected' || status.startsWith('Connection check passed'))
    String color = failed ? '#b71c1c' : good ? '#146c2e' : '#805500'
    String background = failed ? '#ffebee' : good ? '#e8f5e9' : '#fff8e1'
    String label = failed ? 'Sign-in needs attention' : good ? (status == 'Connected' ? 'Account connected' : 'Connection check passed') : 'Sign-in status'
    '<div role="status" aria-live="polite" style="padding:12px;margin:8px 0;border:2px solid ' + color + ';border-radius:6px;background:' + background + ';color:' + color + '"><strong>' + label + ': ' + escapeHtml(status) + '</strong>' + (state.lastError ? '<br>' + escapeHtml(state.lastError) : '') + '</div>'
}

void installed() { initialize() }
void updated() {
    // Saving preferences must not erase a browser verifier or invalidate an
    // asynchronous password login that is still in progress.
    if (enabled() && signInInProgress()) { configureLogging(); scheduleLoginExpiry(); return }
    initialize()
}
boolean signInInProgress() {
    if (state.authUiAction) return true
    Map attempt = state.browserAttempt ?: state.loginAttempt
    attempt && now() - ((attempt.created ?: 0L) as long) < loginLifetime(attempt)
}
long loginLifetime(Map attempt) { attempt?.redirectUri == constants().browserRedirect ? 3600000L : 900000L }
String browserLoginDeadline() {
    Map attempt = state.browserAttempt
    if (!attempt) return ''
    long remaining = Math.max(0L, loginLifetime(attempt) - (now() - (attempt.created as long)))
    String deadline = new Date((attempt.created as long) + loginLifetime(attempt)).format('h:mm a z', location.timeZone)
    'Setup link expires at ' + deadline + ' (about ' + (Math.ceil(remaining / 60000.0d) as int) + ' minutes remaining). After signing in, connect the returned result immediately.'
}
void scheduleLoginExpiry() {
    Map attempt = state.browserAttempt ?: state.loginAttempt
    if (attempt) runIn(Math.max(1, Math.ceil((loginLifetime(attempt) - (now() - (attempt.created as long))) / 1000.0d) as int), 'expireLogin')
}
void uninstalled() { stopIntegration(true) }
void logsOff() {
    app.updateSetting('debugEnable', [value: 'false', type: 'bool'])
    state.debugUntil = null
    diagnostic('debugDisabled')
}
void configureLogging() {
    if (!settings.debugEnable) { state.debugUntil = null; return }
    if (!state.debugUntil) state.debugUntil = now() + 1800000L
    if ((state.debugUntil as long) <= now()) { logsOff(); return }
    runIn(Math.max(1, Math.ceil(((state.debugUntil as long) - now()) / 1000.0d) as int), 'logsOff')
}
boolean debugLogging() {
    if (settings.debugEnable != true) return false
    if (!state.debugUntil) configureLogging()
    state.debugUntil != null && now() < (state.debugUntil as long)
}

// Diagnostics deliberately use a fixed vocabulary rather than redacting arbitrary
// bodies after the fact. Unknown events, strings and keys never reach logs/export.
Map diagnosticLevels() {
    [initialized: 'info', debugDisabled: 'info', authUiQueued: 'debug', authUiProcessed: 'debug', authStarted: 'info', authConnected: 'info', authRequest: 'debug', authResponse: 'debug', authCookies: 'debug', authRedirect: 'debug', authCookieFallback: 'info', authConnectionReady: 'info',
     authFailure: 'warn', reauthRequired: 'warn', tokenRefreshStarted: 'debug', tokenRefreshSucceeded: 'info',
     tokenRefreshRateLimited: 'warn', requestSent: 'debug', requestCompleted: 'debug', requestFailed: 'warn',
     requestRetry: 'debug', rateLimited: 'warn', queueFull: 'warn', staleCallback: 'debug', supersededRead: 'debug',
     invalidResponse: 'warn', endpointUnsupported: 'info', discovered: 'info', deviceCreated: 'info',
     fallbackPoll: 'debug', streamStatus: 'info', streamFailure: 'warn', streamStage: 'debug', feedRenewal: 'debug',
     streamReady: 'info', streamDelta: 'debug', streamRefresh: 'debug', commandReceived: 'debug',
     commandRejected: 'warn', commandAccepted: 'debug', commandUncertain: 'warn', commandProgress: 'debug',
     commandConfirmed: 'info', commandUnconfirmed: 'warn', commandFailed: 'warn', deviceHealth: 'info']
}
Map diagnosticEnums() {
    [endpoint: ['accounts', 'shadow', 'rooms', 'configuration', 'priority', 'activation', 'negotiate', 'azureNegotiate', 'write'],
     method: ['GET', 'POST', 'PUT'], flow: ['browser', 'password', 'connectionCheck'], csrf: ['present', 'missing'], session: ['present', 'missing'],
     exception: ['missingMethod', 'missingProperty', 'security', 'invalidUri', 'other'], operation: ['resolve', 'getPath', 'getHost', 'getScheme', 'split', 'other'],
     route: ['login', 'universalLogin', 'authorize', 'callback', 'other'], step: ['authorize', 'loginPage', 'credentials', 'callback', 'resume', 'exchange', 'refresh'],
     command: ['setThermostatMode', 'setThermostatFanMode', 'setHeatingSetpoint', 'setCoolingSetpoint', 'setHold',
         'setScheduleEnabled', 'setFeelsLikeEnabled', 'setAdaptiveRecoveryEnabled', 'setEmergencyHeatEnabled',
         'setSetpointLimits', 'setFreezeProtectionLowLimit', 'setOccupancySensitivity', 'setExcludeMotion', 'setExcludeTemperature', 'setSchedule'],
     stage: ['stopped', 'negotiating', 'opening', 'handshake', 'subscribing', 'connected', 'backoff'],
     status: ['connecting', 'connected', 'disconnected', 'polling', 'disabled', 'authentication required', 'unavailable'],
     dataStatus: ['current', 'stale', 'offline', 'unavailable'], source: ['push', 'poll'],
     reason: ['timeout', 'transport', 'captcha', 'mfa', 'credentialsRejected', 'blocked', 'missingInput', 'invalidRedirect', 'expired',
         'unexpectedResponse', 'validationRejected', 'cloudRejected', 'negotiationFailed', 'socketOpenFailed',
         'socketDisconnected', 'oversizedFrame', 'invalidFrame', 'handshakeRejected', 'subscriptionRejected',
         'serverClosed', 'stalled', 'feedRenewal', 'sendFailed', 'setupTimeout', 'invalidNotification', 'invalidExpiry', 'staleExpiry',
         'rateLimited', 'queueFull', 'deviceUnavailable', 'deviceOffline', 'staleData', 'unsupportedControl', 'outsideLimits', 'deadband', 'scheduleDisabled', 'invalidNumber'],
     scale: ['F', 'C'],
     property: ['Setpoint', 'OperationStatus', 'SystemSwitch', 'FanSwitch', 'Sensor', 'Rooms', 'DisplayedIndoorTemperature',
         'DisplayedIndoorHumidity', 'DisplayedOutdoorTemperature', 'DisplayedOutdoorHumidity']]
}
Map diagnosticSettings(Map values) {
    Map enums = [thermostatMode: ['heat', 'cool', 'off', 'auto', 'emergency heat'], thermostatFanMode: ['auto', 'circulate', 'on'],
        holdStatus: ['NoHold', 'TemporaryHold', 'PermanentHold', 'VacationHold', 'TimedHold'],
        adaptiveRecoveryMode: ['None', 'AdaptiveIntelligentRecovery'], occupancySensitivity: ['Off', 'Low', 'Medium', 'High']]
    List toggles = ['scheduleEnabled', 'feelsLikeEnabled', 'adaptiveRecoveryEnabled', 'emergencyHeatEnabled', 'excludeMotion', 'excludeTemperature']
    List numeric = ['heatingSetpoint', 'coolingSetpoint', 'heatSetpointMin', 'heatSetpointMax', 'coolSetpointMin', 'coolSetpointMax', 'freezeProtectionLowLimit']
    values.findAll { k, v -> enums[k]?.contains(v) || (k in toggles && v in ['on', 'off']) || (k in numeric && v instanceof Number && v >= -100 && v <= 200) }
}
String diagnosticAlias(String kind, Object identity) {
    kind + '-' + MessageDigest.getInstance('SHA-256').digest((app.id + ':' + identity).getBytes('UTF-8')).encodeHex().toString().substring(0, 12)
}
String diagnosticDevice(String mac, Object aid = null) { diagnosticAlias('thermostat', mac) + (aid == null ? '' : '/' + diagnosticAlias('sensor', mac + ':' + aid)) }
long nextTrace() { state.traceSequence = ((state.traceSequence ?: 0L) as long) + 1L; state.traceSequence as long }
Map requestDiagnostic(Map r) {
    Map out = [request: r.trace, endpoint: r.kind, method: r.method, retry: r.retry ?: 0]
    if (r.mac) out.device = diagnosticDevice(r.mac as String, r.context?.accessoryId)
    if (r.context?.locationId) out.location = diagnosticAlias('location', r.context.locationId)
    out
}
void diagnostic(String event, Map details = [:]) {
    String level = diagnosticLevels()[event]
    if (!level) return
    Map safe = [:]; Map enums = diagnosticEnums()
    List numeric = ['request', 'http', 'elapsedMs', 'queued', 'active', 'retry', 'retrySeconds', 'generation', 'count', 'remaining', 'confirmed', 'unconfirmed', 'ageSeconds']
    details.each { k, v ->
        if (v == null) return
        if (k in numeric && v instanceof Number && v >= 0) safe[k] = v.longValue()
        else if (enums[k]?.contains(v)) safe[k] = v
        else if (k == 'device' && v instanceof String && v ==~ /thermostat-[a-f0-9]{12}(\/sensor-[a-f0-9]{12})?/) safe[k] = v
        else if (k == 'location' && v instanceof String && v ==~ /location-[a-f0-9]{12}/) safe[k] = v
        else if (k in ['requested', 'reported'] && v instanceof Map && debugLogging()) {
            Map fields = diagnosticSettings(v)
            if (fields) safe[k] = fields
        }
    }
    Map counters = state.diagnosticCounters ?: [:]; counters[event] = ((counters[event] ?: 0L) as long) + 1L; state.diagnosticCounters = counters
    if (level == 'debug' && !debugLogging()) return
    // Suppress repeated outage warnings, and cap debug output during a busy feed.
    Map window = state.diagnosticWindow ?: [start: now(), count: 0]
    if (now() - (window.start as long) >= 60000L) window = [start: now(), count: 0]
    if (level == 'debug') {
        window.count = (window.count as int) + 1; state.diagnosticWindow = window
        if (window.count > 120) {
            if (window.count == 121 && settings.logEnable != false) log.warn 'Resideo debug log limit reached; further debug entries suppressed for this minute.'
            return
        }
    } else {
        String key = event + ':' + JsonOutput.toJson(safe.findAll { k, v -> !(k in ['request', 'elapsedMs', 'queued', 'active', 'count', 'ageSeconds', 'generation', 'retry', 'retrySeconds', 'requested', 'reported']) })
        Map seen = (state.diagnosticSeen ?: [:]).findAll { k, v -> now() - (v as long) < 60000L }
        if (seen.containsKey(key)) return
        if (seen.size() >= 100) seen.remove(seen.keySet().first())
        seen[key] = now(); state.diagnosticSeen = seen
    }
    List recent = state.diagnosticEvents ?: []
    recent << ([at: Instant.ofEpochMilli(now()).toString(), event: event] + safe)
    state.diagnosticEvents = recent.takeRight(60)
    if (settings.logEnable == false) return
    String line = 'Resideo ' + event + (safe ? ' ' + JsonOutput.toJson(safe) : '')
    if (level == 'debug') log.debug line
    else if (level == 'warn') log.warn line
    else log.info line
}
String authFailureReason(String message) {
    String lower = message.toLowerCase()
    if (lower.contains('timed out')) return 'timeout'
    if (lower.contains('transport')) return 'transport'
    if (lower.contains('captcha')) return 'captcha'
    if (lower.contains('verification')) return 'mfa'
    if (lower.contains('credentials') || lower.contains('refresh token rejected')) return 'credentialsRejected'
    if (lower.contains('blocked')) return 'blocked'
    if (lower.contains('redirect')) return 'invalidRedirect'
    if (lower.contains('expired')) return 'expired'
    'unexpectedResponse'
}

void initialize() {
    boolean interruptedRefresh = state.refreshing == true
    stopIntegration(false)
    state.cache = state.cache ?: [:]
    state.discovered = state.discovered ?: [:]
    state.pending = state.pending ?: [:]
    state.streams = [:]
    state.lastCommand = state.lastCommand ?: [:]
    clearLogin()
    if (interruptedRefresh) requireReauth('The hub restarted during token refresh. Sign in again.')
    configureLogging()
    diagnostic('initialized', [generation: generation(), status: enabled() ? 'connecting' : 'disabled', count: selectedMacs().size()])
    subscribe(location, 'systemStart', 'systemStart')
    if (enabled() && state.tokens?.refresh_token) {
        state.authStatus = 'Connected'
        scheduleTokenRefresh()
        enqueueRead('accounts', null, '/ris-public-api/api/v1/accounts', [:])
        synchronizeSelection()
        if (state.pending) runIn(10, 'reconcileCommands')
        runIn(60, 'maintenance')
    }
}

void systemStart(evt) { initialize() }
boolean enabled() { settings.integrationEnabled != false }
int pollingMinutes() { Math.max(1, Math.min(30, (settings.fallbackMinutes ?: 5) as int)) }
long generation() { (state.generation ?: 0L) as long }

void stopIntegration(boolean deleting) {
    unschedule()
    unsubscribe()
    state.generation = generation() + 1L
    state.httpQueue = []
    state.inFlight = [:]
    state.writeBusy = [:]
    state.refreshing = false
    state.authRequest = null
    getChildDevices().findAll { it.getDataValue('kind') == 'connection' }.each { it.stopConnection() }
    clearLogin()
    if (deleting) { state.tokens = null; state.pending = [:] }
    if (!enabled()) getChildDevices().each { it.applyAttributes([connectionStatus: 'disabled', dataStatus: 'unavailable']) }
}

void appButtonHandler(String name) {
    switch (name) {
        case 'signIn': scheduleAuthUiAction('password'); break
        case 'browserSignIn': startBrowserLogin(); break
        case 'finishBrowser': scheduleAuthUiAction('browser'); break
        case 'cancelLogin': clearLogin(); state.lastError = null; state.authStatus = state.tokens ? 'Connected' : 'Sign-in cancelled'; break
        case 'discover': enqueueRead('accounts', null, constants().root + '/v1/accounts', [:]); break
        case 'applyDevices': synchronizeSelection(); break
        case 'removeOrphans':
            asList(settings.removeDevices).each { dni ->
                def d = getChildDevice(dni.toString())
                if (d?.getDataValue('orphaned') == 'true') deleteChildDevice(dni.toString())
            }
            app.removeSetting('removeDevices'); break
        case 'showDiagnostics': state.diagnostics = JsonOutput.prettyPrint(JsonOutput.toJson(diagnosticSnapshot())); break
        case 'clearDiagnostics':
            state.diagnostics = null; state.diagnosticEvents = []; state.diagnosticCounters = [:]
            state.diagnosticSeen = [:]; state.diagnosticWindow = null
            break
        case 'testSignInConnection': testSignInConnection(); break
    }
}

// Driver entrypoints only schedule messages. All shared state changes occur in this
// app's serialized scheduled handlers / HTTP callbacks, never in a child execution.
void postMessage(Map msg) { runInMillis(1, 'processMessage', [overwrite: false, data: [accountGeneration: generation()] + msg]) }
void processMessage(Map msg) {
    if (!enabled()) return
    if (msg.accountGeneration != null && (msg.accountGeneration as long) != generation()) { diagnostic('staleCallback'); return }
    switch (msg.kind) {
        case 'command': acceptCommand(msg); break
        case 'refresh': refreshDevice(msg.mac as String, true); break
        case 'streamRequest': negotiate(msg.locationId as String, msg.connectionGeneration as long); break
        case 'streamStatus': streamStatus(msg); break
        case 'streamEvent': handleStreamEvent(msg); break
        case 'streamActivated': activateStream(msg); break
        case 'streamDiagnostic':
            if (currentConnection(msg)) diagnostic('streamStage', [location: diagnosticAlias('location', msg.locationId), stage: msg.stage, reason: msg.reason, generation: msg.connectionGeneration])
            break
        case 'driverRejected': diagnostic('commandRejected', [device: diagnosticDevice(msg.mac as String, msg.accessoryId), command: msg.command, reason: 'validationRejected']); break
        case 'streamInitialize':
            if (state.tokens && locationMacs(msg.locationId as String)) getChildDevice(connectionId(msg.locationId as String))?.startConnection(generation())
            break
    }
}

Exception validationError(String message) { new Exception('Resideo validation: ' + message) }
String validationMessage(Exception error, String fallback) {
    String value = error.message ?: ''
    value.startsWith('Resideo validation: ') ? value.substring(20) : fallback
}

// -- Auth0 / PKCE -----------------------------------------------------------
// UUID.randomUUID is allowlisted by Hubitat and uses the JVM secure generator.
// Three UUIDs provide 366 random bits; 96 hex characters fit PKCE's 43–128 limit.
String randomToken() { UUID.randomUUID().toString().replace('-', '') + UUID.randomUUID().toString().replace('-', '') + UUID.randomUUID().toString().replace('-', '') }
String base64url(byte[] b) { b.encodeBase64().toString().replace('+', '-').replace('/', '_').replaceAll('=+$', '') }
String encode(Object v) { java.net.URLEncoder.encode(v.toString(), 'UTF-8') }
String queryString(Map q) { q.collect { k, v -> encode(k) + '=' + encode(v) }.join('&') }
Map newAuthorization(String redirectUri = constants().redirect) {
    String verifier = randomToken()
    String nonce = randomToken()
    Map c = constants()
    Map query = [state: nonce, scope: c.scope, signUpUrl: 'https://myid.resideo.com/sign-up?userType=consumer',
        client_id: c.client, code_challenge_method: 'S256', response_type: 'code', max_age: '0',
        audience: c.audience, redirect_uri: redirectUri,
        code_challenge: base64url(MessageDigest.getInstance('SHA-256').digest(verifier.getBytes('US-ASCII'))),
        prompt: 'login', auth0Client: c.authClient]
    [url: c.auth + '/authorize?' + queryString(query), verifier: verifier, nonce: nonce, created: now(), redirectUri: redirectUri]
}

void scheduleAuthUiAction(String flow) {
    // Hubitat calls the button handler before persisting that button's form.
    // Defer reading settings; submitOnChange also saves fields on blur.
    if (state.authRequest || state.authUiAction) return
    Map action = [id: randomToken(), generation: generation(), flow: flow]
    state.authUiAction = action
    state.lastError = null
    state.authStatus = 'Saving sign-in details'
    diagnostic('authUiQueued', [flow: flow])
    runIn(1, 'performAuthUiAction', [data: action])
}
void performAuthUiAction(Map action) {
    if (!state.authUiAction || state.authUiAction.id != action.id || action.generation != generation() || !enabled()) return
    state.authUiAction = null
    diagnostic('authUiProcessed', [flow: action.flow, count: action.flow == 'browser' ? (settings.browserRedirect ? 1 : 0) : (settings.loginEmail ? 1 : 0) + (settings.loginPassword ? 1 : 0)])
    if (action.flow == 'password') startPasswordLogin()
    else if (action.flow == 'browser') finishBrowserLogin()
}

void startBrowserLogin() {
    if (state.refreshing) { state.lastError = 'Wait for token refresh to complete before signing in.'; return }
    clearLogin()
    state.lastError = null
    state.diagnostics = null
    state.browserAttempt = newAuthorization(constants().browserRedirect as String)
    diagnostic('authStarted', [flow: 'browser'])
    state.authStatus = 'Waiting for browser sign-in'
    scheduleLoginExpiry()
}

void finishBrowserLogin() {
    Map attempt = state.browserAttempt
    Map q = [:]
    String pasted = settings.browserRedirect?.toString()?.trim()
    try {
        if (!attempt || now() - (attempt.created as long) >= loginLifetime(attempt)) throw validationError('Browser sign-in expired. Start again.')
        q = parseQuery(pasted)
        if (q.error) throw validationError('Browser sign-in was rejected. Start again.')
        if (!q.code) throw validationError('Sign in on First Alert first, then copy the final web address from the address bar and paste it here.')
        if (q.state != attempt.nonce) throw validationError('This address is from a different sign-in attempt. Use the current sign-in link and paste its final address.')
        String redirectUri = (attempt.redirectUri ?: constants().redirect) as String
        if (!pasted.startsWith(redirectUri + '?')) throw validationError('Paste the full final First Alert web address from the browser’s address bar.')
        state.lastError = null; state.authStatus = 'Connecting your account'
        exchangeCode(q.code as String, attempt.verifier as String, redirectUri)
    } catch (Exception e) {
        state.lastError = validationMessage(e, 'Invalid browser redirect. Start sign-in again.')
        diagnostic('authFailure', [flow: 'browser', reason: authFailureReason(state.lastError as String)])
        if (!attempt || now() - (attempt.created as long) >= loginLifetime(attempt) || q.error) clearLogin()
        state.authStatus = state.browserAttempt ? 'Check the pasted address and try again' : state.tokens ? 'Connected; browser sign-in failed' : 'Start browser sign-in again'
    }
    app.removeSetting('browserRedirect')
}

void exchangeCode(String code, String verifier, String redirectUri = constants().redirect) {
    Map c = constants()
    authHttp('exchange', 'POST', c.auth + '/oauth/token',
        [client_id: c.client, code: code, redirect_uri: redirectUri, code_verifier: verifier, grant_type: 'authorization_code'],
        ['Auth0-Client': c.authClient, 'Content-Type': 'application/json'])
}

Map parseQuery(String url) {
    if (!url || !url.contains('?')) return [:]
    String query = url.substring(url.indexOf('?') + 1).split('#', 2)[0]
    Map out = [:]
    query.split('&').each { pair ->
        String[] p = pair.split('=', 2)
        String key = java.net.URLDecoder.decode(p[0], 'UTF-8')
        if (out.containsKey(key)) throw validationError('Duplicate redirect parameters are not accepted.')
        out[key] = p.length > 1 ? java.net.URLDecoder.decode(p[1], 'UTF-8') : ''
    }
    out
}

void startPasswordLogin() {
    if (state.refreshing) { state.lastError = 'Wait for token refresh to complete before signing in.'; return }
    if (!settings.loginEmail || !settings.loginPassword) {
        state.authStatus = 'Enter your sign-in details'; state.lastError = 'Enter your First Alert email and password.'
        diagnostic('authFailure', [flow: 'password', reason: 'missingInput']); return
    }
    clearLogin(false)
    state.lastError = null
    state.diagnostics = null
    Map a = newAuthorization()
    state.loginAttempt = a + [cookies: [:]]
    state.authStatus = 'Signing in'
    diagnostic('authStarted', [flow: 'password'])
    scheduleLoginExpiry()
    authHttp('authorize', 'GET', a.url as String, null, [:])
}

void testSignInConnection() {
    if (signInInProgress() || state.refreshing) { state.lastError = 'Finish or cancel the current sign-in attempt before checking the connection.'; return }
    clearLogin()
    state.diagnostics = null
    state.lastError = null; state.authStatus = 'Checking First Alert sign-in connection'
    Map a = newAuthorization()
    state.loginAttempt = a + [cookies: [:], connectionCheck: true]
    diagnostic('authStarted', [flow: 'connectionCheck'])
    scheduleLoginExpiry()
    authHttp('authorize', 'GET', a.url as String, null, [:])
}
void expireLogin() {
    diagnostic('authFailure', [reason: 'expired']); clearLogin()
    state.authStatus = state.tokens ? 'Connected' : 'Sign-in expired'
    state.lastError = 'Sign-in expired. Start again.'
}
void clearLogin(boolean clearPassword = true) {
    state.authUiAction = null
    unschedule('performAuthUiAction')
    state.browserAttempt = null
    state.loginAttempt = null
    if (state.authRequest?.step != 'refresh') state.authRequest = null
    if (clearPassword) app.removeSetting('loginPassword')
    app.removeSetting('browserRedirect')
    unschedule('expireLogin')
}

void authHttp(String step, String method, String url, Object body, Map extraHeaders) {
    Map c = constants()
    Map headers = ['User-Agent': c.webUA] + extraHeaders
    if (state.loginAttempt?.cookies && !(step in ['exchange', 'refresh'])) headers.Cookie = state.loginAttempt.cookies.collect { k, v -> k + '=' + v }.join('; ')
    Map req = [step: step, id: randomToken(), generation: generation(), trace: nextTrace(), sentAt: now()]
    state.authRequest = req
    diagnostic('authRequest', [request: req.trace, step: step])
    Map params = [uri: url, headers: headers, timeout: 30, followRedirects: false, contentType: 'text/plain']
    if (body != null) {
        params.body = body instanceof Map ? JsonOutput.toJson(body) : body
        params.requestContentType = extraHeaders['Content-Type'] ?: 'application/json'
    }
    runIn(35, 'authTimeout', [data: [id: req.id, generation: req.generation, step: step]])
    if (state.loginAttempt?.nativeCookies && step in ['authorize', 'loginPage', 'callback', 'resume']) {
        // AsyncResponse's Map cannot retain duplicate Set-Cookie headers on
        // some hubs. Run one short native request per scheduled execution;
        // its header decorator exposes every cookie. No custom threads.
        params.timeout = 10
        state.authRequest = req + [wire: params, method: method]
        runInMillis(1, 'performCookieAuth', [data: [id: req.id, generation: req.generation]])
        return
    }
    try {
        if (method == 'GET') asynchttpGet('authResponse', params, req)
        else asynchttpPost('authResponse', params, req)
    } catch (Exception ignored) { authFailure('Transport error during sign-in. Try browser sign-in.', step == 'refresh') }
}
void performCookieAuth(Map ctx) {
    Map req = state.authRequest
    if (!req || req.id != ctx.id || ctx.generation != generation() || !enabled()) return
    try {
        if (req.method == 'GET') httpGet(req.wire as Map) { resp -> handleAuthResponse(resp.getStatus() as int, '', nativeAuthHeaders(resp), req) }
        else httpPost(req.wire as Map) { resp -> handleAuthResponse(resp.getStatus() as int, '', nativeAuthHeaders(resp), req) }
    } catch (Exception ignored) {
        if (state.authRequest?.id == req.id) authFailure('Transport error during sign-in. Retry or use browser sign-in.', false)
    }
}
Map nativeAuthHeaders(resp) {
    Map out = [:]
    def headers
    // getHeaders() on Hubitat's decorator can itself be a flattened Map.
    // The HTTP response's getAllHeaders() retains repeated Set-Cookie fields.
    try { headers = resp.getAllHeaders() } catch (Exception ignored) { headers = resp.getHeaders() }
    if (headers instanceof Map) {
        for (entry in headers.entrySet()) if (entry.key != null) out[entry.key.toString()] = entry.value
    } else {
        for (header in headers) {
            String name = header.name.toString().toLowerCase()
            List values = asList(out[name]); values << header.value.toString(); out[name] = values
        }
    }
    out
}
boolean useNativeCookieFallback(Map attempt) {
    if (attempt.nativeCookies) return false
    attempt.nativeCookies = true; attempt.cookies = [:]; attempt.loginRedirects = 0; attempt.authState = null
    state.loginAttempt = attempt
    diagnostic('authCookieFallback')
    authHttp('authorize', 'GET', attempt.url as String, null, [:])
    true
}

void authTimeout(Map req) {
    if (state.authRequest?.id == req.id && req.generation == generation())
        authFailure(req.step == 'refresh' ? 'Token refresh timed out. Sign in again.' : 'Sign-in timed out. Try browser sign-in.', req.step == 'refresh')
}

void authResponse(resp, Map req) {
    handleAuthResponse(responseStatus(resp), responseText(resp), responseHeaders(resp), req)
}
void handleAuthResponse(int status, String text, Map headers, Map req) {
    if (req.generation != generation() || state.authRequest?.id != req.id) { diagnostic('staleCallback'); return }
    unschedule('authTimeout')
    String step = req.step
    diagnostic('authResponse', [request: req.trace, step: step, http: status, elapsedMs: now() - ((req.sentAt ?: now()) as long)])
    Map a = state.loginAttempt ?: [:]
    if (status <= 0) { authFailure('Transport error during authentication. Sign in again.', step == 'refresh'); return }
    try {
        if (a) {
            a.cookies = collectCookies(a.cookies ?: [:], headers); state.loginAttempt = a
            diagnostic('authCookies', [step: step, count: a.cookies.size(), csrf: a.cookies._csrf ? 'present' : 'missing', session: a.cookies.auth0 || a.cookies.auth0_compat ? 'present' : 'missing'])
        }
        Map c = constants()
        String redirect = headerValue(headers, 'Location')
        switch (step) {
            case 'authorize':
                if (status != 302) throw validationError('Resideo did not start sign-in. Try browser sign-in.')
                a.authState = parseQuery(redirect).state
                if (!a.authState) throw validationError('Missing sign-in state. Try browser sign-in.')
                state.loginAttempt = a
                authHttp('loginPage', 'GET', c.auth + '/login?state=' + encode(a.authState), null, [:]); return
            case 'loginPage':
                if (status in [301, 302, 303, 307, 308]) {
                    if (!redirect) throw validationError('Missing sign-in redirect. Use browser sign-in.')
                    if (((a.loginRedirects ?: 0) as int) >= 5) {
                        if (useNativeCookieFallback(a)) return
                        throw validationError('Resideo sign-in redirected too many times. Use browser sign-in.')
                    }
                    String next = trustedAuthRedirect(redirect)
                    a.loginRedirects = ((a.loginRedirects ?: 0) as int) + 1
                    String path = authPath(next)
                    if (path == '/login' && parseQuery(next).state) a.authState = parseQuery(next).state
                    state.loginAttempt = a
                    diagnostic('authRedirect', [step: step, route: authRoute(path), count: a.loginRedirects])
                    authHttp('loginPage', 'GET', next, null, [:]); return
                }
                if (status == 200 && !a.cookies?._csrf && useNativeCookieFallback(a)) return
                if (status != 200 || !a.cookies?._csrf) throw validationError('Resideo login changed. Try browser sign-in.')
                if (a.connectionCheck) {
                    diagnostic('authConnectionReady', [count: a.cookies.size(), csrf: 'present'])
                    clearLogin(); state.authStatus = state.tokens ? 'Connected' : 'Connection check passed. Ready to sign in.'; state.lastError = null
                    return
                }
                Map credentials = [client_id: c.client, redirect_uri: c.redirect, tenant: 'resideo-prod',
                    response_type: 'code', scope: c.scope, audience: c.audience, _csrf: a.cookies._csrf,
                    state: a.authState, _intstate: 'deprecated', username: settings.loginEmail,
                    password: settings.loginPassword, connection: 'Username-Password-Authentication']
                authHttp('credentials', 'POST', c.auth + '/usernamepassword/login', credentials,
                    ['Auth0-Client': c.browserClient, Origin: c.auth, 'Content-Type': 'application/json']); return
            case 'credentials':
                Map error = parseJsonMap(text)
                if (status != 200 || error.code || error.error || error.name) throw validationError(authenticationError(error))
                String result = htmlInput(text, 'wresult')
                String context = htmlInput(text, 'wctx')
                if (!result) throw validationError('Unsupported login response. Try browser sign-in.')
                String form = queryString([wa: 'wsignin1.0', wresult: result] + (context ? [wctx: context] : [:]))
                authHttp('callback', 'POST', c.auth + '/login/callback', form,
                    [Origin: c.auth, 'Content-Type': 'application/x-www-form-urlencoded']); return
            case 'callback':
                if (status != 302 || !redirect) throw validationError('Login callback failed. Try browser sign-in.')
                String resume = redirect.startsWith('/') ? c.auth + redirect : redirect
                if (!resume.startsWith(c.auth + '/')) throw validationError('Unexpected login redirect. Try browser sign-in.')
                authHttp('resume', 'GET', resume, null, [:]); return
            case 'resume':
                Map q = parseQuery(redirect)
                if (status != 302 || !q.code || q.state != a.nonce) throw validationError('Invalid sign-in redirect. Try browser sign-in.')
                exchangeCode(q.code as String, a.verifier as String); return
            case 'exchange':
            case 'refresh':
                Map tokens = parseJsonMap(text)
                if (status != 200 || !tokens.access_token || (step == 'exchange' && !tokens.refresh_token)) {
                    if (step == 'refresh' && status == 429) {
                        state.refreshing = false; state.authRequest = null
                        atomicState.refreshing = false
                        state.cooldownUntil = now() + retryDelay(headers) * 1000L
                        diagnostic('tokenRefreshRateLimited', [http: status, retrySeconds: retryDelay(headers)])
                        runIn(retryDelay(headers), 'refreshTokens'); return
                    }
                    throw validationError(authenticationError(tokens, step))
                }
                state.tokens = [access_token: tokens.access_token,
                    refresh_token: tokens.refresh_token ?: state.tokens?.refresh_token,
                    expiresAt: now() + ((tokens.expires_in ?: 3600) as long) * 1000L]
                // state is committed when this callback returns. Rotation must
                // already be durable before queued API requests are released.
                atomicState.tokens = state.tokens
                state.refreshing = false; state.authRequest = null
                atomicState.refreshing = false
                clearLogin(); state.lastError = null; state.authStatus = 'Connected'
                if (step == 'refresh') diagnostic('tokenRefreshSucceeded', [request: req.trace, elapsedMs: now() - (req.sentAt as long)])
                scheduleTokenRefresh()
                if (step == 'exchange') {
                    diagnostic('authConnected')
                    state.generation = generation() + 1L
                    state.inFlight = [:]; state.httpQueue = []; state.writeBusy = [:]
                    state.discovered = [:]; state.cache = [:]; state.pending = [:]; state.uncertainWrites = [:]
                    getChildDevices().each { it.updateDataValue('orphaned', 'true'); it.applyAttributes([dataStatus: 'unavailable']) }
                    getChildDevices().findAll { it.getDataValue('kind') == 'connection' }.each { it.stopConnection() }
                    enqueueRead('accounts', null, c.root + '/v1/accounts', [:])
                    runIn(60, 'maintenance')
                }
                pump(); return
        }
    } catch (Exception e) {
        diagnostic('authFailure', [step: step, exception: e instanceof MissingMethodException ? 'missingMethod' : e instanceof MissingPropertyException ? 'missingProperty' : e instanceof SecurityException ? 'security' : e instanceof java.net.URISyntaxException ? 'invalidUri' : 'other', operation: e instanceof MissingMethodException && e.method in ['resolve', 'getPath', 'getHost', 'getScheme', 'split'] ? e.method : 'other'])
        authFailure(validationMessage(e, 'Unexpected authentication response. Try browser sign-in.'), step == 'refresh')
    }
}
String trustedAuthRedirect(String value) {
    // java.net.URI is rejected by some Hubitat sandbox versions. Only the
    // known HTTPS origin and root-relative redirects are needed by Auth0.
    String target = value?.startsWith('/') && !value.startsWith('//') ? constants().auth + value : value
    if (!target || target.contains('#') || target.contains('\\') || !(target ==~ /(?i)^https:\/\/login\.resideo\.com(?::443)?(?:[\/?][^\s]*)?$/))
        throw validationError('Unexpected sign-in redirect. Use browser sign-in.')
    target
}
String authPath(String value) {
    value.replaceFirst('(?i)^https://login\\.resideo\\.com(?::443)?', '').split('[?#]', 2)[0]
}
String authRoute(String path) {
    path == '/login' ? 'login' : path?.startsWith('/u/login') ? 'universalLogin' : path?.startsWith('/authorize') ? 'authorize' : path == '/login/callback' ? 'callback' : 'other'
}

String authenticationError(Map error, String step = null) {
    String code = (error.code ?: error.error ?: error.name ?: '').toString()
    if (code == 'invalid_grant' && step == 'exchange') return 'This sign-in result expired or was already used. Start browser sign-in again and paste the final address immediately after signing in.'
    if (code == 'invalid_grant' && step == 'refresh') return 'Your First Alert session ended. Sign in again.'
    if (code in ['invalid_user_password', 'invalid_grant', 'invalid_credentials']) return 'Credentials or refresh token rejected. Sign in again with your First Alert account.'
    if (code.toLowerCase().contains('captcha')) return 'Resideo requires a CAPTCHA. Use browser sign-in.'
    if (code.toLowerCase().contains('mfa')) return 'Resideo requires additional verification. Use browser sign-in.'
    if (code in ['too_many_attempts', 'blocked_user']) return 'Resideo blocked this sign-in attempt. Wait and use browser sign-in.'
    'Resideo rejected authentication. Try browser sign-in.'
}

void authFailure(String message, boolean refresh) {
    diagnostic('authFailure', [step: state.authRequest?.step, reason: authFailureReason(message)])
    state.authRequest = null; state.refreshing = false
    clearLogin()
    state.lastError = message
    if (refresh) requireReauth(message)
    else state.authStatus = state.tokens ? 'Connected; sign-in attempt failed' : 'Sign-in failed'
}
void requireReauth(String message) {
    diagnostic('reauthRequired', [reason: authFailureReason(message)])
    state.tokens = null; state.refreshing = false; state.authRequest = null
    state.authStatus = 'Reauthentication required'; state.lastError = message
    state.httpQueue = []; state.inFlight = [:]; state.writeBusy = [:]
    unschedule('refreshTokens')
    getChildDevices().findAll { it.getDataValue('kind') == 'connection' }.each { it.stopConnection() }
    getChildDevices().each { it.applyAttributes([connectionStatus: 'authentication required', dataStatus: 'unavailable']) }
}
void scheduleTokenRefresh() {
    long delay = Math.max(1L, ((((state.tokens.expiresAt as long) - now()) / 1000L) as long) - 300L)
    runIn(delay, 'refreshTokens')
}
boolean freshToken() { state.tokens?.access_token && ((state.tokens.expiresAt ?: 0) as long) - now() > 300000L }
void refreshTokens() {
    if (!enabled() || state.refreshing || !state.tokens?.refresh_token) return
    if (state.loginAttempt || state.browserAttempt || state.authRequest) { runIn(60, 'refreshTokens'); return }
    if (((state.cooldownUntil ?: 0L) as long) > now()) { runIn(remainingCooldown(), 'refreshTokens'); return }
    state.refreshing = true
    // Record the refresh intent before transmitting a possibly single-use token.
    atomicState.refreshing = true
    diagnostic('tokenRefreshStarted', [queued: (state.httpQueue ?: []).size()])
    authHttp('refresh', 'POST', constants().auth + '/oauth/token',
        [grant_type: 'refresh_token', refresh_token: state.tokens.refresh_token, client_id: constants().client],
        ['Content-Type': 'application/json'])
}
Map collectCookies(Map current, Map headers) {
    Map out = [:] + current
    // Hubitat's header object can return a List from findAll even though it
    // implements Map. Iterate values directly rather than calling .values().
    headerValues(headers, 'Set-Cookie').each { raw ->
        if (raw != null) {
            raw.toString().split(/,(?=\s*[^;,\s]+=)/).each { cookie ->
                String pair = cookie.trim().split(';', 2)[0]
                String[] p = pair.split('=', 2)
                if (p.length == 2 && p[0].trim()) out[p[0].trim()] = p[1]
            }
        }
    }
    out
}
String htmlInput(String html, String name) {
    def tags = html =~ /(?is)<input\b[^>]*>/
    for (int i = 0; i < tags.count; i++) {
        String tag = tags[i]
        def n = tag =~ /(?is)\bname\s*=\s*['"]([^'"]*)['"]/
        def v = tag =~ /(?is)\bvalue\s*=\s*(['"])(.*?)\1/
        if (n.find() && n.group(1) == name && v.find()) return unescapeHtml(v.group(2))
    }
    null
}
String unescapeHtml(String s) {
    String out = s.replace('&quot;', '"').replace('&#39;', "'").replace('&apos;', "'").replace('&lt;', '<').replace('&gt;', '>')
    out = out.replaceAll(/&#(x[0-9a-fA-F]+|[0-9]+);/) { full, code ->
        int value = code.startsWith('x') ? Integer.parseInt(code.substring(1), 16) : Integer.parseInt(code)
        new String(Character.toChars(value))
    }
    out.replace('&amp;', '&')
}
String escapeHtml(Object s) { s.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;').replace("'", '&#39;') }

// -- Network queue ---------------------------------------------------------
String thermostatPath(String mac, int v = 2) { constants().root + '/v' + v + '/devices/thermostats/' + encode(mac) }
void enqueueRead(String kind, String mac, String path, Map context) {
    enqueueRequest([kind: kind, mac: mac, method: 'GET', path: path, context: context])
}
void enqueueRequest(Map r) {
    if (!enabled()) return
    List queue = state.httpQueue ?: []
    Map inflight = state.inFlight ?: [:]
    if (r.method == 'GET') {
        Map queued = queue.find { it.kind == r.kind && it.mac == r.mac && it.path == r.path }
        if (queued) { queued.context = (queued.context ?: [:]) + (r.context ?: [:]); state.httpQueue = queue; return }
        Map running = inflight.values().find { it.kind == r.kind && it.mac == r.mac && it.path == r.path }
        if (running) {
            // A post-write read must not be coalesced into a pre-write response.
            boolean newBootstrap = r.context?.bootstrapGeneration != null &&
                (r.context.bootstrapGeneration != running.context?.bootstrapGeneration || r.context.bootstrapLocation != running.context?.bootstrapLocation)
            if (newBootstrap || (r.context?.reconcile && ((running.sentAt ?: 0L) as long) < now())) {
                queue << (r + [id: randomToken(), generation: generation(), retry: 0])
                state.httpQueue = queue
            }
            return
        }
    }
    if (queue.size() >= 100) {
        diagnostic('queueFull', [queued: queue.size()])
        state.lastError = 'Request queue is full. Try again after pending work completes.'
        if (r.kind == 'write') rejectCommand(r.context as Map, state.lastError as String)
        return
    }
    r = r + [id: randomToken(), generation: generation(), retry: r.retry ?: 0]
    queue << r; state.httpQueue = queue
    pump()
}
int remainingCooldown() { Math.max(1, Math.ceil((((state.cooldownUntil ?: 0L) as long) - now()) / 1000.0d) as int) }

void pump() {
    if (!enabled() || !state.tokens?.refresh_token) return
    if (((state.cooldownUntil ?: 0L) as long) > now()) { runIn(remainingCooldown(), 'pump'); return }
    List queue = state.httpQueue ?: []
    if (!queue) return
    if (!freshToken()) { refreshTokens(); return }
    Map inflight = state.inFlight ?: [:]
    while (inflight.size() < 2 && queue) {
        if (((state.cooldownUntil ?: 0L) as long) > now()) { runIn(remainingCooldown(), 'pump'); break }
        Map busy = state.writeBusy ?: [:]
        int index = queue.findIndexOf { it.kind != 'write' || (!busy[it.mac] && !writeBlocked(it)) }
        if (index < 0) break
        Map r = queue.remove(index)
        if (r.generation != generation()) continue
        if (r.kind in ['negotiate', 'azureNegotiate', 'activation'] && !currentConnection(r.context as Map)) continue
        if (r.kind == 'write') {
            try { r = r + buildCommand(r.context as Map) }
            catch (Exception e) { rejectCommand(r.context as Map, validationMessage(e, 'Could not validate command. Refresh and try again.')); continue }
            busy[r.mac] = r.id; state.writeBusy = busy
        }
        r.sentAt = now()
        if (!r.trace) r.trace = nextTrace()
        if (r.mac && r.method == 'GET') {
            String field = r.kind == 'activation' ? 'priority' : r.kind
            r.readRevision = (((state.cache ?: [:])[r.mac]?.revisions ?: [:])[field] ?: 0L) as long
        }
        inflight[r.id] = r; state.inFlight = inflight; state.httpQueue = queue
        diagnostic('requestSent', requestDiagnostic(r) + [queued: queue.size(), active: inflight.size()])
        Map headers = ['User-Agent': constants().appUA, Accept: 'application/json']
        if (r.azureToken) headers.Authorization = 'Bearer ' + r.azureToken
        else { headers.Authorization = 'Bearer ' + state.tokens.access_token; headers['Ocp-Apim-Subscription-Key'] = constants().subscription }
        Map params = [uri: r.url ?: constants().base + r.path, headers: headers, timeout: 30, followRedirects: false, contentType: 'application/json']
        if (r.body != null) { params.body = JsonOutput.toJson(r.body); params.requestContentType = 'application/json' }
        else if (r.method == 'POST') params.body = ''
        runIn(35, 'requestTimeout', [overwrite: false, data: [id: r.id, generation: r.generation]])
        try {
            switch (r.method) {
                case 'GET': asynchttpGet('apiResponse', params, [id: r.id, generation: r.generation]); break
                case 'POST': asynchttpPost('apiResponse', params, [id: r.id, generation: r.generation]); break
                case 'PUT': asynchttpPut('apiResponse', params, [id: r.id, generation: r.generation]); break
            }
        } catch (Exception ignored) { completeRequest(r.id as String, 0, [:], [:], true) }
        inflight = state.inFlight ?: [:]; queue = state.httpQueue ?: []
    }
    state.httpQueue = queue
}
void apiResponse(resp, Map ctx) {
    if (ctx.generation != generation() || !(state.inFlight ?: [:]).containsKey(ctx.id)) { diagnostic('staleCallback'); return }
    completeRequest(ctx.id as String, responseStatus(resp), parseJsonMap(responseText(resp)), responseHeaders(resp), false)
}
void requestTimeout(Map ctx) {
    if (ctx.generation == generation() && (state.inFlight ?: [:]).containsKey(ctx.id)) completeRequest(ctx.id as String, 0, [:], [:], true)
}
void completeRequest(String id, int status, Map data, Map headers, boolean uncertain) {
    Map inflight = state.inFlight ?: [:]
    Map r = inflight.remove(id)
    state.inFlight = inflight
    if (!r) return
    Map detail = requestDiagnostic(r) + [http: status, elapsedMs: now() - ((r.sentAt ?: now()) as long)]
    diagnostic('requestCompleted', detail)
    if (r.kind == 'write') { Map busy = state.writeBusy ?: [:]; busy.remove(r.mac); state.writeBusy = busy }
    if (status == 401 && !r.azureToken && (r.retry as int) == 0) {
        state.tokens = (state.tokens ?: [:]) + [expiresAt: 0L]
        List queue = state.httpQueue ?: []; queue.add(0, r + [retry: 1]); state.httpQueue = queue
        diagnostic('requestRetry', detail + [retry: 1])
        refreshTokens(); return
    }
    if (status == 401 && !r.azureToken) { requireReauth('Resideo rejected this account. Sign in again.'); return }
    if (status == 429) {
        state.cooldownUntil = now() + retryDelay(headers) * 1000L
        diagnostic('rateLimited', detail + [retrySeconds: retryDelay(headers)])
        if (r.kind != 'write') { List queue = state.httpQueue ?: []; queue << r; state.httpQueue = queue }
        else rejectCommand(r.context as Map, 'Resideo rate limit reached. Retry after the cooldown.')
    } else if (status >= 200 && status < 300) {
        state.failureCount = 0
        handleSuccess(r, data)
    } else if (status == 404 && r.kind in ['rooms', 'configuration', 'priority', 'activation']) {
        recordUnsupported(r)
    } else {
        if (status == 0 || status >= 500) {
            int failures = Math.min(6, ((state.failureCount ?: 0) as int) + 1)
            state.failureCount = failures
            state.cooldownUntil = now() + Math.min(900L, 30L * (1L << (failures - 1))) * 1000L
        }
        state.lastError = status == 0 ? 'Cloud request timed out or could not connect.' : 'Resideo HTTP ' + status + ': ' + safeServiceMessage(data)
        diagnostic('requestFailed', detail + [reason: status == 0 ? 'transport' : 'cloudRejected', retrySeconds: remainingCooldown()])
        if (r.kind == 'write') ambiguousWrite(r, status == 0 || status >= 500 || uncertain)
        else if (r.kind in ['negotiate', 'azureNegotiate']) streamFailed(r.context as Map)
        else if (r.mac) publishDevice(r.mac as String)
        if (r.method == 'GET' && (status == 0 || status >= 500)) {
            diagnostic('requestRetry', detail + [retry: (r.retry as int) + 1, retrySeconds: remainingCooldown()])
            List queue = state.httpQueue ?: []; queue << (r + [retry: (r.retry as int) + 1]); state.httpQueue = queue
        }
    }
    pump()
}

int retryDelay(Map headers) {
    String value = headerValue(headers, 'Retry-After')
    if (value?.isInteger()) return Math.max(1, Math.min(86400, value as int))
    if (value) {
        try { return Math.max(1, Math.min(86400, Math.ceil((Date.parse('EEE, dd MMM yyyy HH:mm:ss z', value).time - now()) / 1000.0d) as int)) }
        catch (Exception ignored) { }
    }
    60
}
void handleSuccess(Map r, Map data) {
    switch (r.kind) {
        case 'accounts': discoverAccounts(data); return
        case 'negotiate': azureNegotiate(r, data); return
        case 'azureNegotiate': connectNegotiated(r, data); return
        case 'write': acceptWrite(r, data); return
    }
    if (!r.mac || !selectedMacs().contains(r.mac)) return
    Map cache = state.cache ?: [:]
    Map d = cache[r.mac] ?: [:]
    String field = r.kind == 'shadow' ? 'shadow' : r.kind == 'activation' ? 'priority' : r.kind
    if (r.context?.bootstrapGeneration != null && !currentConnection([locationId: r.context.bootstrapLocation, connectionGeneration: r.context.bootstrapGeneration])) return
    if ((((d.revisions ?: [:])[field] ?: 0L) as long) != ((r.readRevision ?: 0L) as long)) {
        // This request started before a pushed delta; fetch again rather than
        // replacing newer cache values with its older server snapshot.
        diagnostic('supersededRead', requestDiagnostic(r))
        enqueueRead(r.kind as String, r.mac as String, r.path as String, r.context ?: [:])
        return
    }
    // A malformed 2xx shadow must not erase live state or become a successful bootstrap.
    if (field == 'shadow' && (!(data.Reported instanceof Map) || !['Online', 'SystemSwitch', 'DisplayedIndoorTemperature', 'Setpoint'].any { data.Reported.containsKey(it) })) {
        state.lastError = 'Resideo returned an invalid thermostat state.'; diagnostic('invalidResponse', requestDiagnostic(r)); return
    }
    if ((field == 'configuration' && !(data.Reported instanceof Map)) ||
        (field == 'rooms' && (!(data.Rooms instanceof List) || data.Rooms.any { !(it instanceof Map) || (it.Accessories != null && (!(it.Accessories instanceof List) || it.Accessories.any { !(it instanceof Map) })) }))) {
        state.lastError = 'Resideo returned invalid optional device data.'; diagnostic('invalidResponse', requestDiagnostic(r)); return
    }
    d[field] = data
    Map revisions = d.revisions ?: [:]; revisions[field] = ((revisions[field] ?: 0L) as long) + 1L; d.revisions = revisions
    if (field == 'shadow') {
        d.lastUpdated = now(); d.lastRest = now(); d.orphaned = false
        if (r.context?.bootstrapGeneration != null) d.lastRestBootstrap = r.context.bootstrapGeneration
    }
    if (field == 'configuration') d.lastMetadata = now()
    d.unsupported = (d.unsupported ?: []) - field
    cache[r.mac] = d; state.cache = cache
    if (field == 'rooms') synchronizeSensors(r.mac as String)
    reconcilePending(r.mac as String, false)
    if (r.context?.reconcile) reconcilePending(r.mac as String, true, field, r.sentAt as long)
    resolveUncertainWrite(r.mac as String, field, r.sentAt as long)
    if (r.kind == 'activation') noteActivation(r, true)
    updateStreamReadiness(r.mac as String)
    publishDevice(r.mac as String)
}
void recordUnsupported(Map r) {
    Map cache = state.cache ?: [:]; Map d = cache[r.mac] ?: [:]
    String field = r.kind == 'activation' ? 'priority' : r.kind
    if (r.context?.bootstrapGeneration != null && !currentConnection([locationId: r.context.bootstrapLocation, connectionGeneration: r.context.bootstrapGeneration])) return
    if ((((d.revisions ?: [:])[field] ?: 0L) as long) != ((r.readRevision ?: 0L) as long)) {
        enqueueRead(r.kind as String, r.mac as String, r.path as String, r.context ?: [:]); return
    }
    d.unsupported = ((d.unsupported ?: []) + field).unique()
    diagnostic('endpointUnsupported', requestDiagnostic(r))
    Map revisions = d.revisions ?: [:]; revisions[field] = ((revisions[field] ?: 0L) as long) + 1L; d.revisions = revisions
    d[field] = [:]; cache[r.mac] = d; state.cache = cache
    if (field == 'rooms') synchronizeSensors(r.mac as String)
    reconcilePending(r.mac as String, true, field, r.sentAt as long)
    resolveUncertainWrite(r.mac as String, field, r.sentAt as long)
    if (r.kind == 'activation') noteActivation(r, false)
    publishDevice(r.mac as String)
}

// -- Discovery, child devices, polling -------------------------------------
List asList(Object value) { value == null ? [] : value instanceof Collection ? new ArrayList(value) : value instanceof Object[] ? value.toList() : [value] }
List selectedMacs() { asList(settings.selectedThermostats).collect { it.toString() } }
String deviceId(String kind, String mac, Object accessory = null) {
    'resideo-' + app.id + '-' + kind + '-' + mac + (accessory != null ? '-' + accessory : '')
}
String connectionId(String loc) { deviceId('connection', base64url(MessageDigest.getInstance('SHA-256').digest(loc.getBytes('UTF-8'))).substring(0, 20)) }
boolean thermostatIdentity(Map dev) {
    String kind = ''
    try { kind = new String(dev.id.toString().decodeBase64(), 'UTF-8').split(':', 2)[0].toLowerCase() } catch (Exception ignored) { }
    String model = (dev.globalDeviceType ?: '').toString().toLowerCase()
    kind.contains('thermostat') || model.contains('thermostat') || model.contains('lyric')
}
void discoverAccounts(Map graph) {
    Map found = [:]
    (graph.data?.consumerUsers ?: []).each { user ->
        (user.consumerAccount?.locations ?: []).each { loc ->
            (loc.consumerDevices ?: []).each { cd ->
                Map dev = cd.device ?: [:]
                if (dev.deviceId && loc.id && thermostatIdentity(dev)) found[dev.deviceId] =
                    [name: cd.name ?: dev.deviceId, locationId: loc.id, locationName: loc.name ?: 'Location', model: dev.globalDeviceType]
            }
        }
    }
    state.countryCode = graph.data?.countryCode
    state.discovered = found
    diagnostic('discovered', [count: found.size()])
    state.authStatus = found ? 'Connected' : 'Connected; no supported First Alert thermostats found'
    synchronizeSelection()
}
def ensureChild(String type, String dni, String label, Map values) {
    def child = getChildDevice(dni)
    if (!child) {
        child = addChildDevice('resideoHubitat', type, dni, [name: type, label: label, isComponent: values.kind == 'connection'])
        diagnostic('deviceCreated', values.kind == 'connection' ? [location: diagnosticAlias('location', values.locationId)] : [device: diagnosticDevice(values.mac as String, values.accessoryId)])
    }
    values.each { k, v -> if (v != null) child.updateDataValue(k.toString(), v.toString()) }
    child
}
void synchronizeSelection() {
    Set active = [] as Set
    Map locations = [:]
    Map connectionDevices = [:]
    selectedMacs().each { mac ->
        Map identity = (state.discovered ?: [:])[mac]
        if (!identity) return
        String dni = deviceId('thermostat', mac)
        def child = ensureChild('Resideo Thermostat', dni, identity.name as String,
            [kind: 'thermostat', mac: mac, locationId: identity.locationId, model: identity.model, orphaned: 'false'])
        active << dni
        Map d = (state.cache ?: [:])[mac] ?: [:]
        d.orphaned = false
        Map cache = state.cache ?: [:]; cache[mac] = d; state.cache = cache
        locations[identity.locationId] = identity.locationName
        synchronizeSensors(mac)
        getChildDevices().findAll { it.getDataValue('kind') == 'sensor' && it.getDataValue('mac') == mac && it.getDataValue('orphaned') != 'true' }.each { active << it.deviceNetworkId }
        refreshDevice(mac, true)
    }
    locations.each { loc, name ->
        String dni = connectionId(loc as String)
        def child = ensureChild('Resideo Connection', dni, 'Resideo connection: ' + name,
            [kind: 'connection', locationId: loc, orphaned: 'false'])
        active << dni
        List members = locationMacs(loc as String).sort()
        connectionDevices[loc] = members
        boolean changed = (state.connectionDevices ?: [:])[loc] != members
        if (enabled() && state.tokens && (!child.connectionActive() || changed)) {
            Map streams = state.streams ?: [:]; streams[loc] = [status: 'connecting', ready: false, activated: false]; state.streams = streams
            child.startConnection(generation())
        }
    }
    state.connectionDevices = connectionDevices
    getChildDevices().findAll { !active.contains(it.deviceNetworkId) }.each { child ->
        child.updateDataValue('orphaned', 'true')
        child.applyAttributes([connectionStatus: 'unavailable', dataStatus: 'unavailable'])
        if (child.getDataValue('kind') == 'connection') child.stopConnection()
    }
}
List accessories(Map rooms) { (rooms.Rooms ?: []).collectMany { room -> (room.Accessories ?: []).collect { [room: room, accessory: it] } } }
void synchronizeSensors(String mac) {
    Map d = (state.cache ?: [:])[mac] ?: [:]
    List live = []
    accessories(d.rooms ?: [:]).findAll { it.accessory.AccessoryAttribute?.Type == 'IndoorAirSensor' }.each { item ->
        Map a = item.accessory
        if (a.AccessoryId == null) return
        String dni = deviceId('sensor', mac, a.AccessoryId)
        live << dni
        ensureChild('Resideo Room Sensor', dni, item.room.Name + ' sensor',
            [kind: 'sensor', mac: mac, accessoryId: a.AccessoryId, roomId: item.room.Id,
             model: a.AccessoryAttribute?.Model, orphaned: 'false'])
    }
    getChildDevices().findAll { it.getDataValue('kind') == 'sensor' && it.getDataValue('mac') == mac && !live.contains(it.deviceNetworkId) }.each {
        it.updateDataValue('orphaned', 'true'); it.applyAttributes([dataStatus: 'unavailable'])
    }
}
void refreshDevice(String mac, boolean metadata, Map context = [:]) {
    if (!selectedMacs().contains(mac) || !(state.discovered ?: [:])[mac]) return
    enqueueRead('shadow', mac, thermostatPath(mac), context)
    enqueueRead('rooms', mac, thermostatPath(mac, 1) + '/group/0/rooms', context)
    if (metadata) {
        enqueueRead('configuration', mac, thermostatPath(mac) + '/configuration', context)
        enqueueRead('priority', mac, thermostatPath(mac, 1) + '/priority', context)
    }
}
void maintenance() {
    if (!enabled()) return
    selectedMacs().each { mac ->
        Map d = (state.cache ?: [:])[mac] ?: [:]
        String loc = (state.discovered ?: [:])[mac]?.locationId
        Map stream = (state.streams ?: [:])[loc] ?: [:]
        if (!stream.ready && now() - ((d.lastPoll ?: 0L) as long) >= pollingMinutes() * 60000L) {
            d.lastPoll = now(); Map cache = state.cache ?: [:]; cache[mac] = d; state.cache = cache
            diagnostic('fallbackPoll', [device: diagnosticDevice(mac as String), retrySeconds: pollingMinutes() * 60])
            refreshDevice(mac, now() - ((d.lastMetadata ?: 0L) as long) >= 1800000L)
        }
        publishDevice(mac)
    }
    runIn(60, 'maintenance')
}

// -- SignalR bridge --------------------------------------------------------
boolean currentConnection(Map context) {
    def driver = getChildDevice(connectionId(context.locationId as String))
    driver && driver.connectionActive() && driver.connectionGeneration() == (context.connectionGeneration as long) && driver.accountGeneration() == generation()
}
void negotiate(String loc, long cg) {
    if (!state.tokens || !currentConnection([locationId: loc, connectionGeneration: cg])) return
    enqueueRequest([kind: 'negotiate', method: 'POST', path: '/ds-notification-service/Hub/negotiate?negotiateVersion=1',
        context: [locationId: loc, connectionGeneration: cg]])
}
void azureNegotiate(Map r, Map data) {
    if (!currentConnection(r.context as Map)) return
    String url = data.url
    // Only send Azure's token to a verified Azure SignalR host.
    if (!trustedSignalrUrl(url) || !data.accessToken) { streamFailed(r.context as Map); return }
    int q = url.indexOf('?')
    String base = q >= 0 ? url.substring(0, q) : url
    if (!base.endsWith('/')) base += '/'
    String neg = base + 'negotiate' + (q >= 0 ? url.substring(q) : '')
    enqueueRequest([kind: 'azureNegotiate', method: 'POST', url: neg, azureToken: data.accessToken,
        context: (r.context as Map) + [baseUrl: url]])
}
boolean trustedSignalrUrl(String url) {
    try {
        URI u = new URI(url)
        u.scheme == 'https' && u.host?.endsWith('.service.signalr.net') && !u.userInfo && (u.port == -1 || u.port == 443)
    } catch (Exception ignored) { false }
}
void connectNegotiated(Map r, Map data) {
    if (!currentConnection(r.context as Map)) return
    String token = data.connectionToken ?: data.connectionId
    if (!token) { streamFailed(r.context as Map); return }
    String url = r.context.baseUrl.replaceFirst('^https://', 'wss://')
    url += (url.contains('?') ? '&' : '?') + 'id=' + encode(token) + '&access_token=' + encode(r.azureToken)
    getChildDevice(connectionId(r.context.locationId as String)).openSocket(url, generation(), r.context.connectionGeneration as long)
}
void streamFailed(Map context) {
    if (currentConnection(context)) getChildDevice(connectionId(context.locationId as String)).connectionFailed('Negotiation failed')
}
void streamStatus(Map msg) {
    if (!currentConnection(msg)) return
    Map streams = state.streams ?: [:]
    Map stream = streams[msg.locationId] ?: [:]
    stream.status = msg.status
    if (msg.status != 'connected') { stream.ready = false; stream.activated = false }
    streams[msg.locationId] = stream; state.streams = streams
    Map detail = [location: diagnosticAlias('location', msg.locationId), status: msg.status, reason: msg.reason,
        stage: msg.stage, retrySeconds: msg.retrySeconds, generation: msg.connectionGeneration]
    if (msg.status == 'disconnected') diagnostic(msg.reason == 'feedRenewal' ? 'feedRenewal' : 'streamFailure', detail)
    else diagnostic('streamStatus', detail)
    locationMacs(msg.locationId as String).each { mac ->
        if (msg.status != 'connected') refreshDevice(mac, false)
        publishDevice(mac)
    }
}
List locationMacs(String loc) { selectedMacs().findAll { (state.discovered ?: [:])[it]?.locationId == loc } }
void activateStream(Map msg) {
    if (!currentConnection(msg)) return
    Map streams = state.streams ?: [:]
    streams[msg.locationId] = [status: 'connected', ready: false, activated: false,
        activation: locationMacs(msg.locationId as String).collectEntries { [(it): false] }, generation: msg.connectionGeneration,
        bootstrapAfter: now()]
    state.streams = streams
    locationMacs(msg.locationId as String).each { mac ->
        refreshDevice(mac, true, [bootstrapLocation: msg.locationId, bootstrapGeneration: msg.connectionGeneration])
        enqueueRead('activation', mac, thermostatPath(mac, 1) + '/priority',
            [locationId: msg.locationId, connectionGeneration: msg.connectionGeneration])
    }
}
void noteActivation(Map r, boolean success) {
    if (!currentConnection(r.context as Map)) return
    Map streams = state.streams ?: [:]; Map s = streams[r.context.locationId] ?: [:]
    Map activation = s.activation ?: [:]; activation[r.mac] = success; s.activation = activation
    s.activated = activation && activation.values().every { it == true }
    streams[r.context.locationId] = s; state.streams = streams
    if (!success) streamFailed(r.context as Map)
}
void updateStreamReadiness(String mac) {
    String loc = (state.discovered ?: [:])[mac]?.locationId
    if (!loc) return
    Map streams = state.streams ?: [:]; Map s = streams[loc] ?: [:]
    boolean wasReady = s.ready == true
    s.ready = s.status == 'connected' && s.activated == true && locationMacs(loc).every {
        (state.cache ?: [:])[it]?.lastRestBootstrap == s.generation
    }
    streams[loc] = s; state.streams = streams
    if (s.ready != wasReady) {
        diagnostic('streamReady', [location: diagnosticAlias('location', loc), source: s.ready ? 'push' : 'poll'])
        locationMacs(loc).each { publishDevice(it.toString()) }
    }
}
void handleStreamEvent(Map msg) {
    if (!currentConnection(msg)) return
    Map event = msg.event instanceof Map ? msg.event : parseJsonMap(msg.event?.toString())
    String mac = event.DeviceId
    if (!mac || !locationMacs(msg.locationId as String).contains(mac)) return
    if (event.NotificationType in ['ChangeRequestSuccess', 'ChangeRequestFailure']) {
        diagnostic('streamRefresh', [device: diagnosticDevice(mac), reason: event.NotificationType == 'ChangeRequestFailure' ? 'cloudRejected' : null])
        if (event.NotificationType == 'ChangeRequestFailure') failTransaction(mac, event.Body?.TransactionId?.toString())
        debounceRefresh(mac); return
    }
    if (event.NotificationType != 'LiveFeedEvent') return
    Map cache = state.cache ?: [:]; Map d = cache[mac] ?: [:]
    Map merged = mergeLive(d.shadow ?: [:], d.rooms ?: [:], event.Body ?: [:])
    if (!merged.applied) { diagnostic('streamRefresh', [device: diagnosticDevice(mac), property: event.Body?.PropertyName]); debounceRefresh(mac); return }
    diagnostic('streamDelta', [device: diagnosticDevice(mac), property: event.Body?.PropertyName])
    d.shadow = merged.shadow; d.rooms = merged.rooms; d.lastUpdated = now()
    String field = event.Body.PropertyName in ['Sensor', 'Rooms'] ? 'rooms' : 'shadow'
    Map revisions = d.revisions ?: [:]; revisions[field] = ((revisions[field] ?: 0L) as long) + 1L; d.revisions = revisions
    cache[mac] = d; state.cache = cache
    if (event.Body?.PropertyName == 'Rooms') synchronizeSensors(mac)
    reconcilePending(mac, false)
    publishDevice(mac)
}
void debounceRefresh(String mac) {
    Map dirty = state.dirtyDevices ?: [:]; dirty[mac] = true; state.dirtyDevices = dirty
    runIn(3, 'refreshDirty')
}
void refreshDirty() {
    Map dirty = state.dirtyDevices ?: [:]; state.dirtyDevices = [:]
    dirty.keySet().each { refreshDevice(it.toString(), true) }
}

// -- Pure partial-delta merge; adapted from upstream merge.py ---------------
Map deepCopy(Map m) { parseJsonMap(JsonOutput.toJson(m ?: [:])) }
void copyPresent(Map dst, String dest, Map src, String key) { if (src[key] != null) dst[dest] = src[key] }
Map mergeLive(Map shadowRaw, Map roomsRaw, Map body) {
    Map shadow = deepCopy(shadowRaw); Map rooms = deepCopy(roomsRaw)
    Map rep = shadow.Reported instanceof Map ? shadow.Reported : [:]; shadow.Reported = rep
    Object value = body.Value
    if (!(value instanceof Map)) return [shadow: shadow, rooms: rooms, applied: false]
    Map v = value
    String property = body.PropertyName
    switch (property) {
        case 'Setpoint':
            Map sp = rep.Setpoint ?: [:]
            copyPresent(sp, 'SetpointStatus', v, 'Status'); copyPresent(sp, 'HeatSetpoint', v, 'HeatSetpoint'); copyPresent(sp, 'CoolSetpoint', v, 'CoolSetpoint')
            if (v.FanSwitch?.Position != null) { rep.FanSwitch = (rep.FanSwitch ?: [:]) + [Position: v.FanSwitch.Position]; sp.FanSwitch = (sp.FanSwitch ?: [:]) + [Position: v.FanSwitch.Position] }
            rep.Setpoint = sp; break
        case 'OperationStatus':
            Map op = rep.OperationStatus ?: [:]
            copyPresent(op, 'Mode', v, 'Mode'); copyPresent(op, 'FanRequest', v, 'Fan'); copyPresent(op, 'CirculationFanRequest', v, 'CircFan')
            rep.OperationStatus = op
            if (v.curStg != null) rep.HeatAndCoolDemand = (rep.HeatAndCoolDemand ?: [:]) + [CurrentStage: v.curStg]
            break
        case 'SystemSwitch':
            copyPresent(rep, 'SystemSwitch', v, 'SystemSwitch'); copyPresent(rep, 'HeatCoolMode', v, 'HeatCoolMode'); break
        case 'FanSwitch':
            if (v.Position == null) return [shadow: shadow, rooms: rooms, applied: false]
            rep.FanSwitch = (rep.FanSwitch ?: [:]) + v.findAll { k, val -> val != null }
            rep.Setpoint = rep.Setpoint ?: [:]; rep.Setpoint.FanSwitch = (rep.Setpoint.FanSwitch ?: [:]) + [Position: v.Position]; break
        case 'Sensor':
            Map target = sensorPushTarget(rooms, v)
            if (!target) return [shadow: shadow, rooms: rooms, applied: false]
            Map av = target.AccessoryValue ?: [:]
            v.AccessoryValue.each { k, val ->
                if (k == 'Id' || val == null) return
                if (val instanceof Map) {
                    Map nested = (av[k] instanceof Map ? av[k] : [:]) + val.findAll { nk, nv -> nv != null }
                    if (nested.containsKey('Measurement') && !nested.containsKey('Displayed')) nested.Displayed = true
                    av[k] = nested
                } else av[k] = val
            }
            target.AccessoryValue = av; break
        case 'Rooms':
            Map roomValue = v.Value instanceof Map ? v.Value : v
            Object roomId = roomValue.Id != null ? roomValue.Id : v.PropertyName
            Map room = (rooms.Rooms ?: []).find { it.Id?.toString() == roomId?.toString() }
            if (!room) return [shadow: shadow, rooms: rooms, applied: false]
            roomValue.each { k, val -> if (k != 'Accessories' && val != null) room[k] = val }; break
        case 'DisplayedIndoorTemperature':
        case 'DisplayedIndoorHumidity':
        case 'DisplayedOutdoorTemperature':
        case 'DisplayedOutdoorHumidity':
            if (v.Value == null) return [shadow: shadow, rooms: rooms, applied: false]
            rep[property] = v.Value; break
        default: return [shadow: shadow, rooms: rooms, applied: false]
    }
    [shadow: shadow, rooms: rooms, applied: true]
}
Map sensorPushTarget(Map rooms, Map value) {
    if (!(value.AccessoryValue instanceof Map)) return null
    List accs = accessories(rooms).collect { it.accessory }.findAll { it.AccessoryId != null }.sort { (it.AccessoryId as int) }
    if (!value.AccessoryValue.containsKey('BatteryStatus')) {
        List t = accs.findAll { it.AccessoryAttribute?.Type == 'Thermostat' }
        return t.size() == 1 ? t[0] : null
    }
    for (int i = 0; i < accs.size() - 1; i++) {
        if (accs[i].AccessoryId == value.AccessoryId && accs[i + 1].AccessoryAttribute?.Type == 'IndoorAirSensor') return accs[i + 1]
    }
    null
}

// -- Commands, complete bodies, pending values -----------------------------
void acceptCommand(Map msg) {
    diagnostic('commandReceived', [device: diagnosticDevice(msg.mac as String, msg.accessoryId), command: msg.command])
    if (!selectedMacs().contains(msg.mac) || !(state.discovered ?: [:])[msg.mac]) { rejectCommand(msg, 'Device is no longer selected or available.'); return }
    try { buildCommand(msg) } catch (Exception e) { rejectCommand(msg, validationMessage(e, 'Could not validate command. Refresh and try again.')); return }
    enqueueRequest([kind: 'write', method: 'PUT', mac: msg.mac, context: msg])
}
boolean toggle(Object value) {
    if (!(value?.toString()?.toLowerCase() in ['on', 'off'])) throw validationError('Use on or off.')
    value.toString().equalsIgnoreCase('on')
}
BigDecimal number(Object value) {
    try { return new BigDecimal(value.toString()) } catch (Exception ignored) { throw validationError('Enter a finite numeric value.') }
}
BigDecimal toF(Object value) { BigDecimal n = number(value); getTemperatureScale() == 'C' ? n * 9 / 5 + 32 : n }
BigDecimal fromF(Object value) { BigDecimal n = number(value); (getTemperatureScale() == 'C' ? (n - 32) * 5 / 9 : n).setScale(1, BigDecimal.ROUND_HALF_UP) }
BigDecimal temperatureReading(Object value) { try { fromF(value) } catch (Exception ignored) { null } }
Object outdoorReading(Object value) { try { outdoorValue(value) } catch (Exception ignored) { null } }
Map numericReadings(Map attrs, List names) {
    names.each { name ->
        if (attrs.containsKey(name)) {
            try { attrs[name] = number(attrs[name]) } catch (Exception ignored) { attrs.remove(name) }
        }
    }
    attrs
}
String outdoorUnit() { !state.countryCode || ['US', 'PR', 'GU', 'VI', 'AS', 'MP', 'UM', 'BS', 'BZ', 'KY', 'LR', 'PW', 'FM', 'MH'].contains(state.countryCode.toString().trim().toUpperCase()) ? 'F' : 'C' }
Object outdoorValue(Object value) { outdoorUnit() == getTemperatureScale() ? number(value).setScale(1, BigDecimal.ROUND_HALF_UP) : outdoorUnit() == 'F' ? fromF(value) : (number(value) * 9 / 5 + 32).setScale(1, BigDecimal.ROUND_HALF_UP) }
Map effectiveAttributes(String mac, Object aid = null) {
    Map d = (state.cache ?: [:])[mac] ?: [:]
    Map attrs = aid != null ? sensorAttributes(findAccessory(d.rooms ?: [:], aid) ?: [:]) : thermostatAttributes(d)
    Map p = (state.pending ?: [:])[pendingKey(mac, aid)] ?: [:]
    p.each { key, val -> if (val.accepted) attrs[key] = val.value }
    if (aid == null) {
        String mode = attrs.thermostatMode
        if (mode in ['heat', 'emergency heat'] || (mode == 'auto' && attrs.thermostatOperatingState == 'heating')) attrs.thermostatSetpoint = attrs.heatingSetpoint
        else if (mode == 'cool' || (mode == 'auto' && attrs.thermostatOperatingState == 'cooling')) attrs.thermostatSetpoint = attrs.coolingSetpoint
        else attrs.remove('thermostatSetpoint')
    }
    attrs
}
String pendingKey(String mac, Object aid) { aid == null ? mac : mac + ':' + aid }
Map findAccessory(Map rooms, Object aid) { accessories(rooms).find { it.accessory.AccessoryId?.toString() == aid?.toString() }?.accessory }

Map buildCommand(Map msg) {
    String mac = msg.mac
    if (!selectedMacs().contains(mac) || !(state.discovered ?: [:])[mac]) throw validationError('Device is no longer selected or available.')
    Map d = (state.cache ?: [:])[mac] ?: [:]
    Map rep = d.shadow?.Reported ?: [:]; Map cfg = d.configuration?.Reported ?: [:]
    if (!rep || rep.Online != true) throw validationError('Thermostat is offline or has no confirmed state. Refresh first.')
    if (now() - ((d.lastUpdated ?: 0L) as long) > Math.max(900000L, pollingMinutes() * 180000L)) throw validationError('Thermostat data is stale. Refresh before changing settings.')
    Map attrs = effectiveAttributes(mac, msg.accessoryId)
    String cmd = msg.command
    List args = asList(msg.args)
    String path; Map body; Map expected = [:]; List deferred = []
    switch (cmd) {
        case 'setThermostatMode':
            Map modes = modeMap(); String mode = modes[args[0]?.toString()?.toLowerCase()]
            if (!mode || !(cfg.SystemSwitchCapabilities ?: []).contains(mode)) throw validationError('Unsupported thermostat mode. Refresh configuration if needed.')
            path = 'systemSwitch'; body = [SystemSwitchValue: mode]; expected.thermostatMode = modeMap().find { it.value == mode }.key; break
        case 'setThermostatFanMode':
            String pos = [auto: 'Auto', circulate: 'Circulate', on: 'On'][args[0]?.toString()?.toLowerCase()]
            if (!pos || !(cfg.FanSwitchCapabilities?.Positions ?: []).contains(pos)) throw validationError('Unsupported fan mode.')
            path = 'fanSwitch'; body = [FanSwitch: [Position: pos, Speed: 1]]; expected.thermostatFanMode = args[0].toString().toLowerCase(); break
        case 'setHeatingSetpoint':
        case 'setCoolingSetpoint':
            boolean heat = cmd == 'setHeatingSetpoint'
            List modes = cfg.SystemSwitchCapabilities ?: []
            boolean supportsHeat = modes.any { it in ['Heat', 'EmergencyHeat', 'Auto'] }
            boolean supportsCool = modes.any { it in ['Cool', 'Auto'] }
            if ((heat && !supportsHeat) || (!heat && !supportsCool)) throw validationError('This setpoint is not supported by the configured equipment.')
            BigDecimal f = toF(args[0])
            Object low = cfg[heat ? 'MinimumHeatSetpointAllowed' : 'MinimumCoolSetpointAllowed']
            Object high = cfg[heat ? 'MaximumHeatSetpointAllowed' : 'MaximumCoolSetpointAllowed']
            if (low == null || high == null || f < number(low) || f > number(high)) throw validationError('Setpoint is outside the reported allowed range.')
            Map thermostat = effectiveAttributes(mac)
            if (supportsHeat && supportsCool) {
                BigDecimal other = toF(thermostat[heat ? 'coolingSetpoint' : 'heatingSetpoint'])
                BigDecimal deadband = number(cfg.SetpointDeadband ?: 0)
                if ((heat && f + deadband > other) || (!heat && f - deadband < other)) throw validationError('Setpoint violates the heat/cool deadband. Change the other setpoint explicitly first.')
            }
            String hold = rep.ScheduleEnabled == true && thermostat.holdStatus in ['NoHold', 'TemporaryHold'] ? 'TemporaryHold' : 'PermanentHold'
            path = heat ? 'heatSetpoint' : 'coolSetpoint'; body = [SetpointStatus: hold, SetpointValue: f]
            expected[heat ? 'heatingSetpoint' : 'coolingSetpoint'] = fromF(f); expected.holdStatus = hold; break
        case 'setHold':
            String hold = [none: 'NoHold', temporary: 'TemporaryHold', permanent: 'PermanentHold'][args[0]?.toString()?.toLowerCase()]
            if (!hold) throw validationError('Use none, temporary, or permanent; timed/vacation holds are read-only.')
            if (hold != 'PermanentHold' && rep.ScheduleEnabled != true) throw validationError('An enabled schedule is required for this hold.')
            path = 'hold'; body = [Status: hold]; expected.holdStatus = hold; break
        case 'setScheduleEnabled':
            if (!rep.containsKey('ScheduleEnabled')) throw validationError('Schedule control is unavailable.')
            boolean on = toggle(args[0]); path = 'schedule/enabled'; body = [ScheduleEnabled: on]; expected.scheduleEnabled = on ? 'on' : 'off'; break
        case 'setFeelsLikeEnabled':
            if (!rep.containsKey('FeelsLikeEnabled')) throw validationError('Feels Like is unavailable.')
            boolean on = toggle(args[0]); path = 'feelsLike'; body = [FeelsLikeEnabled: on]; expected.feelsLikeEnabled = on ? 'on' : 'off'; break
        case 'setAdaptiveRecoveryEnabled':
            String mode = toggle(args[0]) ? 'AdaptiveIntelligentRecovery' : 'None'
            if (!(cfg.AllowedAdaptiveRecoveryModes ?: []).contains(mode)) throw validationError('Adaptive recovery mode is unavailable.')
            path = 'adaptiveIntelligentRecovery'; body = [Mode: mode]; expected.adaptiveRecoveryMode = mode; expected.adaptiveRecoveryEnabled = mode == 'None' ? 'off' : 'on'; break
        case 'setSetpointLimits':
            if (args.size() != 4) throw validationError('Supply all four limits.')
            List limits = args.collect { toF(it).setScale(0, BigDecimal.ROUND_HALF_UP) }
            if (limits.any { it < 50 || it > 90 } || limits[0] > limits[1] || limits[2] > limits[3] || limits[0] > limits[2] || limits[1] > limits[3]) throw validationError('Limits must be within 50–90°F, ordered min/max, with heat limits at or below cool limits.')
            if (['MinimumHeatSetpointAllowed', 'MaximumHeatSetpointAllowed', 'MinimumCoolSetpointAllowed', 'MaximumCoolSetpointAllowed'].any { cfg[it] == null }) throw validationError('Setpoint limits are unavailable.')
            path = 'setPointCapabilities'; body = [minHeatSetpoint: limits[0] as int, maxHeatSetpoint: limits[1] as int, minCoolSetpoint: limits[2] as int, maxCoolSetpoint: limits[3] as int]
            ['heatSetpointMin', 'heatSetpointMax', 'coolSetpointMin', 'coolSetpointMax'].eachWithIndex { key, i -> expected[key] = fromF(limits[i]) }; break
        case 'setFreezeProtectionLowLimit':
            if (cfg.FreezeProtection?.Configured != true) throw validationError('Freeze protection is not configured on this thermostat.')
            int floor = toF(args[0]).setScale(0, BigDecimal.ROUND_HALF_UP) as int
            if (floor < 35 || floor > 45) throw validationError('Freeze protection must be 35–45°F.')
            path = 'freezeProtection'; body = [LowLimitDegrees: floor]; expected.freezeProtectionLowLimit = fromF(floor); break
        case 'setOccupancySensitivity':
        case 'setExcludeMotion':
        case 'setExcludeTemperature':
            Map sensor = findAccessory(d.rooms ?: [:], msg.accessoryId)
            if (!sensor || sensor.AccessoryAttribute?.Type != 'IndoorAirSensor' || !sensor.AccessoryValue?.OccupancySensitivity) throw validationError('This sensor does not support occupancy settings.')
            String sensitivity = attrs.occupancySensitivity
            boolean em = attrs.excludeMotion == 'on'; boolean et = attrs.excludeTemperature == 'on'
            if (cmd == 'setOccupancySensitivity') {
                sensitivity = [off: 'Off', low: 'Low', medium: 'Medium', high: 'High'][args[0]?.toString()?.toLowerCase()]
                if (!sensitivity) throw validationError('Use off, low, medium, or high.')
                expected.occupancySensitivity = sensitivity; deferred << 'occupancySensitivity'
            } else if (cmd == 'setExcludeMotion') { em = toggle(args[0]); expected.excludeMotion = em ? 'on' : 'off' }
              else { et = toggle(args[0]); expected.excludeTemperature = et ? 'on' : 'off' }
            path = 'accessories/' + encode(msg.accessoryId) + '/accessoryValue'
            body = [sensitivity: sensitivity, excludeMotion: em, excludeTemp: et]; break
        default: throw validationError('Unsupported command.')
    }
    [path: thermostatPath(mac, 1) + '/' + path, body: body + [ChannelId: constants().channel], expected: expected, deferred: deferred]
}

void rejectCommand(Map msg, String reason) {
    diagnostic('commandRejected', [device: diagnosticDevice(msg.mac as String, msg.accessoryId), command: msg.command, reason: commandFailureReason(reason)])
    Map last = state.lastCommand ?: [:]
    last[pendingKey(msg.mac as String, msg.accessoryId)] = [status: 'rejected', error: reason]
    state.lastCommand = last
    def child = commandDevice(msg)
    if (child) child.applyAttributes([commandStatus: 'rejected', lastCommandError: reason])
}
String commandFailureReason(String reason) {
    if (reason.startsWith('Cloud rejected')) return 'cloudRejected'
    if (reason.startsWith('Resideo rate limit')) return 'rateLimited'
    if (reason.startsWith('Request queue')) return 'queueFull'
    if (reason.startsWith('Device is no longer')) return 'deviceUnavailable'
    if (reason.startsWith('Thermostat is offline')) return 'deviceOffline'
    if (reason.startsWith('Thermostat data is stale')) return 'staleData'
    if (reason.contains('deadband')) return 'deadband'
    if (reason.contains('enabled schedule')) return 'scheduleDisabled'
    if (reason.contains('outside') || reason.startsWith('Limits must') || reason.startsWith('Freeze protection must')) return 'outsideLimits'
    if (reason.contains('numeric')) return 'invalidNumber'
    if (reason.contains('Unsupported') || reason.contains('not supported') || reason.contains('unavailable') || reason.contains('not configured') || reason.contains('does not support')) return 'unsupportedControl'
    'validationRejected'
}
def commandDevice(Map msg) { getChildDevice(deviceId(msg.accessoryId == null ? 'thermostat' : 'sensor', msg.mac as String, msg.accessoryId)) }
void acceptWrite(Map r, Map data, boolean accepted = true) {
    diagnostic(accepted ? 'commandAccepted' : 'commandUncertain', requestDiagnostic(r) +
        [command: r.context.command, count: r.expected.size(), requested: r.expected, scale: getTemperatureScale()])
    String key = pendingKey(r.mac as String, r.context.accessoryId)
    Map all = state.pending ?: [:]; Map pending = all[key] ?: [:]
    r.expected.each { attr, value -> pending[attr] = [value: value, transaction: data.TransactionId, accepted: accepted,
        deferred: accepted && r.deferred.contains(attr), deadline: now() + 10000L, request: r.id] }
    all[key] = pending; state.pending = all
    Map last = state.lastCommand ?: [:]
    last[key] = [status: accepted ? 'pending' : 'uncertain', error: accepted ? '' : 'Request timed out; checking reported state without repeating the command.']
    state.lastCommand = last
    scheduleReconciliation()
    publishDevice(r.mac as String)
}
void ambiguousWrite(Map r, boolean uncertain) {
    if (!uncertain) { rejectCommand(r.context as Map, 'Cloud rejected command. ' + (state.lastError ?: '')); return }
    // Observe an uncertain write once; never retransmit it automatically.
    Map barriers = state.uncertainWrites ?: [:]
    String key = pendingKey(r.mac as String, r.context.accessoryId)
    barriers[key] = [request: r.id, mac: r.mac,
        field: r.context.accessoryId != null ? 'rooms' : r.expected.keySet().any { configurationAttribute(it.toString()) } ? 'configuration' : 'shadow',
        after: now() + 10000L]
    state.uncertainWrites = barriers
    acceptWrite(r, [:], false)
}
boolean writeBlocked(Map request) {
    (state.uncertainWrites ?: [:]).containsKey(pendingKey(request.mac as String, request.context.accessoryId))
}
void resolveUncertainWrite(String mac, String field, long sentAt) {
    Map barriers = state.uncertainWrites ?: [:]
    barriers.keySet().toList().each { key ->
        Map barrier = barriers[key]
        if (barrier.mac == mac && barrier.field == field && sentAt >= (barrier.after as long)) {
            reconcilePending(mac, true, field, sentAt)
            barriers.remove(key)
        }
    }
    state.uncertainWrites = barriers
}
void scheduleReconciliation(boolean futureOnly = false) {
    List deadlines = (state.pending ?: [:]).values().collectMany { p ->
        p.values().findAll { !it.deferred && (!futureOnly || (it.deadline as long) > now()) }.collect { it.deadline as long }
    }
    if (deadlines) runIn(Math.max(1, Math.ceil(((deadlines.min() as long) - now()) / 1000.0d) as int), 'reconcileCommands')
}
void reconcileCommands() {
    Set macs = [] as Set
    (state.pending ?: [:]).each { key, p -> if (p.values().any { !it.deferred && (it.deadline as long) <= now() }) macs << key.toString().split(':', 2)[0] }
    macs.each { mac ->
        refreshDevice(mac.toString(), true, [reconcile: true, reconcileField: 'shadow'])
        enqueueRead('rooms', mac.toString(), thermostatPath(mac.toString(), 1) + '/group/0/rooms', [reconcile: true, reconcileField: 'rooms'])
        enqueueRead('configuration', mac.toString(), thermostatPath(mac.toString()) + '/configuration', [reconcile: true, reconcileField: 'configuration'])
    }
    scheduleReconciliation(true)
}
void reconcilePending(String mac, boolean timed, String field = null, long sentAt = Long.MAX_VALUE) {
    Map all = state.pending ?: [:]; Map last = state.lastCommand ?: [:]
    Map d = (state.cache ?: [:])[mac] ?: [:]
    all.keySet().findAll { it == mac || it.toString().startsWith(mac + ':') }.each { key ->
        Object aid = key == mac ? null : key.toString().substring(mac.length() + 1)
        Map reported = aid == null ? thermostatAttributes(d) : sensorAttributes(findAccessory(d.rooms ?: [:], aid) ?: [:])
        Map p = all[key] ?: [:]; boolean expired = false
        int before = p.size(); int confirmed = 0; int unconfirmed = 0
        Map requested = p.collectEntries { k, v -> [(k): v.value] }
        Map reportedSettings = reported.findAll { k, v -> p.containsKey(k) }
        p.keySet().toList().each { attr ->
            Map pending = p[attr]
            if (reported.containsKey(attr) && reported[attr] == pending.value) { p.remove(attr); confirmed++ }
            else if (timed && !pending.deferred && (pending.deadline as long) <= sentAt &&
                (field == null || (aid != null ? field == 'rooms' : configurationAttribute(attr) ? field == 'configuration' : field == 'shadow'))) {
                p.remove(attr); expired = true; unconfirmed++
            }
        }
        all[key] = p
        if (expired) last[key] = [status: p ? 'pending' : 'unconfirmed', error: 'Requested change was not confirmed; showing reported state.']
        else if (!p && last[key]?.status in ['pending', 'uncertain']) last[key] = [status: 'confirmed', error: '']
        if (before != p.size()) diagnostic(expired ? 'commandUnconfirmed' : p ? 'commandProgress' : 'commandConfirmed',
            [device: diagnosticDevice(mac, aid), confirmed: confirmed, unconfirmed: unconfirmed, remaining: p.size(),
             requested: requested, reported: reportedSettings, scale: getTemperatureScale()])
    }
    state.pending = all; state.lastCommand = last
}
boolean configurationAttribute(String attr) { attr in ['heatSetpointMin', 'heatSetpointMax', 'coolSetpointMin', 'coolSetpointMax', 'freezeProtectionLowLimit'] }
void failTransaction(String mac, String transaction) {
    if (!transaction) return
    Map all = state.pending ?: [:]; Map last = state.lastCommand ?: [:]
    all.keySet().findAll { it == mac || it.toString().startsWith(mac + ':') }.each { key ->
        Map p = all[key] ?: [:]
        List failed = p.keySet().findAll { p[it].transaction == transaction }.toList()
        failed.each { p.remove(it) }
        if (failed) {
            last[key] = [status: 'failed', error: 'Resideo reported command failure.']
            Object aid = key == mac ? null : key.toString().substring(mac.length() + 1)
            diagnostic('commandFailed', [device: diagnosticDevice(mac, aid), count: failed.size(), reason: 'cloudRejected'])
        }
        all[key] = p
    }
    state.pending = all; state.lastCommand = last; publishDevice(mac)
}

// -- Attribute projection, availability, diagnostics ------------------------
Map modeMap() { [heat: 'Heat', cool: 'Cool', off: 'Off', auto: 'Auto', 'emergency heat': 'EmergencyHeat'] }
Object nested(Map data, String path) {
    Object current = data
    for (String key : path.split('/')) { if (!(current instanceof Map)) return null; current = current[key] }
    current
}
Map thermostatAttributes(Map d) {
    Map r = d.shadow?.Reported ?: [:]; Map c = d.configuration?.Reported ?: [:]
    Map out = [:]
    Map direct = [humidity: 'DisplayedIndoorHumidity', outdoorHumidity: 'DisplayedOutdoorHumidity',
        heatCoolMode: 'HeatCoolMode', equipmentStatus: 'OperationStatus/Mode', holdStatus: 'Setpoint/SetpointStatus',
        demand: 'HeatAndCoolDemand/Demand', currentStage: 'HeatAndCoolDemand/CurrentStage',
        airQuality: 'IndoorAirQuality/TotalIndoorAirQuality/Quality', co2Quality: 'IndoorAirQuality/EstimatedCarbonDioxide/Quality',
        vocQuality: 'IndoorAirQuality/TotalVolatileOrganicCompounds/Quality', humidityQuality: 'IndoorAirQuality/HumidityQuality',
        schedulePeriod: 'CurrentSchedulePeriod/Period', scheduleDay: 'CurrentSchedulePeriod/Day', scheduleType: 'ScheduleType/ScheduleType',
        backlight: 'BackLightState', priorityType: 'CurrentPriorityType', airFilterRemaining: 'AirFilterReminders/1/RemainingDays',
        firmwareStatus: 'FirmwareUpdate/Status', firmwareUpdated: 'FirmwareSystem/LastUpdated', registered: 'RegistrationDate',
        adaptiveRecoveryMode: 'ActiveAdaptiveRecoveryMode', ventilationTimer: 'VentilationTimer', ventilationBoostTimer: 'VentilationBoostTimer',
        language: 'CurrentLanguage', demandResponse: 'DemandResponseState/CurrentDrEventState']
    direct.each { attr, path -> Object v = nested(r, path); if (v != null) out[attr] = v }
    Map temps = [temperature: 'DisplayedIndoorTemperature', heatingSetpoint: 'Setpoint/HeatSetpoint', coolingSetpoint: 'Setpoint/CoolSetpoint']
    temps.each { attr, path -> Object v = temperatureReading(nested(r, path)); if (v != null) out[attr] = v }
    Object outdoor = outdoorReading(r.DisplayedOutdoorTemperature)
    if (outdoor != null) out.outdoorTemperature = outdoor
    if (r.SystemSwitch != null) { String mode = modeMap().find { it.value == r.SystemSwitch }?.key; if (mode) out.thermostatMode = mode }
    String fan = r.FanSwitch?.Position ?: r.Setpoint?.FanSwitch?.Position
    if (fan) out.thermostatFanMode = fan.toLowerCase()
    String op = r.OperationStatus?.Mode
    if (op) out.thermostatOperatingState = op == 'Heat' ? 'heating' : op == 'Cool' ? 'cooling' : r.OperationStatus?.FanRequest == true ? 'fan only' : 'idle'
    if (r.SystemSwitch == 'Heat' || r.SystemSwitch == 'EmergencyHeat' || op == 'Heat') { if (out.heatingSetpoint != null) out.thermostatSetpoint = out.heatingSetpoint }
    else if (r.SystemSwitch == 'Cool' || op == 'Cool') { if (out.coolingSetpoint != null) out.thermostatSetpoint = out.coolingSetpoint }
    Map bools = [online: 'Online', scheduleEnabled: 'ScheduleEnabled', feelsLikeEnabled: 'FeelsLikeEnabled',
        fanRunning: 'OperationStatus/FanRequest', circulationFan: 'OperationStatus/CirculationFanRequest',
        adaptiveRecoveryActive: 'IsAdaptiveRecoveryActive', vacationHold: 'VacationHold/Enabled', airFilter: 'AirFilterReminders/1/IsAlertRaised']
    bools.each { attr, path -> Object v = nested(r, path); if (v != null) out[attr] = v == true ? 'on' : 'off' }
    if (r.ActiveAdaptiveRecoveryMode != null) out.adaptiveRecoveryEnabled = r.ActiveAdaptiveRecoveryMode in ['None', 'Off'] ? 'off' : 'on'
    if (r.SystemSwitch != null && (c.SystemSwitchCapabilities ?: []).contains('EmergencyHeat')) out.emergencyHeatEnabled = r.SystemSwitch == 'EmergencyHeat' ? 'on' : 'off'
    if (r.ThermostatFaults instanceof List) { out.faultCount = r.ThermostatFaults.size(); out.fault = r.ThermostatFaults ? 'on' : 'off' }
    if (r.FanReqReasons instanceof List && r.FanReqReasons) out.fanReason = r.FanReqReasons.join(', ')
    String fw = r.FirmwareSystem?.SwPackage ?: r.FirmwareUpdate?.SwPackage
    if (fw) out.firmwareVersion = fw
    Map self = (r.ZoneAccessories?.ZoneAccessories ?: []).find { it.AccessoryType == 'Thermostat' } ?: [:]
    if (self.RssiAverage != null) out.signalStrength = self.RssiAverage
    Map configDirect = [heatingSystem: 'SystemConfiguration/HeatingSystem', heatingStages: 'SystemConfiguration/HeatingStages',
        coolingSystem: 'SystemConfiguration/CoolingSystem', coolingStages: 'SystemConfiguration/CoolingStages',
        temperatureUnits: 'TemperatureUnits', matterStatus: 'Matter/CommissioningStatus']
    configDirect.each { attr, path -> Object v = nested(c, path); if (v != null) out[attr] = v }
    Map configBools = [freezeProtection: 'FreezeProtection/Active', freezeProtectionConfigured: 'FreezeProtection/Configured', awayMode: 'AwayModeOverride', commercial: 'CommercialConfiguration']
    configBools.each { attr, path -> Object v = nested(c, path); if (v != null) out[attr] = v == true ? 'on' : 'off' }
    Map configTemps = [heatSetpointMin: 'MinimumHeatSetpointAllowed', heatSetpointMax: 'MaximumHeatSetpointAllowed', coolSetpointMin: 'MinimumCoolSetpointAllowed', coolSetpointMax: 'MaximumCoolSetpointAllowed']
    configTemps.each { attr, path -> Object v = temperatureReading(c[path]); if (v != null) out[attr] = v }
    if (c.FreezeProtection?.Configured == true) {
        Object floor = temperatureReading(c.FreezeProtection.LowLimitDegrees)
        if (floor != null) out.freezeProtectionLowLimit = floor
    }
    if (c.SystemSwitchCapabilities instanceof List) out.supportedThermostatModes = JsonOutput.toJson(modeMap().findAll { k, v -> c.SystemSwitchCapabilities.contains(v) }.keySet().toList())
    if (c.FanSwitchCapabilities?.Positions instanceof List) out.supportedThermostatFanModes = JsonOutput.toJson(['auto', 'circulate', 'on'].findAll { c.FanSwitchCapabilities.Positions.contains(it.capitalize()) })
    Object priority = d.priority?.PriorityStatus
    if (priority != null) out.priorityStatus = priority
    Map builtin = accessories(d.rooms ?: [:]).find { it.accessory.AccessoryAttribute?.Type == 'Thermostat' }?.accessory ?: [:]
    Map sensing = sensorAttributes(builtin)
    ['carbonDioxide', 'voc', 'motion', 'occupancy'].each { if (sensing.containsKey(it)) out[it] = sensing[it] }
    numericReadings(out, ['humidity', 'outdoorHumidity', 'demand', 'currentStage', 'airFilterRemaining', 'signalStrength', 'ventilationTimer', 'ventilationBoostTimer', 'heatingStages', 'coolingStages'])
}
Map sensorAttributes(Map accessory) {
    Map v = accessory.AccessoryValue ?: [:]; Map out = [:]
    [temperature: 'IndoorTemperature', temperatureActual: 'TemperatureActual'].each { k, src -> Object temp = temperatureReading(v[src]); if (temp != null) out[k] = temp }
    [humidity: 'IndoorHumidity', batteryStatus: 'BatteryStatus', signalStrength: 'RssiAverage', status: 'Status', occupancyTimeout: 'OccupancyTimeout', occupancySensitivity: 'OccupancySensitivity'].each { k, src -> if (v[src] != null) out[k] = v[src] }
    [carbonDioxide: 'CarbonDioxide', voc: 'TotalVolatileOrganicCompounds'].each { k, src -> if (v[src]?.Displayed == true && v[src]?.Measurement != null) out[k] = v[src].Measurement }
    if (v.MotionDet != null) out.motion = v.MotionDet ? 'active' : 'inactive'
    if (v.OccupancyDet != null) out.occupancy = v.OccupancyDet ? 'occupied' : 'unoccupied'
    if (v.OccupancySensitivity != null) {
        if (v.ExcludeMotion != null) out.excludeMotion = v.ExcludeMotion ? 'on' : 'off'
        if (v.ExcludeTemp != null) out.excludeTemperature = v.ExcludeTemp ? 'on' : 'off'
    }
    numericReadings(out, ['humidity', 'signalStrength', 'occupancyTimeout', 'carbonDioxide', 'voc'])
}
Map healthAttributes(String mac) {
    Map d = (state.cache ?: [:])[mac] ?: [:]
    String loc = (state.discovered ?: [:])[mac]?.locationId
    Map s = (state.streams ?: [:])[loc] ?: [:]
    String status = !enabled() ? 'disabled' : !state.tokens ? 'authentication required' : s.ready ? 'connected' : 'polling'
    long updated = (d.lastUpdated ?: 0L) as long
    String dataStatus = !enabled() || !state.tokens || d.orphaned || !(state.discovered ?: [:])[mac] || !selectedMacs().contains(mac) ? 'unavailable' :
        d.shadow?.Reported?.Online == false ? 'offline' : !updated || now() - updated > Math.max(900000L, pollingMinutes() * 180000L) ? 'stale' : 'current'
    Map out = [connectionStatus: status, updateSource: s.ready ? 'push' : 'poll', dataStatus: dataStatus]
    if (updated) out.lastUpdated = Instant.ofEpochMilli(updated).toString()
    out
}
void publishDevice(String mac) {
    def thermostat = getChildDevice(deviceId('thermostat', mac))
    Map health = healthAttributes(mac)
    Map healthKey = health.findAll { k, v -> k != 'lastUpdated' }
    Map previousHealth = state.diagnosticHealth ?: [:]
    if (previousHealth[mac] != healthKey) {
        diagnostic('deviceHealth', [device: diagnosticDevice(mac), status: health.connectionStatus, dataStatus: health.dataStatus, source: health.updateSource])
        previousHealth[mac] = healthKey
        state.diagnosticHealth = previousHealth.findAll { k, v -> selectedMacs().contains(k) }
    }
    Map lastThermostat = (state.lastCommand ?: [:])[mac] ?: [:]
    if (thermostat) thermostat.applySnapshot(effectiveAttributes(mac) + health + [commandStatus: lastThermostat.status ?: 'idle', lastCommandError: lastThermostat.error ?: ''])
    Map d = (state.cache ?: [:])[mac] ?: [:]
    getChildDevices().findAll { it.getDataValue('kind') == 'sensor' && it.getDataValue('mac') == mac }.each { sensor ->
        Object aid = sensor.getDataValue('accessoryId')
        Map attrs = effectiveAttributes(mac, aid)
        Map ownHealth = health + (sensor.getDataValue('orphaned') == 'true' ? [dataStatus: 'unavailable'] : [:])
        Map last = (state.lastCommand ?: [:])[pendingKey(mac, aid)] ?: [:]
        sensor.applySnapshot(attrs + ownHealth + [commandStatus: last.status ?: 'idle', lastCommandError: last.error ?: ''])
    }
}
Map diagnosticSnapshot() {
    [version: constants().version, upstream: '0e2947b8ad83608ccf212dd2638f2aa3eda06eaa',
     hubFirmware: location.hub?.firmwareVersionString ?: 'unknown', authentication: state.authStatus,
     queuedRequests: (state.httpQueue ?: []).size(), activeRequests: (state.inFlight ?: [:]).size(),
     logging: [statusLogs: settings.logEnable != false, debug: debugLogging(), debugUntil: state.debugUntil ? Instant.ofEpochMilli(state.debugUntil as long).toString() : null],
     cooldownSeconds: ((state.cooldownUntil ?: 0L) as long) > now() ? remainingCooldown() : 0,
     counters: state.diagnosticCounters ?: [:], recentEvents: state.diagnosticEvents ?: [],
     pendingCommands: (state.pending ?: [:]).findAll { k, v -> v }.collect { key, p ->
         String deviceMac = key.toString().split(':', 2)[0]
         Object aid = key == deviceMac ? null : key.toString().substring(deviceMac.length() + 1)
         [device: diagnosticDevice(deviceMac, aid), fields: p.size(), deferredFields: p.values().count { it.deferred }]
     },
     streams: (state.streams ?: [:]).collect { loc, s -> [id: diagnosticAlias('location', loc), ready: s.ready == true, status: s.status] },
     devices: selectedMacs().collect { mac -> [id: diagnosticDevice(mac), health: healthAttributes(mac),
         unsupportedEndpoints: (state.cache ?: [:])[mac]?.unsupported ?: [],
         sensorCount: accessories((state.cache ?: [:])[mac]?.rooms ?: [:]).count { it.accessory.AccessoryAttribute?.Type == 'IndoorAirSensor' }] }]
}
String operationSummary() { 'Selected thermostats: ' + selectedMacs().size() + '. ' + (state.streams ?: [:]).values().count { it.ready } + ' live location stream(s). Fallback polling: ' + pollingMinutes() + ' minutes.' }
String safeServiceMessage(Map body) {
    String value = (body.message ?: body.Message ?: body.error_description ?: 'Request rejected by cloud service.').toString()
    List secrets = [settings.loginEmail, settings.loginPassword, state.tokens?.access_token, state.tokens?.refresh_token]
    secrets += (state.discovered ?: [:]).keySet()
    secrets.findAll { it }.each { value = value.replace(it.toString(), '[redacted]') }
    value.replaceAll(/(?i)bearer\s+\S+/, 'Bearer [redacted]').replaceAll(/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/, '[redacted]').take(200)
}
Map parseJsonMap(String text) { try { def p = new JsonSlurper().parseText(text ?: '{}'); p instanceof Map ? p : [:] } catch (Exception ignored) { [:] } }
int responseStatus(resp) { try { (resp.getStatus() ?: 0) as int } catch (Exception ignored) { 0 } }
String responseText(resp) {
    try { def body = resp.getData(); if (body != null && body.toString()) return body.toString() } catch (Exception ignored) { }
    // Hubitat exposes bodies for failed HTTP responses through getErrorData.
    try { resp.getErrorData()?.toString() ?: '' } catch (Exception ignored) { '' }
}
Map responseHeaders(resp) {
    Map out = [:]
    try {
        def headers = resp.getHeaders()
        if (headers instanceof Map) for (entry in headers.entrySet()) {
            if (entry.key != null) out[entry.key.toString()] = entry.value
        }
    }
    catch (Exception ignored) { }
    out
}
List headerValues(Map headers, String name) {
    List out = []
    for (entry in headers.entrySet()) {
        if (entry.key != null && entry.key.toString().equalsIgnoreCase(name)) {
            asList(entry.value).each { value ->
                if (value != null) {
                    def actual = value
                    // Hubitat can expose Apache Header objects in its Map.
                    // Header.toString() includes "Name: ", which is not part
                    // of a redirect URL or cookie. Read the value instead.
                    if (!(value instanceof CharSequence) && !(value instanceof Number)) {
                        try { if (value.value != null) actual = value.value } catch (Exception ignored) { }
                    }
                    String text = actual.toString()
                    if (text.regionMatches(true, 0, name + ':', 0, name.length() + 1)) text = text.substring(name.length() + 1).trim()
                    out << text
                }
            }
        }
    }
    out
}
String headerValue(Map headers, String name) { List values = headerValues(headers, name); values ? values[0]?.toString() : null }
