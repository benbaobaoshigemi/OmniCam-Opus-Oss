"""Packs the fotufilm engine's data for the native colour engine (cpp/fotufilm): module/payload/master_color/fotufilm.bin.

Everything that does not depend on the user's settings is resolved here: spectral tables, each film's final sensitivities and
dyes, characteristic curves with their push conditions, each film's grain and halation (halation.py solves its kernel on the
35 mm acetate support), print stocks and their reflection-print trim, the colour prior.
Little endian; f64 unless noted. Layout in cpp/fotufilm/data.cpp (same order).
Usage: python export_data.py [out file]
"""
import json
import struct
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import refs  # noqa: E402

sys.path.insert(0, str(refs.FOTUFILM_ENGINE))
import fotufilm_core as F  # noqa: E402
import fotufilm_filters as Fi  # noqa: E402
import fotufilm_print as P  # noqa: E402
import fotufilm_screen as S  # noqa: E402

import halation  # noqa: E402

HERE = Path(__file__).resolve().parent
# fotufilm FilmFormat: 35 mm on acetate safety base, the record-dispersed refractive indices of FilmBase.acetate.
BASE_MM = 0.13
BASE_INDEX = (1.474, 1.480, 1.489)
# FilmStockDefaults.grainDensityRecords: the Vision3 family shape.
DEFAULT_GRAIN_PROFILE = [[20.0, 0.3373, 0.2629, 0.8187, 1.1811, 0.3212], [20.0, 0.3583, 0.3183, 1.6514, 1.4856, 0.3631],
                         [20.0, 0.1069, 0.5457, 4.2644, 1.38, 0.3446]]
LAWS = {"dyeCloud": 0, "silver": 1, "dyeCloudSelwyn": 2, "dyeCloudReversal": 3}
CATALOG = HERE.parents[0] / "mastercolor" / "catalog.json"


class W:
    def __init__(self):
        self.b = bytearray()

    def u32(self, v):
        self.b += struct.pack("<I", int(v))

    def f64(self, v):
        self.b += struct.pack("<d", float(v))

    def arr(self, a):
        a = np.asarray(a, "<f8").ravel()
        self.u32(len(a))
        self.b += a.tobytes()

    def text(self, s):
        e = s.encode("utf-8")
        self.u32(len(e))
        self.b += e

    def flag(self, v):
        self.u32(1 if v else 0)


def curve(w, c):
    for v in (c.d_min, c.gamma, c.toe, c.toe_w, c.sh, c.sh_w):
        w.f64(v)
    sec = c.secondary
    w.flag(sec)
    if sec:
        for k in ("gamma", "toe", "toeWidth", "shoulder", "shoulderWidth"):
            w.f64(sec[k])
    w.flag(c.sampled)
    if c.sampled:
        w.arr(c.x)
        w.arr(c.y)


def stock(w, sid):
    s = F.Stock(sid)
    raw = s.raw
    w.text(sid)
    w.flag(s.is_reversal)
    w.flag(raw.get("isReflectionPrint", False))
    w.flag(s.is_mono)
    if s.is_mono:
        curve(w, s.paper_curve)
    ref_illum = F.at_locus(s.reference_kelvin)
    w.arr(ref_illum)
    w.arr(s.layer_sens)
    w.arr(s.dye)
    w.flag(s.min_density is not None)
    if s.min_density is not None:
        w.arr(s.min_density)
    for c in s.curves:
        curve(w, c)
    d = s.donor
    w.flag(d)
    if d:
        w.arr(d["sensitivity"])
        curve(w, d["curve"])
        w.arr(d["inhibition"])
        w.f64(d["release_gamma"])
    geo = raw.get("couplerGeometry")
    w.flag(geo)
    if geo:
        w.arr(geo["interlayerTransmission"])
        w.arr(geo["release"])
        w.f64(geo.get("selfRetention", 0.091))
    else:
        w.arr(np.array(raw["couplerInhibition"], float))
    w.arr(raw.get("couplerReleaseGamma", [1, 1, 1]))
    rec = raw.get("reciprocityFailure")
    w.flag(rec)
    if rec:
        w.f64(rec["thresholdSeconds"])
        w.f64(rec["lostStopsPerDecade"])
        w.f64(rec.get("statedThroughSeconds") or -1.0)
        lw = rec.get("layerWeights")
        w.flag(lw)
        if lw:
            w.arr(lw)
    w.text(raw.get("nativePrintMedium") or "")
    conds = raw.get("development", {}).get("conditions") or []
    w.u32(len(conds))
    for c in conds:
        w.f64(c["stops"])
        for cc in c["curves"]:
            curve(w, F.Curve(cc))
        dc = c.get("donorCurves") or []
        w.flag(dc)
        if dc:
            curve(w, F.Curve(dc[0]))
    spatial(w, raw, s.layer_sens, ref_illum)


