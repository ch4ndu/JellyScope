# Development Workflow

Setup and commands: [`BUILD.md`](../BUILD.md). Contract ownership:
[documentation map](../README.md). Formal review: [`audit.md`](audit.md).

## 1. Define The Change

Before editing, record behavior, affected platforms, observable success, and
relevant edge cases. Resolve behavior-changing interpretations and obtain
approval before weakening a contract. Check Git status, preserve unrelated work, and read the current
implementation, tests, owning guide, and known issues. A clean checkout must
remain understandable without local artifacts.

Consider empty/single and first/last states, extremes, clamping, missing data,
rapid input, cancellation, stale callbacks, focus restoration, dismissal, Back,
and platform differences. Do not rely on incidental callback timing or assume
every case needs an automated test.

## 2. Bound Scope And Coordination

Implement the smallest complete solution for reachable application states.
Reuse existing domain operations, UI, resources, utilities, and fixtures. Extract
repeated knowledge, not merely similar syntax; keep responsibilities cohesive
and avoid forwarding wrappers, speculative options, and unrelated cleanup.
Remove code and documentation made obsolete by the change.

### Complexity Expansion Decision Gate

Unplanned permanent frameworks, analyzers, linters, parsers, generators,
background subsystems, modules, dependencies, harnesses, duplicated bootstraps,
or disproportionately large support code require separate approval. Before
planning review or affected edits, record:

- the required outcome and evidence for the gap;
- the smallest adequate solution and focused verification;
- why a larger solution is needed;
- its files, approximate implementation/test size, dependencies, runtime, and
  maintenance cost; and
- the recommendation and explicit scope decision.

