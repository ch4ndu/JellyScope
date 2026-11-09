# Subtitle Selection And Assets

Engineering contract. See the [documentation map](../README.md) for related
owners and the [user guides](../USAGE.md) for usage instructions.

## Direct OpenSubtitles And Local Subtitle Assets

- JellyScope talks directly to `https://api.opensubtitles.com/api/v1` through a
  dedicated `OpenSubtitlesApi`/repository boundary. The API lives in `transport`;
  the account-qualified repository and configured clients remain in core. API requests send only the
  persisted user-supplied consumer key, or the local development fallback when no
  persisted key exists, and the JellyScope User-Agent; the separate download
  client never receives OpenSubtitles or Jellyfin credentials. Download links
  must remain HTTPS on an allowlisted OpenSubtitles host across at most three
  redirects. Search follows at most one canonical redirect only when it remains
  HTTPS on the exact API host and search path; the consumer key is never sent to
  an unvalidated destination. Neither URLs, keys, response bodies, titles, nor
  file paths may be logged. Search diagnostics may log only the closed request
  operation and identity-free query shape, numeric HTTP status, coarse
  content-type category, result and query counts, and exception and
  immediate-cause type names.
- OpenSubtitles configuration is device-global and survives Jellyfin logout.
  The consumer key is the only secret value; the result-order preference is
  persisted beside it as `NoPreference`, `PreferHearingImpaired`, or
  `PreferForced`, with missing or unknown values falling back to `NoPreference`.
  It uses the existing platform `SecureStore` implementations, including their
  documented platform limitations. The optional ignored repo-root development
  property is a local runtime fallback only: it never changes the persisted
  getter or settings surface, and it is never logged. Search starts from movie
  or concrete episode detail, prefers IMDb plus season/episode identity, falls back to movie
  title/year or series title/season/episode identity, normalizes the preferred
  language to OpenSubtitles codes, and keeps search out of the in-player picker.
  Episode title fallback omits the episode's production year because the
  provider interprets a title-query year as the series year.
- Results are ranked before display, never left in server order: query
  specificity first (IMDb plus season/episode outranks IMDb alone, which
  outranks the title fallback), then installable results ahead of
  unavailable ones, then an explicit hearing-impaired or forced preference
  match, normalized release-name similarity to the selected source basename,
  OpenSubtitles' trusted flag, rating, download count, and original API
  encounter order as the final deterministic tie-breaker. Preference and
  basename evidence are neutral when absent. The preference only reorders; it
  never filters results. Nullable optional provider flags default false at the
  DTO boundary, and incomplete result or file rows are skipped without failing
  valid siblings. The selected source's full path is reduced to a
  basename at the DTO-to-domain boundary and is never sent to OpenSubtitles,
  logged, diagnosed, displayed, or persisted. The first result is only the
  best-ranked candidate. JellyScope never presents it as a confirmed match for
  the item, never auto-installs it, and always leaves the choice with the user.
- The consumer key is supplied per installation: embedding a shared key would
  expose shared quota and require distribution, rotation and revocation policy.
  The ignored development fallback is not distributable configuration.
- Search state retains raw keyed results. `LazyColumn.items` receives those
  results directly with provider file-ID keys, and localization plus row-model
  materialization happens only inside the composed item content. This preserves
  ordering, quota, focus, errors, and install behavior without eagerly mapping
  offscreen rows.
- Quota handling treats the API's returned remaining-download/reset values as
  authoritative; never hardcode authenticated quota counts. Anonymous guidance
  is five downloads per 24 h per IP (version-bound to OpenSubtitles' current
  public policy). Surface quota state to the user after a download.
- Only single-file SRT and WebVTT results are installable. Downloads are capped
  at 5 MiB, reject empty/HTML/unknown content, decode supported UTF encodings,
  and normalize to canonical UTF-8 WebVTT in persistent app-private storage.
  Local payload decode/canonicalization and final install WebVTT byte encoding,
  fetched-server canonicalization, and upload Base64 all run on the injected
  worker dispatcher. Network calls remain outside those CPU blocks and the
  mutation owner. The raw `ByteArray` download carrier is data-owned, never a
  compiler-declared stable domain model. Remote filenames are never used as
  paths. Metadata and selection are keyed by server, user, item, and media
  source; the file and Room row survive Jellyfin logout until explicit per-file
  or settings-wide deletion.
