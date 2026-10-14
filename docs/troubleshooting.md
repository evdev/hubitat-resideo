# Troubleshooting

## Sign-in

- **Credentials rejected:** verify that the account and thermostat are present in First Alert, rather than the older Resideo or Total Connect Comfort app.
- **CAPTCHA / MFA / blocked automated login:** select browser sign-in and complete verification on Resideo's website.
- **Invalid browser redirect:** paste the full URL including both `code` and `state` from the current attempt. Restart if expired. Do not reuse a redirect from another attempt.
- **Reauthentication required:** sign in again. This includes token revocation, a password change, and an ambiguous refresh timeout. The integration avoids repeatedly reusing a possibly rotated token.
- **No thermostats discovered:** the account endpoint may contain other product classes. This integration selects thermostat identities only.

## Stream and polling

`polling` is a supported recovery mode. Check outbound WSS access to the negotiated Azure SignalR host. Negotiation, handshake, subscription, and priority activation all must succeed before live mode becomes ready. Missing optional room or configuration endpoints do not discard usable thermostat readings.

The feed has a limited lifetime. Regular connection renewal is expected; it includes a new negotiation and a REST resync. During an outage, reconnect delays grow to 60 seconds. Shared API cooldowns can delay retries further.

HTTP 429 uses the cloud's Retry-After header. Transport failures and server errors back off from 30 seconds to 15 minutes. Lowering the fallback interval does not override those cooldowns.

`stale` means no successful thermostat state update for the greater of 15 minutes or three fallback intervals. `offline` reflects the thermostat's explicit online=false state. `unavailable` indicates disablement, lost authentication, deselection, or disappearance. Last-known readings may remain visible, so consult `dataStatus` before using them in an automation.

## Commands

- **Rejected:** inspect `lastCommandError`; the device may lack that mode/control, be offline, have stale state, or violate temperature limits/deadband.
- **Unconfirmed:** a settling-period read did not match the requested normal value. The visible value has returned to the cloud's reported state.
- **Pending sensitivity:** the wireless sensor may not have checked in. This is intentionally not rolled back after ten seconds.
- **Uncertain:** the write timed out. The integration reads state before deciding what happened and does not resend it automatically.
- **Temporary hold unavailable:** enable the thermostat's schedule before using temporary hold or resumeSchedule.

To change settings unsupported by the upstream integration, use First Alert. Do not probe unknown command paths with empty bodies; the cloud can apply unintended defaults.

## Diagnostics

Select **Prepare redacted diagnostics** in the app. Copy that output together with the error description, hub firmware, thermostat model, integration version, and which action failed.

The app's **Operation** section provides two logging settings:

| Setting | Default | What it records |
|---|---|---|
| **Status and warning logs** | On | Authentication outcomes, token refresh completion, discovery, device health, stream readiness, cloud failures, rate limits, and command results |
| **Debug logging for 30 minutes** | Off | Authentication steps, correlated HTTP request/response numbers, HTTP status, elapsed milliseconds, queue size, retry delays, stream stages, feed renewal, mapped push properties, polling, and command acceptance/reconciliation |

To investigate a problem, enable both settings and save the app, open Hubitat **Logs**, and reproduce the action. Then select **Prepare redacted diagnostics** and copy its output along with the relevant log entries and the device's `lastCommandError` if applicable. In debug mode, command comparison entries include validated requested and reported settings in the hub's temperature units; raw command arguments and request bodies are excluded. A request number connects its dispatch, response, and retry entries. Stream failures include a fixed reason code, connection generation, failure stage, and retry delay. Examples include `subscriptionRejected`, `stalled`, and `negotiationFailed`.

Each of the three drivers also has **Driver debug logging for 30 minutes** on its device page. Enable this only when tracing device command forwarding, attribute delivery, or the socket driver's local execution. The app normally supplies the necessary diagnostics. Driver debug preferences operate independently of the app's logging settings.

Debug logging turns itself off after 30 minutes. Saving settings, reconnecting, or restarting the integration does not extend an existing capture. Each app/driver limits debug output to 120 entries per minute and emits one suppression notice when that limit is reached. Repeated identical status/warning entries in the app are suppressed for one minute.

The export retains up to 60 recent sanitized events, counters since installation or the last clearing, cooldown status, location stream status, and pending/deferred command counts. Significant events are retained even with status logs disabled; detailed events require debug mode. Counters include suppressed activity, so they can reveal a recurring failure after debug mode expires. **Clear diagnostic history and counters** removes the saved capture without altering devices, credentials, or pending commands.

Logs and exports use stable pseudonymous device/location references. They exclude credentials, email addresses, cookies, authorization codes, raw identifiers, device labels, full API URLs, raw payloads, and WebSocket status strings. Raw app state, browser redirects, hub backups, and developer-tool network captures can contain account credentials; they are not the diagnostic export.

If a physical hub rejects compilation or a sandbox method, retain the exact compile/runtime error and firmware version. Groovy 2.4 compilation under the test harness checks language compatibility, not every Hubitat sandbox restriction.
