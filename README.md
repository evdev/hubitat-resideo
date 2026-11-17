# Resideo Thermostats Integration

Connect the thermostats and room sensors in your **First Alert** account to Hubitat. The integration runs on your hub and connects to Resideo's cloud for readings and thermostat controls.

**Version [0.1.12](https://github.com/evdev/hubitat-resideo/releases/tag/v0.1.12)** adds automatic authentication recovery and optional password storage.

## Installation

In **Hubitat Package Manager → Install → Browse by Tags → Climate Control**, select **Resideo Thermostats Integration** by **Ephrayim (evdev)**. It's included in the standard HPM catalog.

HPM installs the app and its three drivers. Open the integration, sign in with your First Alert account, and choose your thermostats. Room sensors are added automatically.

For an existing installation, use **HPM → Update**. If you installed the code manually, use **Match Up** first so HPM can manage it.

See the [installation guide](INSTALL.md) for setup details and manual installation.

## What you can do

- Manage multiple thermostats and locations from one First Alert account.
- Change heating and cooling setpoints, system modes, fan settings, and supported holds.
- Enable or disable the thermostat's existing schedule and adjust settings such as Feels Like, Adaptive Recovery, setpoint limits, and freeze protection where supported.
- Read room-sensor temperature, humidity, motion, occupancy, air quality, and battery status where available.
- Receive live updates, with polling as a fallback when the live connection drops.
- See connection health, stale readings, faults, and whether a command was confirmed by the cloud.

Available controls and readings depend on your thermostat and sensors. Advanced settings can be used through Rule Machine custom actions. See the [command guide](docs/commands.md) for details.

## Staying signed in

The app saves refresh tokens so it can reconnect after a hub restart or an outage. Temporary connection failures retry automatically.

For password-based recovery, enable **Save password on this hub for automatic sign-in**, enter your email and password, and sign in successfully once. The app can then sign in again if Resideo rejects the saved refresh token.

Your saved password is stored in readable form on the hub and can be accessed by hub administrators or through backups. Keep your hub, local network, and backups secure. Turning the option off deletes the saved credentials from the app; older backups may still contain them.

CAPTCHA, MFA, a rejected password, or an account block may still need your attention. Browser sign-in is available when verification is required. A successful browser sign-in clears any previously saved password, since it may connect a different account.

## Compatibility and troubleshooting

This integration uses the unofficial API behind First Alert. It requires internet access, and changes to Resideo's service may require an integration update. Devices on older Resideo/Lyric or Total Connect Comfort services use different APIs.

Use First Alert to edit schedules or create vacation and timed holds. The integration can report those holds but cannot start them.

If you're having trouble signing in, connecting, or sending commands, see the [troubleshooting guide](docs/troubleshooting.md).

## Credits

Developed and maintained by [evdev](https://github.com/evdev) (Ephrayim).

Adapted from [sfcodes/ha-resideo](https://github.com/sfcodes/ha-resideo), copyright © 2026 Semyon Fishman, under the MIT license. See [LICENSE.upstream](LICENSE.upstream) and [LICENSE](LICENSE).
