#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
# Source retention/inspection only. Never invokes a native compiler or builder.
set -euo pipefail
repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
python3 - "$repo_root" "$@" <<'PY'
import csv, gzip, os, pathlib, shutil, subprocess, sys, tarfile, tempfile, urllib.request
repo = pathlib.Path(sys.argv[1])
args = sys.argv[2:]
def need(ok, message):
    if not ok: sys.exit('ERROR: '+message)
def run(*args): return subprocess.check_output([str(x) for x in args], text=True).strip()
def values(p):
    pairs = [x.split('=',1) for x in p.read_text().splitlines() if x and not x.startswith('#')]
    need(all(len(x) == 2 for x in pairs) and len({x[0] for x in pairs}) == len(pairs), 'Invalid/duplicate source manifest fields')
    result = dict(pairs)
    for k,v in result.items():
        if k.endswith('-archive'): need(pathlib.Path(v).name == v and not v.startswith('.'), 'Unsafe source artifact path')
    return result
def rows(p): return list(csv.DictReader(p.open(),delimiter='\t'))
def safe(p):
    need(p.is_absolute() and p == p.resolve() and len(p.parts) >= 4, 'Unsafe source output/cache path')
    need(not any(x.is_symlink() for x in (p,*p.parents)), 'Source path traverses a symlink')
    need(p not in (repo,*repo.parents,pathlib.Path.home()), 'Refusing source/root directory')
    if repo in p.parents:
        rel = p.relative_to(repo).parts
        need(rel[0] in ('.local','release-artifacts') or 'build' in rel, 'Source output must be generated output')
usage = 'Usage: prepare-macos-release-sources.sh <output> <bundled-resources> | --verify-native-inputs <resources> | --verify-release <output> <resources> [--with-mpv-metal]'
# The caller's explicit request is the only Metal selector; resource contents never imply it.
metal = bool(args) and args[-1] == '--with-mpv-metal'
if metal: args.pop()
need(bool(args),usage)
mode = args.pop(0) if args[0].startswith('--') else 'prepare'
need(mode in ('prepare','--verify-native-inputs','--verify-release'),'Unknown source preparation mode')
out = pathlib.Path(args.pop(0)) if mode != '--verify-native-inputs' else None
need(len(args) == 1, 'Supply the actual staged/packaged native resource directory')
bundle = pathlib.Path(args[0])
for script in ['prepare-desktop-mpv-bundle.sh','prepare-desktop-vlc-bundle.sh']:
    subprocess.run(['bash',str(repo/'scripts'/script),'--validate-only',str(bundle)],check=True)
if metal:
    subprocess.run(['bash',str(repo/'scripts/prepare-desktop-mpv-metal-bundle.sh'),'--validate-only',str(bundle)],check=True)
else:
    need(not os.path.lexists(bundle/'mpv-metal'), 'Unrequested Metal runtime in '+str(bundle))
