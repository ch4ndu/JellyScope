# IINA-built macOS mpv runtime

JellyScope redistributes the 69 arm64 dylibs published for IINA 1.4.0 at
<https://iina.io/dylibs/1.4.0/arm64/>. The files are copied unchanged into the
runtime cache and are modified only in the staged application resources to
replace their non-system install names and dependencies with sibling
`@loader_path` references and restore an ad-hoc signature after relocation.

The binary identifies mpv source revision `c0dd2b3`. Source and build routes:

- IINA 1.4.0 source and build instructions: <https://github.com/iina/iina/tree/v1.4.0>
- IINA's mpv build formulas: <https://github.com/iina/homebrew-mpv-iina>
- mpv revision: <https://github.com/mpv-player/mpv/tree/c0dd2b3>

IINA publishes its project under GPL-3.0 and mpv declares GPL-2.0-or-later.
The corresponding license texts are packaged beside this notice. Each
upstream component retains its own license and notices.

JellyScope relies on IINA's and the component projects' ordinary open-source
licensing and source declarations. Those declarations were not independently
audited. JellyScope-owned source remains MPL-2.0; the combined macOS package is
made available under GPL-compatible terms through MPL-2.0 Section 3.3.
