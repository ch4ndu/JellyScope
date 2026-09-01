# Developer Workflow

This guide takes a contributor from a reported problem or feature idea to a
review-ready change.

Read [`CONTRIBUTING.md`](../../CONTRIBUTING.md) for the project overview and
[`docs/BUILD.md`](../BUILD.md) for setup and build commands. For a review or
audit, start with [`audit.md`](audit.md) and return here for testing and final
verification.

## The Workflow At A Glance

1. Understand the requested behavior and reproduce the problem when possible.
2. Find the code and guide that own the behavior.
3. Write down success criteria and the important edge cases.
4. Make the smallest complete change in the correct layer.
5. Add focused tests for business behavior or durable state.
6. Review the final diff for scope, clarity, and accidental complexity.
7. Verify the exact user flow and compile every affected platform.
8. Update the owning documentation and report what remains unverified.

Small changes should move through these steps quickly. The workflow is a safety
net, not a reason to add ceremony.

## 1. Understand The Change

Before editing:

- Read the request or issue again. For a bug, record what happens, what should
  happen, and which platforms are affected.
- Turn the request into observable success criteria. A useful criterion names
  the action and result, such as “pressing Back from an episode returns focus to
  the same card,” rather than “fix focus.”
- If two interpretations would produce meaningfully different behavior, ask the
  maintainer instead of choosing silently.
- If a fix would remove or weaken an existing behavior, discuss that tradeoff
  before implementation.
- Check `git status` and preserve unrelated work already in the checkout.
- Read the surrounding implementation and tests before designing a replacement.
  Follow local patterns unless there is a demonstrated reason to change them.
- If you have access to the ignored `.local/KNOWN-ISSUES.md` ledger, check it for
  accepted gaps and pending validation related to the change.

Use the guide that owns the area you are changing:

| Area | Read first |
| --- | --- |
| Layers, state, source sets, platform bridges | [`architecture.md`](architecture.md) |
| Shared UI, adaptive layout, accessibility | [`ui.md`](ui.md) |
| Android TV navigation, focus, D-pad behavior | [`tv-ux-behaviors.md`](tv-ux-behaviors.md) |
| Jellyfin data, authentication, playback, players | [`data-playback.md`](data-playback.md) |
| Shared playback pipeline and backend ownership | [`playback-architecture.md`](playback-architecture.md) |
| Reviews and audits | [`audit.md`](audit.md) |

Do not copy third-party source, structure, or assets without a deliberate
file-level license review. Plans, scratch notes, review artifacts, and other
working documents stay under the ignored `.local/` directory. Durable rules
belong in the owning guide, and released changes belong in the changelog.

## 2. Plan The Smallest Complete Change

Start with the behavior that is required now. Do not add speculative options,
configuration, extensibility, fallback branches, or abstractions for states the
application cannot reach.

- Reuse existing UseCases, Actions, repositories, composables, utilities,
  strings, icons, dimensions, and test fixtures before adding another version.
- Keep one implementation for genuinely repeated knowledge or behavior. Do not
  extract code merely because two small blocks look similar; bounded duplication
  is better than an abstraction that couples unrelated owners.
- Give each function, type, and file one practical responsibility. Splitting a
  straightforward operation across forwarding wrappers does not make it simpler.
- Use the nearest project-owned contract. Do not reach through a collaborator or
  bypass an established layer for convenience.
- Touch only the files and lines the change needs. Record adjacent cleanup as
  follow-up work instead of mixing it into the change.
- Remove code or documentation only when this change makes it obsolete.

### Complexity Expansion Decision Gate

Implementation authorization covers the approved outcome and described
solution shape. It does not authorize an unplanned permanent framework,
analyzer, linter, parser or compiler surrogate, code generator, background
subsystem, module, dependency, integration harness, test framework, duplicated
bootstrap, or other support system. Apply this gate while planning and again
before implementation or review-driven repair. It also applies whenever the
support or test infrastructure would have a maintenance surface materially
larger than the behavior it protects.

Before editing that expansion, present the maintainer with:

- the required outcome and evidence for the gap;
- the smallest adequate solution and its focused verification;
- the proposed expansion and why the smaller solution is insufficient;
- the expected permanent footprint in files, approximate code and test size,
  dependencies, build or CI runtime, and ongoing maintenance; and
