# Commands and attributes

All temperatures entered through commands use **the hub's temperature scale**. Thermostat display-unit metadata does not determine API payload units. Changing the hub's scale updates readings on the next refresh; avoid changing scale while commands are pending.

## Thermostat

| Command | Parameters / behavior |
|---|---|
| `heat`, `cool`, `off`, `auto`, `emergencyHeat` | Select a mode supported by the thermostat configuration |
| `setThermostatMode` | `heat`, `cool`, `off`, `auto`, `emergency heat` |
| `fanAuto`, `fanCirculate`, `fanOn` | Select a supported fan mode |
| `setThermostatFanMode` | `auto`, `circulate`, `on` |
| `setHeatingSetpoint` | Numeric temperature within the reported heating range |
| `setCoolingSetpoint` | Numeric temperature within the reported cooling range |
| `setHold` | `none`, `temporary`, `permanent` |
| `resumeSchedule` | Equivalent to `setHold('none')`; requires an enabled schedule |
| `setScheduleEnabled` | `on` or `off`; enables/disables the thermostat's existing schedule |
| `setFeelsLikeEnabled` | `on` or `off`, where reported |
| `setAdaptiveRecoveryEnabled` | `on` → AdaptiveIntelligentRecovery; `off` → None, where supported |
| `setEmergencyHeatEnabled` | `on` → emergency heat; `off` → heat |
| `setSetpointLimits` | Four numeric values: heat minimum, heat maximum, cool minimum, cool maximum |
| `setFreezeProtectionLowLimit` | Numeric floor corresponding to 35–45°F, when protection is configured |
| `refresh` | Refresh state, room sensors, configuration, and priority |
| `setSchedule` | Required thermostat interface method; rejects schedule programming with an explanation |

Setpoint writes follow First Alert's hold behavior: temporary while following an enabled schedule or already in temporary hold, otherwise permanent. Vacation/timed holds remain read-only. Turning a schedule on may change setpoints to the current schedule period; turning it off keeps the thermostat's resulting setpoints.

The heating/cooling deadband and reported bounds are enforced. The integration never silently shifts the other setpoint. Auto mode exposes the separate heating and cooling setpoints. `thermostatSetpoint` reflects the active side and retains its previous value while neither side is active.

All four limit fields are sent together. Limits round to whole Fahrenheit degrees, remain within the upstream controls' 50–90°F interval, and require ordered min/max values with heating limits at or below the corresponding cooling limits. Freeze protection also rounds to whole Fahrenheit degrees.

## Room sensor

| Command | Parameters / behavior |
|---|---|
| `setOccupancySensitivity` | `off`, `low`, `medium`, `high` |
| `setExcludeMotion` | `on` or `off` |
| `setExcludeTemperature` | `on` or `off` |
| `refresh` | Refresh the owning thermostat and its sensor data |

These writes require a remote IndoorAirSensor reporting occupancy sensitivity. Every request includes sensitivity and both exclusion flags, incorporating accepted pending changes to preserve successive edits.

Sensitivity changes are deferred until the battery-powered sensor checks in. The requested value remains pending across hub restarts until reported; no fixed timer assumes failure. Exclusion flags use the normal confirmation path.

## Integration status

| Attribute | Meaning |
|---|---|
| `connectionStatus` | `connected`, `polling`, `disabled`, `authentication required`, or `unavailable` |
| `updateSource` | `push` when the location feed is ready; otherwise `poll` |
| `dataStatus` | `current`, `stale`, `offline`, or `unavailable` |
| `lastUpdated` | UTC timestamp of the latest successful thermostat-state update |
| `commandStatus` | `idle`, `pending`, `confirmed`, `unconfirmed`, `failed`, `rejected`, or `uncertain` |
| `lastCommandError` | Explanation for a failed, rejected, or unconfirmed command |

An HTTP 202 only accepts a change. Accepted values appear immediately, but `commandStatus` remains pending until reported state matches. Dispatch success alone does not confirm it. A read after the ten-second settling period restores reported values for an unconfirmed normal command. A timeout is checked through reads and is never automatically retransmitted.

Commands are asynchronous; a Rule Machine custom action does not receive a synchronous success/failure result. Use these status attributes when an automation needs confirmation.

The source's binary statuses use `on`/`off`. Motion uses `active`/`inactive`, occupancy uses `occupied`/`unoccupied`, and battery status remains the cloud's categorical string. Numeric battery percentages and unspecified VOC units are not fabricated.

See [parity.md](parity.md) for every diagnostic attribute and its source entity.
