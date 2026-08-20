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

## 4. Write Code A Person Can Read

Names and structure should explain the normal path. Comments and KDoc are for a
non-obvious contract, invariant, rationale, lifecycle or concurrency constraint,
security boundary, or platform limitation. Keep them brief and do not narrate
what the next line already says.

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

Tests protect business behavior and durable state, not line counts. Add the
smallest test that would fail for the bug or missing rule and pass for the
intended behavior.

Tests are expected for:

- business rules and repository behavior;
- authentication, account isolation, deletion, and persistence;
- durable state transitions and narrowly causal concurrency;
- external request shape and playback planning;
- a new user-visible failure, fallback, or degraded path, including the typed
  diagnostic emitted by the boundary that catches it. Its test must show that
  the diagnostic identifies the failed stage, survives the sanitized
  collection allowlist, and contains no raw values, messages, or stacks.

Do not add unit tests for Compose layout, visual styling, formatting,
localization, navigation, focus, buttons, icons, interaction, or screenshots by
default. Trace those paths in source, compile the affected platform, and perform
the appropriate runtime check. Discuss permanent UI, screenshot, integration,
server-orchestration, duplicate-bootstrap, or exhaustive cross-system harnesses
with the maintainer before accepting their maintenance and runtime cost.

Prefer small hand-written fakes with injected clocks, dispatchers, factories,
and storage roots. The project does not use MockK or Turbine. Common tests use
`kotlin.test` and `kotlinx-coroutines-test` (`runTest`); Android host tests use
JUnit 4 and Robolectric only when Android APIs are required.

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

There is no percentage-coverage target. Authentication, deletion, persistence,
account isolation, and external request shape must retain meaningful behavioral
coverage. If a business path resists focused testing, record one line in the
Coverage debt section of `.local/KNOWN-ISSUES.md` using
`path | why hard | escape plan`, then remove it when the path gains coverage.

Playback tests keep shared planning separate from platform mapping. Cover the
relevant stream mode, invalid source, server failure, track and subtitle
selection, resume and reporting, fallback, stale subtitle activation, external
request, and re-plan semantics. Security tests use values resembling URLs,
tokens, usernames, titles, paths, and headers to prove that scrubbers and
cleanup boundaries do not leak them.

## 6. Review Your Own Diff

Before running the final gate:

- Read the diff as a reviewer rather than as its author.
- Confirm that every changed file belongs to the request.
- Remove imports, branches, comments, tests, and documentation made obsolete by
  the change, but leave unrelated cleanup alone.
- Re-run focused tests after each meaningful fix. Do not repeatedly run the
  entire verification suite while the implementation is still changing.
- Use [`audit.md`](audit.md) for a formal review. Compose-sensitive reviews also
  use [`compose-performance-audit.md`](compose-performance-audit.md).

## 7. Verify The Final Candidate

Build success is necessary, but it does not prove the requested behavior. Run
one final gate after review fixes, against the exact candidate you plan to hand
off. Earlier results may be reused only when the relevant files and inputs have
not changed.

1. Re-read the request, issue, or pull-request description and every later
   constraint.
2. Trace each named behavior, click, key, screen, route, lifecycle, and platform
   path through the final implementation.
3. Apply the readability and simplicity review above to the final diff.
4. Run the focused business-behavior tests and compile every affected platform.
5. For a broad change, run `./scripts/verify.sh`. It runs formatting checks,
   shared Android host tests, Android TV route tests, both minified Android
   release assemblies, and packaged-resource checks.
6. New shared code must link the iOS simulator framework with
   `./gradlew :shared-ui:linkDebugFrameworkIosSimulatorArm64`.
7. Run `./gradlew --stop` after Gradle work.

UI and platform changes also need the actual interaction traced or exercised;
compilation alone is not enough. Report any device, simulator, server, playback,
or platform path that was not checked.

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

Package-producing paths bind source metadata to a clean Git candidate. Binary
readiness remains a separate check for third-party inventory and legal review.

### Version Properties

`gradle.properties` carries four independent versions. A release updates only
the properties in its scope.

| Property | Owns | Constraint |
| --- | --- | --- |
| `jellyscope.versionName` | Product version shown to Android | SemVer; prerelease suffixes are allowed |
| `jellyscope.versionCode` | Android installable identity | Increment when the release needs distinct Android packages |
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
  because they are easy to game.
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
- **Release metadata has separate source and binary gates.** A clean source
  binding does not prove third-party inventory or legal readiness, and neither
  gate is weakened merely to make packaging pass.
- **Regression coverage follows business risk.** Authentication, deletion,
  persistence, isolation, concurrency, and external request shape keep focused
  tests. UI presentation uses source tracing, platform compilation, and relevant
  runtime checks unless a durable test provides clear value.
