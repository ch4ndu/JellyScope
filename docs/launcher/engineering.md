# Launcher engineering contracts

App setup and administrator tasks: [Launcher guide](README.md). This page owns
launcher-only navigation, data, persistence and configuration contracts; shared
authentication/transport contracts remain in their existing module guides.
The player receiver owns the cross-app [playback link contract](../engineering/tv-ux-behaviors.md#playback-links).

## Component ownership

| Component | Responsibility |
| --- | --- |
| `android-launcher-app` | Separate APK, Android Home entry, Compose UI and local app management |
| `auth-session` | Reused authentication/session and account cleanup contracts; separate launcher credentials |
| `transport` | Reused guarded HTTP transport; launcher composes its own client/session scope |
| `jellyfin-launcher-plugin` | Independent .NET server build and dashboard layout; no Android server dependency |
| `android-tv-app` | Separate JellyScope playback receiver and native player ownership |

Launcher production files live under
`android-launcher-app/src/main/kotlin/com/jellyscope/launcher/`:
`MainActivity` owns Android entry/Home handling, `LauncherApplication` composes
DI, `LauncherViewModel` owns UI/session operations, and `LauncherShell` routes
Home with Settings and Login dialogs. `LauncherLayout` and
`LauncherConfigurationRepository` own layout validation/fetching;
`LauncherMediaRepository`, `LauncherSnapshotStore` and `LauncherImages` own media,
durable snapshots and artwork respectively. `LauncherAppsRepository` owns app
inventory/order; `MainActivity` and the vendored `JellyScopePlaybackContract`
own the outbound handoff. `LauncherFocus` supports the independent tab focus owners.
The launcher does not import the player UI or native playback runtime.

## Independent Android launcher

The launcher has Home and Jellyfin tabs, separate from the player
application's [drawer and hero contracts](../engineering/tv-ux-behaviors.md). Home starts with local Apps;
Jellyfin contains media only. Server rows may target either tab. Apps, launcher settings and Android System settings remain
reachable before login and during media errors. Apps contains the complete ordered
launchable catalog, one resolved component per package. Long-press offers Open
and Move; each Left/Right step saves synchronously, and Select/Back finishes.
There is no pin prerequisite, plus tile, picker, 24-app ceiling or normal Apps hint.
Apps has no separate grid route. Missing components fall back to a surviving app, then the attached Home tab.

The existing device preferences file uses `app-order-v1`. If absent, surviving
legacy pins seed the order before remaining catalog entries append. The legacy
key is preserved. Refresh removes unavailable IDs, deduplicates and appends new
components without sorting surviving entries. Reinstalled/pruned or renamed
components append on the next resume/manual refresh. Mutex-protected IO commits
precede durable authority changes. Failed refresh commits publish discovered apps
as non-durable, with visible save status and retry on refresh; failed Move commits
leave the visible order unchanged and report failure. The captured durable source
is not replaced by SharedPreferences' failed-commit in-memory map.

Server-configured visible media rows follow their declared order, title and
landscape (176×99), poster (128×192), or square (160×160) shape. Media focus keys
use `row:<id>` and `row:<id>:<itemId>` so remote IDs cannot alias local controls.
Settings reports current, cached, default, unavailable, invalid or unsupported
configuration. Removing or emptying the focused row uses its saved clamped
vertical position among surviving media rows on the active tab, then Apps on
Home or the available action/tab control on Jellyfin.

Each row remembers its stable item identity and semantic index. Vertical movement
restores the destination row; missing items use the clamped index, next available
row on the active tab, then that tab’s fallback described above. Horizontal
movement reveals the focused card without changing
row identity. Modal controls trap focus and restore their invoker. Return from
System settings restores the header gear that opened it. The gear dispatches
the Android settings callback directly, preserving the selected tab. The Jellyfin
icon opens the account dialog; the background header is inactive while modal.

Back dismisses a dialog first, then finishes inline Move. Login Back hides the
keyboard first; another Back cancels outstanding work, clears password and returns
to its origin: Settings or the Jellyfin tab and Connect action. A login already
durably committed remains reflected in session state. On Home, Home Back
below Apps returns to Apps; at Apps it retains ordinary activity
exit behavior unless the public resolver reports this Activity as the default Home;
in that case root Back is consumed without navigation. Device qualification is
separate from this source behavior.
Media selection uses the explicit JellyScope [playback link contract](../engineering/tv-ux-behaviors.md#playback-links). The Settings message
explains matching-account setup and the return path; it does not claim playback
success from an Activity launch.

### Launcher tabs and connection flow

Tab selection lives in the ViewModel. The stable header focus owner survives tab,
route, account and Home-reset changes; separate content owners retain each page's
row/scroll memory. A local focus container uses `focusRequester`,
`focusProperties.onEnter` and `focusGroup`, adapting the TV scope pattern without
importing its coordinator. Immediate entry tries attached valid children;
explicit deferred targets remain pending separately. Header keys forward through
Home content owners, which reflect actual header focus. Settings/Login use a
route-local dialog focus owner without header binding. Home stays composed, but
background focus, actions, dwell and restoration are suppressed while modal.
Account entry targets its enabled account control or Close; login enters Server
URL. Select opens the field keyboard, and IME insets keep the form scrollable.

Focused inactive tabs select after 250 ms; blur, route/modal/move/Home/account
changes cancel stale dwell. Center selects immediately. Down-before-dwell selects
and requests content in one state update. Signed-out Jellyfin enters Connect;
restoring with no action stays on the tab. Right from Jellyfin traverses account
then gear; content Up returns to its tab. Focus uses text/underlines for tabs and
quiet scaling/outline for app tiles over the centralized Dusk plum-to-coral gradient.

Jellyfin Back moves content focus to its tab, then switches to Home. Closing
Settings restores its invoker; playback return preserves tab/item.
Actual device Home selects Home and targets Apps through the Home reset.
Logout keeps the selected tab and chooses a reachable disconnected landing.

Signed-out Home shows Apps without repeated media sign-in prompts.
Signed-out Jellyfin offers **Connect to Jellyfin → Connect to server**. Session
restoration shows a waiting state; pending cleanup directs users to the existing
Settings recovery action. Busy cancellation does not pretend a connection is
available. Signed-in empty content offers Refresh/Settings; offline content uses
the existing cached/stale or retry state rather than showing a login prompt.

Login started from Jellyfin returns to that tab after success or cancellation;
cancel targets Connect without a Home-reset generation bump. Settings-origin
cancel still returns Settings. Session publication and login-action completion
consume the same login origin once, so either arrival order gives the same
result. Actual Home clears origin and wins over a cancelled attempt's UI result;
a committed session remains authoritative. Password/IME disposal is unchanged.

### Standard Home integration

The independent launcher declares a separate MAIN/HOME/DEFAULT filter and uses
singleTask. A new MAIN+HOME intent without data returns to the Apps content entry, clears
a modal/auth error and replaces Home focus state. Cold handling runs only without
saved instance state, so configuration recreation does not replay the original
Home reset. Ordinary app/Leanback intents and external-app return preserve normal
navigation. No Home key interception, vendor redirect, accessibility service or
boot receiver is installed.

Home while Login is visible routes directly to Apps and disposes the password/IME
UI. The existing cancellation fence invalidates the attempt and joins the old job;
a login commit that already won remains authoritative. Account actions can remain
briefly busy while that fence finishes; Apps and System settings stay accessible.

Settings offers **Choose default Home app** through Android's standard selector.
Return restores the settings button's focus and rechecks the default using public
intent resolution with exact component equality. Resolver/unknown/error results
show conservative not-selected status. Missing/blocked selectors produce a focused
message; System settings remains separately available. Firmware may not expose or
honor third-party Home selection. See the
[qualification record](qualification.md) for evidence
levels and the unrun device checks.

## Android launcher queries and artwork

The launcher defaults to Play Next (resume query), Up Next (non-resumable Next Up
query), Recently Added and Favorites. Existing administrator-authored stored
titles are preserved. Server layouts may select up to 12 ordered rows using bounded
typed queries directly through `JellyfinApi`, with at most 20 cards per row and
two concurrent row requests. Successful empty results replace cached rows;
transient failures retain the last snapshot with a stale indication. Foreground
refresh requests coalesce, automatic refresh has a 30-second minimum interval,
and a 15-minute timer runs only while the activity is started. This is not a
background execution guarantee.

The independent [configuration plugin](plugin-reference.md)
owns the schema and server baseline. The launcher fetches its fixed endpoint
only after establishing the durable refresh marker, with full session headers,
origin validation, no redirects, strict UTF-8 and a 64 KiB response limit. Invalid,
unsupported or unavailable configuration retains validated last-good settings;
without those, four defaults apply. A valid empty layout remains empty.
Configuration 401/403 alone never invalidates the session.

Library IDs normalize to lowercase 32-hex GUIDs before client intersection with
current user views. The server excludes external views; the client probe's default
may include them, making its set a conservative superset. Cached configuration is
also intersected; removed library rows need a later successful configuration fetch
to return. Layout adoption durably prunes hidden, removed and query-changed row
snapshots before publishing metadata. Work freezes one effective layout and checks
account lease, generation, row ID and query fingerprint before commits. The
fingerprint includes query, library and limit, but excludes title/order/aspect and
server revision. Metadata changes can therefore reuse cards without accepting old
query results. Optional row tab placement is presentation metadata too: both tabs
consume one account-scoped layout, with the same combined 12-row bound and no
requests caused solely by switching tabs. Absent/null placement is derived from
the query on every wire/cache path, including existing version-2 snapshots. Landscape artwork selects backdrop then primary; poster/square use
primary or a placeholder. Legacy tags are used only for their recorded image type.

The launcher opts into preserving HTTP 403 as `ServerError(403)` for ordinary
authenticated reads. Default transport behavior for existing callers is
unchanged. A scoped denial clears the rejected row; an unauthorized response is
checked against the current-user endpoint before signing out. Temporary probe
failure never proves invalid credentials. A policy fingerprint uses only maximum
parental rating and sorted accessible user-view IDs; confirmed changes invalidate
cached media and artwork. Offline caches cannot discover remote policy changes.

Artwork uses an explicit account-owned Coil loader, not the player UI singleton.
Disk keys use stable account identity and effective tagged URL; runtime epochs
isolate loaders and may appear in memory keys, but do not invalidate disk hits on
cold restart. Logout retires requests/loaders and clears account artwork. Image
path/query components are encoded and the credential-origin guard validates the
final URL before attaching authorization. Redirects and HTTP logging are off;
tokens never enter URLs or cache keys. Missing artwork leaves a focusable local
placeholder. Account cleanup is owned by the
[account persistence contract](#independent-android-launcher-state).

## Independent Android launcher state

The launcher has its own application sandbox and Keystore credentials. It uses
`auth-session` for login, restoration and logout; it never imports credentials
from the player application. Apps and settings remain usable while restoration
or authentication is pending. Passwords are transient editor values, not saved
instance state or preferences.

Launcher media snapshots are bounded, versioned atomic JSON files under
`noBackupFilesDir`, scoped by credential-free server/user identity. The store
registers as both a refetchable and account-scoped cache before the session
repository starts restoration. Cache commits and visible publication require
an account work lease and current query generation. Lock order is registry gate
then store mutex; network work never holds that gate. Cold restoration may load
an existing snapshot; account transitions invalidate refetchable state.

Snapshot version 2 adds the validated last-good layout and up to 12 row snapshots.
The bounded version-1 reader preserves default row IDs/query keys and legacy
artwork tags; writes use version 2. Configuration shares the existing account
cache participant and cleanup paths. Unknown/corrupt snapshots fall back to empty
cache and local defaults; credentials and local app order require no migration.

A synced pending-refresh marker is established before configuration, policy or row requests.
Startup refuses marked snapshots, including AtomicFile backups. The marker is
removed only after a complete safe snapshot or empty tombstone is durably written
and verified, or all snapshot artifacts are verifiably removed. Persistence
failures surface through cleanup status. An interrupted refresh can therefore
make old offline rows unavailable until safe refresh or cleanup; it must not
restore potentially revoked content. Normal transient failures with healthy
storage retain the prior allowed rows.

The launcher durable cleaner includes `SessionStore`: logout clears the session
first and clears the pending flag only after credential removal succeeds. It
attempts remaining cleanup and aggregates failures. A failed cleanup exposes
Retry sign out even when the shared session is already logged out, including
pending-logout recovery at cold start. Application-owned authentication ordering
serializes login, logout and confirmed-invalid-session handling across Activity
recreation. Server/account removal calls
the matching session-store operations. Registry cleanup is idempotent and never
reacquires its gate. Component-key app order are separate device
preferences, survive logout, and are not remote media configuration.

## Launcher configuration ownership

`jellyfin-launcher-plugin` is an independent .NET server build, with no server
dependency introduced into Android. It owns the shared dashboard layout and
authenticated user projection. Launcher-local `LauncherConfigurationRepository`
fetches the bounded schema, `LauncherLayout` validates it, and the existing
`LauncherSnapshotStore` owns durable per-account layout/media adoption. The
[schema](plugin-reference.md#wire-schema) and [server baseline](README.md#requirements-and-current-status) have one
owner; shared transport and authentication remain unchanged by this integration.

## Why

- Launcher row identity and explicit invoker restoration keep Apps and recovery
  controls usable without importing the player UI or its navigation drawer.

- Launcher snapshot files keep the small bounded row cache independent of the
  player database. Shared account leases preserve cleanup ordering without
  coupling local app order to a media account.