General implementation authority, plan approval, file assignment, or review
findings do not authorize an expansion that was not presented this way. Pause
only the affected work while that decision is pending; clarify uncertain cases.
An ordinary local helper within approved scope does not trigger this gate. Tests
also follow the [test expansion gate](#test-expansion-decision-gate).

### Coordination

Keep bounded work under one implementation responsibility. Delegating edits
requires explicit approval. Parallel edits also need independent outcomes,
exact owned/protected paths, acceptance checks, dependencies, and integration
order.
Complete shared contracts before dependent edits; never overlap concurrent write
ownership. Keep shared wiring, documentation, integration, final verification,
and the completion decision under one responsibility. Reports, retained context,
and review verdicts are evidence; completion requires the final gate below.

Use one build slot per checkout and batch shared-state commands at coherent
checkpoints. Record starting `HEAD` and status once, track owned/protected inputs,
and inspect the integrated candidate. Preserve unrelated drift; unexpected
protected-input changes pause dependent work.

Permission to edit does not authorize staging, committing, merging, tagging,
versioning, publishing, or releasing.

## 3. Apply The Owning Contracts

[`architecture.md`](architecture.md) owns layering, domain operations, state and
events, DI, dispatchers, cancellation, persistence boundaries, and source sets.
[`ui.md`](ui.md) and [`tv-ux-behaviors.md`](tv-ux-behaviors.md) own rendering,
resources, navigation, layout, and input. Playback changes follow
[`playback-architecture.md`](playback-architecture.md) and
[`data-playback.md`](data-playback.md); update shared plans and contracts before
platform mappings.

Third-party source, structure, and assets require file-level license review
before copying. Dependency and distribution decisions follow
[`licensing-and-distribution.md`](../operations/licensing-and-distribution.md).

## 4. Write Clear, Maintainable Code

Names and structure explain ordinary paths. Reserve brief comments and KDoc for
non-obvious contracts, invariants, rationale, lifecycle/concurrency constraints,
security boundaries, and platform limitations. In the final diff:

- Every helper, type, abstraction, and defensive branch represents repeated
  knowledge, an owning boundary, or a reachable state. Inline one-use wrappers
  that only rename expressions or forward unchanged arguments.
- Use concise, accurate names; avoid restating the containing type or owner.
- Preserve typed, actionable failures at the boundary that understands them.
- Tests assert observable behavior, not formatting, forwarding, incidental call
  order, logger strings, or implementation shape.
- Kotlin contains no `!!`, hardcoded UI text, raw day-millis constants, or
  UI-side filtering, sorting, and grouping. Apply consistent formatting.

Explain any necessary unusual abstraction or defensive branch in the change
description; do not substitute helper counts or comment quotas for review.

### First-Capture Diagnostics

Changed failure-prone request, persistence, background, platform, lifecycle,
admission, and recovery boundaries need structured release diagnostics that
distinguish causes in the first captured session.

Record stage, terminal outcome, and closed rejection reason before generic
projection; catches record exception class only. Add bounded start/handoff/
success markers when needed, and correlate overlapping, cancelled, retried, or
stale flows with identity-free sequences or closed state.

Use existing tags, the scrubber allowlist, and safe failure formatter. Never log
credentials, URLs, account/media identity, titles, paths, raw responses,
throwable messages, or free-form external data. Trace both the release platform
route and bounded client-log capture; debug-only or filtered-out fields do not
qualify. Repair a missing decision boundary or record its follow-up, without
routine noise.

## 5. Selective Tests

The default is zero new tests. Add at most one causal automated test per bounded
feature, fix, or accepted finding without separate approval, and only when:

- a meaningful deterministic application contract has a costly, unsafe,
  recurrent, or hard-to-notice regression risk;
- existing tests, source tracing, compilation, and planned manual checks provide
  insufficient confidence; and
- the case fits existing suites, fixtures, fakes, and production seams.

State the protected regression and why cheaper evidence is insufficient before
adding it. Repair expectations made stale by intentional behavior changes
without multiplying cases. Authentication, account isolation, deletion,
persistence, security/request shape, durable state transitions, causal
concurrency, recurring bugs, and complex pure policy warrant consideration, not
automatic coverage.

Native tvOS SwiftUI code in `tvos-app` has no automated test target. Do not add
SwiftUI unit, UI, or snapshot tests. Use source tracing and platform builds;
device checks still require authorization. Preserve the existing Kotlin
presenter suite in `shared-tvos`.

### Test Expansion Decision Gate

Pause for explicit approval before:

- a second new test for one bounded outcome or more than three across a program;
- roughly more than 100 lines of test/support code for one outcome;
- a production seam primarily for testing;
- a new fake/helper framework, harness, app/server bootstrap, scheduler model,
  simulator, emulator, device flow, or exhaustive matrix; or
- test/support growth materially larger than the protected production change.

Record the additional guarantee, projected files/tests/lines, runtime and
maintenance cost, and smallest adequate alternative. These are decision triggers,
not quotas; manual validation may remain the chosen approach even for high risk.
General outcome approval or review recommendations do not override this gate.
Reduce scope when fixture or scheduler setup outweighs the assertions, when
hooks exist solely for testing, or when several cases prove the same contract.

### Manual And Platform Validation

Plans and handoffs name the shortest manual path: platform, setup, actions, and
expected result. Run devices, emulators, simulators, or other live runtime checks
only when the current request authorizes them; otherwise report the checklist as
unperformed. Compose layout, styling, formatting, localization, navigation, focus,
buttons, icons, interaction, and screenshots do not require new unit tests by
default. Trace source, compile affected platforms, and supply the manual path.

Host/fake tests cannot validate native decoder selection, rendered output, HDR,
tone mapping, A/V sync, frame pacing, hardware capability, native PiP/remotes,
startup/teardown performance, or TV focus/input. Android physical playback and
performance require a minified release APK on representative hardware; debug
LibVLC is degraded and cannot select a playback candidate or close validation.

Playback tests protect deterministic application planning, request/profile
encoding, persistence, reporting, recovery, track/subtitle activation, or one
small causal/resource-ownership contract. Keep shared planning separate from
platform mapping. Extend existing coverage only for a meaningful case that fails
before the fix. Do not approximate native confidence with fake-player bootstraps,
scheduler choreography, or exhaustive permutations.

### Existing Suites

Reuse the nearest small handwritten fake and injected clock, dispatcher,
factory, or storage root. No MockK or Turbine. Shared tests use `kotlin.test`
and `kotlinx-coroutines-test` (`runTest`); Android host tests use JUnit 4 and
Robolectric only where Android APIs are required. Instrumented tests are for
runtime-only behavior and remain subject to authorization above.

| Suite | Location | Focus |
| --- | --- | --- |
| Shared domain/data | `shared-core/src/commonTest` | Planning, selection, repositories, DTOs, discovery, stores |
| Android host | `shared-core/src/androidHostTest` | Media3, Room, SharedPreferences, device profiles |
| JVM | `shared-core/src/jvmTest` | mpv, tracks, subtitle style, file stores |
| Shared UI | `shared-ui/src/commonTest` | Durable ViewModel state, accounts, shared business behavior |
| Android TV | `android-tv-app/src/test` | Persistence, account/cache boundaries, business state |
| Desktop | `desktop-app/src/test` | Shell behavior, including the cursor bridge |

Apple/native and desktop tests cover bridge mapping/lifecycle beyond shared
fakes; desktop persistence checks cover missing/corrupt data and cross-instance
restore. Security cases use credential- or identity-shaped inputs to exercise
scrubbing and cleanup. Name files `<Subject>Test.kt` in the production package
and methods as descriptive camelCase behavior sentences, without backticks or
given/when/then scaffolding.

There is no coverage percentage target. Preserve applicable existing coverage.
Record debt only for a high-risk deterministic application contract that merits
automation but lacks a proportionate seam. Follow the
[open-work ledger rule](#8-maintain-documentation) with one Coverage debt row,
`path | why hard | escape plan`, removed when covered. Choosing manual validation
does not itself create debt.

## 6. Review The Integrated Diff

Apply the [clarity rules](#4-write-clear-maintainable-code) only to changed
hunks, including staged/untracked work, using the agreed merge base for branch
reviews. Remove obsolete code/docs, redundant narration, forwarding wrappers,
unsafe casts, and speculative defenses only when behavior-neutral. Preserve
reachable failure handling, cancellation, diagnostics, compatibility paths, and
unrelated work; send uncertain cases to correctness review. Cleanup precedes
correctness review and authorizes no functional change. Apply the
[writing rules](#concise-useful-documentation) to changed prose.
Use [`audit.md`](audit.md); Compose-sensitive work also uses
[`compose-performance-audit.md`](compose-performance-audit.md).

Review one stable integrated candidate. Separate defect/reachability from remedy
proportionality, apply accepted findings in one pass, rerun invalidated checks,
then perform one focused recheck. Material blockers or new scope require a
decision, not an open-ended cycle; small changes may use proportional self-review.

## 7. Verify The Final Candidate

After review convergence, verify the exact candidate:

1. Re-read the request and later constraints; trace every named action, key,
   screen, route, lifecycle, and platform path through the final implementation.
2. Check the final diff for clarity and scope.
3. Run applicable existing and explicitly justified tests; compile each affected
   platform. Broad changes use `./scripts/verify.sh` for lint, shared Android host
   and TV route tests, minified Android releases, and packaged-resource checks.
4. New shared code must link with
   `./gradlew :shared-ui:linkDebugFrameworkIosSimulatorArm64`.
5. Run `./gradlew --stop` after Gradle work.

Build success does not prove behavior. Report request verification separately
from builds/tests and manual UI, playback, device, and server checks. State
incomplete or descoped paths explicitly and continue until completed or the gap
is explicitly accepted. Reuse evidence only while its relevant inputs remain
unchanged. Broad tasks run the aggregate matrix after integrated review and its
focused recheck; later repairs rerun invalidated dimensions, or the aggregate
matrix when shared/build/dependency/package/release inputs invalidate it. Do not
reopen an unbounded review after that matrix.

### Platform-Specific Final Checks

- **Android mpv:** run `bash scripts/check-android-mpv-wrapper-source.sh` before
  native packaging, then `scripts/verify-android-native-bundle.sh` for source
  records, ABIs, libraries, license records, and metadata. Coverage limits live in
  [`android-native-dependencies.md`](../operations/android-native-dependencies.md).
- **iOS dependencies:** link the framework and build the full app to resolve
  dependency `*.kotlin_resources.zip` bundles; a successful link alone is
  insufficient.
- **Desktop releases:** run `:desktop-app:verifyDesktopMpvBundle` and
  `:desktop-app:packageReleaseDistributionForCurrentOS`, then follow the
  [manual macOS packaging checks](../RELEASE.md#manual-macos-stages).

## 8. Maintain Documentation

Documentation is a current contract:

- Rewrite or delete the matching `## Why` entry when its rule changes. Rationale
  belongs beside the rule, not in a separate decision log.
- Update the root README for changed capabilities, limitations, or support.
  `CHANGELOG.md` records releases at release time, not ongoing tasks.
- Keep plans, scratch work, automation configuration, and review reports local
  and ignored. Never commit credentials, server URLs, or `.local/`. The optional
  `.local/KNOWN-ISSUES.md` is the sole open-work ledger: delete closed rows in the
  closing change, close validation rows when observed and coverage rows when
  covered, and keep it near or below 150 rows and shrinking.
- Apply the writing rules below to changed documentation; do not expand a
  feature change into a repository-wide prose rewrite.
- List updated docs in the final report, or state `No docs updated;
  behavior/rules unchanged.` Include verification and outstanding gaps as above.

### Concise, Useful Documentation

- Lead with the answer, action, or contract. Use descriptive headings that help
  readers find a real task or question; keep prerequisites and limitations nearby.
- State each fact once in its owning guide and link to it elsewhere. Merge
  repetition and remove stale text before substantial additions; keep guides
  within their current order of magnitude. Keep entry points short; use the
  existing documentation map rather than another index.
- Use concrete actors, precise technical terms, and direct sentences. Cut filler,
  promotional claims, rhetorical questions, formulaic contrasts, and repeated
  introductions or summaries. Keep uncertainty and evidence qualifiers intact.
- Use lists, tables, emphasis, and examples only when they improve comprehension.
  Avoid decorative formatting and prose that narrates obvious code. Do not impose
  punctuation bans, sentence patterns, or arbitrary length and score targets.
- Add an example only when it answers a practical question better than prose.
  Include the necessary context, working directory, prerequisites, and expected
  result; label code fences with the language. Verify commands against current
  source and report whether they were run. Keep unsafe actions clearly marked.
- Preserve behavioral contracts, exceptions, safety and approval gates, platform
  limits, licensing, source citations, and relevant historical qualifications.
  Before removing unique information, confirm it is obsolete or link its surviving
  owner. Brevity must not change the meaning or conceal an unverified claim.
- Before handoff, check that the affected questions remain answerable, links and
  anchors resolve, and the text matches the implementation. Report the changes
  and remaining gaps briefly; a docs-only edit needs no application build.

These rules selectively incorporate [OpenClaw's diff cleanup](https://github.com/openclaw/openclaw/blob/main/.agents/skills/deslop/SKILL.md),
[LLM Docs Optimizer's task-oriented documentation](https://github.com/alonw0/llm-docs-optimizer/blob/main/skills/llm-docs-optimizer/SKILL.md),
and [Stephen Turner's prose cleanup](https://github.com/stephenturner/skill-deslop/blob/main/SKILL.md).
They do not install those skills or require scoring tools, generated indexes,
or removal of provenance to improve a documentation score.

## Dependency And Release Changes

Trust pinned versions and source revisions from declared repositories. Do not
add a second checksum/digest/hash/file-size/expected-byte allowlist for downloaded
dependencies, native runtimes, source archives, Gradle distributions, or packages,
including `gradle/verification-metadata.xml` or fingerprint fields in manifests.
Compatibility, structure, provenance, signing/notarization, and license/source
checks remain required. Dependency/toolchain changes must preserve relevant
Android Studio source/Javadoc or Gradle-source imports and platform builds.

The [release metadata runbook](../RELEASE.md#source-and-license-metadata) owns its
output-path safety rules. `scripts/test-prepare-release-license-metadata.sh` runs
first in `scripts/verify.sh`. Package builds record revision and dirty state
without blocking local Release builds; release-artifact creation requires a
clean Git candidate. Binary inventory readiness remains a separate platform gate.

`gradle.properties` carries independent release versions; update only those in
scope:

| Property | Purpose | Constraint |
| --- | --- | --- |
| `jellyscope.versionName` | Android displayed version | SemVer, including prereleases |
| `jellyscope.versionCode` | Android mobile build number | See the [coordinated Android version-code rule](../RELEASE.md#android-release-checklist) |
| `jellyscope.desktop.version` | Desktop displayed version | Independent SemVer, including prereleases |
| `jellyscope.desktop.packageVersion` | Native desktop package version | macOS requires 1–3 integers, first positive |

## Why

- Native tvOS uses source tracing and compilation to limit ongoing SwiftUI test
  maintenance. That boundary does not justify deleting shared Kotlin coverage.
- **Scope and test expansions need separate decisions:** outcome approval does
  not establish the value of permanent maintenance cost.
- **Manual validation complements selective tests:** native output and physical
  interaction cannot be established by compilation or shared fakes.
- **Review precedes the aggregate build:** rebuilding changing candidates does
  not verify the final result.
- **Release gates stay distinct:** clean source binding does not prove native
  license or publication readiness; neither does a dirty local package.
- **Displayed and package versions differ:** macOS package versions reject
  prerelease text allowed by displayed SemVer.
