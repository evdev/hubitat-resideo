/* Resideo SignalR connection for Hubitat, v0.1.9. MIT; see LICENSE.upstream.
 * Protocol adapted from sfcodes/ha-resideo 0e2947b (c) 2026 Semyon Fishman.
 */
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

metadata {
    definition(name: 'Resideo Connection', namespace: 'resideoHubitat', author: 'Ephrayim (evdev)', singleThreaded: true) {
        capability 'Initialize'
        capability 'Refresh'
        attribute 'connectionStatus', 'string'
        attribute 'dataStatus', 'string'
    }
    preferences { input 'debugEnable', 'bool', title: 'Driver debug logging for 30 minutes', defaultValue: false }
}

void installed() { state.connectionGeneration = 0L; configureLogging() }
void updated() { initialize(); configureLogging() }
void configureLogging() {
    if (!settings.debugEnable) { state.debugUntil = null; return }
    if (!state.debugUntil) state.debugUntil = now() + 1800000L
    if ((state.debugUntil as long) <= now()) { logsOff(); return }
    runIn(Math.max(1, Math.ceil(((state.debugUntil as long) - now()) / 1000.0d) as int), 'logsOff')
}
void logsOff() { device.updateSetting('debugEnable', [value: 'false', type: 'bool']); state.debugUntil = null }
boolean debugLogging() {
    if (settings.debugEnable != true) return false
    if (!state.debugUntil) configureLogging()
    state.debugUntil != null && now() < (state.debugUntil as long)
}
void driverDebug(String line) {
    if (!debugLogging()) return
    Map window = state.debugWindow ?: [start: now(), count: 0]
    if (now() - (window.start as long) >= 60000L) window = [start: now(), count: 0]
    window.count = (window.count as int) + 1; state.debugWindow = window
    if (window.count <= 120) log.debug line
    else if (window.count == 121) log.warn 'Resideo driver debug log limit reached; further entries suppressed for this minute.'
}
void traceStage(String reason = null) {
    driverDebug 'Resideo connection stage=' + state.stage + '; generation=' + connectionGeneration() + (reason ? '; reason=' + reason : '')
    queueParentMessage(envelope('streamDiagnostic') + [stage: state.stage, reason: reason])
}
String failureCode(String reason) {
    ['WebSocket could not open': 'socketOpenFailed', 'WebSocket disconnected': 'socketDisconnected',
     'Oversized SignalR message': 'oversizedFrame', 'Invalid SignalR frame': 'invalidFrame',
     'SignalR handshake rejected': 'handshakeRejected', 'SignalR subscription rejected': 'subscriptionRejected',
     'SignalR server closed connection': 'serverClosed', 'SignalR stream stalled': 'stalled',
     'Renewing SignalR feed': 'feedRenewal', 'SignalR send failed': 'sendFailed',
     'SignalR setup timed out': 'setupTimeout', 'Negotiation failed': 'negotiationFailed'][reason] ?: 'transport'
}
void initialize() { stopConnection(); queueParentMessage([kind: 'streamInitialize', locationId: getDataValue('locationId')]) }
void refresh() { if (state.enabled) reconnect() }
long connectionGeneration() { (state.connectionGeneration ?: 0L) as long }
long accountGeneration() { (state.accountGeneration ?: 0L) as long }
boolean connectionActive() { state.enabled == true }

