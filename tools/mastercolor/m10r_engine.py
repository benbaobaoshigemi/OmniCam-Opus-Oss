"""Leica M10-R in-camera JPEG look (firmware 40.26.35.51), kind 17: packer and reference renderer.

The M10-R imaging pipeline (`IMG-System` B2Y + `IMG-CA9 System` ColorSpec), without noise reduction, white balance, or sharpening:
  - Contrast has 3 steps (low / medium / high = -1..1, 0 = default medium): isotropic RGB multiplier in ProPhoto D50 linear
    domain, indexed by `Y = tone_weights · pro >> 1` (`10240 x u16` per step, `32768 = 1.0`).
  - Saturation has 3 steps in Color (`low / medium / high = -1..1`, `0.8501 / 1.0000 / 1.19995`) and 1 fixed step in Monochrome (`0.0`).
  - CC0 is recomputed per frame CCT by 1/T interpolation between `cm_lt` (2850 K) and `cm_ht` (6807 K) with Bradford CAT from D50
    to scene white; CC1 maps ProPhoto D50 to linear sRGB; DIFF_GAMMA maps `[0..32767]` to `[0..16383]` sRGB code; BT.601 YCbCr
    scales `(Cb, Cr)` by `sat_scale`.
The scene (ProPhoto D50, 18 % grey = 0.18) reaches the tables through the framework's transfer C = G(F^-1(B)): the M10-R Adobe
Standard HueSatMap is inverted to the colorimetric scene `a`, and the Adobe forward matrix turns `a` into 14-bit WB camera RGB.

Binary layout (kind = 17):
  8B  "MCOLLOOK", u32 version = 1, u32 kind = 17
  u32 sat_steps (3 color, 1 monochrome), f32 gray_code, f32 t_lt, f32 t_ht
  f32[9] fm1 (2856 K), f32[9] fm2 (6504 K)
  f32[9] cm_lt, f32[9] cm_ht, f32[9] xyz_to_pro, f32[9] pro_to_srgb
  f32[3] tone_weights, u16[3 * 10240] tone_gain, u16[32768] diff_gamma
  f32[9] yc_matrix, f32[sat_steps] sat_scales
  u32 hue_divs, u32 sat_divs, f32 dcp_t1, f32 dcp_t2, f32 hs1[hue*sat*3], f32 hs2[hue*sat*3]
"""
import struct
from pathlib import Path
import numpy as np

MODES = ("color", "monochrome")
KIND = 17
TONE_LEN = 10240
DG_LEN = 32768


def pack(params_npz, out, mode="color"):
    p = np.load(params_npz)
    assert mode in MODES, mode
    sat_scales = p["sat_scales"][:3] if mode == "color" else p["sat_scales"][3:4]
    sat_steps = len(sat_scales)
    tone = p["tone_gain"]
    assert tone.shape == (3, TONE_LEN) and int(tone.max()) <= 65535
    dg = p["diff_gamma"]
    assert dg.shape == (DG_LEN,) and int(dg.max()) <= 65535

    hs1 = p["hsm1"].astype("<f4")
    hs2 = p["hsm2"].astype("<f4")
    _, hd, sd, _ = hs1.shape

    b = bytearray(b"MCOLLOOK")
    b += struct.pack("<II", 1, KIND)
    b += struct.pack("<Ifff", sat_steps, float(p["gray_code"]), float(p["t_lt"]), float(p["t_ht"]))
    for m in (p["fm1"], p["fm2"], p["cm_lt"], p["cm_ht"], p["xyz_to_pro"], p["pro_to_srgb"]):
        b += np.asarray(m, dtype="<f4").tobytes()
    b += np.asarray(p["tone_weights"], dtype="<f4").tobytes()
    b += tone.astype("<u2").tobytes()
    b += dg.astype("<u2").tobytes()
    b += np.asarray(p["yc_matrix"], dtype="<f4").tobytes()
    b += np.asarray(sat_scales, dtype="<f4").tobytes()
    b += struct.pack("<IIff", hd, sd, float(p["dcp_t1"]), float(p["dcp_t2"]))
    b += hs1.tobytes() + hs2.tobytes()
    Path(out).write_bytes(bytes(b))


