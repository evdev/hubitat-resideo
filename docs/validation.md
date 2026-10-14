# Validation record and live acceptance checklist

## Automated verification

Verified on **2026-10-14**, including the follow-up bug review and optional logging checks: all **88 test groups passed** with Groovy 2.4.21 on Java 26.0.1. Command: `RESIDEO_GROOVY_JAR=/private/tmp/resideo-groovy-all-2.4.21.jar sh tests/run.sh`. The runtime SHA-256 is `de65260cf2070442e99882f2f3d72e7531725c1e6a257446cc0cea525c607bd0`; the runner verifies it before executing.

The test runner uses Groovy **2.4.21**, a Hubitat DSL/method stub, a deterministic scheduler, fake asynchronous HTTP, fake WebSocket transport, and the upstream sanitized fixtures. No account credentials or live thermostat are used.

Coverage includes:

- Compilation of the app and all three drivers; serialized execution metadata.
- HPM manifest contract: all four required components, primary app, OAuth disabled, stable unique IDs, matching names/namespaces, pinned source version and repository URL.
- Password and browser authentication, PKCE state/challenge, cookie/form/redirect handling, CAPTCHA/MFA messages, token rotation, and ambiguous refresh timeout.
- Hubitat header-wrapper regression, folded/array cookies, failed-response body access, cookie-error cleanup, password-free connection check, HTTPS callback consistency, corrected browser pastes, preserving attempts across saves, expiry, and guided setup refresh behavior, bounded login-page redirects, redirect-cookie retention and foreign-host/loop rejection, complete-header native fallback, callback-cookie rotation, fallback cancellation, empty primary error bodies and step-specific expired-result messages.
- Discovery, renamed labels, stable sensor identity, orphan retention, and optional endpoint failures.
- All 79 pinned upstream platform entities, driver declarations, sparse hardware, and complete representative projections.
- Unit conversion, outdoor country units, supported modes, holds, bounds, deadband, complete advanced-setting bodies, and pending full-body overrides.
- Request coalescing/concurrency, serialized writes, 401 handling, Retry-After, uncertain writes, stale callbacks, and endpoint-specific reconciliation.
- SignalR handshake/subscription completion, partial/coalesced frames, event decoding, rejected subscriptions, stalls, renewal, and stale feed-expiration timestamps.
- Delta preservation and the off-by-one sensor workaround.
- Polling transitions, offline/stale distinction, reboot recovery, delayed sensitivity, and disabled integration.
- Diagnostic whitelisting and absence of credentials, cookies, authorization codes, identifiers, and raw server strings in logs/exports.
- Optional status/driver logs, correlated request timing, safe command-value comparison, actionable failure categories, bounded history/counters, rate limiting, capture clearing, and automatic debug expiry across initialization and updates.
- Documented import allowlist, durable refresh intent/token rotation, restored refresh timers, and editable child labels.
- Delayed REST responses versus pushed values and newer REST results, connection-specific bootstrap, immediate readiness publication, and renewed activation after device-selection changes.
- Uncertain write barriers, settling deadlines across multiple devices, disappeared normal fields, single-function HVAC equipment, and malformed numeric/optional payloads.
- Closing replaced WebSocket connections and ignoring old close notifications during negotiation.
- A **simulated 24-hour run** crossing repeated token rotations, 180 feed renewal cycles, and a simulated hub restart, with bounded requests and no duplicate devices.

The simulation is not a live 24-hour soak and the fake runtime does not enforce every Hubitat sandbox rule. See the final test output for the current number of passing test groups.

## Live installation and remaining checks

On **2026-10-14**, browser access was provided and **Hubitat Package Manager v1.9.13** installed package **v0.1.0**. HPM's managed inventory confirmed the integration app and all three drivers, and the integration's account setup page opened successfully. The repository was made public with explicit user approval so HPM could download its manifest and versioned source URLs. Each downloaded file was checked against the tested local source before installation.

The user is completing First Alert authentication. No credentials, screenshots, hub addresses, or device state are included in this repository. Hub firmware is **2.5.2.134**. Hub model and thermostat/sensor models and firmware remain to be recorded for hardware validation:

| Check | Status |
|---|---|
| Compile all four scripts on the physical hub | Passed through HPM v1.9.13, 2026-10-14 |
| HPM manages the app and all three drivers at v0.1.0 | Passed, 2026-10-14 |
| Integration account setup page opens | Passed, 2026-10-14 |
| v0.1.4 app compilation, highlighted status and password-free sign-in connection check | Passed on firmware 2.5.2.134: all five cookies and CSRF present |
| Direct email/password sign-in | Pending |
| Browser-assisted sign-in and token persistence | Pending |
| Thermostat and room-sensor discovery / capability matching | Pending |
| Standard dashboard thermostat controls | Pending |
| Rule Machine standard and custom commands | Pending |
| Changes from First Alert and the wall thermostat reach Hubitat | Pending |
| Accepted commands match actual device state | Pending |
| Stream loss → polling → restored push | Pending |
| Hub restart restores operation without sign-in | Pending |
| Live 24-hour token/feed renewal soak | Pending |
| Live diagnostic/log inspection for secrets | Pending |

## Procedure

1. Record the hub model/firmware, thermostat model/firmware, and sensor models. Compile and install according to [INSTALL.md](../INSTALL.md).
2. Check read-only values against First Alert. Confirm unavailable optional attributes remain unset and room readings are assigned correctly.
3. Exercise appropriate reversible standard and advanced commands, check reported confirmation, and restore original settings. Confirm schedule toggles and hold behavior explicitly.
4. Add the thermostat to a dashboard and use both normal thermostat actions and selected custom actions in Rule Machine.
5. Temporarily interrupt outbound SignalR access while retaining REST access. Confirm polling continues and a restored stream resynchronizes state before returning to push.
6. Restart the hub. Confirm tokens and delayed sensitivity settings survive and device identifiers/labels remain unchanged.
7. Observe for at least 24 real hours. Record feed renewals, token refreshes, request volume, runtime statistics, sensor identity, and confirmation behavior.
8. Inspect the built-in redacted diagnostic export and bounded debug logs. Report unexpected errors without raw app state, login redirects, or unredacted network captures.

Do not mark a hardware/firmware combination validated until these checks have been observed on that combination. Do not infer broader model support from fixture tests.

Live troubleshooting on firmware 2.5.2.134 reproduced two additional Hubitat behaviors: login buttons execute before submitted inputs are saved, and HTTP header values can include the header name or collapse repeated cookies. Version 0.1.4 saves inputs before scheduled sign-in actions, normalizes header values, and requests all native response headers. Highlighted status banners appear beside each login action and the connection check. The corrected native-header fallback passed the physical-hub connection check with all five cookies and CSRF present. Fresh credential acceptance remains pending; no live account success is inferred from the offline suite.
