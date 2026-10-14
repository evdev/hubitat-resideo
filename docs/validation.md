# Validation record and live acceptance checklist

## Automated verification

Verified on **2026-10-14**, including the live-load regression checks: all **104 test groups passed** with Groovy 2.4.21 on Java 26.0.1. Command: `RESIDEO_GROOVY_JAR=/private/tmp/resideo-groovy-all-2.4.21.jar sh tests/run.sh`. The runtime SHA-256 is `de65260cf2070442e99882f2f3d72e7531725c1e6a257446cc0cea525c607bd0`; the runner verifies it before executing.

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

Browser-assisted First Alert authentication succeeded with v0.1.5. Token exchange and account discovery returned HTTP 200; saving/reinitializing and reopening the app retained authentication and a fresh account read succeeded. No credentials, screenshots, hub addresses, or device state are included in this repository. Hub firmware is **2.5.2.134**. Hub model and thermostat/sensor models and firmware remain to be recorded for hardware validation:

| Check | Status |
|---|---|
| Compile all four scripts on the physical hub | Passed through HPM v1.9.13, 2026-10-14 |
| HPM manages the app and all three drivers at v0.1.11 | Passed: HPM v1.9.14 update completion and managed inventory on the repaired physical hub, 2026-10-14 |
| Integration account setup page opens | Passed, 2026-10-14 |
| v0.1.4 app compilation, highlighted status and password-free sign-in connection check | Passed on firmware 2.5.2.134: all five cookies and CSRF present |
| Direct email/password sign-in | Passed on an additional physical hub: credentials accepted, token exchange HTTP 200 and 21 thermostats discovered; about 6.5 seconds, 2026-10-14 |
| Browser-assisted sign-in and token persistence | Passed: token exchange, save/reopen and automatic rotated token refresh, 2026-10-14 |
| Account-level thermostat discovery | Passed: authenticated accounts API returned thermostats |
| Thermostat and room-sensor discovery / capability matching | 21 selected thermostat children; shadow/configuration/priority reads succeeded; rooms unsupported (404); read-only heating-only capability observed |
| Standard dashboard thermostat controls | Pending |
| Rule Machine standard and custom commands | Pending |
| Changes from First Alert and the wall thermostat reach Hubitat | Live temperature stream notifications observed; explicit app/wall control convergence remains pending |
| Accepted commands match actual device state | v0.1.11 setpoint and mode writes confirmed against fresh cloud-reported state; independent app/wall/equipment comparison remains pending |
| Stream loss → polling → restored push | Polling during partial stream setup and transition to ready push verified; deliberate network interruption remains pending |
| Hub restart restores operation without sign-in | Pending |
| Live 24-hour token/feed renewal soak | Pending |
| Live diagnostic/log inspection for secrets | Passed for inspected authentication, device reads, token refresh, stream status and temperature notifications; no credentials or raw network URLs observed |

## Procedure

1. Record the hub model/firmware, thermostat model/firmware, and sensor models. Compile and install according to [INSTALL.md](../INSTALL.md).
2. Check read-only values against First Alert. Confirm unavailable optional attributes remain unset and room readings are assigned correctly.
3. Exercise appropriate reversible standard and advanced commands, check reported confirmation, and restore original settings. Confirm schedule toggles and hold behavior explicitly.
4. Add the thermostat to a dashboard and use both normal thermostat actions and selected custom actions in Rule Machine.
5. Temporarily interrupt outbound SignalR access while retaining REST access. Confirm polling continues and a restored stream resynchronizes state before returning to push.
6. Restart the hub. Confirm tokens and delayed sensitivity settings survive and device identifiers/labels remain unchanged.
7. Observe for at least 24 real hours. Record feed renewals, token refreshes, request volume, runtime statistics, sensor identity, and confirmation behavior.
8. Inspect the bounded, sanitized Hubitat debug logs. Report unexpected errors without raw app state, login redirects, or unredacted network captures.

Do not mark a hardware/firmware combination validated until these checks have been observed on that combination. Do not infer broader model support from fixture tests.

Live troubleshooting on firmware 2.5.2.134 reproduced two additional Hubitat behaviors: login buttons execute before submitted inputs are saved, and HTTP header values can include the header name or collapse repeated cookies. Version 0.1.4 saves inputs before scheduled sign-in actions, normalizes header values, and requests all native response headers. Highlighted status banners appear beside each login action and the connection check. The corrected native-header fallback passed the physical-hub connection check with all five cookies and CSRF present. Browser credential acceptance was subsequently verified with the v0.1.5 live token exchange; direct password sign-in was subsequently confirmed in the v0.1.8 live test described above.

