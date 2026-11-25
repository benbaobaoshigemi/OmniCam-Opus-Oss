"""Fujifilm X100VI film simulation engine (kind 13): extractor, packer, and reference renderer.

Extracts all 20 film simulations directly from the X-T5 v4.31 firmware FWUP0030.DAT without
approximations (the X100VI v1.31 firmware holds byte-identical tone/LUT tables; see the 前段标定_X100VI document): curve40 (13-segment cubic Bezier Y-tone gain), 3x3 Q10 color matrix,
tables31 (10-segment cubic Bezier RGB tone curves), YCC chroma 2x2 matrix (plus firmware
Sepia Cb/Cr offset with luma knee rolloff baked into Sepia's 17^3 lattice), and the 4
on-chip 17^3 3D LUTs (identity, Bleach Bypass, Nostalgic Neg., Reala ACE).

Binary layout (kind = 13):
  8B  "MCOLLOOK"
  4B  u32 version = 1
  4B  u32 kind = 13
  4B  f32 input_scale (shared X-T5 exposure scale: 18 % grey -> sRGB 118/255 on Provia)
  f32[165 + 17^3 * 3]:
    [0..39]     curve40 / 512
    [40..132]   tables31 (3 x 31) / 16384
    [133..143]  tables31 shared edges (11 knots, 0..16384)
    [144..152]  coefficients9 (3x3) / 1024
    [153..161]  ycc_to_rgb @ diag(1, chroma4_2x2) @ rgb_to_ycc (3x3)
    [162..164]  rgb_to_ycc[0] (luma weights)
    [165..]     17^3 RGB 3D LUT (B, G, R order, normalized 0..1)
  then the X-T5 Adobe Standard HueSatMap (the front end was fitted on the forward-matrix scene; the renderer inverts the map first):
    u32 hue divisions, u32 sat divisions, f32 t1, f32 t2 (illuminant temperatures), f32 table1[hue][sat][3], f32 table2
"""
import struct
import sys
from pathlib import Path

import numpy as np

# (look_id_suffix, fsim_enum, bw_filter, mapped_idx, curve40_bank2_id)
X100VI_SIMS = [
    ("provia",         0, 0,  0,   3),
    ("velvia",         1, 0,  1,  43),
    ("astia",          5, 0,  2,   3),
    ("classic_chrome", 8, 0,  5,  43),
    ("reala_ace",     13, 0, 10,  93),
    ("pro_neg_hi",     7, 0,  4,  43),
    ("pro_neg_std",    6, 0,  3,  43),
    ("classic_neg",   10, 0,  7, 123),
    ("nostalgic_neg", 12, 0,  9, 223),
    ("eterna",         9, 0,  6,  83),
    ("bleach_bypass", 11, 0,  8,  83),
    ("acros",         16, 0, 18, 123),
    ("acros_ye",      17, 0, 19, 123),
    ("acros_r",       18, 0, 20, 123),
    ("acros_g",       19, 0, 21, 123),
    ("monochrome",     2, 0, 13,   3),
    ("monochrome_ye",  2, 1, 14,   3),
    ("monochrome_r",   2, 2, 15,   3),
    ("monochrome_g",   2, 3, 16,   3),
    ("sepia",          3, 0, 17,   3),
]

X100VI_IDS = [s[0] for s in X100VI_SIMS]
# Shared scene scale of every look, fixed to the value the front stage was fitted with (Provia 18 % grey -> sRGB 118.5/255, the camera's own value).
INPUT_SCALE = 0.569589
ADOBE_GAMMA = 563.0 / 256.0
ADOBE_TO_P3 = np.array([
    [1.15015701, -0.15009213, -0.00003531],
    [0.04641974, 0.95356952, 0.00000245],
    [0.02388714, 0.02650547, 0.94960698],
], dtype=np.float64)
# Dynamic range: the 40-point tone curve comes from one of four firmware tables chosen by msg+4 (b30 0x13731cc): table 2 (206 entries, b30 0x1bd1878) is DR100,
# table 1 (124 entries, b30 0x1bcc718) is DR200 with id = DR100 id + 1. Above lum 1 (scene about 2.2) the DR200 gain continues past the table end from its end value and slope; DR100 saturates there.
SHADOW_ROWS = 7
SHADOW_FLOATS = 2 * SHADOW_ROWS * 40       # DR100 rows then DR200 rows
# The 3D LUT is extrapolated linearly past the top of its index range (the film colour gain pushes saturated blues above 1; the camera's Color Chrome FX Blue
# then acts on the overshoot); below 0 the index stays clamped.
LUT_CODE_MAX = 1.5
TONE_EDGES = np.array([0, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096, 6144, 8192, 12288, 16384], dtype=np.float64)

# Front stage fitted on the camera's own renders of a shading-compensated synthetic chart (X100VI via X RAW STUDIO, Provia plus grey640):
# linear sRGB (D65) -> firmware working space before tone40 (pure linear 3x3).
FRONT_M = np.array([
    [ 1.175569, -0.086910, -0.088659],
    [-0.026913,  0.990242,  0.036671],
    [-0.132675,  0.143012,  0.989663],
], dtype=np.float64)
FRONT_GAIN = 0.996683  # scene gain putting 18 % grey on Provia 118/255 (inputScale comes after)
FRONT_PEDESTAL = 0.000123  # black pedestal of the camera render, subtracted from the linear scene
# Firmware Rec.601 RGB <-> YCbCr (b30 0x00B08210, Q10) for chroma4 Cb/Cr range clamping.
_FW_YCC = np.array([[306, 601, 117], [-173, -339, 512], [512, -429, -83]], dtype=np.float64) / 1024.0
_FW_YCC_INV = np.linalg.inv(_FW_YCC)
# Firmware Color -4..+4 chroma gain: EEPROM 0xaf50..0xaf58 unsigned bytes / 128 (scales the 2x2 chroma4 matrix).
WB_SHIFT_R = np.array([554,641,735,782,834,892,913,945,977,1024,1050,1088,1124,1159,1242,1315,1417,1612,1896], dtype=np.float64) / 1024.0
WB_SHIFT_B = np.array([587,665,755,800,852,904,932,953,990,1024,1041,1064,1095,1120,1169,1225,1278,1398,1534], dtype=np.float64) / 1024.0
COLOR_GAIN = np.array([102, 109, 115, 122, 128, 134, 141, 147, 154], dtype=np.float64) / 128.0
SHADOW_OFFSETS = [30, 25, 0, 20, 15, 10, 5]  # Shadow -2, -1, 0, +1, +2, +3, +4 in EEPROM 0x1e160 groups

