# Android launcher qualification

The launcher targets Android TV/Google TV and Android-based Fire OS at API 25 or
newer. Vega OS, Fire OS 5/API 22, and older Android are excluded. These are support
targets, not a list of qualified devices. No device has been operated for this
implementation; hardware/firmware details and device-action authorization are
pending. Local APKs are debug-signed verification artifacts, not releases.

## Evidence levels

| Level | What must be established | Current device result |
| --- | --- | --- |
| Installation | Install/update on the identified API/ABI/firmware | Not run |
| Ordinary app | Login, Apps/pins, media/cache, remote focus, settings and playback handoff | Not run |
| Replacement Home | Selector support, actual default, cold/repeated Home and Back/task behavior | Not run |

Device model, firmware/OS, API level, ABI, remote model, artifact hash and setup
steps are currently **unknown**. Record those values alongside each future result;
do not transfer a result to another firmware/model or equate installation with
Home replacement. Emulator evidence, if later authorized, is separate from physical
TV/Fire TV evidence.

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
owned by [TV UX](../engineering/tv-ux-behaviors.md#standard-home-integration).

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

No steps above have been executed. Device selection/default changes and any
install/launch/input/log collection require explicit authorization before use.
