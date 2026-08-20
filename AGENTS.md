# JellyScope Agent Instructions

JellyScope is a production Kotlin Multiplatform + Compose Multiplatform client
for Jellyfin servers: shared domain logic across platforms, Compose where it is
stable, playback planning kept separate from platform players.

Read this file first. It is a router — **do not eagerly load the documents below.**
Load only what the task needs.

## Load Only When Needed

| Task | Load |
| --- | --- |
| Architecture, layering, state/events, source layout, platform bridges | `docs/guides/architecture.md` |
| Shared playback decision pipeline and platform/backend ownership | `docs/guides/playback-architecture.md` |
| Implementation, scope discipline, tests, verification gate | `docs/guides/workflow.md` |
| Code review, architecture or code-quality audit | `docs/guides/audit.md` |
| UI, Compose, layout, previews, recomposition | `docs/guides/ui.md` |
| Compose performance or correctness audit | `docs/guides/compose-performance-audit.md` + `docs/guides/ui.md` |
| TV screens, focus, remote/D-pad behavior | `docs/guides/tv-ux-behaviors.md` |
| Jellyfin API, auth, cache, time, all playback behavior | `docs/guides/data-playback.md` |
| Why something is built this way; rejected alternatives | the `## Why` section of the guide that owns the rule (rows above) |
| Open defects, pending device validation, deferred work, coverage debt | `.local/KNOWN-ISSUES.md` |
| What shipped in which release | `CHANGELOG.md` |
| Release and signing runbook | `docs/RELEASE.md` |
| Bundled native dependencies, licenses, corresponding source | `docs/operations/android-native-dependencies.md` |
| Project license, store-distribution licensing, source obligations, trademarks | `docs/operations/licensing-and-distribution.md` |

`docs/README.md` maps the full documentation set and says which file owns what.

The committed documentation set is deliberately tool-agnostic. Personal
automation, assistant configuration, research notes, plans, and generated
review artifacts stay local and ignored; a clean checkout must remain fully
understandable and buildable without them.

## Always-On Rules

- Read existing code and docs before editing; follow local patterns over
  inventing new ones, and keep changes scoped to the request.
- All coding work follows `docs/guides/workflow.md` for scope and simplicity,
  terse KDoc/comments, and proportional focused-test discipline.
- Prefer `commonMain`; use platform code only when necessary, behind
  project-owned interfaces or narrow `expect`/`actual` boundaries.
- Preserve the layering: data repositories → domain UseCases/Actions →
  ViewModels → UI. ViewModels use UseCases and Actions, never repositories.
- ViewModels expose state and events through deliberate Flow primitives; load
  `docs/guides/architecture.md` before changing that surface.
- Run non-trivial ViewModel/presenter/repository work (decode, mapping, sort,
  grouping, I/O) off the Main launch context on an injected dispatcher; keep
  StateFlow writes and cheap bounded transforms on Main.
- Keep Jellyfin API details behind repositories and API facades, and playback
  planning separate from player implementations.
- Never use Kotlin `!!`; use safe calls, early returns, defaults, or smart-cast
  locals.
- Reuse existing UseCases, Actions, repositories, composables, utilities,
  strings, icons, and theme dimensions before creating new ones.
- If a fix would trade away an approved behavior, ask first.
- Run `./gradlew --stop` after any Gradle work.

## Default Engineering Gate

Before calling a coding task complete:

- Re-read the latest request and its constraints.
- Check each named behavior, screen, flow, and platform path against the
  implementation; for UI work, trace the exact clicks/actions mentioned and
  confirm they are wired.
- Treat build/compile success as necessary but not sufficient.
- State anything incomplete or descoped explicitly, and keep working unless the
  user has accepted the gap.
- Separate request verification from build/compile verification in the final
  response.

`docs/guides/workflow.md` owns the full verification gate and test strategy.

## Coordination And Authority

Follow [`docs/guides/workflow.md`](docs/guides/workflow.md) plus these
coordination rules:

- Obtain explicit user approval before delegating edits. Give each implementation
  role a bounded area of ownership.
- Context and review roles are advisory. An implementation report, retained
  context, green build, or review verdict is evidence, not completion.
- The primary agent owns scope, integration, documentation, final verification,
  and the completion decision against the current source.
- Permission to edit does not imply permission to stage, commit, merge, tag,
  version, publish, or release.

## Documentation Update Rule

The docs are a live contract, not a log: every edit that adds must also retire
what it superseded. When behavior, architecture, cache shape, playback or
platform-player policy, UI layout rules, test strategy, or verification
workflow changes:

1. Update the guide that owns the changed rule (routing table above). When a
   rule changes, rewrite or delete its `## Why` entry in the same edit — a
   `## Why` entry may never describe a rule the guide no longer states.
2. Open work lives only in `.local/KNOWN-ISSUES.md`. Closing an issue deletes its
   row in the same change that closes it: device-validation rows are deleted
   when validated, coverage-debt rows when covered. It is a live TODO list,
   never a record of what was once open.
3. There is no per-release documentation ritual. `CHANGELOG.md` gets a release
   section at release time; nothing else accumulates per release.
4. One owner per fact: change each fact in exactly one place. Another file may
   link to the owner but never restate the fact.
5. Respect size ceilings: each guide stays within its current order of
   magnitude, and `.local/KNOWN-ISSUES.md` stays at or under roughly 150 issue
   rows and must shrink over time as rows close. When an edit would materially
   grow a file, remove or merge stale content before adding.
6. In the final response, list the docs updated or state `No docs updated;
   behavior/rules unchanged.`