B30_PART_OFF = 0x00300060
B82_PART_OFF = 0x00820060
B30_BASE = 0x01101000
B82_BASE = 0x028EB000
EEPROM_BASE = 0x000C0060


def _s13(v):
    v &= 0x1FFF
    return v - 0x2000 if (v & 0x1000) else v


def _s10(v):
    v &= 0x3FF
    return v - 0x400 if (v & 0x200) else v


def _lz_decompress(buf, part_off):
    decomp_sz, comp_sz, _, _, _ = struct.unpack_from("<5I", buf, part_off)
    src = buf[part_off + 20 : part_off + 20 + comp_sz]
    out = bytearray(decomp_sz)
    i = 0
    o = 0
    n = len(src)
    while i < n:
        b0 = src[i]
        i += 1
        if b0 < 0x80:
            out[o : o + b0] = src[i : i + b0]
            i += b0
            o += b0
        else:
            b1 = src[i]
            i += 1
            dist = ((b0 & 0x7F) << 4) | (b1 >> 4)
            length = b1 & 0x0F
            start = o - dist
            if length <= dist:
                out[o : o + length] = out[start : start + length]
                o += length
            else:
                for k in range(length):
                    out[o] = out[start + k]
                    o += 1
    assert o == decomp_sz, (o, decomp_sz)
    return bytes(out)


def bezier(values, edges, x):
    k = np.clip(np.searchsorted(edges, x, side="right") - 1, 0, len(edges) - 2)
    t = (x - edges[k]) / (edges[k + 1] - edges[k])
    s = 1.0 - t
    return values[k * 3] * s**3 + 3 * values[k * 3 + 1] * t * s * s + 3 * values[k * 3 + 2] * t * t * s + values[k * 3 + 3] * t**3


def _bezier_extended(values, edges, x):
    """The last Bezier segment continued past the end of the table (t > 1)."""
    t = (x - edges[-2]) / (edges[-1] - edges[-2])
    s = 1.0 - t
    return values[-4] * s**3 + 3 * values[-3] * t * s * s + 3 * values[-2] * t * t * s + values[-1] * t**3


def decode(b):
    (scale,) = struct.unpack_from("<f", b, 16)
    v = np.frombuffer(b, "<f4", offset=20).astype(np.float64)
    base_len = 40 + 93 + 11 + 9 + 9 + 3 + 17**3 * 3
    tail = 20 + (base_len + SHADOW_FLOATS + GMOD_FLOATS + HUEGAIN_FLOATS) * 4
    hd, sd = struct.unpack_from("<II", b, tail)
    t1, t2 = struct.unpack_from("<ff", b, tail + 8)
    table = np.frombuffer(b, "<f4", offset=tail + 16, count=2 * hd * sd * 3).astype(np.float64).reshape(2, hd, sd, 3)
    assert len(b) == tail + 16 + 2 * hd * sd * 12
    v = v[:base_len + SHADOW_FLOATS + GMOD_FLOATS + HUEGAIN_FLOATS]
    gm = v[base_len + SHADOW_FLOATS:base_len + SHADOW_FLOATS + GMOD_FLOATS]
    hg = v[base_len + SHADOW_FLOATS + GMOD_FLOATS:]
    return dict(
        huegain=hg[1:1 + HUEGAIN_HUES * HUEGAIN_YS].reshape(HUEGAIN_HUES, HUEGAIN_YS) if hg[0] != 0.0 else None,
        hueres=hg[1 + HUEGAIN_HUES * HUEGAIN_YS:].reshape(3, HUEGAIN_HUES, len(HUERES_YK), len(HUERES_CK)) if hg[0] != 0.0 else None,
        gmod=None if not gm[:4].any() and not gm[4:9].any() and np.allclose(gm[9:], 256.0) else gm,
        hsm=dict(t1=float(t1), t2=float(t2), table1=table[0][None], table2=table[1][None]),
        scale=float(scale),
        tone=v[:40],
        curves=v[40:133].reshape(3, 31),
        edges=v[133:144],
        matrix=v[144:153].reshape(3, 3),
        color=v[153:162].reshape(3, 3),
        luma=v[162:165],
        cube=v[165:base_len].reshape(17, 17, 17, 3),
        shadows=v[base_len:base_len + SHADOW_FLOATS].reshape(2, SHADOW_ROWS, 40),
    )


# Firmware YCC pair (b82 0x335f780 forward / 0x335f880 inverse, set 1 = Rec.601) and the luma-dependent chroma gain block of the film core
# (per-simulation set number from b82 0x3366bd0, thresholds b82 0x3366c20, five line segments b82 0x3366748): G(Y) = (b_i + a_i * Y / 256) / 256 for
# Y = 0..255 of the tables31 output, i = number of thresholds <= Y; applied to Cb and Cr. The segments join at the thresholds (checked for all 14 sets).
YCC_FWD = np.array([[306, 601, 117], [-173, -339, 512], [512, -429, -83]], dtype=np.float64) / 1024.0
YCC_INV = np.linalg.inv(YCC_FWD)
GMOD_FLOATS = 14
HUEGAIN_HUES, HUEGAIN_YS = 36, 5          # chroma gain table over hue (10 deg cells) and Y (5 knots 0.075..0.8); one flag float in front
HUERES_YK = np.array([0.10, 0.25, 0.35, 0.45, 0.55, 0.70])                    # Y knots of the high-chroma correction
HUERES_CK = np.array([0.0, 0.08, 0.14, 0.20, 0.24, 0.27, 0.30, 0.34])         # chroma knots (after the gain) of the high-chroma correction
HUERES_FLOATS = 3 * HUEGAIN_HUES * len(HUERES_YK) * len(HUERES_CK)           # (dY, chroma ratio - 1, hue shift in degrees) over hue cell x Y knot x chroma knot
HUEGAIN_FLOATS = 1 + HUEGAIN_HUES * HUEGAIN_YS + HUERES_FLOATS
HUEGAIN_Y0, HUEGAIN_DY = 0.075, 0.18125


