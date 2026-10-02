#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
[[ "$(uname -s)" == Darwin ]] || { echo 'ERROR: desktop mpv bundling is macOS-only' >&2; exit 1; }
mode="${1:?Usage: prepare-desktop-mpv-bundle.sh <destination> | --validate-only <bundle> | --stage-prefix <prefix> <bundle>}"
case "$mode" in
    --validate-only) destination="${2:?Missing bundle}"; source_dir="" ;;
    --stage-prefix) source_dir="${2:?Missing prefix}"; destination="${3:?Missing bundle}" ;;
    *) destination="$mode"; mode=copy; source_dir="$(bash "$script_dir/build-desktop-mpv-runtime.sh")" ;;
esac
python3 - "$script_dir" "$mode" "$source_dir" "$destination" <<'PY'
import json, os, pathlib, re, shutil, subprocess, sys
scripts, mode, source, dest = sys.argv[1:]
scripts, dest = pathlib.Path(scripts), pathlib.Path(dest)
notice = scripts/'desktop-mpv-bundle'
manifest = notice/'manifest-mpv-0.41.0-macos15-arm64.txt'
def need(ok, message):
    if not ok: sys.exit('ERROR: '+message)
def run(*args):
    return subprocess.check_output([str(x) for x in args], text=True).strip()
def safe(p):
    need(p.is_absolute() and p == p.resolve() and len(p.parts) >= 4, 'Unsafe bundle path')
    need(not any(x.is_symlink() for x in (p, *p.parents)), 'Bundle path traverses a symlink')
    repo = scripts.parent
    need(p not in (repo, *repo.parents, pathlib.Path.home()), 'Refusing source/root directory')
    if repo in p.parents:
        rel = p.relative_to(repo).parts
        need((rel[0] == '.local' and len(rel) >= 3) or ('build' in rel and rel[-1] != 'build'), 'Destination must be generated build output')
safe(dest)
if mode != '--validate-only': dest.mkdir(parents=True, exist_ok=True)
need(dest.is_dir(), 'Missing runtime bundle: '+str(dest))
entries = [x[5:] for x in manifest.read_text().splitlines() if x.startswith('file=')]
need(entries and len(entries) == len(set(entries)), 'Empty or duplicate ABI manifest')
need(all(re.fullmatch(r'lib[A-Za-z0-9._+-]+\.dylib', x) for x in entries), 'Unsafe ABI filename')
expected = set(entries)
if mode == 'copy':
    source = pathlib.Path(source)
    target = dest/'mpv-notices'
    need(not target.is_symlink(), 'Symlink notice directory')
    need(not any(dest.iterdir()) or (dest/'mpv-notices'/manifest.name).is_file() or (dest/'license-metadata/.jellyscope-license-metadata').is_file(), 'Refusing an unowned nonempty destination')
    for p in dest.glob('*.dylib'):
        need(p.is_file() and not p.is_symlink(), 'Unexpected native output type')
        p.unlink()
    for name in entries: shutil.copy2(source/name, dest/name)
    if target.exists(): shutil.rmtree(target)
    shutil.copytree(source/'mpv-notices', target)

def load_commands(p):
    text = run('otool', '-l', p)
    need(not re.search(r'/opt/homebrew|/usr/local|/opt/local', text), p.name+' has a package-manager load command')
    platforms = re.findall(r'cmd LC_BUILD_VERSION\b(?:(?!Load command).)*?\bplatform ([^\s]+)', text, re.S)
    need(all(p in ('1','MACOS','macos') for p in platforms), p.name+' is not a macOS binary')
    mins = re.findall(r'cmd LC_BUILD_VERSION\b(?:(?!Load command).)*?\bminos ([0-9.]+)', text, re.S)
    mins += re.findall(r'cmd LC_VERSION_MIN_MACOSX\b(?:(?!Load command).)*?\bversion ([0-9.]+)', text, re.S)
    need(bool(mins) and all(tuple(map(int, (v+'.0.0').split('.')[:3])) <= (15,0,0) for v in mins), p.name+' has an unsupported/missing minimum OS')
    need(run('lipo', '-archs', p) == 'arm64', p.name+' is not arm64-only')
    deps = re.findall(r'cmd LC_(?:LOAD|LOAD_WEAK|REEXPORT|LOAD_UPWARD|LAZY_LOAD)_DYLIB\b(?:(?!Load command).)*?\bname (.*?) \(offset', text, re.S)
    ids = re.findall(r'cmd LC_ID_DYLIB\b(?:(?!Load command).)*?\bname (.*?) \(offset', text, re.S)
    rpaths = re.findall(r'cmd LC_RPATH\b(?:(?!Load command).)*?\bpath (.*?) \(offset', text, re.S)
    need(len(ids) == 1, p.name+' lacks a unique install ID')
    return ids[0], deps, rpaths