def decode(b):
    at = 16
    sat_steps, gray_code, t_lt, t_ht = struct.unpack_from("<Ifff", b, at)
    at += 16
    mats = []
    for _ in range(6):
        mats.append(np.frombuffer(b, "<f4", 9, at).astype(np.float64).reshape(3, 3))
        at += 36
    fm1, fm2, cm_lt, cm_ht, xyz_to_pro, pro_to_srgb = mats
    tone_weights = np.frombuffer(b, "<f4", 3, at).astype(np.float64)
    at += 12
    tone = np.frombuffer(b, "<u2", 3 * TONE_LEN, at).reshape(3, TONE_LEN).astype(np.float64) / 32768.0
    at += 3 * TONE_LEN * 2
    dg = np.frombuffer(b, "<u2", DG_LEN, at).astype(np.float64)
    at += DG_LEN * 2
    yc_matrix = np.frombuffer(b, "<f4", 9, at).astype(np.float64).reshape(3, 3)
    at += 36
    sat_scales = np.frombuffer(b, "<f4", sat_steps, at).astype(np.float64)
    at += sat_steps * 4
    hd, sd, dcp_t1, dcp_t2 = struct.unpack_from("<IIff", b, at)
    at += 16
    n = hd * sd * 3
    hs1 = np.frombuffer(b, "<f4", n, at).astype(np.float64).reshape(1, hd, sd, 3)
    at += 4 * n
    hs2 = np.frombuffer(b, "<f4", n, at).astype(np.float64).reshape(1, hd, sd, 3)
    at += 4 * n
    assert at == len(b), (at, len(b))
    return dict(
        sat_steps=sat_steps,
        gray_code=gray_code,
        t_lt=t_lt,
        t_ht=t_ht,
        fm=(fm1, fm2),
        cm=(cm_lt, cm_ht),
        xyz_to_pro=xyz_to_pro,
        pro_to_srgb=pro_to_srgb,
        tone_weights=tone_weights,
        tone=tone,
        diff_gamma=dg,
        yc_matrix=yc_matrix,
        yc_inv=np.linalg.inv(yc_matrix),
        sat_scales=sat_scales,
        hsm=dict(hd=hd, sd=sd, t1=dcp_t1, t2=dcp_t2, table1=hs1, table2=hs2),
    )


def render(p, pp, cct, contrast=0, saturation=0):
    """pp: linear ProPhoto D50 with 18% grey at 0.18 (n, 3). contrast: -1..1, saturation: -1..1. Returns linear P3."""
    import looks as L
    import x100vi_engine as X

    h = p["hsm"]
    w = float(np.clip((1.0 / cct - 1.0 / h["t1"]) / (1.0 / h["t2"] - 1.0 / h["t1"]), 0.0, 1.0))
    a = X.inverse_hue_sat(np.asarray(pp, np.float64), h["table1"] * (1.0 - w) + h["table2"] * w)
    xyz = a @ L.PROPHOTO_TO_XYZ.T
    fm = p["fm"][0] * (1.0 - w) + p["fm"][1] * w
    cam = np.clip((xyz @ np.linalg.inv(fm).T) * (p["gray_code"] / 0.18), 0.0, None)

    # CA9 dynamic CC0 at frame cct
    g_cm = float(np.clip((1.0 / cct - 1.0 / p["t_ht"]) / (1.0 / p["t_lt"] - 1.0 / p["t_ht"]), 0.0, 1.0))
    ab = g_cm * p["cm"][0] + (1.0 - g_cm) * p["cm"][1]
    white = L.white_xyz(cct)
    cam_w = ab @ white
    asn = cam_w / cam_w[1]
    ca = L.cat(L.D50, white)
    cc0 = p["xyz_to_pro"] @ np.linalg.inv(ab @ ca) @ np.diag(asn)

    pro = np.clip(cam @ cc0.T, 0.0, 16383.0)
    cs = int(np.clip(round(contrast), -1, 1)) + 1
    ss = int(np.clip(round(saturation), -1, 1)) + 1 if p["sat_steps"] == 3 else 0

    y = np.clip(pro @ p["tone_weights"], 0.0, 16383.0)
    t_idx = np.clip(np.floor(y * 0.5).astype(np.int64), 0, TONE_LEN - 1)
    pro_t = np.clip(pro * p["tone"][cs][t_idx, None], 0.0, 16383.0)

    lin = np.clip(pro_t @ p["pro_to_srgb"].T, 0.0, 32767.0)
    rgb = np.interp(lin, np.arange(DG_LEN), p["diff_gamma"]) / 16383.0

    sat_scale = float(p["sat_scales"][ss])
    if abs(sat_scale - 1.0) > 1e-6:
        ycc = rgb @ p["yc_matrix"].T
        ycc[:, 1:] *= sat_scale
        rgb = np.clip(ycc @ p["yc_inv"].T, 0.0, 1.0)
    else:
        rgb = np.clip(rgb, 0.0, 1.0)

    lin_srgb = L.srgb_decode(rgb)
    return np.clip(lin_srgb @ L.SRGB_TO_P3.T, 0.0, 1.0)