def spatial(w, raw, layer_sens, ref_illum):
    """Grain (FilmStock grain fields, pack defaults) and the solved halation kernel."""
    mono = bool(raw.get("isMonochrome", False))
    reversal = bool(raw.get("isReversal", False))
    w.f64(raw.get("grainStrength", 0))
    w.f64(raw.get("grainSizeMM", 0))
    w.arr(raw.get("grainLayerWeights", [1, 1, 1]))
    w.arr(raw.get("grainLayerSizeRatio", [1, 1, 1]))
    w.f64(1.0 if mono else min(max(raw.get("grainLumaCorrelation", 0), 0), 1))
    w.f64(min(max(raw.get("grainMottleShare", 0), 0), 0.9))
    w.f64(min(max(raw.get("grainMottleSizeRatio", 3), 1), 8))
    law = raw.get("grainDensityLaw")
    if law is None:
        law = 1 if mono else (3 if reversal else 0)
    w.u32(LAWS[law] if isinstance(law, str) else law)
    rows = raw.get("grainDensityProfile")
    rows = rows.get("records") if isinstance(rows, dict) else rows
    rows = [r + [0, 1, 0.3] if len(r) == 3 else r for r in rows] if rows and len(rows) == 3 else DEFAULT_GRAIN_PROFILE
    if any(len(r) != 6 for r in rows):
        rows = DEFAULT_GRAIN_PROFILE
    w.arr(rows)
    w.arr(raw.get("grainReversalProfile", [1.1, 3]))
    w.f64(max(raw.get("grainFogDensity", 0.03), 0))
    w.flag(raw.get("granularityReadDensity", "gross" if reversal else "net") == "gross")
    if raw.get("layeredTransport"):
        sigma, weights, matrix = halation.layered_kernel(
            raw["layeredTransport"], layer_sens, ref_illum,
            raw.get("halationLookScale", 1), raw.get("halationHazeMM") or 0, raw.get("halationReturnMatrix"))
    else:
        returned = [max(v, 0) * (raw.get("halationLookScale") or 1) for v in raw.get("halationStrength", [0, 0, 0])]
        sigma, weights, matrix = halation.kernel(returned, BASE_MM, BASE_INDEX, raw.get("halationProfile"),
                                                 raw.get("halationHazeMM") or 0, raw.get("halationReturnMatrix"))
    w.arr(sigma)
    w.arr(weights)
    w.arr(matrix)


def print_stock(w, key):
    p = P.PrintStock(key)
    w.text(key)
    w.text(p.process)
    for c in p.curves:
        curve(w, c)
    w.arr(p.analytical_dyes)
    w.arr(p.dyes)
    w.arr(p.sensitivity)
    w.flag(p.lad is not None)
    if p.lad is not None:
        w.arr(p.lad)
    w.flag(p.sens_above_min)
    w.arr(P.reflection_print_density_trim(p) if p.is_reflection else np.zeros(3))


def main():
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else HERE.parents[1] / "module/payload/master_color/fotufilm.bin"
    films = [f["id"] for f in json.loads(CATALOG.read_text(encoding="utf-8"))["films"]]
    w = W()
    w.b += b"FOTUFILM"
    w.u32(3)
    for a in (F.XBAR, F.YBAR, F.ZBAR, F.D65, P.STATUS_A, P.D50, P.XENON, P.ENLARGER_3200K, F.daylight(6504.0), F.planckian(2856.0),
              F.planckian(3200.0), F.family_dyes("kodakNegative")):
        w.arr(a)
    edge = S.edge_paper()
    for a in (edge.analytical_dyes, edge.dyes, edge.sensitivity):
        w.arr(a)
    prior = F.Reflectance()
    w.u32(prior.dim)
    w.u32(prior.bands)
    w.f64(prior.anchor)
    w.b += prior.data.astype("<f4").tobytes()
    w.u32(len(films))
    for sid in films:
        stock(w, sid)
    keys = sorted(P.print_stock_ids())
    w.u32(len(keys))
    for k in keys:
        print_stock(w, k)
    filters = Fi.catalogue()
    w.u32(len(filters))
    for f in filters:
        w.text(f.id)
        w.text(f.name)
        w.arr(f.transmittance)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(bytes(w.b))
    print(out, len(w.b), "bytes;", len(films), "films,", len(keys), "prints,", len(filters), "filters")


if __name__ == "__main__":
    main()
