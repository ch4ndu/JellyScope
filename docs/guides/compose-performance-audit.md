# Compose Performance And Correctness Audit

Use with [`ui.md`](ui.md) and the relevant feature guide. Static audits do not
run profiling or benchmarks unless measurement is explicitly authorized.

Use the report format and evidence levels from `docs/guides/audit.md`. Use visible,
searchable labels: `[RECOMPOSITION]`, `[STABILITY]`, `[HOT_STATE_READ]`,
`[EFFECT_LIFECYCLE]`, `[LAZY_VIRTUALIZATION]`, `[FLOW_SCOPE]`, `[MAIN_THREAD]`,
`[IMAGE_PIPELINE]`, and `[BUILD_PERF]`.

## Evidence Discipline

- Trace the exact screen and interaction through state production, collection,
  composition, layout, drawing, effects, and platform callbacks.
- Recomposition, allocation, debug slowness, or a missing optional optimization
  is not a confirmed regression without an invalidation, blocking, allocation,
  lifetime, or effect mechanism. Large/repeated avoidable allocation is a
  defect because GC and memory bandwidth affect smoothness.
- Require a reason for work remaining on Main. UI/framework calls that require
  it, state writes, and cheap bounded transforms may stay; decode, projection,
  pixel sampling, I/O, crypto, and large-input layout math belong off Main.
  Evaluate the slowest supported devices rather than one fast device.
- Before accepting a product tradeoff, check for an unnecessarily expensive
  implementation of the same feature. Attribution alone does not justify
  degrading the feature.
- Recommend only optimizations that preserve UI, focus, playback, and state.
  Use `Measurement needed` and the smallest release-mode measurement when
  runtime evidence must distinguish a real cost from harmless work.

## Stability And Inputs

- Inspect the Kotlin/Compose compiler configuration and
  `compose-stability.conf` before applying stability advice. The project uses
  strong skipping: restartable composables with unstable inputs can skip, while
  unstable parameters are compared by identity.
- Treat configured `com.jellyscope.core.domain.model.*` stability as a
  correctness promise: flag mutable public state/collections, but do not request
  annotations or configuration merely to make a composable skippable.
- Look for repeated equal-but-new lists, maps, UI models, painters, interaction
  sources, and other inputs on hot paths. Identity churn can still prevent
  skipping under strong skipping.
- Flag broad screen state passed through deep trees when a smaller projection
  isolates invalidation. Request manual callback `remember` only for an
  identity-sensitive consumer or proven compiler-memoization gap.

## State Reads And Derived Work

- Apply the projection, lowest-scope collection, lazy-key, and hot-read rules in
  `docs/guides/ui.md`; do not replace ViewModel/domain projections with
  composition-local caching.
- Flag duplicate or broad state reads that invalidate unrelated UI. Collect
  branch-specific flows only while the branch, mode, screen, or component that
  needs them is active.
- Use `derivedStateOf` only when its inputs change more frequently than the value
  consumed by UI. Flag it when output changes at the same rate or it hides work
  owned by a ViewModel or UseCase.
- Use `[HOT_STATE_READ]` when rapidly changing scroll, animation, drag, or
  gesture state is read in composition but only placement or drawing depends on
  it. Do not move the read when UI structure genuinely depends on the value.
- Flag state writes during composition, backwards writes, and unbounded
  measure/layout feedback such as unconditional `onGloballyPositioned` updates.

## Effects, Flows, And Lifecycle

- Verify every `LaunchedEffect`, `DisposableEffect`, and `produceState` key.
  Ownership changes must restart work; callbacks that should not restart a
  long-lived effect use `rememberUpdatedState`.
- Flag effect restart storms, per-item effects that can be centralized,
  coroutines launched during composition, missing disposal, stale native/player
  callbacks, and jobs that outlive their screen, session, route entry, or server.
- Use `snapshotFlow` for high-frequency snapshot state observed as a stream and
  verify downstream operators coalesce or filter to the consumer's required
  frequency.
- Verify `stateIn`/`shareIn` and upstream Flow lifetimes match their consumers.
  Flag duplicate network, database, player, focus, or scheduler work caused by
  multiple collectors.
- Preserve cancellation and bound retries, debounce work, prefetch, playback
  recovery, image jobs, and concurrent projections.

## Lazy Layouts, Images, And Animation

- Apply the exact stable-key and container/item identity rules in `ui.md`.
  Verify keys remain stable across refresh, pagination, placeholders, route
  restoration, and duplicate media across containers.
- Use compatible `contentType` values for materially different row shapes.
  Flag eager materialization, bulk repeated content inside one lazy item,
  same-direction unbounded nested scrolling, and placeholders whose initial
  zero size defeats layout and scroll behavior.
- Verify pagination and near-end triggers reject duplicate requests, stop at
  terminal pages, and avoid scanning or rebuilding the whole display model on
  every scroll tick.
- Keep repeated media cells on their plain render path unless actively animated,
  focused, selected, or participating in a transition. Flag per-frame object
  creation, state publication, or expensive drawing setup.
- Verify repeated media requests use stable models/cache identity, authenticated
  project-owned loading, appropriate Jellyfin image variants, bounded decode
  dimensions, and exact precision where the display size is known. Flag
  accidental full-resolution loading in lists, grids, queues, or TV ribbons.
- Verify image and ambient-color work is cancellable, cached to the correct item
  and server owner, and performed off Main when it decodes or samples pixels.
- A pass that samples pixels to produce a summary — palette, ambient color,
  blur, thumbnail, dominant color — must decode to the smallest dimensions the
  result needs, never the source's. Size the bitmap by its output, not its
  input: running off Main does not make a full-resolution software copy
  acceptable, and full-screen sources are the worst case precisely because no
  list or grid rule constrains them.

## Threads, Resources, And Build Configuration

- Check dispatcher choice at the actual call site against the Main-thread rule
  above; a suspend boundary does not establish background execution.
- Look for unbounded remembered collections, retained bitmaps/media lists,
  stale route/focus/player jobs, undisposed platform observers, and caches whose
  lifetime exceeds their screen, server, or playback-session owner.
- Apply common checks to Android mobile/TV, iOS, and desktop Compose paths.
  Keep R8, Baseline Profile, Macrobenchmark, and Android tracing findings
  explicitly platform-scoped.
- Report disabled required release optimization as a defect. Report missing
  optional Baseline Profile or benchmark coverage as a `[BUILD_PERF]`
  `Suggestion` or `Measurement needed`, not as confirmed app slowness.

## Why

- **Performance evidence is platform-specific:** a clean measurement elsewhere
  cannot certify iOS. Shared player frame-timing fixes require a demonstrated
  mechanism and an iOS release-build check; the iOS integration has no equivalent
  recomposition counter or frame-stats dump.

Primary references:

- [Compose performance best practices](https://developer.android.com/develop/ui/compose/performance/bestpractices)
- [Compose stability and strong skipping](https://developer.android.com/develop/ui/compose/performance/stability/strongskipping)
- [Compose phases](https://developer.android.com/develop/ui/compose/phases)
- [Compose side effects](https://developer.android.com/develop/ui/compose/side-effects)
- [Lazy lists and grids](https://developer.android.com/develop/ui/compose/lists)