manifest = repo/'scripts/macos-source-bundle/manifest-macos-arm64.txt'
METAL_KEY = 'desktop-mpv-metal-'
# Metal provenance is recorded only when its graph is requested.
manifest_text = ''.join(x+'\n' for x in manifest.read_text().splitlines() if metal or not x.startswith(METAL_KEY))
meta_all = values(manifest)
meta = {k:v for k,v in meta_all.items() if metal or not k.startswith(METAL_KEY)}
selected = 'desktop-mpv,desktop-mpv-metal,vlc' if metal else 'desktop-mpv,vlc'
MPVKIT_PATCH = 'Sources/BuildScripts/patch/libmpv/0001-player-add-moltenvk-context.patch'
cache = pathlib.Path(os.environ.get('JELLYSCOPE_MACOS_SOURCE_CACHE_ROOT',str(repo/'.local/native/macos-sources')))
safe(cache); cache.mkdir(parents=True,exist_ok=True)
need(not any(p.is_symlink() for p in cache.rglob('*')), 'Symlink in source cache')
work = pathlib.Path(tempfile.mkdtemp(prefix='.source-inputs-',dir=cache))
try:
    def fetch(url, name):
        need(pathlib.Path(name).name == name and not name.startswith('.'), 'Unsafe source archive name')
        p = cache/name
        if not p.is_file():
            print('Retaining source input '+name,file=sys.stderr)
            with urllib.request.urlopen(url,timeout=120) as response, (work/name).open('wb') as f:
                shutil.copyfileobj(response,f)
            (work/name).replace(p)
        return p
    vlc = fetch(meta['vlc-url'],meta['vlc-archive'])
    with tarfile.open(vlc) as t:
        need(t.extractfile('vlc-3.0.24/src/revision.txt').read().decode().strip() == meta['vlc-revision'], 'VLC source revision mismatch')
        vlc_rules = {m.name[11:]:t.extractfile(m).read() for m in t if m.isfile() and m.name.startswith('vlc-3.0.24/contrib/src/') and m.name.endswith('/rules.mak')}
    mpv_notice, metal_notice = repo/'scripts/desktop-mpv-bundle', repo/'scripts/desktop-mpv-metal'
    mpv_rows = rows(mpv_notice/'sources-macos-arm64.tsv')
    vlc_rows = rows(repo/'scripts/vlc-bundle/sources-3.0.24.tsv')
    mpv_cache = pathlib.Path(os.environ.get('JELLYSCOPE_MPV_RUNTIME_CACHE_ROOT',str(repo/'.local/native/desktop-mpv')))/'archives'
    metal_cache = pathlib.Path(os.environ.get('JELLYSCOPE_MPV_METAL_RUNTIME_CACHE_ROOT',str(repo/'.local/native/desktop-mpv-metal')))/'archives'
    families = [('mpv',[(r,mpv_notice) for r in mpv_rows]),('vlc-contrib',[(r,repo/'scripts/vlc-bundle') for r in vlc_rows])]
    if metal:
        # The same merge as build-desktop-mpv-runtime.sh --metal: overlay rows replace same-named base rows.
        overlay = rows(metal_notice/'sources-macos-arm64.tsv')
        replaced = {r['component'] for r in overlay}
        for component in ('moltenvk','glslang','mpvkit-patch'):
            need([r['version'] for r in overlay if r['component'] == component] == [meta[METAL_KEY+component+'-revision']], 'Metal source pin mismatch: '+component)
        families.append(('mpv-metal',[(r,mpv_notice) for r in mpv_rows if r['component'] not in replaced]+[(r,metal_notice) for r in overlay]))
    inputs = {family:[] for family,_ in families}
    for family, source_rows in families:
        for row, notice in source_rows:
            need('PENDING' not in '\t'.join(row.values()), 'Unreviewed source/license input: '+row['component'])
            p = {'mpv':mpv_cache,'mpv-metal':metal_cache}[family]/row['archive'] if family != 'vlc-contrib' else fetch(row['url'],row['archive'])
            need(p.is_file() and not p.is_symlink(), 'Missing exact source input: '+row['archive'])
            if row.get('role') == 'patch':
                need(p.read_bytes().startswith(('From '+row['version']+' ').encode()), 'CoreAudio patch identity mismatch')
            else:
                with tarfile.open(p) as t:
                    root = row['unpack-root']
                    need(all(m.name.split('/')[0] == root for m in t.getmembers()), 'Source archive root mismatch: '+row['component'])
                    # These upstream archives use roots without a version suffix.
                    version_member = {'live555':'liveMedia/include/liveMedia_version.hh', 'mad':'CMakeLists.txt'}.get(row['component']) if family == 'vlc-contrib' else None
                    if version_member:
                        need(row['version'].encode() in t.extractfile(root+'/'+version_member).read(), 'Source version declaration mismatch: '+row['component'])
                    for member in row['license-members'].split(','):
                        need(t.extractfile(root+'/'+member).read() == (notice/'licenses'/row['component']/member).read_bytes(), 'Source/notice mismatch: '+row['component']+'/'+member)
                    if family == 'mpv-metal' and row['component'] == 'mpvkit-patch':
                        need(t.extractfile(root+'/'+MPVKIT_PATCH).read() == (metal_notice/'mpvkit-moltenvk.patch').read_bytes(), 'MPVKit context patch differs from its pinned upstream member')
            if family == 'vlc-contrib':
                need(row['contrib-rule'] in vlc_rules, 'Missing corresponding VLC contrib recipe')
                tokens = [row['version']]
                if row['component'] in ('ffmpeg','gnutls'):
                    major, revision = row['version'].rsplit('.',1)
                    tokens = [f'FFMPEG_MAJVERSION := {major}',f'FFMPEG_REVISION := {revision}'] if row['component'] == 'ffmpeg' else [f'GNUTLS_MAJVERSION := {major}',f'GNUTLS_VERSION := $(GNUTLS_MAJVERSION).{revision}']
                need(all(token.encode() in vlc_rules[row['contrib-rule']] for token in tokens), 'VLC contrib recipe version mismatch: '+row['component'])
            inputs[family].append(p)
    if mode == '--verify-native-inputs':
        print('Verified exact native source archives, recipes, notices and packaged runtime versions')
        sys.exit(0)
    safe(out); out.mkdir(parents=True,exist_ok=True)
    revision = run('git','-C',repo,'rev-parse','HEAD')
    dirty = bool(run('git','-C',repo,'status','--porcelain','--untracked-files=normal'))
    project_name = 'JellyScope-source-'+revision+'.tar.gz'
    groups = [('mpv','desktop-mpv-archive'),('vlc-contrib','vlc-contrib-archive')] + ([('mpv-metal',METAL_KEY+'archive')] if metal else [])
    # Shipped provenance and recipe per group; the Metal sidecar also carries its patches and build records via its notices.
    notices = {'mpv':'mpv-notices','mpv-metal':'mpv-metal/mpv-notices','vlc-contrib':'licenses'}
    recipes = {'mpv':['build-desktop-mpv-runtime.sh','prepare-desktop-mpv-bundle.sh'],
               'mpv-metal':['build-desktop-mpv-runtime.sh','prepare-desktop-mpv-bundle.sh','prepare-desktop-mpv-metal-bundle.sh'],
               'vlc-contrib':['prepare-desktop-vlc-bundle.sh']}
    def check_release():
        recorded = values(out/'SOURCE_MANIFEST.txt')
        need(not dirty and recorded.get('tracked-worktree-dirty') == 'false', 'Source-bound release requires the exact clean candidate')
        need(recorded.get('jellyscope-revision') == revision, 'Release source revision mismatch')
        need(recorded.get('selected-graphs') == selected and (metal or not any(k.startswith(METAL_KEY) for k in recorded)),
             'Recorded native graph selection differs from the request')
        for k,v in meta.items():
            if not k.endswith('-archive'): need(recorded.get(k) == v, 'Source provenance mismatch: '+k)
        with gzip.open(out/recorded['jellyscope-archive'],'rb') as f:
            archive_revision = subprocess.check_output(['git','get-tar-commit-id'],input=f.read(),text=False).decode().strip()
        need(archive_revision == revision, 'JellyScope archive does not match the clean candidate')
        need((out/recorded['vlc-archive']).read_bytes() == vlc.read_bytes(), 'Collected VLC source mismatch')
        for family,key in groups:
            with tarfile.open(out/recorded[key]) as t:
                for p in inputs[family]: need(t.extractfile('archives/'+p.name).read() == p.read_bytes(), 'Collected source input mismatch: '+p.name)
                reference = bundle/notices[family]
                for p in reference.rglob('*'):
                    if p.is_file(): need(t.extractfile('notices/'+str(p.relative_to(reference))).read() == p.read_bytes(), 'Native source artifact differs from shipped provenance')
                if family != 'vlc-contrib':
                    for name in recipes[family]:
                        need(t.extractfile('recipe/'+name).read() == (repo/'scripts'/name).read_bytes(), 'Collected native recipe mismatch')
        print('Verified native source artifacts against the shipped bundle and clean '+revision)
    if mode == '--verify-release':
        check_release(); sys.exit(0)
    project_tar = work/'project.tar'
    subprocess.run(['git','-C',str(repo),'archive','--format=tar','--prefix=JellyScope-'+revision+'/', '--output='+str(project_tar),revision],check=True)
    with project_tar.open('rb') as src, gzip.GzipFile(filename=str(work/project_name),mode='wb',mtime=0) as dest:
        shutil.copyfileobj(src,dest)
    for family,key in groups:
        with tarfile.open(work/meta[key],'w:gz') as t:
            for p in inputs[family]: t.add(p,arcname='archives/'+p.name,recursive=False)
            t.add(bundle/notices[family],arcname='notices')
            for name in recipes[family]:
                t.add(repo/'scripts'/name,arcname='recipe/'+name,recursive=False)
    shutil.copy2(vlc,work/meta['vlc-archive'])
    (work/'SOURCE_MANIFEST.txt').write_text(manifest_text+'selected-graphs='+selected+'\njellyscope-revision='+revision+'\njellyscope-archive='+project_name+'\ntracked-worktree-dirty='+str(dirty).lower()+'\n')
    stale = out/meta_all[METAL_KEY+'archive']
    if not metal and (stale.is_file() or stale.is_symlink()):
        # Never leave an unrequested Metal sidecar beside a Standard-only package.
        need(not stale.is_symlink(), 'Source artifact destination is a symlink')
        stale.unlink()
    for name in [project_name,meta['vlc-archive'],meta['desktop-mpv-archive'],meta['vlc-contrib-archive'],'SOURCE_MANIFEST.txt'] + ([meta[METAL_KEY+'archive']] if metal else []):
        need(not (out/name).is_symlink(), 'Source artifact destination is a symlink')
        (work/name).replace(out/name)
    if not dirty: check_release()
    print('Retained project, '+selected+' sources; tracked-worktree-dirty='+str(dirty).lower())
finally:
    shutil.rmtree(work)
PY
