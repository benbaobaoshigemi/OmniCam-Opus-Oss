"""Check selection coverage, source compatibility, asset references, and native/reference parity (films: tools/fotufilm/parity_test.cpp)."""
import argparse
import itertools
import json
from pathlib import Path
import re
import struct
import subprocess
import sys

import numpy as np

HERE = Path(__file__).resolve().parent
PROJECT = HERE.parents[1]
LOOKS = PROJECT / "module/payload/master_color/looks"


def array(source, name):
    m = re.search(r"String\[\] " + name + r" = \{(.*?)\};", source, re.S)
    assert m, name
    return re.findall(r'"([^"\n]*)"', m[1])


def validate():
    catalog = json.loads((HERE / "catalog.json").read_text(encoding="utf-8"))
    styles = PROJECT / "app/src/main/java/local/jiege/settings"
    source = "\n".join((styles / f).read_text(encoding="utf-8") for f in (
        "HncsStyle.java", "FilmStyle.java", "LumixStyle.java", "CanonStyle.java", "BasicStyle.java", "X100viStyle.java",
        "AdobeSigmaStyle.java", "AdobeOmStyle.java", "AdobeSonyStyle.java", "AdobeNikonStyle.java", "LeicaM240Style.java", "LeicaM10rStyle.java",
    ))
    assert array(source, "FILM_IDS") == [f["id"] for f in catalog["films"]]
    assert array(source, "FILM_LABELS") == [f["label"] for f in catalog["films"]]
    names = [("hncs" if v == "standard" else "hncs_" + v) for v in array(source, "HNCS_IDS")] + ["leica_m9", "x8u", "x7u", "agx", "sigmoid", "filmic"]
    for prefix, group in [
        ("lumix_", "LUMIX_IDS"), ("canon_", "CANON_IDS"), ("x100vi_", "X100VI_IDS"),
        ("adobe_sigma_", "ADOBE_SIGMA_IDS"),
        ("adobe_om_", "ADOBE_OM_IDS"), ("leica_m240_", "LEICA_M240_IDS"), ("leica_m10r_", "LEICA_M10R_IDS"), ("adobe_sony_", "ADOBE_SONY_IDS"), ("adobe_nikon_", "ADOBE_NIKON_IDS"),
    ]:
        names += [prefix + v for v in array(source, group)]
    names += ["basic_" + v for v in array(source, "BASIC_IDS")]
    # These seven assets remain in the module payload; Fuji is no longer a UI family.
    legacy_fuji = ["provia", "velvia", "astia", "classic_chrome", "classic_negative", "nostalgic_neg", "reala_ace"]
    names += ["fuji_" + name for name in legacy_fuji]
    for name in names:
        if name == "hncs": continue
        if name == "x8u":
            assert sorted(p.name for p in (LOOKS / "x8u").iterdir()) == ["LMTLut%d" % i for i in range(4)]
            assert all(p.stat().st_size == 3*64**3*4 for p in (LOOKS / "x8u").iterdir())
            continue
        path = LOOKS / (name + (".lmt" if name == "x7u" else ".bin"))
        assert path.is_file(), path
        if name == "x7u":
            assert path.stat().st_size == 3*64**3*4
            continue
        blob = path.read_bytes()
        assert blob[:8] == b"MCOLLOOK", path
        version, kind = struct.unpack_from("<II", blob, 8)
        assert version == 1
    print(f"PASS catalog: {len(names)} looks, film lattices computed natively", flush=True)
    return names


