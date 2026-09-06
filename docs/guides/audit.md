# Architecture And Code Quality Audit

Audits are read-only and findings-first unless fixes are explicitly authorized.

Treat current files as the source of truth. Compare code with the active guides;
distinguish new defects and regressions from documented gaps, deliberate
non-goals, and device validations that remain pending. Re-verify any inherited
finding list against current source before planning from it.

## Route The Audit

Use the [documentation map](../README.md) to select the relevant contracts.

For a whole-project audit, inspect the repository in staged subsystem passes:

1. shared-core data, domain, DI, state, and tests;
2. shared UI and Compose behavior;
3. Android mobile and Android TV shells, services, playback, and packaging;
4. iOS, tvOS presenter/SwiftUI, and desktop platform boundaries;
5. build configuration, release packaging, tests, and code-versus-doc contracts.

## Evidence Discipline

- Trace the affected call path, not only the changed line. For a named screen or
  interaction, follow state production, collection, rendering, side effects,
  platform callbacks, and persistence where applicable.
- Report `Confirmed` only when code or a documented project contract proves the
  defect. Report a concrete but unmeasured mechanism as `Risk`. Use
  `Measurement needed` when runtime evidence is required to establish impact.
- Do not report hypothetical failures without a reachable mechanism. Do not
  classify accepted prerequisites, deliberate non-goals, or unexecuted manual
  checks as code defects.
- Severity describes impact: **Critical** is a reachable security breach,
  destructive data loss, or widespread unusability; **Major** breaks an
  important supported flow or release contract; **Minor** is a bounded
  correctness, documentation, or maintenance defect; **Suggestion** is an
  optional improvement without a demonstrated defect. Evidence level is
  independent: high potential severity does not make an uncertain impact
  confirmed. Review approval gates apply only when that workflow is invoked.
- Prefer the smallest correction that preserves existing behavior, layering,
  platform support, and accepted scope. Do not turn observations into unrelated
  refactors.

## Required Checks

### Architecture, State, And Code Quality

- ViewModels and presenters use UseCases/Actions rather than repositories,
  stores, DAOs, native services, or Jellyfin transport queries.
- DTOs and vendor/platform types remain at their owning boundaries; DI wiring is
  complete for every affected entry point and target.
- Flow primitives match replay, fan-out, buffering, cancellation, lifetime, and
  synchronous `.value` semantics. Read/modify/write state changes are atomic.
- Async work rejects stale results, preserves cancellation, bounds retries and
  parallel work, and does not depend on unsafe callback ordering.
- CPU-heavy projection and decode work does not run on Main. Network, database,
  and file work uses the project-owned background dispatcher policy; StateFlow
  writes and cheap bounded transforms remain on Main.
- Files and abstractions have cohesive ownership. Treat the project's roughly
  500-line guidance as a review trigger, not a violation by itself. Flag
  duplication, excessive indirection, or complexity only when it creates a
  concrete correctness, maintenance, or testability cost.
- Apply the changed-code simplicity review in
  [`workflow.md`](workflow.md#4-write-clear-maintainable-code) to every changed
  implementation and test path.
- No Kotlin `!!`, raw day-millis literals, or hardcoded user-facing strings are
  used. Keep any requested remediation scoped to the accepted findings.

### Data, Security, Playback, And Platforms

- URL normalization, auth headers, logging, and diagnostics are centralized and
  sanitized. Jellyfin credentials never reach untrusted absolute URLs.
- Treat the documented Apple credential-persistence policy in
  [`data-playback.md`](data-playback.md#persistence-and-account-isolation) as an
  accepted constraint,
  not a finding. Audit concrete violations of that boundary or newly applicable
  external requirements, not the documented plaintext-at-rest tradeoff itself.
- Server/user-scoped caches and persistent stores respect account boundaries,
  register for logout cleanup, and cannot be repopulated by stale in-flight work.
- API/cache/settings details stay below repositories and focused domain
  operations; mappings preserve the current documented transport and fallback
  contracts.
- Shared playback planning owns stream strategy, selection, reporting, recovery,
  and player-visible state. Native players consume shared plans and translate
  callbacks into project-owned typed state.
- Platform code lives in the highest valid source set. Interfaces and
  `expect`/`actual` seams remain narrow, lifecycle-safe, replaceable, and
  testable with shared fakes where practical.

### UI, Interaction, And Tests

- Plain UI renders supplied state and callbacks; projections, filtering,
  sorting, grouping, row construction, and playback decisions stay outside
  composables.
- User-facing text is localized, theme dimensions/tokens are reused, icon-only
  controls expose semantics, touch targets remain reasonable, and adaptive
  layouts preserve their documented content/inset behavior.
- Lazy layouts preserve stable identity and virtualization. Compose performance
  findings follow `docs/guides/compose-performance-audit.md`.
- TV paths preserve D-pad, BACK, SELECT, media-key, focus restoration, and
  stable-key behavior for the exact affected route.
- Tests verify observable behavior and state rather than incidental call order.
  Shared tests avoid platform APIs; platform behavior is verified in its owning
  source set or runtime path.
- Build success is not behavior verification. Report which click, key, route,
  playback, lifecycle, package, or platform paths remain unverified.

## Report Format

Use a searchable rule label and the primary line that proves the finding. Add
other path/line evidence when the mechanism crosses files:

```text
**[RULE] Severity - short title** - path/to/File.kt:123
Evidence: Confirmed | Risk | Measurement needed
Impact: concrete correctness, performance, security, or maintenance mechanism.
Fix: smallest project-compatible correction.
```

Order findings by severity, then confidence and impact. End with totals by
severity, evidence level, and category, followed by important compliant areas
and unverified runtime/platform risk. If no findings remain, say so explicitly;
do not invent optional optimization work.

## Why

- **Recheck findings against current source:** historical observations can become
  stale after releases; retained reports cannot establish a current defect.
