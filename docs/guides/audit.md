# Architecture And Code Quality Audit

Load this for code review, architecture audit, code-quality review, performance
review, or broad "check the codebase" requests. Audits are read-only and
findings-first unless the user explicitly requests fixes.

Treat current files as the source of truth. Compare code with the active guides
and `.local/KNOWN-ISSUES.md`; distinguish new defects and regressions from
recorded open defects, accepted gaps, deliberate non-goals, and device
validations that remain pending. Re-verify any inherited finding list against
current source before planning from it.

## Route The Audit

Load only the contracts that match the requested scope:

- Architecture, state/events, source ownership, or platform bridges:
  `docs/guides/architecture.md`.
- UI, Compose, adaptive layout, accessibility, or previews: `docs/guides/ui.md`.
- Compose correctness or performance: `docs/guides/compose-performance-audit.md`
  and `docs/guides/ui.md`.
- Android TV screens, focus, navigation, or remote input:
  `docs/guides/tv-ux-behaviors.md`.
- Jellyfin API, auth, cache, settings, time, playback planning, player behavior,
  or diagnostics: `docs/guides/data-playback.md`.
- Code simplicity, comments, abstractions, defensive branches, tests,
  build/package checks, or verification strategy: `docs/guides/workflow.md`.

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
- Apply the repository's established `Critical`, `Major`, `Minor`, and
  `Suggestion` definitions from the review checklist that ships with the review
  workflow.
  Evidence level and severity are independent: uncertain impact does not become
  confirmed because its potential severity is high. That approval gate
  applies only when the user invokes that review workflow.
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
  [`workflow.md`](workflow.md#changed-code-simplicity-review) to every changed
  implementation and test path.
- No Kotlin `!!`, raw day-millis literals, or hardcoded user-facing strings are
  used. Keep any requested remediation scoped to the accepted findings.

### Data, Security, Playback, And Platforms

- URL normalization, auth headers, logging, and diagnostics are centralized and
  sanitized. Jellyfin credentials never reach untrusted absolute URLs.
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

Rationale for rules this guide states: the choice, the reason, and what was
rejected. An entry is deleted when its rule changes.

- **Stale finding lists are re-verified, never planned from.** A finding list
  that has gone unre-verified across releases is evidence of what someone once
  saw, not of what is true. One remediation cycle found three false statements
  in the project's own status record; another found an audit's largest claimed
  extraction dissolved into disjoint per-platform state on inspection.
  Re-verify each item against source before planning from it, and re-date the
  list when you do.