def _hue_cells(hue):
    hp = hue / 10.0 - 0.5
    i0f = np.floor(hp)
    return i0f.astype(int) % HUEGAIN_HUES, (i0f.astype(int) + 1) % HUEGAIN_HUES, hp - i0f


def _knot_pos(knots, v):
    p = np.clip(np.interp(v, knots, np.arange(len(knots))), 0.0, len(knots) - 1.0)
    k0 = np.minimum(np.floor(p).astype(int), len(knots) - 2)
    return k0, p - k0


def apply_hue_gain(out, table, resid=None):
    """Hue block after the 3D LUT (S6, Adobe-encoded Rec.601 YCC; the operation is silicon, both tables are measured). `table`: chroma gain, bilinear in
    (hue = atan2(-Cr, -Cb), Y), faded in over chroma 0.03..0.08. `resid`: correction of the gain's high-chroma behaviour, (dY, chroma ratio - 1, hue shift)
    trilinear in (hue cell, Y, chroma after the gain), same fade-in."""
    luma = out @ YCC_FWD[0]
    cb = out @ YCC_FWD[1]
    cr = out @ YCC_FWD[2]
    hue = np.degrees(np.arctan2(-cr, -cb)) % 360.0
    i0, i1, fh = _hue_cells(hue)
    yp = np.clip((luma - HUEGAIN_Y0) / HUEGAIN_DY, 0.0, HUEGAIN_YS - 1.0)
    j0 = np.minimum(np.floor(yp).astype(int), HUEGAIN_YS - 2)
    fy = yp - j0
    g = table[i0, j0] * (1 - fh) * (1 - fy) + table[i1, j0] * fh * (1 - fy) + table[i0, j0 + 1] * (1 - fh) * fy + table[i1, j0 + 1] * fh * fy
    chroma0 = np.hypot(cb, cr)
    w = np.clip((chroma0 - 0.03) / 0.05, 0.0, 1.0)
    g = 1.0 + w * (g - 1.0)
    res = luma[:, None] + g[:, None] * (out - luma[:, None])
    if resid is None:
        return res
    luma = res @ YCC_FWD[0]
    cb = res @ YCC_FWD[1]
    cr = res @ YCC_FWD[2]
    chroma = np.hypot(cb, cr)
    hue = np.degrees(np.arctan2(-cr, -cb)) % 360.0
    i0, i1, fh = _hue_cells(hue)
    j0, fy = _knot_pos(HUERES_YK, luma)
    k0, fc = _knot_pos(HUERES_CK, chroma)
    delta = np.zeros((3, len(luma)))
    for ih, wh in ((i0, 1 - fh), (i1, fh)):
        for jy, wy in ((j0, 1 - fy), (j0 + 1, fy)):
            for kc, wc in ((k0, 1 - fc), (k0 + 1, fc)):
                delta += resid[:, ih, jy, kc] * (wh * wy * wc)
    w = np.clip((chroma - 0.03) / 0.05, 0.0, 1.0)
    angle = np.radians(hue + delta[2] * w)
    chroma = chroma * (1.0 + delta[1] * w)
    return np.c_[luma + delta[0] * w, -chroma * np.cos(angle), -chroma * np.sin(angle)] @ YCC_INV.T


# Measured at a gap: the firmware's hue block (b82 0x2e5ff6c; parameter sets 8 for Nostalgic Neg., 7 for Bleach Bypass, 9 for Sepia) is packed into registers
# without arithmetic, so its operation is silicon and only the camera's render shows it. Nostalgic Neg.: chroma reduction in hue 228..330 (0.70 dark to 0.92
# bright); Bleach Bypass: chroma boost 1.06..1.10 in hue 270..335. Tables = camera / engine chroma ratio on the synthetic ring and chart (X100VI via X RAW STUDIO),
# cells hue x Y, applied after the LUT.
NOSTALGIC_HUE_GAIN = np.array([
    [1.0023, 1.0023, 1.0023, 1.0019, 1.0019],
    [0.9954, 0.9954, 0.9993, 1.0018, 1.0018],
    [0.9951, 0.9951, 0.9997, 1.0015, 1.0015],
    [1.0012, 1.0012, 1.0012, 1.0010, 1.0209],
    [1.0006, 1.0006, 1.0006, 1.0010, 1.0209],
    [1.0006, 1.0006, 1.0006, 1.0010, 1.0010],
    [1.0033, 1.0033, 1.0033, 1.0006, 1.0006],
    [1.0038, 1.0038, 1.0038, 1.0014, 1.0014],
    [1.0030, 1.0030, 1.0030, 1.0009, 1.0009],
    [1.0022, 1.0022, 1.0022, 1.0002, 1.0002],
    [0.9980, 0.9980, 0.9980, 0.9980, 1.0053],
    [1.0061, 1.0061, 1.0061, 0.9979, 1.0022],
    [0.9988, 0.9988, 0.9988, 0.9989, 1.0013],
    [0.9993, 0.9993, 0.9993, 0.9967, 1.0014],
    [1.0021, 1.0021, 0.9965, 0.9950, 0.9950],
    [0.9994, 0.9994, 0.9950, 0.9950, 0.9950],
    [0.9728, 0.9980, 0.9946, 0.9880, 0.9880],
    [0.9503, 0.9974, 0.9937, 0.9908, 0.9908],
    [0.9792, 0.9943, 0.9930, 0.9921, 0.9921],
    [0.9904, 0.9937, 0.9949, 0.9937, 0.9937],
    [0.9955, 0.9943, 0.9949, 0.9905, 0.9905],
    [0.9944, 0.9944, 0.9952, 0.9927, 0.9927],
    [0.9347, 0.9347, 0.9787, 0.9777, 0.9777],
    [0.8400, 0.8400, 0.8607, 0.9486, 0.9486],
    [0.7962, 0.7962, 0.8505, 0.9336, 0.9336],
    [0.7503, 0.7874, 0.8431, 0.9186, 0.9186],
    [0.7330, 0.7874, 0.8432, 0.9053, 0.9053],
    [0.7284, 0.7847, 0.8478, 0.8967, 0.8967],
    [0.7392, 0.7883, 0.8774, 0.9035, 0.9035],
    [0.7499, 0.7938, 0.8952, 0.9000, 0.9000],
    [0.7923, 0.7923, 0.8830, 0.9070, 0.9070],
    [0.7947, 0.7947, 0.8540, 0.9145, 0.9490],
    [0.8065, 0.8065, 0.8762, 0.9398, 0.9520],
    [0.9325, 0.9325, 0.9325, 1.0006, 1.0027],
    [1.0038, 1.0038, 1.0038, 1.0013, 1.0022],
    [1.0049, 1.0049, 1.0049, 1.0019, 0.9997],
], dtype=np.float64)

