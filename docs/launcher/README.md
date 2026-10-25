# JellyScope Launcher

JellyScope Launcher is a separate Android TV Home app for opening installed apps
and browsing Jellyfin media. It fetches media directly from Jellyfin; the optional
server plugin controls row layout. Playback opens the separate JellyScope TV app.

Technical contracts: [engineering](engineering.md) and [plugin reference](plugin-reference.md).

## Requirements and current status

- Targets Android TV, Google TV and Android-based Fire OS on **Android API 25+**;
  ARMv7, ARM64 and x86_64 builds. Vega OS and Fire OS 5/API 22 are excluded.
- Plugin source targets **Jellyfin Server 12.3**, `Jellyfin.Controller` **12.3.0**,
  and **.NET 10**. Other 12.x versions are unqualified.
- See [qualification](qualification.md) for host, device and server evidence.
- Current APKs are debug-signed local verification artifacts. They lack release
  license/readiness wiring and are not collected by `scripts/build-release-artifacts.sh`.
  See [device qualification](qualification.md) for tested paths and current Dusk status.

Optional [Home-button helper setup](home-button-helper.md) covers firmware that ignores Home selection.

## Screens and everyday use

| View | Purpose |
| --- | --- |
| Home | Apps first; default media rows are Play Next, Up Next and Favorites |
| Jellyfin | Media only; default Recently Added, or a connection prompt when signed out |
| Account dialog | Refresh, layout/cache status, account cleanup and Home selection |
| Sign-in dialog | Server URL, username and password |

Tabs select after 250 ms of focus; Center selects immediately. Left/Right traverses
tabs and icons; Down enters content. Switching tabs resets ribbon scroll and focus
to the beginning. Cached rows remain visible while fresh data loads; rows update
as responses arrive. Refresh also runs roughly every 30 minutes, including in
the background when Android permits and a network is available. The Jellyfin icon opens account controls;
the gear opens Android **System settings** directly. Account and sign-in dialogs
keep focus inside until closed; Close returns to the invoking control.

**Apps** automatically lists every launchable app. Long-press for Open or **Move**;
Left/Right reorders inline, and Select or Back finishes with each step saved.
Existing favorites seed the order; other apps append. Order survives sign-out.
New apps appear after resume/Refresh; pruned/reinstalled apps append.

Open **Jellyfin → Connect to server**, enter the server URL and credentials, then
choose **Sign in**. HTTPS is preferred; HTTP is unencrypted. Success and Cancel
both return to Jellyfin. Login opened from Settings cancels back to Settings.
Select a field to open the keyboard; Back hides it before cancelling.
Passwords are transient; the launcher keeps its own saved session, separate from
JellyScope's player account.

Install/update JellyScope TV and sign into the **same server and user in both
apps**. Selecting a series opens its seasons/episodes; other media opens Details.
Choose Resume there to continue from the saved position, or restart explicitly.
An older TV app shows update guidance instead of starting playback. Back from the initial Details/Series screen returns to the launcher; nested
screens unwind first.
Missing apps or mismatched accounts need setup and a fresh selection.

**Choose default Home app** opens Android's selector when firmware permits it.
Device Home selects Home and Apps. Back from Jellyfin content first returns
to its tab, then Home. At Home root, Back stays when this app is the
reported default Home, otherwise ordinary Activity exit applies. Some firmware,
including Fire TV variants, may not allow replacement Home. System settings is
always a separate action.

## Build and install the launcher

Use the repository's [Android toolchain](../BUILD.md#toolchain). From its root:

```sh
./gradlew :android-launcher-app:assembleDebug
./gradlew :android-launcher-app:assembleRelease
```

Outputs are `android-launcher-app/build/outputs/apk/debug/android-launcher-app-debug.apk`
and `android-launcher-app/build/outputs/apk/release/android-launcher-app-release.apk`.
Application ID: `com.udnahc.jellyscope.launcher`.

For a chosen development device with ADB authorized, install the local Release APK:

```sh
adb -s DEVICE_SERIAL install -r android-launcher-app/build/outputs/apk/release/android-launcher-app-release.apk
```

