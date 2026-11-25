"""Hasselblad HNCS (Phocus 4.1.1 LUTTable100MP3 + HDR FilmCurve + presets) reference engine.

Per 64^3 node h (master BasicTone input: Display P3 encoded with ISP IPE gamma):
  inverse IPE gamma -> scene-linear P3 -> x K -> XYZ D50 -> HasselbladRGB -> BT.601 YCbCr
  -> Phocus 2D colour map at CCT -> HDR FilmCurve (normalized [0..49.261x]) -> CGammaFilter CIE L*
  -> preset tone/gradation & SelectiveColor saturation (Nature / Saturated / Extra contrast / Lighten)
  -> inverse L* -> HighlightStrength(50, 50) -> Display P3 linear -> C1 highlight shoulder & P3 gamut protection
  -> HLG OETF per channel (HDRTransform master dark rendition).
"""
import json
import math
import struct
import sys
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import refs  # noqa: E402

import numpy as np

HERE = Path(__file__).resolve().parent
ASSETS = refs.PHOTONCAMERA / "app/src/main/assets/hncs"
DATA = HERE / "data"
N = 64

# ---- HLG / sRGB (X9U trans.cl) ----
A, B, C = 0.17883277, 0.28466892, 0.55991073
L2020 = np.array([0.2627, 0.678, 0.0593])
W601 = np.array([0.299, 0.587, 0.114])


def hlg_inv_oetf(x):
    x = np.clip(x, 0, 1)
    return np.where(x <= 0.5, x * x / 3.0, (np.exp((x - C) / A) + B) / 12.0)


def hlg_oetf(e):
    e = np.clip(e, 0, None)
    return np.where(e <= 1 / 12, np.sqrt(3 * e), A * np.log(np.maximum(12 * e - B, 1e-12)) + C)


def ootf_inv(d):
    y = np.maximum((d * L2020).sum(-1, keepdims=True), 1e-12)
    return d * y ** (-1 / 6)


def srgb_enc(x):
    x = np.clip(x, 0, None)
    return np.where(x < 0.0030412825601275209, x * 12.92,
                    1.0550107189475866 * x ** (1 / 2.4) - 0.0550107189475866)


# ---- Colorimetry (Phocus 4.1.1 HasselbladRGB + BT.601 YCbCr) ----
P3_TO_XYZ = np.array([[0.4865709, 0.2656677, 0.1982173],
                      [0.2289746, 0.6917385, 0.0792869],
                      [0.0000000, 0.0451134, 1.0439444]], dtype=np.float64)
XYZ_TO_P3 = np.linalg.inv(P3_TO_XYZ)
P3_LUMA = P3_TO_XYZ[1]
BRADFORD = np.array([[0.8951, 0.2664, -0.1614],
                     [-0.7502, 1.7135, 0.0367],
                     [0.0389, -0.0685, 1.0296]], dtype=np.float64)
D65 = np.array([0.95047, 1.0, 1.08883], dtype=np.float64)
D50 = np.array([0.96422, 1.0, 0.82521], dtype=np.float64)


def cat(src, dst):
    s, d = BRADFORD @ src, BRADFORD @ dst
    return np.linalg.inv(BRADFORD) @ np.diag(d / s) @ BRADFORD


def white_xyz(t):
    """Scene white for a CCT: Planckian locus below 4000 K, CIE daylight locus from 4000 K."""
    if t < 4000:
        x = -0.2661239e9 / t**3 - 0.2343589e6 / t**2 + 0.8776956e3 / t + 0.179910
        y = (-1.1063814 * x**3 - 1.34811020 * x**2 + 2.18555832 * x - 0.20219683 if t < 2222 else
             -0.9549476 * x**3 - 1.37418593 * x**2 + 2.09137015 * x - 0.16748867)
    else:
        x = (-4.6070e9 / t**3 + 2.9678e6 / t**2 + 0.09911e3 / t + 0.244063 if t <= 7000 else
             -2.0064e9 / t**3 + 1.9018e6 / t**2 + 0.24748e3 / t + 0.237040)
        y = -3.0 * x * x + 2.87 * x - 0.275
    return np.array([x / y, 1.0, (1 - x - y) / y], dtype=np.float64)


XYZ_D50_TO_HASS = np.array([
    [1.768049, -0.490119, -0.260191],
    [-0.805970, 1.750069, 0.032788],
    [0.034066, -0.093962, 1.286342],
], dtype=np.float64)
HASS_TO_XYZ_D50 = np.linalg.inv(XYZ_D50_TO_HASS)

