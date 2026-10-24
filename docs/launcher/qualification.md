# Android launcher qualification

The launcher targets Android TV/Google TV and Android-based Fire OS at API 25 or
newer. Vega OS, Fire OS 5/API 22, and older Android are excluded. These are support
targets, not a list of qualified devices. An authorized TiVo installation and cold launch passed after user removal of
CoreService; full ordinary-app and replacement-Home behavior remain unqualified. Local APKs are debug-signed verification artifacts, not releases.

## Evidence levels

| Level | What must be established | Current device result |
| --- | --- | --- |
| Installation | Install/update on the identified API/ABI/firmware | TiVo: install passed after CoreService removal; update/reboot not tested |
| Ordinary app | Login, Apps/pins, media/cache, remote focus, settings and playback handoff | Cold launch/Essentials screen passed; remaining flows not run |
| Replacement Home | Selector support, actual default, cold/repeated Home and Back/task behavior | Not run |

Device identity and artifact details for the first attempt are recorded below;
remote model remains unknown. Record these values alongside each future result;
do not transfer a result to another firmware/model or equate installation with
Home replacement. Emulator evidence, if later authorized, is separate from physical
TV/Fire TV evidence.

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
Google TV Home remained default; no reboot/default selection was performed.
Retention across reboot or future firmware updates remains unverified.

## Implemented host-side boundary

The manifest declares standard Home eligibility while preserving ordinary app
entry. Settings opens the standard Home selector; an unavailable selector shows
an error and leaves Android System settings accessible. Public resolver detection
is best effort and may report a vendor proxy or resolver instead of this Activity.
There is no vendor-launcher disabling, root flow, accessibility redirect, boot
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
2. Open normally and check Apps/pins, login/cancel/password/IME, settings and
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

6. Verify Essentials/Jellyfin tab selection, saved focus/scroll and inactive-page
   disposal; assign, reorder and move rows across tabs in the server editor. Check
   old missing/null tab fields, invalid placement, both-tab account cleanup, and
   disconnected/restoring/cleanup/busy/signed-in-empty/offline states. Confirm
   Jellyfin login success/cancel returns to Jellyfin, actual Home selects Essentials,
   and logout preserves the tab with a reachable action.

Only the installation/launch attempt documented above has been executed; the
remaining manual checks have not run. Device selection/default changes and any
install/launch/input/log collection require explicit authorization before use.
