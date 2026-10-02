#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
mode="${1:?Usage: prepare-desktop-vlc-bundle.sh <destination> | --validate-only <bundle>}"
[[ "$(uname -s)" == Darwin ]] || { echo 'ERROR: desktop VLC bundling is macOS-only' >&2; exit 1; }
if [[ "$mode" == --validate-only ]]; then
    destination="${2:?Missing bundle}"; vlc_app=""
else
    destination="$mode"; mode=stage
    vlc_app="${JELLYSCOPE_VLC_APP:-}"
    [[ -n "$vlc_app" ]] || vlc_app="$(bash "$script_dir/fetch-desktop-vlc-runtime.sh")"
    [[ "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$vlc_app/Contents/Info.plist")" == 3.0.24 ]] \
        || { echo 'ERROR: VLC packaging requires exactly 3.0.24' >&2; exit 1; }
fi
python3 - "$script_dir" "$mode" "$vlc_app" "$destination" <<'PY'
import os, pathlib, re, shutil, subprocess, sys
scripts, mode, app, dest = sys.argv[1:]
scripts, dest = pathlib.Path(scripts), pathlib.Path(dest)
notice = scripts/'vlc-bundle'
manifest = notice/'manifest-3.0.24-arm64.txt'
def need(ok, message):
    if not ok: sys.exit('ERROR: '+message)
def run(*args): return subprocess.check_output([str(x) for x in args], text=True).strip()
repo = scripts.parent
need(dest.is_absolute() and dest == dest.resolve() and len(dest.parts) >= 4, 'Unsafe VLC bundle destination')
need(not any(x.is_symlink() for x in (dest, *dest.parents)), 'VLC destination traverses a symlink')
need(dest not in (repo, *repo.parents, pathlib.Path.home()), 'Refusing source/root directory')
if repo in dest.parents:
    rel = dest.relative_to(repo).parts
    need((rel[0] == '.local' and len(rel) >= 3) or ('build' in rel and rel[-1] != 'build'), 'Destination must be generated build output')
entries = [x[6:] for x in manifest.read_text().splitlines() if x.startswith('file: ')]
need(len(entries) == len(set(entries)) == 334, 'VLC must have the reviewed 334-file inventory')
need(all(re.fullmatch(r'(?:lib|plugins)/lib[A-Za-z0-9._+-]+\.dylib', x) for x in entries), 'Unsafe VLC manifest path')
expected = {dest/x for x in entries}
if mode == 'stage':
    need(not dest.exists() or not any(dest.iterdir()) or (dest/'mpv-notices').is_dir() or (dest/'licenses'/manifest.name).is_file() or (dest/'license-metadata/.jellyscope-license-metadata').is_file(), 'Refusing an unowned nonempty VLC destination')
    runtime = pathlib.Path(app).resolve()/'Contents/MacOS'
    for name in ['lib','plugins','licenses']:
        p = dest/name
        need(not p.is_symlink(), 'Symlink in VLC output')
        if p.exists(): shutil.rmtree(p)
        p.mkdir(parents=True)
    for relative in entries:
        source = (runtime/relative).resolve(strict=True)
        need(runtime in source.parents and source.is_file(), 'VLC source escapes selected runtime')
        shutil.copy2(source, dest/relative)
        (dest/relative).chmod((dest/relative).stat().st_mode | 0o200)
    shutil.copytree(notice, dest/'licenses', dirs_exist_ok=True)
need(expected == {p for name in ['lib','plugins'] for p in (dest/name).rglob('*')}, 'VLC inventory contains missing or unexpected files')
def commands(p):
    text = run('otool','-l',p)
    need(run('lipo','-archs',p) == 'arm64', 'VLC architecture mismatch: '+p.name)
    need(not re.search(r'/opt/homebrew|/usr/local|/opt/local',text), 'VLC package-manager load command')
    platforms = re.findall(r'cmd LC_BUILD_VERSION\b(?:(?!Load command).)*?\bplatform ([^\s]+)',text,re.S)
    need(all(x in ('1','MACOS','macos') for x in platforms), 'VLC target is not macOS')
    mins = re.findall(r'cmd LC_BUILD_VERSION\b(?:(?!Load command).)*?\bminos ([0-9.]+)',text,re.S)
    mins += re.findall(r'cmd LC_VERSION_MIN_MACOSX\b(?:(?!Load command).)*?\bversion ([0-9.]+)',text,re.S)
    need(bool(mins) and all(tuple(map(int,(v+'.0.0').split('.')[:3])) <= (15,0,0) for v in mins), 'VLC minimum OS mismatch: '+p.name)
    deps = re.findall(r'cmd LC_(?:LOAD|LOAD_WEAK|REEXPORT|LOAD_UPWARD|LAZY_LOAD)_DYLIB\b(?:(?!Load command).)*?\bname (.*?) \(offset',text,re.S)
    ids = re.findall(r'cmd LC_ID_DYLIB\b(?:(?!Load command).)*?\bname (.*?) \(offset',text,re.S)
    rpaths = re.findall(r'cmd LC_RPATH\b(?:(?!Load command).)*?\bpath (.*?) \(offset',text,re.S)
    need(len(ids) == 1, 'VLC library has no unique ID: '+p.name)
    return ids[0],deps,rpaths
for p in sorted(expected):
    need(p.is_file() and not p.is_symlink(), 'VLC requires regular native files')
    identifier,deps,rpaths = commands(p)
    for dependency in deps:
        if dependency.startswith(('/usr/lib/','/System/Library/')) and '..' not in pathlib.PurePosixPath(dependency).parts: continue
        candidates = []
        if dependency.startswith('@loader_path/'): candidates = [p.parent/dependency[13:]]
        elif mode == 'stage' and dependency.startswith('@rpath/'):
            # VLC.app's selected lib directory supplies its core runtime rpath.
            bases = [dest/'lib']
            for rpath in rpaths:
                need(rpath == '@loader_path' or rpath.startswith('@loader_path/'), 'Unsafe VLC RPATH: '+rpath)
                bases.append(p.parent if rpath == '@loader_path' else p.parent/rpath[13:])
            candidates = [b/dependency[7:] for b in bases]
        else: need(False, 'Unexpected VLC dependency: '+dependency)
        resolved = {c.resolve() for c in candidates if c.is_file()}
        need(len(resolved) == 1 and resolved <= expected, 'Missing/ambiguous/escaping VLC target: '+dependency)
        target = next(iter(resolved))
        if mode == 'stage': run('install_name_tool','-change',dependency,'@loader_path/'+os.path.relpath(target,p.parent),p)
    if mode == 'stage':
        run('install_name_tool','-id','@loader_path/'+p.name,p)
        for rpath in rpaths: run('install_name_tool','-delete_rpath',rpath,p)
        run('codesign','--force','--sign','-',p)
    else: need(identifier == '@loader_path/'+p.name and not rpaths, 'Invalid VLC install ID/RPATH')
    run('codesign','--verify','--strict',p)
for name in ['libvlccore.dylib','libvlccore.9.dylib']:
    core = (dest/'lib'/name).read_bytes().split(b'\0')
    need(b'3.0.24' in core and b'3.0.24-rc1-0-g6de05adcba' in core, 'Actual VLC version/revision differs from corresponding source')
need(not any(p.is_symlink() for p in (dest/'licenses').rglob('*')), 'Symlink in VLC notices')
for p in notice.rglob('*'):
    if p.is_file(): need((dest/'licenses'/p.relative_to(notice)).read_bytes() == p.read_bytes(), 'VLC notice/source inventory mismatch: '+p.name)
print('Validated bundled VLC 3.0.24: all 334 arm64/macOS <=15 libraries at '+str(dest),file=sys.stderr)
PY
if [[ "$mode" == stage ]]; then
    bash "$script_dir/prepare-desktop-vlc-bundle.sh" --validate-only "$destination"
fi