- Asset metadata retains source/account ownership, provider identity, opaque file
  identity, display/format flags, usage timestamps and sync confirmation/baseline.
  Add fields only with behavior that reads them. Source FPS remains transient
  search metadata; raw source paths never enter the row.
- A local selection is `SubtitleSelectionIntent.LocalAsset`, not a synthetic
  Jellyfin stream index. The plan carries a sealed `SubtitleAsset` that
  distinguishes authenticated `JellyfinRemote` resources from verified
  `LocalFile` assets. Android resolves local files into Media3 subtitle
  configurations, iOS inserts a local WebVTT legible track into its AVFoundation
  composition, and desktop passes the app-owned path to mpv `sub-add`; no path
  receives Jellyfin headers. Off removes the local track immediately. A local
  activation failure is nonfatal and never enters Jellyfin ForceEncode because
  the server does not own the file.
- One application-scoped `LocalSubtitleMutationCoordinator` FIFO actor is the
  sole writer of local subtitle files, asset metadata, and selections. The raw
  platform Room selection store is qualified in DI; the ordinary
  `SubtitleSelectionStore` contract is a coordinator-backed facade so normal
  writes and account/server/full cleanup cannot bypass the owner. Immediate
  work reserves its monotonic logical ticket and enters the FIFO in one short
  lock-protected submission step. Physical FIFO order governs persistence,
  while per-key intent tickets and key/account/server/global barriers govern
  whether delayed work remains valid.
- Install reserves before download and normalization, then writes file -> asset
  -> conditional selection. A genuinely newer selection prevents auto-select
  but keeps a successfully installed asset; a newer delete, clear, account,
  server, repair, or reconciliation barrier prevents an older delayed write
  from resurrecting state. Delete and missing-file repair clear the matching
  selection before asset metadata and file. Reconciliation snapshots and
  mutates in one actor request, and duplicate metadata whose file is missing is
  repaired by recreating the file rather than returning a dangling asset. Each
  platform file store treats an already-absent target as successful deletion;
  when deletion reports failure and the target remains, it throws exactly
  `Unable to delete local subtitle file.` without a file identity or path.
- Mutation requests distinguish queued, started, cancelled, and completed
  state. Cancellation before start skips persistence. Cancellation after start
  waits for non-cancellable in-actor compensation, which attempts every
  applicable cleanup, clears only the matching selection, preserves the
  original failure or cancellation, and settles before the caller rethrows. A
  failed or cancelled sync-state upsert restores its exact captured prior row
  only while the same asset generation and exact claim/order still own it;
  for terminal or inapplicable updates, committed success and compensation share
  one finalization winner, and only that winner owns release. Shutdown and
  undelivered work complete terminally without leaving a FIFO gap.
- Newly installed assets enter an app-scoped, sequential Jellyfin sync
  coordinator. Automatic upload is limited to a single source or the selected
  primary source because Jellyfin's upload endpoint has no media-source
  parameter; alternate sources remain `LocalOnlyAlternateSource`. Permission is
  checked from the current-user policy. Before POST and after any successful or
  ambiguous dispatch, compare candidate server WebVTT by canonical content and
  language/forced/hearing-impaired flags. Stale Uploading and Reconciling work
  only reconciles automatically and never repeats the POST. Each logged-in
  boundary takes one bounded current-account snapshot pass that admits
  `UploadedUnconfirmed` through the ordinary lease/reconciliation route; it is
  absent from the live pending query, cannot self-loop, and never reaches an
  automatic POST. Manual Retry also reconciles first and posts only after a
  conclusive no-match. Current playback remains local; a later stored launch may
  prefer the confirmed server stream while retaining the local fallback.
- Sync network work remains outside the mutation actor. One exact
  asset-generation/order claim is active at a time; every returned state update
  re-reads and conditionally validates that claim inside the actor, so a stale
  response cannot replace an asset deleted, cleared, or reinstalled while the
  request was in flight. The sole app-scoped startup owner awaits storage
  reconciliation before observing sessions or pending assets. A
  non-cancellation reconciliation failure settles after one fixed sanitized
  diagnostic before observation begins; cancellation is rethrown and prevents
  observation. It then runs one network job at a time, cancels on account
  switch, and resumes stale work on the next launch. Do not hand subtitle sync
  to an operating-system background scheduler — no WorkManager job, no
  `BGTaskScheduler` task, no desktop daemon. The prohibition is scoped to
  subtitle sync and says nothing about unrelated scheduled work such as the
  Android TV Watch Next job.

