# Bundled VLC (LibVLC) Attribution

JellyScope's macOS desktop package bundles a selected subset of the
official VideoLAN VLC 3.0.23 macOS (arm64) binaries so the optional LibVLC
player backend works without a separate VLC installation. Staging may rewrite
Mach-O install names and dependency paths for the application bundle; this
preparation route does not rebuild VLC from source.

- libvlc / libvlccore: LGPL-2.1-or-later — © VideoLAN and authors.
- VLC plugins: LGPL-2.1-or-later and GPL-2.0-or-later — © VideoLAN and authors.
- Full license texts ship beside this file (`GPL-2.0.txt`, `LGPL-2.1.txt`);
  JellyScope-owned source is MPL-2.0 (repository root `LICENSE`).

Excluded from the bundle relative to the upstream distribution:
`libmacosx_plugin.dylib` (Sparkle dependency), `libosx_notifications_plugin.dylib`
(Growl dependency), `libdvdread_plugin.dylib`, `libdvdnav_plugin.dylib`
(statically linked libdvdcss), `liblibbluray_plugin.dylib`,
`libbluray-*.jar` (disc access), and `plugins.dat` (path-specific cache).

Corresponding source: VLC 3.0.23 sources are published by VideoLAN at
<https://download.videolan.org/pub/videolan/vlc/3.0.23/vlc-3.0.23.tar.xz>.
External distributions of this bundle must retain that source archive (or an
equivalent written offer) alongside the release artifacts to satisfy GPL/LGPL
source-availability obligations.

The audited input is pinned by `manifest-3.0.23-arm64.txt`; the bundle script
requires its selected files, expected architecture, and dependency closure.
Re-run the licensing/dependency audit before regenerating the manifest for a
different VLC version or architecture.