def system(ref): return ref.startswith(('/usr/lib/', '/System/Library/')) and '..' not in pathlib.PurePosixPath(ref).parts
if mode == '--stage-prefix':
    prefix = pathlib.Path(source).resolve()
    toolchain = (dest/'mpv-notices/build/identity/toolchain.txt').read_text()
    fields = re.findall(r'^(sdk|swiftc|swift-library-directory)=(.+)$', toolchain, re.M)
    need(len(fields) == 3 and len(dict(fields)) == 3, 'Missing/ambiguous SDK or Swift compiler identity')
    identity = dict(fields)
    sdk, swiftc, swift_lib_dir = (pathlib.Path(identity[key]) for key in ('sdk', 'swiftc', 'swift-library-directory'))
    need(sdk.is_absolute() and sdk.is_dir() and swiftc.is_absolute() and
         swift_lib_dir == swiftc.parent.parent/'lib/swift/macosx', 'Invalid SDK or Swift library directory identity')
    sources = {name: (prefix/'lib'/name).resolve(strict=True) for name in entries}
    need(all(p.is_file() and prefix in p.parents for p in sources.values()), 'ABI source escapes prefix')
    need(len(set(sources.values())) == len(entries), 'ABI manifest aliases the same library twice')
    by_source = {p:name for name,p in sources.items()}
    for name, p in sources.items(): shutil.copy2(p, dest/name)
    for name, original in sources.items():
        library = dest/name
        library.chmod(library.stat().st_mode | 0o200)
        identifier, deps, rpaths = load_commands(library)
        # Only mpv adds these two Swift search paths; neither supplies bundled libraries.
        bases = [prefix/'lib']
        for rpath in rpaths:
            if rpath == '@loader_path': bases.append(original.parent)
            elif rpath.startswith('@loader_path/'): bases.append(original.parent/rpath[13:])
            elif rpath.startswith(str(prefix)+'/'): bases.append(pathlib.Path(rpath))
            elif name == 'libmpv.2.dylib' and rpath in (str(swift_lib_dir), '/usr/lib/swift'): continue
            else: need(False, name+' has an unexpected build RPATH: '+rpath)
        for dependency in deps:
            if system(dependency): continue
            candidates = []
            if dependency.startswith('@loader_path/'):
                candidates = [original.parent/dependency[len('@loader_path/'):]]
            elif dependency.startswith('@rpath/'):
                # The isolated prefix is the installed graph's declared search root.
                candidates = [b/dependency[7:] for b in bases]
            elif dependency.startswith(str(prefix)+'/'): candidates = [pathlib.Path(dependency)]
            else: need(False, name+' references an external dependency: '+dependency)
            resolved = {p.resolve() for p in candidates if p.is_file()}
            if not resolved and dependency.startswith('@rpath/libswift'):
                problem = name+' has an unresolved Swift system dependency: '+dependency
                need(name == 'libmpv.2.dylib' and '/usr/lib/swift' in rpaths and
                     re.fullmatch(r'@rpath/libswift[A-Za-z0-9_]+\.dylib', dependency), problem)
                system_ref = '/usr/lib/swift/'+dependency[7:]
                stub = (sdk/system_ref.lstrip('/')).with_suffix('.tbd')
                need(stub.is_file() and sdk.resolve() in stub.resolve().parents, problem+' (missing SDK stub: '+str(stub)+')')
                # Require this SDK's primary arm64/macOS stub to name the exact system library.
                header = stub.read_text().split('\n---', 1)[0]
                targets = re.search(r'^targets:\s*\[([^\]]+)\]', header, re.M)
                install_names = re.findall(r'''^install-name:\s*['"]?([^'"\s]+)['"]?[ \t]*$''', header, re.M)
                need(targets and 'arm64-macos' in [t.strip() for t in targets[1].split(',')] and
                     install_names == [system_ref], problem+' (SDK stub lacks the matching arm64 system install name: '+str(stub)+')')
                run('install_name_tool', '-change', dependency, system_ref, library)
                print(name+': resolved '+dependency+' to '+system_ref+' using '+str(stub), file=sys.stderr)
                continue
            need(len(resolved) == 1 and next(iter(resolved)) in by_source, name+' has a missing/ambiguous/undeclared dependency: '+dependency)
            target = by_source[next(iter(resolved))]
            run('install_name_tool', '-change', dependency, '@loader_path/'+target, library)
        run('install_name_tool', '-id', '@loader_path/'+name, library)
        for rpath in rpaths: run('install_name_tool', '-delete_rpath', rpath, library)
        run('codesign', '--force', '--sign', '-', library)

need({p.name for p in dest.glob('*.dylib')} == expected, 'Runtime inventory differs from the ABI manifest')
for name in entries:
    p = dest/name
    need(p.is_file() and not p.is_symlink(), 'Native library is not a regular file: '+name)
    identifier, deps, rpaths = load_commands(p)
    need(identifier == '@loader_path/'+name and not rpaths, name+' retains an invalid ID or RPATH')
    for dependency in deps:
        if system(dependency): continue
        need(dependency.startswith('@loader_path/') and dependency[13:] in expected, name+' has an unclosed dependency: '+dependency)
    run('codesign', '--verify', '--strict', p)
