# Architecture and maintenance

## Deployment

The deployment unit is one app and three standalone driver scripts. Each is compatible with Groovy 2.4 syntax and defines no custom classes or runtime library dependencies. The app owns HTTP requests and tokens; connection drivers use Hubitat's WebSocket interface. Thermostat and sensor drivers are thin command/event adapters.

All components declare `singleThreaded: true`. Drivers emit native location events containing only their child ID. Message envelopes remain in a bounded private driver outbox until the app's event handler drains them. Drivers never synchronously call their single-threaded parent, avoiding circular parent/driver gate acquisition. Shared state changes occur inside the app's serialized handlers. HTTP callbacks join the same processing path. Account generation identifiers reject previous-session responses; connection generation identifiers reject obsolete negotiation and stream messages.

## HPM catalog and release publishing

The approved HPM author entry is `evdev` in [HubitatCommunity's repository list](https://github.com/HubitatCommunity/hubitat-packagerepositories/blob/master/repositories.json). It points to [hubitat-modern-dashboard/hubitat/repository.json](https://github.com/evdev/hubitat-modern-dashboard/blob/master/hubitat/repository.json), which lists all evdev packages. New integrations must be added to that existing catalog. A standalone `repository.json` in a new project does not register it with the standard HPM catalog, and no additional global-catalog approval is needed when adding a package to the already registered author catalog.

Resideo's stable catalog ID is `e8abccd2-7957-5651-8005-843ba39d4cb9`, and its manifest URL is `https://raw.githubusercontent.com/evdev/hubitat-resideo/main/packageManifest.json`. Keep the standalone listing in this repository consistent with the approved author entry. Preserve existing app/driver IDs and names/namespaces so HPM can recognize installed code.

For each release, update the package manifest's version, date, notes and pinned source URLs; tag the tested revision and verify its public downloads before exposing the new manifest on `main`. Verify the complete public lookup chain: standard HPM list → approved evdev catalog → Resideo manifest → tagged source files. Existing catalog entries keep their manifest URL across version updates; adding a new package requires updating the approved author catalog. Publish the GitHub release after those checks. Already managed packages use HPM Update; manually installed code must be matched through HPM Match Up first.

## HTTP and credentials

The app limits active API requests to two, coalesces reads, and serializes writes per thermostat. Write bodies are constructed at dispatch, allowing later sensor edits to include already accepted pending settings. The authentication request chain has its own single-flight gate.

PKCE uses cryptographically random verifier/state and SHA-256. Headless login explicitly processes cookies and redirects. Password sign-in retains the upstream native redirect URI. Browser sign-in uses the same client's registered HTTPS callback, so the final address can be copied directly from the address bar. The authorization request and token exchange use the same callback; the app exposes no OAuth endpoint. Pending attempts survive preference saves. Browser setup attempts expire after one hour; password login attempts expire after 15 minutes. Both preserve First Alert’s fresh-login parameters and server-controlled code expiration and reject results with a different state or callback address.

Access tokens refresh five minutes before expiry, with their timer restored after initialization. The minute maintenance job also checks token freshness and recovers stuck renewals if their timeout callback was lost. Since normal state is committed when a handler returns, refresh intent and the new token pair also use atomicState for immediate persistence before network transmission or releasing waiting requests. Explicit API 401 responses retry once after refresh.

Startup retains the saved refresh token, renews expired access tokens before releasing queued API requests, and attempts recovery with the latest saved token after an interrupted refresh. Saving preferences during an active refresh defers initialization until that request completes or fails. Temporary refresh failures retain credentials and retry after 30 seconds, doubling to a 15-minute maximum; HTTP 429 follows Retry-After. The retry deadline survives initialization and the watchdog respects it. A late successful response can persist its rotated token until a replacement refresh request is sent. Explicit authentication rejection clears both normal and immediately persisted token pairs, then attempts saved-password sign-in if enabled. If the server rotated a token before power loss and the new token never reached storage, token recovery may be rejected: Auth0 reuse detection and maximum token lifetimes are server-controlled.

Password storage is opt-in and off by default. The login attempt snapshots the supplied email/password, and only a successful password token exchange persists that snapshot in `state.savedCredentials` and `atomicState.savedCredentials`. The entry field and temporary attempt are cleared afterward. The saved email must match the configured login email before reuse. Startup with no tokens and explicit refresh rejection schedule the existing six-step PKCE password flow using these saved credentials. Automatic login preserves existing child identities, caches, and pending reconciliation. Temporary automatic-login failures retry after one minute, doubling to a 15-minute maximum, with Retry-After respected; the watchdog also recovers missed timeout jobs. Credential rejection, verification challenges, account blocking, unsupported responses, and user cancellation pause automatic password attempts across restart. A successful manual password sign-in resets that pause. Disabling the integration stops automatic requests while preserving the saved password. Opting out or uninstalling clears both persisted credential views. Successful browser sign-in clears saved credentials because it can select a different account.

Tokens, optional saved passwords, and temporary login state live in the hub's app storage. Saved passwords are readable by hub administrators and through backups; the setup page includes this disclaimer. Diagnostic output is a separate whitelist of operational metrics, not a dump of app state. Saved and in-flight passwords also participate in cloud-message redaction.

Successful automatic password sign-in establishes a separate recovery cooldown of one minute, doubling to a 15-minute maximum when new grants repeatedly require recovery within an hour. It survives restart and prevents rapidly repeating full password sign-ins when the cloud rejects new tokens. A manual sign-in does not itself set a persistent recovery pause. Rejection of older API requests preserves an active password/browser exchange. Refresh errors using `error`, `code`, or `name` are normalized; verification and integration configuration failures pause password fallback. Failures in connection setup after token persistence retain the authenticated session and retry setup once per minute without resubmitting credentials.

## API versions

- `https://api.ha.resideo.com/ris-public-api/api/v1/accounts`: account graph.
- `/v2/devices/thermostats/{id}` and `/configuration`: wrapped Reported/Desired state.
- `/v1/devices/thermostats/{id}/priority` and `/group/0/rooms`: priority and room/sensor state.
- `/v1/devices/thermostats/{id}/{command}`: complete command body plus ChannelId.
- `/ds-notification-service/Hub/negotiate?negotiateVersion=1`: initial SignalR negotiation.

The source's public Auth0 client and APIM subscription constant are retained. They identify the mobile-app protocol; account access still requires the user's token. Constants are centralized in `constants()` in the app. Do not fall back to the retired `api.resideo.com` host.

## Stream

The app negotiates with Resideo and then Azure, validating the returned host before sending Azure credentials. The connection URL includes both connection token and Azure access token. The driver implements record-separator JSON framing, handshake and subscription completion checks, nested notifications, 15-second keepalive, 45-second stall detection, and proactive feed renewal.

Subscriptions wait for their completion before activation. The app reads priority per thermostat and resynchronizes state. Polling remains active until activation and bootstrap both finish. Feed-expiration timestamps inside the renewal margin are ignored; otherwise they update the renewal deadline. The fallback feed lifetime is 600 seconds with a 120-second margin, matching the source constants.

Known live-feed shapes merge only non-null fields into REST-shaped state. Endpoint cache revisions prevent slow HTTP responses from replacing newer pushed or REST values. Bootstrap reads carry the connection generation; pre-subscription reads cannot establish push readiness. Changes to the location's selected thermostat set renew the connection and activation. Unknown events refresh REST after a three-second debounce. Sensor pushes use the upstream verified off-by-one identity workaround: thermostat-shaped values target the unique thermostat accessory; battery-sensor values target the successor of the header accessory in AccessoryId order. Ambiguous routing is rejected and refreshed through REST.

## Writes and identity

Reported values and pending overlays are separate. Accepted fields track request, transaction, settling deadline, and deferred status. Normal fields reconcile only against their owning endpoint and a read dispatched after the deadline. The earliest outstanding deadline is scheduled across devices. Uncertain results pause conflicting writes until a fresh settling read; they are never automatically resent. Accepted sensitivity changes remain deferred until matching reported state.

Thermostat identifiers use the app instance and cloud device ID; sensor identifiers add the accessory ID without the mutable room ID. Location connection identifiers hash the location ID. Labels are never automatically overwritten. Orphans remain until explicit removal.

## Upstream changes

The exact baseline and original license are recorded in the repository. Sanitized upstream fixtures are checked in under `tests/fixtures/`. `.reference/` is an ignored maintenance snapshot of source files; it is not part of deployment.

`tools/build-parity.py` reads the pinned platform declarations from `.reference/` and regenerates `tests/parity.json` and `docs/parity.md`. Tests verify every declared upstream entity has a driver attribute and a populated representative projection. Update the source baseline, mapping, fixtures, and tests together when porting an upstream change.

### Cookie-header fallback during sign-in

Most HTTP work remains asynchronous. Some hubs flatten duplicate Set-Cookie headers in AsyncResponse, which can omit Auth0's CSRF/session cookies. If a login page lacks CSRF or enters a bounded redirect loop, setup restarts its temporary cookie session once using native HTTP for the cookie-bearing authorization, login-page, callback and resume steps. Each native request runs in its own scheduled callback with a ten-second timeout. Credential submission, token exchange, refresh and all device API requests stay asynchronous. Full native header iteration retains duplicates, the same HTTPS host guards apply, and cancellation clears temporary wire/cookie data. No extra server, runtime library or custom thread is used.

Driver outboxes hold at most 64 messages and 262144 serialized characters. Overflow signals a REST resynchronization; an additional command that cannot be queued is visibly failed, rather than silently replayed. Native bridge events carry no message payload, credentials or stream URLs. Negotiation, activation and eligible foreground writes take priority over background reads while retaining per-thermostat write serialization and the shared two-request limit. Optional rooms endpoints known to be unsupported are retried on explicit/metadata refresh, rather than every ordinary poll.

Drained bridge packets execute in separate scheduled app callbacks so an exception in one cannot discard the next packet. Thermostat and sensor snapshots are coalesced for 100 milliseconds and emitted in the child driver's own execution. A driver caches its last emitted attribute values, avoiding repeated platform reads for unchanged fields. Connection start, socket open, negotiation failure and status events also use short driver callbacks. Periodic maintenance publishes only health transitions; reconnect status changes do not trigger REST reads. Fallback polling retains its configured interval, and successful subscriptions still perform their generation-scoped resynchronization.
