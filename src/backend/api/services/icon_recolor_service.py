"""Pixel-level icon recoloring: replaces dark pixels in an image with a target color,
preserving each pixel's original alpha. Used to re-theme built-in monochrome icons to
a user-chosen marker color.
"""
from io import BytesIO
from pathlib import Path

from PIL import Image

# Pixels with relative luminance below this are considered "dark" and get recolored.
_BRIGHTNESS_THRESHOLD = 200


def _hex_to_rgb(hex_color: str) -> tuple[int, int, int]:
    hex_color = hex_color.lstrip('#')
    return int(hex_color[0:2], 16), int(hex_color[2:4], 16), int(hex_color[4:6], 16)


def recolor_dark_pixels(img: Image.Image, hex_color: str) -> Image.Image:
    """
    Replace dark pixels (by relative luminance) in an image with hex_color, preserving
    each pixel's original alpha. Mutates and returns an RGBA copy of the given image.
    """
    if img.mode != 'RGBA':
        img = img.convert('RGBA')

    r, g, b = _hex_to_rgb(hex_color)
    pixels = img.load()
    width, height = img.size

    for y in range(height):
        for x in range(width):
            pixel_r, pixel_g, pixel_b, pixel_a = pixels[x, y]
            if pixel_a == 0:
                continue
            brightness = 0.299 * pixel_r + 0.587 * pixel_g + 0.114 * pixel_b
            if brightness < _BRIGHTNESS_THRESHOLD:
                pixels[x, y] = (r, g, b, pixel_a)

    return img


def recolor_icon_file_to_png_bytes(icon_path: Path, hex_color: str) -> bytes:
    """Load an icon file, recolor its dark pixels, and return the result as PNG bytes."""
    with Image.open(icon_path) as img:
        recolored = recolor_dark_pixels(img, hex_color)
        output = BytesIO()
        recolored.save(output, format='PNG')
        return output.getvalue()