BLEACH_BYPASS_HUE_GAIN = np.array([
    [1.0104, 1.0104, 1.0104, 1.0084, 1.0120],
    [1.0105, 1.0105, 1.0105, 1.0091, 1.0103],
    [1.0058, 1.0058, 1.0085, 1.0116, 1.0111],
    [1.0082, 1.0082, 1.0103, 1.0109, 1.0115],
    [1.0049, 1.0049, 1.0062, 1.0073, 1.0101],
    [1.0061, 1.0061, 1.0055, 1.0064, 1.0118],
    [1.0107, 1.0107, 1.0091, 1.0078, 1.0127],
    [1.0183, 1.0183, 1.0130, 1.0090, 1.0119],
    [1.0130, 1.0130, 1.0130, 1.0074, 1.0104],
    [1.0128, 1.0128, 1.0128, 1.0043, 1.0065],
    [1.0117, 1.0117, 1.0117, 1.0020, 1.0054],
    [1.0117, 1.0117, 1.0117, 1.0015, 1.0065],
    [1.0131, 1.0131, 1.0048, 0.9981, 1.0078],
    [1.0068, 1.0068, 1.0001, 0.9967, 1.0052],
    [1.0018, 1.0018, 0.9975, 0.9935, 0.9935],
    [1.0008, 1.0008, 0.9942, 0.9905, 0.9905],
    [1.0005, 1.0005, 0.9913, 0.9908, 0.9908],
    [0.9988, 0.9988, 0.9909, 0.9883, 0.9883],
    [0.9944, 0.9944, 0.9911, 0.9828, 0.9828],
    [0.9940, 0.9940, 0.9911, 0.9877, 0.9877],
    [0.9936, 0.9936, 0.9920, 0.9897, 0.9897],
    [0.9910, 0.9910, 0.9926, 0.9879, 0.9879],
    [0.9876, 0.9876, 0.9925, 0.9865, 0.9760],
    [0.9870, 0.9870, 0.9874, 0.9869, 0.9760],
    [0.9900, 0.9900, 0.9921, 0.9833, 0.9833],
    [0.9895, 0.9895, 0.9935, 0.9825, 0.9825],
    [1.0019, 1.0019, 0.9976, 0.9869, 0.9869],
    [1.0319, 1.0319, 1.0143, 1.0223, 1.0223],
    [1.0466, 1.0466, 1.0564, 1.0827, 1.0827],
    [1.0977, 1.0632, 1.0723, 1.0912, 1.0912],
    [1.0977, 1.0921, 1.0912, 1.1033, 1.1033],
    [1.1087, 1.1087, 1.1005, 1.0974, 1.0974],
    [1.1047, 1.1047, 1.1041, 1.0933, 1.1083],
    [1.1039, 1.1039, 1.0983, 1.0783, 1.0807],
    [1.0560, 1.0560, 1.0560, 1.0459, 1.0373],
    [1.0200, 1.0200, 1.0200, 1.0281, 1.0307],
], dtype=np.float64)

HUE_GAIN_TABLES = {12: NOSTALGIC_HUE_GAIN, 11: BLEACH_BYPASS_HUE_GAIN, 13: np.ones((HUEGAIN_HUES, HUEGAIN_YS))}
# High-chroma correction of the two tables above (Nostalgic Neg., Bleach Bypass), measured on the camera's Adobe RGB renders of the probe, chart, ring and wide
# charts: x100vi_hue_resid.npz, arrays resid<film simulation number> of shape (3, hue cell, Y knot, chroma knot).
_RES = np.load(Path(__file__).with_name("x100vi_hue_resid.npz"))
assert np.array_equal(_RES["YK"], HUERES_YK) and np.array_equal(_RES["CK"], HUERES_CK)
HUE_RESID_TABLES = {fsim: _RES[f"resid{fsim}"].astype(np.float64) for fsim in HUE_GAIN_TABLES}


def apply_gmod(enc, gm):
    """Chroma gain of the film core: gm = [t1..t4, a1..a5, b1..b5] (thresholds on 0..255, slope Q8, intercept Q8)."""
    luma = enc @ YCC_FWD[0]
    y = np.clip(luma, 0.0, 1.0) * 255.0
    i = np.searchsorted(gm[:4], y, side="right")
    g = (gm[9:][i] + gm[4:9][i] * y / 256.0) / 256.0
    return luma[:, None] + g[:, None] * (enc - luma[:, None])


def _interp_c40(shadows, val):
    v = float(np.clip(val, -2.0, 4.0)) + 2.0
    idx0 = min(int(np.floor(v)), 5)
    frac = v - idx0
    return shadows[idx0] * (1.0 - frac) + shadows[idx0 + 1] * frac


def _resolve_tone(p, highlight=0.0, shadow=0.0, dr=100):
    rows = p["shadows"][1 if dr == 200 else 0]
    sh = _interp_c40(rows, shadow)
    if highlight == shadow:
        return sh
    hi = _interp_c40(rows, highlight)
    return np.r_[sh[:24], hi[24:]]


