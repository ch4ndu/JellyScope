# Downloads And Offline Storage

Engineering contract. See the [documentation map](../README.md) for related
owners and the [user guides](../USAGE.md) for usage instructions.

## Downloads And Offline

This section is the authoritative data, transfer, storage, and local-playback
contract for Downloads. Secondary architecture and UI guides link here rather
than restating it.

## Scope and identity

- Downloads support individual Movies and Episodes on Android mobile, Android
  TV, iOS, tvOS, and JVM desktop. tvOS installs the existing download graph
  with shared Apple adapters; Original and converted/HLS copies use VLC offline.
- `DownloadId` and `DownloadArtifactKey` are opaque device-local identities.
  The one retained-download business identity is
  `(serverId, userId, itemId, mediaSourceId)`: enqueueing the same identity
  returns its existing record instead of creating a second copy. UI/player
  routes carry only the opaque download identity, never a raw server URL,
  credential, `Session`, or absolute artifact path. Package checkpoint
  manifests and diagnostics omit account/media/download identity as well as
  tokens, remote URLs, and paths; only the trusted resolver may turn an opaque
  artifact key into a lease-owned controller resource.
- Downloads are account-owned. Lists and commands expose only the current
  account's records. Device-wide usage may identify that account's physical
  bytes but represents every other account through one aggregate physical-byte
  count, never identities or titles. Switching accounts checkpoints active work
  and retains every artifact; only the current account is eligible to claim
  queued work.
