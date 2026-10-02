# Project-built macOS mpv runtime

JellyScope builds the arm64, macOS 15 runtime from the exact upstream inputs in
`sources-macos-arm64.tsv` using `../build-desktop-mpv-runtime.sh`. The only mpv
source change is the unmodified upstream CoreAudio patch
`c5d391adba7bd024954d0df1e0405f5749f4d4ca`. Mach-O install IDs and dependency paths
are relocated for packaging and the staged libraries receive ad-hoc signatures.
Configured release signing replaces those signatures.

| Component | Version or revision | Upstream terms |
| --- | --- | --- |
| mpv | 0.41.0 plus the CoreAudio patch above | GPL-2.0-or-later; individual permissive files |
| FFmpeg | 9.0.2 | GPL-3.0-or-later with the selected GPL/version3 configuration |
| dav1d | 1.5.4 | BSD-2-Clause |
| libplacebo | 7.360.1 | LGPL-2.1-or-later |
| libass | 0.17.5 | ISC |
| FreeType | 2.14.3 | FTL or GPL-2.0-or-later; included driver notices |
| HarfBuzz | 14.5.1 | Old MIT; Microsoft MIT and Unicode notices |
| FriBidi | 1.0.17 | LGPL-2.1-or-later; Unicode data |
| libunibreak | 8.0 | Zlib; Unicode data |
| Little CMS | 2.19.1 | MIT |
| zimg | 3.0.6 | WTFPL-2.0 |
| zlib | 1.3.2 | Zlib |
| libarchive | 3.8.9 | BSD-2-Clause, BSD-3-Clause and embedded BLAKE2 notice |
| uchardet | 0.0.8 | MPL-1.1 or GPL-2.0-or-later or LGPL-2.1-or-later |
| libpng | 1.6.59 | libpng license |
| Brotli | 1.2.0 | MIT |
| glad | 73db193f853e2ee079bf3ca8a64aa2eaf6459043 | MIT; Khronos Apache-2.0 and permissive header notices |
| Jinja | 15206881c006c79667fe5154fe80c01c65410679 | BSD-3-Clause; build-only |
| MarkupSafe | 297fc8e356e6836a62087949245d09a28e9f1b13 | BSD-3-Clause; build-only |
| fast_float | 97b54ca9e75f5303507699d27c6b4f4efe4641a1 | MIT or Apache-2.0 or BSL-1.0; embedded headers |
| Vulkan-Headers | 450bd2232225d6c7728a4108055ac2e37cef6475 | Apache-2.0 C headers; some archive files Apache-2.0 or MIT; `parse_dependency.py` MIT |
| Meson | 1.12.1 | Apache-2.0; build-only |

Vulkan-Headers supplies the compile-time declarations required by libplacebo's
disabled-Vulkan stubs. It uses libplacebo's pinned gitlink; Vulkan loader and
renderer libraries remain disabled.

The original component texts are retained under `licenses/`. Notice excerpts
from FFmpeg's IJG files, libarchive's compression/BLAKE2 files and glad's
Khronos platform header preserve their original comments. Unicode's original
License V3 is retained from <https://www.unicode.org/license.txt>. This software
is based in part on the work of the Independent JPEG Group. JellyScope makes
no changes to FFmpeg's IJG files. Portions of this software are copyright ©
The FreeType Project (<https://www.freetype.org/>). All rights reserved.

The recipe enables shared libmpv, Cocoa/OpenGL/render API, VideoToolbox-GL,
CoreAudio, subtitle shaping, networking, native FFmpeg codecs and filters,
color management, HDR/tone mapping and Dolby Vision metadata reshaping.
FFmpeg enables GPL and version3 and disables nonfree components. mpv's required
Cocoa Swift bridge and helpers are covered by the retained mpv sources and
notices. Standalone players, CocoaCB, macOS media-player and Touch Bar support,
external scripting, Vulkan, Rust libdovi, disc access and optional autodetected
libraries are disabled. `mpv-notices/build/` in a built
runtime records the actual configuration, toolchain and native version strings;
the manifest alone is not evidence of a successful build. Little CMS's 2.19.1
source archive reports `2.19` in its upstream Meson/configure package metadata;
the source pin and actual reported package version are retained separately.

Corresponding-source output retains every declared archive, embedded gitlink
input, upstream patch, recipe and actual build record. JellyScope-owned source
remains MPL-2.0. The combined macOS package uses the accepted GPL-compatible
Larger Work route through MPL-2.0 Section 3.3; it is not an LGPL-only package.