def hue_sat_table(hsm, cct):
    """The X-T5 Adobe Standard HueSatMap at the frame colour temperature (1/T interpolation between its illuminants)."""
    w = float(np.clip((1.0 / (cct or 5000) - 1.0 / hsm["t1"]) / (1.0 / hsm["t2"] - 1.0 / hsm["t1"]), 0.0, 1.0))
    return hsm["table1"] * (1.0 - w) + hsm["table2"] * w


def inverse_hue_sat(s, table, rounds=16):
    """The colorimetric scene a with hue_sat(a) = s (scene_front.hue_sat, the Adobe HueSatMap on linear ProPhoto)."""
    import scene_front as S

    s = np.maximum(np.asarray(s, np.float64), 0.0)
    h1, s1, v1 = S.rgb_to_hsv(s)
    a = s.copy()
    for _ in range(rounds):
        ha, sa, va = S.rgb_to_hsv(a)
        hf, sf, vf = S.rgb_to_hsv(S.hue_sat(a, table))
        dh = (hf - ha + 3.0) % 6.0 - 3.0
        ms = np.where(sa > 1e-4, sf / np.maximum(sa, 1e-4), 1.0)
        mv = np.where(va > 1e-6, vf / np.maximum(va, 1e-6), 1.0)
        ms = np.where(ms > 1e-3, ms, 1.0)
        a = S.hsv_to_rgb(h1 - dh, np.clip(s1 / ms, 0.0, 1.0), v1 / np.where(mv > 1e-3, mv, 1.0))
    return a


_CCE_YCC = np.array([
    [0.2988, 0.5869, 0.1143],
    [-0.1689, -0.3311, 0.5000],
    [0.5000, -0.4189, -0.0811],
], dtype=np.float64)
CCE_A_Y = 0.8452
CCE_C_CH = 0.3693
CCE_X_E = (159.0924 - 162.7657, 159.0924)   # (x_min, x_max) for Color Chrome Effect
CCE_X_B = (215.0538 - 50.2728, 215.0538)    # (x_min, x_max) for Color Chrome FX Blue
CCE_A = [-0.3167, -0.4593, 155.2244, 147.0820, 550.9013, 518.9044]   # green-sector effect (A): ycc weights of Cb and Cr, x range, hue ramp, measured
CCE_HUE_OFFSET = 1.5509                     # degrees, measured
_CCE_EDGES_E = np.array([47.0, 83.0, 156.0, 174.0, 286.0, 347.0], dtype=np.float64)
_CCE_VECS_E = np.array([[-0.4909, 0.1811], [-0.8408, 0.5362], [-1.0484, 0.1238], [0.0000, 0.0000], [0.2013, 0.4390], [-0.3056, 0.7095]], dtype=np.float64)
_CCE_EDGES_B = np.array([47.0, 83.0, 168.0, 189.0, 260.0, 295.0, 355.0], dtype=np.float64)
_CCE_VECS_B = np.array([[0.0000, 0.0000], [0.0000, 0.0000], [0.0000, 0.0000], [1.3373, -0.2973], [0.6737, 0.0068], [0.2588, -0.0512], [0.0000, 0.0000]], dtype=np.float64)


# REALA ACE (firmware mode 0xd, msg+0x130 = 13) runs the same module with its own parameter sets (b82 0x336b278) and the
# "both" vector row at every level, so "off" is a baseline. Plateau / 256 per level off, weak, strong. A and B share their
# plateaus; the R curve (1/8 off) sits on the three anchors that carry an effect vector (47, 83, 156 deg: orange to green),
# where the REALA ACE sweeps (X100VI, nine states, hue ring chart) show the weaker baseline.
MODE_D_A = (96 / 256, 176 / 256, 1.0)
MODE_D_R = (32 / 256, 144 / 256, 1.0)
MODE_D_B = (96 / 256, 176 / 256, 1.0)
_MODE_D_R_ZONES = (0, 1, 2)


def _eval_cce_sector(edges, vecs, ycc, x, h, x_min, x_max, scale=None):
    idx0 = np.searchsorted(edges, h, side="right") - 1
    idx0 = np.where(idx0 < 0, len(edges) - 1, idx0)
    idx1 = (idx0 + 1) % len(edges)
    span = (edges[idx1] - edges[idx0]) % 360.0
    t = ((h - edges[idx0]) % 360.0) / span
    f = np.clip((x_max - x) / (x_max - x_min), 0.0, 1.0)
    cb, cr = ycc[..., 1], ycc[..., 2]
    s = np.ones(len(edges)) if scale is None else np.asarray(scale, dtype=np.float64)
    return ((1.0 - t) * s[idx0] * (vecs[idx0, 0] * cb + vecs[idx0, 1] * cr) + t * s[idx1] * (vecs[idx1, 0] * cb + vecs[idx1, 1] * cr)) * f


