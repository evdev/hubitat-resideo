# Upstream parity matrix

Reference: sfcodes/ha-resideo `0e2947b8ad83608ccf212dd2638f2aa3eda06eaa`.

All upstream platform entities are mapped below. Where hardware omits a reading, its Hubitat attribute remains unset. The availability expressions refer to the upstream property names. Static Hubitat metadata still lists custom commands; unsupported operations are rejected.

| Platform | Device | Upstream entity | Hubitat attribute(s) | Command | Units | Availability | Write behavior |
|---|---|---|---|---|---|---|---|
| climate | thermostat | thermostat | temperature, humidity, thermostatMode, thermostatFanMode, thermostatOperatingState, heatingSetpoint, coolingSetpoint, holdStatus | standard Thermostat commands; setHold; resumeSchedule | hub °F/°C | reported values; configured supported modes/limits | accepted → reported-state confirmation |
| sensor | thermostat | indoor_temperature | temperature | — | hub °F/°C | reported value; missing values remain unset | read-only |
| sensor | thermostat | outdoor_temperature | outdoorTemperature | — | hub °F/°C | reported value; missing values remain unset | read-only |
| sensor | thermostat | indoor_humidity | humidity | — | % | reported value; missing values remain unset | read-only |
| sensor | thermostat | outdoor_humidity | outdoorHumidity | — | % | reported value; missing values remain unset | read-only |
| sensor | thermostat | co2 | carbonDioxide | — | ppm | _co2(d) is not None | read-only |
| sensor | thermostat | tvoc | voc | — | upstream unspecified | _tvoc(d) is not None | read-only |
| sensor | thermostat | heat_setpoint | heatingSetpoint | — | hub °F/°C | reported value; missing values remain unset | read-only |
| sensor | thermostat | cool_setpoint | coolingSetpoint | — | hub °F/°C | reported value; missing values remain unset | read-only |
| sensor | thermostat | setpoint_status | holdStatus | — | — | reported value; missing values remain unset | read-only |
| sensor | thermostat | equipment_status | equipmentStatus | — | — | reported value; missing values remain unset | read-only |
| sensor | thermostat | demand | demand | — | % | reported value; missing values remain unset | read-only |
| sensor | thermostat | current_stage | currentStage | — | — | reported value; missing values remain unset | read-only |
| sensor | thermostat | air_quality | airQuality | — | — | reported value; missing values remain unset | read-only |
| sensor | thermostat | co2_quality | co2Quality | — | — | reported value; missing values remain unset | read-only |
| sensor | thermostat | voc_quality | vocQuality | — | — | reported value; missing values remain unset | read-only |
| sensor | thermostat | humidity_quality | humidityQuality | — | — | reported value; missing values remain unset | read-only |
| sensor | thermostat | schedule_period | schedulePeriod | — | — | reported value; missing values remain unset | read-only |
| sensor | thermostat | backlight | backlight | — | — | reported value; missing values remain unset | read-only |
| sensor | thermostat | priority_type | priorityType | — | — | reported value; missing values remain unset | read-only |
| sensor | thermostat | air_filter_remaining | airFilterRemaining | — | days | reported value; missing values remain unset | read-only |
| sensor | thermostat | firmware_version | firmwareVersion | — | — | reported value; missing values remain unset | read-only |
| sensor | thermostat | fault_count | faultCount | — | — | reported value; missing values remain unset | read-only |
| sensor | thermostat | signal_strength | signalStrength | — | dBm | d.thermostat.signal_strength is not None | read-only |
| sensor | thermostat | heat_cool_mode | heatCoolMode | — | — | d.thermostat.heat_cool_mode is not None | read-only |
| sensor | thermostat | fan_reason | fanReason | — | — | reported value; missing values remain unset | read-only |
| sensor | thermostat | demand_response | demandResponse | — | — | d.thermostat.demand_response_state is not None | read-only |
| sensor | thermostat | schedule_day | scheduleDay | — | — | d.thermostat.schedule_day is not None | read-only |
| sensor | thermostat | schedule_type | scheduleType | — | — | d.thermostat.schedule_type is not None | read-only |
| sensor | thermostat | adaptive_recovery_mode | adaptiveRecoveryMode | — | — | d.thermostat.active_adaptive_recovery_mode is not None | read-only |
| sensor | thermostat | ventilation_timer | ventilationTimer | — | minutes | d.thermostat.ventilation_timer is not None | read-only |
| sensor | thermostat | ventilation_boost_timer | ventilationBoostTimer | — | minutes | d.thermostat.ventilation_boost_timer is not None | read-only |
| sensor | thermostat | language | language | — | — | d.thermostat.current_language is not None | read-only |
| sensor | thermostat | firmware_status | firmwareStatus | — | — | d.thermostat.firmware_status is not None | read-only |
| sensor | thermostat | registered | registered | — | — | dt_util.parse_datetime(d.thermostat.registration_date or '') is not None | read-only |
| sensor | thermostat | firmware_updated | firmwareUpdated | — | — | dt_util.parse_datetime(d.thermostat.firmware_last_updated or '') is not None | read-only |
| sensor | thermostat | heating_system | heatingSystem | — | — | d.configuration.heating_system is not None | read-only |
| sensor | thermostat | heating_stages | heatingStages | — | — | d.configuration.heating_stages is not None | read-only |
| sensor | thermostat | cooling_system | coolingSystem | — | — | d.configuration.cooling_system is not None | read-only |
| sensor | thermostat | cooling_stages | coolingStages | — | — | d.configuration.cooling_stages is not None | read-only |
| sensor | thermostat | temperature_units | temperatureUnits | — | F / C metadata | d.configuration.temperature_units is not None | read-only |
| sensor | thermostat | matter_status | matterStatus | — | — | d.configuration.matter_commissioning_status is not None | read-only |
| sensor | thermostat | priority_status | priorityStatus | — | — | d.priority.priority_status is not None | read-only |
| sensor | sensor | temperature | temperature | — | hub °F/°C | a.indoor_temperature is not None | read-only |
| sensor | sensor | humidity | humidity | — | % | a.indoor_humidity is not None | read-only |
| sensor | sensor | temperature_actual | temperatureActual | — | hub °F/°C | a.temperature_actual is not None | read-only |
| sensor | sensor | co2 | carbonDioxide | — | ppm | a.co2 is not None | read-only |
| sensor | sensor | voc | voc | — | upstream unspecified | a.tvoc is not None | read-only |
| sensor | sensor | battery_status | batteryStatus | — | — | a.battery_status is not None | read-only |
| sensor | sensor | signal_strength | signalStrength | — | dBm | a.rssi is not None | read-only |
| sensor | sensor | status | status | — | — | a.status is not None | read-only |
| sensor | sensor | occupancy_timeout | occupancyTimeout | — | seconds | a.occupancy_timeout is not None | read-only |
| binary_sensor | thermostat | motion | motion | — | — | _ta(d) is not None and _ta(d).motion is not None | read-only |
| binary_sensor | thermostat | occupancy | occupancy | — | — | _ta(d) is not None and _ta(d).occupancy is not None | read-only |
| binary_sensor | thermostat | online | online | — | — | reported value; missing values remain unset | read-only |
| binary_sensor | thermostat | fan_running | fanRunning | — | — | reported value; missing values remain unset | read-only |
| binary_sensor | thermostat | circulation_fan | circulationFan | — | — | reported value; missing values remain unset | read-only |
| binary_sensor | thermostat | adaptive_recovery_active | adaptiveRecoveryActive | — | — | reported value; missing values remain unset | read-only |
| binary_sensor | thermostat | vacation_hold | vacationHold | — | — | reported value; missing values remain unset | read-only |
| binary_sensor | thermostat | air_filter | airFilter | — | — | reported value; missing values remain unset | read-only |
| binary_sensor | thermostat | fault | fault | — | — | reported value; missing values remain unset | read-only |
| binary_sensor | thermostat | freeze_protection | freezeProtection | — | — | bool(d.configuration.reported) | read-only |
| binary_sensor | thermostat | freeze_protection_configured | freezeProtectionConfigured | — | — | bool(d.configuration.reported) | read-only |
| binary_sensor | thermostat | away_mode | awayMode | — | — | bool(d.configuration.reported) | read-only |
| binary_sensor | thermostat | commercial | commercial | — | — | bool(d.configuration.reported) | read-only |
| binary_sensor | sensor | motion | motion | — | — | a.motion is not None | read-only |
| binary_sensor | sensor | occupancy | occupancy | — | — | a.occupancy is not None | read-only |
| switch | thermostat | feels_like | feelsLikeEnabled | setFeelsLikeEnabled | — | reported value; missing values remain unset | accepted → reported-state confirmation |
| switch | thermostat | adaptive_recovery | adaptiveRecoveryEnabled | setAdaptiveRecoveryEnabled | — | reported value; missing values remain unset | accepted → reported-state confirmation |
| switch | thermostat | schedule | scheduleEnabled | setScheduleEnabled | — | reported value; missing values remain unset | accepted → reported-state confirmation |
| switch | thermostat | emergency_heat | emergencyHeatEnabled | setEmergencyHeatEnabled | — | SYSTEM_SWITCH_EMERGENCY_HEAT in (d.configuration.system_switch_capabilities or []) | accepted → reported-state confirmation |
| switch | sensor | exclude_motion | excludeMotion | setExcludeMotion | — | remote sensor reports OccupancySensitivity | accepted → reported-state confirmation |
| switch | sensor | exclude_temperature | excludeTemperature | setExcludeTemperature | on / off | remote sensor reports OccupancySensitivity | accepted → reported-state confirmation |
| number | thermostat | freeze_protection_low_limit | freezeProtectionLowLimit | setFreezeProtectionLowLimit | hub °F/°C | d.configuration.freeze_protection_configured | accepted → reported-state confirmation |
| number | thermostat | heat_setpoint_min | heatSetpointMin | setSetpointLimits | hub °F/°C | reported value; missing values remain unset | accepted → reported-state confirmation |
| number | thermostat | heat_setpoint_max | heatSetpointMax | setSetpointLimits | hub °F/°C | reported value; missing values remain unset | accepted → reported-state confirmation |
| number | thermostat | cool_setpoint_min | coolSetpointMin | setSetpointLimits | hub °F/°C | reported value; missing values remain unset | accepted → reported-state confirmation |
| number | thermostat | cool_setpoint_max | coolSetpointMax | setSetpointLimits | hub °F/°C | reported value; missing values remain unset | accepted → reported-state confirmation |
| select | sensor | occupancy_sensitivity | occupancySensitivity | setOccupancySensitivity | Off / Low / Medium / High | remote sensor reports OccupancySensitivity | pending until sensor check-in; persists across restart |

Extra integration attributes: `connectionStatus`, `dataStatus`, `updateSource`, `lastUpdated`, `commandStatus`, and `lastCommandError`.

Vacation/timed holds, room-priority settings, schedule programming, backlight, ventilation, and Matter provisioning remain read-only where upstream exposes no writable platform control. The room-priority API method exists upstream but is not exposed by its platforms, so this port does not add a new writable control.

`BatteryStatus` is a category, not a percentage. VOC units are unspecified upstream and remain unlabeled. All API thermostat and room temperatures use Fahrenheit; outdoor units use account country.