- a recommendation.

Wait for an explicit choice. When planning proposes the expansion, obtain that
choice before plan review or approval and record it in the plan. When the need
emerges later, pause the affected edits. Plan approval, general implementation
authority, assigned file ownership, or a reviewer finding does not approve an
expansion that was not presented this way. A reviewer may identify a gap, but
cannot turn it into new scope. An ordinary local helper or single focused
causal test does not trigger this gate when it fits the approved solution; the
test still must satisfy the separate Test Expansion Decision Gate below. When
the classification is uncertain, pause and ask.

### Scale Coordination To The Task

These rules apply to every engineering task, but the ceremony must remain
proportional. A bounded change normally has one implementation owner and a
short acceptance checklist; do not create lanes, ledgers, or coordination
artifacts merely because the workflow supports them.

When two or more genuinely independent outcomes can materially shorten the
work, map their dependencies and file conflicts before delegating edits. Give
each lane exact owned paths, protected paths, acceptance checks, and an
integration order. Complete a shared contract or seam before dependent lanes,
and keep shared wiring and canonical documentation under one integration
owner. Never give concurrent writers overlapping ownership.

Use one named build owner and one build slot per checkout. Concurrent writers
yield at coherent source checkpoints; only the build owner runs Gradle,
formatters, generators, or other commands that write shared build state. Batch
compatible focused checks instead of letting workers stop or invalidate one
another's build processes.

Record the starting `HEAD` and complete status inventory once. During lane work,
track only the lane-owned, reviewed, and protected inputs. Preserve and report
unrelated outside-scope changes without treating them as lane invalidation;
unexpected drift in an owned or protected path pauses that lane. Inspect and
freeze the complete candidate once at integration and final verification.

### Consider Edge Cases Before Shared Decisions

For behavior shared by multiple screens or platforms, write down how the design
handles the relevant cases:

- empty, single-item, first/last, and boundary states;
- extremes, clamping, mixed sizes, and missing data;
- rapid input, cancellation, callback ordering, and stale responses;
- focus loss and restoration, dismissal, Back, and platform differences.

Prefer a design that does not depend on incidental callback timing. Include the
important cases in the change description so reviewers can see what was
considered even when a case does not need its own test.

## 3. Put Code In The Right Layer

Prefer `commonMain`. Add platform code only when a platform API or behavior
requires it, behind a project-owned interface or a narrow `expect`/`actual`
boundary.

| Layer | Owns |
| --- | --- |
| Data | Jellyfin DTOs and transport, databases, caches, settings, repository implementations |
| Domain | UseCases for reads, Actions for writes and side effects, reusable business rules and projections |
| ViewModel or presenter | Screen state, events, pagination, selected-item state, and screen-specific row models |
| UI | Rendering supplied state and forwarding user actions |
| Platform | Native players, system services, persistence adapters, and lifecycle bridges |

### Data

- Keep remote DTOs and Jellyfin request details in the data layer. Map them to
  domain models before they reach a ViewModel or UI.
- Repositories own API, cache, and settings behavior. Server-scoped stores must
  be registered for logout cleanup.
- Re-fetchable caches may use a documented destructive alpha migration or reset.
  Durable user-authored state requires explicit migrations.
- Register repository and platform implementations in dependency injection. Add
  focused fakes when their behavior is non-trivial.

### Domain And ViewModels

- Reuse or add one UseCase per read and one Action per write or side effect.
- Keep reusable filtering, grouping, and projection in domain code. Keep
  pagination, selected items, and screen-specific rows in the ViewModel.
- Expose deliberate state and event primitives, with typed values for UI
  messages.
- Register new UseCases, Actions, and ViewModels in dependency injection. Cover
  pure domain behavior in `commonTest`.

### Threading And Cancellation

Network, database, and file work belongs on the project IO dispatcher. CPU-heavy
decode, mapping, sorting, grouping, search ranking, and playback planning belongs
on the Default dispatcher. Android and JVM use `Dispatchers.IO`; Kotlin/Native
uses the project-owned supported background dispatcher because it has no public
separate IO dispatcher.