def apply_color_chrome(encoded, color_chrome=0, color_chrome_blue=0, mode_d=False):
    """Apply Fujifilm Color Chrome Effect & Color Chrome FX Blue on post-3D-LUT sRGB-encoded RGB (0..1)."""
    ef = int(np.clip(round(color_chrome), 0, 2))
    bf = int(np.clip(round(color_chrome_blue), 0, 2))
    if ef == 0 and bf == 0 and not mode_d:
        return encoded
    rgb = np.asarray(encoded, dtype=np.float64)
    ycc = (rgb @ _CCE_YCC.T) * 255.0
    mn = rgb.min(axis=-1) * 255.0
    c = np.hypot(ycc[..., 1], ycc[..., 2])
    x = CCE_A_Y * ycc[..., 0] + (1.0 - CCE_A_Y) * mn - CCE_C_CH * c
    h = (np.degrees(np.arctan2(-ycc[..., 1], ycc[..., 2])) + 720.0 - CCE_HUE_OFFSET) % 360.0
    if mode_d:
        zone = np.full(len(_CCE_EDGES_E), MODE_D_A[ef])
        for k in _MODE_D_R_ZONES:
            zone[k] = MODE_D_R[ef]
        qe = _eval_cce_sector(_CCE_EDGES_E, _CCE_VECS_E, ycc, x, h, CCE_X_E[0], CCE_X_E[1], zone)
        qb = _eval_cce_sector(_CCE_EDGES_B, _CCE_VECS_B, ycc, x, h, CCE_X_B[0], CCE_X_B[1])
        fe = np.clip((CCE_A[2] - x) / CCE_A[3], 0.0, 1.0)
        qe_a = (CCE_A[0] * ycc[..., 1] + CCE_A[1] * ycc[..., 2]) * fe * np.clip((CCE_A[4] - h) / CCE_A[5], 0.0, 1.0)
        in_a = (h >= 134.0) & (h <= 194.2)
        we = np.where(h < 134.0, 1.0, np.where(h < 273.9, 0.0, np.clip((h - 273.9) / (307.6 - 273.9), 0.0, 1.0)))
        wb = np.where(h < 134.0, 0.0, np.where(h <= 278.5, 1.0, np.clip((318.9 - h) / (318.9 - 278.5), 0.0, 1.0)))
        q = np.where(in_a, MODE_D_A[ef] * qe_a, we * qe) + MODE_D_B[bf] * wb * qb
    else:
        qe = _eval_cce_sector(_CCE_EDGES_E, _CCE_VECS_E, ycc, x, h, CCE_X_E[0], CCE_X_E[1]) if ef > 0 else 0.0
        qb = _eval_cce_sector(_CCE_EDGES_B, _CCE_VECS_B, ycc, x, h, CCE_X_B[0], CCE_X_B[1]) if bf > 0 else 0.0
        if bf == 0:
            q = (ef * 0.5) * qe
        elif ef == 0:
            q = (bf * 0.5) * qb
        else:
            fe = np.clip((CCE_A[2] - x) / CCE_A[3], 0.0, 1.0)
            qe_a = (CCE_A[0] * ycc[..., 1] + CCE_A[1] * ycc[..., 2]) * fe * np.clip((CCE_A[4] - h) / CCE_A[5], 0.0, 1.0)
            in_a = (h >= 134.0) & (h <= 194.2)
            we = np.where(h < 134.0, 1.0, np.where(h < 273.9, 0.0, np.clip((h - 273.9) / (307.6 - 273.9), 0.0, 1.0)))
            wb = np.where(h < 134.0, 0.0, np.where(h <= 278.5, 1.0, np.clip((318.9 - h) / (318.9 - 278.5), 0.0, 1.0)))
            q = (ef * 0.5) * np.where(in_a, qe_a, we * qe) + (bf * 0.5) * wb * qb
    return np.clip(rgb - np.maximum(q, 0.0)[..., None] / 255.0, 0.0, 1.0)


def render(p, pp, cct=None, highlight=0.0, shadow=0.0, color_step=0, wb_r=0, wb_b=0, color_chrome=0, color_chrome_blue=0, dr=100):
    import looks as L

    pp_to_srgb = np.linalg.inv(L.SRGB_TO_XYZ) @ L.cat(L.D50, L.D65) @ L.PROPHOTO_TO_XYZ
    pp = np.asarray(pp, np.float64)
    if "hsm" in p:
        pp = inverse_hue_sat(pp, hue_sat_table(p["hsm"], cct))
    # Colours outside sRGB keep their negative channels through the linear front matrix (the camera's front stage is linear); the result is clipped at 0.
    lin = (pp @ pp_to_srgb.T - FRONT_PEDESTAL) * FRONT_GAIN
    lin = lin * np.array([WB_SHIFT_R[int(np.clip(round(wb_r), -9, 9)) + 9], 1.0, WB_SHIFT_B[int(np.clip(round(wb_b), -9, 9)) + 9]])
    rgb = np.maximum(lin @ FRONT_M.T, 0.0)
    rgb = rgb * p["scale"]
    # Hardware ISP 1:2:1 luma approximation (R + 2G + B) / 4 for the pre-matrix tone40 stage.
    lum = np.maximum(0.25 * rgb[:, 0] + 0.5 * rgb[:, 1] + 0.25 * rgb[:, 2], 0.0)
    x = np.clip(lum * 16384.0, 0.0, 16384.0)
    tone = _resolve_tone(p, highlight, shadow, dr)
    output = x / 16384.0 * bezier(tone, TONE_EDGES, x)
    gain = np.divide(output, lum, out=np.ones_like(lum), where=lum > 0)
    if dr == 200:
        # Past the end of the curve table the gain continues from its end value and end slope (d ln gain / d ln lum at lum 1).
        gain = np.where(lum > 1.0, tone[-1] * np.power(np.maximum(lum, 1e-9), 12.0 * (tone[-1] - tone[-2]) / tone[-1]), gain)
    # The film matrix output is clipped at 0 (the camera's Acros / Monochrome filters, Eterna and Bleach Bypass show it on saturated primaries; luma-preserving pulling is wrong there).
    mixed = np.maximum((rgb * gain[:, None]) @ p["matrix"].T, 0.0)
    encoded = np.stack([bezier(p["curves"][i], p["edges"], np.clip(mixed[:, i] * 16384.0, 0.0, 16384.0)) for i in range(3)], axis=1)
    color = p["luma"][None, :] + COLOR_GAIN[int(np.clip(round(color_step), -4, 4)) + 4] * (p["color"] - p["luma"][None, :])
    ycc = (encoded @ color.T) @ _FW_YCC.T
    ycc[:, 1] = np.clip(ycc[:, 1], -0.5, 0.5)
    ycc[:, 2] = np.clip(ycc[:, 2], -0.5, 0.5)
    code = ycc @ _FW_YCC_INV.T
    if p.get("gmod") is not None:
        code = apply_gmod(code, p["gmod"])
    code = np.clip(code, 0.0, LUT_CODE_MAX) * 4095.0
    base = np.minimum(np.floor(code / 256.0).astype(int), 15)
    frac = (code - base * 256.0) / np.where(base == 15, 255.0, 256.0)
    out = np.zeros_like(code)
    for z in range(2):
        for y in range(2):
            for x2 in range(2):
                weight = np.prod(np.where(np.array([x2, y, z]), frac, 1.0 - frac), axis=1)
                out += weight[:, None] * p["cube"][base[:, 2] + z, base[:, 1] + y, base[:, 0] + x2]
    if p.get("huegain") is not None:
        out = apply_hue_gain(out, p["huegain"], p.get("hueres"))
    out = apply_color_chrome(out, color_chrome, color_chrome_blue, p.get("mode_d", False))
    # The film core's encoded output is Adobe RGB (gamma 563/256): converted straight to Display P3.
    lin = np.clip(out, 0.0, 1.0) ** ADOBE_GAMMA
    return np.clip(lin @ ADOBE_TO_P3.T, 0.0, 1.0)


