# JellyScope Launcher

JellyScope Launcher is a separate Android TV Home app for opening installed apps
and browsing Jellyfin media. It fetches media directly from Jellyfin; the optional
server plugin controls row layout. Playback opens the separate JellyScope TV app.

Technical contracts: [engineering](../engineering/launcher.md) and [plugin reference](../engineering/launcher-plugin.md).

## Requirements and current status

- Targets Android TV, Google TV and Android-based Fire OS on **Android API 25+**;
  ARMv7, ARM64 and x86_64 builds. Vega OS and Fire OS 5/API 22 are excluded.
- The optional plugin targets **Jellyfin Server 12.3**. Other versions are unqualified.
- There is no published launcher release. Local builds have limited device
  testing; setting the launcher as Home does not work on every firmware. See
  [support and verification limits](../engineering/launcher-verification.md).

Optional [Home-button helper setup](home-button-helper.md) covers firmware that ignores Home selection.

## Screens and everyday use

| View | Purpose |
| --- | --- |
| Home | Apps first; default media rows are Play Next, Up Next and Favorites |
| Jellyfin | Media only; default Recently Added, or a connection prompt when signed out |
| Jellyfin account panel | Refresh library, Connection details, Launcher preferences and sign-out |
| Sign-in dialog | Server URL, username and password |

Focus a tab or press Center to select it; Left/Right traverses tabs and icons,
and Down enters content. Tab changes return ribbons to their beginning. The
Jellyfin icon opens account controls; the gear opens Android **System settings**.
Back leaves a subsection; Close returns to its invoking control.

Disable cinematic previews in **Jellyfin account → Launcher preferences → Show
cinematic hero**. Media refreshes on entry, manually and when Android permits
background work; [engineering](../engineering/launcher.md#android-launcher-queries-and-artwork)
owns refresh/caching behavior.

**Apps** automatically lists every launchable app. Long-press for Open or **Move**;
Left/Right reorders inline, and Select or Back finishes with each step saved.
App order survives sign-out. New or reinstalled apps appear at the end after
returning to the launcher or choosing Refresh.

Open **Jellyfin → Connect to server**, enter the server URL and credentials, then
choose **Sign in**. HTTPS is preferred; HTTP is unencrypted. Success and Cancel
both return to Jellyfin. Login opened from Settings cancels back to Settings.
Select a field to open the keyboard; Back hides it before cancelling.
The launcher does not save your password. Its sign-in is separate from the
JellyScope TV app.

Install/update JellyScope TV and sign into the **same server and user in both
apps**. Selecting a series opens its seasons/episodes; other media opens Details.
Choose Resume there to continue from the saved position, or restart explicitly.
An older TV app shows update guidance instead of starting playback. Back from the initial Details/Series screen returns to the launcher; nested
screens unwind first.
Missing apps or mismatched accounts need setup and a fresh selection.

**Choose default Home app** opens a dialog showing current status. Choose
**Open Android Home settings** to open Android's selector when firmware permits it.
Device Home selects Home and Apps. Back from Jellyfin content first returns
to its tab, then Home. At Home, Back stays in the launcher when Android reports it as the default
Home app; otherwise Back exits it. Some firmware,
including Fire TV variants, may not allow replacement Home. System settings is
always a separate action.

## Build and install the launcher

Local APK build and installation commands are in the [launcher build guide](../engineering/launcher.md#build-and-install).

## Install the Jellyfin plugin

The plugin is optional: without it, the launcher uses its default media layout.
There is **no published catalog entry, repository feed or installable release ZIP**.
Manual installation remains unverified.

**Update the launcher before installing plugin 0.3.0.** Older launchers cannot
use its layouts. The updated launcher also works with the previous plugin.

1. Obtain a matching plugin build using the [engineering build instructions](../engineering/launcher.md#build-the-server-plugin).
2. Locate the running server's actual plugins directory and stop that server or
   container. Common examples are `/var/lib/jellyfin/plugins` on Linux,
   `%ProgramData%\Jellyfin\Server\plugins` for Windows tray installs, and
   `%UserProfile%\AppData\Local\jellyfin\plugins` for direct Windows installs.
   Containers use the plugins directory in their persistent volume.
3. Use the existing plugin subdirectory, or create `JellyScopeLauncher_0.3.0.0`. Copy
   `Jellyfin.Plugin.Launcher.dll` and its generated `.deps.json` from the output.
   The `.pdb` is optional debugging information. Do not copy server/framework
   assemblies from NuGet; Jellyfin supplies those. Give the server service account
   access to the plugin directory and permission to maintain plugin metadata.
4. Restart Jellyfin. In its administrator dashboard, open **Plugins**, confirm
   **JellyScope Launcher** is loaded/enabled, then open its configuration page.
   If absent or failed, inspect server startup logs, version and file permissions.
   `meta.json` is not packaged; 12.3 loader behavior remains unqualified.
   Copying the DLL does not prove loading. Never force incompatible plugins Active.
5. Save a small layout, then use **Refresh library** in Jellyfin account. Confirm
   **Current server configuration** and the expected rows. Also check a restricted
   user's library visibility before deploying broadly.

Jellyfin documents its [manual plugin directory/restart procedure](https://jellyfin.org/docs/general/server/plugins/#manual).
For stale version/status after replacement, inspect `meta.json` and startup logs.
Folder names do not update manifest metadata.

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
| Query | Play Next, Up Next, Recently Added, Recently Added/Released Movies/Episodes/Series, Favorites or Newest items in library |
| Library | All available libraries or one accessible library; Library and Released queries require one |
| Aspect | Landscape, Poster or Square artwork |
| Limit | 1–20 cards per row |
| Up / Down | Order rows; each tab preserves their relative order |
| Playback app | JellyScope only; other players are not supported |

**Play Next** uses resumable media; **Up Next** excludes resumable episodes.
Recently Added uses server addition dates; Recently Released uses the selected
library's premiere dates and excludes unknown/future dates. Series rows describe
series, not newly added/released child episodes. Released rows may underfill.
These are Jellyfin queries rather than Android's cross-app Watch Next feed; see
[query semantics](../engineering/launcher-plugin.md#wire-schema) for exact behavior.

New rows start on Jellyfin with Recently Added, landscape artwork and limit 20.
Changing Query preserves an explicitly selected Tab. **Refresh libraries** updates
choices while preserving the draft; **Reload saved layout** discards draft edits.
Save failures preserve the draft. Unavailable libraries remain selectable as
unavailable entries until replaced or removed. Saving zero rows intentionally
removes media rows; it does not remove Apps or the Jellyfin empty-state actions.

The plugin cannot change app order, tab names/count, login, system settings,
wallpaper/theme, custom filters or links, or per-device/per-user layouts.

## Offline use and troubleshooting

- Missing/invalid/unavailable plugin configuration uses a validated saved layout,
  or defaults if none exists. Check the layout status in Jellyfin account → Connection details.
- Cached media may remain visible offline with a stale indicator; it is not
  downloaded video. Empty successful results replace old cards.
- A signed-in empty Jellyfin tab offers Refresh/Settings, not another login prompt.
  Check row visibility, tab assignment, library access and available media.
- **Retry sign out** finishes a failed sign-out; app order is retained. If cached
  rows disappear after a storage or refresh failure, retry Refresh.
- Recheck permissions when rows disappear. Offline data cannot discover new remote
  access restrictions until a server check succeeds.
