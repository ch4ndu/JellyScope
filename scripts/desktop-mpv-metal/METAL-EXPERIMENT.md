# Metal runtime recipes

The packaging route is `../build-desktop-mpv-runtime.sh --metal`. It builds the
Standard source graph with a Metal-only source overlay, pinned MoltenVK and
static glslang, applying `mpvkit-moltenvk.patch` and `metal-context-resize.patch`
to the complete mpv source build. The overlay declares exact archives and original
notices; the runtime validator checks the native graph and recorded feature flags.
Build caches and package resource directories remain separate from Standard.
Commands and explicit Metal source-sidecar selection are in
[BUILD.md](../../docs/BUILD.md#macos-metal-experiment).

The following notes describe the retained prebuilt investigation recipe, which
is not accepted by the source-built packaging validator.

`../prepare-desktop-mpv-metal-experiment.py` prepares an isolated macOS 15 arm64
runtime. It never replaces the normal desktop bundle or enables Metal by default.
Preparation and launch commands are in [BUILD.md](../../docs/BUILD.md#macos-metal-experiment).

The recipe retains the non-GPL/non-Samba MPVKit 1.0.0 release graph declared by
MPVKit revision `288527dffbc6d3e63cce147fc7b520c64a791603`, except its dav1d
archive. It copies assembly-enabled dav1d 1.5.4 from the validated desktop source
build. That build's source pins, options, notices and ABI configuration are
retained under `provenance/desktop-source-build`; those records describe the
baseline build, not the experimental linked graph. `candidate.json` records the
selected MPVKit inputs, and `provenance/link-command.json` records the actual link.

The only replaced mpv object is `video_out_vulkan_context_moltenvk.m.o`. It uses
mpv 0.41.0 sources, the pinned MPVKit caller-owned-layer patch, Libplacebo headers
from the linked XCFramework slice, and Vulkan-Headers at the revision pinned in
`sources-macos-arm64.tsv`. That revision reports `VK_HEADER_VERSION` 337, as did
the headers used for the tested diagnostic object; full header equivalence is not
recorded. The desktop configuration supplies the same macOS pthread ABI;
Vulkan is enabled for this object and standard Metal surface headers replace the
MoltenVK umbrella include. This does not rebuild or change mpv's other objects.
Preparation stops if the pinned mpv or dav1d version changes; those changes, or
changed public struct layouts, require a fresh ABI review.

`metal-context-resize.patch` modifies MPVKit's LGPL-2.1-or-later mpv context;
it retains those terms, not JellyScope's MPL source terms. It refreshes native
video/subtitle rectangles and requests redraw when the borrowed layer changes
size. A 50 ms bound on the existing VO wait detects paused resize without a new
thread or callback lifetime. The upstream context license header remains intact
in the retained patched source. mpv and Vulkan-Headers license texts, both
patches, source archives, compile/link commands and logs, and the recipe are
retained with each candidate.

The original graph's dav1d archive was inadequate for the tested 8K AV1 workload.
Preparation requires the existing source build's recorded `enable_asm=true`
option; its retained dav1d configure log records the same option. This is
configuration evidence, not a performance guarantee. Binary validation checks arm64, install names, allowed
dynamic dependencies, deployment target, and ad-hoc signatures. It does not
establish playback behavior, source completeness for every upstream binary,
or redistribution readiness. A distributable Metal package needs its own full
native source/notice inventory and packaging review; this recipe remains local. The source-built stager rejects this prebuilt graph; retained comparison apps
remain historical test evidence.
