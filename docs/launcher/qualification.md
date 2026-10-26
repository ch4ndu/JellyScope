# Android launcher qualification

The launcher targets Android TV/Google TV and Android-based Fire OS at API 25 or
newer. Vega OS, Fire OS 5/API 22, and older Android are excluded. These are support
targets, not a list of qualified devices. An authorized TiVo installation and cold launch passed after user removal of
CoreService; full ordinary-app and replacement-Home behavior remain unqualified. Local APKs are debug-signed verification artifacts, not releases.

## Evidence levels

| Level | What must be established | Current device result |
| --- | --- | --- |
| Installation | Install/update on the identified API/ABI/firmware | TiVo: install and Ocean update passed after CoreService removal; reboot not tested |
| Ordinary app | Login, Apps/order, media/cache, remote focus, settings and playback handoff | Ocean startup, Apps/Move and targeted ADB navigation passed; authenticated media/playback and physical remote remain unqualified |
| Replacement Home | Selector support, actual default, cold/repeated Home and Back/task behavior | Not run |

Device identity and artifact details for the first attempt are recorded below;
remote model remains unknown. Record these values alongside each future result;
do not transfer a result to another firmware/model or equate installation with
Home replacement. Emulator evidence, if later authorized, is separate from physical
TV/Fire TV evidence.

## Standalone media entry — 2026-10-24

The reviewed TV Release installed successfully on the TiVo. Actual launcher
Series, Movie and Episode selections opened their corresponding Series/Details
views without autoplay. Root Back returned to the launcher; the Episode check
also confirmed the launcher as Android's resumed Activity. A Series-entry
recording sampled at 3 fps showed launcher, neutral loading and Series, with no
JellyScope Home visible in the sampled frames. This is not frame-perfect proof.

Formatting, existing TV unit tests, Debug compilation, Release lint, minified
assembly, independent code review and final verification passed. Account sessions
were preserved. Legacy `/play` and Watch Next ingress, nested Back, actual resumed
playback ticks, rapid links, loading cancellation, failure/recreation, ordinary
warm relaunch and account rejection remain source-reviewed only for this slice.
The prior handoff observation below describes superseded Back behavior; standalone
entry now finishes the TV Activity at its root.

## Earlier details handoff qualification

Both Release apps installed successfully on the TiVo on 2026-10-24. Source review,
final verification, formatting, compilation, Release lint and assembly passed.
The new launcher against the old TV receiver showed Update JellyScope without
playback. After updating TV, a media selection opened Details with Resume from
0:41; choosing it reached the player paused at 0:41. A Recently Added series
opened Series with Seasons and no autoplay. Back restored the prior TV details
screen. These are observed paths, not blanket playback qualification.

Account/delivery races, active-player rejection and lookup-failure retry remain
source-reviewed; logged-out/mismatched-account device checks were not repeated.
No parser test was added. Both app sessions were preserved.

## Account and sign-in dialog candidate

The current source replaces account/Login pages with focus-isolated dialogs,
removes the redundant account buttons and Apps grid, and hides Fresh ribbon
status labels. Tile titles use one line with ellipsis; media rows have a 24 dp gap.
Debug compilation, formatting, independent review, Release lint/assembly and final
source verification passed. The Release update installed on the TiVo. Device
checks confirmed account-dialog focus after Refresh/Right, Close restoring the
account icon, and single-line media titles with clearance beside a focused
landscape card. Login field entry/IME/Back and Connect-origin cancellation remain
unverified because the existing signed-in session was preserved. Transition
clearance for all card shapes is source-calculated, not frame-by-frame device proof.
Earlier Dusk tests established form visibility only, not editable-field behavior.

## Dusk header refinement (before dialogs)

Dusk replaces the Ocean background and the gear opens Android System settings
directly; the Jellyfin icon retains launcher account/settings entry. Source review,
formatting, debug compilation, Release lint and minified assembly passed. The
Release installed successfully on the same TiVo on 2026-10-24; APK SHA-256:
`c588b330899d105ef18897ed44687b82a31359cd428a46e16aeba2060ef3b141`.