A bare `viewModelScope.launch {}` or presenter `scope.launch {}` starts on
`Main.immediate`. Move non-trivial work with `withContext(...)` or launch it on
the appropriate dispatcher. Keep cheap bounded transforms, `_state.update {}`,
and related live-field writes on Main so state changes remain ordered and
atomic. Repositories guarantee their own off-Main execution, so callers do not
need to wrap repository work again.

Do not use standard `runCatching` around suspending work; it turns
`CancellationException` into an ordinary failure. Use
`runCatchingCancellable`, or rethrow cancellation before handling other
failures. Synchronous parsing and best-effort platform API guards may still use
ordinary `runCatching`.

### UI

- Put a screen in the shared or platform package that owns it, then wire
  navigation at the application boundary.
- Separate the state-holding composable from plain content where that pattern is
  already used.
- Use shared resources and theme dimensions. Let ViewModels provide row models;
  UI code does not filter, sort, or group application data.
- Reuse a composable only for a real repeated UI pattern.
- Follow [`ui.md`](ui.md). TV work also follows
  [`tv-ux-behaviors.md`](tv-ux-behaviors.md).

### Playback And Platform Code

Change shared playback contracts and plans before changing a native player.
Domain code owns stream strategy, selection policy, progress, recovery, and
observable state. Native players consume a plan, invoke platform APIs, and
report typed state back.

Use [`data-playback.md`](data-playback.md) for behavior and
[`playback-architecture.md`](playback-architecture.md) for ownership. Platform
bridges must remain narrow, lifecycle-safe, and replaceable.

## 4. Write Clear, Maintainable Code

Names and structure should explain the normal path. Comments and KDoc are for a
non-obvious contract, invariant, rationale, lifecycle or concurrency constraint,
security boundary, or platform limitation. Keep them brief and do not narrate
what the next line already says.

### Build First-Capture Diagnostics

Every new or changed failure-prone boundary must emit enough structured,
release-available diagnostics to identify the failed stage and distinguish its
reachable causes from the first captured log session. This includes remote
requests and response projection, persistence, background work, platform
bridges, lifecycle transitions, and multi-stage admission or recovery flows.

- Record the causal stage, terminal outcome, and one closed reason for every
  distinct rejection path before a lower layer collapses it into a generic UI
  state. Catch paths also record only the exception class.
- Add bounded start, handoff, and success markers where their absence is needed
  to distinguish “not invoked,” “still running,” “rejected,” and “completed.”
  Do not log routine noise that adds no diagnostic decision value.
- Preserve correlation with identity-free sequence numbers or closed state when
  an asynchronous flow can overlap, cancel, retry, or become stale.
- Never log credentials, URLs, account or media identity, titles, local paths,
  raw server/native responses, throwable messages, or other free-form external
  data. Use the existing diagnostic tags, scrubber allowlist, and safe failure
  formatter.
- Trace the exact release Logcat, Apple unified-log, or desktop-output path and
  the bounded client-log capture path. A producer is incomplete when its useful
  fields are filtered out or require an unshipped debug-only logger.
- When an investigation cannot identify the cause from its first capture,
  instrument the missing decision boundary as part of the same fix or follow-up
  before declaring the diagnostic work complete.

A generic “failed,” HTTP status, or top-level exception without the decision
that consumed it is not sufficient when several reachable causes remain.

Before asking for review, inspect every changed line:

- Every helper, type, abstraction, and defensive branch represents repeated
  knowledge, an owning boundary, or a state the application can actually reach.
- Names are accurate and concise. They do not repeat the owner or type and do
  not read like explanatory sentences.
- One-use wrappers that only rename an expression or forward unchanged arguments
  are inlined.
- Failures remain typed and actionable at the boundary that understands them.
  Catch-all fallbacks do not erase useful failure information.
- Tests assert observable behavior rather than formatting, forwarding calls,
  call order, or another implementation detail.
- Changed Kotlin contains no `!!`, hardcoded user-facing text, raw day-millis
  constants, or UI-side filtering, sorting, or grouping.

Consistent formatting is required; it is not itself a complexity smell. If an
unusual abstraction or defensive branch is necessary, explain the concrete
reason in the review description.

## 5. Test The Behavior You Changed

Automated tests are selective protection for deterministic app-owned risks,
not a default deliverable for every feature or fix. The default new-test budget
for one bounded feature, fix, or accepted finding is zero. Most user-facing app
behavior is validated manually. A feature or bug fix does not by itself require
a new regression test, and several edge cases may need design analysis without
needing one test each.

