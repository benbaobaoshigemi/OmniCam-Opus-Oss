"""Bakes the Adobe looks (kind 15, looks/adobe_<brand>_<id>.bin, looks/basic_standard.bin) as transfers from the Adobe Standard scene.

The camera's Adobe Standard profile and its Camera Matching look both render the same colorimetric scene A: B = F(A) is the
Adobe Standard rendering, C = G(A) the look. The phone is assumed to share F, so its look is the same transfer applied to its
Adobe Standard scene s (forward matrix + HueSatMap, what the master front stage hands the styles): table[s] = G(F_hsm^-1(s)).

Per table node s: s -> inverse of the camera's Adobe Standard HueSatMap -> colorimetric scene a -> synthetic linear DNG of the
camera (forward matrix of the Standard profile) -> Photoshop ACR with the look (a Camera Matching profile or a preset) -> table.
"basic" bakes the Adobe Standard rendering itself: the scene s goes into a synthetic DNG of the phone's lens that embeds its Adobe
Standard profile with a neutral HueSatMap (the front stage already applied it), so ACR adds the profile's look table and tone curve.
The forward matrices of the Adobe Standard profile and of the look's profile differ and both change with the colour temperature,
so a table is baked at several colour temperatures (BAKE_CCT) and the master baker interpolates them (1/T) at the frame's.
The node axis is that of style_adobe.cpp (11-stop P3 scene axis, 18 % grey = 0.18); the exposure of a brand is solved so that its
standard look puts the grey where the other brands' do ("basic": 18 % grey at display 0.18).

Usage: python bake_adobe_transfer.py [basic|sony|om|sigma|nikon ...] [--work DIR]   (Photoshop is driven over COM)
"""
import argparse
import io
import re
import struct
import subprocess
import sys
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageCms

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import refs

sys.path.insert(0, str(refs.DCP_RENDER))
sys.path.insert(0, str(HERE))
import dcp_render as D
import looks as L
import scene_front as S

GRID = 33
NORMAL_TOP, AXIS_NORMAL, HEADROOM_STOPS = 2.88, 0.8, 7.0
KIND = 15
BAKE_CCT = (2856.0, 3970.0, 6504.0)   # A, the 1/T midpoint of the profile illuminants, D65
BASELINE_EV = 10.0
GREY_TARGET = 0.125          # table value of the grey node (scene 0.152) in a brand's standard look
BASIC_GREY_TARGET = 0.144    # the same node in the basic standard: 18 % grey leaves at display 0.18
LOOKS_DIR = HERE.parent.parent / "module/payload/master_color/looks"
ACR = refs.ADOBE_CAMERA_RAW
PROFILES = ACR / "CameraProfiles"
PRESETS = ACR / "Settings/Adobe/Profiles/Camera"
PHONE_PROFILE = refs.ADOBE_DCP / "OPPO Find X9 Ultra wide camera 23mm f1.5 Adobe Standard.dcp"


def camera_dcp(folder, file):
    return ("dcp", PROFILES / "Camera" / folder / file)


def preset(folder, file):
    return ("xmp", PRESETS / folder / file)


SONY_DIR = "Sony ILCE-7M4"
OM_DIR = "OM Digital Solutions OM-1 Mark II"
SIGMA_DIR = "SIGMA fp"
SIGMA_PRESETS = "Sigma/Sigma fp"
NIKON_DIR = "Nikon Z 8"
NIKON_PRESETS = "Nikon/Nikon Z 8"
NIKON_BASIC = {"standard": "Standard", "neutral": "Neutral", "vivid": "Vivid", "portrait": "Portrait",
               "rich_tone_portrait": "Rich Tone Portrait", "landscape": "Landscape", "flat": "Flat"}
NIKON_CREATIVE = ("bleach", "blue", "denim", "dramatic", "dream", "melancholic", "morning", "pink", "pop", "pure", "red",
                  "sepia", "silence", "somber", "sunday", "toy")   # without the grey ones (Binary, Carbon, Charcoal, Graphite)
