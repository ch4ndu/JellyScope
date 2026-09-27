# Native tvOS UI

Engineering contract. See the [documentation map](../README.md) for related
owners and the [user guides](../USAGE.md) for usage instructions.

## Native tvOS screens

The SwiftUI shell uses `TabView`/`NavigationStack`, with a tvOS 18 sidebar and
tvOS 17 tabs ordered Home, Favorites, Libraries, Search, Downloads, Settings.
Favorites has its own history and reuses View All. Refresh keeps cards mounted,
reports failure inline, restores surviving focus, and supersedes older re-entry
refreshes. System Back pops the current destination. Browsing state follows the
[shared account lifecycle](accounts-and-persistence.md#account-boundary-and-lifecycle).
App-global appearance watches live outside the account-keyed subtree: Ocean,
Midnight and Ember colors and Small/Medium/Large card sizes update browsing and
settings through a native environment. Resizing preserves aspect ratios, clipped
viewports, focus reserve and stable media identity; player video remains black.

Home orders Continue Watching, Favorites, Next Up, Recently Added. Ribbons load
and retry independently; empty rows collapse and refresh retains cards. The hero
follows settled media focus, remains above the clipped shelves, preserves its
last item during control focus, and respects Reduce Motion.

Libraries opens a collection-specific hub with Recommended and Library views
where supported. Recommendation sections load and retry independently. The
Library view retains a paged grid, collection-specific sort choices, and draft
filters with Apply, Reset, and Cancel. Sort and the optional last inner view use
shared preferences. Paging tracks consumed server offsets independently of
card deduplication; re-entry refresh is bounded to 200 consumed items. A newer
query or page invalidates older refresh results. The grid clips beneath its
controls and the fixed hero; horizontal ribbons reserve room for focus scaling.

Media focus identity is `(ribbon, item)`. Return reveals the saved target before
focus, falling back within its ribbon. Select opens detail; Play/Pause starts a
movie/episode at resume. View All uses the bounded query. Swift owns rendering
and native focus; Kotlin owns business projection. Android focus belongs to the
TV UX guide.

Item detail presents readable metadata, source-qualified version/audio/subtitle
choices, media information, people, and related titles. Play, Resume, and
Restart follow the shared resume decision. Watched applies to movies and
episodes; an explicit change clears the launch bookmark, and a failed change
restores the confirmed watched status and progress. Series exposes favorite.
Explicit track choices travel with the
playback route and are validated against its original item and source. Local
subtitle handoff keeps Play, Restart, and Download mounted but disabled until
selection settles; failures require a new subtitle or source choice. A person
credited in both Cast and Crew remains in both sections, with distinct role
labels preserved within each section.

Series opens seasons and Next Up; an explicit season keeps precedence over
Next Up. Account-qualified final Stop settlement refreshes retained detail,
relevant episodes and Next Up, including when settlement follows the initial
return refresh. Refresh preserves visible content and explicit choices.
Episode Select opens detail, while Play/Pause and row actions target
that episode. Season errors and item-action failures remain visible and
retryable. Person links open a header and paged Movies/Shows filmography.
Detail headers scroll with content; the fixed browsing hero is specific to
Home and Libraries.

Search uses the system field and keyboard for the full Find query: text/year,
person, genre, runtime, and watched status. Person and filter-only requests work
without free text; keyboard edits clear a person selection while retaining
questionnaire filters. All, Movies, Shows, and Episodes select cached result
sections. Query, filters, results, focus, and navigation survive detail/player
return and tab changes. Recent queries are account-scoped and recorded on
submit, recent selection, or person selection; filter edits never add blank
recents. Failed searches retain useful results with Retry.

Settings uses native forms and navigation-link pickers for supported playback,
language, segment, browsing, appearance, subtitles, diagnostics, and account
controls. A failed playback-preference load shows Retry and keeps only playback,
language, and segment controls disabled until a successful read; recovery retains
the last confirmed values. Remember Library Tab uses the
shared preference; autoplay-next controls automatic advancement while keeping
manual Next available. Delay and still-watching preferences use the shared
playback writer. Diagnostics disclosure and open-source notices remain
reachable. Account information and Manage Accounts show each configured server
URL, including its base path. Account switching and confirmed removal use shared Actions; removal
shows the captured account's download count/bytes, refreshes stale previews,
blocks leased artifacts, and releases canceled previews. Native dismissal cannot
cancel a consumed confirmation; Cancel and Back still release an unconfirmed
preview. Add Account presents
the same login flow, with cancellation preserving the session.

Login keeps manual entry beside capability-gated discovery. Sign-in offers Quick
Connect or password. Back cancels sign-in, clears the password, and returns while
retaining discovered rows. Idle discovery offers Search Again; failure offers
Retry and never blocks manual entry. Shared session state owns successful routing.

Player components separate the installed native host, transport and panels.
Queue offers the current item, direct selection, previous/next and shuffle.
Next-up offers identity/artwork, Play Now and Dismiss; completion countdown and
still-watching semantics are owned by
[native tvOS playback](apple-playback.md#tvos-native-player). Modal precedence is
action/error, still-watching, a user-opened panel, then next-up. Back closes the
topmost panel and returns focus to its invoker; unobscured Back closes playback.
Progress updates do not steal focus or rebuild AVKit menus. Controls expose
speed, supported subtitle style, AVKit video sizing and sanitized diagnostics.
Timing and VLC video sizing are explicitly unavailable.

OpenSubtitles settings provide a masked consumer-key editor, result preference
and confirmed local-asset clearing. Movie/episode detail and online playback
open account/item/source-qualified search. Results show loading, empty, failure,
unsupported, quota and installation states; local rows offer Select, Delete and
Retry Sync. Off clears selection. Offline playback has no remote subtitle-search
entry or arbitrary file import.

Downloads has its own tab and navigation path. Movie/episode detail previews the
current version and track selection for Original or supported converted quality
before enqueue. The local list groups completed, active, queued, paused and
failed records and provides guarded playback, pause/resume/retry, Resume All,
explicit Resume Queued Downloads with pending and retryable failure states,
Cancel/Delete and storage allocation controls. Destructive actions confirm and
respect active leases. Retained-account access survives server failure; the UI
explains Apple TV's reclaimable storage and app-active transfer limit. The
[download contract](downloads.md#downloads-and-offline) owns those rules.

## Why

- Native tvOS keeps SwiftUI adapters/focus with feature views and business state
  in Kotlin presenters. Qualified routes, settlement refresh, and paired
  watched/progress rollback preserve retained detail state.