Screenshots confirmed the plum/coral background and readable pale header icons.
ADB D-pad activation of the gear opened `com.android.tv.settings/.MainSettings`
from Home, Jellyfin, internal account Settings and Login. Back preserved the
origin; screenshots confirmed gear focus on Home, Jellyfin and the empty Login
form. The account icon focused Sign in internally, and Jellyfin Connect remained
reachable. No credentials were entered or app order changed. Cold launch reported
683 ms, a single observation rather than a performance qualification. Physical
remote timing and reboot behavior remain unverified; earlier Ocean results below
cover unchanged paths.

## Ocean/Home candidate

The earlier Ocean candidate replaced favorites with the complete ordered Apps catalog,
adds Ocean colors/quiet focus, and uses stable header/container focus with delayed
tab selection. Host checks and review are recorded separately from device results.
The reviewed minified Release installed with `adb install -r` on the TiVo described
below on 2026-10-24. APK SHA-256:
`954f070647c376f7f3c750454b4d35ebc507d221151a47b64318c83a9df90d99`.
Cold launch reported 846 ms; this is a single observation, not a performance result.
Source review, formatting, debug compilation, Release lint and assembly passed.

ADB D-pad input and screenshots confirmed:

- Ocean background, Home/Jellyfin underline tabs, quiet app focus, updated icons,
  seven automatically listed apps and no normal instructional subtitle.
- Long-press Open/Move, a one-position move persisting after force-stop/relaunch,
  and restoration of the original order without accidental app launches.
- Dwell selection, Down to Connect, and rapid Right/Down reaching Connect.
  Traversing Jellyfin to the account icon without dwelling kept Home selected.
  Exact event timing relative to the 250 ms delay was not measured.
- Right from Jellyfin through account and gear; the account shortcut focused
  Sign in. Connect opened the empty sign-in form; Back returned to Jellyfin,
  with Down reaching Connect again. No credentials were entered.
- Settings, the seven-app grid, Android System settings, and return focus on
  the System settings control. An explicit MAIN/HOME intent returned to Apps.

This is targeted ordinary-app verification, not full device qualification.
Physical-remote hold/repeat timing, accessibility announcements, injected write
failures, complete migration edge cases, authenticated media and playback remain
unverified. No default change or reboot was performed by these checks.

### Saved Home selection versus effective routing — 2026-10-24

The user reported selecting JellyScope as default. Read-only inspection of the
same TiVo found the chooser selected JellyScope, `dumpsys role` reported
`android.app.role.HOME` holder `com.udnahc.jellyscope.launcher`, and
`dumpsys package preferred-xml` named its MainActivity. However,
`cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME`
resolved `com.google.android.tvlauncher/.MainActivity`; a Home key press also
opened Google TV. The stock privileged launcher advertises HOME priority 2,
compared with JellyScope's 0. Selection is saved but effective routing differs.
No package/default changes were made during this inspection. The launcher status
currently checks effective resolution, so it cannot distinguish this case from
not being selected. This device limitation remains unresolved.

## Earlier favorites UX device check

Stock Home, app-menu and favorites-picker screenshots informed the compact
header and banner geometry. After reconnection, the reviewed minified Release installed successfully and
cold-launched in 1,006 ms on the same TiVo. ADB D-pad input and screenshots
confirmed the favorites picker, adding two temporary pins, long-press menus,
inline leftward Move, Back finishing with the new order retained, and removal
with focus falling back to the surviving pin and then plus. The original empty
favorites list was restored. These checks did not launch an app accidentally.

Physical-remote timing, sustained held-key transitions, accessibility announcements,
grid scrolling, settings return, login cancellation and Home cancellation remain
unqualified for this redesign. Earlier installation evidence below is separate.

## TiVo Stream 4K installation attempt — 2026-10-23

User authorized installing the Release APK and investigating the missing-activity
error. Device: TiVo Stream 4K / SEI400TV, Android 12/API 31,
firmware `SC.1272 release-keys`, ARMv7 (`armeabi-v7a,armeabi`).
Source revision: `32898a1a`; launcher version 0.1.0/versionCode 1.
Release APK SHA-256:
`43065384dc8117ba53b7b4061e9fef3a2995294cefcf2e5aceb207d5728302ac`.

