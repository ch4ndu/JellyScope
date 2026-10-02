#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
# Build tools are provisioned by the caller; this script never installs them.
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(dirname "$script_dir")"
notices="$script_dir/desktop-mpv-bundle"
cache_root="${JELLYSCOPE_MPV_RUNTIME_CACHE_ROOT:-$repo_root/.local/native/desktop-mpv}"
die() { echo "ERROR: $*" >&2; exit 1; }
[[ "$(uname -s)/$(uname -m)" == Darwin/arm64 ]] || die "Desktop mpv requires an Apple Silicon macOS build host"
for tool in python3 curl xcrun make cmake ninja pkg-config autoconf automake autoreconf codesign otool lipo patch; do
    command -v "$tool" >/dev/null || die "Required build tool missing: $tool"
done
libtoolize="$(command -v glibtoolize || command -v libtoolize || true)"
[[ -n "$libtoolize" ]] || die "GNU libtool (glibtoolize) must be provisioned by the build owner"
"$libtoolize" --version | grep -q 'GNU libtool' || die "GNU libtool is required"
python3 - "$cache_root" "$repo_root" <<'PY'
import os, pathlib, sys
if sys.version_info < (3, 10): sys.exit('Python 3.10 or later is required')
p, repo = map(pathlib.Path, sys.argv[1:])
if not p.is_absolute() or p != p.resolve() or len(p.parts) < 4 or any(c.isspace() for c in str(p)): sys.exit('Unsafe native cache root')
if any(x.is_symlink() for x in (p, *p.parents)): sys.exit('Symlink in native cache root')
if p in (repo, *repo.parents, pathlib.Path.home()) or (repo in p.parents and repo/'.local' not in p.parents):
    sys.exit('Native cache must be dedicated, outside source paths (or under .local)')
if any(x.is_symlink() for x in p.rglob('*')): sys.exit('Symlink in native cache')
marker = p/'.jellyscope-desktop-mpv-cache'
if p.exists() and any(p.iterdir()) and not (marker.is_file() and marker.read_text() == 'desktop-mpv-v1\n'):
    sys.exit('Refusing a nonempty cache not owned by desktop mpv')
p.mkdir(parents=True, exist_ok=True)
marker.write_text('desktop-mpv-v1\n')
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
} > "$work/identity/toolchain.txt" 2>&1
runtime="$cache_root/runtime"
if [[ -f "$runtime/mpv-notices/build/COMPLETE" ]] &&
    diff -qr "$work/identity" "$runtime/mpv-notices/build/identity" >/dev/null &&
    bash "$script_dir/prepare-desktop-mpv-bundle.sh" --validate-only "$runtime"; then
    echo "Reusing validated desktop mpv source build" >&2
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
    python3 - "$cached" "$unpack" "$work/src/$destination" "$notices/licenses/$component" "$licenses" <<'PY'
import pathlib, posixpath, sys, tarfile
archive, root, dest, notices, licenses = sys.argv[1:]
with tarfile.open(archive) as t:
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
    for name in licenses.split(','):
        if t.extractfile(root+'/'+name).read() != (pathlib.Path(notices)/name).read_bytes():
            sys.exit('Source license differs from reviewed notice: '+name)
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
done < "$notices/sources-macos-arm64.tsv"
patch_file="$cache_root/archives/mpv-coreaudio-c5d391adba7bd024954d0df1e0405f5749f4d4ca.patch"
grep -q '^From c5d391adba7bd024954d0df1e0405f5749f4d4ca ' "$patch_file" || die "Unexpected CoreAudio patch identity"
patch -d "$work/src/mpv" -p1 < "$patch_file"
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
meson_build libplacebo -Dopengl=enabled -Dgl-proc-addr=enabled -Dlcms=enabled -Ddovi=enabled \
    -Dvulkan=disabled -Dd3d11=disabled -Dglslang=disabled -Dshaderc=disabled -Dlibdovi=disabled -Ddemos=false
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
    -Dmacos-11-3-features=enabled -Dmacos-12-features=enabled
stage="$work/runtime"
mkdir -p "$stage/mpv-notices/build"
cp -R "$notices/." "$stage/mpv-notices/"
cp -R "$work/identity" "$stage/mpv-notices/build/"
cp "$record" "$stage/mpv-notices/build/options.txt"
# Retain actual configurations/feature lists and installed package versions.
cp "$work/build/ffmpeg/config.h" "$work/build/ffmpeg/config_components.h" "$work/build/ffmpeg/ffbuild/config.mak" "$stage/mpv-notices/build/"
cp "$work/build/mpv/config.h" "$stage/mpv-notices/build/mpv-config.h"
cp "$work/build/libplacebo/src/include/libplacebo/config.h" "$stage/mpv-notices/build/libplacebo-config.h"
for component in mpv libplacebo dav1d; do
    cp "$work/build/$component/meson-info/intro-buildoptions.json" "$stage/mpv-notices/build/$component-options.json"
    cp "$work/build/$component/meson-logs/meson-log.txt" "$stage/mpv-notices/build/$component-configure.txt"
done
for pc in libavcodec libavformat libavutil libavfilter libswscale libswresample dav1d libass freetype2 harfbuzz fribidi libunibreak lcms2 zimg zlib libarchive uchardet libpng libbrotlidec libplacebo mpv; do
    pc_version="$(pkg-config --modversion "$pc")"
    [[ -n "$pc_version" ]] || die "Missing installed version for $pc"
    printf '%s=%s\n' "$pc" "$pc_version" >> "$stage/mpv-notices/build/pkg-config.txt"
done
bash "$script_dir/prepare-desktop-mpv-bundle.sh" --stage-prefix "$prefix" "$stage"
printf 'source-build-validated\n' > "$stage/mpv-notices/build/COMPLETE"
# Keep the matching archives beside the validated runtime for source collection.
if [[ -e "$runtime" ]]; then mv "$runtime" "$work/previous-runtime"; fi
mv "$stage" "$runtime"
printf '%s\n' "$runtime" >&3
