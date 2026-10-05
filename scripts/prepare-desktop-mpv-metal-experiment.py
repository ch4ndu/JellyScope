#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Prepare the isolated macOS arm64 Metal experiment, never the shipped runtime.

Inputs are the non-GPL, non-Samba MPVKit 1.0.0 graph at SOURCE_REVISION.
Retained archives/source records are build provenance, not redistribution clearance.
Uses the validated desktop source build for optimized dav1d and mpv ABI config.
This recipe does not initialize mpv or claim that native video output works.
"""

import argparse
import csv
import json
import os
from pathlib import Path, PurePosixPath
import platform
import plistlib
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import zipfile


SOURCE_REVISION = "288527dffbc6d3e63cce147fc7b520c64a791603"
SOURCE_URL = f"https://github.com/mpvkit/MPVKit/archive/{SOURCE_REVISION}.zip"
RECIPE = "jellyscope-mpv-metal-mpvkit-1.0.0-arm64-resize-v2"
DEPLOYMENT_TARGET = "15.0"
INSTALL_NAME = "@loader_path/libmpv.2.dylib"
# Published non-GPL/non-Samba graph, excluding the unoptimized dav1d archive.
# URLs and source revision are pins; there is no checksum allowlist.
COMPONENT_GROUPS = (
    ("openssl-build", "3.3.5", ("Libcrypto", "Libssl")),
    ("gnutls-build", "3.8.11", ("gmp", "nettle", "hogweed", "gnutls")),
    ("libass-build", "0.17.5", ("Libunibreak", "Libfreetype", "Libfribidi", "Libharfbuzz", "Libass")),
    ("libbluray-build", "1.4.0", ("Libbluray",)),
    ("libuavs3d-build", "1.2.1-fix", ("Libuavs3d",)),
    ("libdovi-build", "3.3.2", ("Libdovi",)),
    ("moltenvk-build", "1.4.2", ("MoltenVK",)),
    ("libshaderc-build", "2025.5.0", ("Libshaderc_combined",)),
    ("lcms2-build", "2.17.0", ("lcms2",)),
    ("libplacebo-build", "7.360.1", ("Libplacebo",)),
    ("MPVKit", "1.0.0", ("Libavcodec", "Libavdevice", "Libavformat", "Libavfilter", "Libavutil", "Libswresample", "Libswscale")),
    ("libuchardet-build", "0.0.8", ("Libuchardet",)),
    ("libluajit-build", "2.1.0-fix", ("Libluajit",)),
    ("MPVKit", "1.0.0", ("Libmpv",)),
)
COMPONENTS = [
    {"name": name, "url": f"https://github.com/mpvkit/{repo}/releases/download/{tag}/{name}.xcframework.zip"}
    for repo, tag, names in COMPONENT_GROUPS for name in names
]
FRAMEWORKS = (
    "Foundation", "AppKit", "AVFoundation", "AudioToolbox", "CoreAudio", "CoreFoundation",
    "CoreGraphics", "CoreMedia", "CoreVideo", "IOKit", "Metal", "QuartzCore", "Security",
    "VideoToolbox", "OpenGL",
)
LIBRARIES = ("bz2", "iconv", "expat", "resolv", "xml2", "z", "c++")


def run(*arguments, cwd=None):
    result = subprocess.run(arguments, cwd=cwd, check=True, text=True, capture_output=True)
    return result.stdout.strip()


def local_path(value, local_root):
    path = Path(os.path.abspath(Path(value).expanduser()))
    resolved = path.resolve()
    if resolved == local_root or local_root not in resolved.parents:
        raise ValueError("All experiment paths must be inside this worktree's .local directory")
    if path != resolved:
        raise ValueError("Experiment paths must not traverse symlinks")
    return path


def member_path(value):
    path = PurePosixPath(value)
    if not value or path.is_absolute() or ".." in path.parts or "\\" in value or any(ord(c) < 32 for c in value):
        raise ValueError("Unsafe archive member path")
    return path


def validate_members(archive):
    names = set()
    for member in archive.infolist():
        member_path(member.filename)
        if member.filename in names:
            raise ValueError("Duplicate archive member")
        names.add(member.filename)
        mode = member.external_attr >> 16
        if mode and stat.S_IFMT(mode) not in (0, stat.S_IFREG, stat.S_IFDIR, stat.S_IFLNK):
            raise ValueError("Unsupported archive member type")
        # Framework symlinks are never materialized. Validate their targets too;
        # binary extraction below only accepts a real file at BinaryPath.
        if stat.S_ISLNK(mode):
            target = archive.read(member).decode("utf-8")
            if not target or PurePosixPath(target).is_absolute() or "\\" in target or any(ord(c) < 32 for c in target):
                raise ValueError("Unsafe archive symlink")
            combined = os.path.normpath(str(PurePosixPath(member.filename).parent / target))
            member_path(combined)


def acquire(url, target, candidates, offline):
    source = next((path for path in candidates if path.is_file()), None)
    if source is not None:
        shutil.copyfile(source, target)
        return
    if offline:
        raise ValueError(f"Missing offline input: {target.name}")
    request = urllib.request.Request(url, headers={"User-Agent": "JellyScope-Metal-Experiment"})
    with urllib.request.urlopen(request, timeout=120) as response:
        if not response.url.startswith("https://"):
            raise ValueError("Refusing a non-HTTPS archive redirect")
        with target.open("xb") as output:
            shutil.copyfileobj(response, output)


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n")


def extract_arm64(archive_path, name, work, lipo):
    with zipfile.ZipFile(archive_path) as archive:
        validate_members(archive)
        root = f"{name}.xcframework"
        info = plistlib.loads(archive.read(f"{root}/Info.plist"))
        slices = [entry for entry in info["AvailableLibraries"]
                  if entry.get("SupportedPlatform") == "macos"
                  and not entry.get("SupportedPlatformVariant")
                  and "arm64" in entry.get("SupportedArchitectures", [])]
        if len(slices) != 1:
            raise ValueError(f"Expected one macOS arm64-capable slice: {name}")
        selected = slices[0]
        identifier = member_path(selected["LibraryIdentifier"])
        binary = member_path(selected.get("BinaryPath", selected["LibraryPath"]))
        if binary.suffix == ".framework":
            binary = binary / "Versions" / "A" / binary.stem
        binary_member = archive.getinfo(f"{root}/{identifier}/{binary}")
        if binary_member.is_dir() or stat.S_ISLNK(binary_member.external_attr >> 16):
            raise ValueError(f"Expected a regular static archive: {name}")
        universal = work / "universal" / f"{name}.a"
        with archive.open(binary_member) as source, universal.open("xb") as target:
            shutil.copyfileobj(source, target)
        write_json(work / "provenance" / f"{name}-slice.json", selected)
    architectures = run(lipo, "-archs", str(universal)).split()
    if "arm64" not in architectures:
        raise ValueError(f"Input lacks arm64: {name}")
    thin = work / "arm64" / f"{name}.a"
    if architectures == ["arm64"]:
        shutil.copyfile(universal, thin)
    else:
        run(lipo, str(universal), "-thin", "arm64", "-output", str(thin))
    with thin.open("rb") as data:
        if data.read(8) != b"!<arch>\n":
            raise ValueError(f"Expected a static library after arm64 extraction: {name}")
    return str(thin.relative_to(work))


def validate_candidate(directory, lipo, otool):
    library = directory / "libmpv.2.dylib"
    if library.is_symlink() or not library.is_file():
        raise ValueError("Candidate dylib is missing or is a symlink")
    if run(lipo, "-archs", str(library)).split() != ["arm64"]:
        raise ValueError("Candidate is not arm64-only")
    dependencies = run(otool, "-L", str(library))
    entries = [line.strip().split(" (", 1)[0] for line in dependencies.splitlines()[1:]]
    if not entries or entries[0] != INSTALL_NAME:
        raise ValueError("Candidate install name is unexpected")
    if any(not path.startswith(("/System/Library/", "/usr/lib/"))
           and path != "@loader_path/libdav1d.7.dylib" for path in entries[1:]):
        raise ValueError("Candidate contains a non-system dynamic dependency")
    load_commands = run(otool, "-l", str(library))
    version = re.search(r"cmd LC_BUILD_VERSION\s+cmdsize \d+\s+platform (\S+)\s+minos (\S+)", load_commands)
    if not version or version.group(1) not in ("1", "MACOS", "macos") or version.group(2) != DEPLOYMENT_TARGET:
        raise ValueError("Candidate deployment target is not macOS 15.0")
    if "LC_RPATH" in load_commands:
        raise ValueError("Candidate unexpectedly embeds a runtime search path")
    if entries[1:].count("@loader_path/libdav1d.7.dylib") != 1:
        raise ValueError("Candidate must link the optimized decoder exactly once")
    decoder = directory / "libdav1d.7.dylib"
    if decoder.is_symlink() or run(lipo, "-archs", str(decoder)) != "arm64":
        raise ValueError("Decoder must be a regular arm64 library")
    decoder_entries = [line.strip().split(" (", 1)[0]
                       for line in run(otool, "-L", str(decoder)).splitlines()[1:]]
    if not decoder_entries or decoder_entries[0] != "@loader_path/libdav1d.7.dylib" or any(
            not entry.startswith(("/System/Library/", "/usr/lib/")) for entry in decoder_entries[1:]):
        raise ValueError("Unexpected decoder install name or dependencies")
    run("/usr/bin/codesign", "--verify", "--strict", str(decoder))
    run("/usr/bin/codesign", "--verify", "--strict", str(library))
    return {"architectures": ["arm64"], "deployment_target": DEPLOYMENT_TARGET,
            "dependencies": entries, "signature_verified": True, "native_video_exercised": False}


def prepare_context(repo, work, baseline, caches, local_root, offline):
    """Rebuild one object using pinned headers and the existing macOS pthread ABI."""
    notices = repo / "scripts/desktop-mpv-bundle"
    with (notices / "sources-macos-arm64.tsv").open() as source:
        pins = {row["component"]: row for row in csv.DictReader(source, delimiter="\t")}
    if pins["mpv"]["version"] != "0.41.0" or pins["dav1d"]["version"] != "1.5.4":
        raise ValueError("Desktop source versions changed; review the Metal ABI and decoder first")
    for name in ("mpv", "vulkan-headers"):
        pin = pins[name]
        target = work / "inputs" / pin["archive"]
        cached = [local_path(str(cache / pin["archive"]), local_root) for cache in caches]
        acquire(pin["url"], target, cached, offline)
        destination = work / "sources" / name
        destination.mkdir(parents=True)
        # Only regular files/directories are materialized; no archive links.
        with tarfile.open(target) as archive:
            for member in archive.getmembers():
                path = member_path(member.name)
                if path.parts[0] != pin["unpack-root"]:
                    raise ValueError("Unexpected source archive root")
                output = destination.joinpath(*path.parts[1:])
                if member.isdir():
                    output.mkdir(parents=True, exist_ok=True)
                elif member.isfile():
                    output.parent.mkdir(parents=True, exist_ok=True)
                    with archive.extractfile(member) as source, output.open("xb") as sink:
                        shutil.copyfileobj(source, sink)
        for license_name in pin["license-members"].split(","):
            if (destination / license_name).read_bytes() != (notices / "licenses" / name / license_name).read_bytes():
                raise ValueError("Source license differs from reviewed notice")
    mpv = work / "sources/mpv"
    upstream_patch = work / "provenance/mpvkit-moltenvk.patch"
    with zipfile.ZipFile(work / "inputs/MPVKit-source.zip") as archive:
        upstream_patch.write_bytes(archive.read(
            f"MPVKit-{SOURCE_REVISION}/Sources/BuildScripts/patch/libmpv/0001-player-add-moltenvk-context.patch"))
    patch = work / "provenance/metal-context-resize.patch"
    shutil.copyfile(repo / "scripts/desktop-mpv-metal" / patch.name, patch)
    existing_backups = set(mpv.rglob("*.orig"))
    for item in (upstream_patch, patch):
        run("/usr/bin/patch", "--batch", "-p1", "-i", str(item), cwd=mpv)
    # Offset hunks leave backups; the retained tree should hold only patched sources.
    for backup in set(mpv.rglob("*.orig")) - existing_backups:
        backup.unlink()
    include = work / "include"
    include.mkdir()
    config = (baseline / "mpv-notices/build/mpv-config.h").read_text()
    if ("#define HAVE_WIN32_THREADS 0\n" not in config or config.count("#define HAVE_VULKAN 0\n") != 1
            or "HAVE_MOLTENVK" in config):
        raise ValueError("Context requires mpv's macOS pthread ABI with Vulkan disabled in the baseline")
    config = config.replace("#define HAVE_VULKAN 0\n", "#define HAVE_VULKAN 1\n")
    (include / "config.h").write_text(config + "\n#define HAVE_MOLTENVK 0\n")
    # Headers come from the same slice that extract_arm64 selected for linking.
    placebo = json.loads((work / "provenance/Libplacebo-slice.json").read_text())
    with zipfile.ZipFile(work / "inputs/Libplacebo.xcframework.zip") as archive:
        prefix = (f"Libplacebo.xcframework/{member_path(placebo['LibraryIdentifier'])}/"
                  f"{member_path(placebo['LibraryPath'])}/Headers/")
        for member in archive.infolist():
            if member.filename.startswith(prefix) and not member.is_dir():
                if stat.S_ISLNK(member.external_attr >> 16):
                    raise ValueError("Unexpected header symlink")
                header_root = include / "libplacebo"
                target = header_root / member_path(member.filename[len(prefix):])
                if header_root not in target.parents:
                    raise ValueError("Header member escapes the header directory")
                target.parent.mkdir(parents=True, exist_ok=True)
                with target.open("xb") as output:
                    output.write(archive.read(member))
    obj = work / "video_out_vulkan_context_moltenvk.m.o"
    command = ["xcrun", "clang", "-arch", "arm64", "-mmacosx-version-min=15.0", "-O2", "-fPIC",
               "-std=c11", "-I" + str(include), "-I" + str(mpv),
               "-I" + str(work / "sources/vulkan-headers/include"),
               "-c", str(mpv / "video/out/vulkan/context_moltenvk.m"), "-o", str(obj)]
    write_json(work / "provenance/context-compile.json", command)
    compiled = subprocess.run(command, text=True, capture_output=True)
    (work / "provenance/context-compile.log").write_text(compiled.stdout + compiled.stderr)
    if compiled.returncode != 0:
        raise ValueError(f"Context compile failed ({compiled.returncode}):\n{compiled.stderr}")
    library = work / "arm64/Libmpv.a"
    if run("/usr/bin/ar", "-t", str(library)).splitlines().count(obj.name) != 1:
        raise ValueError("Pinned mpv context object is missing or ambiguous")
    run("/usr/bin/ar", "-r", str(library), str(obj))
    run("/usr/bin/ranlib", str(library))
    shutil.copyfile(baseline / "libdav1d.7.dylib", work / "libdav1d.7.dylib")
    shutil.copytree(baseline / "mpv-notices", work / "provenance/desktop-source-build")
    shutil.copyfile(repo / "scripts/desktop-mpv-metal/METAL-EXPERIMENT.md", work / "provenance/METAL-EXPERIMENT.md")
    shutil.copyfile(Path(__file__), work / "provenance/prepare-desktop-mpv-metal-experiment.py")
    write_json(work / "provenance/context-source-pins.json", {name: pins[name] for name in ("mpv", "vulkan-headers", "dav1d")})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--destination", required=True, help="New/recipe-owned candidate directory under this worktree's .local")
    parser.add_argument("--archives-dir", action="append", default=[], help="Task-local cached release archives; may be repeated")
    parser.add_argument("--source-archive", help="Task-local cached MPVKit source zip at the pinned revision")
    parser.add_argument("--offline", action="store_true", help="Fail if any input is absent; never download")
    parser.add_argument("--desktop-runtime",
                        help="Validated source-built desktop runtime under .local; provides dav1d and mpv ABI config "
                             "(default: this checkout's .local/native/desktop-mpv/runtime)")
    args = parser.parse_args()
    if sys.platform != "darwin" or platform.machine() != "arm64":
        raise ValueError("This experiment recipe requires macOS arm64")
    repo = Path(__file__).resolve().parent.parent
    local_root = repo / ".local"
    if local_root.is_symlink():
        raise ValueError("The worktree .local directory must not be a symlink")
    destination = local_path(args.destination, local_root)
    caches = [local_path(value, local_root) for value in args.archives_dir]
    source_archive = local_path(args.source_archive, local_root) if args.source_archive else None
    baseline = local_path(args.desktop_runtime or str(local_root / "native/desktop-mpv/runtime"), local_root)
    run("bash", str(repo / "scripts/prepare-desktop-mpv-bundle.sh"), "--validate-only", str(baseline))
    options = json.loads((baseline / "mpv-notices/build/dav1d-options.json").read_text())
    if not any(option["name"] == "enable_asm" and option["value"] is True for option in options):
        raise ValueError("Desktop dav1d must have assembly enabled")
    if destination == baseline or destination in baseline.parents or baseline in destination.parents:
        raise ValueError("Candidate must be separate from the desktop runtime")
    tools = {name: run("xcrun", "--find", name) for name in ("clang++", "swiftc", "lipo", "otool")}
    sdk = run("xcrun", "--sdk", "macosx", "--show-sdk-path")
    swift_compat = Path(tools["swiftc"]).resolve().parent.parent / "lib/swift/macosx"
    if not swift_compat.is_dir():
        raise ValueError("Selected Xcode toolchain has no Swift macOS compatibility libraries")
    if destination.exists() and any(destination.iterdir()):
        raise ValueError("Choose a new or empty destination; existing candidates are never overwritten")
    destination.parent.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix=".mpv-metal-", dir=destination.parent))
    try:
        for name in ("inputs", "provenance", "universal", "arm64"):
            (work / name).mkdir()
        source = work / "inputs/MPVKit-source.zip"
        acquire(SOURCE_URL, source, [source_archive] if source_archive else [], args.offline)
        with zipfile.ZipFile(source) as archive:
            validate_members(archive)
            prefix = f"MPVKit-{SOURCE_REVISION}/"
            package = archive.read(prefix + "Package.swift").decode("utf-8")
            # Verify each pin against the retained upstream declaration without
            # importing its checksum allowlist or executing its build scripts.
            declared = dict(re.findall(r'\.binaryTarget\(\s*name:\s*"([^"]+)",\s*url:\s*"([^"]+)"', package))
            if any(declared.get(item["name"]) != item["url"] for item in COMPONENTS):
                raise ValueError("Pinned source does not declare the selected dependency graph")
            (work / "provenance/Package.swift").write_text(package)
            (work / "provenance/MPVKit-LICENSE").write_bytes(archive.read(prefix + "LICENSE"))
        archives = {}
        for component in COMPONENTS:
            name = component["name"]
            filename = f"{name}.xcframework.zip"
            cached = [local_path(str(cache / filename), local_root) for cache in caches]
            target = work / "inputs" / filename
            acquire(component["url"], target, cached, args.offline)
            archives[name] = extract_arm64(target, name, work, tools["lipo"])
        prepare_context(repo, work, baseline, caches, local_root, args.offline)
        # The optimized decoder takes the omitted Libdav1d archive's link position.
        archives["Libdav1d"] = "libdav1d.7.dylib"
        command = [tools["clang++"], "-dynamiclib", "-arch", "arm64", "-isysroot", sdk,
                   f"-mmacosx-version-min={DEPLOYMENT_TARGET}", f"-Wl,-install_name,{INSTALL_NAME}",
                   "-Xlinker", "-force_load", "-Xlinker", archives["Libmpv"], "-o", "libmpv.2.dylib"]
        command += [archives[name] for name in sorted(archives) if name != "Libmpv"]
        for framework in FRAMEWORKS:
            command += ["-framework", framework]
        command += [f"-l{name}" for name in LIBRARIES] + [f"-L{swift_compat}", "-L/usr/lib/swift"]
        write_json(work / "provenance/link-command.json", command)
        write_json(work / "provenance/toolchain.json", {"tools": tools, "sdk": sdk, "swift_compatibility": str(swift_compat)})
        link = subprocess.run(command, cwd=work, text=True, capture_output=True)
        (work / "provenance/link.log").write_text(link.stdout + link.stderr)
        if link.returncode != 0:
            raise ValueError(f"Native link failed ({link.returncode}):\n{link.stderr}")
        if "built for newer" in link.stderr:
            raise ValueError("A native input requires a newer deployment target; candidate was not published")
        run("/usr/bin/codesign", "--force", "--sign", "-", str(work / "libmpv.2.dylib"))
        validation = validate_candidate(work, tools["lipo"], tools["otool"])
        write_json(work / "candidate.json", {
            "recipe": RECIPE, "source_revision": SOURCE_REVISION, "source_url": SOURCE_URL,
            "components": COMPONENTS, "validation": validation,
            "distribution_status": "Development only; source and notice obligations require separate review before redistribution",
        })
        # Only the requested empty path can be replaced. Every build input and
        # provenance record moves with the candidate, so no global cache is used.
        if destination.exists() and (destination.is_symlink() or any(destination.iterdir())):
            raise ValueError("Destination changed during preparation; refusing to overwrite it")
        os.replace(work, destination)
        print(f"Prepared experiment candidate: {destination}")
        print("Candidate prepared. Repeat native/app checks before accepting a changed runtime.")
    finally:
        if work.exists():
            shutil.rmtree(work)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        print(f"Metal experiment preparation failed: {error}", file=sys.stderr)
        sys.exit(1)