BRANDS = {
    "sony": dict(
        make="SONY", model="ILCE-7M4", unique="Sony ILCE-7M4", gain=0.489,
        standard=PROFILES / "Adobe Standard/Sony ILCE-7M4 Adobe Standard.dcp",
        looks={k: camera_dcp(SONY_DIR, f"Sony ILCE-7M4 Camera {k.upper()}.dcp") for k in ("st", "vv", "vv2", "fl", "in", "sh", "nt", "pt", "bw")},
    ),
    "om": dict(
        make="OM Digital Solutions", model="OM-1MarkII", unique="OM Digital Solutions OM-1 Mark II", gain=0.47912,
        standard=PROFILES / "Adobe Standard/OM Digital Solutions OM-1 Mark II Adobe Standard.dcp",
        looks={
            "natural": camera_dcp(OM_DIR, f"{OM_DIR} Camera Natural.dcp"), "vivid": camera_dcp(OM_DIR, f"{OM_DIR} Camera Vivid.dcp"),
            "muted": camera_dcp(OM_DIR, f"{OM_DIR} Camera Muted.dcp"), "portrait": camera_dcp(OM_DIR, f"{OM_DIR} Camera Portrait.dcp"),
            "monotone": camera_dcp(OM_DIR, f"{OM_DIR} Camera Monotone.dcp"),
            "monotone_or": camera_dcp(OM_DIR, f"{OM_DIR} Camera Monotone Or Filter.dcp"),
            "monotone_r": camera_dcp(OM_DIR, f"{OM_DIR} Camera Monotone R Filter.dcp"),
            "monotone_g": camera_dcp(OM_DIR, f"{OM_DIR} Camera Monotone G Filter.dcp"),
        },
    ),
    "sigma": dict(
        make="SIGMA", model="SIGMA fp", unique="SIGMA fp", gain=0.58908,
        standard=PROFILES / "Adobe Standard/SIGMA fp Adobe Standard.dcp",
        looks={
            **{k: camera_dcp(SIGMA_DIR, f"SIGMA fp Camera {k.capitalize()}.dcp") for k in ("standard", "vivid", "neutral", "monochrome")},
            "teal_orange": preset(SIGMA_PRESETS, "SIGMA fp Camera Teal and Orange.xmp"), "cinema": preset(SIGMA_PRESETS, "SIGMA fp Camera Cinema.xmp"),
            "powder_blue": preset(SIGMA_PRESETS, "SIGMA fp Camera Powder Blue.xmp"), "fov_blue": preset(SIGMA_PRESETS, "SIGMA fp Camera FOV Classic Blue.xmp"),
            "fov_yellow": preset(SIGMA_PRESETS, "SIGMA fp Camera FOV Classic Yellow.xmp"), "forest_green": preset(SIGMA_PRESETS, "SIGMA fp Camera Forest Green.xmp"),
            "sunset_red": preset(SIGMA_PRESETS, "SIGMA fp Camera Sunset Red.xmp"), "warm_gold": preset(SIGMA_PRESETS, "SIGMA fp Camera Warm Gold.xmp"),
        },
    ),
    "nikon": dict(
        make="NIKON CORPORATION", model="NIKON Z 8", unique="NIKON Z 8", gain=0.5,
        standard=PROFILES / "Adobe Standard/NIKON Z 8 Adobe Standard.dcp",
        looks={
            **{k: camera_dcp(NIKON_DIR, f"NIKON Z 8 Camera {name}.dcp") for k, name in NIKON_BASIC.items()},
            **{k: preset(NIKON_PRESETS, f"Nikon Z 8 Camera {k.capitalize()}.xmp") for k in NIKON_CREATIVE},
        },
    ),
}


def axis_to_scene(u):
    """Inverse of sceneToAxis in style_adobe.cpp."""
    return np.where(u <= AXIS_NORMAL, NORMAL_TOP * L.pp_decode(u / AXIS_NORMAL),
                    NORMAL_TOP * 2.0 ** ((u - AXIS_NORMAL) / (1.0 - AXIS_NORMAL) * HEADROOM_STOPS))