def _adobe_standard_hue_sat():
    """The X-T5 Adobe Standard HueSatMap block of the look files (see the layout above)."""
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
    import refs

    sys.path.insert(0, str(refs.DCP_RENDER))
    import dcp_render as D

    d = D.read_dcp(refs.ADOBE_CAMERA_RAW / "CameraProfiles/Adobe Standard/Fujifilm X-T5 Adobe Standard.dcp")
    hd, sd, vd = (int(x) for x in d[50937])
    assert vd == 1
    t1, t2 = D.ILLUM_T[int(d[50778][0])], D.ILLUM_T[int(d[50779][0])]
    tables = [d[k].astype("<f4").reshape(hd, sd, 3) for k in (50938, 50939)]
    return struct.pack("<IIff", hd, sd, t1, t2) + tables[0].tobytes() + tables[1].tobytes()


# Sepia: the plateau of the Cb / Cr offset is EEPROM 0xaf8d / 0xaf8f (10-bit signed, -52 / +51) over 1024, which the camera's own render reproduces to 0.5 %
# (measured on the synthetic chart, X100VI via X RAW STUDIO, Cb = -0.0511, Cr = +0.0498 on the Adobe-encoded 601 YCC of the output). How the offset
# rises from 0 at black to the plateau is not in the firmware (knee value EEPROM 0x1ff14 / 4095 = 0.221 only); SEPIA_RISE_S is that rise measured
# in units of the plateau, constant above SEPIA_RISE_Y[-1]. From EEPROM 0x1ff16 (y_hi, 12-bit) the offset tapers to 0 at white.
SEPIA_RISE_Y = np.array([0.0, 0.06450, 0.12916, 0.17452, 0.20307, 0.24065, 1.0])
SEPIA_RISE_S = np.array([0.0, 0.224, 0.583, 0.798, 0.907, 1.0, 1.0])


def _s10(v):
    v &= 0x3FF
    return v - 0x400 if v >= 0x200 else v


def _build_sepia_cube(rgb_to_ycc, ycc_to_rgb, y_hi, cb_off, cr_off):
    """Bake the Sepia tint into a 17^3 cube (Cb/Cr offsets in 1/1024 units)."""
    nodes = np.array([min(i * 256, 4095) / 4095.0 for i in range(17)], dtype=np.float64)
    grid_b, grid_g, grid_r = np.meshgrid(nodes, nodes, nodes, indexing="ij")
    rgb = np.stack([grid_r, grid_g, grid_b], axis=-1)
    ycc = rgb @ rgb_to_ycc.T
    y = ycc[..., 0]
    y_hi_n = y_hi / 4095.0
    w_hi = np.where(y > y_hi_n, (1.0 - y) / max(1.0 - y_hi_n, 1e-9), 1.0) * np.interp(y, SEPIA_RISE_Y, SEPIA_RISE_S)
    ycc[..., 1] += w_hi * cb_off / 1024.0
    ycc[..., 2] += w_hi * cr_off / 1024.0
    return np.clip(ycc @ ycc_to_rgb.T, 0.0, 1.0)