`assembleRelease` passed. `adb install -r` reported Success, but subsequent
package lookup was empty and explicit `am start -W` returned Error type 3 for
`com.udnahc.jellyscope.launcher/com.jellyscope.launcher.MainActivity`.
The packaged manifest declares that exact activity. Device logs show
`EvoBlackListAppsFun` identifying the non-system launcher, followed by
`AppUtil: uninstallAsUser` for its package and package-removal events.

This establishes firmware-driven removal after installation, not a missing
Activity declaration or an app startup crash. Home eligibility is the suspected
trigger; a build without that eligibility has not yet been tested. No vendor
services were disabled, no default Home was changed, and no ordinary-app,
login, playback, remote or live-server behavior was validated. The user requested device debloating instead of an ordinary-app variant.
After explicit risk/rollback approval, firmware rejected disabling
`com.nes.coreservice` with “Warning! This command is illegal!”. It also rejected
ordinary TiVo `sageservice` and `tvlaunchercustomization` disable commands.
At that checkpoint the core service remained enabled; disabled-package lists were identical.
A subsequently authorized `pm uninstall -k --user 0 com.nes.coreservice` attempt
was also rejected because the firmware identifies it as a core application. Those attempts changed no existing device packages; the launcher remained absent
until the subsequent user intervention below.

### Authorized Shizuku and Canta attempt

Official Shizuku 13.6.0 (r1086) and Canta 4.0.0 were installed. Canta's APK
certificate matched its published developer fingerprint. Shizuku started with
its displayed ADB command, and Canta received Shizuku access. Only
`com.nes.coreservice` was selected and confirmed for removal.

Canta logged `DELETE_FAILED_INTERNAL_ERROR` (status 1). Subsequent package
inspection still reported `installed=true`, default enabled state, and the same
running CoreService process. No more specific cause appeared in the inspected
package-manager log. Disabled-package state was unchanged, ADB remained connected,
and Google TV Home remained the default. This attempt does not establish that
all direct-API approaches fail; it establishes that this Canta removal failed.
The launcher was not reinstalled because its removal service remained active.
Shizuku and Canta remain installed; no reboot or alternate launcher variant was used.

### Successful installation after user intervention

The user subsequently reported uninstalling CoreService; their removal method
was not observed. ADB verified `installed=false`, `stopped=true`, and no running
`com.nes.coreservice` process for user 0. The unchanged Release APK identified
above then installed successfully. Explicit cold launch returned `Status: ok`,
with 667 ms reported Activity launch time. This single startup measurement is not
a performance qualification.

The launcher remained installed and running during follow-up checks. The resumed
Activity was `com.jellyscope.launcher.MainActivity`; the UI hierarchy showed
Essentials, Jellyfin, Apps/Add apps, All apps, Launcher settings and System settings.
Package flags did not include DEBUGGABLE. No fatal exception or fatal signal was
found in the inspected launcher-process log. This verifies installation and basic
signed-out startup on this modified device, not login, playback or full remote UX.
At that earlier checkpoint Google TV Home was the observed default; no reboot
or default selection was performed by the check. The later role/resolver
discrepancy is recorded above.
Retention across reboot or future firmware updates remains unverified.

## Implemented host-side boundary