## Selection and delivery

- Selectable Subtitle identities are resolved over the complete Subtitle list,
  including external and unsupported entries. A nonnegative actual index is
  used directly; a missing index falls back to its original position in that
  list. Negative indexes and repeated resolved identities produce no choice,
  including an actual index colliding with another entry's position fallback.
  Omission never shifts later positions or rewrites raw metadata. Detail,
  startup and queue defaults, in-player pickers, and download draft resolvers
  use this projection. Off and local assets remain separate intents.
- A position fallback is a client/playback identity, not an authenticated
  download identity. Original and Fixed admission require a unique actual
  index; a missing-index choice can still be refused as `SourceChanged`.

- Subtitle launch intent has four states: omitted `Unspecified`, explicit `Off`
  (`SubtitleStreamIndex=-1`), `Track(index)`, and `LocalAsset(assetId)`. Startup
  resolves explicit intent first, then the durable selection keyed by
  server/user/item/media source, preferred subtitle language, Jellyfin's default
  subtitle, and finally Off. Invalid stored tracks are deleted and fall through;
  invalid route values are ignored and never persisted. Detail launches send
  their current Off/Track choice explicitly, while shuffle, autoplay, and queue
  transitions omit it and resolve each new item/source independently.
- Explicit launch and in-player choices persist through the Room-backed subtitle
  store. Additive version-2-to-3-to-4 migration preserves existing rows. Logout
  clears Jellyfin Off/Track intent for the affected server but retains local
  asset rows and LocalAsset selections; explicit deletion clears them. Writes
  are serialized so rapid choices cannot finish out of order. Process-local
  playback memory retains audio choice only; session quality is separate.
- PlaybackInfo always receives the resolved subtitle index, including `-1` for
  Off or a local asset and the real Jellyfin index for remote external tracks.
  `PlaybackPlan` carries response-derived embedded audio descriptors and a
  response-authoritative `PlannedSubtitle`: Off, a track with an optional
  embedded descriptor, delivery method (Drop, Embed, External, HLS, or Encode),
  text/bitmap kind, optional external resource, and local activation target, or
  Unavailable. Each embedded descriptor records Jellyfin stream index, filtered
  container ordinal, nullable response-authoritative cohort size, codec,
  normalized language, and label; detail-metadata fallback descriptors leave
  that cohort size unknown. Filtered container ordinals exist because
  Jellyfin's `MediaStream.Index` is a global response index that lists
  external streams first (an external SRT can be index 0) — it never reliably
  equals a player's container track position, so no bridge may use a Jellyfin
  index directly as a native track ordinal (probe-verified on Jellyfin 10.11).
  Subtitle descriptors
  canonicalize only known Jellyfin/native codec and MIME alias families,
  including `mov_text`, `tx3g`, and `application/x-quicktime-tx3g`; BOTH
  kinds use the raw source `Title` as comparable identity — never the
  server-synthesized DisplayTitle, which cannot equal a native candidate's
  container track title and would force TitleConflict transcodes (readable
  display labels are UI-only). Audio compares codec families only during native
  mapping, and track languages canonicalize through the complete ISO
  639-1→639-2/T table plus the 639-2 B→T variant pairs, so `te`/`tel` or
  `fre`/`fra` style taggings never conflict. The unknown-language family (`und`,
  `unknown`, `undetermined`, `mul`, `zxx`) canonicalizes to null, so it never
  creates a language conflict with a real tag; other unknown subtitle/audio
  codec and language values stay isolated rather than being guessed into a
  family. Successful PlaybackInfo plans build descriptors from the selected
  response media source, retaining capability-approved detail-derived embedded
  subtitle descriptors for indexes absent from the response's Embed descriptors.
  A failed PlaybackInfo request returns no plan. Audio descriptors exclude
  external audio; response-derived subtitle ordinals include only approved
  Embed/HLS streams, so
  External, Encode, and Drop entries never shift them. Transcoding URLs carry
  only the resolved requested subtitle intent: when a server-attached
  `SubtitleStreamIndex` differs from that intent (including Off or a local
  asset), the planner strips it and `SubtitleMethod` before the plan reaches a
  controller. The strip exists because the server attaches these parameters on
  its own — the user's server-side `SubtitleMode` setting can add
  `SubtitleStreamIndex=N&SubtitleMethod=Encode` to a transcode URL even when
  the request omitted a subtitle or sent `-1` — and burn-in forces a full
  video re-encode while rendering subtitles the user never selected in-app;
  stripping the two parameters restores video stream copy on the same asset
  (probe-verified on Jellyfin 10.11). The response's
  `DefaultSubtitleStreamIndex` never becomes
  installed subtitle truth. Matching user-selected bitmap subtitles retain their
  server-selected burn-in path during transcodes. Stream mode never determines
  subtitle rendering. Every platform controller disables native subtitle
  auto-selection during prepare before loading or playing new media; Embed/HLS
  activation applies in direct play, direct stream, or transcode plans. Only
  confirmed local text is styleable.
