#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Apply the generated borderless icon set to JellyScope platform projects."""

from __future__ import annotations

import shutil
import sys
from pathlib import Path

from PIL import Image

sys.dont_write_bytecode = True

from generate_assets import (
    APP_MARK_SCALE,
    RESAMPLE,
    SOURCE_ROOT,
    crop_mark,
    place_mark,
    save_png,
)


ROOT = Path(__file__).resolve().parent
REPO_ROOT = ROOT.parents[4]
ANDROID_SAFE_FRACTION = 0.48


def resize(image: Image.Image, size: tuple[int, int]) -> Image.Image:
    return image.resize(size, RESAMPLE)


def build_android_layers() -> tuple[Image.Image, Image.Image]:
    background = Image.open(
        ROOT / "jellyscope-adaptive-background-432.png"
    ).convert("RGBA")
    mark = crop_mark(
        Image.open(SOURCE_ROOT / "jellyscope-adaptive-foreground-432.png")
    )
    foreground = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
    place_mark(
        foreground,
        mark,
        (0, 0, 432, 432),
        content_scale=ANDROID_SAFE_FRACTION * APP_MARK_SCALE,
    )
    return background, foreground


def apply_android_icons() -> None:
    background, foreground = build_android_layers()
    densities = {
        "mdpi": (108, 48),
        "hdpi": (162, 72),
        "xhdpi": (216, 96),
        "xxhdpi": (324, 144),
        "xxxhdpi": (432, 192),
    }
    for module in ("android-app", "android-tv-app"):
        resources = REPO_ROOT / module / "src/main/res"
        for density, (layer_size, launcher_size) in densities.items():
            directory = resources / f"mipmap-{density}"
            sized_background = resize(background, (layer_size, layer_size))
            sized_foreground = resize(foreground, (layer_size, layer_size))
            composed = Image.alpha_composite(
                sized_background,
                sized_foreground,
            ).convert("RGB")
            composed = resize(composed, (launcher_size, launcher_size))
            save_png(sized_background, directory / "ic_launcher_background.png")
            save_png(sized_foreground, directory / "ic_launcher_foreground.png")
            save_png(composed, directory / "ic_launcher.png")
            save_png(composed, directory / "ic_launcher_round.png")


def apply_ios_icons() -> None:
    directory = REPO_ROOT / "ios-app/iosApp/Assets.xcassets/AppIcon.appiconset"
    source = Image.open(ROOT / "jellyscope-appstore-1024.png").convert("RGB")
    sizes = {
        "AppIcon-20@2x.png": 40,
        "AppIcon-20@3x.png": 60,
        "AppIcon-29@2x.png": 58,
        "AppIcon-29@3x.png": 87,
        "AppIcon-40@2x.png": 80,
        "AppIcon-40@3x.png": 120,
        "AppIcon-60@2x.png": 120,
        "AppIcon-60@3x.png": 180,
        "jellyscope-appstore-1024.png": 1024,
    }
    for filename, size in sizes.items():
        save_png(resize(source, (size, size)), directory / filename)


def apply_desktop_icons() -> None:
    directory = REPO_ROOT / "desktop-app/icons"
    shutil.copy2(ROOT / "jellyscope-macos.icns", directory / "jellyscope.icns")
    shutil.copy2(ROOT / "jellyscope-windows.ico", directory / "jellyscope.ico")
    shutil.copy2(ROOT / "jellyscope-playstore-512.png", directory / "jellyscope.png")


def apply_tvos_icons() -> None:
    source = ROOT / "jellyscope-tvos-icon-source"
    brandassets = (
        REPO_ROOT
        / "tvos-app/tvosApp/Assets.xcassets/App Icon & Top Shelf Image.brandassets"
    )
    background = Image.open(
        source / "jellyscope-tvos-layer-background-1280x768.png"
    ).convert("RGBA")
    foreground = Image.open(
        source / "jellyscope-tvos-layer-foreground-1280x768.png"
    ).convert("RGBA")

    app_store = brandassets / "App Icon - App Store.imagestack"
    save_png(
        background,
        app_store / "Back.imagestacklayer/Content.imageset/back-1280x768.png",
    )
    save_png(
        foreground,
        app_store / "Front.imagestacklayer/Content.imageset/front-1280x768.png",
    )

    launcher = brandassets / "App Icon.imagestack"
    for width, height in ((400, 240), (800, 480)):
        save_png(
            resize(background, (width, height)),
            launcher
            / f"Back.imagestacklayer/Content.imageset/back-{width}x{height}.png",
        )
        save_png(
            resize(foreground, (width, height)),
            launcher
            / f"Front.imagestacklayer/Content.imageset/front-{width}x{height}.png",
        )


def verify_android_reference_coverage() -> None:
    densities = ("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")
    expected_names = (
        "ic_launcher_background.png",
        "ic_launcher_foreground.png",
        "ic_launcher.png",
        "ic_launcher_round.png",
    )
    missing = [
        REPO_ROOT / module / "src/main/res" / f"mipmap-{density}" / filename
        for module in ("android-app", "android-tv-app")
        for density in densities
        for filename in expected_names
        if not (REPO_ROOT / module / "src/main/res" / f"mipmap-{density}" / filename).is_file()
    ]
    if missing:
        raise RuntimeError(
            "Generated Android launcher references are missing: "
            + ", ".join(str(path.relative_to(REPO_ROOT)) for path in missing),
        )


def verify_tvos_reference_coverage() -> None:
    brandassets = (
        REPO_ROOT
        / "tvos-app/tvosApp/Assets.xcassets/App Icon & Top Shelf Image.brandassets"
    )
    expected = (
        brandassets
        / "App Icon - App Store.imagestack/Back.imagestacklayer/Content.imageset/back-1280x768.png",
        brandassets
        / "App Icon - App Store.imagestack/Front.imagestacklayer/Content.imageset/front-1280x768.png",
        brandassets
        / "App Icon.imagestack/Back.imagestacklayer/Content.imageset/back-400x240.png",
        brandassets
        / "App Icon.imagestack/Back.imagestacklayer/Content.imageset/back-800x480.png",
        brandassets
        / "App Icon.imagestack/Front.imagestacklayer/Content.imageset/front-400x240.png",
        brandassets
        / "App Icon.imagestack/Front.imagestacklayer/Content.imageset/front-800x480.png",
    )
    missing = [path for path in expected if not path.is_file()]
    if missing:
        raise RuntimeError(
            "Generated tvOS icon references are missing: "
            + ", ".join(str(path.relative_to(REPO_ROOT)) for path in missing),
        )


def main() -> None:
    apply_android_icons()
    apply_ios_icons()
    apply_desktop_icons()
    apply_tvos_icons()
    verify_android_reference_coverage()
    verify_tvos_reference_coverage()


if __name__ == "__main__":
    main()
