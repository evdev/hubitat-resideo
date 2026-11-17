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

## Upstream request audit — 2026-10-14

The current `main` revision of [sfcodes/ha-resideo](https://github.com/sfcodes/ha-resideo) was checked directly on GitHub and remained `0e2947b8ad83608ccf212dd2638f2aa3eda06eaa`, the port's pinned baseline. Its client, constants, climate, number, switch, and select sources matched the local reference after ignoring trailing file whitespace. The comparison uses that project's reverse-engineered consumer API implementation; it is not an official Resideo API specification.

The [upstream request builders](https://github.com/sfcodes/ha-resideo/blob/0e2947b8ad83608ccf212dd2638f2aa3eda06eaa/custom_components/resideo/aioresideo/client.py#L287-L453) were exercised with an offline recording transport. All **56 comparisons** passed through the actual Hubitat driver methods, parent command validation, dispatch, and JSON serialization. They cover every exposed write and convenience alias, both boolean values, every exposed enum, schedule-dependent holds, Celsius conversion, complete bodies, and JSON value types. The full suite passed **106 test groups**. No production command-syntax correction or hub code update was needed.

Every write is `PUT https://api.ha.resideo.com/ris-public-api/api/v1/devices/thermostats/{id}/{route}` and includes `ChannelId: "ds-notification-service"`. Bearer authorization, the APIM subscription header, JSON content type, Accept, and app User-Agent were checked.

| Route | Verified body fields and values, in addition to ChannelId |
|---|---|
| `systemSwitch` | `SystemSwitchValue`: Heat, Cool, Off, Auto, EmergencyHeat |
| `fanSwitch` | `FanSwitch`: nested Position Auto/Circulate/On and integer Speed 1 |
| `heatSetpoint`, `coolSetpoint` | `SetpointStatus`: TemporaryHold or PermanentHold; numeric `SetpointValue` in Fahrenheit |
| `hold` | `Status`: NoHold, TemporaryHold, PermanentHold |
| `schedule/enabled` | Explicit boolean `ScheduleEnabled` |
| `feelsLike` | Explicit boolean `FeelsLikeEnabled` |
| `adaptiveIntelligentRecovery` | `Mode`: None or AdaptiveIntelligentRecovery |
| `setPointCapabilities` | All four integer fields: minHeatSetpoint, maxHeatSetpoint, minCoolSetpoint, maxCoolSetpoint |
| `freezeProtection` | Integer `LowLimitDegrees` in Fahrenheit |
| `accessories/{id}/accessoryValue` | All three fields: sensitivity Off/Low/Medium/High, boolean excludeMotion, boolean excludeTemp |

Refresh was checked separately: shadow/configuration use GET v2, priority/rooms use GET v1. Connection initialization and refresh manage the SignalR connection; they are not thermostat PUT commands. The subscription method and arguments match upstream `SubscribeSignalRV2` with the location node ID.

Intentional scope and behavior differences:

- The upstream client has a raw room-priority write helper, but its Home Assistant platforms do not expose it; this port also leaves priority read-only.
- DelayedStartRecovery exists in the API enum but is not selected by either integration's two-state adaptive-recovery control. Vacation/hold-until creation and schedule programming remain unsupported by the exposed controls.
- Integer settings use half-up rounding in Hubitat; upstream Python uses ties-to-even. For example, 40.5°F becomes integer 41 here and integer 40 upstream. Both are valid freeze-protection payloads; this is a rounding choice, not a syntax mismatch. The contract comparison excludes exact half ties.
- This audit proves request compatibility with the referenced client. Physical support and behavior for each hardware model still require the live checks in [validation.md](validation.md).

The generated expectations are in `tests/fixtures/command-contracts.json`, with source hashes and revision provenance. To regenerate from a verified source snapshot, run `python3 tools/build-command-contracts.py --source-root /path/to/upstream`. The tool extracts the inspected command methods and hold selector into an offline recorder without importing upstream authentication or HTTP transport. Normal tests consume the checked-in JSON and need no Python or upstream checkout.