The manifest declares standard Home eligibility while preserving ordinary app
entry. Settings opens the standard Home selector; an unavailable selector shows
an error and leaves Android System settings accessible. Public resolver detection
is best effort and may report a vendor proxy or resolver instead of this Activity.
The core launcher has no vendor-launcher disabling, root flow, accessibility redirect, boot
service or automatic default change. Android documents the
[Home selection action](https://developer.android.com/reference/android/provider/Settings#ACTION_HOME_SETTINGS);
Amazon describes the Home key as opening its
[system Home view](https://developer.amazon.com/docs/fire-tv/design-and-user-experience-guidelines.html).
Neither reference establishes replacement support on a particular firmware.

The launcher build declares minSdk 25 and ARMv7, ARM64 and x86_64 ABI filters.
Its measured native baseline is graphics.path and sqliteJni, not a native player.
Host compilation, lint, APK inspection and source review cannot prove install,
low-memory operation, input behavior or replacement Home. Source navigation is
owned by [launcher engineering](engineering.md#standard-home-integration).

## Future authorized manual checks

1. Record hardware/firmware/API/ABI/remote and the exact APK hash; verify install,
   update and restart before claiming ordinary-app support.
2. Verify the complete installed-app inventory, legacy-order migration and Move
   persistence across relaunch. Exercise first/last boundaries and restore the
   starting order after temporary moves. Open normally and check Apps/order, login/cancel/password/IME, settings and
   System settings, disconnected/offline/empty media, cache/account cleanup and
   playback handoff/return. Observe low-memory and process-restart behavior
   separately; no performance or memory result has been measured yet.
3. Open **Choose default Home app** only on the authorized device. Record whether
   a selector exists and whether selection actually changes Home behavior.
4. Compare ordinary root Back with selected-Home root Back; check cold/repeated
   Home from Apps, media, Settings, dialogs and idle/in-flight Login. Confirm Apps
   landing, password disposal, focus and preserved committed login.
5. Check configuration recreation without replaying the original Home reset,
   unavailable-selector messaging, selector return focus, device restart and
   external playback return. Record each result and failure independently.

6. Verify Home/Jellyfin dwell selection and cancellation during rapid traversal,
   immediate Down before dwell, Jellyfin Down to Connect, Right/Left through
   header icons, Connect/cancel, and account-dialog/System settings return.
   Check saved focus/scroll and inactive-page
   disposal; assign, reorder and move rows across tabs in the server editor. Check
   old missing/null tab fields, invalid placement, both-tab account cleanup, and
   disconnected/restoring/cleanup/busy/signed-in-empty/offline states. Confirm
   Jellyfin login success/cancel returns to Jellyfin, actual Home selects Home,
   and logout preserves the tab with a reachable action.

The observations above establish only the named checks; other checklist paths
remain unverified. Device selection/default changes and any
install/launch/input/log collection require explicit authorization before use.

## Optional Home helper

The separate SDK-only helper passed debug compilation, Release lint, minified
Release packaging and independent code review. Its Release APK was installed on
the TiVo; the setup screen rendered, and enabling it through Android accessibility
settings produced a bound service with key-filter capability and no accessibility
event subscription. The user confirmed that pressing Home on the physical TiVo
remote opened JellyScope Launcher. This establishes the observed physical Home
path on this device; the originating app was not specified. Held/repeated Home,
Home from another app, Back preservation, disabled-service fallback and the
helper’s manual Open action remain unverified. Neither manual opening nor
ADB-injected Home establishes interception. No reboot or stock package changes
are part of this qualification. See [setup](home-button-helper.md).

## Background refresh and tab reset

The minified Release passed compilation, lint, independent review and final
verification, then installed and launched on the TiVo. Scrolling Apps and a
Jellyfin media ribbon, switching tabs and returning reset each ribbon to its
first item; Down focused that first item. WorkManager logged one successful
launcher refresh after installation and registered a network-constrained job
with an approximately 30-minute next-run delay. An early forced scheduler attempt
did not produce another Worker run; it was rescheduled. This does not establish
a full background interval, reboot recovery or cancellation-race behavior.
Cached cards remained visible in the inspected screens; exact first-frame and
incremental response timing were not measured. Account-switch/logout and Worker
cancellation paths were source-reviewed, without changing the device account.

## Cinematic hero Release qualification — 2026-10-25

Installed the Release APK built from `b5a9e5f1` in place on TiVo Stream 4K
(Android 12), retaining launcher data. ADB worked after starting its server from
macOS Terminal; the same endpoint was reachable by TCP while the daemon started
from the agent session returned No route to host. Reuse the working server;
this establishes a launch-context difference, not a proven macOS policy cause.

D-pad checks and captured settled screens confirmed compact Home Apps, Home
media metadata with Dusk fallback, Jellyfin cinematic backdrop/details, and a
second asset with matching changed artwork/text and a different ambient tint.
The account panel's Preferences toggle was reachable, disabled the hero, and
remained disabled after a process restart. Re-enabled it before finishing.
Selecting a series opened the TV series Details view without starting playback;
Back returned to the launcher's Jellyfin tab. Focused tiles were visible and did
not overlap neighbors in the captured states.

These are bounded device checks, not frame-by-frame transition/performance proof.
Rapid interrupted-fade smoothness, memory peaks, large-font layout, forced IO
failure, and all tab-return timing permutations remain unmeasured. Series with
zero runtime currently display `0 min` in hero metadata; omit that value in a
follow-up. Private screenshots remain in ignored local task evidence.

## Shared hero and launcher polish — 2026-10-26

Installed both verified Release APKs corresponding to source commit `a59f2ca1`
on TiVo Stream 4K with `adb install -r`, retaining both accounts. Account action
rows remained visibly outlined when unfocused; the cinematic switch changed
Off/On without losing its row focus and was left On. The Home prompt settled
into a centered opaque dialog. Cancel dismissed it; Android Home settings opened
and Back restored the prompt action focus. The external firmware selector remains
full-screen and transparent; its selected radio disagrees with the launcher's
conservative public-resolution status. No default selection was changed.

Captured Home media showed a single ellipsized title, two overview lines and
multiple ribbons. Jellyfin displayed a backdrop and tinted gradient behind its
ribbons; the inspected focused landscape tile did not overlap neighbors. A series
opened seasons/details without playback and exposed a saved Resume position.
Back returned to the Jellyfin tab, but focus was observed on the account icon
rather than the selected series tile: return-focus restoration needs follow-up.
TV Home, its expanded drawer underlay and a focused Library grid rendered artwork
and ambient tint after the shared extraction.

These are bounded D-pad and settled-screen checks. Frame-accurate crossfade
timing/smoothness, short-list final-row alignment, large-font clipping, forced
palette failure, account-switch clearing, TV Collection and Recommended remain
unverified. Injected Home reached the stock launcher; this does not requalify
the separately confirmed physical-remote helper. Private captures stay local.


## Shared ribbons, hero visibility and return focus — 2026-10-26

The launcher and TV Home now consume the same centered lazy-ribbon component.
Both Release APKs installed in place on the TiVo Stream 4K, retaining accounts.
Later launcher-only focus repairs were rebuilt and installed on that device.
With the second and third Jellyfin ribbons focused, settled captures showed
hero title, metadata and overview unobstructed; ribbons overlap only the lower
artwork. Nine Right presses reached and centered a movie beyond the initial
visible tiles.

The first device pass caught incorrect Details-return focus; the next caught a
restored tile below the viewport. After window-owned focus/tab dwell and an
actual-content-focus key for vertical alignment were added, the same second-row,
tenth-movie Details/Back sequence restored the exact focused tile, horizontal
center and fully visible ribbon. Account-panel dismissal returned focus to its
header icon. The gear opened Android system settings and regained focus on Back.
No account, system setting or default-Home selection was changed.

Host verification passed both apps' release lint and APK assembly, affected
ktlint checks, detail JVM tests, the existing TV Home focus-restoration test and
the shared UI iOS simulator framework link. Launcher-only repairs passed focused
formatting, lint and Release assembly; unchanged shared/TV evidence was retained.
Independent final source verification approved the final correction.

These are bounded ADB D-pad and settled-screen observations, not measured remote
latency or recomposition counts. Forced process death, concurrent removal of the
selected item, large-font geometry, TV loading/View All runtime paths and all
lifecycle orderings were not qualified in this pass. Private device captures
remain local. This result supersedes the Details-return focus gap in the prior
polish entry for the observed path.


## Shared hero artwork fallback — 2026-10-26

The launcher and shared media-card mapper now use the same typed artwork
selector: nonblank Backdrop, then nonblank Primary, otherwise no image. Source
review confirmed endpoint/tag pairing, legacy typed-tag compatibility and scene
identity changes, with authentication and account caches unchanged. Both Android
Release builds/lints, affected ktlint, core-model JVM tests, the existing
MediaCardMapperTest and shared UI iOS simulator framework link passed.

The launcher Release installed in place on the TiVo, retaining its session.
Focused Play Next assets (a video and an episode) and two Up Next episodes all
rendered matching hero artwork and ambient tint. Changing focus updated the
image and copy. Private settled captures remain local. Live server image tags
were not inspected, so these observations establish rendering for the sampled
items, not which selection branch each exercised or coverage of every asset.
The primary-only and missing-tag branches were traced in source; no new fixture
or test harness was introduced. The matching TV Release was built but not
reinstalled in this pass.
