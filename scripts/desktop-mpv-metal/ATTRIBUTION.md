# Source-built Metal native attribution

This document supplements the Standard runtime attribution. In the Metal
runtime notices and its source sidecar, that Standard `ATTRIBUTION.md` is
retained unchanged one directory above this supplement's `metal/ATTRIBUTION.md`
copy. The Standard document describes only the Standard graph. Its statements that the CoreAudio patch is the only mpv
source change and that Vulkan is disabled do not apply to the Metal graph.

The Metal graph uses the Standard mpv 0.41.0, FFmpeg 9.0.2 and transitive source
inventory, with the additions and Vulkan-Headers override declared in
[sources-macos-arm64.tsv](sources-macos-arm64.tsv). It applies the same CoreAudio
patch, then `mpvkit-moltenvk.patch` and `metal-context-resize.patch` to mpv. It
enables Vulkan in mpv and libplacebo through MoltenVK, which is linked directly
without a separate Vulkan loader. It also applies the recorded libplacebo
static-glslang lookup correction described below. The combined graph retains
GPL-compatible terms; these records supplement the original license texts,
which remain unchanged.

| Input | Use in the macOS build | Original notices |
| --- | --- | --- |
| MoltenVK 1.4.2 | Dynamic Vulkan-to-Metal runtime | `licenses/moltenvk/LICENSE` |
| glslang | Static GLSL/SPIR-V compiler embedded in libplacebo | `licenses/glslang/LICENSE.txt` and the full generated `glslang_tab.cpp` notice, including its Bison exception |
| cereal | Embedded MoltenVK serialization headers | `licenses/cereal/`, including the original nested external-library notices |
| SPIRV-Cross | Embedded static shader translation code | `licenses/spirv-cross/` |
| SPIRV-Tools and SPIRV-Headers | MoltenVK's selected static library and generated/header inputs; no complete standalone tools binary is shipped | `licenses/spirv-tools/` and `licenses/spirv-headers/` |
| Vulkan-Headers | Headers from MoltenVK's declared revision, also used by libplacebo | `licenses/vulkan-headers/` |
| Vulkan-Tools and Volk | Declared upstream preparation inputs retained for source correspondence; the macOS scheme does not build them | `licenses/vulkan-tools/` and `licenses/volk/` |
| MPVKit snapshot | Source of the caller-owned-layer context patch, bound to its exact archive member | `licenses/mpvkit-patch/LICENSE`; the patched mpv context retains its upstream LGPL terms |

`metal-context-resize.patch` modifies that mpv context under its existing terms.
The recipe records its one-line libplacebo static-library lookup correction and
retains the unmodified source archive. Runtime build records identify actual
commands, features and toolchain settings. The Metal source sidecar retains the
merged archives, original notices, both context patches and rebuild recipes.
A dirty local app does not establish clean JellyScope release-source binding.