void startConnection(long ag) {
    stopConnection()
    state.enabled = true
    state.accountGeneration = ag
    state.backoff = 1
    requestConnection()
}
void stopConnection() {
    state.enabled = false
    state.connectionGeneration = connectionGeneration() + 1L
    state.stage = 'stopped'
    state.buffer = ''
    unschedule()
    configureLogging()
    try { interfaces.webSocket.close() } catch (Exception ignored) { }
}
void requestConnection() {
    if (!state.enabled) return
    state.connectionGeneration = connectionGeneration() + 1L
    state.stage = 'negotiating'; state.buffer = ''; state.subscribeId = null
    unschedule('keepalive'); unschedule('setupTimeout'); unschedule('reconnect')
    try { interfaces.webSocket.close() } catch (Exception ignored) { }
    status('connecting')
    queueParentMessage(envelope('streamRequest'))
    runIn(70, 'setupTimeout', [data: [generation: connectionGeneration()]])
}
void openSocket(String url, long ag, long cg) {
    if (!state.enabled || ag != accountGeneration() || cg != connectionGeneration()) return
    state.stage = 'opening'
    traceStage()
    try { interfaces.webSocket.connect(url) }
    catch (Exception ignored) { connectionFailed('WebSocket could not open') }
}
void webSocketStatus(String message) {
    if (!state.enabled || state.stage in ['stopped', 'backoff', 'negotiating']) return
    if (message == 'status: open' || message.toLowerCase().contains('status: open')) {
        if (state.stage != 'opening') return
        state.stage = 'handshake'; state.lastFrame = now(); state.feedExpiry = now() + 600000L
        traceStage()
        if (!sendFrame([protocol: 'json', version: 1])) return
        runIn(20, 'setupTimeout', [data: [generation: connectionGeneration()]])
    } else if (message.toLowerCase().contains('failure') || message.toLowerCase().contains('closed') || message.toLowerCase().contains('closing')) {
        connectionFailed('WebSocket disconnected')
    }
    // Never log the raw status; it can include the token-bearing connection URL.
}
void parse(String text) {
    if (!state.enabled || !(state.stage in ['handshake', 'subscribing', 'connected'])) return
    state.lastFrame = now()
    String buffer = (state.buffer ?: '') + text
    if (buffer.length() > 262144) { connectionFailed('Oversized SignalR message'); return }
    int delimiter
    while ((delimiter = buffer.indexOf('\u001e')) >= 0) {
        String part = buffer.substring(0, delimiter)
        buffer = buffer.substring(delimiter + 1)
        if (part) {
            try {
                def value = new JsonSlurper().parseText(part)
                if (value instanceof Map) handleFrame(value)
                else { connectionFailed('Invalid SignalR frame'); return }
            } catch (Exception ignored) { connectionFailed('Invalid SignalR frame'); return }
        }
        if (state.stage == 'backoff') return
    }
    state.buffer = buffer
}
void handleFrame(Map frame) {
    if (state.stage == 'handshake') {
        if (frame.error || frame.type != null) { connectionFailed('SignalR handshake rejected'); return }
        state.stage = 'subscribing'
        traceStage()
        state.subscribeId = '1'
        if (!sendFrame([type: 1, invocationId: state.subscribeId, target: 'SubscribeSignalRV2', arguments: [getDataValue('locationId')]])) return
        runIn(20, 'setupTimeout', [data: [generation: connectionGeneration()]])
        runIn(15, 'keepalive', [data: [generation: connectionGeneration()]])
        return
    }
    if (frame.type == 3 && frame.invocationId?.toString() == state.subscribeId) {
        if (frame.error || frame.result == false) { connectionFailed('SignalR subscription rejected'); return }
        if (state.stage == 'subscribing') {
            state.stage = 'connected'; state.backoff = 1
            unschedule('setupTimeout')
            status('connected')
            queueParentMessage(envelope('streamActivated'))
        }
        return
    }
    if (frame.type == 7) { connectionFailed('SignalR server closed connection'); return }
    if (frame.type != 1 || frame.target != 'events') return
    (frame.arguments ?: []).each { arg ->
        Map event
        try { def parsed = arg instanceof String ? new JsonSlurper().parseText(arg) : arg; event = parsed instanceof Map ? parsed : null }
        catch (Exception ignored) { event = null }
        if (!event) { traceStage('invalidNotification'); return }
        noteExpiry(event.Body?.SubscriptionExpiration?.toString())
        queueParentMessage(envelope('streamEvent') + [event: event])
    }
}
void noteExpiry(String value) {
    if (!value) return
    try {
        String normalized = value.replace('Z', '+0000').replaceAll(/([+-]\d\d):(\d\d)$/, '$1$2')
        // Resideo can include seven fractional digits; Date expects milliseconds.
        normalized = normalized.replaceAll(/\.(\d{3})\d+/, '.$1')
        long expiry = Date.parse(normalized.contains('.') ? "yyyy-MM-dd'T'HH:mm:ss.SSSZ" : "yyyy-MM-dd'T'HH:mm:ssZ", normalized).time
        if (expiry > now() + 120000L) state.feedExpiry = expiry
        else traceStage('staleExpiry')
    } catch (Exception ignored) { traceStage('invalidExpiry') }
}
void keepalive(Map ctx) {
    if (!state.enabled || ctx.generation != connectionGeneration() || !(state.stage in ['subscribing', 'connected'])) return
    if (now() - ((state.lastFrame ?: 0L) as long) >= 45000L) { connectionFailed('SignalR stream stalled'); return }
    if (now() >= ((state.feedExpiry ?: 0L) as long) - 120000L) { connectionFailed('Renewing SignalR feed'); return }
    if (!sendFrame([type: 6])) return
    runIn(15, 'keepalive', [data: ctx])
}
boolean sendFrame(Map frame) {
    try { interfaces.webSocket.sendMessage(JsonOutput.toJson(frame) + '\u001e'); return true }
    catch (Exception ignored) { connectionFailed('SignalR send failed'); false }
}
void setupTimeout(Map ctx) {
    if (state.enabled && ctx.generation == connectionGeneration() && state.stage != 'connected') connectionFailed('SignalR setup timed out')
}
void connectionFailed(String reason) {
    if (!state.enabled || state.stage == 'backoff') return
    String failedStage = state.stage
    String code = failureCode(reason)
    state.stage = 'backoff'; state.buffer = ''
    unschedule('keepalive'); unschedule('setupTimeout')
    int delay = (state.backoff ?: 1) as int
    status('disconnected', [reason: code, stage: failedStage, retrySeconds: delay])
    try { interfaces.webSocket.close() } catch (Exception ignored) { }
    state.backoff = Math.min(60, delay * 2)
    runIn(delay, 'reconnect')
}
void reconnect() { requestConnection() }
Map envelope(String kind) { [kind: kind, locationId: getDataValue('locationId'), accountGeneration: accountGeneration(), connectionGeneration: connectionGeneration()] }
void status(String value, Map details = [:]) {
    driverDebug 'Resideo connection status=' + value + '; generation=' + connectionGeneration() + (details.reason ? '; reason=' + details.reason + '; retrySeconds=' + details.retrySeconds : '')
    applyAttributes([connectionStatus: value]); queueParentMessage(envelope('streamStatus') + [status: value] + details)
}
void applyAttributes(Map values) {
    values.findAll { k, v -> k in ['connectionStatus', 'dataStatus'] && v != null }.each { k, v ->
        if (device.currentValue(k.toString()) != v) sendEvent(name: k, value: v)
    }
}


