"""Renders style-list cards and skeleton JSONs for the 19 Xiaomi Leica watermark styles.

Gallery's watermark editor displays 1:1 square cards (475x475), and the camera watermark settings
page displays 2:3 tall cards (475x712). Each card is rendered using the target aspect crop so
the framed/overlaid preview matches the list tile cleanly without distorting the frame bands.

  python leica_sketches.py [base portrait photo] [output dir]
"""
import json
import os
import sys
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
ENGINE = os.path.normpath(os.path.join(HERE, "..", "..", "..", "实验", "Xiaomi-Leica-Watermark-Engine"))
DEFAULT_PHOTO = os.path.normpath(
    os.path.join(HERE, "..", "..", "..", "实验", "Phocus-Watermark-Engine", "samples", "portrait", "style_00_none.jpg")
)
sys.path.insert(0, ENGINE)

from PIL import Image  # noqa: E402

from xiaomi_leica_watermark import (  # noqa: E402
    PhotoMetadata,
    WatermarkOptions,
    get_style_info,
    render,
)

TILE = 475
SHAPES = (("sketch_%s", 1.0), ("sketch_camera_%s", 1.5))  # file name, height / width
STYLES = ("1", "2", "3", "4", "5", "67", "68", "69", "70", "88", "89", "90", "91", "92", "93", "94", "104", "111", "135")
STYLE_PREFIX = "personalize_leica_"


ANCHOR_Y = 1.0  # tall photos are cropped towards their lower part, where the subject sits


def center_crop(photo, width, height):
    ratio = width / height
    w, h = photo.size
    if w / h > ratio:
        crop_w = round(h * ratio)
        box = ((w - crop_w) // 2, 0, (w - crop_w) // 2 + crop_w, h)
    else:
        crop_h = round(w / ratio)
        top = round((h - crop_h) * ANCHOR_Y)
        box = (0, top, w, top + crop_h)
    return photo.crop(box).resize((width, height), Image.LANCZOS)


def sample_meta():
    return PhotoMetadata(
        market_name="OPPO Find X9 Ultra",
        focal_length_mm=70.0,
        aperture=2.1,
        shutter_speed="1/250",
        iso=100,
        taken_at=datetime(2026, 5, 18, 17, 30),
        latitude=31.2304,
        longitude=121.4737,
        address="Lujiazui, Shanghai",
        cv_lens="Summilux",
        filter_name="Vivid",
    )


def frame_paddings_for_style(style_id, meta):
    """Measure portrait border paddings in 1080-base design units from the real layout engine."""
    probe = Image.new("RGB", (1080, 1440), (0, 0, 0))
    res = render(
        probe,
        WatermarkOptions(style_id=style_id, model_render_mode="text", badge_brand="leica"),
        meta,
    )
    cw, ch = res.image.size
    bx, by, bw, bh = res.src_bounds
    return float(bx), float(by), float(max(0, cw - bx - bw)), float(max(0, ch - by - bh))


def solve_framed_photo_size(style_id, width, target_ratio, meta):
    """Compute source photo height so photo + Leica frame hits the requested aspect ratio."""
    l, t, r, b = frame_paddings_for_style(style_id, meta)
    if l + t + r + b <= 0.1:
        return round(width * target_ratio)
    # Try portrait first: photo_h >= width -> scale = width / 1080
    scale_p = width / 1080.0
    canvas_w_p = width + round((l + r) * scale_p)
    photo_h_p = round(canvas_w_p * target_ratio) - round((t + b) * scale_p)
    if photo_h_p >= width:
        return photo_h_p
    # Otherwise landscape photo: photo_h < width -> scale = photo_h / 1080
    # photo_h + (t + b) * (photo_h / 1080) == target_ratio * (width + (l + r) * (photo_h / 1080))
    denom = 1.0 + (t + b - target_ratio * (l + r)) / 1080.0
    photo_h_l = round((target_ratio * width) / max(0.1, denom))
    return max(100, min(width - 1, photo_h_l))


def build_skeleton_json(style_id, meta):
    info = get_style_info(style_id)
    l, t, r, b = frame_paddings_for_style(style_id, meta)
    has_border = (l + t + r + b) > 0.1 and info.position != "in"
    bg_color = "#141414" if style_id == "111" else ("#00FFFFFF" if not has_border else "#FFFFFF")
    return {
        "styleId": f"{STYLE_PREFIX}{style_id}",
        "version": 1,
        "baseImageSize": 1080,
        "imageOffset": {
            "startX": l if has_border else 0.0,
            "startY": t if has_border else 0.0,
        },
        "size": {
            "leftMargin": l if has_border else 0.0,
            "topMargin": t if has_border else 0.0,
            "rightMargin": r if has_border else 0.0,
            "bottomMargin": b if has_border else 0.0,
        },
        "background": {
            "backgroundType": 0,
            "color": bg_color,
        },
        "bitmaps": [
            {
                "direction": 3 if has_border else 4,
                "width": 0,
                "height": 0,
                "position": {"borderX": 0, "borderY": 0},
                "elements": [],
            }
        ],
    }


def main():
    photo_path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_PHOTO
    out_dir = sys.argv[2] if len(sys.argv) > 2 else os.path.join(HERE, "..", "module", "payload", "leica_series")
    os.makedirs(out_dir, exist_ok=True)
    photo = Image.open(photo_path).convert("RGB")
    meta = sample_meta()
    width = 1080

    for sid in STYLES:
        spec_json = build_skeleton_json(sid, meta)
        json_path = os.path.join(out_dir, f"{STYLE_PREFIX}{sid}.json")
        with open(json_path, "w", encoding="utf-8", newline="\n") as fp:
            json.dump(spec_json, fp, indent=2, ensure_ascii=False)
            fp.write("\n")

        info = get_style_info(sid)
        framed = info.position != "in"
        for name_tpl, ratio in SHAPES:
            height = solve_framed_photo_size(sid, width, ratio, meta) if framed else round(width * ratio)
            cropped = center_crop(photo, width, height)
            card = render(
                cropped,
                WatermarkOptions(style_id=sid, model_render_mode="text", badge_brand="leica"),
                meta,
            ).image
            card = center_crop(card, TILE, round(TILE * ratio))
            out_png = os.path.join(out_dir, f"{name_tpl % (STYLE_PREFIX + sid)}.png")
            card.save(out_png, optimize=True)
        print(sid, "ok")


if __name__ == "__main__":
    main()