- The effective Downloads permission is carried by the authenticated `Session`
  and `StoredSession` projection. Password and Quick Connect authentication
  project the current Jellyfin `EnableContentDownloading` user policy into that
  session value. Missing policy and missing stored fields default to `false`,
  with no download-permission synchronization flow. The separate parental-rating
  [parental-rating refresh](kids-viewing.md#kids-account-playback) preserves this permission value.

## Admission, quality, and artifacts

- Every new request starts at `Original`; Downloads has no Auto choice, custom
  bitrate, or remembered/default download quality. A user may instead choose a
  finite canonical Fixed rung for that request.
- Admission first fails closed unless there is an authenticated session whose
  effective session permission enables content downloading. This client-side
  permission gate runs before current-account matching, account-boundary lease
  acquisition, API preflight, enqueue, or platform wake work. Once a
  request passes that gate, Jellyfin current-user account, raw policy, item, and
  exact-source facts are revalidated during admitted preflight and again
  immediately before every start or resume. The effective session permission is
  a separate client-side gate, not a substitute for those remote facts. The
  Jellyfin static stream route is an exact-source byte route, not server-side
  proof of the user's download permission; JellyScope supplies that policy
  boundary. The final admission result and every bounded durable attempt commit
  must still match the captured account-boundary work lease; streaming body I/O
  never holds the account mutation gate.
- Original downloads the exact selected static source. Preflight sends a
  one-byte Range request and accepts only a truthful `206 Partial Content` with
  a complete `Content-Range` total and `Last-Modified` validator. Start and
  resume use Range plus `If-Range`; a `200` fallback, changed total/validator,
  invalid range, missing selected track, or changed source fails as
  `SourceChanged` instead of appending incompatible bytes. The resulting private
  artifact preserves all embedded tracks from the source, and its snapshot
  remembers the selected audio/subtitle intent for offline playback.
- Original admission also compares authenticated source codec, dimensions, and
  frame rate with the normally selected offline backend's probed finite decoder
  bounds. A source proven to exceed those bounds is rejected with guidance to
  choose a converted download. Missing source facts or unknown decoder limits
  remain admissible; they are not evidence of incompatibility.
- Original localizes one selected external text subtitle into the same private
  package, whether it came from a bounded Jellyfin subtitle response or an
  installed local OpenSubtitles asset. It cannot copy an external bitmap
  subtitle: the user must explicitly continue without it or choose a compatible
  embedded text subtitle for confirmed Fixed burn-in. Manual download deletion
  never deletes the separately installed subtitle asset.
- Fixed always asks Jellyfin to encode the exact selected source to the chosen
  canonical rung as finite HLS-TS VOD with H.264 video and AAC audio. Direct
  play, direct stream, and audio/video stream copy are disabled. The package
  contains exactly the selected audio track, or the source default/fallback
  audio when none was selected. Subtitles are either Off or one compatible
  embedded text track after explicit confirmation that it will be permanently
  burned into the video; Fixed never writes a selectable subtitle sidecar. Its
  bitrate-times-duration plus ten-percent admission estimate is not a promised
  final size or per-record limit.
- Fixed preflight and admission publish identity-free structured diagnostics
  before each distinct rejection is reduced to a UI decision. The causal record
  distinguishes session and policy gates, source/track validation, PlaybackInfo
  request or decode failure, response source cardinality, transcode capability,
  HLS protocol/container, trusted URL projection, required device/session facts,
  estimate construction, post-preflight consistency, and snapshot construction;
  preview, enqueue, and transfer preflights carry distinct request kinds. The
  transfer chain separately identifies master/media fetch and parse rejection,
  source/duration consistency, package preparation, and transfer start.
  Fetch rejection retains its closed transport/body cause until that causal
  record is emitted, before mapping to the public download failure. Successful
  stages publish bounded completion markers. Logs retain only closed reasons/
  results, bounded counts, and exception classes.
- The Fixed localizer accepts only one bounded relative master variant and a
  finite relative `.ts` media playlist with `PLAYLIST-TYPE:VOD` and `ENDLIST`.
  Master playlists are limited to 1 MiB, finite media playlists to 4 MiB, and
  credential-free resume checkpoints to 2 MiB; media still permits at most
  8,192 segments and every playlist line remains limited to 8,192 characters.
  Periodic checkpoints scale with the package segment count and reserved bytes,
  targeting at most roughly 128 routine full-manifest rewrites while preserving
  final and interruption checkpoints. Each bounded checkpoint manifest is
  written and synced in a fixed private-root sibling replacement directory,
  then atomically replaces its staging member; stale replacement data is never
  inside a package or visible to staging/completed enumeration. Media and
  segment truncation remains writer-owned rather than using that replacement
  operation.
  A Jellyfin transcoding URL may project either the master or that finite media
  playlist directly; both shapes produce the same deterministic local package.
  Metadata-only master `VERSION`, `INDEPENDENT-SEGMENTS`, session-data,
  trickplay image variants, and inert comments are accepted but not retained;
  media `INDEPENDENT-SEGMENTS`, validated `ALLOW-CACHE`, and inert comments are
  also accepted. Rendition/resource-bearing and unknown tags remain rejected.
  Jellyfin/FFmpeg `EXTINF` values may carry bounded sub-millisecond precision;
  the localizer rounds that precision to the package's millisecond identity.
  It rewrites the completed package to deterministic local relative names and
  rejects encryption/key, map/init-segment, absolute or cross-origin resource,
  traversal, live/low-latency HLS, and every other unverified tag or resource
  shape. Every admitted Fixed preflight/transfer exit attempts the exact active-
  encoding cleanup; cleanup failure does not replace the authoritative transfer
  outcome.

- Android and desktop mpv receive resolver-owned absolute local paths for offline
  media and sidecars. Do not convert them to Java `file:/` URIs: mpv treats that
  spelling as a filename and fails before loading the media.

## Saved metadata and artwork

- New downloads save credential-free metadata after media preflight; preview and
  existing downloads never fetch or enrich it. Missing facts are omitted.
- Poster, backdrop and logo references store role, owning item and image tag.
  Episodes prefer series posters. Missing backdrops use saved primary artwork;
  offline reads never fetch server images.
- Optional artwork capture runs after media/sidecars and before Finalizing.
  Network failures omit images without failing media; cancellation propagates.
- A private presentation area keeps artwork outside media completeness checks.
  Images are capped at 2 MiB each, 6 MiB total; lazy, account-qualified reads
  return bounded bytes, never paths or payloads embedded in observed records.
- Actual `presentationBytes` counts toward usage, allocation and removal without
  another reservation. Capture respects the safety reserve; publication checks
  account, generation and allocation under the mutation owner, without network
  I/O. Migration 2-to-3 defaults the count to zero and preserves existing records.
- Recovery cleans known rows and temporary members after cancelling stale work,
  skips live writers, and reconciles retained bytes. Retry resets accounting with
  deletion; Cancel, Delete and account removal retain row/tombstone ownership
  until both media and artwork cleanup succeeds. No arbitrary directory sweeps.

## Offline playback, progress, and removal

- Local playback is entered only from the explicit Download Play action. Normal
  remote Play never silently substitutes a local copy. The offline branch reads
  a persisted snapshot before any detail, PlaybackInfo, image, trickplay,
  segment, or autoplay request, and admits only a current-account, Completed,
  current-generation artifact whose exact package is complete.
- Controllers receive only a generation-bound `OfflineArtifactRef` and must
  acquire a project-owned artifact lease before resolving a local resource.
  Delete and account cleanup return `ArtifactInUse` while that generation is
  leased. Android and JVM preserve the normally resolved platform backend. On
  iOS and tvOS, every offline artifact requires VLCKit for that playback session
  without changing the stored backend preference; missing, wrong, or failed VLCKit
  returns `OfflinePlayerUnavailable`, never falls back to AVPlayer, and retains
  the artifact. Apple VLC preparsing uses local-only flags for offline plans;
  network metadata and cover fetching remain enabled only for online plans.
- An Original package's retained subtitle is a separate artifact-qualified
  sidecar choice, including imported subtitles with no Jellyfin stream index.
  Initial selection and reselection carry a non-null ExternalText activation
  target; trusted lease contents supply the bytes, and native confirmation still
  determines whether the subtitle is active. Off and embedded rows remain
  mutually exclusive with that choice. Reselection uses the offline prepare
  lifecycle, preserving position, audio, speed, timing, style, play/pause intent
  and reporting generation; it never resolves a local-asset ID or remote URL.
  Missing or stale package members fail Offline without remote fallback.
- Offline playback durably coalesces the latest local resume position onto the
  download record first. Only a genuine controller `Completed` state marks the
  record watched; an ordinary stop or near-end position cannot, and an ordinary
  stop never clears an already watched record. When the same
  account is currently online, the existing Jellyfin Start/Progress/Stopped
  calls may also run in the independent remote drain and failures remain best
  effort; a slow remote call cannot delay newer local resume/completion writes.
  There is deliberately no
  reachability monitor, outbox, delayed synchronization,
  server-progress overlay, cross-device conflict claim, or guarantee that local
  progress ever reaches Jellyfin. Native tvOS offline playback is local-only:
  it updates the download record and performs no Jellyfin reporting. Subtitle
  replacement retains the content checkpoint through native teardown and
  preparation; temporary reset positions cannot overwrite local resume progress.
- Manual Cancel/Delete never marks the server item unplayed and never rewrites
  Jellyfin watch history. If the server item is deleted after completion, the
  verified local artifact and snapshot remain usable; there is no periodic
  reconciliation. If the item/source disappears before completion, the next
  admission or resume fails visibly.
- Account removal and full logout quiesce affected attempts, show the exact
  affected record count and bytes, and require explicit permanent-deletion
  confirmation. The authorization is bound to that quiesced membership
  snapshot, so membership change causes a stale-confirmation re-prompt. A
  durable removal operation is written before credential deletion and replayed
  before session restore until both account absence and artifact/row cleanup are
  verified. Ordinary account switch uses no deletion path.

## Offline resolution branch

An explicit Download Play route takes a separate branch before the online
[online pipeline](playback-architecture.md#2-end-to-end-decision-pipeline). It resolves a current-account, Completed, current-generation
record and its persisted snapshot into `StreamMode.Offline`; it does not call
detail, PlaybackInfo, remote image/trickplay/segment, or autoplay resolution,
and ordinary remote Play never substitutes this branch. The plan carries only a
generation-bound opaque artifact reference and a typed package-sidecar choice
when selected, never a fabricated Jellyfin index or raw local path. Every
accepting controller must
acquire the trusted local artifact lease before resolving the resource; the
offline branch never enters a remote URL or credential-attachment path, and
deletion must refuse that leased generation.

Android and JVM keep the normally resolved concrete backend for both artifact
kinds. iOS and tvOS select VLCKit for every offline session, never write that
session-only choice to preferences, and make the backend required. A missing,
wrong, or failing VLCKit controller returns `OfflinePlayerUnavailable`; it does
not prepare AVPlayer, fall back to it, or delete the artifact. The complete
identity, artifact, and progress rules live in
[Downloads And Offline](#downloads-and-offline).

## Why

### Downloads and offline

- **Download authorization is a current-account boundary.** Static bytes do not
  prove permission; admission and every start/resume revalidate account, policy,
  source, validators, and the captured lease.
- **iOS continuation retains the existing writer.** A user-initiated native
  execution grant extends runtime without moving bytes into an OS-owned
  transfer pipeline that bypasses per-write allocation and redirect controls.
  Exact, nonduplicated handler registration avoids native exceptions that
  Swift error handling cannot catch.
  Grant expiry pauses honestly; it does not imply a corrupt or completed file.
  Older iOS and tvOS retain their app-active limits.
- **Original and Fixed are closed artifact formats.** Original preserves the
  selected source; Fixed produces the bounded HLS-TS/H.264/AAC package that can
  be validated, resumed, and localized. Adaptive/custom packages and general HLS
  parsing would make artifact identity and completeness ambiguous.
  A missing subtitle sidecar contributes zero bytes to a package checkpoint;
  it must never reset already downloaded main-file bytes.
- **One queue and device allocation make admission deterministic.** Reservations
  and the free-space floor prevent overcommit without eviction. Android
  notification permission controls visibility, never transfer authorization.
- **Artwork is optional.** Missing images must not invalidate playable media;
  stored images still count toward allocation.
- **Offline progress is local truth.** It persists independently of optional
  server reporting, so network delay cannot block resume state. No outbox claims
  eventual delivery or cross-device conflict resolution.
- **Account removal is a recoverable cross-store operation.** Credentials and
  artifacts cannot commit atomically, so a durable removal header,
  generation-bound leases, exact confirmation snapshot, and startup replay
  prevent stranded private media. Account switching remains non-destructive.

- **Offline playback is a separate trusted-local branch.** Routing it through
  remote planning could contact the server, expose a local path, or bypass
  account and generation authority. Opaque artifact references and leases keep
  resolution and deletion coherent; iOS and tvOS require session-only VLCKit so
  a missing backend fails visibly without changing ownership.
