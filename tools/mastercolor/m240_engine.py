"""Leica M (Typ 240) in-camera JPEG look (firmware 2.1.0.0), kind 16: packer and reference renderer.

The three film modes Standard, Vivid and Natural of the M240's image processor (IMG_LOKI-212), without noise reduction, white balance,
sharpening or black and white. The tables come from the firmware; experiments/Leica-M240引擎 (README, tools/extract_params.py) documents where they
sit and how a film mode, contrast step and saturation step pick them:
  - Standard has five contrast steps and five saturation steps (stored -2 .. 2). Vivid and Natural have one fixed tone gain and one fixed
    saturation curve; their contrast and saturation settings do nothing (as in the firmware).
  - tone gain   4096 x u16 per step, 2048 = 1.0, multiplied onto each 12-bit linear channel (index = value, clamped to 4095)
  - gamma       4096 x u8, 12-bit linear -> 8-bit sRGB-like code (one curve for all modes)
  - saturation  256 x u8 per step, 128 = 1.0: BT.601 chroma gain on the gamma code, indexed by the 8-bit luma
The scene (ProPhoto D50, 18 % grey = 0.18) reaches the tables through the framework's transfer C = G(F^-1(B)): the M240 Adobe Standard
HueSatMap is inverted to the colorimetric scene a, the Adobe forward matrix (interpolated in 1/T) turns a into the white-balanced
12-bit camera RGB (18 % grey at `gray_code`, clamped to [0, 4095]), and the firmware's fixed sRGB colorcomp matrix (group 1) maps it
to linear sRGB code before tone gain, gamma and lumisat.

Binary layout (kind = 16):
  8B  "MCOLLOOK", u32 version = 1, u32 kind = 16
  u32 steps (5 Standard, 1 Vivid / Natural), f32 gray_code (12-bit code of the 18 % grey)
  f32[9] forward matrix 1 (Adobe Standard, illuminant 2856 K), f32[9] forward matrix 2 (6504 K): XYZ D50 = FM . camera
  f32[9] colorcomp (firmware sRGB matrix group 1, row major)
  u16[steps * 4096] tone gain, u8[4096] gamma, u8[steps * 256] saturation curves
  u32 hue divisions, u32 sat divisions, f32 t1, f32 t2, f32 table1[hue][sat][3], f32 table2   (M240 Adobe Standard HueSatMap)
"""
import struct
import sys
from pathlib import Path

import numpy as np

MODES = ("standard", "vivid", "natural")
KIND = 16


def pack(params, dcp, out, mode):
    """params: engine/params/m240_params.npz of the experiment; mode: standard / vivid / natural."""
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import m9_engine as M9

    p = np.load(params)
    sl = {"standard": slice(0, 5), "vivid": slice(5, 6), "natural": slice(6, 7)}[mode]
    tone = p["tone_gain"][sl].astype("<u2")
    lumi = p["lumisat"][sl].astype(np.uint8)
    hd, sd, t1, t2, fm, tables = M9._adobe_dcp(dcp)
    b = b"MCOLLOOK" + struct.pack("<IIIf", 1, KIND, len(tone), float(p["gray_code"]))
    b += b"".join(m.astype("<f4").tobytes() for m in fm) + p["colorcomp"].astype("<f4").tobytes()
    b += tone.tobytes() + p["gamma"].astype(np.uint8).tobytes() + lumi.tobytes()
    b += struct.pack("<IIff", hd, sd, t1, t2) + tables[0].astype("<f4").tobytes() + tables[1].astype("<f4").tobytes()
    Path(out).write_bytes(b)


def decode(b):
    at = 16
    steps, gray = struct.unpack_from("<If", b, at)
    at += 8
    fm = np.frombuffer(b, "<f4", 18, at).astype(np.float64).reshape(2, 3, 3)
    at += 72
    colorcomp = np.frombuffer(b, "<f4", 9, at).astype(np.float64).reshape(3, 3)
    at += 36
    tone = np.frombuffer(b, "<u2", steps * 4096, at).reshape(steps, 4096).astype(np.float64) / 2048.0
    at += steps * 8192
    gamma = np.frombuffer(b, np.uint8, 4096, at).astype(np.float64) / 255.0
    at += 4096
    lumi = np.frombuffer(b, np.uint8, steps * 256, at).reshape(steps, 256).astype(np.float64) / 128.0
    at += steps * 256
    hd, sd, t1, t2 = struct.unpack_from("<IIff", b, at)
    at += 16
    n = hd * sd * 3
    t = [np.frombuffer(b, "<f4", n, at + 4 * n * i).astype(np.float64).reshape(1, hd, sd, 3) for i in range(2)]
    assert at + 8 * n == len(b)
    return dict(steps=steps, gray=gray, fm=fm, colorcomp=colorcomp, tone=tone, gamma=gamma, lumi=lumi, hsm=dict(hd=hd, sd=sd, t1=t1, t2=t2, table1=t[0], table2=t[1]))


def render(p, pp, cct, contrast=0, saturation=0):
    """pp: linear ProPhoto D50 with the 18 % grey at 0.18 (n, 3). contrast / saturation: -2..2 (Standard only). Returns linear P3."""
    import looks as L
    import x100vi_engine as X

    h = p["hsm"]
    w = float(np.clip((1.0 / cct - 1.0 / h["t1"]) / (1.0 / h["t2"] - 1.0 / h["t1"]), 0.0, 1.0))
    a = X.inverse_hue_sat(np.asarray(pp, np.float64), h["table1"] * (1.0 - w) + h["table2"] * w)
    xyz = a @ L.PROPHOTO_TO_XYZ.T
    fm = p["fm"][0] * (1.0 - w) + p["fm"][1] * w
    cam = np.clip((xyz @ np.linalg.inv(fm).T) * (p["gray"] / 0.18), 0.0, 4095.0)
    lin = cam @ p["colorcomp"].T
    code = np.floor(np.clip(lin, 0.0, 4095.0)).astype(np.int64)
    if p["steps"] == 5:
        cs = int(np.clip(round(contrast), -2, 2)) + 2
        ss = int(np.clip(round(saturation), -2, 2)) + 2
    else:
        cs = ss = 0
    gain = p["tone"][cs][code]
    y = np.floor(np.minimum(code * gain, 4095.0)).astype(np.int64)
    enc = p["gamma"][y]
    luma = enc @ np.array([0.299, 0.587, 0.114])
    s = p["lumi"][ss][np.clip(np.floor(luma * 255.0), 0, 255).astype(np.int64)]
    cb = (enc[..., 2] - luma) * 0.564 * s
    cr = (enc[..., 0] - luma) * 0.713 * s
    r = luma + 1.402 * cr
    b = luma + 1.772 * cb
    g = (luma - 0.299 * r - 0.114 * b) / 0.587
    enc = np.clip(np.stack([r, g, b], -1), 0.0, 1.0)
    srgb = np.where(enc <= 0.04045, enc / 12.92, ((enc + 0.055) / 1.055) ** 2.4)
    return np.clip(srgb @ L.SRGB_TO_P3.T, 0.0, 1.0)


if __name__ == "__main__":
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
    import refs

    dcp = refs.ADOBE_CAMERA_RAW / "CameraProfiles/Adobe Standard/LEICA M (Typ 240) Adobe Standard.dcp"
    for mode in MODES:
        pack(refs.M240_PARAMS, dcp, Path(sys.argv[1]) / f"leica_m240_{mode}.bin", mode)
