# JellyScope borderless app icons

This folder mirrors the platform asset matrix in `../appIcon`, rebuilt from the
approved borderless sample.

The square sources are fully opaque and contain no baked squircle, perimeter
border, rounded corners, or transparent corner treatment. Platform launchers
remain responsible for applying their own masks.

Launcher artwork uses a `0.90` content scale so the jellyfish retains additional
breathing room after platform-specific icon masks are applied. The approved
borderless sample remains unchanged as the visual reference.

Included outputs:

- App Store and Play Store icons
- Android adaptive foreground and background layers
- Linux PNG sizes
- macOS iconset and ICNS
- Windows multi-resolution ICO
- Android TV banners
- tvOS app-icon layers, app-icon composites, and top-shelf artwork

The TV compositions retain the existing poster background and typography while
placing the jellyfish directly on the artwork without a framed icon container.

Regenerate with a Python environment containing Pillow:

```bash
python3 generate_assets.py
```

Apply the generated launcher icons to the Android, iOS, desktop, and tvOS
projects with:

```bash
python3 apply_platform_assets.py
```

Android TV banners and tvOS top-shelf artwork are intentionally left unchanged;
the `0.90` scale applies only to launcher/app-icon artwork.
