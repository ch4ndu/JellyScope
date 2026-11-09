# Seerr requests

Connect JellyScope to an existing Seerr server to discover movies and shows
outside your Jellyfin library, request eligible titles or seasons, and follow
your requests. This connection is optional; ordinary Jellyfin browsing and
playback work independently.

The integration targets the Seerr v3.5.1 API. It does not configure Seerr,
Radarr or Sonarr, provide an administrator dashboard, or promise compatibility
with every older Jellyseerr deployment. The existing
[platform support limits](platforms.md) still apply, including the Apple TV
preview's status.

## Connect your account

Open **Requests**, choose **Manage connection**, and enter your Seerr address.
Include any deployment subpath, then validate the server. Changing the address
requires validation again before sign-in. Seerr must already be initialized by
its administrator.

Choose an authentication method offered by that server:

- **Jellyfin:** sign in with your Jellyfin username and password. The linked
  Jellyfin user must match the currently selected account where Seerr provides
  that identity.
- **Local account:** sign in with your Seerr email address and password. This
  explicitly links that Seerr account to the currently selected Jellyfin account.
- **Quick Connect:** start the flow, approve the displayed code through the
  server's Jellyfin Quick Connect flow, and wait for verification. Cancel if you
  no longer want to connect; an expired code requires a new attempt.

Check the connected user's name before requesting. Passwords and Quick Connect
secrets are not saved. JellyScope saves the verified session for this Jellyfin
account; switching accounts does not transfer it. Returning to an account
restores and verifies its saved connection.

Use HTTPS when available. Seerr protection that requires Secure cookies needs
HTTPS. Other HTTP connections remain subject to the app platform's existing
network restrictions; JellyScope does not weaken those restrictions or the
server's protection.

## Discover and request

Requests has **Discover**, **My Requests**, and **Search** sections. Discover
offers trending titles, movies and TV shows through its catalog control. Search
retains its query and results separately; Clear leaves an empty Search section.
**Load more** adds another page;
**Refresh** reloads the list from its beginning. The existing Jellyfin Discover
destination continues to browse your server's own content.

**Filters** contains **Hide available**, on by default for the trending, movie
and TV catalogs.
It hides titles that Seerr reports as fully available in either quality; partly
available series remain visible unless another quality is fully available.
Turn it off to browse existing titles or another quality. Search, My Requests
and Similar remain inclusive. Availability follows Seerr's library scan and may
lag recent Jellyfin additions. A filtered-empty page can still offer Load more.

Open a title to inspect availability and request history. Choose standard or
4K quality where your permissions and server configuration allow requests, or
where that quality already exists in the library. Existing library access does
not require permission to make new requests. For TV,
select one or more eligible seasons in **Seasons**. Checkmarks show your selections
independently of focus; unavailable choices explain why they cannot be selected.
Changing quality clears the selection. The request action shows the selected
season count, and confirmation lists the exact seasons and quality. Quotas may
limit how many requests you can make; TV quotas count selected seasons.

Review the title, media type, quality, seasons and approval notice, then confirm.
Seerr uses its administrator's acquisition defaults and decides whether approval
is required. If another request or library update covers some selected seasons
before submission finishes, Seerr can accept only the remaining seasons; check
the refreshed details for the actual request. Approval starts the server's
request process; it does not mean the
media is already available to play.

Some selections cannot be requested:

- Available or partly available seasons cannot be requested as missing episodes.
- Active requests, including failed requests, can block another request for the
  same selection. Ask the server administrator to resolve an existing failure.
- Specials appear only when Seerr returns them and enables special-episode
  requests. 4K requests also require server configuration and permission.
- Missing eligibility or quota information requires a successful refresh before
  requesting. A completed request does not prevent another request if the media
  was subsequently deleted and is otherwise eligible.

Details also show **Cast** and a **Similar** ribbon when data is available.
Cast cards are informational. Selecting a similar title opens its Seerr details;
Back returns through the previous details to the original list. Similar shows a
bounded first page. A failed or interrupted Similar load offers Retry without
blocking requests; returning to the screen does not automatically retry it.

## Follow requests and open your library

**My Requests** shows requests belonging to the connected Seerr user. Refresh
to check their latest state. Pending, approved, declined, failed and completed
describe the request; unavailable, processing, partly available and available
describe the media. These are separate states.

Use **View in library** to open a matching title in the current Jellyfin account.
JellyScope verifies access, media type and provider identity before opening the
normal movie or series screen. Use its existing playback controls from there.
Availability reported by Seerr does not grant access to media on another server.
If no accessible match is found, remain on Requests and refresh later.

Back closes request confirmation or external title detail before leaving the
Requests flow. Returning from a Jellyfin library screen retains the external
detail and its browsing context. Android TV uses its drawer for top-level
navigation; the native Apple TV preview has a separate Requests tab.

## Recover or disconnect

If submission times out or its result is unclear, use the request-outcome refresh
control before trying again. The server may already have accepted the request.
JellyScope checks its state and never automatically resends an uncertain
submission. A message that no selected seasons were requested is a non-creation
result; refresh eligibility and review the selection again.

An expired session requires sign-in again. Permission or quota denial does not
by itself mean the connection expired. Connection management provides explicit
verification/retry and disconnect controls. Disconnect removes the local Seerr
connection for the selected account; signing out clears its saved credentials.

Accounts with a Jellyfin parental-rating restriction cannot browse or request
the external Seerr catalog. Connection management remains available. If that
restriction changes while Requests is open, external content and pending
confirmations are cleared.
