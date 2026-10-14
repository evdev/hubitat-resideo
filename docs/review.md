# Follow-up bug review — 2026-10-14

The initial 41-group suite passed, but it did not exercise several real failure paths. The review found and fixed the following issues and expanded verification to **55 passing test groups** under Groovy 2.4.21.

| Issue | Correction |
|---|---|
| Undocumented SecureRandom import could prevent hub compilation | Generate PKCE verifier/state through the documented UUID class; use allowlisted Exception for validation; check deployment imports against a saved official contract |
| Rotating tokens were only saved when the callback returned | Persist refresh intent before transmission and rotated credentials through atomicState before releasing waiting requests |
| Initialization removed the proactive refresh timer | Restore the timer when retained credentials are loaded |
| Delayed REST results could overwrite pushed readings or newer configuration | Track cache revisions per endpoint and reject/refetch superseded responses |
| Bootstrap could be satisfied by a request from before subscription | Bind bootstrap reads to the current connection generation |
| Devices could keep showing polling after push became ready | Publish readiness immediately, including other devices at the location |
| Adding a thermostat to an already connected location did not renew activation | Restart negotiation/activation when the location's selected device set changes |
| Reconnect left the replaced WebSocket and old timers active | Close the old socket, cancel its timers, and ignore close notifications during negotiation |
| A timed-out or server-error write could be followed by a stale complete-body write | Pause conflicting writes until a settling read resolves the uncertain result; treat server errors as potentially ambiguous |
| Commands on other thermostats could repeatedly postpone confirmation | Schedule the earliest outstanding settling deadline; retire disappeared normal fields after a fresh owning-endpoint read |
| Heating-only and cooling-only systems required a nonexistent counterpart setpoint | Apply deadband validation when both functions are supported and reject only unsupported setpoint operations |
| Component child devices prevented user label editing | Create thermostat and room-sensor children as editable devices; keep connection children internal |
| Invalid readings or optional response containers could interrupt publication | Omit malformed numeric readings and retain existing cache on invalid endpoint structures |
| A full request queue did not report a rejected command to its device | Publish a clear command rejection when enqueueing cannot succeed |

Compatibility checks used Hubitat's [allowed imports](https://docs2.hubitat.com/en/developer/allowed-imports), [InstalledApp methods](https://docs2.hubitat.com/en/developer/app/installedapp-object), [device methods](https://docs2.hubitat.com/en/developer/device-object), and [WebSocket interface](https://docs2.hubitat.com/en/developer/interfaces/websocket-interface). The imported-class snapshot is in `tests/hubitat-contract.json`.

The tests include deterministic HTTP response reordering, malformed payloads, simulated persistence ordering, multi-device command timing, and a simulated 24-hour run. They do not establish physical-hub or live-cloud compatibility. [Live acceptance checks](validation.md) remain deferred until browser access is provided.

## Optional diagnostic logging

A subsequent logging pass expanded the suite to **67 passing test groups**. Status/warning logs are optional, detailed app and driver logs default to off, and debug captures expire after 30 minutes without being extended by reinitialization or reconnects. Debug output is capped at 120 entries per minute per script; repetitive app outage messages are throttled.

Authentication steps, token refresh results, HTTP timing/status/retries, queue sizes, SignalR stages and safe failure reasons, polling/availability changes, and accepted/confirmed/failed/unconfirmed commands now have diagnostic events. Debug command comparisons retain only validated settings and hub temperature units. Exports include a 60-event history, counters, shared cooldown, stream status, and pending/deferred command counts. History and counters can be cleared independently of integration state.

Tests inject credentials, cookies, authorization codes, token-bearing URLs, raw service errors, device identifiers/labels, and invalid command values to check their exclusion. Logging uses fixed event/field vocabularies and pseudonymous device/location references. Physical-hub logging behavior remains part of the pending live checks.

## HPM packaging and physical-hub installation

Added a package manifest for the primary app and three required drivers, plus an optional custom repository listing. Component IDs are stable, OAuth is disabled, and source URLs are pinned to the version tag. The HPM contract check expanded the suite to **68 passing groups**. On 2026-10-14, HPM v1.9.13 successfully installed v0.1.0 on the user-provided hub and recorded all four compiled components. The account setup page opened; account and hardware acceptance checks remain in progress.

## Authentication repair — v0.1.1

The hub logs revealed `java.util.ArrayList.values()` at cookie collection immediately after a successful authorization redirect. The cookie parser depended on the returned type of `headers.findAll`, which did not match the workstation stub. Header normalization and direct entry/value iteration now remove that dependency, and cookie parsing sits inside the authentication failure handler. Error response bodies also fall back to Hubitat's `getErrorData` accessor so CAPTCHA/MFA/credential errors remain distinguishable.

Browser sign-in now uses the corresponding registered HTTPS callback. A public authorization request confirmed that Resideo accepts this callback and redirects to sign-in; the callback page itself returns the expected 404 for dummy values. Users can copy the final address directly from the address bar instead of using Developer Tools. Authorization and exchange retain the same callback URI, state and PKCE verifier. Mistaken pastes preserve the current attempt, and saving preferences no longer destroys an active attempt or invalidates in-flight password-login callbacks.

Resideo's public client rejected the OAuth device-code grant, so the implementation does not offer that unsupported flow. A password-free connection check exercises the redirect and login-page cookies on the hub. The expanded suite has **77 passing groups** under Groovy 2.4.21; live account acceptance still requires a fresh user sign-in.

## Redirect handling — v0.1.2

The first physical-hub connection check passed authorization and cookie decoding but received HTTP 302 on the login page. The pinned upstream follows login-page redirects; the port had disabled redirect following without reproducing that part of the flow. Login-page redirects are now followed explicitly, preserving received cookies and the final server state, with a five-hop limit and strict HTTPS/authentication-host checks. Safe diagnostics report only fixed route categories and session/CSRF presence. Two additional regression groups bring verification to **79 passing groups**.

## Complete cookie transport — v0.1.3

An anonymous reproduction showed that retaining only the last repeated Set-Cookie value reaches the login page without the CSRF cookie. A one-time native-header fallback now retains all cookies during the setup steps that require them. Native requests are bounded to ten seconds and individually scheduled; credential submission, token exchange/rotation and device API work remain asynchronous. Temporary wire data is excluded from scheduled job arguments and diagnostic exports and is removed on cancellation/completion. Expired browser results now have their own message. The suite has **84 passing groups**. The user's remote-admin session expired; installation and end-to-end credential verification of this follow-up remain pending reconnection.