def parity(names):
    import master_lmt as M
    import looks
    work = PROJECT / "build/mastercolor_check"
    work.mkdir(parents=True, exist_ok=True)
    # Parameter overrides: "<look> p0 p1 p2 p3" in the reference's units and order (nan = default). The native check takes
    # the style's own settings by name ("<look> name=value ..."; the sigmoid hue in percent) and writes <line with _>.f32.
    overrides = ["basic_gamma 1.8", "sigmoid 2.2 0.5", "sigmoid 1.0 0.0", "agx -8 5 2 2", "agx nan nan nan 2.5",
                 "filmic -6 3.5", "filmic -8.5 6", "agx -10 6.5 1.5 3.3", "leica_m9 -2 2", "leica_m9 2 -2", "leica_m240_standard -2 2", "leica_m240_standard 2 -2", "leica_m240_standard 1 1", "leica_m240_vivid", "leica_m240_natural", "leica_m10r_color -1 1", "leica_m10r_color 1 -1", "leica_m10r_color 1 1", "leica_m10r_monochrome -1", "leica_m10r_monochrome 1",
                 "x100vi_provia nan nan nan nan nan 2 0", "x100vi_provia nan nan nan nan nan 0 2", "x100vi_provia nan nan nan nan nan 2 2", "x100vi_provia nan nan nan nan nan 0 0 1", "x100vi_eterna nan nan nan nan nan 0 0 1", "x100vi_nostalgic_neg nan nan nan nan nan 0 0 1", "x100vi_reala_ace nan nan nan nan nan 1 1 1", "x100vi_velvia nan nan nan nan nan 1 2", "x100vi_nostalgic_neg nan nan nan nan nan 2 2", "x100vi_reala_ace", "x100vi_reala_ace nan nan nan nan nan 1 0", "x100vi_reala_ace nan nan nan nan nan 0 1", "x100vi_reala_ace nan nan nan nan nan 2 2"]
    setting_names = {"basic_gamma": ("gamma",), "sigmoid": ("contrast", "hue"), "agx": ("black", "white", "toe", "shoulder"),
                     "filmic": ("black", "white"), "leica_m9": ("contrast", "saturation"), "leica_m240_standard": ("contrast", "saturation"), "leica_m10r_color": ("contrast", "saturation"), "leica_m10r_monochrome": ("contrast",),
                     "x100vi_provia": ("highlight", "shadow", "wb_r", "wb_b", "color", "color_chrome", "color_chrome_blue", "dr"),
                     "x100vi_eterna": ("highlight", "shadow", "wb_r", "wb_b", "color", "color_chrome", "color_chrome_blue", "dr"),
                     "x100vi_velvia": ("highlight", "shadow", "wb_r", "wb_b", "color", "color_chrome", "color_chrome_blue", "dr"),
                     "x100vi_reala_ace": ("highlight", "shadow", "wb_r", "wb_b", "color", "color_chrome", "color_chrome_blue", "dr"),
                     "x100vi_nostalgic_neg": ("highlight", "shadow", "wb_r", "wb_b", "color", "color_chrome", "color_chrome_blue", "dr")}

    def native_line(line):
        tokens = line.split()
        parts = [tokens[0]]
        for setting, token in zip(setting_names.get(tokens[0], ()), tokens[1:]):
            if token != "nan":
                parts.append(f"{setting}={float(token) * (100 if (tokens[0], setting) == ('sigmoid', 'hue') else 1):g}")
        return " ".join(parts)

    names = names + overrides
    (work / "names.txt").write_text("\n".join(native_line(n) for n in names)+"\n", encoding="ascii")
    ccm = [1.0703125,-0.1328125,0.0625,-0.09375,1.109375,-0.03125,0.046875,-0.5703125,1.5234375]
    # Stable host fixture; the lens profile is the main camera's Adobe Standard data from scene.bin.
    lens = "main23"
    key = b"MCOLKEY9" + struct.pack("<ii9fiiI", 2, 5000, *ccm, 0, 0, 4) + b"hncs" + struct.pack("<2f", 1, 1)
    key += struct.pack("<I",len(lens)) + lens.encode("ascii") + struct.pack("<I", 3)
    for setting, value in (("probe_a", 50.0), ("probe_b", 100.0), ("probe_c", 150.0)):
        key += struct.pack("<I", len(setting)) + setting.encode("ascii") + struct.pack("<f", value)
    (work / "key.bin").write_bytes(key)
    exe = work / "catalog_check.exe"
    subprocess.run([sys.executable,"-m","ziglang","c++","-O2","-std=c++17",str(HERE/"catalog_check.cpp"),
                    *[str(p) for p in sorted((PROJECT/"app/src/main/cpp").glob("style_*.cpp"))],
                    *[str(p) for p in sorted((PROJECT/"app/src/main/cpp/fotufilm").glob("*.cpp"))],
                    *[str(PROJECT/"app/src/main/cpp"/f) for f in ("master_core.cpp","master_styles.cpp","master_canon.cpp","master_dt.cpp")],"-o",str(exe)],check=True)
    subprocess.run([str(exe),(LOOKS.parent/"hncs_engine.bin").as_posix(),str(work/"names.txt"),str(work),str(work/"key.bin")],check=True)
    nodes = np.array([(r/63,g/63,b/63) for b,g,r in itertools.product([0,8,24,40,63], repeat=3)])
    worst = (0, "")
    for line in names:
        tokens = line.split()
        name, param = tokens[0], [float(t) for t in tokens[1:]] or None
        stem = native_line(line).replace(" ", "_").replace("=", "_")
        if name in ("x8u", "x7u"): continue
        if name == "hncs" or name.startswith("hncs_"):
            import hncs_engine as E
            engine = E.Engine(5000) if name == "hncs" else E.Engine.from_look(LOOKS / (name + ".bin"), 5000)
            ref = M.render(nodes,"main",5000,ccm,engine=engine,lens=lens)
        else:
            ref = M.render_look(nodes,"main",5000,ccm,looks.Packed(LOOKS/(name+".bin")),lens=lens,param=param)
        native = np.fromfile(work/(stem+".f32"),"<f4").reshape(-1,3)
        error = float(np.abs(native-ref).max())
        if error > worst[0]: worst = (error,line)
        # Table quantisation / 10-bit HDR inverse / float-vs-double boundary differences.
        assert error < 0.01, (name,error)
    print(f"PASS native/reference: {len(names)-1} looks × 125 nodes; maximum error {worst[0]:.6f} ({worst[1]})",flush=True)


if __name__ == "__main__":
    p=argparse.ArgumentParser();p.add_argument("--native",action="store_true");args=p.parse_args()
    names=validate()
    if args.native: parity(names)
