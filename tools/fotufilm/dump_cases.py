"""Reference cases for the native engine: options + scene points + the Python engine's output (parity_test.cpp compares).

Usage: python dump_cases.py <out file> [group ...]    groups: digital  (more are added with the media)
"""
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import refs  # noqa: E402

sys.path.insert(0, str(refs.FOTUFILM_ENGINE))
import fotufilm_engine as E  # noqa: E402
import fotufilm_screen as S  # noqa: E402
import fotufilm_print as P  # noqa: E402
import fotufilm_filters as Fi  # noqa: E402

HERE = Path(__file__).resolve().parent
# Three films stand for the catalogue: plain geometry (portra400), donor layer (pro400h), 3200 K reference (vision500t).
SAMPLE = ["portra400", "pro400h", "vision500t"]
FILMS = [f["id"] for f in json.loads((HERE.parent / "mastercolor" / "catalog.json").read_text(encoding="utf-8"))["films"]]
PRINTS = sorted(P.print_stock_ids())
FILTERS = [f.id for f in Fi.catalogue()]
rng = np.random.default_rng(7)
# ProPhoto D50 scene points: neutrals, saturated, out-of-gamut, dark and bright.
POINTS = [[0.18] * 3, [0.02] * 3, [1.5] * 3, [0.4, 0.2, 0.05], [0.05, 0.3, 0.1], [0.02, 0.02, 0.4], [0.6, 0.6, 0.02],
          [0.9, 0.0, 0.0], [0.0, 0.8, 0.0], [0.0, 0.0, 0.9], [0.0, 0.0, 0.0], [2.0, 0.5, 0.1], [0.001, 0.003, 0.002]]
POINTS += [list(np.round(rng.random(3) * rng.choice([0.3, 1.0, 2.8]), 5)) for _ in range(20)]


def line(opt, engine_kwargs, medium):
    eng = E.Engine(FILMS[opt["stock"]], medium, **engine_kwargs)
    rows = []
    for p in POINTS:
        o = eng.render(np.array(p, float))
        rows.append(" ".join(repr(float(v)) for v in p) + " > " + " ".join(repr(float(v)) for v in o))
    head = " ".join(str(opt.get(k, d)) for k, d in (
        ("stock", 0), ("medium", 0), ("print", -1), ("style", 0), ("grade", 2.0), ("push", 0.0), ("expired", 0.0), ("shutter", 0.0),
        ("dir", 1.0), ("bleach", 0.0), ("viewing", 0.0), ("preflash", 0.0), ("pfilter", 0), ("mag", 0.4), ("yel", 0.5),
        ("scanner", 0), ("shadow", 0.0), ("highlight", 0.0), ("filter", -1), ("lr", 25.0), ("lg", 25.0), ("lb", 25.0)))
    return "case " + head + "\n" + "\n".join(rows)


