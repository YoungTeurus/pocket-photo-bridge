"""Regenerate independent vectors with Pillow 12.3.0 (not a build dependency)."""

from pathlib import Path
from PIL import Image

destination = Path(__file__).parent
profiles = [
    ("rgb420", "RGB", (17, 19), 2),
    ("rgb422", "RGB", (23, 11), 1),
    ("rgb444", "RGB", (9, 13), 0),
    ("gray", "L", (13, 11), 0),
]
for name, mode, size, sampling in profiles:
    image = Image.new(mode, size)
    for y in range(size[1]):
        for x in range(size[0]):
            pixel = ((x * 37 + y * 19) % 256, (x * 11 + y * 53) % 256, (x * 71 + y * 7) % 256)
            image.putpixel((x, y), pixel[0] if mode == "L" else pixel)
    for optimized in (True, False):
        suffix = "optimized" if optimized else "standard"
        image.save(destination / f"{name}-{suffix}.jpg", quality=90, optimize=optimized,
                   progressive=False, subsampling=sampling)

image = Image.new("RGB", (19, 17), (17, 123, 209))
image.save(destination / "progressive.jpg", quality=90, optimize=True, progressive=True)
image.save(destination / "restart.jpg", quality=90, optimize=True, progressive=False, restart_marker_blocks=1)
image.save(destination / "standard-tables.jpg", quality=90, optimize=False, progressive=False)
image = Image.new("RGB", (1040, 1560), (31, 107, 191))
for optimized in (True, False):
    suffix = "optimized" if optimized else "standard"
    image.save(destination / f"portrait-{suffix}.jpg", quality=90, optimize=optimized, progressive=False)
