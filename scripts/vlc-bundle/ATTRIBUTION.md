# Bundled VLC (LibVLC) attribution

JellyScope's macOS package includes the 334 files in
`manifest-3.0.24-arm64.txt` from VideoLAN's official VLC 3.0.24 arm64 DMG:
<https://download.videolan.org/pub/videolan/vlc/3.0.24/macosx/vlc-3.0.24-arm64.dmg>.
Its source revision is `3.0.24-rc1-0-g6de05adcba`. Packaging relocates install
IDs/dependencies and restores signatures; it does not rebuild VLC.

LibVLC/libvlccore and VLC plugins retain their LGPL-2.1-or-later and
GPL-2.0-or-later terms. `GPL-2.0.txt`, `LGPL-2.1.txt` and `licenses/` preserve
the original VLC and selected contrib notices. The combined macOS distribution
uses the accepted GPL-compatible route; JellyScope-owned source remains MPL-2.0.

`sources-3.0.24.tsv` records the exact selected contrib versions, published
archives, unpack roots, original license members and owning recipes in the VLC
source tar. It includes static and header dependencies used by the retained
plugins. VLC's FFmpeg 8.1.2 and other contrib versions are independent of the
project-built mpv graph. The source collector retains these matching archives
alongside VLC's source tar, which supplies their build rules and patches:
<https://download.videolan.org/pub/videolan/vlc/3.0.24/vlc-3.0.24.tar.xz>.
Missing or mismatched inputs fail native source readiness. Upstream published
provenance is the basis for this record; rebuilding VLC reproducibly is not
asserted.

The existing exclusions remain: `libmacosx_plugin` (Sparkle),
`libosx_notifications_plugin` (Growl), `libdvdread_plugin`, `libdvdnav_plugin`
(libdvdcss), `liblibbluray_plugin`, Blu-ray Java extras and `plugins.dat`.
Upstream 3.0.24 removes `libaccess_realrtsp_plugin` and `libschroedinger_plugin`
from the previous selected graph. `libvout_macosx_plugin` is retained.

This software is based in part on the work of the Independent JPEG Group.
Portions of this software are copyright © The FreeType Project
(<https://www.freetype.org/>). All rights reserved. Original Unicode terms are
retained from <https://www.unicode.org/license.txt>. Source and notice archives
must remain available alongside external distributions of this runtime.
