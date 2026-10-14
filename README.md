# Resideo / First Alert for Hubitat

Native Hubitat app and drivers for thermostats registered in the **First Alert** app. Everything runs on the hub and talks directly to Resideo and its Azure SignalR service. No Home Assistant instance, bridge computer, or hosted callback is needed. Internet access and Resideo's cloud are required.

**Version 0.1.4 — implementation candidate.** Automated verification uses Groovy 2.4.21 and a simulated Hubitat runtime. Installation through HPM and compilation of the app and all three drivers on a physical hub are verified. Actual account sign-in, hardware compatibility, dashboard behavior, and a live 24-hour soak are still being validated. See [validation](docs/validation.md).

## Install

Follow [INSTALL.md](INSTALL.md). In **Hubitat Package Manager → Install → From a URL**, enter `https://raw.githubusercontent.com/evdev/hubitat-resideo/main/packageManifest.json`. HPM installs the app and all three drivers and can open the account setup page. The app creates the device children after authentication and selection. Manual installation is also documented.

## Included

- Email/password and guided browser Auth0 PKCE sign-in, rotating refresh tokens, and reauthentication status. Browser sign-in uses address-bar copy/paste without Developer Tools.
- Multiple thermostats and locations per First Alert account; one app instance per account.
- Heat, cool, off, auto, emergency heat, fan controls, setpoints, and supported holds.
- Schedule enable/disable, Feels Like, Adaptive Recovery, setpoint limits, and configured freeze protection.
- Room-sensor temperature, humidity, motion, occupancy, air quality, battery status, and occupancy settings where reported.
- Real-time SignalR updates with five-minute polling during outages; configurable from 1 to 30 minutes.
- Firmware, HVAC, filter, faults, schedule, recovery, and other diagnostics.
- Confirmation of asynchronous cloud writes, stale/offline status, and redacted diagnostic output.
- Optional status/debug logs, automatic 30-minute debug shutoff, bounded diagnostic history, and request/command tracing.

The [parity matrix](docs/parity.md) maps all 79 entities exposed by the pinned Home Assistant integration. Advanced settings are custom commands on the thermostat or sensor, suitable for Rule Machine custom actions. No separate switch devices are created.

## Files

| File | Install location / purpose |
|---|---|
| `packageManifest.json` | HPM package with one app and three required drivers |
| `repository.json` | Optional HPM custom repository listing |
| `apps/resideo-integration.groovy` | Apps Code: account, REST client, device state, and lifecycle |
| `drivers/resideo-connection.groovy` | Drivers Code: SignalR connection |
| `drivers/resideo-thermostat.groovy` | Drivers Code: thermostat interface |
| `drivers/resideo-room-sensor.groovy` | Drivers Code: room-sensor interface |
| `docs/commands.md` | Commands, units, and status semantics |
| `docs/troubleshooting.md` | Sign-in, connection, and confirmation issues |
| `docs/architecture.md` | Queues, protocol, state ownership, and compatibility |
| `docs/validation.md` | Automated coverage and remaining live checks |
| `docs/review.md` | Bugs corrected during the follow-up review |

## Tests

Run `sh tests/run.sh`. The runner downloads the pinned Groovy 2.4.21 JAR into ignored `.runtime/` storage if needed. It requires Java and network access for that first download; the tests themselves use fixtures and do not contact a thermostat or cloud account.

An existing runtime can be selected with `RESIDEO_GROOVY_JAR=/absolute/path/groovy-all-2.4.21.jar sh tests/run.sh`. The installed Groovy 5 runtime is intentionally not used. Modern Java versions may print Groovy's legacy `Unsafe` warning.

## Upstream and boundaries

Hubitat integration author and maintainer: [evdev](https://github.com/evdev) (Ephrayim). Commit author email: `evbenev@gmail.com`.

Adapted from [sfcodes/ha-resideo](https://github.com/sfcodes/ha-resideo), revision [`0e2947b8ad83608ccf212dd2638f2aa3eda06eaa`](https://github.com/sfcodes/ha-resideo/tree/0e2947b8ad83608ccf212dd2638f2aa3eda06eaa). The original API client, protocol, mapping rules, and sanitized fixtures are MIT-licensed, copyright © 2026 Semyon Fishman. See [LICENSE.upstream](LICENSE.upstream) and [LICENSE](LICENSE).

This port uses the same unofficial consumer API. A future Resideo change may require updated constants, authentication, or protocol handling. Devices on the older Resideo/Lyric or Total Connect Comfort services use other APIs. Vacation and timed holds can be reported but cannot be started here; edit schedules and other read-only settings in First Alert.

Source, tests, and guides are available in [evdev/hubitat-resideo](https://github.com/evdev/hubitat-resideo). HPM URL installation and a custom repository listing are provided. Community catalog registration is not configured.
