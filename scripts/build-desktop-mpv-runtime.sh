#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
# Build tools are provisioned by the caller; this script never installs them.
# --metal builds the separate MoltenVK/glslang graph in its own cache.
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(dirname "$script_dir")"
notices="$script_dir/desktop-mpv-bundle"
metal_notices="$script_dir/desktop-mpv-metal"
die() { echo "ERROR: $*" >&2; exit 1; }
variant=standard
if [[ "${1:-}" == --metal ]]; then variant=metal; shift; fi
[[ $# -eq 0 ]] || die "Usage: build-desktop-mpv-runtime.sh [--metal]"
if [[ "$variant" == metal ]]; then
    # The Standard cache variable never selects the Metal cache, nor the reverse.
    cache_root="${JELLYSCOPE_MPV_METAL_RUNTIME_CACHE_ROOT:-$repo_root/.local/native/desktop-mpv-metal}"
    cache_owner=desktop-mpv-metal-v1
    complete=metal-source-build-validated
else
    cache_root="${JELLYSCOPE_MPV_RUNTIME_CACHE_ROOT:-$repo_root/.local/native/desktop-mpv}"
    cache_owner=desktop-mpv-v1
    complete=source-build-validated
fi
bundle_tool() {
    if [[ "$variant" == metal ]]; then bash "$script_dir/prepare-desktop-mpv-bundle.sh" --metal "$@"
    else bash "$script_dir/prepare-desktop-mpv-bundle.sh" "$@"; fi
}
[[ "$(uname -s)/$(uname -m)" == Darwin/arm64 ]] || die "Desktop mpv requires an Apple Silicon macOS build host"
for tool in python3 curl xcrun make cmake ninja pkg-config autoconf automake autoreconf codesign otool lipo patch; do
    command -v "$tool" >/dev/null || die "Required build tool missing: $tool"
done
if [[ "$variant" == metal ]]; then
    command -v xcodebuild >/dev/null || die "Required build tool missing: xcodebuild"
fi
libtoolize="$(command -v glibtoolize || command -v libtoolize || true)"
[[ -n "$libtoolize" ]] || die "GNU libtool (glibtoolize) must be provisioned by the build owner"
"$libtoolize" --version | grep -q 'GNU libtool' || die "GNU libtool is required"
python3 - "$cache_root" "$repo_root" "$cache_owner" <<'PY'
import os, pathlib, sys
if sys.version_info < (3, 10): sys.exit('Python 3.10 or later is required')
p, repo = map(pathlib.Path, sys.argv[1:3])
owner = sys.argv[3]+'\n'
if not p.is_absolute() or p != p.resolve() or len(p.parts) < 4 or any(c.isspace() for c in str(p)): sys.exit('Unsafe native cache root')
if any(x.is_symlink() for x in (p, *p.parents)): sys.exit('Symlink in native cache root')
if p in (repo, *repo.parents, pathlib.Path.home()) or (repo in p.parents and repo/'.local' not in p.parents):
    sys.exit('Native cache must be dedicated, outside source paths (or under .local)')
if any(x.is_symlink() for x in p.rglob('*')): sys.exit('Symlink in native cache')
marker = p/'.jellyscope-desktop-mpv-cache'
if p.exists() and any(p.iterdir()) and not (marker.is_file() and marker.read_text() == owner):
    sys.exit('Refusing a nonempty cache not owned by this desktop mpv variant')
p.mkdir(parents=True, exist_ok=True)
marker.write_text(owner)
PY
mkdir "$cache_root/build.lock" 2>/dev/null || die "Native build cache is locked; inspect the previous invocation before removing its lock"
work="$(mktemp -d "$cache_root/work.XXXXXX")"
cleanup() { rm -rf "$work"; rmdir "$cache_root/build.lock"; }
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
# Keep stdout exclusively for the path consumed by the staging caller.
exec 3>&1 1>&2
sdk="$(xcrun --sdk macosx --show-sdk-path)"
export SDKROOT="$sdk" MACOSX_DEPLOYMENT_TARGET=15.0
export CC="$(xcrun --find clang)" CXX="$(xcrun --find clang++)" OBJC="$(xcrun --find clang)"
export AR="$(xcrun --find ar)" RANLIB="$(xcrun --find ranlib)" NM="$(xcrun --find nm)"
# Match mpv's xcrun selection and compiler-relative Swift library lookup.
swiftc="$(xcrun --find swiftc)"
swift_lib_dir="$(dirname "$(dirname "$swiftc")")/lib/swift/macosx"
[[ -x "$swiftc" && -d "$swift_lib_dir" ]] || die "Selected Xcode Swift compiler/library directory is unavailable"
unset SWIFT_LIB_DYNAMIC
unset CPATH C_INCLUDE_PATH CPLUS_INCLUDE_PATH OBJC_INCLUDE_PATH LIBRARY_PATH DYLD_LIBRARY_PATH DYLD_FALLBACK_LIBRARY_PATH
unset CFLAGS CXXFLAGS CPPFLAGS OBJCFLAGS LDFLAGS PKG_CONFIG_PATH PKG_CONFIG_SYSROOT_DIR CMAKE_PREFIX_PATH CMAKE_TOOLCHAIN_FILE CONFIG_SITE PYTHONPATH
unset LIBS CPP CXXCPP LD LD_LIBRARY_PATH SDK_DIR CMAKE_INCLUDE_PATH CMAKE_LIBRARY_PATH CMAKE_FRAMEWORK_PATH
unset ASFLAGS MAKEFLAGS MFLAGS PKG_CONFIG_SYSTEM_INCLUDE_PATH PKG_CONFIG_SYSTEM_LIBRARY_PATH PKG_CONFIG_TOP_BUILD_DIR
export PKG_CONFIG="$(command -v pkg-config)"
unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE
export GIT_CEILING_DIRECTORIES="$cache_root" PYTHONNOUSERSITE=1
prefix="$work/prefix"
export CFLAGS="-O2 -arch arm64 -mmacosx-version-min=15.0 -isysroot $sdk -fPIC"
export CXXFLAGS="$CFLAGS" OBJCFLAGS="$CFLAGS" ASFLAGS="$CFLAGS" CPPFLAGS="-I$prefix/include"
export LDFLAGS="-arch arm64 -mmacosx-version-min=15.0 -isysroot $sdk -L$prefix/lib -Wl,-headerpad_max_install_names"
export PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig:$prefix/share/pkgconfig"
mkdir -p "$work/identity" "$work/src" "$work/build" "$prefix" "$cache_root/archives"
cp "$0" "$script_dir/prepare-desktop-mpv-bundle.sh" "$work/identity/"
cp "$notices/sources-macos-arm64.tsv" "$notices/manifest-mpv-0.41.0-macos15-arm64.txt" "$work/identity/"
sources="$notices/sources-macos-arm64.tsv"
overlay=""
if [[ "$variant" == metal ]]; then
    mkdir "$work/identity/metal"
    cp "$metal_notices/sources-macos-arm64.tsv" "$metal_notices/manifest-mpv-metal-0.41.0-macos15-arm64.txt" \
        "$metal_notices/mpvkit-moltenvk.patch" "$metal_notices/metal-context-resize.patch" \
        "$metal_notices/ATTRIBUTION.md" "$work/identity/metal/"
    # Metal rows add to the base graph and replace same-named base rows (only Vulkan-Headers).
    overlay=" $(tail -n +2 "$metal_notices/sources-macos-arm64.tsv" | cut -f1 | tr '\n' ' ')"
    sources="$work/identity/metal/sources-merged.tsv"
    awk -F '\t' -v overlay="$overlay" 'NR == 1 || index(overlay, " " $1 " ") == 0' \
        "$notices/sources-macos-arm64.tsv" > "$sources"
    tail -n +2 "$metal_notices/sources-macos-arm64.tsv" >> "$sources"
fi
{
    printf 'architecture=arm64\nminimum-os=15.0\nsdk=%s\n' "$sdk"
    xcrun --sdk macosx --show-sdk-version
    "$CC" --version
    printf 'swiftc=%s\nswift-library-directory=%s\nswift-target=arm64-apple-macosx15.0\n' "$swiftc" "$swift_lib_dir"
    "$swiftc" -version
    xcrun ld -version_details
    for tool in python3 make cmake ninja pkg-config autoconf automake autoreconf "$libtoolize"; do
        command -v "$tool"
        "$tool" --version
    done
    if [[ "$variant" == metal ]]; then
        command -v xcodebuild
        xcodebuild -version
    fi
} > "$work/identity/toolchain.txt" 2>&1
runtime="$cache_root/runtime"
if [[ -f "$runtime/mpv-notices/build/COMPLETE" ]] &&
    diff -qr "$work/identity" "$runtime/mpv-notices/build/identity" >/dev/null &&
    bundle_tool --validate-only "$runtime"; then
    echo "Reusing validated desktop mpv $variant source build" >&2
    printf '%s\n' "$runtime" >&3
    exit 0
fi
# Archive records, not installed package-manager libraries, are the only dependency inputs.
while IFS=$'\t' read -r component version url archive unpack destination role licenses; do
    [[ "$component" != component ]] || continue
    cached="$cache_root/archives/$archive"
    if [[ ! -f "$cached" ]]; then
        curl --fail --location --silent --show-error "$url" --output "$work/$archive"
        mv "$work/$archive" "$cached"
    fi
    [[ "$role" != patch ]] || continue
    row_notices="$notices/licenses/$component"
    [[ "$overlay " != *" $component "* ]] || row_notices="$metal_notices/licenses/$component"
    # The repository patch must equal the pinned MPVKit member; the snapshot is not unpacked.
    binding=-
    if [[ "$component" == mpvkit-patch ]]; then
        [[ "$role" == build-rule && "$destination" == - ]] || die "Unexpected MPVKit build-rule record"
        binding=Sources/BuildScripts/patch/libmpv/0001-player-add-moltenvk-context.patch
    fi
    python3 - "$cached" "$unpack" "$destination" "$work/src" "$row_notices" "$licenses" \
        "$binding" "$metal_notices/mpvkit-moltenvk.patch" <<'PY'
import pathlib, posixpath, sys, tarfile
archive, root, destination, src, notices, licenses, member, repo_patch = sys.argv[1:]
relative = pathlib.PurePosixPath(destination)
if destination != '-' and (relative.is_absolute() or '..' in relative.parts): sys.exit('Unsafe source destination: '+destination)
dest = str(pathlib.Path(src)/destination)
with tarfile.open(archive) as t:
    for name in licenses.split(','):
        if t.extractfile(root+'/'+name).read() != (pathlib.Path(notices)/name).read_bytes():
            sys.exit('Source license differs from reviewed notice: '+name)
    if member != '-' and t.extractfile(root+'/'+member).read() != pathlib.Path(repo_patch).read_bytes():
        sys.exit('Repository patch differs from its pinned upstream member: '+member)
    if destination == '-': sys.exit(0)
    members = t.getmembers()
    links = {m.name.rstrip('/') for m in members if m.issym() or m.islnk()}
    for m in members:
        p = pathlib.PurePosixPath(m.name)
        if p.is_absolute() or '..' in p.parts or p.parts[0] != root or not (m.isfile() or m.isdir() or m.issym() or m.islnk()):
            sys.exit('Unsafe archive member: '+m.name)
        if any(str(x) in links for x in p.parents): sys.exit('Archive member traverses a link')
        if m.issym() or m.islnk():
            target = posixpath.normpath(posixpath.join(posixpath.dirname(m.name), m.linkname) if m.issym() else m.linkname)
            if not target.startswith(root+'/'): sys.exit('Archive link escapes source root')
    pathlib.Path(dest).parent.mkdir(parents=True, exist_ok=True)
    staging = pathlib.Path(dest).parent/('.unpack-'+pathlib.Path(dest).name)
    staging.mkdir()
    t.extractall(staging)
    extracted = staging/root
    target = pathlib.Path(dest)
    if target.exists(): target.rmdir()  # Empty gitlink directory only.
    extracted.rename(target)
    staging.rmdir()
PY
done < "$sources"
patch_file="$cache_root/archives/mpv-coreaudio-c5d391adba7bd024954d0df1e0405f5749f4d4ca.patch"
grep -q '^From c5d391adba7bd024954d0df1e0405f5749f4d4ca ' "$patch_file" || die "Unexpected CoreAudio patch identity"
patch -d "$work/src/mpv" -p1 < "$patch_file"
if [[ "$variant" == metal ]]; then
    # The MPVKit context (bound to its pinned member above), then the local resize repair.
    patch -d "$work/src/mpv" -p1 < "$metal_notices/mpvkit-moltenvk.patch"
    patch -d "$work/src/mpv" -p1 < "$metal_notices/metal-context-resize.patch"
fi
meson=(python3 "$work/src/meson/meson.py")
jobs="$(sysctl -n hw.ncpu)"
record="$work/build-options.txt"
run() { printf '%q ' "$@" >> "$record"; printf '\n' >> "$record"; "$@"; }
meson_build() {
    local name="$1"; shift
    run "${meson[@]}" setup "$work/build/$name" "$work/src/$name" --prefix="$prefix" --libdir=lib \
        --buildtype=release --default-library=shared --wrap-mode=nofallback -Dauto_features=disabled "$@"
    run ninja -C "$work/build/$name" -j "$jobs"
    run ninja -C "$work/build/$name" install
}
cmake_build() {
    local name="$1"; shift
    run cmake -S "$work/src/$name" -B "$work/build/$name" -G Ninja -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="$prefix" -DCMAKE_INSTALL_LIBDIR=lib -DBUILD_SHARED_LIBS=ON \
        -DCMAKE_OSX_ARCHITECTURES=arm64 -DCMAKE_OSX_DEPLOYMENT_TARGET=15.0 -DCMAKE_OSX_SYSROOT="$sdk" \
        -DCMAKE_C_COMPILER="$CC" -DCMAKE_CXX_COMPILER="$CXX" -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
        -DCMAKE_PREFIX_PATH="$prefix" -DCMAKE_FIND_ROOT_PATH="$prefix;$sdk" \
        -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=ONLY -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=ONLY \
        -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=ONLY -DCMAKE_FIND_ROOT_PATH_MODE_PROGRAM=NEVER \
        -DCMAKE_FIND_USE_PACKAGE_REGISTRY=OFF -DCMAKE_FIND_USE_SYSTEM_PACKAGE_REGISTRY=OFF \
        -DCMAKE_INSTALL_NAME_DIR="$prefix/lib" "$@"
    run cmake --build "$work/build/$name" -j "$jobs"
    run cmake --install "$work/build/$name"
}
autotools_build() {
    local name="$1"; shift
    mkdir -p "$work/build/$name"
    (cd "$work/build/$name"; run "$work/src/$name/configure" --prefix="$prefix" --enable-shared --disable-static "$@"
     run make -j "$jobs"; run make install)
}
(cd "$work/src/zlib"; run ./configure --prefix="$prefix" --shared; run make -j "$jobs"; run make install)
cmake_build brotli -DBROTLI_DISABLE_TESTS=ON
cmake_build libpng -DPNG_STATIC=OFF -DPNG_FRAMEWORK=OFF -DPNG_TESTS=OFF -DPNG_TOOLS=OFF -DZLIB_ROOT="$prefix"
meson_build freetype -Dharfbuzz=disabled -Dzlib=external -Dpng=enabled -Dbrotli=enabled -Dmmap=enabled
meson_build harfbuzz -Dfreetype=enabled -Dcoretext=enabled -Dtests=disabled -Dutilities=disabled \
    -Dsubset=disabled -Draster=disabled -Dvector=disabled -Dgpu=disabled
meson_build fribidi -Ddocs=false -Dbin=false -Dtests=false
autotools_build libunibreak
meson_build libass -Dcoretext=enabled -Dasm=enabled -Dlibunibreak=enabled
meson_build dav1d -Denable_tests=false -Denable_tools=false -Denable_asm=true
meson_build little-cms2 -Dtests=disabled
(cd "$work/src/zimg"; run env LIBTOOLIZE="$libtoolize" autoreconf -fiv)
autotools_build zimg
# Only zlib and SDK bzip2/lzma/iconv; no ambient compression or crypto libraries.
autotools_build libarchive --disable-bsdtar --disable-bsdcat --disable-bsdcpio --disable-bsdunzip \
    --without-openssl --without-nettle --without-libb2 --without-lz4 --without-zstd \
    --without-xml2 --without-expat --without-mbedtls --with-zlib --with-bz2lib --with-lzma \
    --with-iconv --without-libiconv-prefix --without-lzo2 --without-cng --enable-posix-regex-lib=libc
cmake_build uchardet -DBUILD_BINARY=OFF -DBUILD_STATIC=OFF
placebo_vulkan=(-Dvulkan=disabled)
placebo_glslang=disabled
mpv_metal=()
if [[ "$variant" == metal ]]; then
    mvk="$work/src/moltenvk"
    vk_headers="$work/src/libplacebo/3rdparty/Vulkan-Headers"
    # The declared pins must be exactly MoltenVK's own ExternalRevisions.
    for pin in cereal:cereal Vulkan-Headers:vulkan-headers SPIRV-Cross:spirv-cross SPIRV-Tools:spirv-tools \
        SPIRV-Headers:spirv-headers Vulkan-Tools:vulkan-tools Volk:volk; do
        declared="$(awk -F '\t' -v c="${pin#*:}" '$1 == c { print $2 }' "$metal_notices/sources-macos-arm64.tsv")"
        [[ -n "$declared" && "$(tr -d '[:space:]' < "$mvk/ExternalRevisions/${pin%%:*}_repo_revision")" == "$declared" ]] \
            || die "MoltenVK ExternalRevisions differs from the declared ${pin#*:} source"
    done
    # Lay out MoltenVK's External tree as fetchDependencies would, from declared archives only.
    [[ ! -e "$mvk/External/Vulkan-Headers" ]] || die "Unexpected MoltenVK Vulkan-Headers directory"
    cp -R "$vk_headers" "$mvk/External/Vulkan-Headers"
    python3 - "$mvk/Templates/spirv-tools/build.zip" "$mvk/External/SPIRV-Tools" <<'PY'
import pathlib, sys, zipfile
archive, dest = sys.argv[1:]
with zipfile.ZipFile(archive) as z:
    for info in z.infolist():
        p = pathlib.PurePosixPath(info.filename)
        if p.parts[:1] == ('__MACOSX',): continue  # fetchDependencies deletes these after unzipping.
        if p.is_absolute() or '..' in p.parts or ((info.external_attr >> 16) & 0o170000) == 0o120000:
            sys.exit('Unsafe SPIRV-Tools template member: '+info.filename)
        z.extract(info, dest)
PY
    # Upstream schemes directly: arm64 only, unsigned, derived data inside this work tree.
    # KEEP_CACHE=Y: the finish step must not delete Intermediates (the live build.db) mid-build.
    xcb=(env -u CC -u CXX -u OBJC -u AR -u RANLIB -u NM -u CFLAGS -u CXXFLAGS -u OBJCFLAGS -u ASFLAGS -u CPPFLAGS -u LDFLAGS xcodebuild)
    xcode_settings=(ARCHS=arm64 ONLY_ACTIVE_ARCH=YES MACOSX_DEPLOYMENT_TARGET=15.0 CODE_SIGNING_ALLOWED=NO
        CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY= LD_RUNPATH_SEARCH_PATHS= KEEP_CACHE=Y)
    external=(-project ExternalDependencies.xcodeproj -scheme ExternalDependencies-macOS -destination generic/platform=macOS
        -configuration Release -derivedDataPath External/build/Intermediates/macOS)
    package=(-project MoltenVKPackaging.xcodeproj -scheme 'MoltenVK Package (macOS only)' -destination generic/platform=macOS
        -configuration Release -derivedDataPath "$work/build/moltenvk-package")
    (cd "$mvk"
     "${xcb[@]}" "${external[@]}" "${xcode_settings[@]}" -showBuildSettings > "$work/moltenvk-external-settings.txt"
     run "${xcb[@]}" "${external[@]}" "${xcode_settings[@]}" build) 2>&1 | tee "$work/moltenvk-external-build.log"
    if [[ ! -d "$mvk/External/build/Latest" ]]; then
        # fetchDependencies' own finish steps, against the retained output only.
        (cd "$mvk"; run env PROJECT_DIR=. CONFIGURATION=Release SKIP_PACKAGING= KEEP_CACHE=Y \
            bash -ec '. ./Scripts/create_ext_lib_xcframeworks.sh; . ./Scripts/package_ext_libs_finish.sh') \
            2>&1 | tee -a "$work/moltenvk-external-build.log"
    fi
    (cd "$mvk"
     "${xcb[@]}" "${package[@]}" "${xcode_settings[@]}" -showBuildSettings > "$work/moltenvk-package-settings.txt"
     run "${xcb[@]}" "${package[@]}" "${xcode_settings[@]}" build) 2>&1 | tee "$work/moltenvk-package-build.log"
    mvk_package="$mvk/Package/Latest/MoltenVK"
    mkdir -p "$prefix/lib/pkgconfig" "$prefix/include"
    cp "$mvk_package/dynamic/dylib/macOS/libMoltenVK.dylib" "$prefix/lib/libMoltenVK.dylib"
    cp -RL "$mvk_package/include/MoltenVK" "$prefix/include/MoltenVK"
    # Isolated loader record: the MoltenVK-declared headers, linked directly to MoltenVK.
    python3 - "$vk_headers" "$prefix" > "$prefix/lib/pkgconfig/vulkan.pc" <<'PY'
import pathlib, re, sys
headers, prefix = sys.argv[1:]
core = (pathlib.Path(headers)/'include/vulkan/vulkan_core.h').read_text()
patch = re.search(r'^#define VK_HEADER_VERSION (\d+)$', core, re.M)
api = re.search(r'^#define VK_HEADER_VERSION_COMPLETE VK_MAKE_API_VERSION\(0, (\d+), (\d+), VK_HEADER_VERSION\)$', core, re.M)
if not (patch and api): sys.exit('Unrecognized Vulkan header version')
print('prefix='+prefix, 'libdir=${prefix}/lib', 'includedir=${prefix}/include', '',
      'Name: Vulkan-Loader', 'Description: MoltenVK, linked directly by the isolated Metal mpv build',
      'Version: '+'.'.join((api[1], api[2], patch[1])), 'Libs: -L${libdir} -lMoltenVK',
      'Cflags: -I'+headers+'/include -I${includedir}', sep='\n')
PY
    # Static GLSL compiler only: no optimizer, external sources, tests, tools or HLSL.
    cmake_build glslang -DBUILD_SHARED_LIBS=OFF -DENABLE_SPIRV=ON -DENABLE_OPT=OFF -DBUILD_EXTERNAL=OFF \
        -DALLOW_EXTERNAL_SPIRV_TOOLS=OFF -DGLSLANG_TESTS=OFF -DENABLE_GLSLANG_BINARIES=OFF -DENABLE_HLSL=OFF \
        -DGLSLANG_ENABLE_INSTALL=ON 2>&1 | tee "$work/glslang-build.txt"
    python3 - "$work/glslang-build.txt" <<'PY'
import re, sys
log = open(sys.argv[1]).read()
unused = re.search(r'Manually-specified variables were not used by the project:\n\n((?:    .+\n)+)', log)
ignored = set(unused.group(1).split()) if unused else set()
requested = {'BUILD_SHARED_LIBS', 'ENABLE_SPIRV', 'ENABLE_OPT', 'BUILD_EXTERNAL', 'ALLOW_EXTERNAL_SPIRV_TOOLS',
             'GLSLANG_TESTS', 'ENABLE_GLSLANG_BINARIES', 'ENABLE_HLSL', 'GLSLANG_ENABLE_INSTALL'}
if ignored & requested: sys.exit('glslang ignored requested options: '+' '.join(sorted(ignored & requested)))
PY
    [[ -f "$prefix/lib/libglslang.a" ]] || die "Static glslang library was not installed"
    for library in "$prefix"/lib/*.dylib; do
        case "${library##*/}" in *glslang* | *SPIRV*) die "glslang installed a dynamic library: $library" ;; esac
    done
    # Meson's static find_library ignores -L, and libplacebo's glslang lookup alone omits the SDK dirs.
    run python3 - "$work/src/libplacebo/src/glsl/meson.build" "$record" <<'PY'
import sys
path, record = sys.argv[1:]
old = "cxx.find_library('glslang', required: required, static: static)"
new = "cxx.find_library('glslang', required: required, static: static, dirs: vulkan_lib_dirs)"
text = open(path).read()
if text.count(old) != 1: sys.exit('Unexpected libplacebo glslang lookup in '+path)
open(path, 'w').write(text.replace(old, new))
with open(record, 'a') as r:
    print('# source edit: src/libplacebo/src/glsl/meson.build', '-'+old, '+'+new, sep='\n', file=r)
PY
    # libplacebo searches the SDK-style prefix for the static glslang libraries.
    placebo_vulkan=(-Dvulkan=enabled -Dvk-proc-addr=enabled -Dvulkan-sdk="$prefix")
    placebo_glslang=enabled
    mpv_metal=(-Dvulkan=enabled -Dmoltenvk=enabled)
fi
meson_build libplacebo -Dopengl=enabled -Dgl-proc-addr=enabled -Dlcms=enabled -Ddovi=enabled \
    "${placebo_vulkan[@]}" -Dd3d11=disabled -Dglslang="$placebo_glslang" -Dshaderc=disabled -Dlibdovi=disabled -Ddemos=false
mkdir -p "$work/build/ffmpeg"
# FFmpeg's --disable-autodetect skips the -liconv fallback; link the SDK library explicitly.
(cd "$work/build/ffmpeg"; run "$work/src/ffmpeg/configure" --prefix="$prefix" --arch=aarch64 --cpu=generic --target-os=darwin \
    --cc="$CC" --cxx="$CXX" --sysroot="$sdk" --extra-cflags="$CFLAGS $CPPFLAGS" --extra-ldflags="$LDFLAGS" --extra-libs=-liconv \
    --enable-shared --disable-static --enable-pic --disable-autodetect --enable-gpl --enable-version3 \
    --disable-nonfree --disable-programs --disable-doc --disable-avdevice --enable-network \
    --enable-pthreads --enable-securetransport --enable-videotoolbox --enable-audiotoolbox \
    --enable-libdav1d --enable-libass --enable-libzimg --enable-zlib --enable-bzlib --enable-iconv
 run make -j "$jobs"; run make install)
meson_build mpv --sysconfdir=/etc -Dlibmpv=true -Dcplayer=false -Dgpl=true -Dbuild-date=false -Dlua=disabled \
    -Dgl=enabled -Dplain-gl=enabled -Dcocoa=enabled -Dgl-cocoa=enabled -Dvideotoolbox-gl=enabled \
    -Dcoreaudio=enabled -Dlcms2=enabled -Dzimg=enabled -Dzlib=enabled -Dlibarchive=enabled \
    -Duchardet=enabled -Diconv=enabled -Dswift-build=enabled -Dswift-flags='-target arm64-apple-macosx15.0' \
    -Dmacos-cocoa-cb=disabled -Dmacos-media-player=disabled -Dmacos-touchbar=disabled \
    -Dmacos-10-15-4-features=enabled -Dmacos-11-features=enabled \
    -Dmacos-11-3-features=enabled -Dmacos-12-features=enabled ${mpv_metal[@]+"${mpv_metal[@]}"}
stage="$work/runtime"
mkdir -p "$stage/mpv-notices/build"
cp -R "$notices/." "$stage/mpv-notices/"
cp -R "$work/identity" "$stage/mpv-notices/build/"
cp "$record" "$stage/mpv-notices/build/options.txt"
extra_pc=""
if [[ "$variant" == metal ]]; then
    # Metal-only notices, attribution supplement, sources and patches beside the shared base notices.
    mkdir "$stage/mpv-notices/metal"
    cp -R "$metal_notices/licenses" "$metal_notices/sources-macos-arm64.tsv" \
        "$metal_notices/manifest-mpv-metal-0.41.0-macos15-arm64.txt" "$metal_notices/mpvkit-moltenvk.patch" \
        "$metal_notices/metal-context-resize.patch" "$metal_notices/ATTRIBUTION.md" "$stage/mpv-notices/metal/"
    cp "$work/moltenvk-external-settings.txt" "$work/moltenvk-external-build.log" "$work/moltenvk-package-settings.txt" \
        "$work/moltenvk-package-build.log" "$work/glslang-build.txt" "$prefix/lib/pkgconfig/vulkan.pc" "$stage/mpv-notices/build/"
    cp "$work/build/glslang/CMakeCache.txt" "$stage/mpv-notices/build/glslang-CMakeCache.txt"
    extra_pc=vulkan
fi
# Retain actual configurations/feature lists and installed package versions.
cp "$work/build/ffmpeg/config.h" "$work/build/ffmpeg/config_components.h" "$work/build/ffmpeg/ffbuild/config.mak" "$stage/mpv-notices/build/"
cp "$work/build/mpv/config.h" "$stage/mpv-notices/build/mpv-config.h"
cp "$work/build/libplacebo/src/include/libplacebo/config.h" "$stage/mpv-notices/build/libplacebo-config.h"
for component in mpv libplacebo dav1d; do
    cp "$work/build/$component/meson-info/intro-buildoptions.json" "$stage/mpv-notices/build/$component-options.json"
    cp "$work/build/$component/meson-logs/meson-log.txt" "$stage/mpv-notices/build/$component-configure.txt"
done
for pc in libavcodec libavformat libavutil libavfilter libswscale libswresample dav1d libass freetype2 harfbuzz fribidi libunibreak lcms2 zimg zlib libarchive uchardet libpng libbrotlidec libplacebo mpv $extra_pc; do
    pc_version="$(pkg-config --modversion "$pc")"
    [[ -n "$pc_version" ]] || die "Missing installed version for $pc"
    printf '%s=%s\n' "$pc" "$pc_version" >> "$stage/mpv-notices/build/pkg-config.txt"
done
bundle_tool --stage-prefix "$prefix" "$stage"
printf '%s\n' "$complete" > "$stage/mpv-notices/build/COMPLETE"
# Keep the matching archives beside the validated runtime for source collection.
if [[ -e "$runtime" ]]; then mv "$runtime" "$work/previous-runtime"; fi
mv "$stage" "$runtime"
printf '%s\n' "$runtime" >&3