Replace `DEVICE_SERIAL` with that device's serial, open **JellyScope Launcher** from
its apps list, then connect. Select it as Home only if desired and supported.
For TiVo CoreService installation restrictions, see [qualification](qualification.md#tivo-stream-4k-installation-attempt--2026-10-23).

## Install the Jellyfin plugin

The plugin is optional: without it, the launcher uses its default media layout.
There is **no published catalog entry, repository feed or installable release ZIP**.
Manual installation remains unverified.

**Update the launcher before installing plugin 0.2.0.** This plugin emits schema 2
for every layout. Older launchers retain their cached/default layout instead of
accepting it. The updated launcher also supports the previous plugin.

1. Check the server version first. Use a backed-up development **12.3** server for
   initial qualification; lower 12.x versions are not qualified. Install a .NET 10
   SDK on the build machine. From the repository root run:

   ```sh
   dotnet build jellyfin-launcher-plugin/Jellyfin.Plugin.Launcher.csproj --configuration Release --artifacts-path .local/launcher-plugin-artifacts
   ```

   Output: `.local/launcher-plugin-artifacts/bin/Jellyfin.Plugin.Launcher/release/`.
   The plugin is `Jellyfin.Plugin.Launcher.dll`, version **0.2.0**. Its dashboard
   HTML is embedded; no separate web-page installation is needed.
2. Locate the running server's actual plugins directory and stop that server or
   container. Common examples are `/var/lib/jellyfin/plugins` on Linux,
   `%ProgramData%\Jellyfin\Server\plugins` for Windows tray installs, and
   `%UserProfile%\AppData\Local\jellyfin\plugins` for direct Windows installs.
   Containers use the plugins directory in their persistent volume.
3. Create a dedicated `JellyScopeLauncher_0.2.0.0` subdirectory there. Copy
   `Jellyfin.Plugin.Launcher.dll` and its generated `.deps.json` from the output.
   The `.pdb` is optional debugging information. Do not copy server/framework
   assemblies from NuGet; Jellyfin supplies those. Give the server service account
   access to the plugin directory and permission to maintain plugin metadata.
4. Restart Jellyfin. In its administrator dashboard, open **Plugins**, confirm
   **JellyScope Launcher** is loaded/enabled, then open its configuration page.
   If absent or failed, inspect server startup logs, version and file permissions.
   `meta.json` is not packaged; 12.3 loader behavior remains unqualified.
   Copying the DLL does not prove loading. Never force incompatible plugins Active.
5. Save a small layout, then use **Refresh** in Launcher settings. Confirm
   **Current server configuration** and the expected rows. Also check a restricted
   user's library visibility before deploying broadly.

Jellyfin documents its [manual plugin directory/restart procedure](https://jellyfin.org/docs/general/server/plugins/#manual).
Its [current plugin manager source](https://github.com/jellyfin/jellyfin/blob/master/Emby.Server.Implementations/Plugins/PluginManager.cs)
contains manifest handling and fallback discovery; that moving source is not
proof of this plugin loading on the target server.

To update, stop Jellyfin, back up the existing plugin/configuration, replace the
plugin build, restart and repeat the checks. To roll back, restore the previous
build/configuration. To remove, stop Jellyfin and remove only this plugin's folder;
retain configuration XML if settings should survive reinstall. A launcher may
retain its last-good layout after removal rather than immediately showing defaults.

## Customize the server layout

Open **Dashboard → Plugins → JellyScope Launcher** as an administrator. One layout
is shared by all launcher users/devices; inaccessible library rows are omitted per
user. Changes take effect after **Save** and launcher **Refresh**.

| Control | Available customization |
| --- | --- |
| Add row / Remove | 0–12 rows total across both tabs, including hidden rows |
| Title | 1–80 characters; control characters/line separators are rejected |
| Visible | Show/hide a row without deleting its configuration |
| Tab | Dashboard Essentials means Home; Jellyfin means Jellyfin |
| Query | Play Next, Up Next, Recently Added, Recently Added Movies/Episodes/Series, Favorites or Newest items in library |
| Library | All available libraries or one accessible library; Library query requires one |
| Aspect | Landscape, Poster or Square artwork |
| Limit | 1–20 cards per row |
| Up / Down | Order rows; each tab preserves their relative order |
| Playback app | JellyScope only; other players are not supported |

**Play Next** uses resumable media; **Up Next** excludes resumable episodes.
**Recently Added** uses Jellyfin's Latest endpoint and its episode grouping.
**Recently Added Movies**, **Recently Added Episodes** and **Recently Added Series**
filter to that type, newest-first by creation date. Episodes appear individually;
Series means newly added series, not older shows with new episodes. Each can use
all available libraries or one selected library. **Favorites** filters favorite
items. **Newest items in library** (the `library` query) requires a library and
lists its movies/episodes recursively, newest-first by creation date. These are Jellyfin queries, not Android's
cross-app Watch Next feed.

Example: **New Movies** → Jellyfin → Recently Added Movies → Movies library → Poster → 12.
Save, then Refresh. Apps stays first.

New rows start on Jellyfin with Recently Added, landscape artwork and limit 20.
Changing Query preserves an explicitly selected Tab. **Refresh libraries** updates
choices while preserving the draft; **Reload saved layout** discards draft edits.
Save failures preserve the draft. Unavailable libraries remain selectable as
unavailable entries until replaced or removed. Saving zero rows intentionally
removes media rows; it does not remove Apps or the Jellyfin empty-state actions.

The plugin cannot change app order, tab names/count, login, system settings,
wallpaper/theme, arbitrary filters/scripts/URLs, or per-device/per-user layouts.


## Offline use and troubleshooting

- Missing/invalid/unavailable plugin configuration uses a validated saved layout,
  or defaults if none exists. Check the layout status in Settings.
- Cached media may remain visible offline with a stale indicator; it is not
  downloaded video. Empty successful results replace old cards.
- A signed-in empty Jellyfin tab offers Refresh/Settings, not another login prompt.
  Check row visibility, tab assignment, library access and available media.
- **Retry sign out** finishes failed account cleanup; app order is retained. Interrupted
  refresh/storage failures can withhold old cached rows until safe refresh/cleanup.
- Recheck permissions when rows disappear. Offline data cannot discover new remote
  access restrictions until a server check succeeds.
