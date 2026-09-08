# Development

- [Build and run](docs/BUILD.md): toolchain, modules, platform commands, and installation.
- [Development workflow](docs/guides/workflow.md): scope, implementation, tests,
  review, verification, and documentation updates.
- [Documentation map](docs/README.md): architecture, UI, playback, and platform contracts.
- [Licensing and distribution](docs/operations/licensing-and-distribution.md):
  source licensing, third-party review, and distribution requirements.

## Change Requirements

Keep changes scoped to the approved behavior and preserve unrelated work.
Describe the resulting behavior, affected platforms, verification evidence, and
remaining runtime checks. Follow the workflow's approval requirements before
trading away existing behavior or adding permanent support infrastructure.

`main` is the only long-lived branch; feature and fix branches are merged and
deleted. Git and release operations require their own authorization.
