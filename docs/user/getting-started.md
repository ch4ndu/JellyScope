# Getting Started

[All user guides](../USAGE.md)

## Install and upgrade

Use the build supplied for your platform; a source checkout does not imply a
public download or store listing. Android phone and TV packages share an app ID
and cannot coexist on one device. Updating requires the same variant and signing
key. Uninstalling to change signing deletes local data and downloads. Old builds
using `com.jellyscope` are a separate app and do not migrate automatically.

On macOS, quit JellyScope before replacing the app in Applications. iPhone/iPad
updates follow the supplied TestFlight or provisioned-install route; an arbitrary
IPA or simulator build is not a general installer. Apple TV remains an unsupported
preview. See [build artifacts and installation](../BUILD.md#installing-and-upgrading)
for local build paths and developer install commands, and [supported platforms](../../README.md#platforms)
before choosing a package.

## Connect, browse, and find content

Add a server by discovery where available or enter its URL, then use a password
or Quick Connect. For multiple accounts, use **Switch** on phones, tablets, and
macOS; **Switch Users** on Android TV; or **Settings → Manage Accounts** on
Apple TV. The TV tile opens **Switch account**.

Choose a library from the **Library** title on phones, tablets, and macOS, or
from the Android TV drawer. Movie and show libraries offer **Recommended** and
**Library**. In **Library**, use **Sort** and **Filters**; deselect filters and
choose **Apply**, or use Android TV's **Clear filters**. Available choices vary
by library. Touch/desktop layouts also offer grid and list modes.
**Appearance → Remember last library view** controls restoration.

Detail pages provide **Favorite** or **Unfavorite** and **Mark watched** or
**Mark unwatched**. Favorites appear on Home and through the library's favorite
filter. Apple TV also has a dedicated **Favorites** tab. Changing watched state
can change whether a title appears in **Continue Watching**.

**Find** searches titles, collections, and people on shared layouts and Android
TV. Phones, tablets, and macOS also offer mood/genre, runtime, watched, and
person filters; cast links open person pages. **Discover** contains genres,
studios, collections, suggestions, and upcoming episodes. Apple TV instead has
Home, Favorites, Libraries, Search, Downloads, and Settings tabs; Libraries
includes sort/filter views, Search accepts text/year, person, genre, runtime,
and watched filters, and there is no separate Discover tab.

### Choose a media version

On phones, tablets, macOS, Android TV, and Apple TV, a movie or episode with at
least two valid sources shows **Version** on its detail page. Choose the version
before **Play** or **Download**, then check the audio tracks, subtitle tracks,
and media information for that source. The choice applies to the current detail
and playback session; it is not a global preferred-version setting. The picker
is hidden when only one source is available. For a series or season, make the
choice on the concrete episode where it is offered.

## Kids viewing

JellyScope selects Kids viewing on Android/iOS phones and tablets from the
account's parental rating. It plays one chosen video at a time: recommendations
are choices, not an autoplay queue. The compact watch page supports selecting
another title, fullscreen, and permitted downloads. The normal player's queue,
Up Next, and automatic episode advance do not apply. See [Kids engineering](../engineering/kids-viewing.md)
for eligibility and lifecycle details.

## Account changes and saved files

Switching accounts preserves their downloads. Removing an account or signing out
can remove downloaded media after confirmation; see [download removal](downloads.md).
Refreshing download permission requires authenticating the same account again,
not removing it. Downloaded OpenSubtitles files have a separate lifecycle and
remain until explicitly cleared through subtitle settings.
