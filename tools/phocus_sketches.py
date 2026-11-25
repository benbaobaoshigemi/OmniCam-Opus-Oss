"""Renders the style-list cards of the Phocus watermark styles.

The gallery's watermark editor lays its cards out as squares and the camera watermark settings page
as tall cards (2:3), so each card is a render of the framed picture in the shape of its list: the
photo crop is chosen so that photo + frame come out at that shape.

  python phocus_sketches.py <base photo> [output dir]

The base photo is any portrait photo; the output is sketch_personalize_more_phocus_<id>.png (editor,
475x475) and sketch_camera_personalize_more_phocus_<id>.png (camera page, 475x712) in the output dir
(default: module/payload/more_series).
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ENGINE = os.path.normpath(os.path.join(HERE, "..", "..", "..", "实验", "Phocus-Watermark-Engine"))
sys.path.insert(0, ENGINE)

from PIL import Image  # noqa: E402

from phocus_watermark.models import STYLE_SPECS, format_metadata  # noqa: E402
from phocus_watermark.renderer import PhocusWatermarkEngine  # noqa: E402

TILE = 475
SHAPES = (("sketch_%s", 1.0), ("sketch_camera_%s", 1.5))  # file name, height / width
STYLES = (1, 2, 3, 4, 6, 7, 8, 9, 12, 13, 14, 15)


def framed_photo_size(spec, width, ratio):
    """Photo height that makes photo + frame `ratio` (height / width) for a photo `width` wide."""
    for landscape in (False, True):
        pad = spec.padding_landscape if landscape else spec.padding_portrait
        scale = width / ((878.0 if landscape else 462.0) - pad[0] - pad[2])
        canvas_w = width + int(pad[0] * scale) + int(pad[2] * scale)
        height = round(canvas_w * ratio) - int(pad[1] * scale) - int(pad[3] * scale)
        if (width > height) == landscape:
            return height
    raise ValueError("no %.2f frame for style %d" % (ratio, spec.style_id))


def center_crop(photo, width, height):
    ratio = width / height
    w, h = photo.size
    if w / h > ratio:
        crop_w = round(h * ratio)
        box = ((w - crop_w) // 2, 0, (w - crop_w) // 2 + crop_w, h)
    else:
        crop_h = round(w / ratio)
        box = (0, (h - crop_h) // 2, w, (h - crop_h) // 2 + crop_h)
    return photo.crop(box).resize((width, height), Image.LANCZOS)


def main():
    photo = Image.open(sys.argv[1]).convert("RGB")
    out_dir = sys.argv[2] if len(sys.argv) > 2 else os.path.join(HERE, "..", "module", "payload", "more_series")
    meta = format_metadata(aperture=2.8, shutter="1/250", iso=100, lens="XCD 38", body_name="X2D 100C",
                           create_time_str="2026 11 15")
    engine = PhocusWatermarkEngine()
    width = 1200
    for style_id in STYLES:
        spec = STYLE_SPECS[style_id]
        framed = sum(spec.padding_portrait) + sum(spec.padding_landscape) > 0
        for name, ratio in SHAPES:
            height = framed_photo_size(spec, width, ratio) if framed else round(width * ratio)
            card = engine.render(center_crop(photo, width, height), style_id, metadata=meta, seed=7)
            card = center_crop(card, TILE, round(TILE * ratio))
            card.save(os.path.join(out_dir, (name % ("personalize_more_phocus_%d" % style_id)) + ".png"), optimize=True)
        print(style_id, "ok")


main()