- MOV_TEXT/TX3G canonical identity supports strict embedded matching; parser
  support does not qualify raw TX3G sidecars or HLS delivery.
- Subtitle selectors remain available when exactly one real server or app-local
  track is present because Off and that track are two distinct choices. One
  shared predicate controls CC visibility and picker dismissal; the picker
  closes only when both lists become empty.
- Device profiles always enumerate a `SubtitleProfiles` delivery method for
  every subtitle format the platform can render, because the server plans
  burn-in for a default-flagged or auto-selected subtitle whose format has no
  advertised delivery — denying direct play for the entire item, not just that
  track (probe-verified on Jellyfin 10.11). Normal device profiles advertise
  local subtitle methods before Encode: Android
  Media3 supports its verified text formats, VTT HLS, and bitmap Embed only
  where `DefaultSubtitleParserFactory` confirms the MIME. Its separate
  `mov_text` profile advertises Embed only when that factory supports TX3G,
  followed by Encode; it advertises neither External nor HLS. Desktop libmpv
  supports broad embedded text/bitmap and authenticated external text; iOS
  limits AVPlayer text delivery to Embed (direct-play container tracks matched
  by stable index) and Encode. It advertises NO External (unreliable
  `AVMutableComposition` sidecar splice) and NO HLS text (transcode text tracks
  carry no stable source index, so native selection false-conflicts on the
  manifest language string), so text subtitles are burned in (Encode) during a
  transcode — ForceEncode forces one on a direct-play video — and render with no
  native track selection. The iOS VLCKit profile uses the SAME text policy
  (Embed + Encode, no External/HLS): VLC renders embedded container tracks
  natively on direct play, but an external subtitle slave is not rendered over
  an HLS/transcode stream, so text subtitles burn in (Encode) on a VLCKit
  transcode just like AVPlayer. VLCKit bitmap subtitles
  remain Embed + Encode. Each device profile lists Jellyfin's equivalent codec
  spellings separately (`vtt`/`webvtt`, `srt`/`subrip`, `ass`/`ssa`, and bitmap
  aliases) while applying the same platform policy to each. Do not collapse
  aliases in the request profile because exact format names participate in
  server delivery negotiation. Capability lookups use canonical subtitle-format
  equivalence, so a provider advertising one spelling still matches an
  equivalent stream spelling. External subtitle files are sideloaded from the
  response `DeliveryUrl`; missing/unknown MIME or URL is unsupported and never
  guessed as SubRip. Server-relative delivery URLs are resolved against the
  active session server URL. Platform bridges should treat unsupported sidecar
  formats as non-fatal: keep video playback alive, leave that sidecar
  unselected, and log only sanitized diagnostics.
- Non-direct audio changes, External/Encode subtitle transitions, local activation
  fallback, and all quality cap changes re-request PlaybackInfo at the current
  position with the selected stream fields; External, transcoded and burned-in
  transitions close the picker. Before installing a successful replacement, the
  old progress-reporting session is stopped exactly once at the captured position;
  play/pause intent is preserved.
  Off is immediate only for a locally selected track. Removing an External or
  Encode selection re-plans with `SubtitleStreamIndex=-1`.
