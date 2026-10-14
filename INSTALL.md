# Installation and account setup

## Requirements

- A Hubitat Elevation hub with current supported firmware and access to Apps Code and Drivers Code.
- Thermostats present in your **First Alert** account.
- Outbound HTTPS/WSS on port 443 to `login.resideo.com`, `api.ha.resideo.com`, and the negotiated `*.service.signalr.net` host.
- A computer browser for browser-assisted login if direct sign-in is blocked.

No inbound port, Hubitat OAuth callback, developer.honeywellhome.com account, bridge service, or hosted endpoint is needed. Firmware 2.2.9 introduced the serialized-execution and redirect-control features used here, but this candidate does not yet have a tested minimum firmware or hardware matrix. Validate on an up-to-date hub first.

## Add code

1. Open the hub's local administration interface.
2. Under **Developer Tools → Drivers Code**, choose **New Driver**, paste `drivers/resideo-connection.groovy`, and save.
3. Repeat for `drivers/resideo-thermostat.groovy` and `drivers/resideo-room-sensor.groovy`.
4. Under **Apps Code**, choose **New App**, paste `apps/resideo-integration.groovy`, and save.
5. Open **Apps → Add User App → Resideo Integration**.

Do not create virtual thermostat or sensor devices manually. The app provisions and identifies them. Do not enable OAuth for this app; browser login uses the source client's registered redirect URI and a manual paste step.

## Sign in with email/password

1. Enter the email and password you use in First Alert.
2. Select **Sign in with email and password**.
3. Wait for the account status to change, then refresh/reopen the app page if needed.
4. If CAPTCHA, MFA, or another automated-login restriction is reported, use browser sign-in below.

The password is used only by Resideo's sign-in flow and is cleared after completion or cancellation. The hub retains the resulting access and refresh tokens. Hub administrators and backups can access app state; share the built-in redacted diagnostics rather than raw app state or login redirects.

## Browser-assisted sign-in

1. Select **Start browser sign-in**, then open **Open Resideo sign-in** on a computer.
2. Before completing login, open browser Developer Tools → **Network**. Enable **Preserve log** if the browser offers it.
3. Sign in on Resideo's website, completing any CAPTCHA or additional verification there.
4. The final redirect targets `com.resideo.firstalert://…`. Cancel any prompt to launch an app.
5. In the Network panel, locate the final redirect response from Resideo and copy its full **Location** response header. It contains both `code` and `state` query parameters.
6. Paste that complete URL into **Complete redirect URL** in the Hubitat app and select **Complete browser sign-in**.

If the custom URL is visible in the browser, copying it directly also works. The code is short-lived and belongs to the specific attempt. The app rejects a missing or mismatched state and expires unfinished attempts after 15 minutes. Start again if necessary. Do not share the redirect or save it in diagnostics.

## Select devices

1. Select **Discover / refresh account** if discovery has not finished.
2. Select the desired thermostats and choose **Apply device selection**.
3. The app creates thermostat children, supported room-sensor children, and one connection device per selected location.
4. Leave fallback polling at five minutes initially. Select **Done** to save the app.
5. Open a thermostat's device page and confirm temperature, setpoints, supported modes, `dataStatus`, and `connectionStatus`.

`connectionStatus=connected` means subscription, feed activation, and REST bootstrap completed. `polling` means the thermostat can still update through REST while the stream reconnects. Missing optional readings remain unset.

## First functional checks

Confirm read-only state against First Alert before issuing commands. Then perform deliberate, reversible changes appropriate for your equipment, verify the wall thermostat and First Alert, and restore the original settings. A command can affect running HVAC equipment.

Test one normal setpoint, fan mode, and supported operating mode. Add the child thermostat to a standard dashboard thermostat tile and use its normal commands in Rule Machine. Advanced operations are Rule Machine **Custom Action** commands; see [command reference](docs/commands.md).

## Updates, removal, and rollback

Update all four code files together, then open the app and choose **Done** to reinitialize. Existing device identifiers and user-edited labels are retained. Do not uninstall/reinstall just to update code.

Disable **Enable integration** to stop scheduled work, close streams, and block new integration commands. Already transmitted cloud commands cannot be recalled. Disappeared or deselected devices remain until you explicitly remove them from the app; check their automation references first.

Uninstalling the app removes its child devices through Hubitat's app lifecycle. Retain a hub backup if you need to recover device references. Restore the previous four source files and save the app to roll back a code update.

## Optional logging

In the app’s **Operation** section, status/warning logs default to enabled and debug logs default to disabled. Enable **Debug logging for 30 minutes** and save the app when reproducing a problem. The app automatically ends the capture; repeat saves or reconnects do not extend it. Driver device pages provide separate optional 30-minute debug captures for local forwarding or socket issues. See [troubleshooting](docs/troubleshooting.md#diagnostics) for what is recorded and how to export the bounded, redacted diagnostic history.
