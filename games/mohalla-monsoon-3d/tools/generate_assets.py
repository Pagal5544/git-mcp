#!/usr/bin/env python3
"""Generate deterministic high-detail RGB surface maps for the native Android renderer.

The source maps are deliberately kept as uncompressed raw RGB assets in the APK. The
renderer averages each 2x2 texel block while loading, so GPU memory uses 1024x1024
textures even though the distributed texture pack contains 2048x2048 source detail.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import sys

import numpy as np
from PIL import Image

SIZE = 2048
CHANNELS = 3
EXPECTED_BYTES = SIZE * SIZE * CHANNELS
ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "app" / "src" / "main" / "assets" / "textures"


def cloud_noise(rng: np.random.Generator, grid: int) -> np.ndarray:
    small = rng.integers(0, 256, size=(grid, grid), dtype=np.uint8)
    im = Image.fromarray(small, mode="L").resize((SIZE, SIZE), Image.Resampling.BILINEAR)
    return (np.asarray(im, dtype=np.float32) - 127.5) / 48.0


def make_texture(kind: str, seed: int) -> np.ndarray:
    rng = np.random.default_rng(seed)
    y = np.arange(SIZE, dtype=np.float32)[:, None]
    x = np.arange(SIZE, dtype=np.float32)[None, :]
    fine = rng.standard_normal((SIZE, SIZE), dtype=np.float32)
    medium = cloud_noise(rng, 128)
    broad = cloud_noise(rng, 24)
    micro = rng.standard_normal((SIZE, SIZE), dtype=np.float32) * 2.4

    if kind == "asphalt":
        grain = 63.0 + fine * 11.0 + medium * 10.0 + broad * 9.0 + micro
        chips = rng.random((SIZE, SIZE), dtype=np.float32) < 0.025
        grain += chips * rng.uniform(12.0, 55.0, (SIZE, SIZE)).astype(np.float32)
        r, g, b = grain * 1.04, grain * 1.02, grain * 0.98
    elif kind == "plaster":
        streaks = np.sin(y * 0.035) * 3.3 + np.sin(y * 0.012 + x * 0.001) * 2.0
        stains = np.maximum(0.0, broad * 2.0 + medium * 0.7) * 6.0
        base = 174.0 + fine * 3.0 + medium * 7.0 + broad * 11.0 + streaks - stains
        r, g, b = base * 1.04, base * 0.98, base * 0.88
    elif kind == "brick":
        rows = np.floor(y / 132.0).astype(np.int32)
        offset = (rows % 2) * 124
        tx = np.mod(x + offset, 256.0)
        ty = np.mod(y, 132.0)
        mortar = (tx < 8.0) | (ty < 9.0)
        edge = np.minimum(np.minimum(tx, 256.0 - tx), np.minimum(ty, 132.0 - ty))
        shade = 145.0 + fine * 9.0 + medium * 8.0 + broad * 10.0 - np.maximum(0.0, 18.0 - edge) * 0.9
        r, g, b = shade * 1.18, shade * 0.57, shade * 0.40
        r = np.where(mortar, 153.0 + fine * 3.0, r)
        g = np.where(mortar, 143.0 + fine * 3.0, g)
        b = np.where(mortar, 126.0 + fine * 3.0, b)
    elif kind == "mud":
        rut = np.sin(y * 0.09 + np.sin(x * 0.006) * 3.0) * 5.0
        base = 96.0 + fine * 17.0 + medium * 17.0 + broad * 15.0 + rut
        pebble = (rng.random((SIZE, SIZE), dtype=np.float32) < 0.018)
        base += pebble * rng.uniform(18.0, 50.0, (SIZE, SIZE)).astype(np.float32)
        r, g, b = base * 1.09, base * 0.79, base * 0.52
    elif kind == "clay_roof":
        rows = np.floor(y / 112.0).astype(np.int32)
        tx = np.mod(x + (rows % 2) * 84, 168.0)
        ty = np.mod(y, 112.0)
        edge = np.minimum(np.minimum(tx, 168.0 - tx), np.minimum(ty, 112.0 - ty))
        groove = edge < 7.0
        tile = 153.0 + fine * 5.5 + medium * 8.0 + broad * 6.0
        bevel = np.clip(edge, 0, 18) * 0.9
        tile += bevel
        r, g, b = tile * 1.14, tile * 0.62, tile * 0.43
        r = np.where(groove, r * 0.49, r)
        g = np.where(groove, g * 0.48, g)
        b = np.where(groove, b * 0.52, b)
    elif kind == "concrete":
        cracks = np.abs(np.sin(x * 0.008 + np.sin(y * 0.006) * 2.0)) < 0.018
        base = 133.0 + fine * 6.0 + medium * 11.0 + broad * 9.0
        base -= cracks * 26.0
        aggregate = rng.random((SIZE, SIZE), dtype=np.float32) < 0.014
        base += aggregate * rng.uniform(-28.0, 34.0, (SIZE, SIZE)).astype(np.float32)
        r, g, b = base * 1.01, base, base * 0.94
    elif kind == "fabric":
        weave = np.sin(x * 1.27) * 2.5 + np.sin(y * 1.31) * 2.1
        base = 101.0 + fine * 4.0 + medium * 5.0 + weave
        r, g, b = base * 0.58, base * 0.88, base * 1.07
    else:
        raise ValueError(f"Unknown texture kind: {kind}")

    rgb = np.stack((r, g, b), axis=-1)
    result = np.clip(rgb, 0, 255).astype(np.uint8)
    return np.ascontiguousarray(result)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--size", type=int, default=SIZE,
                        help="Texture side length; CI uses 2048 to meet the distributable content target.")
    args = parser.parse_args()
    if args.size != SIZE:
        print(f"This project's native loader currently expects {SIZE}x{SIZE}; refusing --size={args.size}.", file=sys.stderr)
        return 2

    OUT.mkdir(parents=True, exist_ok=True)
    maps = [
        ("asphalt", 1001),
        ("plaster", 1002),
        ("brick", 1003),
        ("mud", 1004),
        ("clay_roof", 1005),
        ("concrete", 1006),
        ("fabric", 1007),
    ]
    total = 0
    for name, seed in maps:
        path = OUT / f"{name}.rgbtex"
        pixels = make_texture(name, seed)
        pixels.tofile(path)
        actual = path.stat().st_size
        if actual != EXPECTED_BYTES:
            raise RuntimeError(f"{path.name}: expected {EXPECTED_BYTES} bytes, got {actual}")
        total += actual
        print(f"{path.name}: {actual:,} bytes")
        del pixels
    print(f"Generated {len(maps)} surface maps: {total:,} raw bytes ({total / (1024 * 1024):.1f} MiB)")
    print("Runtime renderer downsamples each map to 1024x1024 before creating OpenGL textures.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
