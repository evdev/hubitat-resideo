# Installation and account setup

## Requirements

- A Hubitat Elevation hub with current supported firmware and access to Apps Code and Drivers Code.
- Thermostats present in your **First Alert** account.
- Outbound HTTPS/WSS on port 443 to `login.resideo.com`, `api.ha.resideo.com`, and the negotiated `*.service.signalr.net` host.
- A computer browser for browser-assisted login if direct sign-in is blocked.

No inbound port, Hubitat OAuth callback, developer.honeywellhome.com account, bridge service, or hosted endpoint is needed. Firmware 2.2.9 introduced the serialized-execution and redirect-control features used here, but this candidate does not yet have a tested minimum firmware or hardware matrix. Validate on an up-to-date hub first.

## Install with Hubitat Package Manager (recommended)

The package includes the integration app and all three required drivers. HPM downloads versioned source files so all four components are installed from the same revision.

1. Open **Hubitat Package Manager → Install → From a URL**.
2. Enter `https://raw.githubusercontent.com/evdev/hubitat-resideo/main/packageManifest.json` and choose **Next**.
3. Review the package and MIT license, leave **Configure the installed package after installation completes** enabled, and finish installation.
4. HPM opens **Resideo Integration**. Authenticate using one of the methods below, then select your thermostats.

To make the package available when browsing custom repositories, use **HPM → Package Manager Settings → Add a Custom Repository** with `https://raw.githubusercontent.com/evdev/hubitat-resideo/main/repository.json`. Listing in the community's global catalog is a separate step and is not required for URL installation.

These URLs must be publicly accessible to the hub. A private GitHub repository is not downloadable by HPM's normal URL installer. No GitHub token is embedded in the package URLs.

For HPM-managed installations, update or repair through HPM and then open the integration and select **Done**. Do not manually replace or remove HPM-managed code.

## Add code manually

1. Open the hub's local administration interface.
2. Under **Developer Tools → Drivers Code**, choose **New Driver**, paste `drivers/resideo-connection.groovy`, and save.
3. Repeat for `drivers/resideo-thermostat.groovy` and `drivers/resideo-room-sensor.groovy`.
4. Under **Apps Code**, choose **New App**, paste `apps/resideo-integration.groovy`, and save.
5. Open **Apps → Add User App → Resideo Integration**.

Do not create virtual thermostat or sensor devices manually. The app provisions and identifies them. Do not enable OAuth for this app; browser login uses the source client's registered redirect URI and a manual paste step.

## Sign in with email/password

1. Enter the email and password you use in First Alert.
2. Select **Sign in with email and password**.
3. Wait for the highlighted status beside the button. Green indicates success; red explains the next action. The same status appears at the top and beside the connection check.
4. If CAPTCHA, MFA, or another automated-login restriction is reported, use browser sign-in below.

The password is used only by Resideo's sign-in flow and is cleared after completion or cancellation. The hub retains the resulting access and refresh tokens. Hub administrators and backups can access app state; share the built-in redacted diagnostics rather than raw app state or login redirects.

## Browser-assisted sign-in

1. Choose **Start browser sign-in** in the integration, then **Open First Alert sign-in**. Keep the Hubitat page open.
2. Sign in on Resideo's website, completing any verification it requests.
3. After sign-in, the browser reaches a blank or error page at `https://login.resideo.com/ios/com.resideo.firstalert/callback`. This is expected. Copy the **entire address from the browser's address bar** (Ctrl+L then Ctrl+C on Windows/Linux, or Cmd+L then Cmd+C on macOS).
4. Return to Hubitat, paste it into **3. Paste the final web address here**, and choose **Connect my account**.

No Developer Tools, Network panel, phone-app launch, hosted callback, or browser extension is required. If you still see the sign-in form, finish signing in before copying the address. The copied address is a private, short-lived sign-in result; do not share it or add it to diagnostics.

Use the link from the current attempt and finish within 15 minutes. Saving the app preserves an active attempt. A mistaken paste can be corrected without restarting; an expired attempt needs a new sign-in link. The integration validates the result's state and exact callback address, then exchanges it on the hub using the same callback and PKCE verifier.

## Check the sign-in connection

In **Operation**, choose **Check First Alert sign-in connection (no password needed)**. This checks the authorization redirect and login-page cookies from the hub without submitting account credentials. If the hub's asynchronous response omits duplicate cookies, the integration retries this setup check once using bounded native HTTP so every cookie can be retained. Success means the hub can reach and parse the sign-in service; it does not verify your password or account migration. Enable optional debug logging to capture the steps and safe cookie-count/CSRF-presence diagnostics.

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