# ---------------------------------------------------------------- the camera's Adobe Standard scene
class Standard:
    """Forward matrix, colour matrix and HueSatMap of a camera's Adobe Standard profile at colour temperature T."""

    def __init__(self, path, temperature=6504.0):
        self.dcp = D.read_dcp(path)
        self.temperature = temperature
        d = self.dcp
        rat = lambda k: (d[k][0::2] / d[k][1::2]).reshape(3, 3)
        t1, t2 = D.ILLUM_T[int(d[50778][0])], D.ILLUM_T[int(d[50779][0])]
        w = float(np.clip((1 / temperature - 1 / t1) / (1 / t2 - 1 / t1), 0, 1))
        mix = lambda a, b: a * (1 - w) + b * w
        self.fm, self.cm = mix(rat(50964), rat(50965)), mix(rat(50721), rat(50722))
        hd, sd, vd = (int(x) for x in d[50937])
        assert vd == 1
        shape = (1, hd, sd, 3)
        self.table = mix(d[50938].astype(np.float64).reshape(shape), d[50939].astype(np.float64).reshape(shape))

    def forward(self, a):
        return S.hue_sat(np.maximum(a, 0.0), self.table)

    def inverse(self, s, rounds=16):
        """The colorimetric scene a with forward(a) = s (the least saturated one where the map saturates)."""
        s = np.maximum(s, 0.0)
        h1, s1, v1 = S.rgb_to_hsv(s)
        a = s.copy()
        for _ in range(rounds):
            ha, sa, va = S.rgb_to_hsv(a)
            hf, sf, vf = S.rgb_to_hsv(self.forward(a))
            dh = (hf - ha + 3.0) % 6.0 - 3.0
            ms = np.where(sa > 1e-4, sf / np.maximum(sa, 1e-4), 1.0)
            mv = np.where(va > 1e-6, vf / np.maximum(va, 1e-6), 1.0)
            ms = np.where(ms > 1e-3, ms, 1.0)
            a = S.hsv_to_rgb(h1 - dh, np.clip(s1 / ms, 0.0, 1.0), v1 / np.where(mv > 1e-3, mv, 1.0))
        return a