- Requested subtitle memory updates immediately, but picker selection, debug
  state, and styleability remain derived from the installed `PlaybackPlan`.
  During a re-plan the old track therefore stays active until the replacement
  plan is installed; a PGS/external/off request must not be classified as failed
  merely because it is being compared with the previous plan. A newer subtitle
  choice cancels any older pending re-plan so a stale response cannot overwrite
  the latest request. None is selected only when subtitles are actually Off; a
  local row becomes selected/styleable only after exact activation confirmation.

## Response-authoritative delivery

- Successful PlaybackInfo response subtitle delivery is authoritative; detail
  metadata never overrides it. PlaybackInfo request failures propagate under
  the [runtime request-failure contract](playback-runtime.md#runtime-request-fallback);
  subtitle Off or a selected Track never permits an unverified local plan. If the
  selected subtitle is missing only from a successful PlaybackInfo response, its
  exact detail stream may supply a known format/kind for one Encode attempt;
  missing detail format disables the retry. A fallback response is accepted only
  when it returns Encode at the exact failed Jellyfin stream index. Embed,
  External, HLS, Off, missing, and wrong-index responses leave the subtitle
  unavailable without replacing the playing plan.

## Local activation identity

- Local subtitle state uses a project-owned request identity containing request
  id, item id, local kind, and either a Jellyfin stream index or local asset id.
  `PlaybackPlan` carries the expected target and `PlaybackState` carries
  `Pending`, `Active`, or `Unavailable` for that target. Shared render
  diagnostics accept only an exact target match; stale platform callbacks remain
  pending and cannot enable subtitle styling. Media3 may prefix a sidecar
  `Format.id` with its media-period identifier, so Android matches the complete
  configured activation id after that delimiter; partial or suffix-collision
  matches remain invalid. Confirmation budgets begin at backend-qualified
  readiness; an expired budget turns a pending target into `Unavailable`.
  An exact Jellyfin-track local failure disables the failed
  selection and publishes the six-second token-keyed passive notice
  “Local subtitles unavailable; switching to server-rendered subtitles.”
  It re-plans once at the same position with only Encode exposed for that
  normalized format. That
  server-burn-in fallback MUST disable both direct play and direct stream in its
  request policy so the server performs a real transcode; Encode delivery
  renders only inside the transcoded video. This invariant applies to both the
  shared-UI and tvOS fallback paths. A local-file failure instead disables that
  app-owned asset, shows a passive unavailable notice, keeps video playing, and
  never requests ForceEncode. Neither notice takes focus, adds a scrim, appears
  in PiP, or becomes fatal. A newer selection cancels either path; stale targets
  are ignored. The Jellyfin retry
  must return Encode for the exact failed stream index. Failure retains the
  playing video, leaves the subtitle unavailable, and never loops or becomes a
  fatal error. `PlaybackSessionRecoveryPolicy` owns the one-shot,
  generation/item-scoped decision and exact request identity for both shared UI
  and tvOS; each shell still owns its suspended request and cancels it when a
  newer choice/item or stop/close makes the result stale.

The activation fallback above concerns the installed target. Reading a newly
selected local asset follows the [proposal contract](playback-policy.md#quality-semantics):
a healthy rejection preserves installed selection; an unsafe held seek fails
with its latest target retained rather than committing Subtitle Off.

`Active` means the exact requested target reached the adapter's native
acceptance boundary; it does not prove subtitle pixels were displayed. Native
track identity/readback is preferred where available. Apple VLC waits for
external-track discovery without a discovery timeout: an earlier timeout caused
Encode/reprepare loops while a valid HLS sidecar was still loading. AVPlayer
separately bounds asset/group loading before selection confirmation. Desktop
VLC's accepted slave plus native Playing handoff is weaker evidence than selected
track readback and must not be described as rendered output. These exceptions
retain the same target identity and nonfatal activation outcomes; equal timeout
numbers would not establish equal behavior. See the [Apple recovery contract](apple-playback.md#ios-recovery)
and [desktop playback](desktop-playback.md).

## Timing offset contract

- Audio and subtitle timing offsets are signed, finite milliseconds, clamped to
  the shared ±20-second limit, and keyed by server/account/item/media source and
  stable track identity. The ViewModel loads an offset **before** the first
  prepare once the source/track is known (so the initial sink configure applies
  it without a post-start re-prepare), resets the previous key on source/track
  changes, applies it through the optional `PlayerTimingController`, and
  persists **the value the controller actually applied** (a negative subtitle
  request is stored as 0 on Media3), not the raw request. Unsupported platforms
  expose an explicit unsupported capability rather than a misleading applied
  value. Unsupported describes the installed adapter/route, not proof that the
  native SDK lacks an API. Available native APIs still require ownership,
  acknowledgement and restoration integration before a control can be enabled.
- The timing owner is the currently installed controller. Commands and restores
  do nothing while no concrete controller is installed or after disposal;
  Stop/release retain the last displayed values until a new observation.
  Replacement observation rejects emissions from the outgoing controller.
- Stored reads are qualified by the load generation, controller and timing
  controller identity, and both track keys after suspension. Finish all eligible
  reads before applying either restored offset, then recheck ownership, support
  and each kind's command revision without another storage suspension. A manual
  adjustment supersedes only its own kind's pending restore. New restores, Stop,
  release and disposal invalidate older loads; persistence still records the
  applied value.
- Before reading storage, a restore prefers the timing save owner's outstanding
  value for the same key when its account identity and boundary epoch match the
  current session. Visibility starts at submission and ends when the write
  finishes or can no longer start. This does not guarantee persistence or retain
  failed values; commands issued during the restore still take precedence.
- Normal startup owns the existing installation admission while restoring
  offsets, drains deferred Stop on exit, and rechecks request and controller
  ownership after unlocking before prepare. Offline preparation keeps its
  existing concurrent controls and post-prepare selection reconciliation;
  backend switches and replans keep their existing serialized preparation.

## Quality, tracks, and subtitles

- **[all]** Replanning and installed subtitle truth follow
  [Selection and delivery](#selection-and-delivery); activation failure follows
  [Local activation identity](#local-activation-identity). Response-approved
  Embed/HLS tracks switch locally regardless of video stream mode.
  A DirectPlay plan also retains capability-approved embedded text descriptors
  while subtitles are Off, so selecting one of those tracks switches in the
  current native session without another PlaybackInfo request or reprepare.
  Bitmap, External, HLS, unsupported, missing-descriptor, DirectStream, and
  Transcode Off-to-track selections retain the server-authoritative replan path.

- **[all]** Subtitle style is visible only for exact platform-confirmed local
  text AND a backend that can apply it
  (`PlayerController.appliesSubtitleStyle`). Burned/transcoded subtitles are
  pixels and cannot be restyled; close an invalid open subtitle/style picker.
  Android LibVLC reports the capability as false because libvlc-android 3.7.7
  exposes no subtitle text-scale API (font size is a `LibVLC` construction-time
  option), so its size control is hidden rather than inert. The ViewModel
  publishes one `subtitleStyleable` truth so the shared controls, TV overlay,
  picker auto-close, and debug row cannot disagree.

- **[mpv]** Shared styling keeps `sub-ass-override=force`: ordinary style and
  clearance options can affect native ASS as well as converted text. Authored
  signs, colors and layout are therefore not guaranteed to remain unchanged.
  The shared Compose picker offers font size; Android TV also offers foreground,
  background and edge controls. Preserve those controls with their renderer
  mapping; a global switch to `scale` would leave the TV appearance choices
  ineffective for native ASS. A preserve-authored mode would need an explicit
  user-intent and control-availability contract before changing this policy.

- **[all]** Explicit subtitle **Off** is durable and distinct from
  `Unspecified`, which resolves language/default fallbacks. Detail exposes
  separate track and local-asset paths rather than one nullable choice.

- **[all]** After deleting a downloaded subtitle, re-read durable selection and
  reproject from the options already in state through the shared resolver before
  any server refresh. Deleting an unselected asset must preserve the surviving
  selection and highlight.

- **[all]** Requested and installed audio are distinct. Keep the installed row
  selected while a DirectPlay switch is Pending; update it only after exact
  native activation or a response-selected DirectStream/Transcode plan.

- **[ios]** Embedded tracks use response descriptors mapped against Ready
  AVFoundation media-selection groups and confirm the exact selected option;
  None clears the legible group. External composition is used only for a
  compatible legible asset and unsupported sidecars remain non-fatal.

- **[Android]** Media3 confirms the selected embedded group or request-specific
  external configuration id before reporting active.

<a id="why"></a>
<a id="subtitles-and-opensubtitles"></a>
See the selection, timing and asset rules above.
