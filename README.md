# Resideo Thermostats Integration

Native Hubitat app and drivers for thermostats registered in the **First Alert** app. Everything runs on the hub and talks directly to Resideo and its Azure SignalR service. No Home Assistant instance, bridge computer, or hosted callback is needed. Internet access and Resideo's cloud are required.

**Version 0.1.12 — implementation candidate.** All 137 automated test groups pass using Groovy 2.4.21 and a simulated Hubitat runtime, including the authentication recovery regressions and 56 upstream command-request comparisons. The new authentication recovery and optional password storage require physical-hub validation. Existing physical-hub installation, thermostat-command, restart, and stream-recovery acceptance records apply to v0.1.11. Broader hardware compatibility, router/firewall-level outages, independent equipment behavior, dashboards, and a live 24-hour soak remain pending. See [validation](docs/validation.md).

## Install

The package is listed in the existing approved **evdev** HPM catalog. In **Hubitat Package Manager → Install → Browse by Tags → Climate Control**, select **Resideo Thermostats Integration**. No custom repository is required. Existing HPM-managed installations use **Update**; manually installed code can use **Match Up** first. HPM manages the app and all three drivers. See [INSTALL.md](INSTALL.md) for setup and advanced URL/manual installation.

## Included

- Email/password and guided browser Auth0 PKCE sign-in, rotating refresh tokens, and reauthentication status. Browser sign-in uses address-bar copy/paste without Developer Tools.
- Saved-token recovery after shutdowns, automatic retries after temporary authentication outages, and a minute authentication watchdog. Optional local password storage obtains new tokens automatically when a refresh token is rejected. Includes a storage disclaimer and pauses for rejected passwords, CAPTCHA, MFA, or account blocking. The new authentication behavior still requires live hub validation.
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