_KR, _KG, _KB = 0.2989, 0.5866, 0.1145
RGB_TO_YCC = np.array([
    [_KR, _KG, _KB],
    [-_KR / (2.0 * (1.0 - _KB)), -_KG / (2.0 * (1.0 - _KB)), 0.5],
    [0.5, -_KG / (2.0 * (1.0 - _KR)), -_KB / (2.0 * (1.0 - _KR))],
], dtype=np.float64)
YCC_TO_RGB = np.linalg.inv(RGB_TO_YCC)

P3_TO_HASS = XYZ_D50_TO_HASS @ cat(D65, D50) @ P3_TO_XYZ
HASS_TO_P3 = XYZ_TO_P3 @ cat(D50, D65) @ HASS_TO_XYZ_D50

# ---- HDR & film-curve constants ----
HDR_MAX_GAIN = 10000.0 / 203.0
GAMMA_HASS = 563.0 / 256.0
HDR_RGB_LIMIT = HDR_MAX_GAIN ** (1.0 / GAMMA_HASS)

FLAG_COMPANDING_GAMMA = 1 << 0
FLAG_IS_REPRODUCTION = 1 << 1
FLAG_HIGHLIGHT_STRENGTH = 1 << 2
FLAG_HAS_GRADATION = 1 << 3


def fwd_lstar(x):
    x = np.asarray(x, dtype=np.float64)
    return np.where(x <= (216.0 / 24389.0), x * (24389.0 / 2700.0), 1.16 * np.cbrt(np.maximum(x, 0.0)) - 0.16)


def inv_lstar(t):
    t = np.asarray(t, dtype=np.float64)
    return np.where(t <= 0.08, t * (2700.0 / 24389.0), ((t + 0.16) / 1.16) ** 3.0)


def eval_1d(tbl, x):
    p = np.clip(x, 0.0, 1.0) * (tbl.size - 1)
    i0 = np.minimum(np.floor(p).astype(np.int64), tbl.size - 2)
    f = p - i0
    return tbl[i0] * (1.0 - f) + tbl[i0 + 1] * f


def build_fc0_perceptual():
    """Old Standard (film curve type 0) perceptual table normalized to [0, 1]."""
    idx = np.arange(65536, dtype=np.float64)
    v = ((idx / 65536.0) ** (1.0 / 4.1)) * 65536.0
    lo = (v * v * (0.96 / 32768.0) - 0.96 * v + v) / 65536.0
    u = 65536.0 - v
    hi = (-u * u * (0.96 / 32768.0) + 0.96 * u + v) / 65536.0
    inner = np.where(v < 32768.0, lo, hi)
    return np.clip(np.floor((inner ** (1.0 / 0.76)) * 65536.0), 0.0, 65535.0) / 65535.0


def build_hdr_filmcurve(film, companding_gamma=False, is_reproduction=False):
    """HDR film curve indexed by linear / HDR_MAX_GAIN in [0..1], returning linear / HDR_MAX_GAIN."""
    if is_reproduction:
        return np.arange(65536, dtype=np.float64) / 65535.0
    base_lin = (film ** GAMMA_HASS) if companding_gamma else inv_lstar(film)
    n = 65535.0
    x_stop = n / math.sqrt(2.0)
    curve = np.zeros(65536, dtype=np.float64)
    join_i = 0
    m_join, b_join = 1.0, 0.0
    for i in range(65536):
        pos = i * HDR_MAX_GAIN
        if pos >= 65535.0:
            break
        lo = int(pos)
        hi = min(lo + 1, 65535)
        f = pos - lo
        curve[i] = base_lin[lo] * (1.0 - f) + base_lin[hi] * f
        join_i = i
        if pos >= 20000.0 and lo >= 20:
            r8 = lo - 20
            m = (base_lin[lo] - base_lin[r8]) / 20.0
            b = base_lin[r8] - m * r8
            if m * x_stop + b < 1.0:
                m_join, b_join = m, b
                break
    for i in range(join_i + 1, 65536):
        curve[i] = m_join * (i * HDR_MAX_GAIN) + b_join
    x1 = max(10, join_i)
    y1 = curve[x1]
    m1 = (curve[x1 + 10] - curve[x1 - 10]) / 20.0
    x2 = n / HDR_MAX_GAIN
    if x2 > x1 + 2:
        dx = x2 - x1
        yt = math.sqrt(2.0)
        a = (yt - y1 - m1 * dx) / (dx * dx)
        b = m1 - 2.0 * a * x1
        c = y1 - a * x1 * x1 - b * x1
        m_end = 2.0 * a * x2 + b
        idx = np.arange(x1, 65536, dtype=np.float64)
        curve[x1:] = np.where(idx <= x2, a * idx * idx + b * idx + c, yt + m_end * (idx - x2))
    return curve / HDR_MAX_GAIN


