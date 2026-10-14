/* Resideo thermostat for Hubitat, v0.1.11. MIT; see LICENSE.upstream. */
metadata {
    definition(name: 'Resideo Thermostat', namespace: 'resideoHubitat', author: 'Ephrayim (evdev)', singleThreaded: true) {
        capability 'Actuator'
        capability 'Sensor'
        capability 'Refresh'
        capability 'Thermostat'
        capability 'TemperatureMeasurement'
        capability 'RelativeHumidityMeasurement'
        capability 'MotionSensor'
        capability 'CarbonDioxideMeasurement'
        attribute 'supportedThermostatModes', 'JSON_OBJECT'
        attribute 'supportedThermostatFanModes', 'JSON_OBJECT'
        attribute 'outdoorTemperature', 'number'
        attribute 'outdoorHumidity', 'number'
        attribute 'occupancy', 'enum', ['occupied', 'unoccupied']
        attribute 'voc', 'number'
        attribute 'holdStatus', 'string'
        attribute 'heatCoolMode', 'string'
        attribute 'equipmentStatus', 'string'
        attribute 'demand', 'number'
        attribute 'currentStage', 'number'
        attribute 'airQuality', 'string'
        attribute 'co2Quality', 'string'
        attribute 'vocQuality', 'string'
        attribute 'humidityQuality', 'string'
        attribute 'schedulePeriod', 'string'
        attribute 'scheduleDay', 'string'
        attribute 'scheduleType', 'string'
        attribute 'backlight', 'string'
        attribute 'priorityType', 'string'
        attribute 'priorityStatus', 'string'
        attribute 'airFilterRemaining', 'number'
        attribute 'firmwareVersion', 'string'
        attribute 'firmwareStatus', 'string'
        attribute 'firmwareUpdated', 'string'
        attribute 'registered', 'string'
        attribute 'faultCount', 'number'
        attribute 'signalStrength', 'number'
        attribute 'fanReason', 'string'
        attribute 'demandResponse', 'string'
        attribute 'adaptiveRecoveryMode', 'string'
        attribute 'ventilationTimer', 'number'
        attribute 'ventilationBoostTimer', 'number'
        attribute 'language', 'string'
        attribute 'heatingSystem', 'string'
        attribute 'heatingStages', 'number'
        attribute 'coolingSystem', 'string'
        attribute 'coolingStages', 'number'
        attribute 'temperatureUnits', 'string'
        attribute 'matterStatus', 'string'
        attribute 'heatSetpointMin', 'number'
        attribute 'heatSetpointMax', 'number'
        attribute 'coolSetpointMin', 'number'
        attribute 'coolSetpointMax', 'number'
        attribute 'freezeProtectionLowLimit', 'number'
        attribute 'online', 'enum', ['on', 'off']
        attribute 'fanRunning', 'enum', ['on', 'off']
        attribute 'circulationFan', 'enum', ['on', 'off']
        attribute 'adaptiveRecoveryActive', 'enum', ['on', 'off']
        attribute 'vacationHold', 'enum', ['on', 'off']
        attribute 'airFilter', 'enum', ['on', 'off']
        attribute 'fault', 'enum', ['on', 'off']
        attribute 'freezeProtection', 'enum', ['on', 'off']
        attribute 'freezeProtectionConfigured', 'enum', ['on', 'off']
        attribute 'awayMode', 'enum', ['on', 'off']
        attribute 'commercial', 'enum', ['on', 'off']
        attribute 'feelsLikeEnabled', 'enum', ['on', 'off']
        attribute 'adaptiveRecoveryEnabled', 'enum', ['on', 'off']
        attribute 'scheduleEnabled', 'enum', ['on', 'off']
        attribute 'emergencyHeatEnabled', 'enum', ['on', 'off']
        attribute 'connectionStatus', 'string'
        attribute 'updateSource', 'string'
        attribute 'lastUpdated', 'string'
        attribute 'dataStatus', 'string'
        attribute 'commandStatus', 'string'
        attribute 'lastCommandError', 'string'
        command 'setHold', [[name: 'Hold', type: 'ENUM', constraints: ['none', 'temporary', 'permanent']]]
        command 'resumeSchedule'
        command 'setScheduleEnabled', [[name: 'Enabled', type: 'ENUM', constraints: ['on', 'off']]]
        command 'setFeelsLikeEnabled', [[name: 'Enabled', type: 'ENUM', constraints: ['on', 'off']]]
        command 'setAdaptiveRecoveryEnabled', [[name: 'Enabled', type: 'ENUM', constraints: ['on', 'off']]]
        command 'setEmergencyHeatEnabled', [[name: 'Enabled', type: 'ENUM', constraints: ['on', 'off']]]
        command 'setSetpointLimits', [[name: 'Heat minimum', type: 'NUMBER'], [name: 'Heat maximum', type: 'NUMBER'], [name: 'Cool minimum', type: 'NUMBER'], [name: 'Cool maximum', type: 'NUMBER']]
        command 'setFreezeProtectionLowLimit', [[name: 'Floor in hub temperature units', type: 'NUMBER']]
    }
    preferences { input 'debugEnable', 'bool', title: 'Driver debug logging for 30 minutes', defaultValue: false }
}
void installed() { configureLogging() }
void updated() { configureLogging(); refresh() }
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
void parse(String ignored) { }
void refresh() {
    driverDebug 'Resideo thermostat refresh requested'
    queueParentMessage([kind: 'refresh', mac: getDataValue('mac')])
}
void auto() { setThermostatMode('auto') }
void cool() { setThermostatMode('cool') }
void heat() { setThermostatMode('heat') }
void off() { setThermostatMode('off') }
void emergencyHeat() { setThermostatMode('emergency heat') }
void fanAuto() { setThermostatFanMode('auto') }
void fanCirculate() { setThermostatFanMode('circulate') }
void fanOn() { setThermostatFanMode('on') }
void setThermostatMode(String value) { dispatch('setThermostatMode', [value]) }
void setThermostatFanMode(String value) { dispatch('setThermostatFanMode', [value]) }
void setHeatingSetpoint(value) { dispatch('setHeatingSetpoint', [value]) }
void setCoolingSetpoint(value) { dispatch('setCoolingSetpoint', [value]) }
void setHold(String value) { dispatch('setHold', [value]) }
void resumeSchedule() { setHold('none') }
void setScheduleEnabled(String value) { dispatch('setScheduleEnabled', [value]) }
void setFeelsLikeEnabled(String value) { dispatch('setFeelsLikeEnabled', [value]) }
void setAdaptiveRecoveryEnabled(String value) { dispatch('setAdaptiveRecoveryEnabled', [value]) }
void setEmergencyHeatEnabled(String value) {
    if (value in ['on', 'off']) setThermostatMode(value == 'on' ? 'emergency heat' : 'heat')
    else rejectLocal('setEmergencyHeatEnabled', 'Use on or off.')
}
void setSetpointLimits(a, b, c, d) { dispatch('setSetpointLimits', [a, b, c, d]) }
void setFreezeProtectionLowLimit(value) { dispatch('setFreezeProtectionLowLimit', [value]) }
// Required by Thermostat; schedule programming is outside the upstream API surface.
void setSchedule(value) { rejectLocal('setSchedule', 'Schedule editing is not supported. Use the First Alert app.') }
void rejectLocal(String command, String reason) {
    applyAttributes([commandStatus: 'rejected', lastCommandError: reason])
    queueParentMessage([kind: 'driverRejected', mac: getDataValue('mac'), command: command])
}
void dispatch(String command, List args) {
    driverDebug 'Resideo thermostat command forwarded; argumentCount=' + args.size()
    queueParentMessage([kind: 'command', mac: getDataValue('mac'), command: command, args: args])
}
void applySnapshot(Map values) {
    state.pendingProjection = [full: true, values: values]
    scheduleProjection()
}
void applyAttributes(Map values) {
    Map pending = state.pendingProjection ?: [full: false, values: [:]]
    pending.values = (pending.values ?: [:]) + values
    state.pendingProjection = pending
    scheduleProjection()
}
void scheduleProjection() {
    // Parent calls only queue data. Emit native events in this driver's own
    // short execution, coalescing adjacent REST responses and pushed fields.
    if (!state.projectionDue || now() >= (state.projectionDue as long)) {
        state.projectionDue = now() + 100L
        runInMillis(100, 'flushProjection')
    }
}
void flushProjection() {
    Map pending = state.pendingProjection ?: [:]
    state.projectionDue = null
    if (!pending) return
    Map values = pending.values ?: [:]
    if (pending.full) {
        Map emitted = state.emittedAttributes ?: [:]
        (state.projectedAttributes ?: []).findAll { !values.containsKey(it) && it != 'thermostatSetpoint' }.each {
            device.deleteCurrentState(it.toString()); emitted.remove(it.toString())
        }
        state.emittedAttributes = emitted
        state.projectedAttributes = values.keySet().toList()
    }
    emitAttributes(values)
    state.pendingProjection = null
}
void emitAttributes(Map values) {
    int changed = 0
    Map emitted = state.emittedAttributes ?: [:]
    values.each { name, value ->
        if (value == null) return
        String key = name.toString()
        Object previous = emitted.containsKey(key) ? emitted[key] : device.currentValue(key)
        emitted[key] = value
        if (previous == value) return
        Map event = [name: name, value: value]
        if (name in ['temperature', 'outdoorTemperature', 'heatingSetpoint', 'coolingSetpoint', 'thermostatSetpoint', 'heatSetpointMin', 'heatSetpointMax', 'coolSetpointMin', 'coolSetpointMax', 'freezeProtectionLowLimit']) event.unit = '°' + getTemperatureScale()
        if (name in ['humidity', 'outdoorHumidity', 'demand']) event.unit = '%'
        if (name == 'carbonDioxide') event.unit = 'ppm'
        if (name == 'signalStrength') event.unit = 'dBm'
        if (name == 'airFilterRemaining') event.unit = 'd'
        if (name in ['ventilationTimer', 'ventilationBoostTimer']) event.unit = 'min'
        sendEvent(event)
        changed++
    }
    state.emittedAttributes = emitted
    if (changed) driverDebug 'Resideo thermostat attributes updated; count=' + changed
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
