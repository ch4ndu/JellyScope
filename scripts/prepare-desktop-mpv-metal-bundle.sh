#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
# Stages the source-built Metal mpv runtime (build-desktop-mpv-runtime.sh --metal)
# into <resources>/mpv-metal, beside the untouched Standard graph. Graph, version,
# configuration and notice validation belongs to prepare-desktop-mpv-bundle.sh --metal.
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
usage='Usage: prepare-desktop-mpv-metal-bundle.sh <runtime> <resources> | --validate-only <resources> | --clean <resources>'
mode="${1:?$usage}"
case "$mode" in
    --validate-only | --clean) resources="${2:?$usage}"; candidate="" ;;
    -*) echo "ERROR: $usage" >&2; exit 2 ;;
    *) candidate="$mode"; mode=stage; resources="${2:?$usage}" ;;
esac
# Cleanup is portable so a Standard-only build never needs native tools.
[[ "$mode" == --clean || "$(uname -s)" == Darwin ]] || { echo 'ERROR: Metal mpv staging is macOS-only' >&2; exit 1; }
python3 - "$script_dir" "$mode" "$candidate" "$resources" <<'PY'
import os, pathlib, shutil, subprocess, sys, tempfile
scripts, mode, candidate, resources = sys.argv[1:]
scripts, resources = pathlib.Path(scripts), pathlib.Path(resources)
manifest = scripts/'desktop-mpv-metal/manifest-mpv-metal-0.41.0-macos15-arm64.txt'
# Unchanged owner line: earlier script-owned staging stays replaceable and removable.
OWNER = 'owner=jellyscope-desktop-mpv-metal\n'
MARKER = 'mpv-metal-runtime.txt'
NOTICES = 'mpv-notices'
STAGING_PREFIX = '.mpv-metal-staging-'
def need(ok, message):
    if not ok: sys.exit('ERROR: '+message)
def no_links(p): return not any(x.is_symlink() for x in (p, *p.parents))
def safe(p):
    need(p.is_absolute() and p == p.resolve() and len(p.parts) >= 4, 'Unsafe resources path')
    need(no_links(p), 'Resources path traverses a symlink')
    repo = scripts.parent
    need(p not in (repo, *repo.parents, pathlib.Path.home()), 'Refusing source/root directory')
    if repo in p.parents:
        rel = p.relative_to(repo).parts
        need((rel[0] == '.local' and len(rel) >= 3) or ('build' in rel and rel[-1] != 'build'), 'Resources must be generated build output')
def owned(d):
    # Only a directory this script created may be replaced or removed.
    marker = d/MARKER
    return (not d.is_symlink() and d.is_dir() and not marker.is_symlink() and marker.is_file()
            and marker.read_text().startswith(OWNER))
def remove_stale_staging(parent):
    for p in parent.glob(STAGING_PREFIX+'*'):
        if owned(p) or (p.is_dir() and not p.is_symlink() and not any(p.iterdir())): shutil.rmtree(p)
LIBRARIES = {x[5:] for x in manifest.read_text().splitlines() if x.startswith('file=')}
need('libmpv.2.dylib' in LIBRARIES and 'libMoltenVK.dylib' in LIBRARIES, 'Metal ABI manifest lacks its runtime')
def inventory(d, marked):
    """Exact top-level layout and no links anywhere, then the source-built graph checks."""
    for root, dirs, names in os.walk(d):
        for name in dirs + names: need(not (pathlib.Path(root)/name).is_symlink(), 'Symlink in Metal runtime: '+name)
    expected = LIBRARIES | {NOTICES} | ({MARKER} if marked else set())
    need({p.name for p in d.iterdir()} == expected and (d/NOTICES).is_dir(), 'Metal runtime inventory contains missing or unexpected entries')
    subprocess.run(['bash', str(scripts/'prepare-desktop-mpv-bundle.sh'), '--metal', '--validate-only', str(d)], check=True)
def identity(d):
    return ''.join([OWNER, 'build=source-built\n',
                    *(x+'\n' for x in manifest.read_text().splitlines() if x.startswith('bundle-id=')),
                    (d/NOTICES/'build/versions.txt').read_text()])
def validate(d):
    need(owned(d), 'Missing or unowned Metal runtime: '+str(d))
    inventory(d, True)
    need((d/MARKER).read_text() == identity(d), 'Metal runtime identity record differs from its contents')

safe(resources)
target = resources/'mpv-metal'
if mode == '--clean':
    if target.exists() or target.is_symlink():
        need(owned(target), 'Refusing to remove an unowned mpv-metal directory')
        shutil.rmtree(target)
    if resources.parent.is_dir(): remove_stale_staging(resources.parent)
    sys.exit(0)
if mode == '--validate-only':
    validate(target)
    print('Validated isolated source-built Metal mpv runtime in '+str(target), file=sys.stderr)
    sys.exit(0)

source = pathlib.Path(candidate)
need(source.is_absolute() and source == source.resolve() and no_links(source) and source.is_dir(), 'Unsafe or missing Metal runtime directory')
need(source != resources and source not in resources.parents and resources not in source.parents, 'Metal runtime overlaps the resources directory')
need(resources.is_dir(), 'Missing prepared resources: '+str(resources))
# Earlier prebuilt comparison candidates fail here: only the source-built graph is accepted.
inventory(source, False)
need(not (target.exists() or target.is_symlink()) or owned(target), 'Refusing an unowned mpv-metal directory')
remove_stale_staging(resources.parent)
# Stage outside the packaged platform directory so a failed run never ships.
staging = pathlib.Path(tempfile.mkdtemp(prefix=STAGING_PREFIX, dir=resources.parent))
try:
    (staging/MARKER).write_text(OWNER+'state=staging\n')
    for name in LIBRARIES: shutil.copy2(source/name, staging/name)
    shutil.copytree(source/NOTICES, staging/NOTICES)
    (staging/MARKER).write_text(identity(staging))
    validate(staging)
    if target.exists(): shutil.rmtree(target)
    os.replace(staging, target)
finally:
    if staging.exists(): shutil.rmtree(staging)
validate(target)
print('Staged isolated source-built Metal mpv runtime in '+str(target), file=sys.stderr)
PY
