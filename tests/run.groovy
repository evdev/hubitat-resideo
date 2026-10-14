import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import org.codehaus.groovy.control.CompilerConfiguration

assert GroovySystem.version == '2.4.21' : 'Use Groovy 2.4.21 to test Hubitat compatibility.'
def loader = new GroovyClassLoader(getClass().classLoader)
loader.parseClass(new File('tests/HubitatHarness.groovy'))
def config = new CompilerConfiguration(scriptBaseClass: 'HubitatScript')
def shell = new GroovyShell(loader, new Binding(), config)
def load = { String path -> def s = shell.parse(new File(path).text, new File(path).name.replace('-', '_')); s.run(); s }
def app = { -> load('apps/resideo-integration.groovy') }
def fixture = { String name -> new JsonSlurper().parse(new File('tests/fixtures/' + name + '.json')) }
String mac = 'AABBCCDDEEFF'
def ready = { ->
    def s = app()
    s.settings.selectedThermostats = [mac]
    s.state.generation = 1L
    s.state.tokens = [access_token: 'access-secret', refresh_token: 'refresh-secret', expiresAt: s.now() + 3600000L]
    s.state.discovered = [(mac): [name: 'Thermostat', locationId: 'loc', locationName: 'Home']]
    s.state.cache = [(mac): [shadow: fixture('device'), configuration: fixture('configuration'), rooms: fixture('rooms'), lastUpdated: s.now()]]
    s.ensureChild('Resideo Thermostat', s.deviceId('thermostat', mac), 'Thermostat', [mac: mac, kind: 'thermostat'])
    s.synchronizeSensors(mac)
    s
}
def messages = { drv -> drv.state.bridgeOutbox ?: [] }
int passed = 0
def test = { String name, Closure body ->
    try { body(); passed++; println('PASS ' + name) }
    catch (Throwable e) { println('FAIL ' + name); throw e }
}
def rejected = { Closure body -> boolean failed = false; try { body() } catch (Exception e) { assert e.message.startsWith('Resideo validation: '); failed = true }; assert failed }
def command = { String name, List args = [], Object aid = null -> [mac: mac, command: name, args: args, accessoryId: aid] }