Without separate approval, add at most one new causal automated test only when
all of these are true:

- it protects a meaningful deterministic app-owned invariant whose regression
  would be costly, unsafe, recurrent, or difficult to notice manually;
- existing coverage, source tracing, compilation, and the planned manual check
  do not already provide adequate confidence; and
- it fits an existing suite, fixture, fake, and production seam without new
  support machinery.

Update existing tests when an intentional behavior change makes their
expectations stale, but keep that repair to the affected expectations and do
not multiply cases. Before adding the one optional causal test, state the exact
regression it protects and why the cheaper evidence is insufficient.

### Test Expansion Decision Gate

Pause before writing a larger test surface when one outcome or finding would
require any of the following:

- a second new automated test for one bounded feature, fix, or accepted
  finding;
- more than three new automated tests across a program-sized task;
- roughly more than 100 lines of new test or support code for one bounded
  outcome;
- a production seam introduced primarily for testing;
- a new fake or helper framework, harness, application/server bootstrap,
  scheduler model, simulator, emulator, device flow, or exhaustive permutation
  matrix; or
- test growth materially larger than the production change it protects.

Show the maintainer the distinct guarantee added by the extra coverage, the
projected files, tests and approximate lines, runtime and maintenance cost, and
the smallest adequate alternative. Wait for a decision before crossing the
trigger. These are conversation triggers, not coverage quotas. The maintainer
may choose manual validation even for a high-risk path after seeing the tradeoff.

A new automated test is most likely to justify its cost for:

- authentication, account isolation, deletion, and persistence;
- security and external request shape;
- a durable state transition or narrowly causal concurrency invariant;
- a recurring bug with a cheap deterministic reproduction; or
- a complex pure business rule that is impractical to verify reliably through
  the app.

Those categories justify consideration, not automatic coverage. Prefer manual
validation, source tracing, and applicable existing tests when they adequately
cover the risk.

Every implementation plan and handoff names the shortest exact manual path:
platform, setup, user actions, and expected result. Unless the current request
explicitly authorizes a device, emulator, simulator, or other live runtime
check, supply that checklist and report it as unperformed rather than running
it.

Do not add unit tests for Compose layout, visual styling, formatting,
localization, navigation, focus, buttons, icons, interaction, or screenshots by
default. Trace those paths in source, compile the affected platform, and
document the appropriate manual runtime check; perform it only when authorized
for the current task. Discuss permanent UI, screenshot, integration,
server-orchestration, duplicate-bootstrap, or exhaustive cross-system harnesses
with the maintainer before accepting their maintenance and runtime cost.

When the one justified test needs a fake, reuse the nearest small hand-written
fake and existing injected clock, dispatcher, factory, or storage root. Do not
introduce a production seam primarily for testing. The project does not use
MockK or Turbine. Common tests use
`kotlin.test` and `kotlinx-coroutines-test` (`runTest`); Android host tests use
JUnit 4 and Robolectric only when Android APIs are required.

### Playback And Device-Test Scope

For playback, player, and device-integration changes, zero new host or fake
tests is the default. One new test may be justified for a deterministic
app-owned contract such as pure policy, Jellyfin request/profile encoding,
persistence, reporting state, or one small exactly-once resource-ownership or
causal-concurrency seam when the general criteria above are met.

Host and fake tests do not validate native decoder selection or fallback,
rendered audio or video, HDR or tone mapping, A/V sync or frame pacing,
hardware capability, native PiP or remote behavior, engine startup/teardown
performance, or real TV focus and input. Those claims require minified-release
manual validation on representative hardware and remain explicit pending device
validation until observed.

Do not add exhaustive permutation matrices, duplicate fake-player or app
bootstraps, scheduler-hop choreography, or assertions about internal logger
strings or call order merely to approximate native confidence. If a new
automated case is justified, stop at that one causal case unless the maintainer
explicitly approves more.

Stop and reduce scope when fixture, mock, or scheduler setup is longer or more
complex than its behavioral assertions, when a small rule needs production hooks
solely for testing, or when several tests prove the same contract. Prefer the
smallest pure or causal test with a source trace, affected-platform compilation,
and manual device validation. Do not create coverage debt merely because this
manual-first policy chose zero new tests. Record it only for a high-risk,
deterministic app-owned contract that should eventually be automated but cannot
be covered proportionately with the current seams.