Two browser handoffs exceeded the original 15-minute setup-attempt lifetime. Their callbacks were correctly rejected as expired; no token exchange occurred. Version 0.1.5 extends only the pending browser setup request to one hour and displays its deadline. Fresh-login parameters and Auth0’s authorization-code lifetime are unchanged. A subsequent fresh callback completed token exchange and authenticated account discovery successfully with v0.1.5.

Live device initialization with v0.1.5 created the selected thermostat children and populated read-only state, configuration and priority. Room endpoints returned 404 and were marked unsupported. It also exposed circular parent/driver single-threaded gate waits. Version 0.1.6 replaces synchronous driver callbacks with native child-ID events and private outboxes; physical-hub verification confirmed stream events and automatic token refresh without new gate errors. The stream remained partly initialized because duplicate priority/activation requests filled the 100-request queue during the 21-device bootstrap. Version 0.1.8 shares these reads; physical-hub verification then confirmed all 21 feeds activated, all 21 thermostats resynchronized, a ready stream, an empty request queue, and current data with push as its update source. New live temperature notifications were observed, with no new queue-full or gate-acquisition errors during that startup. No heating/cooling setting was changed during these checks.

Version 0.1.8 was installed through HPM on the physical hub. The account remained connected across both code updates and reinitialization. All 21 existing thermostat children retained their identities; the total child-creation counter stayed at 22 (21 thermostats and one connection). Redacted diagnostics reported 21 selected, 21 activated, 21 resynchronized, zero queued/active requests, and current/push data for every thermostat. The live log recorded streamReady with source push and incoming temperature deltas. Status logging was enabled and the diagnostic debug window remained bounded to 30 minutes. No HVAC control writes were performed.

The v0.1.9 sign-in progress update was installed through HPM on the additional password-test hub. Its new wait instructions appeared next to the password button, the connected account survived saving/reinitialization, and a fresh authenticated accounts response returned HTTP 200 with 21 thermostats. Progress phase sequencing and automatic-refresh transitions passed the Groovy 2.4 suite; no additional password attempt was required to verify token persistence.

Version 0.1.10 was installed through HPM on the original physical hub. The same installed instance appeared under Integrations as Resideo Thermostats Integration. Setup retained a connected account and 21 selected thermostats, with both diagnostic buttons and the prepared report absent. Status/debug logging controls remained available. No child devices were removed for this UI update.

Live inspection of v0.1.10 found repeated Hubitat excessive-load exceptions during child snapshot publication and negotiation setup timeouts. Publication could abort a drained bridge batch before its negotiation packet executed, and reconnect statuses initiated additional account-wide reads. Version 0.1.11 defers native attribute events and connection startup/socket operations to driver callbacks, coalesces snapshots, caches emitted attribute values, isolates bridge packets, and retains the configured fallback polling cadence during reconnect failures. Regression coverage includes 21-device failure loops, unchanged maintenance, stale-state transitions, packet survival after a publication failure, and driver event coalescing. The corrected app and all three drivers were compiled and saved directly in the existing physical hub code entries, with editor contents checked against the tested local files. Authentication and all 21 thermostat child identities were retained. The connection completed negotiation, handshake, subscription, activation and REST resynchronization, followed by connected/current/push health for all 21 devices. The first 286 captured post-repair log records contained no errors or warnings. A one-degree heating setpoint change and restoration, followed by Heat and Off mode changes on a heating-only thermostat, each reached confirmed state. The original settings were restored and reported equipment state remained idle. The next automatic feed renewal completed negotiation and returned to ready push with all 21 thermostats current. In a 125-second runtime sample, total local-app busy time increased by 15 seconds; cumulative historical percentages still include the earlier failure. No physical heating-output test, dashboard test, or Rule Machine test is claimed. The v0.1.11 GitHub release and HPM manifest were published with explicit approval. The published manifest and four source files matched the tested local bytes. HPM v1.9.14 subsequently downloaded and upgraded all four components; its managed inventory confirmed v0.1.11 for the app and every driver. The final 500-record log capture through HPM update completion contained no errors or warnings, and fresh thermostat state remained connected/current/push with the original settings restored.
