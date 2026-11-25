"""Build the master colour looks: firmware engines, Lumix styles and HNCS variants.

Usage: python build_assets.py [--photon <PhotonCamera checkout>] --out <looks directory> [--hncs-only]
"""
import argparse
from pathlib import Path
import struct
import sys

import numpy as np

HERE = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--photon", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--hncs-only", action="store_true")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    if not args.hncs_only:
        sys.path.insert(0, str(HERE.parent))
        import refs  # noqa: E402
        photon = args.photon or refs.PHOTONCAMERA
        assets = photon / "app/src/main/assets"
        import firmware_engines
        firmware_engines.pack(assets, args.out)
        import m9_engine
        m9_engine.pack(refs.M9_UPD, refs.ADOBE_CAMERA_RAW / "CameraProfiles/Adobe Standard/M9 Digital Camera Adobe Standard.dcp", args.out / "leica_m9.bin")
        import m240_engine
        for mode in m240_engine.MODES:
            m240_engine.pack(refs.M240_PARAMS, refs.ADOBE_CAMERA_RAW / "CameraProfiles/Adobe Standard/LEICA M (Typ 240) Adobe Standard.dcp",
                             args.out / ("leica_m240_" + mode + ".bin"), mode)
        import m10r_engine
        for mode in m10r_engine.MODES:
            m10r_engine.pack(refs.M10R_PARAMS, args.out / ("leica_m10r_" + mode + ".bin"), mode)
        import looks
        looks.PHOTON = assets
        grey = looks.lumix_grey()
        for style in looks.LUMIX_STYLES:
            (args.out / ("lumix_" + style + ".bin")).write_bytes(looks.pack_lumix(style, grey))
    # HNCS look presets ("hncs" is built into hncs_engine.bin; the 6 variants live in looks/hncs_<id>.bin).
    for legacy in args.out.glob("hncs_*_*.bin"):
        legacy.unlink()
    sys.path.insert(0, str(HERE))
    import hncs_engine as E  # noqa: E402

    k_std = E.Engine(5500).k
    k_old = E.Engine(5500, film=E.FILM_OLD, companding_gamma=True).k
    k_rep = E.Engine(5500, film=E.FILM_REPRO, tables=E.REPRO_TABLES, is_reproduction=True, apply_hs=False).k
    variants = [
        ("nature", k_std, E.FLAG_HIGHLIGHT_STRENGTH | E.FLAG_HAS_GRADATION, 1.0, 0.0, 1.0, E.FILM, E.TABLES, E.NATURE_GRADATION),
        ("saturated", k_std, E.FLAG_HIGHLIGHT_STRENGTH, 1.10, 0.0, 1.0, E.FILM, E.TABLES, None),
        ("extra_contrast", k_std, E.FLAG_HIGHLIGHT_STRENGTH, 1.0, 0.20, 1.20, E.FILM, E.TABLES, None),
        ("lighten", k_std, E.FLAG_HIGHLIGHT_STRENGTH | E.FLAG_HAS_GRADATION, 1.0, 0.0, 1.0, E.FILM, E.TABLES, E.LIGHTEN_GRADATION),
        ("old_standard", k_old, E.FLAG_COMPANDING_GAMMA | E.FLAG_HIGHLIGHT_STRENGTH, 1.0, 0.0, 1.0, E.FILM_OLD, E.TABLES, None),
        ("reproduction", k_rep, E.FLAG_IS_REPRODUCTION, 1.0, 0.0, 1.0, E.FILM_REPRO, E.REPRO_TABLES, None),
    ]
    for vid, k, flags, sat_scale, contrast, gamma, film, tables, gradation in variants:
        b = bytearray(b"MCOLLOOK") + struct.pack("<IIfIfff", 1, 9, k, flags, sat_scale, contrast, gamma)
        b += np.round(film * 65535.0).astype("<u2").tobytes()
        b += struct.pack("<I", len(tables))
        for temp, vals in tables:
            b += struct.pack("<f", temp) + vals.astype("<f4").tobytes()
        if gradation is not None:
            b += np.round(gradation * 65535.0).astype("<u2").tobytes()
        (args.out / f"hncs_{vid}.bin").write_bytes(bytes(b))
    print(f"Complete: {len(variants)} HNCS looks -> {args.out}", flush=True)


if __name__ == "__main__":
    main()