symbols = set(re.findall(r'\b_mpv_[A-Za-z0-9_]+', run('xcrun','nm','-gU',dest/'libmpv.2.dylib')))
for symbol in ['create','initialize','terminate_destroy','error_string','set_option_string','set_property_string','get_property','get_property_string','free','command','wait_event','render_context_create','render_context_render','render_context_set_update_callback','render_context_update','render_context_free']:
    need('_mpv_'+symbol in symbols, 'Missing JNA client/render entrypoint: mpv_'+symbol)
# Inspect actual native string constants without loading any installed runtime.
versions = [('mpv', 'libmpv.2.dylib', rb'(?:mpv )?v?0\.41\.0'),
            ('ffmpeg', 'libavutil.61.dylib', rb'9\.0\.2'), ('dav1d', 'libdav1d.7.dylib', rb'1\.5\.4')]
evidence = []
for component, library, pattern in versions:
    strings = (dest/library).read_bytes().split(b'\0')
    matches = sorted({s.decode('ascii') for s in strings if re.fullmatch(pattern, s)})
    need(bool(matches), 'Missing actual '+component+' version string')
    evidence.append(component+'='+','.join(matches))
record = '\n'.join(evidence)+'\n'
build = dest/'mpv-notices/build'
if mode == '--stage-prefix': (build/'versions.txt').write_text(record)
else:
    need((build/'COMPLETE').read_text() == 'source-build-validated\n', 'Incomplete native build')
    need((build/'versions.txt').read_text() == record, 'Native version evidence mismatch')
for p in notice.rglob('*'):
    if p.is_file(): need((dest/'mpv-notices'/p.relative_to(notice)).read_bytes() == p.read_bytes(), 'Packaged mpv notice/source inventory differs: '+p.name)
for name in ['build-desktop-mpv-runtime.sh', 'prepare-desktop-mpv-bundle.sh']:
    need((build/'identity'/name).read_bytes() == (scripts/name).read_bytes(), 'Native recipe differs from the candidate')
for name in ['sources-macos-arm64.tsv','manifest-mpv-0.41.0-macos15-arm64.txt']:
    need((build/'identity'/name).read_bytes() == (notice/name).read_bytes(), 'Native identity differs from the candidate')
need(not any(p.is_symlink() for p in (dest/'mpv-notices').rglob('*')), 'Symlink in mpv provenance')
placebo_config = (build/'libplacebo-config.h').read_text()
for feature in ['OPENGL','LCMS','DOVI']:
    need(re.search(r'^#define PL_HAVE_'+feature+r' 1$',placebo_config,re.M), 'Missing libplacebo feature: '+feature)
mpv_config = (build/'mpv-config.h').read_text()
for feature in ['GL','COCOA','SWIFT','GL_COCOA','VIDEOTOOLBOX_GL','COREAUDIO','LIBASS','LCMS2','ZIMG','ZLIB','LIBARCHIVE','UCHARDET','ICONV','MACOS_10_15_4_FEATURES','MACOS_11_FEATURES','MACOS_11_3_FEATURES','MACOS_12_FEATURES']:
    need(re.search(r'^#define HAVE_'+feature+r' 1$', mpv_config, re.M), 'Missing mpv feature: '+feature)
for feature in ['MACOS_COCOA_CB','MACOS_MEDIA_PLAYER','MACOS_TOUCHBAR']:
    need(re.search(r'^#define HAVE_'+feature+r' 0$', mpv_config, re.M), 'Unexpected mpv standalone feature: '+feature)
mpv_options = {option['name']: option['value'] for option in json.loads((build/'mpv-options.json').read_text())}
for option, value in {'cplayer': False, 'plain-gl': 'enabled', 'swift-build': 'enabled', 'swift-flags': '-target arm64-apple-macosx15.0',
                      'macos-cocoa-cb': 'disabled', 'macos-media-player': 'disabled', 'macos-touchbar': 'disabled'}.items():
    need(mpv_options.get(option) == value, 'Unexpected mpv build option: '+option)
ffmpeg_config = (build/'config.h').read_text()+'\n'+(build/'config_components.h').read_text()
for feature in ['GPL','VERSION3','SECURETRANSPORT','VIDEOTOOLBOX','NETWORK','HTTP_PROTOCOL','HTTPS_PROTOCOL','FILE_PROTOCOL','LIBDAV1D_DECODER','H264_DECODER','HEVC_DECODER','AV1_DECODER','ASS_FILTER','LIBZIMG','ICONV']:
    need(re.search(r'^#define CONFIG_'+feature+r' 1$', ffmpeg_config, re.M), 'Missing FFmpeg feature: '+feature)
need(re.search(r'^#define CONFIG_NONFREE 0$',ffmpeg_config,re.M), 'Nonfree FFmpeg configuration')
for name in ['options.txt','toolchain.txt']:
    p = build/('identity/'+name if name == 'toolchain.txt' else name)
    need(p.is_file() and bool(p.read_text().strip()), 'Missing native build record: '+name)
print('Validated complete mpv arm64/macOS 15 graph and actual versions in '+str(dest), file=sys.stderr)
PY
