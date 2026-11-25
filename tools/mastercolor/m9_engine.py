"""Leica M9 (firmware 1.216) colour engine, kind 10: extractor, packer and reference renderer.

The M9's BF561 colour kernel (ASMExecuteColorMatrix), per pixel on the white-balanced 14-bit camera RGB:
  1. clamp >= 0; pick the matrix: G <= R uses the illuminant-A matrix, otherwise the D65 matrix (the two agree on R = G, so
     the map is continuous; there is no colour-temperature interpolation)
  2. int16 matrix (1.0 = 8192) = saturation matrix . XYZ->sRGB(D50) . forward matrix, one step: value = sum(coef * pixel14)
  3. index = floor(value / 65536) clamped to [0, 2047]  (linear 1.0 = 14-bit full scale = index 2048)
  4. one 2048-entry 8-bit tone curve for all three channels (sRGB encoded); 5 contrast steps
The scene (ProPhoto D50, 18 % grey = 0.18) reaches the kernel through the framework's transfer C = G(F^-1(B)): the M9 Adobe Standard
HueSatMap is inverted to the colorimetric scene a, and the Adobe forward matrix (interpolated in 1/T) turns a into the camera RGB.

Binary layout (kind = 10):
  8B  "MCOLLOOK", u32 version = 1, u32 kind = 10
  f32 input_scale   (camera scale: 18 % grey -> sRGB 118/255 at contrast 0 / saturation 0)
  f32[9] forward matrix 1 (Adobe Standard, illuminant 2856 K), f32[9] forward matrix 2 (6504 K): XYZ D50 = FM . camera
  i32[5 * 2 * 9]  firmware matrix records, saturation step major, then (A, D65), row major, 1.0 = 8192
  u8[5 * 2048]    contrast curves (sRGB encoded), contrast step major
  u32 hue divisions, u32 sat divisions, f32 t1, f32 t2, f32 table1[hue][sat][3], f32 table2   (M9 Adobe Standard HueSatMap)
"""
import struct
import sys
from pathlib import Path

import numpy as np

PERIOD = 1021          # the .upd is XORed with a 1021-byte repeating key
LUTS_BLOB_SIZE = 427744
GREY_SRGB = 118.0 / 255.0   # every brand's standard look puts the 18 % grey node here (X-T5 Provia, Lumix)


def _recover_key(data):
    key = bytearray(PERIOD)
    for r in range(PERIOD):
        key[r] = np.bincount(np.frombuffer(data[r::PERIOD], np.uint8), minlength=256).argmax()
    return bytes(key)


def _wad(buf, base):
    if buf[base:base + 4] != b"PWAD":
        raise ValueError("not a PWAD at %#x" % base)
    n, table = struct.unpack_from("<II", buf, base + 4)
    out = {}
    for i in range(n):
        pos, size, name = struct.unpack_from("<II8s", buf, base + table + 16 * i)
        out[name.split(b"\0")[0].decode("latin1")] = (base + pos, size)
    return out


def read_luts(upd):
    """The PROCESS/LUTS lump of an M8/M9 .upd firmware file."""
    raw = Path(upd).read_bytes()
    key = np.frombuffer(_recover_key(raw), np.uint8)
    dec = np.frombuffer(raw, np.uint8) ^ np.resize(key, len(raw))
    dec = dec.tobytes()
    luts = _wad(dec, 0)["LUTS"]
    process = _wad(dec, luts[0])["PROCESS"]
    off, size = _wad(dec, process[0])["LUTS"]
    blob = dec[off:off + size]
    assert len(blob) == LUTS_BLOB_SIZE, len(blob)
    return blob


def firmware_params(blob):
    """Matrix records and contrast curves of the sRGB, colour group."""
    records = np.frombuffer(blob, "<i2", 5 * 2 * 9, 0xB44).reshape(5, 2, 9).astype(np.int32)
    curves = np.array([np.frombuffer(blob, np.uint8, 2048, 0xCF8 + 2048 * c) for c in range(5)])
    return records, curves