# ---- HSL / Gradation / HighlightStrength / P3 gamut ----
def _rgb2hsl(rgb):
    r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    mn = np.minimum(np.minimum(r, g), b)
    mx = np.maximum(np.maximum(r, g), b)
    l = 0.5 * (mx + mn)
    d = mx - mn
    achrom = (d <= 1e-12) | (l <= 1e-12) | (l >= 1.0 - 1e-12)
    sd = np.where(achrom, 1.0, d)
    sl = np.where(achrom, 0.5, l)
    s_raw = np.where(sl <= 0.5, 0.5 * sd / sl, 0.5 * sd / (1.0 - sl))
    s = np.where(achrom, 0.0, np.clip(s_raw, 0.0, 1.0))
    h = np.zeros_like(l)
    mr = (~achrom) & (mx == r)
    mg = (~achrom) & (mx != r) & (mx == g)
    mb = (~achrom) & (mx != r) & (mx != g)
    h = np.where(mr, 60.0 * (((g - b) / sd) % 6.0), h)
    h = np.where(mg, 60.0 * ((b - r) / sd + 2.0), h)
    h = np.where(mb, 60.0 * ((r - g) / sd + 4.0), h)
    return np.stack([h % 360.0, s, l], -1)


def _hsl2rgb(hsl):
    h, s, l = hsl[..., 0], hsl[..., 1], hsl[..., 2]
    q = np.where(l <= 0.5, l * (1.0 + s), l + s - l * s)
    p = 2.0 * l - q
    hk = (h / 360.0) % 1.0

    def ch(t):
        t = t % 1.0
        return np.where(t < 1.0 / 6.0, p + (q - p) * 6.0 * t,
               np.where(t < 0.5, q,
               np.where(t < 2.0 / 3.0, p + (q - p) * 6.0 * (2.0 / 3.0 - t), p)))

    out = np.stack([ch(hk + 1.0 / 3.0), ch(hk), ch(hk - 1.0 / 3.0)], -1)
    return np.where((s <= 1e-12)[..., None], l[..., None], out)


def recover_hue(base_rgb, curved_rgb):
    hb = _rgb2hsl(base_rgb)
    hc = _rgb2hsl(curved_rgb)
    return _hsl2rgb(np.stack([hb[..., 0], hc[..., 1], hc[..., 2]], -1))


def contrast_s6(x):
    return np.where(x < 0.5, 2.0 * x * x - x,
           np.where(x < 0.9267767, 3.0 * x - 2.0 * x * x - 1.0,
           np.where(x < 1.103553, 2.0 * x * x - (3.0 + math.sqrt(2.0)) * x + 2.43566, 0.0)))


def apply_highlight_strength(rgb_hdr_norm, strength=50.0, desat=50.0):
    x1, x2 = 0.0083466849774929425, 0.0203
    y2 = (2.0 ** 0.6) * x2
    p_a = (y2 - x1 - 0.01195331502250706) / 0.00014288174002729295
    p_b = 1.0 - 2.0 * p_a * x1
    p_c = x1 - p_a * x1 * x1 - p_b * x1
    c0 = 0.020284740745262004
    xmm4 = p_a * c0 * c0 + p_b * c0 + p_c
    l_a = (y2 - xmm4) / 1.5259254737998162e-05
    l_b = xmm4 - l_a * c0
    ph_a = ((1.0 - y2) - 0.9797 * l_a) / 0.95981209
    ph_b = l_a - 2.0 * ph_a * x2
    ph_c = y2 - ph_a * x2 * x2 - ph_b * x2
    sb = strength * (strength * 4.5e-5 + 0.01)
    asf = 1.0 - sb
    sat_b = desat / 100.0
    x = np.clip(rgb_hdr_norm, 0.0, 1.0)
    res_p = p_a * x * x + p_b * x + p_c
    res_hi = (ph_a * x * x + ph_b * x + ph_c) if sb > 0.0 else (l_a * x + l_b)
    rgb_hs = np.clip(np.where(x < x1, x, np.where(x < x2, res_p, res_hi)), 0.0, 1.0)
    hi = _rgb2hsl(x)
    ho = _rgb2hsl(rgb_hs)
    a = ho[..., 1] - hi[..., 1]
    b = hi[..., 1] - 0.5 * a * asf
    s_new = np.clip(a * sat_b + b, 0.0, 1.0)
    l_new = (1.0 - sb) * hi[..., 2] + sb * ho[..., 2]
    return _hsl2rgb(np.stack([hi[..., 0], s_new, l_new], -1))