def main():
    out, groups = Path(sys.argv[1]), set(sys.argv[2:]) or {"digital"}
    cases = []
    if "digital" in groups:
        for f in SAMPLE:
            i = FILMS.index(f)
            cases.append(line(dict(stock=i, medium=0, style=0), {}, "digital-reference"))
            cases.append(line(dict(stock=i, medium=0, style=1, grade=2.0), dict(style=S.GRADED_PRINT), "digital-reference"))
        i = FILMS.index("portra400")
        cases.append(line(dict(stock=i, medium=0, style=1, grade=0.5), dict(style=S.GRADED_PRINT, screen_grade=0.5), "digital-reference"))
        cases.append(line(dict(stock=i, medium=0, style=1, grade=4.0), dict(style=S.GRADED_PRINT, screen_grade=4.0), "digital-reference"))
        cases.append(line(dict(stock=i, medium=0, push=1.0), dict(development_ev=1.0), "digital-reference"))
        cases.append(line(dict(stock=i, medium=0, expired=10.0), dict(expired_years=10.0), "digital-reference"))
        cases.append(line(dict(stock=i, medium=0, shutter=30.0), dict(shutter_seconds=30.0), "digital-reference"))
        cases.append(line(dict(stock=i, medium=0, dir=0.5), dict(coupler_scale=0.5), "digital-reference"))
        cases.append(line(dict(stock=i, medium=0, dir=2.0), dict(coupler_scale=2.0), "digital-reference"))
    if "filters" in groups:
        i, v = FILMS.index("portra400"), FILMS.index("vision500t")
        for fid in ("w85b", "w80a", "w81a", "w82c", "w25", "w47", "nd12", "cc20r", "cc50b", "didymium", "w2b"):
            k = FILTERS.index(fid)
            cases.append(line(dict(stock=i, medium=0, filter=k), dict(filters=[fid]), "digital-reference"))
        cases.append(line(dict(stock=FILMS.index("pro400h"), medium=0, filter=FILTERS.index("w81b")), dict(filters=["w81b"]), "digital-reference"))
        cases.append(line(dict(stock=v, medium=2, print=PRINTS.index("vision-2383"), filter=FILTERS.index("w85b")), dict(filters=["w85b"]), "vision-2383"))
        cases.append(line(dict(stock=FILMS.index("velvia50"), medium=0, filter=FILTERS.index("w81c")), dict(filters=["w81c"]), "digital-reference"))
    if "lights" in groups:
        v = FILMS.index("vision500t")
        for lights in ((25, 25, 25), (31, 25, 25), (25, 18, 25), (25, 25, 14), (30, 22, 27), (1, 50, 25)):
            cases.append(line(dict(stock=v, medium=2, print=PRINTS.index("vision-2383"), lr=lights[0], lg=lights[1], lb=lights[2]),
                              dict(printer_lights=lights), "vision-2383"))
        cases.append(line(dict(stock=v, medium=2, print=PRINTS.index("eterna-cp"), lr=28, lg=20, lb=30),
                          dict(printer_lights=(28, 20, 30)), "eterna-cp"))
    if "mono" in groups:
        for sid in ("acros100", "trix400", "tmax100", "doublex5222", "hp5plus400", "fp4plus125", "delta3200"):
            i = FILMS.index(sid)
            cases.append(line(dict(stock=i, medium=0, style=0), {}, "digital-reference"))
            cases.append(line(dict(stock=i, medium=0, style=1, grade=2.0), dict(style=S.GRADED_PRINT), "digital-reference"))
            cases.append(line(dict(stock=i, medium=2, print=PRINTS.index("ektacolor-edge")), {}, "ektacolor-edge"))
        cases.append(line(dict(stock=FILMS.index("trix400"), medium=0, style=1, grade=4.0), dict(style=S.GRADED_PRINT, screen_grade=4.0), "digital-reference"))
        cases.append(line(dict(stock=FILMS.index("trix400"), medium=0, push=3.0), dict(development_ev=3.0), "digital-reference"))
        cases.append(line(dict(stock=FILMS.index("trix400"), medium=0, shutter=100.0), dict(shutter_seconds=100.0), "digital-reference"))
        cases.append(line(dict(stock=FILMS.index("trix400"), medium=0, expired=10.0), dict(expired_years=10.0), "digital-reference"))
        cases.append(line(dict(stock=FILMS.index("trix400"), medium=0, filter=FILTERS.index("w25")), dict(filters=["w25"]), "digital-reference"))
        cases.append(line(dict(stock=FILMS.index("acros100"), medium=2, print=PRINTS.index("vision-2383"), filter=FILTERS.index("w11")), dict(filters=["w11"]), "vision-2383"))
        cases.append(line(dict(stock=FILMS.index("fp4plus125"), medium=0, push=-1.3333), dict(development_ev=-1.3333), "digital-reference"))
        cases.append(line(dict(stock=FILMS.index("doublex5222"), medium=1), {}, "negative"))
    if "print" in groups:
        P_ = lambda k: PRINTS.index(k)
        for f in SAMPLE:
            i = FILMS.index(f)
            cases.append(line(dict(stock=i, medium=2), {}, "match-film"))
        i = FILMS.index("portra400")
        for k in PRINTS:
            if "ilfochrome" in k:
                continue
            cases.append(line(dict(stock=i, medium=2, print=P_(k)), {}, k))
        for kel in (6504, 2856, 5003):
            cases.append(line(dict(stock=i, medium=2, print=P_("ektacolor-edge"), viewing=kel), dict(viewing_kelvin=kel), "ektacolor-edge"))
        v = FILMS.index("vision500t")
        cases.append(line(dict(stock=v, medium=2, print=P_("vision-2383"), viewing=6504), dict(viewing_kelvin=6504), "vision-2383"))
        cases.append(line(dict(stock=i, medium=2, print=P_("ektacolor-edge"), bleach=0.6), dict(bleach_bypass=0.6), "ektacolor-edge"))
        cases.append(line(dict(stock=v, medium=2, print=P_("vision-2383"), bleach=1.0), dict(bleach_bypass=1.0), "vision-2383"))
        cases.append(line(dict(stock=i, medium=2, print=P_("ektacolor-edge"), preflash=0.02), dict(printer_preflash=0.02), "ektacolor-edge"))
        cases.append(line(dict(stock=v, medium=2, print=P_("vision-2383"), preflash=0.05), dict(printer_preflash=0.05), "vision-2383"))
        cases.append(line(dict(stock=i, medium=2, print=P_("ektacolor-edge"), pfilter=1, mag=0.8, yel=0.2),
                          dict(printer=dict(lampKelvin=3200.0, magenta=0.8, yellow=0.2)), "ektacolor-edge"))
        cases.append(line(dict(stock=i, medium=2, print=P_("crystal-archive"), pfilter=1, mag=0.1, yel=0.9),
                          dict(printer=dict(lampKelvin=3200.0, magenta=0.1, yellow=0.9)), "crystal-archive"))
        cases.append(line(dict(stock=i, medium=2, print=P_("ektacolor-edge"), shadow=0.6, highlight=0.6),
                          dict(shadow_detail=0.6, highlight_detail=0.6), "ektacolor-edge"))
        cases.append(line(dict(stock=v, medium=2, print=P_("vision-2383"), shadow=0.5, highlight=0.0),
                          dict(shadow_detail=0.5), "vision-2383"))
        cases.append(line(dict(stock=i, medium=2, print=P_("ektacolor-edge"), push=1.0, expired=10.0, dir=2.0),
                          dict(development_ev=1.0, expired_years=10.0, coupler_scale=2.0), "ektacolor-edge"))
    if "instax" in groups:
        for f in ("instaxmini", "instaxwide"):
            i = FILMS.index(f)
            cases.append(line(dict(stock=i, medium=0), {}, "digital-reference"))
    if "positive" in groups:
        for f in ("provia100f", "velvia50", "ektachromee100", "kodachrome64"):
            i = FILMS.index(f)
            cases.append(line(dict(stock=i, medium=0), {}, "digital-reference"))
            for k in ("ilfochrome-cps-1k", "ilfochrome-clm-1k"):
                cases.append(line(dict(stock=i, medium=2, print=PRINTS.index(k)), {}, k))
    if "negative" in groups:
        i = FILMS.index("portra400")
        cases.append(line(dict(stock=i, medium=1), {}, "negative"))
        cases.append(line(dict(stock=i, medium=1, scanner=1), dict(look="scanner"), "negative"))
        cases.append(line(dict(stock=i, medium=1, bleach=0.7), dict(bleach_bypass=0.7), "negative"))
        v = FILMS.index("pro400h")
        cases.append(line(dict(stock=v, medium=1), {}, "negative"))
    out.write_text("\n".join(cases) + "\n", encoding="utf-8", newline="\n")
    print(out, len(cases), "cases")


if __name__ == "__main__":
    main()