Comprehensive playback matrices, new native-player proxy coverage, permanent
integration or device harnesses, and broader fixtures require explicit
maintainer approval before implementation. A plan, reviewer, or test-checklist
recommendation is not that approval. Extend an existing focused test only when
the smallest added case would fail before the change and catch a meaningful
app-owned regression; coverage quantity or percentage is never a goal.

Use Android instrumented tests only for behavior that needs the runtime or a
device. Apple/native and desktop tests cover bridge mapping and lifecycle
behavior that shared fakes cannot prove; desktop persistence tests cover
missing or corrupt data and restore across instances.

| Suite | Location | Focus |
| --- | --- | --- |
| Shared domain/data | `shared-core/src/commonTest` | Playback planning, track selection, repositories, DTO mapping, discovery, stores |
| Android host | `shared-core/src/androidHostTest` | Media3 controller, Room, SharedPreferences stores, device profile |
| JVM | `shared-core/src/jvmTest` | mpv controller, tracks, subtitle style, JVM file stores |
| Shared UI | `shared-ui/src/commonTest` | Durable ViewModel state, account boundaries, shared business behavior |
| Android TV | `android-tv-app/src/test` | TV persistence, account/cache boundaries, platform business state |
| Desktop | `desktop-app/src/test` | Desktop shell behavior such as the cursor bridge |

Test files are named `<Subject>Test.kt` in the production package. Test methods
use descriptive camelCase behavior sentences without backticks or
given/when/then scaffolding.

There is no percentage-coverage target. Preserve applicable existing coverage,
but do not add cases merely to increase counts. If a high-risk deterministic
path should eventually be automated and resists the one-test budget, record one
line in the Coverage debt section of `.local/KNOWN-ISSUES.md` using
`path | why hard | escape plan`, then remove it when the path gains coverage.
Ordinary manually validated behavior does not create coverage debt.

Playback coverage keeps shared planning separate from platform mapping and
protects only app-owned planning or reporting contracts, such as stream mode,
invalid source, server failure, track and subtitle selection, resume and
reporting, fallback policy, stale subtitle activation, external request shape,
and re-plan semantics. Security tests use values resembling URLs, tokens,
usernames, titles, paths, and headers to prove that scrubbers and cleanup
boundaries do not leak them.

## 6. Review Your Own Diff

Before running the final gate:

- Read the diff as a reviewer rather than as its author.
- Confirm that every changed file belongs to the request.
- Remove imports, branches, comments, tests, and documentation made obsolete by
  the change, but leave unrelated cleanup alone.
- Re-run only applicable existing tests and any explicitly justified new test
  after a meaningful fix invalidates their evidence. Do not repeatedly run the
  entire verification suite while the implementation is still changing.
- Use [`audit.md`](audit.md) for a formal review. Compose-sensitive reviews also
  use [`compose-performance-audit.md`](compose-performance-audit.md).

Review a stable integrated candidate once. The maintainer first decides whether
each finding is a current reachable defect or contract violation, separately
from deciding whether its proposed remedy is proportionate. Combine accepted
findings into one repair pass, run only invalidated focused checks, and give the
same reviewer one focused recheck. If material blockers or new scope remain,
stop for a maintainer decision instead of starting another open-ended review
cycle. A small solo change may use this same sequence as proportional
self-review without creating an independent reviewer.

## 7. Verify The Final Candidate

Build success is necessary, but it does not prove the requested behavior. Run
one final gate after review fixes, against the exact candidate you plan to hand
off. Earlier results may be reused only when the relevant files and inputs have
not changed. For a broad or multi-lane task, run the aggregate matrix only after
review and its one recheck settle the candidate; development uses focused lane
checks. A later repair reruns only invalidated dimensions unless it changes a
shared, build, dependency, packaging, or release input that makes the aggregate
matrix stale. Do not start a new open-ended review after that final matrix.

1. Re-read the request, issue, or pull-request description and every later
   constraint.
2. Trace each named behavior, click, key, screen, route, lifecycle, and platform
   path through the final implementation.
3. Apply the readability and simplicity review above to the final diff.
4. Run applicable existing tests and any explicitly justified new causal test,
   then compile every affected platform.
