/* Resideo room sensor for Hubitat, v0.1.2. MIT; see LICENSE.upstream. */
metadata {
    definition(name: 'Resideo Room Sensor', namespace: 'resideoHubitat', author: 'Ephrayim (evdev)', singleThreaded: true) {
        capability 'Sensor'
        capability 'Refresh'
        capability 'TemperatureMeasurement'
        capability 'RelativeHumidityMeasurement'
        capability 'MotionSensor'
        capability 'CarbonDioxideMeasurement'
        attribute 'temperatureActual', 'number'
        attribute 'occupancy', 'enum', ['occupied', 'unoccupied']
        attribute 'voc', 'number'
        attribute 'batteryStatus', 'string'
        attribute 'signalStrength', 'number'
        attribute 'status', 'string'
        attribute 'occupancyTimeout', 'number'
        attribute 'occupancySensitivity', 'string'
        attribute 'excludeMotion', 'enum', ['on', 'off']
        attribute 'excludeTemperature', 'enum', ['on', 'off']
        attribute 'connectionStatus', 'string'
        attribute 'updateSource', 'string'
        attribute 'lastUpdated', 'string'
        attribute 'dataStatus', 'string'
        attribute 'commandStatus', 'string'
        attribute 'lastCommandError', 'string'
        command 'setOccupancySensitivity', [[name: 'Sensitivity', type: 'ENUM', constraints: ['off', 'low', 'medium', 'high']]]
        command 'setExcludeMotion', [[name: 'Exclude motion', type: 'ENUM', constraints: ['on', 'off']]]
        command 'setExcludeTemperature', [[name: 'Exclude temperature', type: 'ENUM', constraints: ['on', 'off']]]
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
    driverDebug 'Resideo sensor refresh requested'
    parent.postMessage([kind: 'refresh', mac: getDataValue('mac')])
}
void setOccupancySensitivity(String value) { dispatch('setOccupancySensitivity', [value]) }
void setExcludeMotion(String value) { dispatch('setExcludeMotion', [value]) }
void setExcludeTemperature(String value) { dispatch('setExcludeTemperature', [value]) }
void dispatch(String command, List args) {
    driverDebug 'Resideo sensor command forwarded; argumentCount=' + args.size()
    parent.postMessage([kind: 'command', mac: getDataValue('mac'), accessoryId: getDataValue('accessoryId'), command: command, args: args])
}
void applySnapshot(Map values) {
    (state.projectedAttributes ?: []).findAll { !values.containsKey(it) }.each { device.deleteCurrentState(it.toString()) }
    state.projectedAttributes = values.keySet().toList()
    applyAttributes(values)
}
void applyAttributes(Map values) {
    int changed = 0
    values.each { name, value ->
        if (value == null || device.currentValue(name.toString()) == value) return
        Map event = [name: name, value: value]
        if (name in ['temperature', 'temperatureActual']) event.unit = '°' + getTemperatureScale()
        if (name == 'humidity') event.unit = '%'
        if (name == 'carbonDioxide') event.unit = 'ppm'
        if (name == 'signalStrength') event.unit = 'dBm'
        if (name == 'occupancyTimeout') event.unit = 's'
        sendEvent(event)
        changed++
    }
    if (changed) driverDebug 'Resideo sensor attributes updated; count=' + changed
}