def shoulder_c1(y, yk=0.50):
    y = np.maximum(y, 0.0)
    u = np.maximum(y - yk, 0.0)
    r = 1.0 - yk
    return np.where(y <= yk, y, yk + r * (u / (u + r)))


HNCS_HDR_PEAK = 10.23


def hlg_oetf(e):
    e = np.clip(e, 0.0, 1.0)
    a, b, c = 0.17883277, 1.0 - 4.0 * 0.17883277, 0.55991073
    return np.where(e <= 1.0 / 12.0, np.sqrt(3.0 * e), a * np.log(np.maximum(12.0 * e - b, 1e-12)) + c)


def pull_to_p3_gamut(rgb, max_val=1.0):
    y = np.sum(rgb * P3_LUMA, axis=-1, keepdims=True)
    mn = np.min(rgb, axis=-1, keepdims=True)
    neg_scale = np.where(mn < 0.0, y / np.maximum(y - mn, 1e-12), 1.0)
    rgb = y + (rgb - y) * neg_scale
    mx = np.max(rgb, axis=-1, keepdims=True)
    hi_scale = np.where(mx > max_val, np.maximum(max_val - y, 0.0) / np.maximum(mx - y, 1e-12), 1.0)
    rgb = y + (rgb - y) * hi_scale
    rgb = np.where(y <= 0.0, 0.0, np.where(y >= max_val, max_val, rgb))
    return np.clip(rgb, 0.0, max_val)


# ---- HNCS assets ----
_raw = (ASSETS / "LUTTable100MP3.hncs").read_bytes()
assert _raw[:8] == b"HNCSMAP1"
_hl = struct.unpack("<i", _raw[8:12])[0]
HEADER = json.loads(_raw[12:12 + _hl])
_payload = np.frombuffer(zlib.decompress(_raw[12 + _hl:]), "<f4")
assert _payload.size == HEADER["payload"]["floatCount"]
GRID = HEADER["grid"]
GW, GH = GRID["width"], GRID["height"]
VERSION3 = HEADER.get("phocusVersion") == "3.0"


def _values(entry):
    ref = entry["values"]
    return _payload[ref["offset"]:ref["offset"] + ref["count"]].astype(np.float64)


def load_hcurve(kind):
    raw = (ASSETS / f"filmcurve_type{kind}_companding2.hcurve").read_bytes()
    assert raw[:8] == b"HNCURV1\0" and len(raw) == 68 + 65536 * 2
    return np.frombuffer(raw[68:], "<u2").astype(np.float64) / 65535.0


MATRICES = sorted((m["temperature"], _values(m)) for m in HEADER["matrices"])
TABLES = sorted((t["temperature"], _values(t)) for t in HEADER["tables"] if t["intent"] == "standard")
REPRO_TABLES = sorted((t["temperature"], _values(t)) for t in HEADER["tables"] if t["intent"] == "reproduction")
CM = np.array(json.loads((ASSETS / "x2d_ii_100c_calibration.json").read_text())["colorMatrix1"]).reshape(3, 3)
FILM = load_hcurve(6)
FILM_REPRO = load_hcurve(7)
FILM_OLD = build_fc0_perceptual()
NATURE_GRADATION = np.load(DATA / "hncs_nature_gradation.npy").astype(np.float64) / 65535.0
LIGHTEN_GRADATION = np.load(DATA / "hncs_lighten_gradation.npy").astype(np.float64) / 65535.0

TONECURVE = np.load(DATA / "x9u_master_tonecurve.npy").astype(np.float64)
assert TONECURVE.size == 1024
IPE = np.load(DATA / "x9u_master_ipe_gamma.npy").astype(np.float64)
assert IPE.size == 257 and np.all(np.diff(IPE) > 0)
IPE_X = np.arange(257) / 256.0
STOCK_GREY_CODE = 0.4237