5. For a broad change, run `./scripts/verify.sh`. It runs formatting checks,
   shared Android host tests, Android TV route tests, both minified Android
   release assemblies, and packaged-resource checks.
6. New shared code must link the iOS simulator framework with
   `./gradlew :shared-ui:linkDebugFrameworkIosSimulatorArm64`.
7. Run `./gradlew --stop` after Gradle work.

UI and platform changes also need the actual interaction traced and a concise
manual validation checklist; compilation alone is not enough. Exercise that
interaction only when the current request authorizes the required runtime or
device. Report every device, simulator, server, playback, or platform path that
was not manually checked.

Android physical playback, frame pacing, A/V sync, memory, and performance must
be evaluated with a minified release APK. Debug builds are useful for diagnosis,
but LibVLC is degraded there and a debug result cannot select a native-player
candidate or close physical validation.

### Platform-Specific Final Checks

- **Android mpv wrapper:** run
  `bash scripts/check-android-mpv-wrapper-source.sh` before native packaging.
  The final package gate is `scripts/verify-android-native-bundle.sh`, which
  checks source records, ABIs, native libraries, licenses, and metadata.
- **iOS dependency changes:** an iOS framework link is only a compile check. A
  full iOS app build must also resolve dependencies' `*.kotlin_resources.zip`
  bundles; otherwise a dependency may link successfully and fail during the app
  build.
- **Desktop release:** run `:desktop-app:verifyDesktopMpvBundle` and
  `:desktop-app:packageReleaseDistributionForCurrentOS`. A supported release may
  not depend on a system libmpv. The package verifier must inspect the produced
  `.app`, because Compose copies only `common`, `<os>`, and `<os>-<arch>` from
  `appResourcesRootDir`; files staged at its root are dropped. The bundle check
  must also resolve every `@loader_path` reference.

## 8. Update Documentation And Hand Off Clearly

Documentation changes are part of the implementation:

- Update the guide that owns a changed behavior or rule. Rewrite or delete its
  matching `## Why` entry when the rule changes.
- Update the root `README.md` only when a user-facing capability, limitation, or
  support status changes.
- Add release notes to `CHANGELOG.md` during the release, not as a running task
  diary.
- If you have the internal issue ledger, delete an entry when the same change
  closes it.
- Do not commit plans, review notes, generated reports, credentials, server URLs,
  or anything under `.local/`.

In the pull request or handoff, separate:

- behavior verified against the request;
- readability and simplicity review;
- builds and compiles;
- automated tests;
- manual UI, playback, device, and server checks;
- anything still unverified or deliberately out of scope.

## Specialized Dependency And Release Work

Most changes do not need this section. Use it when changing dependencies,
native runtimes, release metadata, or product versions.

### Third-Party Artifacts And IDE Imports

JellyScope trusts pinned versions and source revisions from their declared
repositories. It does not maintain a second checksum, digest, hash, file-size,
or expected-byte allowlist for dependencies, native libraries, downloaded
frameworks and runtimes, source archives, Gradle distributions, or generated
packages. Do not add `gradle/verification-metadata.xml`, checksum-only manifests,
checksum fields to mixed manifests, or replacement fingerprint gates.

Compatibility, package structure, provenance, signing and notarization, and
license and source disclosure are still required. When a dependency or
toolchain changes, confirm that the relevant Android Studio source/Javadoc or
Gradle-source import and platform build remain clean.

### Release Metadata Safety

The release-metadata preparer resolves its requested output through the nearest
existing parent. It accepts an empty unmarked directory or a marker-owned,
symlink-free rerun. It rejects files, non-empty unmarked directories, symlinks,
the repository root, the effective home directory, and `/`.
`scripts/test-prepare-release-license-metadata.sh` runs first from
`scripts/verify.sh`.

Package-producing paths record the source revision and dirty state without
blocking local Release builds. The release-artifact script requires a clean Git
candidate; binary readiness remains a separate, platform-scoped inventory check.

### Version Properties

`gradle.properties` carries four independent versions. A release updates only
the properties in its scope.