def pack_from_firmware(fw_path, out_dir):
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    raw = np.fromfile(fw_path, dtype=np.uint8)
    inv = np.bitwise_xor(raw[532:], 0xFF).tobytes()
    b30 = _lz_decompress(inv, B30_PART_OFF)
    b82 = _lz_decompress(inv, B82_PART_OFF)

    t31_db = {}
    for i in range(34):
        off = 0x00AC7CE8 + i * 0x4C
        tid = struct.unpack_from("<I", b30, off)[0]
        ctrl = np.array(list(b30[off + 4 : off + 14]), dtype=np.float64)
        vals = np.array(struct.unpack_from("<31H", b30, off + 14), dtype=np.float64) / 16384.0
        t31_db[tid] = (ctrl, vals)

    c40_b2 = {}
    for i in range(206):
        off = 0x00AD0878 + i * 0x54
        cid = struct.unpack_from("<I", b30, off)[0]
        c40_b2[cid] = np.array(struct.unpack_from("<40H", b30, off + 4), dtype=np.float64) / 512.0

    c40_dr200 = {}
    n200 = struct.unpack_from("<I", b30, 0x1BDAE78 - B30_BASE)[0]
    for i in range(n200):
        off = 0x1BCC718 - B30_BASE + i * 0x54
        c40_dr200[struct.unpack_from("<I", b30, off)[0]] = np.array(struct.unpack_from("<40H", b30, off + 4), dtype=np.float64) / 512.0

    luts = []
    for i in range(4):
        off = 0x00AD9EA0 + i * 0xB640
        cube = np.frombuffer(b30, "<u2", 18 * 18 * 18 * 4, off).reshape(18, 18, 18, 4)
        assert np.all(cube[17] == 0) and np.all(cube[:, 17] == 0) and np.all(cube[:, :, 17] == 0)
        luts.append(cube[:17, :17, :17, :3].astype(np.float64) / 4095.0)

    rgb_to_ycc = np.array(struct.unpack_from("<9h", b30, 0x00B08210), dtype=np.float64).reshape(3, 3) / 1024.0
    ycc_to_rgb = np.linalg.inv(rgb_to_ycc)

    _, y_hi = struct.unpack_from("<2H", inv, EEPROM_BASE + 0x1FF14)
    cb_off = _s10(struct.unpack_from("<H", inv, EEPROM_BASE + 0xAF8D)[0])
    cr_off = _s10(struct.unpack_from("<H", inv, EEPROM_BASE + 0xAF8F)[0])
    sepia_cube = _build_sepia_cube(rgb_to_ycc, ycc_to_rgb, y_hi, cb_off, cr_off)

    mat_idx_eids = struct.unpack_from("<22I", b82, 0x0336D4D0 - B82_BASE)
    t31_eids = struct.unpack_from("<22I", b82, 0x0336C250 - B82_BASE)
    lut_eids = struct.unpack_from("<20I", b82, 0x0378D748 - B82_BASE)
    ch_eids = struct.unpack_from("<20I", b82, 0x033645D8 - B82_BASE)
    bw_ch_eids = struct.unpack_from("<4I", b82, 0x03364628 - B82_BASE)

    def extract_sim(fsim, flt, m, c40_id):
        mat_id = inv[EEPROM_BASE + mat_idx_eids[m]]
        if mat_id == 0:
            mat = np.eye(3)
        else:
            meids = struct.unpack_from("<9I", b82, 0x0336D070 - B82_BASE + (mat_id - 1) * 0x24)
            mat = np.array([_s13(struct.unpack_from("<H", inv, EEPROM_BASE + x)[0]) for x in meids], dtype=np.float64).reshape(3, 3) / 1024.0

        t_eid = t31_eids[m]
        if m in (7, 9, 10):
            tids = (inv[EEPROM_BASE + t_eid], inv[EEPROM_BASE + t_eid + 1], inv[EEPROM_BASE + t_eid + 2])
        else:
            tid = inv[EEPROM_BASE + t_eid]
            tids = (tid, tid, tid)
        ctrl = t31_db[tids[0]][0]
        edges = np.r_[0.0, np.cumsum(2.0 ** (ctrl + 5.0))]
        assert edges[-1] == 16384.0
        curves = np.stack([t31_db[t][1] for t in tids], axis=0)

        ch_eid = bw_ch_eids[flt] if fsim == 2 else ch_eids[fsim]
        ch_id = inv[EEPROM_BASE + ch_eid]
        assert ch_id >= 1
        ce = struct.unpack_from("<4I", b82, 0x03364638 - B82_BASE + (ch_id - 1) * 16)
        raw4 = [struct.unpack_from("<H", inv, EEPROM_BASE + x)[0] for x in ce]
        c = np.array([_s13(raw4[0]), _s13(raw4[2]), _s13(raw4[1]), _s13(raw4[3])], dtype=np.float64) / 1024.0
        op = np.array([[1.0, 0.0, 0.0], [0.0, c[2], c[3]], [0.0, c[1], c[0]]])
        color = ycc_to_rgb @ op @ rgb_to_ycc
        if fsim == 3:
            cube = sepia_cube
        else:
            lut_idx = 0 if fsim in (2, 16, 17, 18, 19) else inv[EEPROM_BASE + lut_eids[fsim]]
            cube = luts[lut_idx]
        def shadow_rows(table, c_id, base_id):
            if c_id in (base_id, base_id + 40, base_id + 80, base_id + 120):
                return np.stack([table[c_id + off] for off in SHADOW_OFFSETS], axis=0)
            return np.stack([table[c_id] * (table[base_id + off] / table[base_id]) for off in SHADOW_OFFSETS], axis=0)

        shadows = np.concatenate([shadow_rows(c40_b2, c40_id, 3), shadow_rows(c40_dr200, c40_id + 1, 4)], axis=0)
        set_ids = struct.unpack_from("<20I", b82, 0x3366BD0 - B82_BASE)
        gset = 0 if fsim == 2 else int(inv[EEPROM_BASE + set_ids[fsim]])
        gmod = None
        if gset:
            knots = [inv[EEPROM_BASE + i] for i in struct.unpack_from("<4I", b82, 0x3366C20 - B82_BASE + (gset - 1) * 16)]
            raw = [struct.unpack_from("<H", inv, EEPROM_BASE + i)[0] for i in struct.unpack_from("<10I", b82, 0x3366748 - B82_BASE + (gset - 1) * 0x28)]
            slope = [((v & 0x7FF) ^ 0x400) - 0x400 for v in raw[:5]]
            gmod = np.array(knots + slope + raw[5:], dtype=np.float64)
        return dict(huegain=HUE_GAIN_TABLES.get(fsim), gmod=gmod, tone=c40_b2[c40_id], shadows=shadows, curves=curves, edges=edges, matrix=mat, color=color, luma=rgb_to_ycc[0], cube=cube)

    x100vi_scale = INPUT_SCALE

    hsm_block = _adobe_standard_hue_sat()
    for name, fsim, flt, m, c40_id in X100VI_SIMS:
        p = extract_sim(fsim, flt, m, c40_id)
        gmod = p["gmod"] if p["gmod"] is not None else np.r_[np.zeros(9), np.full(5, 256.0)]
        hug = np.r_[1.0, p["huegain"].ravel(), HUE_RESID_TABLES[fsim].ravel()] if p["huegain"] is not None else np.r_[0.0, np.ones(HUEGAIN_HUES * HUEGAIN_YS), np.zeros(HUERES_FLOATS)]
        values = np.r_[p["tone"], p["curves"].ravel(), p["edges"], p["matrix"].ravel(), p["color"].ravel(), p["luma"], p["cube"].ravel(), p["shadows"].ravel(), gmod, hug]
        assert np.isfinite(values).all()
        blob = b"MCOLLOOK" + struct.pack("<IIf", 1, 13, x100vi_scale) + values.astype("<f4").tobytes() + hsm_block
        (out / f"x100vi_{name}.bin").write_bytes(blob)
    print(f"Wrote {len(X100VI_SIMS)} x100vi_*.bin looks to {out}")


if __name__ == "__main__":
    pack_from_firmware(sys.argv[1], sys.argv[2])
