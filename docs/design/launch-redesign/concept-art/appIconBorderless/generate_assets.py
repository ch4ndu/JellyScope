#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Build the complete borderless JellyScope platform icon set."""

from __future__ import annotations

import shutil
import struct
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter


ROOT = Path(__file__).resolve().parent
SOURCE_ROOT = ROOT.parent / "appIcon"
SAMPLE = SOURCE_ROOT / "jellyscope-borderless-sample-1024.png"
RESAMPLE = Image.Resampling.LANCZOS
APP_MARK_SCALE = 0.90


def save_png(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, format="PNG", optimize=True)


def resize(image: Image.Image, size: tuple[int, int]) -> Image.Image:
    return image.resize(size, RESAMPLE)


def inset_opaque_icon(image: Image.Image, scale: float) -> Image.Image:
    """Inset a finished icon while extending its edge-safe background."""
    image = image.convert("RGB")
    width, height = image.size
    inset_size = (round(width * scale), round(height * scale))
    inset = image.resize(inset_size, RESAMPLE)

    border = Image.new("RGB", image.size)
    border_pixels = []
    border_pixels.extend(image.crop((0, 0, width, 1)).get_flattened_data())
    border_pixels.extend(
        image.crop((0, height - 1, width, height)).get_flattened_data()
    )
    border_pixels.extend(image.crop((0, 0, 1, height)).get_flattened_data())
    border_pixels.extend(
        image.crop((width - 1, 0, width, height)).get_flattened_data()
    )
    channels = tuple(zip(*border_pixels, strict=True))
    background = tuple(round(sum(channel) / len(channel)) for channel in channels)
    border.paste(background, (0, 0, width, height))

    feather = max(2, round(min(inset_size) * 0.015))
    mask = Image.new("L", inset_size, 0)
    draw = ImageDraw.Draw(mask)
    draw.rectangle(
        (feather, feather, inset_size[0] - feather, inset_size[1] - feather),
        fill=255,
    )
    mask = mask.filter(ImageFilter.GaussianBlur(feather / 2))
    position = ((width - inset_size[0]) // 2, (height - inset_size[1]) // 2)
    border.paste(inset, position, mask)
    return border


def scale_transparent_layer(image: Image.Image, scale: float) -> Image.Image:
    """Scale visible layer content around the canvas center."""
    image = image.convert("RGBA")
    rendered = image.resize(
        (round(image.width * scale), round(image.height * scale)),
        RESAMPLE,
    )
    canvas = Image.new("RGBA", image.size, (0, 0, 0, 0))
    canvas.alpha_composite(
        rendered,
        ((image.width - rendered.width) // 2, (image.height - rendered.height) // 2),
    )
    return canvas


def write_icns(iconset_dir: Path, output: Path) -> None:
    """Write all ten macOS PNG representations into an ICNS container."""
    representations = (
        (b"icp4", "icon_16x16.png"),
        (b"ic11", "icon_16x16@2x.png"),
        (b"icp5", "icon_32x32.png"),
        (b"ic12", "icon_32x32@2x.png"),
        (b"ic07", "icon_128x128.png"),
        (b"ic13", "icon_128x128@2x.png"),
        (b"ic08", "icon_256x256.png"),
        (b"ic14", "icon_256x256@2x.png"),
        (b"ic09", "icon_512x512.png"),
        (b"ic10", "icon_512x512@2x.png"),
    )
    chunks = []
    for chunk_type, filename in representations:
        png = (iconset_dir / filename).read_bytes()
        chunks.append(chunk_type + struct.pack(">I", len(png) + 8) + png)
    payload = b"".join(chunks)
    output.write_bytes(b"icns" + struct.pack(">I", len(payload) + 8) + payload)


def cover(image: Image.Image, size: tuple[int, int]) -> Image.Image:
    target_width, target_height = size
    scale = max(target_width / image.width, target_height / image.height)
    resized = image.resize(
        (round(image.width * scale), round(image.height * scale)),
        RESAMPLE,
    )
    left = (resized.width - target_width) // 2
    top = (resized.height - target_height) // 2
    return resized.crop((left, top, left + target_width, top + target_height))


def blend_right(
    base: Image.Image,
    original: Image.Image,
    start_x: int,
    feather: int,
) -> Image.Image:
    base = base.convert("RGBA")
    original = original.convert("RGBA")
    mask = Image.new("L", base.size, 0)
    pixels = mask.load()
    for x in range(start_x, base.width):
        alpha = 255 if x >= start_x + feather else round(
            255 * (x - start_x) / feather
        )
        for y in range(base.height):
            pixels[x, y] = alpha
    return Image.composite(original, base, mask)


def crop_mark(mark: Image.Image) -> Image.Image:
    mark = mark.convert("RGBA")
    bounds = mark.getbbox()
    if bounds is None:
        raise ValueError("Adaptive foreground contains no visible pixels")
    return mark.crop(bounds)


def place_mark(
    canvas: Image.Image,
    mark: Image.Image,
    bounds: tuple[int, int, int, int],
    content_scale: float = 1.0,
) -> None:
    left, top, right, bottom = bounds
    max_width = right - left
    max_height = bottom - top
    scale = min(max_width / mark.width, max_height / mark.height) * content_scale
    rendered = mark.resize(
        (round(mark.width * scale), round(mark.height * scale)),
        RESAMPLE,
    )
    x = left + (max_width - rendered.width) // 2
    y = top + (max_height - rendered.height) // 2
    canvas.alpha_composite(rendered, (x, y))


def build_square_icons(sample: Image.Image) -> None:
    applied_sample = inset_opaque_icon(sample, APP_MARK_SCALE)
    save_png(
        resize(applied_sample, (1254, 1254)),
        ROOT / "jellyscope-source-final.png",
    )
    save_png(
        resize(applied_sample, (4096, 4096)),
        ROOT / "jellyscope-master-4096.png",
    )
    save_png(applied_sample, ROOT / "jellyscope-appstore-1024.png")
    save_png(
        resize(applied_sample, (512, 512)),
        ROOT / "jellyscope-playstore-512.png",
    )
    save_png(sample, ROOT / "jellyscope-borderless-sample-1024.png")

    linux_dir = ROOT / "jellyscope-linux-icons"
    for size in (32, 64, 128, 256, 512, 1024):
        save_png(
            resize(applied_sample, (size, size)),
            linux_dir / f"jellyscope-{size}.png",
        )

    iconset_dir = ROOT / "jellyscope-macos.iconset"
    macos_files = {
        "icon_16x16.png": 16,
        "icon_16x16@2x.png": 32,
        "icon_32x32.png": 32,
        "icon_32x32@2x.png": 64,
        "icon_128x128.png": 128,
        "icon_128x128@2x.png": 256,
        "icon_256x256.png": 256,
        "icon_256x256@2x.png": 512,
        "icon_512x512.png": 512,
        "icon_512x512@2x.png": 1024,
    }
    for filename, size in macos_files.items():
        save_png(resize(applied_sample, (size, size)), iconset_dir / filename)

    write_icns(iconset_dir, ROOT / "jellyscope-macos.icns")

    applied_sample.save(
        ROOT / "jellyscope-windows.ico",
        format="ICO",
        sizes=[
            (16, 16),
            (24, 24),
            (32, 32),
            (48, 48),
            (64, 64),
            (128, 128),
            (256, 256),
        ],
    )


def build_adaptive_icons() -> Image.Image:
    adaptive_foreground = SOURCE_ROOT / "jellyscope-adaptive-foreground-432.png"
    adaptive_background = SOURCE_ROOT / "jellyscope-adaptive-background-432.png"
    foreground = Image.open(adaptive_foreground).convert("RGBA")
    save_png(
        scale_transparent_layer(foreground, APP_MARK_SCALE),
        ROOT / "jellyscope-adaptive-foreground-432.png",
    )
    shutil.copy2(
        adaptive_background,
        ROOT / "jellyscope-adaptive-background-432.png",
    )
    return crop_mark(foreground)


def build_tvos(mark: Image.Image) -> None:
    source_dir = SOURCE_ROOT / "jellyscope-tvos-icon-source"
    output_dir = ROOT / "jellyscope-tvos-icon-source"
    output_dir.mkdir(parents=True, exist_ok=True)

    background = Image.open(
        source_dir / "jellyscope-tvos-layer-background-1280x768.png"
    ).convert("RGBA")
    old_foreground = Image.open(
        source_dir / "jellyscope-tvos-layer-foreground-1280x768.png"
    ).convert("RGBA")

    foreground = Image.new("RGBA", (1280, 768), (0, 0, 0, 0))
    foreground.alpha_composite(
        old_foreground.crop((560, 0, 1280, 768)),
        (560, 0),
    )
    place_mark(
        foreground,
        mark,
        (105, 135, 535, 650),
        content_scale=APP_MARK_SCALE,
    )

    app_icon_large = Image.alpha_composite(background, foreground)
    save_png(
        background,
        output_dir / "jellyscope-tvos-layer-background-1280x768.png",
    )
    save_png(
        foreground,
        output_dir / "jellyscope-tvos-layer-foreground-1280x768.png",
    )
    save_png(
        app_icon_large,
        output_dir / "jellyscope-tvos-app-icon-large-1280x768.png",
    )
    save_png(
        resize(app_icon_large, (400, 240)),
        output_dir / "jellyscope-tvos-app-icon-small-400x240.png",
    )

    shelf_specs = (
        (
            "jellyscope-tvos-top-shelf-1920x720.png",
            (1920, 720),
            730,
            (145, 85, 700, 650),
        ),
        (
            "jellyscope-tvos-top-shelf-wide-2320x720.png",
            (2320, 720),
            790,
            (180, 75, 760, 655),
        ),
    )
    for filename, size, right_start, mark_bounds in shelf_specs:
        original = Image.open(source_dir / filename).convert("RGBA")
        base = cover(background, size)
        base = Image.alpha_composite(
            base,
            Image.new("RGBA", size, (1, 10, 22, 58)),
        )
        composed = blend_right(base, original, right_start, 110)
        place_mark(composed, mark, mark_bounds)
        save_png(composed, output_dir / filename)

    standard = Image.open(
        output_dir / "jellyscope-tvos-top-shelf-1920x720.png"
    ).convert("RGBA")
    wide = Image.open(
        output_dir / "jellyscope-tvos-top-shelf-wide-2320x720.png"
    ).convert("RGBA")
    save_png(
        resize(standard, (3840, 1440)),
        output_dir / "jellyscope-tvos-top-shelf-3840x1440.png",
    )
    save_png(
        resize(wide, (4640, 1440)),
        output_dir / "jellyscope-tvos-top-shelf-wide-4640x1440.png",
    )


def build_android_tv(mark: Image.Image) -> None:
    original = Image.open(
        SOURCE_ROOT / "jellyscope-tv-banner-master-1920x1080.png"
    ).convert("RGBA")
    background = Image.open(
        SOURCE_ROOT
        / "jellyscope-tvos-icon-source"
        / "jellyscope-tvos-layer-background-1280x768.png"
    ).convert("RGBA")
    size = (1920, 1080)
    base = cover(background, size)
    base = Image.alpha_composite(
        base,
        Image.new("RGBA", size, (1, 10, 22, 58)),
    )
    banner = blend_right(base, original, 750, 120)
    place_mark(banner, mark, (120, 150, 720, 930))
    save_png(banner, ROOT / "jellyscope-tv-banner-master-1920x1080.png")
    save_png(
        resize(banner, (320, 180)),
        ROOT / "jellyscope-tv-banner-320x180.png",
    )


def main() -> None:
    sample = Image.open(SAMPLE).convert("RGB")
    build_square_icons(sample)
    mark = build_adaptive_icons()
    build_tvos(mark)
    build_android_tv(mark)


if __name__ == "__main__":
    main()
