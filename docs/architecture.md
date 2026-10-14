# Architecture and maintenance

## Deployment

The deployment unit is one app and three standalone driver scripts. Each is compatible with Groovy 2.4 syntax and defines no custom classes or runtime library dependencies. The app owns HTTP requests and tokens; connection drivers use Hubitat's WebSocket interface. Thermostat and sensor drivers are thin command/event adapters.

All components declare `singleThreaded: true`. Child-to-parent entrypoints schedule message envelopes with `overwrite: false`; shared state changes occur inside the app's serialized handlers. HTTP callbacks join the same processing path. Account generation identifiers reject previous-session responses; connection generation identifiers reject obsolete negotiation and stream messages.

## HTTP and credentials

The app limits active API requests to two, coalesces reads, and serializes writes per thermostat. Write bodies are constructed at dispatch, allowing later sensor edits to include already accepted pending settings. The authentication request chain has its own single-flight gate.

PKCE uses cryptographically random verifier/state and SHA-256. Headless login explicitly processes cookies and redirects. Password sign-in retains the upstream native redirect URI. Browser sign-in uses the same client's registered HTTPS callback, so the final address can be copied directly from the address bar. The authorization request and token exchange use the same callback; the app exposes no OAuth endpoint. Pending attempts survive preference saves, expire after 15 minutes, and reject results with a different state or callback address.

Access tokens refresh five minutes before expiry, with their timer restored after initialization. Since normal state is committed when a handler returns, refresh intent and the new token pair also use atomicState for immediate persistence before network transmission or releasing waiting requests. Explicit 401 responses retry once after refresh. An ambiguous refresh result invalidates authentication instead of replaying a potentially consumed grant.

Tokens and temporary login state live in the hub's app storage. Clearing settings limits retained passwords but does not imply encrypted storage inaccessible to administrators. Diagnostic output is a separate whitelist of operational metrics; it is not a dump of app state.

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