| Property | Owns | Constraint |
| --- | --- | --- |
| `jellyscope.versionName` | Product version shown to Android | SemVer; prerelease suffixes are allowed |
| `jellyscope.versionCode` | Android mobile build number | TV uses the next code; advance by two for a coordinated release |
| `jellyscope.desktop.version` | Desktop displayed version | SemVer; independent of Android and may include a prerelease suffix |
| `jellyscope.desktop.packageVersion` | Native desktop package version | macOS `jpackage` requires one to three integers with a positive first component |

The desktop values are intentionally separate: the displayed version may be a
SemVer prerelease while the native package format rejects prerelease text.

## Why

Rationale stays beside the rules it explains. Delete or rewrite an entry when
its rule changes.

- **Rationale lives with its rule.** Architectural, platform, dependency,
  playback, and verification decisions keep their rationale and rejected
  alternatives in the owning guide. A separate decision log was rejected
  because it drifts away from the operative rule.
- **Working documents stay local.** Plans, reviews, test notes, and the internal
  issue ledger are useful while work is active but do not belong in the public
  contract. Durable rules move into guides, and released changes move into the
  changelog. A standalone architecture overview was retired because it repeated
  the guides section by section.
- **Release metadata deletion is marker- and canonical-path guarded.** The
  preparer may replace only its own symlink-free output. Lexical-path recursive
  deletion was rejected because it could remove unrelated user data.
- **Simplicity is reviewed by meaning, not metrics.** The final-diff review
  catches narrative comments, speculative structure, impossible-state defenses,
  generic failures, and implementation-shape tests while preserving necessary
  domain, platform, security, and concurrency boundaries. Comment quotas,
  helper counts, coverage targets, and generated-code detectors were rejected
  because they are easy to game. Test-size thresholds pause for a maintainer
  decision; they are not quality scores or automatic rejection rules.
- **Permanent complexity requires a separate decision because outcome approval
  is not maintenance approval.** The maintainer must see the smallest adequate
  solution beside any proposed framework, analyzer, generator, subsystem, or
  large test/support surface and explicitly accept its footprint. Reviewer
  severity and broad implementation authority were rejected as substitutes for
  that product and maintenance decision.
- **Coordination follows real independence.** One owner is fastest for a
  bounded change. Exact-file parallel lanes are useful only when they remove a
  real dependency bottleneck; shared-seam ordering, one integration owner, and
  one build slot were chosen to retain that speed without file collisions or
  competing Gradle processes. Repeated whole-tree snapshots were rejected when
  scoped ownership can attribute changes safely.
- **Third-party validation is structural and provenance-based.** Pinned versions
  and source revisions, required package contents, dependency closure, signing,
  and license/source checks remain required. Maintaining a second expected-byte
  system was rejected as release and upgrade overhead.
- **Third-party material requires deliberate review.** Public availability is
  not permission to copy. Licensing and distribution decisions are owned by
  [`licensing-and-distribution.md`](../operations/licensing-and-distribution.md).
- **A review verdict does not prove device behavior.** Device validation is a
  separate gate and must remain explicit when it has not been performed.
- **Release builds select playback candidates.** Debug playback can diagnose a
  problem, but cannot select a native-player candidate or close physical
  validation because its runtime behavior differs, especially for LibVLC.
- **Release metadata has separate source and binary gates.** Platform inventory
  checks stay active for local Release builds, while the publication script owns
  the clean-source requirement. A clean binding does not prove third-party
  readiness, and a dirty local test package is never a publishable artifact.
- **Automated tests are exceptional and manual validation is the default.** A
  bounded change starts at zero new tests. At most one cheap causal case is
  added without another decision, and only for a meaningful deterministic
  app-owned risk that existing evidence and the manual path do not adequately
  protect. A second case, new test machinery, or disproportionate support code
  exposes its incremental value and cost before implementation. This keeps
  coverage focused on durable regressions instead of turning every feature and
  edge case into permanent maintenance work.
- **Diagnostics preserve decisions at their owning boundaries.** Closed
  first-capture records were chosen over generic terminal errors because a
  successful request can still fail during decode, projection, admission, or
  handoff. Logging raw responses or throwable text was rejected because it
  cannot provide a durable privacy boundary.
- **Review converges before the aggregate build.** One integrated review, one
  consolidated repair, and one focused recheck bound speculative reopening.
  Running the broad matrix only after that sequence avoids repeatedly rebuilding
  candidates that are still changing.
