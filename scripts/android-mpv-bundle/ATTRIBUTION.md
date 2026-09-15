# Android Native Attribution

JellyScope's Android applications package the project-owned `android-libmpv`
module, Jellyfin's Media3 FFmpeg decoder, and VideoLAN LibVLC. The mpv bridge is
based on the MIT-licensed `dev.jdtech.mpv:libmpv:1.0.0` wrapper source. The JellyScope native bundle AAR
is only a build input; the original `libplayer.so` is excluded.

The source/build route, selected NDK-29 `libc++_shared.so`, required ABIs and
libraries, and license/source records are in `manifest-1.0.0.txt`. The same
manifest, this notice, the source route, and the license texts are packaged as
assets in both Android applications. `scripts/check-android-mpv-wrapper-source.sh`
guards the committed source boundary; `scripts/verify-android-native-bundle.sh`
checks the release APK package contents.

| Component | Version | License | Corresponding source |
| --- | --- | --- | --- |
| mpv | 0.41.0; JellyScope bundle 1, ARM32 patches only | GPL-2.0-or-later graph; modified ImageReader file LGPL-2.1-or-later | [Exact patched source and unchanged-ABI source](https://github.com/ch4ndu/jellyscope-mpv-android/releases/download/v0.41.0-jellyscope.1/libmpv-native-0.41.0-jellyscope.1-sources.tar.gz) |
| FFmpeg | 8.1 | LGPL-2.1-or-later; GPL-3.0-or-later code enabled | https://github.com/FFmpeg/FFmpeg/tree/n8.1 |
| dav1d | 1.5.3 | BSD-2-Clause | https://code.videolan.org/videolan/dav1d/-/tree/1.5.3 |
| libplacebo | 7.360.1 | LGPL-2.1-or-later | https://github.com/haasn/libplacebo/tree/v7.360.1 |
| libass | 0.17.4 | ISC | https://github.com/libass/libass/tree/0.17.4 |
| fontconfig | 2.17.1 | MIT | https://gitlab.freedesktop.org/fontconfig/fontconfig/-/tree/2.17.1 |
| freetype | 2-14-3 | FreeType License (FTL selected) | https://github.com/freetype/freetype/tree/VER-2-14-3 |
| harfbuzz | 14.1.0 | MIT | https://github.com/harfbuzz/harfbuzz/tree/14.1.0 |
| fribidi | 1.0.16 | LGPL-2.1-or-later | https://github.com/fribidi/fribidi/tree/v1.0.16 |
| libunibreak | 6_1 | zlib | https://github.com/adah1972/libunibreak/tree/libunibreak_6_1 |
| libxml2 | 2.15.2 | MIT | https://github.com/GNOME/libxml2/tree/v2.15.2 |
| mbedTLS | 3.6.6 | Apache-2.0 | https://github.com/Mbed-TLS/mbedtls/tree/v3.6.6 |
| Lua | 5.2.4 | MIT | https://www.lua.org/versions.html#5.2 |
| libmpv-android wrapper base | 1.0.0 | MIT | https://github.com/jarnedemeulemeester/libmpv-android/tree/v1.0.0 |
| JellyScope Android wrapper patch | in-repository `android-libmpv` | Upstream MIT wrapper with MPL-2.0 project integration | `android-libmpv/UPSTREAM.md` at the JellyScope revision identified by [SOURCE_MANIFEST.md](SOURCE_MANIFEST.md) |
| Android NDK libc++ runtime | 29.0.14206865 | Apache-2.0 with LLVM exception | https://android.googlesource.com/toolchain/llvm-project/ |
| Jellyfin Media3 FFmpeg decoder | 1.9.0+1 | GPL-3.0 | https://github.com/jellyfin/jellyfin-androidx-media/tree/af9ee4e26b2045e3ea6f2ebf4a18ac8ebfeae396 |
| LibVLC Android | 3.7.5; embedded VLC `3.0.23-2-850-gac1101d2c5` | LGPL-2.1 | https://code.videolan.org/videolan/libvlcjni/-/tree/libvlcjni-3.x and https://code.videolan.org/videolan/vlc/-/tree/ac1101d2c5 |

## License-file coverage

The `licenses/` directory retains the generic license texts and adds
`MPV-NATIVE-NOTICES.txt`, preserving the published native release's component
notices and NDK notice text. It also includes Lua's notice and FreeType's FTL
text from the exact source archives; FreeType is distributed using its FTL
option. The consolidated file labels each original source path and retains its
text. Build-tool, demo and source-test notices in the release are retained for
context, not a claim that those files execute in the APK.

The [native release source archive](https://github.com/ch4ndu/jellyscope-mpv-android/releases/download/v0.41.0-jellyscope.1/libmpv-native-0.41.0-jellyscope.1-sources.tar.gz)
provides the full source context, dependency revisions and submodule mappings.
The [published notices](https://github.com/ch4ndu/jellyscope-mpv-android/releases/download/v0.41.0-jellyscope.1/libmpv-native-0.41.0-jellyscope.1-notices.zip)
and source files retain their own terms. Package filename checks establish
presence and consistency, not a legal-compliance determination.

The combined Android application is distributed under GPL-3.0 terms. Its
JellyScope-owned files remain available under MPL-2.0 and are additionally
distributed under GPL-3.0 for this Larger Work through MPL-2.0 Section 3.3.
This notice does not replace the license texts or `SOURCE_MANIFEST.md`.