def _adobe_dcp(path):
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
    import refs

    sys.path.insert(0, str(refs.DCP_RENDER))
    import dcp_render as D

    d = D.read_dcp(path)
    hd, sd, vd = (int(x) for x in d[50937])
    assert vd == 1
    t1, t2 = D.ILLUM_T[int(d[50778][0])], D.ILLUM_T[int(d[50779][0])]
    fm = [D.rat(d[k]).reshape(3, 3) for k in (50964, 50965)]
    tables = [np.asarray(d[k], "<f4").reshape(hd, sd, 3) for k in (50938, 50939)]
    return hd, sd, t1, t2, fm, tables


def input_scale(curves):
    """Camera scale that puts the 18 % grey at sRGB 118/255 on the standard contrast step (index 2)."""
    c = curves[2].astype(np.float64) / 255.0
    first = int(np.argmax(c >= GREY_SRGB - 1e-9))
    last = int(len(c) - 1 - np.argmax(c[::-1] <= GREY_SRGB + 1e-9))
    x = (0.5 * (first + last) + 0.5) / 2048.0
    return float(x / 0.18)


def pack(upd, dcp, out):
    blob = read_luts(upd)
    records, curves = firmware_params(blob)
    hd, sd, t1, t2, fm, tables = _adobe_dcp(dcp)
    b = b"MCOLLOOK" + struct.pack("<IIf", 1, 10, input_scale(curves))
    b += b"".join(m.astype("<f4").tobytes() for m in fm)
    b += records.astype("<i4").tobytes() + curves.tobytes()
    b += struct.pack("<IIff", hd, sd, t1, t2) + tables[0].tobytes() + tables[1].tobytes()
    Path(out).write_bytes(b)


def decode(b):
    at = 16
    scale = struct.unpack_from("<f", b, at)[0]; at += 4
    fm = np.frombuffer(b, "<f4", 18, at).astype(np.float64).reshape(2, 3, 3); at += 72
    records = np.frombuffer(b, "<i4", 90, at).reshape(5, 2, 3, 3).astype(np.int64); at += 360
    curves = np.frombuffer(b, np.uint8, 5 * 2048, at).reshape(5, 2048).astype(np.float64) / 255.0; at += 5 * 2048
    hd, sd, t1, t2 = struct.unpack_from("<IIff", b, at); at += 16
    n = hd * sd * 3
    t = [np.frombuffer(b, "<f4", n, at + 4 * n * i).astype(np.float64).reshape(1, hd, sd, 3) for i in range(2)]
    assert at + 8 * n == len(b)
    return dict(scale=scale, fm=fm, records=records, curves=curves, hsm=dict(hd=hd, sd=sd, t1=t1, t2=t2, table1=t[0], table2=t[1]))


def _weight(cct, t1, t2):
    return float(np.clip((1.0 / cct - 1.0 / t1) / (1.0 / t2 - 1.0 / t1), 0.0, 1.0))


def render(p, pp, cct, contrast=0, saturation=0):
    """pp: linear ProPhoto D50 with the 18 % grey at 0.18 (n, 3). contrast / saturation: -2..2 (low..high). Returns linear P3."""
    import looks as L
    import x100vi_engine as X

    h = p["hsm"]
    w = _weight(cct, h["t1"], h["t2"])
    a = X.inverse_hue_sat(np.asarray(pp, np.float64), h["table1"] * (1.0 - w) + h["table2"] * w)
    xyz = a @ L.PROPHOTO_TO_XYZ.T
    fm = p["fm"][0] * (1.0 - w) + p["fm"][1] * w
    cam = (xyz @ np.linalg.inv(fm).T) * p["scale"]
    px = np.floor(np.clip(cam, 0.0, 1.0) * 16383).astype(np.int64)
    rec = p["records"][int(np.clip(round(saturation), -2, 2)) + 2]
    first = (px[:, 0] >= px[:, 1])[:, None]
    value = np.where(first, px @ rec[0].T, px @ rec[1].T)
    index = np.clip(np.floor(value / 65536.0).astype(np.int64), 0, 2047)
    encoded = p["curves"][int(np.clip(round(contrast), -2, 2)) + 2][index]
    lin = np.where(encoded <= 0.04045, encoded / 12.92, ((encoded + 0.055) / 1.055) ** 2.4)
    return np.clip(lin @ L.SRGB_TO_P3.T, 0.0, 1.0)


if __name__ == "__main__":
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
    import refs

    pack(refs.M9_UPD, refs.ADOBE_CAMERA_RAW / "CameraProfiles/Adobe Standard/M9 Digital Camera Adobe Standard.dcp", sys.argv[1])