# ---------------------------------------------------------------- synthetic DNG
def synthetic_dng(a_pro, std, gain, make, model, unique, embedded=None):
    """Linear 3-channel float DNG whose pixels are the camera RGB of the colorimetric scene a (ProPhoto D50).
    `embedded`: the tags of a profile (Adobe Standard) to embed, its HueSatMap neutral."""
    asn = std.cm @ L.white_xyz(std.temperature)
    asn_n = asn / asn.max()
    cam = (a_pro @ L.PROPHOTO_TO_XYZ.T) @ np.linalg.inv(std.fm).T
    raw = ((cam * gain) * (asn_n / 2.0 ** BASELINE_EV)).astype(np.float32)
    cell, gw, gh = 8, 192, 192
    blocks = np.zeros((gh, gw, 3), np.float32)
    blocks.reshape(-1, 3)[:len(raw)] = raw
    raw_bytes = np.repeat(np.repeat(blocks, cell, 0), cell, 1).tobytes()
    width, height = gw * cell, gh * cell
    extra, entries = bytearray(), []

    def put(data):
        offset = len(extra)
        extra.extend(data)
        return ("OFFSET", offset)

    ascii_ = lambda s: s.encode("ascii") + b"\x00"
    rat = lambda v: (int(round(float(v) * 1000000)), 1000000)

    def srat(a):
        v = []
        for x in np.asarray(a).ravel():
            v.extend(rat(x))
        return struct.pack("<%di" % len(v), *v)

    for tag, typ, count, value in [
        (254, 4, 1, struct.pack("<I", 0)), (256, 4, 1, struct.pack("<I", width)), (257, 4, 1, struct.pack("<I", height)),
        (259, 3, 1, struct.pack("<HH", 1, 0)), (262, 3, 1, struct.pack("<HH", 34892, 0)),
        (277, 3, 1, struct.pack("<HH", 3, 0)), (278, 4, 1, struct.pack("<I", height)), (284, 3, 1, struct.pack("<HH", 1, 0)),
        (50706, 1, 4, struct.pack("<4B", 1, 4, 0, 0)), (50707, 1, 4, struct.pack("<4B", 1, 4, 0, 0)),
    ]:
        entries.append((tag, typ, count, value))
    make_b, model_b, unique_b = ascii_(make), ascii_(model), ascii_(unique)
    entries += [
        (258, 3, 3, put(struct.pack("<3H", 32, 32, 32))), (339, 3, 3, put(struct.pack("<3H", 3, 3, 3))),
        (271, 2, len(make_b), put(make_b)), (272, 2, len(model_b), put(model_b)), (50708, 2, len(unique_b), put(unique_b)),
        (273, 4, 1, ("STRIP_OFF", 0)), (279, 4, 1, struct.pack("<I", len(raw_bytes))),
        (50717, 5, 3, put(struct.pack("<6I", 1, 1, 1, 1, 1, 1))),
        (50728, 5, 3, put(struct.pack("<6I", *[x for v in asn_n for x in rat(v)]))),
        (50730, 10, 1, put(struct.pack("<2i", *rat(BASELINE_EV)))),
    ]
    if embedded is None:
        entries += [(50778, 3, 1, struct.pack("<HH", 21, 0)), (50721, 10, 9, put(srat(std.cm))), (50964, 10, 9, put(srat(std.fm)))]
    else:
        d = embedded
        rm = lambda k: (d[k][0::2] / d[k][1::2]).reshape(3, 3)
        name = ascii_("Adobe Standard")
        entries += [
            (50778, 3, 1, struct.pack("<HH", int(d[50778][0]), 0)), (50779, 3, 1, struct.pack("<HH", int(d[50779][0]), 0)),
            (50721, 10, 9, put(srat(rm(50721)))), (50722, 10, 9, put(srat(rm(50722)))),
            (50964, 10, 9, put(srat(rm(50964)))), (50965, 10, 9, put(srat(rm(50965)))),
            (50936, 2, len(name), put(name)), (50941, 4, 1, struct.pack("<I", 0)),
            (50937, 4, 3, put(struct.pack("<3I", *[int(x) for x in d[50937]]))),
            (50981, 4, 3, put(struct.pack("<3I", *[int(x) for x in d[50981]]))),
        ]
        for tag in (50938, 50939):
            identity = np.tile(np.array([0.0, 1.0, 1.0], "<f4"), len(d[tag]) // 3)
            entries.append((tag, 11, len(identity), put(identity.tobytes())))
        look = d[50982].astype("<f4")
        entries.append((50982, 11, len(look), put(look.tobytes())))
    entries.sort(key=lambda e: e[0])
    extra_base = 8 + 2 + len(entries) * 12 + 4
    strip_base = extra_base + len(extra)
    ifd = bytearray(struct.pack("<H", len(entries)))
    for tag, typ, count, value in entries:
        if isinstance(value, tuple):
            kind, offset = value
            ifd.extend(struct.pack("<HHII", tag, typ, count, strip_base if kind == "STRIP_OFF" else extra_base + offset))
        else:
            ifd.extend(struct.pack("<HHI", tag, typ, count) + value)
    ifd.extend(struct.pack("<I", 0))
    return struct.pack("<2sHI", b"II", 42, 8) + bytes(ifd) + bytes(extra) + raw_bytes


SIDECAR = '''<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="" xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/" crs:Version="16.0" crs:ProcessVersion="11.0" crs:WhiteBalance="As Shot" crs:Exposure2012="0.00" crs:Contrast2012="0" crs:Highlights2012="0" crs:Shadows2012="0" crs:Whites2012="0" crs:Blacks2012="0" crs:Texture="0" crs:Clarity2012="0" crs:Dehaze="0" crs:Vibrance="0" crs:Saturation="0" crs:Sharpness="0" crs:LuminanceSmoothing="0" crs:ColorNoiseReduction="0" crs:ToneCurveName2012="Linear" crs:CameraProfile="%s" crs:HasSettings="True"/></rdf:RDF></x:xmpmeta>'''
PRESET_SIDECAR = '''<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="" xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/" crs:Version="16.0" crs:ProcessVersion="11.0" crs:WhiteBalance="As Shot" crs:Exposure2012="0.00" crs:Contrast2012="0" crs:Highlights2012="0" crs:Shadows2012="0" crs:Whites2012="0" crs:Blacks2012="0" crs:Texture="0" crs:Clarity2012="0" crs:Dehaze="0" crs:Vibrance="0" crs:Saturation="0" crs:Sharpness="0" crs:LuminanceSmoothing="0" crs:ColorNoiseReduction="0" crs:ToneCurveName2012="Linear" crs:CameraProfile="%(profile)s" crs:HasSettings="True"><crs:Look><rdf:Description crs:Name="%(name)s" crs:Amount="1" crs:Cluster="Adobe" crs:UUID="%(uuid)s" crs:SupportsAmount="true" crs:SupportsMonochrome="false"><crs:Parameters><rdf:Description crs:Version="16.0" crs:ProcessVersion="11.0" crs:CameraProfile="%(profile)s" crs:RGBTable="%(table)s" %(data)s/></crs:Parameters></rdf:Description></crs:Look></rdf:Description></rdf:RDF></x:xmpmeta>'''


def sidecar(look):
    kind, path = look
    if kind == "xmp":
        # A preset (look) is applied through <crs:Look>: its RGBTable id and the table data (crs:Table_<id>) go along with it.
        text = path.read_text(encoding="utf-8")
        table = re.search(r'crs:RGBTable="([^"]+)"', text).group(1)
        return PRESET_SIDECAR % dict(
            profile=re.search(r'crs:CameraProfile="([^"]+)"', text).group(1),
            name=re.search(r'<rdf:li xml:lang="x-default">([^<]+)</rdf:li>', text).group(1),
            uuid=re.search(r'crs:UUID="([^"]+)"', text).group(1), table=table,
            data=re.search(r'(crs:Table_%s="[^"]+")' % table, text).group(1))
    return SIDECAR % D.read_dcp(path)[50936]   # ProfileName


# ---------------------------------------------------------------- Photoshop
def run_photoshop(work, ids):
    work = Path(work)
    names = ", ".join('"%s"' % i for i in ids)
    jsx = f'''#target photoshop
app.displayDialogs = DialogModes.NO;
var items = [{names}];
var dir = "{work.as_posix()}";
for (var i = 0; i < items.length; i++) {{
    var doc = app.open(new File(dir + "/" + items[i] + ".dng"));
    var opts = new TiffSaveOptions();
    opts.byteOrder = ByteOrder.IBM; opts.imageCompression = TIFFEncoding.NONE; opts.embedColorProfile = true;
    doc.saveAs(new File(dir + "/" + items[i] + ".tif"), opts, true, Extension.LOWERCASE);
    doc.close(SaveOptions.DONOTSAVECHANGES);
}}
'''
    (work / "run.jsx").write_text(jsx, encoding="utf-8")
    command = "$ps = New-Object -ComObject Photoshop.Application; $null = $ps.DoJavaScriptFile('%s')" % (work / "run.jsx")
    subprocess.run(["powershell", "-NoProfile", "-Command", command], check=True)
    missing = [i for i in ids if not (work / (i + ".tif")).exists()]
    if missing:
        raise RuntimeError("Photoshop exported nothing for " + ", ".join(missing))


def read_table(path):
    image = Image.open(path)
    icc = ImageCms.getProfileDescription(ImageCms.ImageCmsProfile(io.BytesIO(image.info["icc_profile"]))).strip()
    raw = cv2.imread(str(path), cv2.IMREAD_UNCHANGED)
    pixels = raw[..., ::-1] if raw.ndim == 3 else np.repeat(raw[..., None], 3, -1)
    cells = pixels.reshape(192, 8, 192, 8, 3)[:, 2:6, :, 2:6, :].mean(axis=(1, 3)) / 65535.0
    encoded = cells.reshape(-1, 3)[:GRID ** 3]
    if icc == "Display P3":
        return L.srgb_decode(encoded)
    if icc == "Gray Gamma 2.2":
        return np.clip(encoded, 0.0, 1.0) ** 2.2
    if icc == "ProPhoto RGB":
        return L.pp_decode(encoded) @ L.PROPHOTO_TO_P3.T
    raise ValueError("unexpected ICC profile %s in %s" % (icc, path))


def grey_y(table):
    """Display luma of the 18 % grey scene (the node whose axis value is nearest)."""
    u = np.linspace(0, 1, GRID)
    k = int(np.argmin(abs(axis_to_scene(u) - 0.18)))
    return table[(k * GRID + k) * GRID + k, 1]


def write_look(path, tables, temperatures):
    """kind 15: u32 grid, u32 count, f32 temperatures[count], then count tables of grid^3 * 3 float16 (display-linear P3)."""
    blob = b"MCOLLOOK" + struct.pack("<IIII", 1, KIND, GRID, len(tables)) + np.asarray(temperatures, "<f4").tobytes()
    for table in tables:
        blob += np.clip(table, 0.0, 1.0).astype("<f2").tobytes()
    path.write_bytes(blob)


def render(work, jobs):
    """jobs: {stem: (dng bytes, sidecar text)} -> {stem: table}."""
    for stem, (dng, xmp) in jobs.items():
        (work / (stem + ".dng")).write_bytes(dng)
        (work / (stem + ".xmp")).write_text(xmp, encoding="utf-8")
        (work / (stem + ".tif")).unlink(missing_ok=True)
    run_photoshop(work, list(jobs))
    return {stem: read_table(work / (stem + ".tif")) for stem in jobs}


def solve_gain(work, make_job, gain, target):
    """The scene gain at which the grey of make_job(gain) reaches the target display luma (secant on the log-log response)."""
    points = []
    for _ in range(4):
        y = grey_y(render(work, {"calibrate": make_job(gain)})["calibrate"])
        points.append((np.log(gain), np.log(y)))
        print("  gain %.4f -> grey %.4f (target %.4f)" % (gain, y, target))
        if abs(y / target - 1) < 0.01:
            return gain
        slope = 1.43 if len(points) == 1 else (points[-1][1] - points[-2][1]) / (points[-1][0] - points[-2][0])
        gain = float(np.exp(points[-1][0] + (np.log(target) - points[-1][1]) / slope))
    return gain


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("brands", nargs="*", default=["sony", "om", "sigma"])
    parser.add_argument("--work", default=str(Path(__import__("tempfile").gettempdir()) / "adobe_transfer"))
    parser.add_argument("--gain", type=float, help="override the scene gain (no solving)")
    args = parser.parse_args()
    work = Path(args.work)
    work.mkdir(parents=True, exist_ok=True)
    u = np.linspace(0, 1, GRID)
    b, g, r = np.meshgrid(u, u, u, indexing="ij")
    p3 = axis_to_scene(np.stack([r, g, b], -1).reshape(-1, 3))
    scene = p3 @ np.linalg.inv(L.PROPHOTO_TO_P3).T      # ProPhoto D50: the Adobe Standard scene s of every node
    for name in args.brands:
        if name == "basic":
            phone = Standard(PHONE_PROFILE)
            make_job = lambda gain: (synthetic_dng(scene, phone, gain, "OPPO", "Find X9 Ultra W", "OPPO Find X9 Ult", embedded=phone.dcp),
                                     SIDECAR % "Adobe Standard")
            gain = args.gain or solve_gain(work, make_job, 0.55, BASIC_GREY_TARGET)
            table = render(work, {"basic_standard": make_job(gain)})["basic_standard"]
            write_look(LOOKS_DIR / "basic_standard.bin", [table], [6504.0])
            print("basic_standard: gain %.4f, grey 18 %% -> display %.3f" % (gain, grey_y(table)))
            continue
        brand = BRANDS[name]
        stems = [f"adobe_{name}_{i}" for i in brand["looks"]]
        tables = {stem: [] for stem in stems}
        if name == "nikon" and not args.gain:      # the exposure is solved once, at D65
            std = Standard(brand["standard"], BAKE_CCT[-1])
            a = std.inverse(scene)
            brand["gain"] = solve_gain(work, lambda gn: (synthetic_dng(a, std, gn, brand["make"], brand["model"], brand["unique"]),
                                                          sidecar(brand["looks"]["standard"])), brand["gain"], GREY_TARGET)
        for temperature in BAKE_CCT:
            std = Standard(brand["standard"], temperature)
            a = std.inverse(scene)
            residual = np.linalg.norm(std.forward(a) - np.maximum(scene, 0), axis=-1) / np.maximum(np.linalg.norm(scene, axis=-1), 1e-6)
            print("%s %.0f K: HueSatMap inverse, relative residual median %.2g max %.2g" % (name, temperature, np.median(residual), residual.max()))
            job = lambda gain, look: (synthetic_dng(a, std, gain, brand["make"], brand["model"], brand["unique"]), sidecar(look))
            gain = args.gain or brand["gain"]
            rendered = render(work, {f"{stem}": job(gain, look) for stem, look in zip(stems, brand["looks"].values())})
            for stem, table in rendered.items():
                tables[stem].append(table)
        for stem in stems:
            write_look(LOOKS_DIR / (stem + ".bin"), tables[stem], BAKE_CCT)
            print("%-30s grey 18 %% -> display %.3f (D65)" % (stem, grey_y(tables[stem][-1])))


if __name__ == "__main__":
    main()