// Notify the app through a native location event. Only the child ID is public;
// message bodies stay in this driver's bounded, private outbox until drained.
// A driver must never call a single-threaded parent while holding its own gate.
void queueParentMessage(Map message) {
    Map packet = [accountGeneration: (getDataValue('accountGeneration') ?: 0L) as long] + message
    List outbox = state.bridgeOutbox ?: []
    int bytes = groovy.json.JsonOutput.toJson(packet).length()
    int used = (state.bridgeBytes ?: 0) as int
    if (outbox.size() < 64 && used + bytes <= 262144) {
        outbox << packet; state.bridgeOutbox = outbox; state.bridgeBytes = used + bytes
    } else {
        if (!state.bridgeOverflow && settings.logEnable != false) log.warn 'Resideo driver message queue is full; a state refresh will be requested.'
        state.bridgeOverflow = true
        if (message.kind == 'command') sendEvent(name: 'commandStatus', value: 'failed')
    }
    String owner = getDataValue('appId')
    if (owner) sendLocationEvent(name: 'resideoBridge-' + owner, value: device.id.toString(), isStateChange: true)
}
List drainMessages() {
    List outbox = state.bridgeOutbox ?: []
    state.bridgeOutbox = []; state.bridgeBytes = 0
    if (state.bridgeOverflow) {
        state.bridgeOverflow = false
        outbox << [kind: 'bridgeOverflow', accountGeneration: (getDataValue('accountGeneration') ?: 0L) as long,
            mac: getDataValue('mac'), locationId: getDataValue('locationId')]
    }
    outbox
}