def ipe_decode(code):
    """IPE-coded value -> ISP linear; above the last node the last segment's slope continues."""
    code = np.clip(code, 0, None)
    lin = np.interp(code, IPE, IPE_X)
    slope = (IPE_X[-1] - IPE_X[-2]) / (IPE[-1] - IPE[-2])
    return np.where(code > IPE[-1], 1.0 + (code - IPE[-1]) * slope, lin)


GREY_SCENE = float(hlg_inv_oetf(np.array(0.75))) * 0.18
INPUT_SCALE = GREY_SCENE / float(ipe_decode(np.array(STOCK_GREY_CODE)))


def decode_input(code):
    return ipe_decode(code) * INPUT_SCALE


def _interp(anchors, t):
    if t <= anchors[0][0]:
        return anchors[0][1]
    if t >= anchors[-1][0]:
        return anchors[-1][1]
    for (t0, v0), (t1, v1) in zip(anchors, anchors[1:]):
        if t0 <= t <= t1:
            w = (t - t0) / (t1 - t0)
            return v0 * (1 - w) + v1 * w


class Engine:
    def __init__(
        self,
        cct,
        film=FILM,
        tables=TABLES,
        companding_gamma=False,
        is_reproduction=False,
        apply_hs=False,
        sat_scale=1.0,
        contrast=0.0,
        gamma=1.0,
        gradation=None,
        k=None,
    ):
        self.cct = float(cct)
        self.film = np.asarray(film, dtype=np.float64)
        self.companding_gamma = bool(companding_gamma)
        self.is_reproduction = bool(is_reproduction)
        self.apply_hs = bool(apply_hs)
        self.sat_scale = float(sat_scale)
        self.contrast = float(contrast)
        self.gamma = float(gamma)
        self.gradation = None if gradation is None else np.asarray(gradation, dtype=np.float64)
        self.hdr_film = build_hdr_filmcurve(self.film, self.companding_gamma, self.is_reproduction)
        ttab = min(max(self.cct, 2000.0), 10000.0 if VERSION3 else 6200.0)
        self.table = _interp(tables, float(int(ttab))).reshape(GH, GW, 2)
        self.k = self._exposure() if k is None else float(k)

    @classmethod
    def from_look(cls, path, cct):
        blob = Path(path).read_bytes()
        assert blob[:8] == b"MCOLLOOK"
        version, kind = struct.unpack_from("<II", blob, 8)
        assert version == 1 and kind == 9
        at = 16
        k, flags, sat_scale, contrast, gamma = struct.unpack_from("<fIfff", blob, at)
        at += 20
        film = np.frombuffer(blob, "<u2", 65536, at).astype(np.float64) / 65535.0
        at += 65536 * 2
        count = struct.unpack_from("<I", blob, at)[0]
        at += 4
        tables = []
        nvals = GW * GH * 2
        for _ in range(count):
            temp = struct.unpack_from("<f", blob, at)[0]
            at += 4
            vals = np.frombuffer(blob, "<f4", nvals, at).astype(np.float64)
            at += nvals * 4
            tables.append((temp, vals))
        gradation = None
        if flags & FLAG_HAS_GRADATION:
            gradation = np.frombuffer(blob, "<u2", 65536, at).astype(np.float64) / 65535.0
            at += 65536 * 2
        assert at == len(blob)
        return cls(
            cct,
            film=film,
            tables=sorted(tables, key=lambda x: x[0]),
            companding_gamma=bool(flags & FLAG_COMPANDING_GAMMA),
            is_reproduction=bool(flags & FLAG_IS_REPRODUCTION),
            apply_hs=False,
            sat_scale=sat_scale,
            contrast=contrast,
            gamma=gamma,
            gradation=gradation,
            k=k,
        )

    def _colormap(self, rgb):
        ycc = rgb @ RGB_TO_YCC.T
        y = ycc[..., 0]
        ok = y > 1e-6
        safe = np.where(ok, y, 1.0)
        pos = GRID["divFactor"] * ycc[..., 1:] / safe[..., None] - np.array([GRID["cbStart"], GRID["crStart"]])
        pos = np.clip(pos, 0, [GW - 1, GH - 1])
        lo = np.clip(np.floor(pos).astype(int), 0, [GW - 2, GH - 2])
        f = pos - lo
        x0, y0 = lo[..., 0], lo[..., 1]
        t = self.table
        r0 = t[y0, x0] * (1 - f[..., :1]) + t[y0, x0 + 1] * f[..., :1]
        r1 = t[y0 + 1, x0] * (1 - f[..., :1]) + t[y0 + 1, x0 + 1] * f[..., :1]
        mapped = r0 * (1 - f[..., 1:]) + r1 * f[..., 1:]
        chroma = mapped * y[..., None] / GRID["divFactor"]
        out = np.concatenate([y[..., None], chroma], -1) @ YCC_TO_RGB.T
        return np.where(ok[..., None], out, rgb)

    def _p3_hdr(self, scene_p3):
        hass = self._colormap(np.asarray(scene_p3, dtype=np.float64) @ P3_TO_HASS.T)
        hdr_norm = eval_1d(self.hdr_film, np.maximum(hass, 0.0) / HDR_MAX_GAIN)
        perc = np.clip(fwd_lstar(hdr_norm * HDR_MAX_GAIN) / HDR_RGB_LIMIT, 0.0, 1.0)
        if self.gradation is not None or self.gamma != 1.0 or self.contrast != 0.0:
            x_sc = np.clip(perc * HDR_RGB_LIMIT, 0.0, HDR_RGB_LIMIT)
            if self.gradation is not None:
                y_sc = np.where(x_sc <= 1.0, eval_1d(self.gradation, x_sc), x_sc)
            else:
                g = np.power(np.maximum(x_sc, 0.0), 1.0 / self.gamma)
                y_sc = g + 0.5 * self.contrast * contrast_s6(g)
            curved = np.clip(y_sc / HDR_RGB_LIMIT, 0.0, 1.0)
            perc = recover_hue(perc, curved)
        if self.sat_scale != 1.0:
            hsl = _rgb2hsl(perc)
            hsl[..., 1] = np.clip(hsl[..., 1] * self.sat_scale, 0.0, 1.0)
            perc = _hsl2rgb(hsl)
        lin_hdr = inv_lstar(perc * HDR_RGB_LIMIT) / HDR_MAX_GAIN
        if self.apply_hs:
            lin_hdr = apply_highlight_strength(lin_hdr, 50.0, 50.0)
        return (lin_hdr * HDR_MAX_GAIN) @ HASS_TO_P3.T

    def render_hdr(self, scene_p3):
        """Scene-linear P3 -> HLG-encoded HDR in [0, 1] for Main/Thumbnail paths."""
        return hlg_oetf(pull_to_p3_gamut(self._p3_hdr(scene_p3), HNCS_HDR_PEAK) / HNCS_HDR_PEAK)

    def render(self, scene_p3):
        """Scene-linear P3 (already exposure-scaled) -> display-linear P3 in [0, 1]."""
        p3_hdr = self._p3_hdr(scene_p3)
        y = np.sum(p3_hdr * P3_LUMA, axis=-1, keepdims=True)
        mn = np.min(p3_hdr, axis=-1, keepdims=True)
        neg_scale = np.where(mn < 0.0, y / np.maximum(y - mn, 1e-12), 1.0)
        p3_pos = y + (p3_hdr - y) * neg_scale
        y2 = shoulder_c1(y, 0.35)
        p3_scaled = np.where(y > 0.0, p3_pos * (y2 / np.maximum(y, 1e-12)), 0.0)
        return pull_to_p3_gamut(p3_scaled)

    def _exposure(self):
        grey = GREY_SCENE
        lo, hi = 1e-3, 100.0
        for _ in range(60):
            k = (lo * hi) ** 0.5
            lo, hi = (k, hi) if self.render(np.full((1, 3), grey * k))[0, 1] < 0.18 else (lo, k)
        return (lo * hi) ** 0.5


def solve_standard_k(cct=5500.0):
    return Engine(cct).k


def invert_projection(target):
    """Display-linear P3 target -> master LMT output code (per channel HLG OETF)."""
    return np.clip(hlg_oetf(target), 0, 1)


def bake(cct):
    """Returns the LMT as float32 planes [R,G,B], each indexed [B][G][R] (BasicTone layout)."""
    engine = Engine(cct)
    axis = np.linspace(0, 1, N)
    b, g, r = np.meshgrid(axis, axis, axis, indexing="ij")
    nodes = np.stack([r, g, b], -1).reshape(-1, 3)
    out = invert_projection(engine.render(decode_input(nodes) * engine.k)).reshape(N, N, N, 3)
    return np.stack([out[..., 0], out[..., 1], out[..., 2]]).astype(np.float32), engine
