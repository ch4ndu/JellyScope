# Download Execution And Recovery

Engineering contract. See the [documentation map](../README.md) for related
owners and the [user guides](../USAGE.md) for usage instructions.

## Queue, quota, and recovery

- One device-global FIFO sequence and one device-global active slot cover every
  account. Among the current account's eligible rows, the oldest queued row is
  the only claim candidate; a quota-blocked head blocks later rows for that
  account. Pause checkpoints the item and requires explicit Resume. Cancel
  removes the record and partial artifact; Retry increments the attempt
  generation before reusing a failed record. Only an `UnsupportedArtifact`
  failed Local HLS package deletes its staging area before that requeue; all
  other staging remains resumable, and `MissingArtifact` retains its guarded
  completed-area cleanup. The explicit current-account resume-all command
  moves every `Paused` row, but not `BlockedByQuota` or `Failed`, back to the
  FIFO and issues one platform wake after any row applies. Each shared or Android
  TV Downloads-screen resume requests one queue-level wake without targeting a
  row; a lifecycle wake failure is safe-diagnostic-only and never a UI error.
  Native tvOS instead exposes an explicit queue wake and reports scheduling
  rejection without changing durable row state. Its entry and Refresh stay
  passive. Passive app start never wakes the queue.
- Within an existing execution window, Original and Fixed transfers retry only
  `DownloadFailure.Network`, at most twice after cancellable delays of 1 and
  3 seconds. Writers settle and checkpoint before each retry. Reclaim requires
  the same account, epoch, failed row and attempt, with no competing command,
  active slot, removal, or quota conflict. Requeue preserves generation; the
  exact claim increments it. Source, quota, artifact, and server-unavailable
  failures remain terminal. There is no persisted retry counter or new wake.
- A failed row does not stop later eligible FIFO work. Continued-processing
  completion checks the exact enrolled rows and generations, including late
  joins; draining past a failure alone does not prove those rows completed.
- The user must configure a device-wide hard allocation in positive whole
  decimal GB, with a 1 GB minimum. Admission and every durable transfer
  checkpoint account for completed and partial physical bytes plus outstanding
  reservations, and
  retain the Finalizing reservation until Completed commits. The safe maximum
  also preserves 1 GB of filesystem free space. Download database migration
  1-to-2 preserves a valid legacy whole-number selection by converting only its
  configured quota from binary GiB to decimal GB; malformed legacy quota values
  become unconfigured, while downloaded-byte facts remain unchanged. Lowering
  the allocation below committed/reserved use shows over-allocation and blocks
  new work; JellyScope
  never evicts or automatically deletes another download. Before a writer can
  exceed its reservation, it extends that reservation transactionally under
  both limits or stops at the next bounded pre-write boundary as
  `BlockedByQuota`; an underestimated admission estimate is not a per-item cap.
- Android uses exactly one OS execution family: API 34+ user-initiated data
  transfer work, or API 33-and-lower foreground WorkManager work. A vanished
  API 34+ job is paused for explicit Resume rather than silently restarted after
  a possible Task Manager stop. On API 33+, each Android app shell makes a
  best-effort notification-permission request immediately before an Original or
  Fixed Start action. The permission result affects notification visibility, not
  Jellyfin authorization, enqueue eligibility, or transfer execution. Android
  job-stop cleanup starts undispatched and non-cancellable, with a five-second
  bound for cancel/join and the exact UIDT attempt checkpoint. Without an attempt
  identity it performs no broad checkpoint; process loss retains recovery as the
  fallback. Notification setup failures retain safe diagnostics and prevent
  transfer work from starting without the required notification.
- iOS 26+ requests continued-processing time for explicit Original or Fixed
  Start, Resume, Resume All, and Retry actions. An actual native grant can keep
  the existing app-owned writer running after backgrounding; submitting a
  request alone does not grant execution. Each concrete wake identifier is
  registered once before submission; the plist wildcard supplies permission.
  Registration or scheduling rejection retains foreground downloading, with
  generation-correlated scheduler diagnostics. The grant is bound to one retained
  wake and account epoch. Screen entry, recovery, Pause/Cancel follow-up, and
  removal-preview dismissal never request background time. Native Stop or
  expiration checkpoints to Paused for explicit Resume. Slow server encoding
  or Fixed preflight can show little progress and cause iOS to end execution;
  progress is never fabricated to keep the task alive. Downloads explains the
  version-dependent capability with a small localized helper.
- Earlier iOS versions, or iOS without an active grant, retain app-active
  downloading; suspension checkpoints to the runnable queue. JVM desktop
  likewise transfers only while open and checkpoints on graceful exit. The
  next active launch recovers durable state. iOS continued processing does not
  promise completion, process-death survival, force-quit survival, or a
  closed-app download daemon. Existing per-write quota, source validation,
  redirect, and account-lease safeguards apply to both iOS transfer qualities.
- Original byte checkpoints and Fixed package checkpoints are generation-bound.
  Original resume preserves the full main-file checkpoint when no subtitle
  sidecar exists; with a sidecar, its bytes are subtracted from the package
  checkpoint before normalizing the main file. Only bytes beyond those durable
  per-part checkpoints may be truncated.
  Original active-attempt settlement is non-throwing: a checkpoint failure
  retains the registration's last durable facts, both sidecar and main writers
  are independently best-effort closed under non-cancellable cleanup, and only
  the exception class enters the Original diagnostic tag before the registration
  clears for later FIFO work. Fixed resume re-fetches and authenticates the
  finite playlists, then requires the same normalized variant, media sequence,
  and segment identity. Its
  checkpoint persists only relative local part names, lengths, and completion
  facts; completed parts may be retained, an incomplete segment restarts, and a
  remote-shape mismatch fails instead of persisting a token-bearing URL.
  Recovery cancels duplicate/stale native work before reassociation and
  validates staging facts before atomic promotion. After promotion, the current
  reservation, physical, and checkpoint facts must pass the canonical completed
  HLS validator before `Finalizing` can commit `Completed`; an invalid or
  uncertain result remains finalizing for the existing recovery path. Recovery
  never marks an incomplete/corrupt artifact Completed or starts a duplicate
  transfer. Missing/corrupt completed artifacts fail visibly and remain
  explicitly deletable rather than being trusted by metadata alone.
- Download metadata uses an isolated `DownloadDatabase`, separate from the
  ordinary refetchable cache. Database files, journals, checkpoints, staging,
  and completed artifacts share Android no-backup storage or one iOS Application
  Support subtree excluded from backup; JVM keeps them under one private app-data
  subtree. Mobile restore therefore cannot recreate completed rows without their
  media, and the device-local allocation returns unconfigured with that subtree.
  tvOS uses a private Caches subtree for both the download database and media.
  Apple TV may reclaim either independently: playback verifies files, recovery
  tolerates missing rows/artifacts, and the UI explains possible redownloads.
  On tvOS, transfers run only while the app is active; the shared Apple
  lifecycle host checkpoints on inactivity/termination. tvOS has no background-
  transfer or durable-retention guarantee.

## Why

- Bounded in-window network retries absorb transient reads without creating
  background execution promises. Exact attempt claims preserve queue ownership,
  while continued drains distinguish row failure from enrollment completion.