test('All four deployment scripts compile as Groovy 2.4 and declare serialized execution') {
    ['apps/resideo-integration.groovy', 'drivers/resideo-connection.groovy', 'drivers/resideo-thermostat.groovy', 'drivers/resideo-room-sensor.groovy'].each { path ->
        def s = load(path)
        assert s.metadataCalls.find { it[0] == 'definition' }[1].singleThreaded
    }
}
test('PKCE has independent state, verifier, and a SHA-256 challenge') {
    def s = app(); Map a = s.newAuthorization(); Map b = s.newAuthorization()
    assert a.nonce != b.nonce && a.verifier != b.verifier
    Map q = s.parseQuery(a.url)
    assert q.code_challenge_method == 'S256'
    assert q.code_challenge == s.base64url(java.security.MessageDigest.getInstance('SHA-256').digest(a.verifier.getBytes('US-ASCII')))
    assert q.redirect_uri == s.constants().redirect
    assert q.scope.contains('offline_access')
    rejected { s.parseQuery('https://x/?code=a&code=b') }
}
test('Browser sign-in validates state and rejects incomplete redirects') {
    def s = app(); s.startBrowserLogin()
    s.settings.browserRedirect = s.constants().browserRedirect + '?code=abc&state=wrong'
    s.finishBrowserLogin(); assert !s.requests && !s.settings.browserRedirect
    s.startBrowserLogin(); String nonce = s.state.browserAttempt.nonce
    s.settings.browserRedirect = s.constants().browserRedirect + '?code=a%2Bb&state=' + nonce
    s.finishBrowserLogin()
    assert s.requests.size() == 1
    Map body = new JsonSlurper().parseText(s.requests[0].params.body)
    assert body.code == 'a+b' && body.code_verifier
}
test('Password login performs all six steps with cookies, form decoding, and explicit redirects') {
    def s = app(); s.settings.loginEmail = 'test@example.com'; s.settings.loginPassword = 'password-secret'
    s.startPasswordLogin(); String nonce = s.state.loginAttempt.nonce
    s.respond(s.requests[-1], 302, '', [Location: '/login?state=auth-state', 'Set-Cookie': ['sess=x; Path=/']])
    s.respond(s.requests[-1], 200, '<html/>', ['Set-Cookie': ['_csrf=csrf-value; Path=/', 'other=value; Path=/']])
    Map body = new JsonSlurper().parseText(s.requests[-1].params.body)
    assert body._csrf == 'csrf-value' && body.password == 'password-secret'
    assert s.requests[-1].params.headers.Cookie.contains('sess=x')
    s.respond(s.requests[-1], 200, '<input value="&lt;xml a=&quot;b&quot;&gt;" name="wresult"><input name="wctx" value="a&amp;b">')
    assert s.requests[-1].params.requestContentType == 'application/x-www-form-urlencoded'
    assert s.requests[-1].params.body.contains('wresult=%3Cxml+a%3D%22b%22%3E')
    s.respond(s.requests[-1], 302, '', [Location: '/authorize/resume'])
    s.respond(s.requests[-1], 302, '', [Location: 'com.resideo.firstalert://callback?code=code&state=' + nonce])
    s.respond(s.requests[-1], 200, [access_token: 'new-access', refresh_token: 'new-refresh', expires_in: 3600])
    assert s.state.tokens.refresh_token == 'new-refresh'
    assert !s.settings.loginPassword && !s.state.loginAttempt
    assert s.requests.take(6).every { it.params.followRedirects == false }
}
test('CAPTCHA and MFA errors offer browser sign-in and clear passwords') {
    ['invalid_captcha', 'mfa_required'].each { code ->
        def s = app(); s.settings.loginEmail = 'test@example.com'; s.settings.loginPassword = 'secret'; s.startPasswordLogin()
        s.state.authRequest.step = 'credentials'; Map req = s.requests[-1]; req.data.step = 'credentials'
        s.respond(req, 400, [code: code]); assert s.state.lastError.contains('browser sign-in'); assert !s.settings.loginPassword
    }
}
test('Token refresh is single flight and persists rotation before releasing waiting reads') {
    def s = ready(); s.state.tokens.expiresAt = 0L
    s.enqueueRead('shadow', mac, s.thermostatPath(mac), [:]); s.refreshTokens(); s.refreshTokens()
    assert s.requests.size() == 1
    s.respond(s.requests[0], 200, [access_token: 'rotated-access', refresh_token: 'rotated-refresh', expires_in: 3600])
    assert s.state.tokens.refresh_token == 'rotated-refresh'
    assert s.requests[1].params.headers.Authorization == 'Bearer rotated-access'
}
test('Ambiguous refresh timeout requires reauthentication without replay') {
    def s = ready(); s.state.tokens.expiresAt = 0L; s.refreshTokens(); s.advance(35000L)
    assert !s.state.tokens && s.state.authStatus == 'Reauthentication required'
    assert s.requests.size() == 1
    s.respond(s.requests[0], 200, [access_token: 'late', refresh_token: 'late'])
    assert !s.state.tokens
}
test('Discovery excludes smoke detectors and preserves renamed devices and sensor identity') {
    def s = app(); s.settings.selectedThermostats = [mac]; s.state.generation = 1L
    s.discoverAccounts(fixture('accounts'))
    assert s.state.discovered.keySet() == [mac] as Set
    def t = s.getChildDevice(s.deviceId('thermostat', mac)); t.displayName = 'My custom label'
    s.state.cache = [(mac): [rooms: fixture('rooms')]]; s.synchronizeSensors(mac)
    String sid = s.deviceId('sensor', mac, 1)
    s.state.cache[mac].rooms.Rooms[1].Id = 99; s.state.cache[mac].rooms.Rooms[1].Name = 'New name'
    s.discoverAccounts(fixture('accounts')); s.synchronizeSensors(mac)
    assert s.getChildDevice(sid).getDataValue('roomId') == '99'
    assert t.displayName == 'My custom label'
    assert s.children.values().count { it.getDataValue('kind') == 'sensor' } == 1
    s.discoverAccounts([data: [consumerUsers: []]])
    assert t.getDataValue('orphaned') == 'true'
}
test('Attribute projection covers standard and advanced fields and categorical battery') {
    def s = ready(); Map a = s.thermostatAttributes(s.state.cache[mac])
    assert a.temperature == 76.0 && a.humidity == 56 && a.thermostatMode == 'cool'
    assert a.thermostatOperatingState == 'cooling' && a.heatingSetpoint == 62.0
    assert a.adaptiveRecoveryEnabled == 'on' && a.fault == 'off'
    assert a.carbonDioxide == 425 && a.motion == 'active' && a.occupancy == 'occupied'
    assert a.firmwareVersion == '01.3605.740' && a.matterStatus == 'Commissioned'
    Map sensor = s.sensorAttributes(s.findAccessory(s.state.cache[mac].rooms, 1))
    assert sensor.batteryStatus == 'Ok' && !sensor.containsKey('battery')
    assert sensor.occupancySensitivity == 'Medium'
}
test('Hub Celsius conversions and account-country outdoor units are independent') {
    def s = ready(); s.location.temperatureScale = 'C'; s.state.countryCode = 'US'
    assert s.fromF(77) == 25 && s.toF(25) == 77
    assert s.outdoorValue(77) == 25
    s.state.countryCode = 'CA'; assert s.outdoorValue(25) == 25
    s.location.temperatureScale = 'F'; assert s.outdoorValue(25) == 77
    rejected { s.buildCommand(command('setCoolingSetpoint', [25])) }
    s.location.temperatureScale = 'C'
    assert s.buildCommand(command('setCoolingSetpoint', [25])).body.SetpointValue == 77
}
test('Unsupported modes, invalid input, bounds, deadband, and schedule-less holds are rejected') {
    def s = ready()
    rejected { s.buildCommand(command('setHold', ['temporary'])) }
    rejected { s.buildCommand(command('setThermostatMode', ['invalid'])) }
    rejected { s.buildCommand(command('setCoolingSetpoint', ['NaN'])) }
    rejected { s.buildCommand(command('setHeatingSetpoint', [99])) }
    s.state.cache[mac].configuration.Reported.SetpointDeadband = 3
    rejected { s.buildCommand(command('setHeatingSetpoint', [75])) }
    assert s.buildCommand(command('setThermostatMode', ['emergency heat'])).body.SystemSwitchValue == 'EmergencyHeat'
    s.state.cache[mac].configuration.Reported.SystemSwitchCapabilities = ['Heat', 'Off']
    rejected { s.buildCommand(command('setThermostatMode', ['cool'])) }
}
test('All writable advanced settings use exact endpoints and complete bodies') {
    def s = ready()
    Map limits = s.buildCommand(command('setSetpointLimits', [50, 85, 55, 90]))
    assert limits.path.endsWith('/v1/devices/thermostats/' + mac + '/setPointCapabilities')
    assert limits.body == [minHeatSetpoint: 50, maxHeatSetpoint: 85, minCoolSetpoint: 55, maxCoolSetpoint: 90, ChannelId: 'ds-notification-service']
    rejected { s.buildCommand(command('setSetpointLimits', [60, 85, 55, 90])) }
    Map sensor = s.buildCommand(command('setExcludeMotion', ['on'], 1))
    assert sensor.body == [sensitivity: 'Medium', excludeMotion: true, excludeTemp: false, ChannelId: 'ds-notification-service']
    assert s.buildCommand(command('setFeelsLikeEnabled', ['off'])).body.FeelsLikeEnabled == false
    assert s.buildCommand(command('setAdaptiveRecoveryEnabled', ['on'])).body.Mode == 'AdaptiveIntelligentRecovery'
    s.state.cache[mac].configuration.Reported.FreezeProtection.Configured = true
    assert s.buildCommand(command('setFreezeProtectionLowLimit', [40.6])).body.LowLimitDegrees == 41
}
test('Reads coalesce and never exceed two active network requests') {
    def s = ready(); s.refreshDevice(mac, true); s.refreshDevice(mac, true)
    assert s.requests.size() == 2 && s.state.httpQueue.size() == 2
    s.respond(s.requests[0], 200, fixture('device'))
    assert s.requests.size() == 3 && s.state.inFlight.size() == 2
}
test('Commands serialize per thermostat and accepted fields confirm only from state') {
    def s = ready(); s.acceptCommand(command('setFeelsLikeEnabled', ['off'])); s.acceptCommand(command('setScheduleEnabled', ['on']))
    assert s.requests.size() == 1
    s.respond(s.requests[0], 202, [TransactionId: 'tx1']); assert s.requests.size() == 2
    assert s.state.pending[mac].feelsLikeEnabled.value == 'off'
    s.state.cache[mac].shadow.Reported.FeelsLikeEnabled = false; s.reconcilePending(mac, false)
    assert !s.state.pending[mac].feelsLikeEnabled
    assert s.state.lastCommand[mac].status == 'confirmed'
}
test('Rapid sensor writes preserve accepted pending full-body settings') {
    def s = ready()
    s.acceptCommand(command('setOccupancySensitivity', ['high'], 1)); s.acceptCommand(command('setExcludeMotion', ['on'], 1))
    s.respond(s.requests[0], 202, [TransactionId: 'sensitivity'])
    Map nextBody = new JsonSlurper().parseText(s.requests[1].params.body)
    assert nextBody.sensitivity == 'High' && nextBody.excludeMotion == true
    s.respond(s.requests[1], 202, [TransactionId: 'exclude'])
    s.reconcilePending(mac, true)
    assert s.state.pending[mac + ':1'].occupancySensitivity.value == 'High'
    s.state.cache[mac].rooms.Rooms[1].Accessories[0].AccessoryValue.OccupancySensitivity = 'High'
    s.reconcilePending(mac, false)
    assert !s.state.pending[mac + ':1'].occupancySensitivity
}
test('A timed-out write is not retransmitted and late HTTP responses are ignored') {
    def s = ready(); s.acceptCommand(command('setFeelsLikeEnabled', ['off'])); Map request = s.requests[0]
    s.advance(35000L)
    assert s.requests.count { it.verb == 'PUT' } == 1
    assert s.effectiveAttributes(mac).feelsLikeEnabled == 'on'
    assert s.state.lastCommand[mac].status == 'uncertain'
    s.state.cache[mac].shadow.Reported.FeelsLikeEnabled = false; s.reconcilePending(mac, false)
    assert s.state.lastCommand[mac].status == 'confirmed'
    s.respond(request, 202, [TransactionId: 'late'])
    assert s.requests.count { it.verb == 'PUT' } == 1
}
test('Explicit 401 refreshes once; rate limits defer queued reads') {
    def s = ready(); s.enqueueRead('shadow', mac, s.thermostatPath(mac), [:])
    s.respond(s.requests[0], 401, [:]); assert s.requests[1].params.uri.endsWith('/oauth/token')
    s.respond(s.requests[1], 200, [access_token: 'renewed', refresh_token: 'renewed-refresh', expires_in: 3600])
    assert s.requests[2].params.headers.Authorization == 'Bearer renewed'
    s.respond(s.requests[2], 429, [:], ['Retry-After': '120'])
    assert s.state.cooldownUntil == s.now() + 120000L
    int count = s.requests.size(); s.advance(119000L); assert s.requests.size() == count
}
test('Known live-feed shapes merge without erasing unrelated fields') {
    def s = ready(); Map d = s.state.cache[mac]
    ['live_setpoint', 'live_operation_status', 'live_system_switch', 'live_fan_switch', 'live_displayed_indoor_humidity', 'live_displayed_outdoor_temperature', 'live_rooms'].each { f ->
        Map ev = fixture(f); Map merged = s.mergeLive(d.shadow, d.rooms, ev.Body)
        assert merged.applied
        assert merged.shadow.Reported.FirmwareSystem == d.shadow.Reported.FirmwareSystem
        assert merged.rooms.Rooms[1].Accessories
    }
    Map merged = s.mergeLive(d.shadow, d.rooms, [PropertyName: 'Setpoint', Value: [HeatSetpoint: 66]])
    assert merged.shadow.Reported.Setpoint.CoolSetpoint == 76
    assert d.shadow.Reported.Setpoint.HeatSetpoint == 62
    assert !s.mergeLive(d.shadow, d.rooms, [PropertyName: 'Unknown', Value: [:]]).applied
}
test('Off-by-one sensor headers route to the successor and never change control temperature') {
    def s = ready(); Map d = s.state.cache[mac]
    Map merged = s.mergeLive(d.shadow, d.rooms, fixture('live_sensor_remote').Body)
    assert merged.rooms.Rooms[1].Accessories[0].AccessoryValue.IndoorTemperature == 70
    assert merged.rooms.Rooms[0].Accessories[0].AccessoryValue.IndoorTemperature == 76
    assert merged.shadow.Reported.DisplayedIndoorTemperature == 76
    Map remote = fixture('live_sensor_remote').Body; remote.Value.AccessoryId = 999
    assert !s.mergeLive(d.shadow, d.rooms, remote).applied
    Map builtin = s.mergeLive(d.shadow, d.rooms, fixture('live_sensor_builtin').Body)
    assert builtin.applied
}
test('SignalR parses partial/coalesced frames and activates only after subscription completion') {
    def s = load('drivers/resideo-connection.groovy'); s.device.data.locationId = 'loc'
    s.startConnection(1L); long cg = s.connectionGeneration(); s.openSocket('wss://test', 1L, cg)
    s.webSocketStatus('status: open'); assert s.interfaces.webSocket.sent[-1].contains('"protocol":"json"')
    s.parse('{'); s.parse('}\u001e'); assert s.state.stage == 'subscribing'
    assert !messages(s).any { it.kind == 'streamActivated' }
    s.parse('{"type":3,"invocationId":"1"}\u001e{"type":6}\u001e')
    assert s.state.stage == 'connected'
    assert messages(s).count { it.kind == 'streamActivated' } == 1
    Map ev = fixture('live_setpoint')
    s.parse(JsonOutput.toJson([type: 1, target: 'events', arguments: [JsonOutput.toJson(ev)]]) + '\u001e')
    assert messages(s)[-1].event.DeviceId == mac
}
test('SignalR rejection, stalled connections, renewal, and stale expiry timestamps') {
    def s = load('drivers/resideo-connection.groovy'); s.device.data.locationId = 'loc'
    s.startConnection(1L); s.openSocket('wss://test', 1L, s.connectionGeneration()); s.webSocketStatus('status: open')
    s.parse('{}\u001e'); s.parse('{"type":3,"invocationId":"1","error":"denied"}\u001e')
    assert s.state.stage == 'backoff'; s.advance(1000L); assert s.state.stage == 'negotiating'
    s.state.stage = 'connected'; s.state.lastFrame = s.now() - 45000L; s.keepalive([generation: s.connectionGeneration()])
    assert s.state.stage == 'backoff'
    s.state.feedExpiry = s.now() + 600000L
    s.noteExpiry('2020-01-01T00:00:00.000+00:00'); assert s.state.feedExpiry == s.now() + 600000L
    s.state.stage = 'connected'; s.state.lastFrame = s.now(); s.state.feedExpiry = s.now() + 119000L
    s.keepalive([generation: s.connectionGeneration()]); assert s.state.stage == 'backoff'
}
test('Azure negotiation verifies host and includes both connection and access tokens') {
    def s = ready(); def c = s.ensureChild('Resideo Connection', s.connectionId('loc'), 'Connection', [kind: 'connection', locationId: 'loc'])
    c.startConnection(s.generation()); Map ctx = [locationId: 'loc', connectionGeneration: c.cg]
    s.azureNegotiate([context: ctx], [url: 'https://x.service.signalr.net/client/?hub=h', accessToken: 'azure'])
    assert s.requests[-1].params.uri.contains('/client/negotiate?')
    assert s.requests[-1].params.headers.Authorization == 'Bearer azure'
    assert !s.requests[-1].params.headers['Ocp-Apim-Subscription-Key']
    s.respond(s.requests[-1], 200, [connectionToken: 'c+token'])
    assert c.calls[-1][1].contains('id=c%2Btoken&access_token=azure')
    assert !s.trustedSignalrUrl('https://x.service.signalr.net.attacker.test/client/')
}
test('Polling remains active until subscription, activation, and REST resync succeed') {
    def s = ready(); def c = s.ensureChild('Resideo Connection', s.connectionId('loc'), 'Connection', [kind: 'connection', locationId: 'loc']); c.startConnection(1L)
    s.activateStream([locationId: 'loc', connectionGeneration: c.cg])
    assert !s.state.streams.loc.ready
    s.state.streams.loc.activated = true; s.state.cache[mac].lastRestBootstrap = c.cg; s.updateStreamReadiness(mac)
    assert s.state.streams.loc.ready
    int count = s.requests.size(); s.maintenance(); assert s.requests.size() == count
    s.state.streams.loc.ready = false; s.state.cache[mac].lastUpdated = s.now() - 1000000L
    assert s.healthAttributes(mac).dataStatus == 'stale'
    s.state.cache[mac].shadow.Reported.Online = false; assert s.healthAttributes(mac).dataStatus == 'offline'
}
test('Startup preserves pending sensor settings and invalidates prior callbacks') {
    def s = ready(); s.acceptCommand(command('setOccupancySensitivity', ['high'], 1))
    s.respond(s.requests[0], 202, [TransactionId: 'sensitivity'])
    Map old = s.requests[0]; long before = s.generation(); s.initialize()
    assert s.generation() > before
    assert s.state.pending[mac + ':1'].occupancySensitivity.value == 'High'
    s.respond(old, 202, [TransactionId: 'late']); assert s.state.pending[mac + ':1'].occupancySensitivity.transaction == 'sensitivity'
}
test('Diagnostics use a whitelist and never export account credentials or identifiers') {
    def s = ready(); s.settings.loginEmail = 'private@example.com'; s.settings.loginPassword = 'private-password'
    String json = JsonOutput.toJson(s.diagnosticSnapshot())
    ['private@example.com', 'private-password', 'access-secret', 'refresh-secret', mac, '000000000000'].each { assert !json.contains(it) }
    assert !s.safeServiceMessage([message: 'Bearer access-secret for private@example.com ' + mac]).contains('access-secret')
    assert s.escapeHtml('<script>') == '&lt;script&gt;'
}
test('Driver commands delegate and unsupported schedule editing reports a clear error') {
    def t = load('drivers/resideo-thermostat.groovy'); t.device.data.mac = mac
    t.emergencyHeat(); assert messages(t)[-1].args == ['emergency heat']
    t.setSchedule([:]); assert t.device.attributes.commandStatus == 'rejected'
    t.applyAttributes([temperature: 72, dataStatus: 'current']); int count = t.device.events.size()
    t.applyAttributes([temperature: 72]); assert t.device.events.size() == count
    def room = load('drivers/resideo-room-sensor.groovy'); room.device.data = [mac: mac, accessoryId: '1']
    room.setExcludeTemperature('on'); assert messages(room)[-1].accessoryId == '1'
}
test('Every pinned upstream entity has a declared and populated Hubitat equivalent') {
    List matrix = new JsonSlurper().parse(new File('tests/parity.json'))
    assert matrix.size() == 79
    assert matrix.collect { it.platform + ':' + it.scope + ':' + it.key }.unique().size() == matrix.size()
    def s = ready()
    s.state.cache[mac].priority = [PriorityStatus: 'NoHold']
    s.state.cache[mac].configuration.Reported.FreezeProtection = [Configured: true, Active: false, LowLimitDegrees: 40]
    Map a = s.findAccessory(s.state.cache[mac].rooms, 1)
    a.AccessoryValue.CarbonDioxide = [Measurement: 500, Displayed: true]
    a.AccessoryValue.TotalVolatileOrganicCompounds = [Measurement: 2, Displayed: true]
    Map attrs = [thermostat: s.thermostatAttributes(s.state.cache[mac]), sensor: s.sensorAttributes(a)]
    Map scripts = [thermostat: load('drivers/resideo-thermostat.groovy'), sensor: load('drivers/resideo-room-sensor.groovy')]
    Map standard = [thermostat: ['temperature', 'humidity', 'thermostatMode', 'thermostatFanMode', 'thermostatOperatingState', 'heatingSetpoint', 'coolingSetpoint', 'motion', 'carbonDioxide'], sensor: ['temperature', 'humidity', 'motion', 'carbonDioxide']]
    matrix.each { row ->
        List declared = scripts[row.scope].metadataCalls.findAll { it[0] == 'attribute' }.collect { it[1][0] } + standard[row.scope]
        row.attributes.each { attr ->
            assert declared.contains(attr) : 'Undeclared parity attribute: ' + attr
            assert attrs[row.scope].containsKey(attr) : 'Unpopulated parity attribute: ' + attr
        }
        if (row.platform != 'climate' && row.command != '—') assert scripts[row.scope].metadataCalls.any { it[0] == 'command' && it[1][0] == row.command }
    }
}
test('Sparse snapshots clear unsupported readings and retain an inactive combined setpoint') {
    def t = load('drivers/resideo-thermostat.groovy')
    t.applySnapshot([temperature: 72, carbonDioxide: 500, thermostatSetpoint: 74])
    t.applySnapshot([temperature: 72])
    assert !t.device.attributes.containsKey('carbonDioxide')
    assert t.device.attributes.thermostatSetpoint == 74
    def s = ready(); s.state.cache[mac].shadow.Reported.SystemSwitch = 'Auto'; s.state.cache[mac].shadow.Reported.OperationStatus.Mode = 'EquipmentOff'
    assert !s.effectiveAttributes(mac).containsKey('thermostatSetpoint')
}
test('Read-only unsupported hardware leaves custom fields unset and rejects writes') {
    def s = ready(); s.state.cache[mac].configuration = [Reported: [SystemSwitchCapabilities: ['Heat', 'Off']]]
    s.state.cache[mac].shadow = [Reported: [Online: true, SystemSwitch: 'Heat', Setpoint: [HeatSetpoint: 65, CoolSetpoint: 75]]]
    s.state.cache[mac].rooms = [:]
    Map attrs = s.thermostatAttributes(s.state.cache[mac])
    assert !attrs.containsKey('feelsLikeEnabled') && !attrs.containsKey('carbonDioxide')
    rejected { s.buildCommand(command('setAdaptiveRecoveryEnabled', ['on'])) }
    rejected { s.buildCommand(command('setOccupancySensitivity', ['high'], 1)) }
    rejected { s.buildCommand(command('setFreezeProtectionLowLimit', [40])) }
}
test('Pre-deadline or unrelated endpoint reads do not roll back pending advanced settings') {
    def s = ready(); s.acceptCommand(command('setSetpointLimits', [50, 85, 55, 90]))
    s.respond(s.requests[0], 202, [TransactionId: 'limits'])
    s.advance(10000L)
    s.reconcilePending(mac, true, 'shadow', s.now())
    assert s.state.pending[mac].heatSetpointMax.value == 85
    s.reconcilePending(mac, true, 'configuration', s.now() - 10000L)
    assert s.state.pending[mac].heatSetpointMax.value == 85
    s.reconcilePending(mac, true, 'configuration', s.now())
    assert !s.state.pending[mac]
    assert s.state.lastCommand[mac].status == 'unconfirmed'
}
test('Post-write reconciliation is not absorbed by a read dispatched before the write') {
    def s = ready(); s.enqueueRead('shadow', mac, s.thermostatPath(mac), [:])
    s.advance(10000L)
    s.enqueueRead('shadow', mac, s.thermostatPath(mac), [reconcile: true])
    assert s.state.httpQueue.size() == 1
    s.respond(s.requests[0], 200, fixture('device'))
    assert s.requests.size() == 2 && s.state.inFlight.values()[0].context.reconcile
}
test('Dispatch success alone does not confirm a change; dispatch failure restores reported state') {
    def s = ready(); def c = s.ensureChild('Resideo Connection', s.connectionId('loc'), 'Connection', [kind: 'connection', locationId: 'loc']); c.startConnection(1L)
    s.acceptCommand(command('setFeelsLikeEnabled', ['off'])); s.respond(s.requests[0], 202, [TransactionId: 'tx'])
    Map message = [locationId: 'loc', connectionGeneration: c.cg, event: [DeviceId: mac, NotificationType: 'ChangeRequestSuccess', Body: [TransactionId: 'tx']]]
    s.handleStreamEvent(message); assert s.state.pending[mac].feelsLikeEnabled
    message.event.NotificationType = 'ChangeRequestFailure'; s.handleStreamEvent(message)
    assert !s.state.pending[mac].feelsLikeEnabled
    assert s.getChildDevice(s.deviceId('thermostat', mac)).attributes.feelsLikeEnabled == 'on'
    assert s.state.lastCommand[mac].status == 'failed'
}
test('Combined setpoint reflects accepted values in the active mode') {
    def s = ready(); s.acceptCommand(command('setCoolingSetpoint', [78])); s.respond(s.requests[0], 202, [TransactionId: 'cool'])
    assert s.effectiveAttributes(mac).thermostatSetpoint == 78
    s = ready()
    s.state.cache[mac].shadow.Reported.ScheduleEnabled = true; s.state.cache[mac].shadow.Reported.Setpoint.SetpointStatus = 'NoHold'
    assert s.buildCommand(command('setCoolingSetpoint', [77])).body.SetpointStatus == 'TemporaryHold'
}
test('Device deselection blocks already queued changes') {
    def s = ready(); s.settings.selectedThermostats = []
    rejected { s.buildCommand(command('setFeelsLikeEnabled', ['off'])) }
    s.acceptCommand(command('setFeelsLikeEnabled', ['off'])); assert !s.requests
    assert s.getChildDevice(s.deviceId('thermostat', mac)).attributes.commandStatus == 'rejected'
}
test('Connection driver initialization requests a fresh app-managed connection') {
    def c = load('drivers/resideo-connection.groovy'); c.device.data.locationId = 'loc'
    c.startConnection(1L); c.initialize()
    assert messages(c)[-1].kind == 'streamInitialize' && messages(c)[-1].locationId == 'loc'
    def s = ready(); def child = s.ensureChild('Resideo Connection', s.connectionId('loc'), 'Connection', [kind: 'connection', locationId: 'loc'])
    s.processMessage([kind: 'streamInitialize', locationId: 'loc'])
    assert child.connectionActive()
}
test('Combined cookie headers retain CSRF cookies without splitting Expires dates') {
    def s = app()
    Map cookies = s.collectCookies([initial: 'one'], ['Set-Cookie': 'session=abc; Expires=Wed, 21 Oct 2026 07:28:00 GMT, _csrf=csrf; Path=/'])
    assert cookies == [initial: 'one', session: 'abc', _csrf: 'csrf']
}
test('Optional endpoint 404s keep thermostat readings usable') {
    def s = ready(); s.enqueueRead('rooms', mac, s.thermostatPath(mac, 1) + '/group/0/rooms', [:])
    s.respond(s.requests[0], 404, [:])
    assert s.state.cache[mac].unsupported == ['rooms']
    assert s.thermostatAttributes(s.state.cache[mac]).temperature == 76
    assert s.healthAttributes(mac).dataStatus == 'current'
    assert s.getChildDevice(s.deviceId('sensor', mac, 1)).getDataValue('orphaned') == 'true'
}
test('Stale account and connection events cannot alter current readings') {
    def s = ready(); long generation = s.generation()
    s.processMessage([kind: 'command', accountGeneration: generation - 1L, mac: mac, command: 'setFeelsLikeEnabled', args: ['off']])
    assert !s.requests
    def c = s.ensureChild('Resideo Connection', s.connectionId('loc'), 'Connection', [kind: 'connection', locationId: 'loc']); c.startConnection(generation)
    s.handleStreamEvent([locationId: 'loc', connectionGeneration: c.cg - 1L, event: fixture('live_setpoint')])
    assert s.state.cache[mac].shadow.Reported.Setpoint.CoolSetpoint == 76
}
test('Disabled integration cancels work and secrets never enter debug logs') {
    def s = ready(); s.settings.debugEnable = true; s.refreshDevice(mac, true)
    String logs = s.logs.join('\n')
    assert !logs.contains('access-secret') && !logs.contains(mac)
    s.settings.integrationEnabled = false; s.initialize()
    assert !s.state.httpQueue && !s.state.inFlight
    assert s.getChildDevice(s.deviceId('thermostat', mac)).attributes.dataStatus == 'unavailable'
}
test('Malformed successful shadows do not erase cached state') {
    def s = ready(); s.enqueueRead('shadow', mac, s.thermostatPath(mac), [:]); s.respond(s.requests[0], 200, [:])
    assert s.state.cache[mac].shadow.Reported.DisplayedIndoorTemperature == 76
    assert s.state.cloudError.contains('invalid thermostat state')
}
test('Simulated 24-hour operation crosses token rotations, feed renewals, and hub restart') {
    def s = ready(); Map graph = fixture('accounts')
    String loc = graph.data.consumerUsers[0].consumerAccount.locations[0].id
    int served = 0; int rotations = 0; int renewals = 0
    def service = { ->
        int limit = 0
        while (served < s.requests.size()) {
            assert ++limit < 1000
            Map req = s.requests[served++]
            String url = req.params.uri
            if (url.endsWith('/oauth/token')) { rotations++; s.respond(req, 200, [access_token: 'access-' + rotations, refresh_token: 'refresh-' + rotations, expires_in: 3600]) }
            else if (url.endsWith('/accounts')) s.respond(req, 200, graph)
            else if (url.contains('/Hub/negotiate')) s.respond(req, 200, [url: 'https://x.service.signalr.net/client/?hub=h', accessToken: 'azure'])
            else if (url.contains('/client/negotiate')) {
                s.respond(req, 200, [connectionToken: 'connection'])
                def c = s.getChildDevice(s.connectionId(loc))
                if (c?.connectionActive()) s.activateStream([locationId: loc, connectionGeneration: c.cg])
            }
            else if (url.endsWith('/configuration')) s.respond(req, 200, fixture('configuration'))
            else if (url.endsWith('/rooms')) s.respond(req, 200, fixture('rooms'))
            else if (url.endsWith('/priority')) s.respond(req, 200, [PriorityStatus: 'PermanentHold'])
            else s.respond(req, 200, fixture('device'))
        }
    }
    s.initialize(); service()
    for (int tick = 0; tick < 8640; tick++) {
        if (tick % 48 == 0) {
            def c = s.getChildDevice(s.connectionId(loc)); assert c
            c.stopConnection(); c.startConnection(s.generation()); renewals++
            s.negotiate(loc, c.cg)
        }
        s.advance(10000L); service()
        if (tick == 4320) { s.initialize(); service() }
        assert s.state.tokens?.refresh_token
        assert s.state.inFlight.size() <= 2
        assert s.state.httpQueue.size() < 20
    }
    assert rotations >= 24 && renewals == 180
    assert s.getChildDevices().count { it.getDataValue('kind') == 'thermostat' } == 1
    assert s.getChildDevices().count { it.getDataValue('kind') == 'sensor' } == 1
    assert s.state.streams[loc].ready
}
test('Deployment imports and validation exceptions use documented sandbox classes') {
    Map contract = new JsonSlurper().parse(new File('tests/hubitat-contract.json'))
    ['apps/resideo-integration.groovy', 'drivers/resideo-connection.groovy', 'drivers/resideo-thermostat.groovy', 'drivers/resideo-room-sensor.groovy'].each { path ->
        String source = new File(path).text
        (source =~ /(?m)^import ([\w.]+)$/).each { match -> assert contract.allowedClasses.contains(match[1]) : 'Undocumented sandbox import: ' + match[1] }
        assert !source.contains('new IllegalArgumentException')
    }
}
test('Initialization restores proactive refresh and child labels remain editable') {
    def s = ready(); s.initialize()
    assert s.jobs.any { it.handler == 'refreshTokens' }
    assert s.getChildDevice(s.deviceId('thermostat', mac)).component == false
}
test('A delayed REST response cannot overwrite a newer live thermostat or sensor update') {
    def s = ready(); def c = s.ensureChild('Resideo Connection', s.connectionId('loc'), 'Connection', [kind: 'connection', locationId: 'loc']); c.startConnection(1L)
    s.enqueueRead('shadow', mac, s.thermostatPath(mac), [:]); Map shadowRead = s.requests[-1]
    s.handleStreamEvent([locationId: 'loc', connectionGeneration: c.cg,
        event: [DeviceId: mac, NotificationType: 'LiveFeedEvent', Body: [PropertyName: 'DisplayedIndoorTemperature', Value: [Value: 80]]]])
    s.respond(shadowRead, 200, fixture('device'))
    assert s.state.cache[mac].shadow.Reported.DisplayedIndoorTemperature == 80
    s.enqueueRead('rooms', mac, s.thermostatPath(mac, 1) + '/group/0/rooms', [:]); Map roomsRead = s.requests[-1]
    s.handleStreamEvent([locationId: 'loc', connectionGeneration: c.cg, event: fixture('live_sensor_remote')])
    s.respond(roomsRead, 200, fixture('rooms'))
    assert s.findAccessory(s.state.cache[mac].rooms, 1).AccessoryValue.IndoorTemperature == 70
}
test('Bootstrap readiness requires a read belonging to the current subscription') {
    def s = ready(); def c = s.ensureChild('Resideo Connection', s.connectionId('loc'), 'Connection', [kind: 'connection', locationId: 'loc']); c.startConnection(1L)
    s.enqueueRead('shadow', mac, s.thermostatPath(mac), [:]); Map beforeConnect = s.requests[-1]
    s.activateStream([locationId: 'loc', connectionGeneration: c.cg])
    s.state.streams.loc.activated = true
    s.respond(beforeConnect, 200, fixture('device'))
    assert !s.state.streams.loc.ready
    assert s.requests.any { it != beforeConnect && it.params.uri == beforeConnect.params.uri }
}
test('The final bootstrap response publishes push readiness immediately') {
    def s = ready(); def c = s.ensureChild('Resideo Connection', s.connectionId('loc'), 'Connection', [kind: 'connection', locationId: 'loc']); c.startConnection(1L)
    s.activateStream([locationId: 'loc', connectionGeneration: c.cg])
    int served = 0
    while (served < s.requests.size()) {
        Map r = s.requests[served++]
        s.respond(r, 200, r.params.uri.endsWith('/configuration') ? fixture('configuration') : r.params.uri.endsWith('/rooms') ? fixture('rooms') : r.params.uri.endsWith('/priority') ? [PriorityStatus: 'PermanentHold'] : fixture('device'))
    }
    assert s.state.streams.loc.ready
    assert s.getChildDevice(s.deviceId('thermostat', mac)).attributes.connectionStatus == 'connected'
}
test('Changing a selected location device set renews activation for the new thermostat') {
    def s = ready(); s.synchronizeSelection(); def c = s.getChildDevice(s.connectionId('loc')); long before = c.cg
    String other = '112233445566'
    s.state.discovered[other] = [name: 'Second', locationId: 'loc', locationName: 'Home']
    s.settings.selectedThermostats = [mac, other]
    s.synchronizeSelection()
    assert c.cg > before
}
test('Unknown write results block later complete-body writes until a settling read') {
    def s = ready()
    s.acceptCommand(command('setOccupancySensitivity', ['high'], 1)); Map first = s.requests[0]
    s.acceptCommand(command('setExcludeMotion', ['on'], 1))
    s.respond(first, 503, [message: 'Temporarily unavailable'])
    assert s.state.lastCommand[mac + ':1'].status == 'uncertain'
    s.advance(30000L)
    assert s.requests.count { it.verb == 'PUT' } == 1
    int served = 1
    while (served < s.requests.size()) {
        Map r = s.requests[served++]
        if (r.verb == 'PUT') break
        Map rooms = fixture('rooms'); rooms.Rooms[1].Accessories[0].AccessoryValue.OccupancySensitivity = 'High'
        s.respond(r, 200, r.params.uri.endsWith('/rooms') ? rooms : r.params.uri.endsWith('/configuration') ? fixture('configuration') : fixture('device'))
    }
    Map second = s.requests.findAll { it.verb == 'PUT' }[-1]
    assert second != first
    assert new JsonSlurper().parseText(second.params.body).sensitivity == 'High'
}
test('Commands on other devices cannot postpone an existing settling deadline indefinitely') {
    def s = ready(); String other = '112233445566'
    s.settings.selectedThermostats = [mac, other]
    s.state.discovered[other] = [name: 'Second', locationId: 'loc', locationName: 'Home']
    s.state.cache[other] = s.deepCopy(s.state.cache[mac])
    s.acceptCommand(command('setFeelsLikeEnabled', ['off'])); s.respond(s.requests[0], 202, [TransactionId: 'first'])
    s.advance(9000L)
    s.acceptCommand([mac: other, command: 'setFeelsLikeEnabled', args: ['off']]); s.respond(s.requests[-1], 202, [TransactionId: 'second'])
    s.advance(1000L)
    assert s.requests.any { it.verb == 'GET' && it.params.uri.contains(mac) }
}
test('A fresh owning-endpoint response can retire a normal field that disappeared') {
    def s = ready(); s.acceptCommand(command('setFeelsLikeEnabled', ['off'])); s.respond(s.requests[0], 202, [TransactionId: 'field'])
    s.advance(10000L)
    s.state.cache[mac].shadow.Reported.remove('FeelsLikeEnabled')
    s.reconcilePending(mac, true, 'shadow', s.now())
    assert !s.state.pending[mac].feelsLikeEnabled
    assert s.state.lastCommand[mac].status == 'unconfirmed'
}
test('Malformed readings and optional responses cannot poison state publication') {
    def s = ready()
    Map r = s.state.cache[mac].shadow.Reported
    r.DisplayedIndoorTemperature = 'NaN'; r.DisplayedOutdoorTemperature = [:]; r.DisplayedIndoorHumidity = 'bad'
    s.publishDevice(mac)
    assert !s.thermostatAttributes(s.state.cache[mac]).containsKey('temperature')
    assert !s.thermostatAttributes(s.state.cache[mac]).containsKey('humidity')
    s.enqueueRead('rooms', mac, s.thermostatPath(mac, 1) + '/group/0/rooms', [:])
    s.respond(s.requests[-1], 200, [Rooms: ['invalid']])
    assert s.state.cache[mac].rooms.Rooms.size() == 2
    s.enqueueRead('configuration', mac, s.thermostatPath(mac) + '/configuration', [:])
    s.respond(s.requests[-1], 200, [Reported: 'invalid'])
    assert s.state.cache[mac].configuration.Reported.SystemSwitchCapabilities
}
test('Single-function equipment does not require an unsupported counterpart setpoint') {
    def s = ready()
    s.state.cache[mac].configuration.Reported.SystemSwitchCapabilities = ['Heat', 'Off']
    s.state.cache[mac].shadow.Reported.Setpoint.remove('CoolSetpoint')
    assert s.buildCommand(command('setHeatingSetpoint', [68])).body.SetpointValue == 68
    rejected { s.buildCommand(command('setCoolingSetpoint', [78])) }
    s = ready(); s.state.cache[mac].configuration.Reported.SystemSwitchCapabilities = ['Cool', 'Off']
    s.state.cache[mac].shadow.Reported.Setpoint.remove('HeatSetpoint')
    assert s.buildCommand(command('setCoolingSetpoint', [78])).body.SetpointValue == 78
    rejected { s.buildCommand(command('setHeatingSetpoint', [68])) }
}
test('Manual reconnect closes the old socket and ignores its close notification during negotiation') {
    def c = load('drivers/resideo-connection.groovy'); c.device.data.locationId = 'loc'
    c.startConnection(1L); c.openSocket('wss://test', 1L, c.connectionGeneration()); c.webSocketStatus('status: open'); c.parse('{}\u001e')
    int closed = c.interfaces.webSocket.closed; long generation = c.connectionGeneration()
    c.refresh()
    assert c.connectionGeneration() > generation && c.interfaces.webSocket.closed > closed
    assert !c.jobs.any { it.handler == 'keepalive' }
    c.webSocketStatus('status: closing')
    assert c.state.stage == 'negotiating'
}
test('A slow optional endpoint response cannot replace a newer confirmed configuration') {
    def s = ready(); String path = s.thermostatPath(mac) + '/configuration'
    s.enqueueRead('configuration', mac, path, [:]); Map old = s.requests[-1]
    s.advance(10000L)
    s.enqueueRead('configuration', mac, path, [reconcile: true]); s.pump(); Map fresh = s.requests[-1]
    assert old != fresh
    Map latestConfig = fixture('configuration'); latestConfig.Reported.MaximumHeatSetpointAllowed = 85
    s.respond(fresh, 200, latestConfig)
    s.respond(old, 200, fixture('configuration'))
    assert s.state.cache[mac].configuration.Reported.MaximumHeatSetpointAllowed == 85
}
test('Refresh intent and rotated credentials are durable before any dependent network call') {
    def s = ready(); s.state.tokens.expiresAt = 0L
    s.enqueueRead('shadow', mac, s.thermostatPath(mac), [:])
    Map exchange = s.requests[-1]
    assert exchange.params.uri.endsWith('/oauth/token')
    assert exchange.durableRefreshing == true
    s.respond(exchange, 200, [access_token: 'rotated-access', refresh_token: 'rotated-refresh', expires_in: 3600])
    Map read = s.requests[-1]
    assert read.params.uri != exchange.params.uri
    assert read.durableRefreshToken == 'rotated-refresh' && read.durableRefreshing == false
    assert s.durable.tokens.refresh_token == 'rotated-refresh'
}
test('Status logs are optional and debug requests require opt-in') {
    def s = app()
    s.diagnostic('requestSent', [endpoint: 'shadow', method: 'GET', request: 3])
    assert !s.logs && !s.state.diagnosticEvents
    s.diagnostic('requestFailed', [endpoint: 'shadow', http: 503])
    assert s.logs.any { it.contains('requestFailed') && it.contains('503') }
    s.settings.logEnable = false; s.logs.clear()
    s.diagnostic('reauthRequired', [reason: 'timeout'])
    s.settings.debugEnable = true
    s.diagnostic('requestSent', [endpoint: 'shadow', method: 'GET', request: 4])
    assert !s.logs
    assert s.diagnosticSnapshot().recentEvents*.event.containsAll(['requestFailed', 'reauthRequired', 'requestSent'])
}
test('Request traces correlate responses, elapsed time, retry and cooldown') {
    def s = ready(); s.settings.debugEnable = true
    s.enqueueRead('shadow', mac, s.thermostatPath(mac), [:]); Map first = s.requests[-1]
    Map sent = s.state.diagnosticEvents.find { it.event == 'requestSent' }
    s.advance(1250L); s.respond(first, 429, [message: 'private server message'], ['Retry-After': '75'])
    Map done = s.state.diagnosticEvents.find { it.event == 'requestCompleted' }
    Map limited = s.state.diagnosticEvents.find { it.event == 'rateLimited' }
    assert done.request == sent.request && done.elapsedMs == 1250 && done.http == 429
    assert limited.retrySeconds == 75 && s.diagnosticSnapshot().cooldownSeconds == 75
    assert !s.logs.join().contains('private server message')
}
test('Diagnostic vocabulary rejects secrets, unknown fields and untrusted server strings') {
    def s = ready(); s.settings.debugEnable = true
    List secrets = [mac, 'access-secret', 'refresh-secret', 'cookie-secret', 'code-secret', 'password-secret', 'user@example.com', 'Private Home', 'private-transaction']
    s.settings.loginEmail = 'user@example.com'; s.settings.loginPassword = 'password-secret'
    s.diagnostic('unrecognized access-secret', [http: 500])
    s.diagnostic('streamStage', [location: 'Private Home', reason: 'wss://cloud/?access_token=access-secret', stage: 'cookie-secret', token: 'refresh-secret'])
    s.diagnostic('commandAccepted', [requested: [heatingSetpoint: 'access-secret', thermostatMode: 'refresh-secret', unknown: 'cookie-secret', heatSetpointMin: 123456789]])
    s.authHttp('authorize', 'GET', 'https://login.resideo.com/?code=code-secret', null, [Cookie: 'cookie-secret'])
    s.respond(s.requests[-1], 400, [error_description: secrets.join(' ')])
    s.rejectCommand(command('access-secret', ['password-secret']), secrets.join(' '))
    s.enqueueRead('shadow', mac, s.thermostatPath(mac), [:]); s.respond(s.requests[-1], 400, [message: secrets.join(' ')])
    String output = s.logs.join('\n') + JsonOutput.toJson(s.diagnosticSnapshot())
    secrets.each { assert !output.contains(it) : 'Secret reached diagnostic output: ' + it }
    assert s.state.diagnosticEvents.any { it.event == 'authFailure' }
    assert s.state.diagnosticEvents.any { it.event == 'commandRejected' }
}
test('Debug capture expires without being extended by initialization') {
    def s = app(); s.settings.debugEnable = true; s.initialize()
    long expires = s.state.debugUntil
    s.advance(600000L); s.initialize()
    assert s.state.debugUntil == expires
    assert s.jobs.find { it.handler == 'logsOff' }.at == expires
    s.advance(1200000L)
    assert s.settings.debugEnable == false && !s.debugLogging() && !s.state.debugUntil
    int logged = s.logs.size(); s.diagnostic('requestSent', [endpoint: 'accounts'])
    assert s.logs.size() == logged
}
test('Debug history and outage logs are bounded while counters retain suppressed activity') {
    def s = app(); s.settings.debugEnable = true
    250.times { s.diagnostic('requestSent', [endpoint: 'shadow', request: it]) }
    assert s.state.diagnosticEvents.size() == 60
    assert s.logs.size() == 121 && s.logs[-1].contains('limit reached')
    assert s.state.diagnosticCounters.requestSent == 250
    s.logs.clear()
    10.times { s.diagnostic('streamFailure', [location: s.diagnosticAlias('location', 'loc'), reason: 'stalled', generation: it, retrySeconds: it + 1]) }
    assert s.logs.size() == 1 && s.state.diagnosticCounters.streamFailure == 10
    s.advance(60000L); s.diagnostic('requestSent', [endpoint: 'shadow']); s.diagnostic('streamFailure', [reason: 'stalled'])
    assert s.logs.size() == 3
}
test('Command diagnostics distinguish accepted, confirmed, failed and unconfirmed state') {
    def s = ready(); s.settings.debugEnable = true
    s.acceptCommand(command('setFeelsLikeEnabled', ['off'])); s.respond(s.requests[-1], 202, [TransactionId: 'private-transaction'])
    assert s.state.diagnosticEvents*.event.contains('commandAccepted')
    s.state.cache[mac].shadow.Reported.FeelsLikeEnabled = false; s.reconcilePending(mac, false)
    assert s.state.diagnosticEvents*.event.contains('commandConfirmed')
    s.acceptCommand(command('setFeelsLikeEnabled', ['on'])); s.respond(s.requests[-1], 202, [TransactionId: 'private-transaction'])
    s.failTransaction(mac, 'private-transaction')
    assert s.state.diagnosticEvents*.event.contains('commandFailed')
    s.acceptCommand(command('setFeelsLikeEnabled', ['on'])); s.respond(s.requests[-1], 202, [TransactionId: 'another-transaction'])
    s.advance(10000L); s.reconcilePending(mac, true, 'shadow', s.now())
    assert s.state.diagnosticEvents*.event.contains('commandUnconfirmed')
    assert !JsonOutput.toJson(s.diagnosticSnapshot()).contains('private-transaction')
}
test('Connection diagnostics retain safe stage, failure reason and backoff') {
    def c = load('drivers/resideo-connection.groovy'); c.settings.debugEnable = true; c.device.data.locationId = 'loc'
    c.startConnection(1L); c.openSocket('wss://cloud/?access_token=private-token', 1L, c.connectionGeneration()); c.webSocketStatus('status: open')
    c.parse('{"error":"private-token"}\u001e')
    Map failure = messages(c).find { it.kind == 'streamStatus' && it.status == 'disconnected' }
    assert failure.reason == 'handshakeRejected' && failure.stage == 'handshake' && failure.retrySeconds == 1
    assert !c.logs.join().contains('private-token')
    c.advance(1000L); c.connectionFailed('wss://cloud/?access_token=other-secret')
    assert messages(c)[-1].reason == 'transport' && !c.logs.join().contains('other-secret')
    def s = ready(); s.settings.debugEnable = true
    def child = s.ensureChild('Resideo Connection', s.connectionId('loc'), 'Connection', [kind: 'connection', locationId: 'loc']); child.startConnection(1L)
    s.streamStatus([locationId: 'loc', connectionGeneration: child.cg, status: 'disconnected', reason: 'stalled', stage: 'connected', retrySeconds: 8])
    Map exported = s.state.diagnosticEvents.find { it.event == 'streamFailure' }
    assert exported.reason == 'stalled' && exported.retrySeconds == 8 && exported.location != 'loc'
}
test('Each driver supports bounded opt-in logging and preserves automatic shutoff across updates') {
    ['thermostat', 'room-sensor', 'connection'].each { name ->
        def d = load('drivers/resideo-' + name + '.groovy'); d.settings.debugEnable = true; d.installed()
        long expires = d.state.debugUntil
        d.advance(600000L); d.updated()
        assert d.state.debugUntil == expires && d.jobs.find { it.handler == 'logsOff' }.at == expires
        d.logs.clear(); d.state.debugWindow = null; 250.times { d.driverDebug('Resideo test activity') }
        assert d.logs.size() == 121
        d.advance(1200000L)
        assert d.settings.debugEnable == false && !d.debugLogging()
        int count = d.logs.size(); d.driverDebug('Resideo test activity')
        assert d.logs.size() == count
    }
}
test('Driver forwarding and local rejections can be diagnosed without logging arguments') {
    def t = load('drivers/resideo-thermostat.groovy'); t.settings.debugEnable = true; t.device.data.mac = mac
    t.setHeatingSetpoint('private-command-value'); t.applyAttributes([temperature: 70]); t.refresh(); t.setSchedule('private-schedule')
    assert t.logs.any { it.contains('command forwarded') } && t.logs.any { it.contains('attributes updated') }
    assert messages(t)[-1].kind == 'driverRejected' && messages(t)[-1].command == 'setSchedule'
    assert !t.logs.join().contains('private-command-value') && !t.logs.join().contains('private-schedule') && !t.logs.join().contains(mac)
    def d = load('drivers/resideo-room-sensor.groovy'); d.settings.debugEnable = true; d.device.data.mac = mac
    d.setOccupancySensitivity('private-command-value'); d.applyAttributes([occupancy: 'occupied']); d.refresh()
    assert d.logs.size() == 3 && !d.logs.join().contains('private-command-value')
}
test('Debug command comparison records only valid setting values in hub units') {
    def s = ready(); s.settings.debugEnable = true; s.location.temperatureScale = 'C'
    s.acceptCommand(command('setHeatingSetpoint', [20])); s.respond(s.requests[-1], 202, [TransactionId: 'private-transaction'])
    Map accepted = s.state.diagnosticEvents.find { it.event == 'commandAccepted' }
    assert accepted.requested.heatingSetpoint == 20 && accepted.scale == 'C'
    s.advance(10000L); s.reconcilePending(mac, true, 'shadow', s.now())
    Map checked = s.state.diagnosticEvents.find { it.event == 'commandUnconfirmed' }
    assert checked.requested.heatingSetpoint == 20 && checked.reported.heatingSetpoint != 20
    s.settings.debugEnable = false; s.advance(60000L)
    s.diagnostic('commandConfirmed', [requested: [heatingSetpoint: 22], reported: [heatingSetpoint: 22]])
    assert !s.state.diagnosticEvents[-1].containsKey('requested')
}
test('Command rejection logs classify useful validation and cloud failure reasons') {
    def s = ready()
    s.acceptCommand(command('setThermostatMode', ['invalid']))
    assert s.state.diagnosticEvents[-1].reason == 'unsupportedControl'
    s.acceptCommand(command('setHeatingSetpoint', [90]))
    assert s.state.diagnosticEvents[-1].reason in ['outsideLimits', 'deadband']
    s.rejectCommand(command('setHold', ['temporary']), 'An enabled schedule is required for this hold.')
    assert s.state.diagnosticEvents[-1].reason == 'scheduleDisabled'
    s.rejectCommand(command('setHold', ['temporary']), 'Resideo rate limit reached. Retry after the cooldown.')
    assert s.state.diagnosticEvents[-1].reason == 'rateLimited'
}
test('First debug activity starts automatic expiry in the app and drivers') {
    def s = app(); s.settings.debugEnable = true
    s.diagnostic('requestSent', [endpoint: 'accounts'])
    assert s.state.debugUntil == s.now() + 1800000L && s.jobs.any { it.handler == 'logsOff' }
    ['thermostat', 'room-sensor', 'connection'].each { name ->
        def d = load('drivers/resideo-' + name + '.groovy'); d.settings.debugEnable = true
        d.driverDebug('Resideo test activity')
        assert d.state.debugUntil == d.now() + 1800000L && d.jobs.any { it.handler == 'logsOff' }
    }
}
test('HPM installs exactly the app and three required drivers with matching definitions and versions') {
    Map manifest = new JsonSlurper().parse(new File('packageManifest.json'))
    Map repository = new JsonSlurper().parse(new File('repository.json'))
    assert manifest.version == app().constants().version
    assert manifest.apps.size() == 1 && manifest.drivers.size() == 3
    assert manifest.apps[0].primary == true && manifest.apps[0].oauth == false
    List components = manifest.apps + manifest.drivers
    assert components*.id.unique().size() == 4
    String prefix = 'https://raw.githubusercontent.com/evdev/hubitat-resideo/v' + manifest.version + '/'
    Set paths = [] as Set
    components.each { item ->
        assert item.required == true && item.location.startsWith(prefix)
        java.util.UUID.fromString(item.id)
        String path = item.location.substring(prefix.length()); paths << path
        def script = load(path)
        Map definition = script.metadataCalls.find { it[0] == 'definition' }[1]
        assert definition.name == item.name && definition.namespace == item.namespace
    }
    assert paths == ['apps/resideo-integration.groovy', 'drivers/resideo-connection.groovy', 'drivers/resideo-thermostat.groovy', 'drivers/resideo-room-sensor.groovy'] as Set
    assert manifest.licenseFile == prefix + 'LICENSE'
    assert repository.packages.size() == 1
    assert repository.packages[0].location == 'https://raw.githubusercontent.com/evdev/hubitat-resideo/main/packageManifest.json'
}
test('Cookie processing handles Hubitat header wrappers and folded duplicate cookies') {
    def s = app()
    def headers = loader.loadClass('FakeHubitatHeaders').newInstance()
    headers.putAll(['sEt-CoOkIe': 'session=one; Expires=Wed, 21 Oct 2026 07:28:00 GMT; Path=/, _csrf=csrf-secret; Path=/; Secure, auth0=a=b; Path=/', Location: '/login?state=private-state'])
    assert headers.findAll { k, v -> true } instanceof List
    Map cookies = s.collectCookies([retained: 'old'], headers)
    assert cookies == [retained: 'old', session: 'one', _csrf: 'csrf-secret', auth0: 'a=b']
    def resp = loader.loadClass('FakeResponse').newInstance(headers: headers, body: '')
    Map normalized = s.responseHeaders(resp)
    assert !(normalized.findAll { k, v -> true } instanceof List)
    assert s.headerValue(normalized, 'location') == '/login?state=private-state'
    assert s.collectCookies([:], ['Set-Cookie': ['one=1; Path=/', '_csrf=2; Path=/'] as String[]])._csrf == '2'
    assert s.collectCookies([:], ['Set-Cookie': null]) == [:]
}
test('Failed asynchronous HTTP bodies are read through the Hubitat error accessor') {
    def s = app(); s.settings.loginEmail = 'test@example.com'; s.settings.loginPassword = 'password-secret'; s.startPasswordLogin()
    s.respond(s.requests[-1], 302, '', [Location: '/login?state=state'])
    s.respond(s.requests[-1], 200, '', ['Set-Cookie': '_csrf=csrf-secret; Path=/'])
    Map request = s.requests[-1]
    def resp = loader.loadClass('FakeResponse').newInstance(status: 400, body: [code: 'invalid_captcha'], errorBodyOnly: true)
    s.authResponse(resp, request.data)
    assert s.state.lastError.contains('CAPTCHA') && !s.settings.loginPassword && !s.state.authRequest
    assert !s.logs.join().contains('password-secret') && !s.logs.join().contains('csrf-secret')
}
test('Cookie parsing errors terminate sign-in cleanly instead of leaving a stuck request') {
    def s = app(); s.settings.loginEmail = 'test@example.com'; s.settings.loginPassword = 'password-secret'; s.startPasswordLogin()
    // A deliberately unparseable cookie simulates unexpected transport metadata.
    Map request = s.requests[-1]
    s.respond(request, 302, '', ['Set-Cookie': loader.loadClass('FakeBadCookie').newInstance(), Location: '/login?state=state'])
    assert !s.state.authRequest && !s.state.loginAttempt && !s.settings.loginPassword
    assert s.state.authStatus == 'Sign-in failed'
}
test('Connection self-check verifies redirect and CSRF without submitting credentials') {
    def s = app(); s.settings.debugEnable = true; s.testSignInConnection()
    def headers = loader.loadClass('FakeHubitatHeaders').newInstance()
    headers.putAll([Location: '/login?state=private-state', 'Set-Cookie': ['session=private-cookie; Path=/']])
    s.respond(s.requests[-1], 302, '', headers)
    s.respond(s.requests[-1], 200, '<html/>', ['Set-Cookie': '_csrf=private-csrf; Path=/'])
    assert s.state.authStatus == 'Connection check passed. Ready to sign in.'
    assert s.requests.size() == 2 && s.requests.every { it.verb == 'GET' }
    assert !s.state.authRequest && !s.state.loginAttempt
    assert s.state.diagnosticEvents.any { it.event == 'authConnectionReady' && it.csrf == 'present' }
    assert !s.logs.join().contains('private-cookie') && !s.logs.join().contains('private-csrf')
}
test('Browser authorization and exchange use the same registered HTTPS callback') {
    def s = app(); s.startBrowserLogin(); Map attempt = s.state.browserAttempt
    assert s.parseQuery(attempt.url).redirect_uri == s.constants().browserRedirect
    s.settings.browserRedirect = attempt.redirectUri + '?code=private-code&state=' + attempt.nonce
    s.finishBrowserLogin()
    Map body = new JsonSlurper().parseText(s.requests[-1].params.body)
    assert body.redirect_uri == attempt.redirectUri && body.code_verifier == attempt.verifier
    assert !s.settings.browserRedirect
    s.respond(s.requests[-1], 200, [access_token: 'private-access', refresh_token: 'private-refresh', expires_in: 3600])
    assert s.state.authStatus == 'Connected' && s.requests[-1].params.uri.endsWith('/accounts')
}
test('Browser paste mistakes retain the current attempt and accept a corrected address') {
    def s = app(); s.startBrowserLogin(); String nonce = s.state.browserAttempt.nonce
    ['https://login.resideo.com/login?state=wrong', s.constants().browserRedirect + '?code=old&state=old', s.constants().browserRedirect + '?code=one&code=two&state=' + nonce, 'https://other.example/?code=code&state=' + nonce].each { value ->
        s.settings.browserRedirect = value; s.finishBrowserLogin()
        assert s.state.browserAttempt.nonce == nonce && !s.requests && !s.settings.browserRedirect
    }
    s.settings.browserRedirect = s.constants().browserRedirect + '?code=correct&state=' + nonce; s.finishBrowserLogin()
    assert s.requests.size() == 1 && !s.state.lastError
}
test('Saving the app preserves browser PKCE and in-flight password login callbacks') {
    def s = app(); s.startBrowserLogin(); Map attempt = s.deepCopy(s.state.browserAttempt); long gen = s.generation()
    s.advance(10000L); s.updated()
    assert s.state.browserAttempt == attempt && s.generation() == gen
    assert s.jobs.find { it.handler == 'expireLogin' }.at == (attempt.created as long) + 3600000L
    s = app(); s.settings.loginEmail = 'test@example.com'; s.settings.loginPassword = 'private-password'; s.startPasswordLogin()
    Map request = s.requests[-1]; gen = s.generation(); s.updated()
    assert s.state.authRequest.id == request.data.id && s.generation() == gen && s.settings.loginPassword == 'private-password'
    s.respond(request, 302, '', [Location: '/login?state=state'])
    assert s.requests.size() == 2 && s.requests[-1].verb == 'GET'
}
test('Expired and disabled sign-ins clear temporary data and report a truthful status') {
    def s = app(); s.startBrowserLogin(); s.advance(3600000L)
    assert !s.state.browserAttempt && s.state.authStatus == 'Sign-in expired'
    s.startBrowserLogin(); s.settings.integrationEnabled = false; s.updated()
    assert !s.state.browserAttempt && !s.state.authRequest
}
test('Guided browser UI avoids technical tools and refreshes only during HTTP sign-in') {
    def s = app(); s.startBrowserLogin(); Map page = s.mainPage()
    List paragraphs = s.metadataCalls.findAll { it[0] == 'paragraph' }.collect { it[1].join() }
    assert paragraphs.any { it.contains('address bar') } && !paragraphs.any { it.contains('Developer Tools') || it.contains('Location header') }
    assert !page.refreshInterval
    s.settings.browserRedirect = s.constants().browserRedirect + '?code=code&state=' + s.state.browserAttempt.nonce; s.finishBrowserLogin()
    assert s.mainPage().refreshInterval == 3
}
test('Login-page redirects collect cookies and preserve the final Auth0 server state') {
    def s = app(); s.settings.debugEnable = true; s.testSignInConnection()
    s.respond(s.requests[-1], 302, '', [Location: '/login?state=first', 'Set-Cookie': 'auth0=private-session; Path=/'])
    s.respond(s.requests[-1], 302, '', [Location: '/login?state=second', 'Set-Cookie': 'did=private-device; Path=/'])
    assert s.state.loginAttempt.authState == 'second'
    assert s.requests[-1].params.headers.Cookie.contains('auth0=private-session')
    s.respond(s.requests[-1], 200, '', ['Set-Cookie': '_csrf=private-csrf; Path=/'])
    assert s.state.authStatus == 'Connection check passed. Ready to sign in.'
    assert s.state.diagnosticEvents.any { it.event == 'authRedirect' && it.route == 'login' }
    assert !s.logs.join().contains('private-session')
}
test('Login-page redirect loops and foreign hosts fail safely without forwarding cookies') {
    def s = app(); s.testSignInConnection(); s.respond(s.requests[-1], 302, '', [Location: '/login?state=first'])
    s.state.loginAttempt.nativeCookies = true
    6.times { s.respond(s.requests[-1], 302, '', [Location: '/login?state=again']); s.advance(1L) }
    assert !s.state.loginAttempt && s.state.lastError.contains('too many times')
    s = app(); s.testSignInConnection(); s.respond(s.requests[-1], 302, '', [Location: '/login?state=first'])
    s.respond(s.requests[-1], 302, '', [Location: 'https://other.example/login?state=private'])
    assert s.requests.size() == 2 && !s.state.loginAttempt
    rejected { s.trustedAuthRedirect('https://login.resideo.com@other.example/login') }
    rejected { s.trustedAuthRedirect('http://login.resideo.com/login') }
}
test('Missing duplicate cookies trigger one scheduled native bootstrap with complete headers') {
    def s = app(); s.settings.debugEnable = true; s.testSignInConnection()
    s.respond(s.requests[-1], 302, '', [Location: '/login?state=lost', 'Set-Cookie': 'auth0_compat=private-session; Path=/'])
    s.respond(s.requests[-1], 200, '', ['Set-Cookie': 'did_compat=private-device; Path=/'])
    assert s.state.loginAttempt.nativeCookies && s.jobs.any { it.handler == 'performCookieAuth' }
    assert s.jobs.findAll { it.handler in ['performCookieAuth', 'authTimeout'] }.every { !it.data.containsKey('wire') }
    s.advance(1L); Map root = s.requests[-1]
    assert root.nativeCallback && root.params.timeout == 10 && root.params.followRedirects == false
    s.respond(root, 302, '', [Location: '/login?state=complete', 'Set-Cookie': ['auth0=private-session; Path=/', 'did=private-device; Path=/']])
    s.advance(1L); Map page = s.requests[-1]
    assert page.params.headers.Cookie.contains('auth0=private-session') && page.params.headers.Cookie.contains('did=private-device')
    s.respond(page, 200, '', ['Set-Cookie': ['_csrf=private-csrf; Path=/', 'did_compat=private-device; Path=/']])
    assert s.state.authStatus == 'Connection check passed. Ready to sign in.'
    assert !s.state.authRequest && !s.state.loginAttempt
    assert !s.logs.join().contains('private-session') && !s.logs.join().contains('private-csrf')
}
test('Native cookie fallback preserves callback rotation while credential and token POSTs stay asynchronous') {
    def s = app(); s.settings.loginEmail = 'test@example.com'; s.settings.loginPassword = 'private-password'; s.startPasswordLogin()
    String nonce = s.state.loginAttempt.nonce
    s.respond(s.requests[-1], 302, '', [Location: '/login?state=lost'])
    s.respond(s.requests[-1], 200, '', [:]); s.advance(1L)
    s.respond(s.requests[-1], 302, '', [Location: '/login?state=complete', 'Set-Cookie': ['auth0=initial; Path=/', 'did=device; Path=/']]); s.advance(1L)
    s.respond(s.requests[-1], 200, '', ['Set-Cookie': ['_csrf=csrf; Path=/', 'did_compat=device; Path=/']])
    Map credentials = s.requests[-1]
    assert !credentials.nativeCallback && new JsonSlurper().parseText(credentials.params.body).password == 'private-password'
    s.respond(credentials, 200, '<input name="wresult" value="private-form-token"><input name="wctx" value="context">'); s.advance(1L)
    Map callback = s.requests[-1]; assert callback.nativeCallback && callback.verb == 'POST'
    s.respond(callback, 302, '', [Location: '/authorize/resume', 'Set-Cookie': ['auth0=rotated; Path=/', 'auth0_compat=rotated; Path=/']]); s.advance(1L)
    Map resume = s.requests[-1]; assert resume.nativeCallback && resume.params.headers.Cookie.contains('auth0=rotated')
    s.respond(resume, 302, '', [Location: s.constants().redirect + '?code=private-code&state=' + nonce])
    Map exchange = s.requests[-1]; assert !exchange.nativeCallback && !exchange.params.headers.Cookie
    s.respond(exchange, 200, [access_token: 'private-access', refresh_token: 'private-refresh', expires_in: 3600])
    assert s.state.authStatus == 'Connected' && !s.settings.loginPassword && !s.state.loginAttempt && !s.state.authRequest
}
test('Native fallback does not loop or retain private wire data after cancellation') {
    def s = app(); s.testSignInConnection(); s.respond(s.requests[-1], 302, '', [Location: '/login?state=first']); s.respond(s.requests[-1], 200, '', [:])
    s.appButtonHandler('cancelLogin'); s.advance(1L)
    assert s.requests.size() == 2 && !s.state.authRequest && !s.state.loginAttempt
    s = app(); s.testSignInConnection(); s.respond(s.requests[-1], 302, '', [Location: '/login?state=first']); s.respond(s.requests[-1], 200, '', [:]); s.advance(1L)
    s.respond(s.requests[-1], 302, '', [Location: '/login?state=second']); s.advance(1L)
    s.respond(s.requests[-1], 200, '', [:])
    assert !s.state.authRequest && !s.state.loginAttempt && s.state.authStatus == 'Sign-in failed'
    assert s.state.diagnosticCounters.authCookieFallback == 1
}
test('Empty primary response bodies do not hide useful authentication errors') {
    def s = app()
    def resp = loader.loadClass('FakeResponse').newInstance(status: 400, body: '', errorDataBody: [code: 'invalid_captcha'])
    assert s.parseJsonMap(s.responseText(resp)).code == 'invalid_captcha'
}
test('Expired browser results are distinguished from account credentials and revoked sessions') {
    def s = app()
    assert s.authenticationError([error: 'invalid_grant'], 'exchange').contains('sign-in result expired')
    assert !s.authenticationError([error: 'invalid_grant'], 'exchange').contains('Credentials')
    assert s.authenticationError([error: 'invalid_grant'], 'refresh').contains('session ended')
    assert s.authenticationError([code: 'invalid_user_password'], 'credentials').contains('Credentials')
}
test('Login buttons read settings after Hubitat persists the submitted form') {
    def s = app(); s.appButtonHandler('signIn')
    assert !s.requests && s.signInInProgress() && s.mainPage().refreshInterval == 3
    s.settings.loginEmail = 'test@example.invalid'; s.settings.loginPassword = 'private-password'
    s.updated(); s.advance(1000L)
    assert s.requests.size() == 1 && s.state.loginAttempt
    s = app(); s.startBrowserLogin(); String nonce = s.state.browserAttempt.nonce
    s.appButtonHandler('finishBrowser'); assert !s.requests
    s.settings.browserRedirect = s.constants().browserRedirect + '?code=private-code&state=' + nonce
    s.advance(1000L)
    assert s.requests.size() == 1 && s.requests[-1].data.step == 'exchange' && !s.settings.browserRedirect
}
test('Queued login actions coalesce clicks and cannot survive cancellation or disablement') {
    def s = app(); s.appButtonHandler('signIn'); String id = s.state.authUiAction.id
    s.appButtonHandler('signIn'); assert s.state.authUiAction.id == id
    s.settings.loginEmail = 'test@example.invalid'; s.settings.loginPassword = 'private-password'
    s.appButtonHandler('cancelLogin'); s.advance(1000L)
    assert !s.requests && !s.state.authUiAction && !s.settings.loginPassword && !s.state.lastError
    s.appButtonHandler('signIn'); s.settings.integrationEnabled = false; s.updated(); s.advance(1000L)
    assert !s.requests && !s.state.authUiAction
}
test('Authentication fields save on blur and status appears next to each action') {
    def s = app(); s.startBrowserLogin(); s.mainPage()
    ['loginEmail', 'loginPassword', 'browserRedirect'].each { name ->
        assert s.metadataCalls.find { it[0] == 'input' && it[1].contains(name) }[1].find { it instanceof Map }.submitOnChange == true
    }
    assert s.metadataCalls.count { it[0] == 'paragraph' && it[1].join().contains('role="status"') } == 4
    s.state.authStatus = 'Connected'; assert s.authenticationBanner().contains('#146c2e') && s.authenticationBanner().contains('Account connected')
    s.state.lastError = '<private>'; assert s.authenticationBanner().contains('#b71c1c') && s.authenticationBanner().contains('&lt;private&gt;')
    s.state.lastError = null; s.state.authStatus = 'Connection check passed. Ready to sign in.'
    assert !s.authenticationBanner().contains('Account connected')
}
test('Apache header values and header-prefixed strings normalize before auth parsing') {
    def s = app()
    Map headers = [Location: [name: 'Location', value: '/login?state=private'], 'Set-Cookie': [[name: 'Set-Cookie', value: 'auth0_compat=session; Path=/'], 'Set-Cookie: _csrf=csrf; Path=/']]
    assert s.headerValue(headers, 'Location') == '/login?state=private'
    assert s.collectCookies([:], headers) == [auth0_compat: 'session', _csrf: 'csrf']
    assert s.headerValue([Location: 'Location: https://login.resideo.com/login?state=private'], 'Location').startsWith('https://')
}
test('Browser setup lasts one hour without extending password or cloud-code lifetimes') {
    def s = app(); s.startBrowserLogin(); String nonce = s.state.browserAttempt.nonce
    Map query = s.parseQuery(s.state.browserAttempt.url)
    assert query.prompt == 'login' && query.max_age == '0'
    s.advance(1200000L); s.updated()
    assert s.state.browserAttempt.nonce == nonce && s.signInInProgress()
    assert s.browserLoginDeadline().contains('40 minutes remaining')
    assert s.loginLifetime(s.newAuthorization()) == 900000L
    s.advance(2400000L); assert !s.state.browserAttempt
    s.appButtonHandler('signIn'); s.advance(1000L)
    assert s.state.authStatus == 'Enter your sign-in details' && s.state.lastError
}
test('Device selection and explicit orphan removal read the saved form and reject stale callbacks') {
    def s = ready(); s.settings.selectedThermostats = []
    s.appButtonHandler('applyDevices'); assert !s.requests
    s.settings.selectedThermostats = [mac]; s.advance(1000L)
    assert s.requests && s.children.values().find { it.getDataValue('kind') == 'connection' }
    s = ready()
    s.ensureChild('Resideo Thermostat', 'old-orphan', 'Old', [kind: 'thermostat', mac: 'old', orphaned: 'true'])
    s.ensureChild('Resideo Thermostat', 'new-orphan', 'New', [kind: 'thermostat', mac: 'new', orphaned: 'true'])
    s.settings.removeDevices = ['old-orphan']; s.appButtonHandler('removeOrphans')
    s.settings.removeDevices = ['new-orphan']; s.advance(1000L)
    assert s.getChildDevice('old-orphan') && !s.getChildDevice('new-orphan') && !s.settings.removeDevices
    s.settings.removeDevices = ['old-orphan']; s.appButtonHandler('removeOrphans'); s.state.generation++
    s.advance(1000L); assert s.getChildDevice('old-orphan')
}
test('Driver callbacks use native events even while the parent gate is held') {
    ['drivers/resideo-thermostat.groovy', 'drivers/resideo-room-sensor.groovy', 'drivers/resideo-connection.groovy'].each { path ->
        def d = load(path); d.parent.gateHeld = true
        d.device.data = [appId: '42', accountGeneration: '1', mac: mac, locationId: 'loc', accessoryId: '1']
        if (path.contains('connection')) d.startConnection(1L)
        else d.refresh()
        assert !d.parent.messages && d.locationEvents && messages(d)
        assert d.locationEvents.every { it.name == 'resideoBridge-42' && it.value == d.device.id && !it.data }
        List outbox = d.drainMessages(); assert outbox && !messages(d)
    }
}
test('The app drains only owned child outboxes and rejects stale generations') {
    def s = ready(); def child = s.getChildDevice(s.deviceId('thermostat', mac))
    child.messages = [[kind: 'refresh', mac: mac, accountGeneration: 1L], [kind: 'command', mac: mac, command: 'setHeatingSetpoint', args: [70], accountGeneration: 0L]]
    s.childMessage([value: 'unowned-device']); assert child.messages && !s.requests
    s.childMessage([value: child.id]); assert !child.messages && s.requests
    assert s.requests.every { it.verb == 'GET' } && s.state.diagnosticCounters.staleCallback == 1
    int count = s.requests.size(); s.childMessage([value: child.id]); assert s.requests.size() == count
}
test('Private driver outboxes are bounded and overflow requests resynchronization') {
    def d = load('drivers/resideo-thermostat.groovy'); d.device.data = [appId: '42', accountGeneration: '1', mac: mac]
    70.times { d.queueParentMessage([kind: 'refresh', mac: mac, privatePayload: 'private-test-value']) }
    assert messages(d).size() == 64 && d.state.bridgeOverflow
    assert !JsonOutput.toJson(d.locationEvents + d.device.events).contains('private-test-value')
    List pending = d.drainMessages(); assert pending.size() == 65 && pending[-1].kind == 'bridgeOverflow'
    assert !messages(d) && !d.state.bridgeOverflow && d.state.bridgeBytes == 0
    def s = ready(); s.processMessage(pending[-1]); assert s.requests
}
test('Negotiation takes priority over account bootstrap and unsupported rooms skip ordinary polls') {
    def s = ready(); def c = s.ensureChild('Resideo Connection', s.connectionId('loc'), 'Connection', [kind: 'connection', locationId: 'loc'])
    c.startConnection(s.generation())
    s.enqueueRead('shadow', mac, s.thermostatPath(mac), [:])
    s.enqueueRead('rooms', mac, s.thermostatPath(mac, 1) + '/group/0/rooms', [:])
    s.enqueueRead('configuration', mac, s.thermostatPath(mac) + '/configuration', [:])
    s.negotiate('loc', c.connectionGeneration())
    s.respond(s.requests[0], 200, fixture('device'))
    assert s.requests[-1].verb == 'POST' && s.requests[-1].params.uri.contains('/Hub/negotiate?')
    s = ready(); s.state.cache[mac].unsupported = ['rooms']; s.refreshDevice(mac, false)
    assert !s.requests.any { it.params.uri.endsWith('/group/0/rooms') }
    s.refreshDevice(mac, true); assert s.requests.any { it.params.uri.endsWith('/group/0/rooms') } || s.state.httpQueue.any { it.kind == 'rooms' }
}
test('Recovered cloud errors do not turn a connected account red or erase auth failures') {
    def s = ready(); s.state.authStatus = 'Connected'; s.state.lastError = 'Cloud request timed out or could not connect.'
    s.mainPage(); assert !s.state.lastError && s.state.cloudError && s.authenticationBanner().contains('#146c2e')
    assert s.metadataCalls.any { it[0] == 'paragraph' && it[1].join().contains('Cloud communication needs attention') }
    s.setCloudError('Temporary cloud failure', [kind: 'shadow', mac: mac])
    s.clearCloudError([kind: 'rooms', mac: mac]); assert s.state.cloudError
    s.enqueueRead('shadow', mac, s.thermostatPath(mac), [:]); s.respond(s.requests[-1], 200, fixture('device'))
    assert !s.state.cloudError
    s.state.lastError = 'Credentials rejected'; s.state.authStatus = 'Sign-in failed'; s.mainPage()
    assert s.state.lastError && s.authenticationBanner().contains('#b71c1c')
    assert !s.safeServiceMessage([message: 'azure-secret abcdefghijkl.abcdefghijkl.abcdefghijkl'], ['azure-secret']).contains('azure-secret')
    assert !s.safeServiceMessage([message: 'abcdefghijkl.abcdefghijkl.abcdefghijkl']).contains('abcdefghijkl')
}
test('SignalR explicitly false subscription completion is rejected') {
    def c = load('drivers/resideo-connection.groovy'); c.device.data = [locationId: 'loc']; c.startConnection(1L)
    c.openSocket('wss://example.service.signalr.net/client', 1L, c.connectionGeneration())
    c.webSocketStatus('status: open'); c.parse('{}\u001e')
    c.parse(JsonOutput.toJson([type: 3, invocationId: '1', result: false]) + '\u001e')
    assert c.state.stage == 'backoff' && !messages(c).any { it.kind == 'streamActivated' }
}
test('Twenty-one thermostat bootstrap shares priority reads with feed activation') {
    def s = ready(); List devices = (1..21).collect { 'DEVICE' + it }
    s.settings.selectedThermostats = devices
    s.state.discovered = devices.collectEntries { [(it): [name: 'Thermostat', locationId: 'loc', locationName: 'Home']] }
    s.state.cache = [:]
    def c = s.ensureChild('Resideo Connection', s.connectionId('loc'), 'Connection', [kind: 'connection', locationId: 'loc'])
    c.startConnection(s.generation())
    devices.each { s.refreshDevice(it, true) }
    s.activateStream([locationId: 'loc', connectionGeneration: c.connectionGeneration()])
    assert !(s.state.diagnosticCounters ?: [:]).queueFull
    assert s.state.httpQueue.count { it.kind == 'activation' } == 21
    assert !s.state.httpQueue.any { it.kind == 'priority' }
    int cursor = 0
    while (cursor < s.requests.size()) {
        Map request = s.requests[cursor++]; String path = request.params.uri
        s.respond(request, path.endsWith('/rooms') ? 404 : 200,
            path.endsWith('/configuration') ? fixture('configuration') : path.endsWith('/priority') ? [:] : fixture('device'))
    }
    assert s.state.streams.loc.ready
    assert s.diagnosticSnapshot().streams[0].activated == 21
    assert s.diagnosticSnapshot().streams[0].resynchronized == 21
}
test('Activation arriving during a priority read gets a fresh generation-scoped request') {
    def s = ready(); def c = s.ensureChild('Resideo Connection', s.connectionId('loc'), 'Connection', [kind: 'connection', locationId: 'loc'])
    c.startConnection(s.generation()); s.enqueueRead('priority', mac, s.thermostatPath(mac, 1) + '/priority', [:])
    s.enqueueRead('activation', mac, s.thermostatPath(mac, 1) + '/priority', [locationId: 'loc', connectionGeneration: c.connectionGeneration()])
    assert s.requests.size() == 1 && s.state.httpQueue[0].kind == 'activation'
    s.respond(s.requests[0], 200, [:]); assert s.requests.size() == 2
    s.respond(s.requests[1], 200, [:]); assert s.state.streams.loc.activated
}
test('Sign-in banners explain the wait and display every password phase without secrets') {
    def s = app(); s.settings.loginEmail = 'private@example.invalid'; s.settings.loginPassword = 'private-password'
    s.appButtonHandler('signIn'); assert s.authenticationBanner().contains('Please wait')
    s.performAuthUiAction(s.state.authUiAction)
    ['authorize', 'loginPage', 'credentials', 'callback', 'resume', 'exchange'].eachWithIndex { step, index ->
        if (index) s.authHttp(step, 'GET', s.constants().auth + '/test', null, [:])
        String banner = s.authenticationBanner()
        assert banner.contains('Step ' + (index + 1) + ' of 6')
        assert !banner.contains('private@example.invalid') && !banner.contains('private-password')
        assert s.mainPage().refreshInterval == 3
    }
    assert s.metadataCalls.any { it[0] == 'paragraph' && it[1].join().contains('allow several seconds') }
    s.authFailure('Credentials rejected. Use browser sign-in.', false)
    assert !s.mainPage().refreshInterval && !s.authenticationBanner().contains('Step ')
    assert s.authenticationBanner().contains('#b71c1c')
    s = app(); s.startBrowserLogin(); s.settings.browserRedirect = s.constants().browserRedirect + '?code=private-code&state=' + s.state.browserAttempt.nonce
    s.finishBrowserLogin(); assert s.authenticationProgress().contains('Completing browser sign-in')
    assert !s.authenticationBanner().contains('private-code') && !s.authenticationBanner().contains('Step 6')
}
test('Signed-in thermostat discovery refreshes the page until success and slows during cooldown') {
    def s = ready(); s.state.authStatus = 'Connected'
    s.enqueueRead('accounts', null, s.constants().root + '/v1/accounts', [:])
    assert s.accountDiscoveryPending() && s.authenticationBanner().contains('Finding the thermostats')
    assert s.authenticationBanner().contains('#146c2e') && s.mainPage().refreshInterval == 3
    s.respond(s.requests[0], 429, [:], ['Retry-After': '60'])
    assert s.mainPage().refreshInterval == 30 && s.authenticationProgress().contains('cooldown')
    s.advance(60000L); s.respond(s.requests[-1], 200, fixture('accounts'))
    assert !s.accountDiscoveryPending() && !s.mainPage().refreshInterval
    assert !s.authenticationProgress() && s.state.authStatus == 'Connected'
    s.state.authRequest = [step: 'refresh']; assert !s.authenticationProgress() && !s.mainPage().refreshInterval
}
test('Integration menu and renamed defaults preserve existing accounts and child identities') {
    def s = ready(); s.app.label = 'Resideo Integration'; s.state.diagnostics = 'old-prepared-report'
    Set children = s.children.keySet() as Set; String token = s.state.tokens.refresh_token
    s.initialize(); s.mainPage()
    assert s.app.label == 'Resideo Thermostats Integration'
    assert s.state.tokens.refresh_token == token && s.settings.selectedThermostats == [mac]
    assert s.children.keySet().containsAll(children) && !s.state.diagnostics
    Map definition = s.metadataCalls.find { it[0] == 'definition' }[1]
    assert definition.name == 'Resideo Thermostats Integration' && definition.menu == 'Integrations'
    assert !s.metadataCalls.any { it[0] == 'input' && (it[1].contains('showDiagnostics') || it[1].contains('clearDiagnostics')) }
    assert !s.metadataCalls.any { it[0] == 'paragraph' && it[1].join().contains('old-prepared-report') }
    s.app.label = 'My custom thermostat account'; s.migrateAppPresentation()
    assert s.app.label == 'My custom thermostat account'
}
println('Passed ' + passed + ' test groups using Groovy ' + GroovySystem.version)
