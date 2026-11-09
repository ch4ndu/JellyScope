# Borderless app icons

Opaque square sources leave corner masks to each platform. Launcher artwork uses
90% content scale for spacing; TV banners and top-shelf artwork are unchanged.

From this directory, with Python and Pillow installed:

```bash
python3 generate_assets.py
python3 apply_platform_assets.py
```

The first command regenerates assets; the second applies launcher icons to the
platform projects. [Asset provenance](../../ASSETS-LICENSE.md) covers ownership.
