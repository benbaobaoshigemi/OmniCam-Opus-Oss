"""Builds module/payload/algo/libmsnativefilter.so from the stock Meishe library of the current ROM.

The fisheye base layer and the newer ROM's fisheye (docs/MODULE.md, "POP 鱼眼"):
  * 18 embedded QSB shaders (preview, thumbnails, LightFlow) and the GL YUV shader of CompoundLut::RenderEffectYuvGps
    (captures) get a base-layer sampler and the fisheye functions of fisheye_glsl.py / fisheye_qsb.py.
  * algo/fisheye_base.S: hooks that read the layer path ("Fish Eye Layer File Path"), upload the base layer texture
    once per CNvFilterResourceManager, bind it next to the fisheye edge texture, and tell the QSB shaders whether the
    frame scale is 4:3.
Soft focus (algo/soft_focus.S, docs/MODULE.md "POP 鱼眼") moves the capture route of the soft focus effect into the single-effect path.
The added code lives in a new executable segment at the end of the file; .bss grows by one page for its state.
Needs the Android NDK (ANDROID_NDK or the default SDK location) and `pip install zopfli`.

Run: python tools/patch_msnativefilter.py   (from any directory, with the Python that has zopfli)
"""
import hashlib
import os
import re
import struct
import subprocess
import sys
import tempfile
import zlib
from pathlib import Path

import zopfli.zlib

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE / "algo"))
import refs  # noqa: E402
from fisheye_glsl import (  # noqa: E402
    CALC_COORDS_GLSL,
    EDGE_BLEND_GLSL,
    QSB_EDGE_BLEND_GLSL,
    YUV_DECL,
    YUV_DECL_NEW,
    YUV_REPLACEMENTS,
)
from fisheye_qsb import patch_qsb_main  # noqa: E402

PROJECT = HERE.parent
PHONE_SO = refs.PULLED_LIBS / "libmsnativefilter-pma110.so"
STOCK_MD5 = "008b8ed9cc761f08574672c83f653ed8"
ASM_SRCS = [HERE / "algo" / "fisheye_base.S", HERE / "algo" / "soft_focus.S", HERE / "algo" / "lightflow_vertical.S"]
OUT_SO = PROJECT / "module/payload/algo/libmsnativefilter.so"
NDK = Path(os.environ.get("ANDROID_NDK", Path.home() / "AppData/Local/Android/Sdk/ndk/29.0.14206865"))
NDK_BIN = str(NDK / "toolchains/llvm/prebuilt/windows-x86_64/bin")

PHONE_QSBS = [
    0x178c3d, 0x17d561, 0x1806a0, 0x1934d5, 0x19a285, 0x1a13b0,
    0x1a9290, 0x1aeb27, 0x1b616d, 0x1c034a, 0x1c501c, 0x1c9ee2,
    0x1cf5ce, 0x1d38e2, 0x1d8adb, 0x1dc653, 0x1e6a40, 0x1ec367,
]

NEW_CAVE_OFFSET = 0x00A80000
NEW_CAVE_VA = 0x010C0000
PHDR_SIZE = 56
NEW_PHNUM = 10
CODE_VA = ((NEW_CAVE_VA + NEW_PHNUM * PHDR_SIZE) + 0x7F) & ~0x7F  # 0x10c0280

GLSL_PAT = re.compile(
    r"if \(length\(\(vTexCoord - vec2\(0\.5\)\) \* (_\w+)\) > 0\.5\)\s*\{\s*"
    r"(_\w+) = vec4\(0\.0, 0\.0, 0\.0, 1\.0\);\s*\}\s*else\s*\{\s*"
    r"\2 = vec4\((_\w+)\.x, \3\.y, \3\.z, (_\w+)\.w\);\s*\}"
)


def replace_glsl_function(text: str, header: str, new_def: str) -> str:
    start = text.index(header)
    assert text.count(header) == 1
    end = text.index("\n}\n", start) + 3
    return text[:start] + new_def + text[end:]




def patch_qsb_blob(dec: bytes) -> bytes:
    assert dec[0x107d:0x107f] == b"\x0a\x00"
    assert dec[0x11c2:0x11e8] == (
        b"\x12\x00\x00\x00fishEyeEdgeSampler\x28\x00\xff\xff\x0b\x00\x00\x00"
        + b"\x00" * 8
    )
    h_idx = dec.find(b"#version")
    glsl_len = struct.unpack("<I", dec[h_idx - 4 : h_idx])[0]
    glsl = dec[h_idx : h_idx + glsl_len].decode("utf-8")
    trailer = dec[h_idx + glsl_len :]

    # newer ROM's fisheye: the call of the proMist layer reads the coordinates without the rim mirror (needReflect 0)
    calls = list(re.finditer(r"fishEyeCalcCoords\((\w+), (\w+)\);", glsl))
    assert len(calls) == 2 and "proMistLayerflipY" in glsl[calls[1].start() - 400 : calls[1].start()]
    glsl = glsl[: calls[1].start()] + f"fishEyeCalcCoordsR({calls[1].group(1)}, {calls[1].group(2)}, 0);" + glsl[calls[1].end() :]
    glsl = replace_glsl_function(glsl, "vec2 fishEyeCalcCoords(vec2 v_texCoord, mediump vec2 _textureSize)\n{", CALC_COORDS_GLSL)
    glsl = replace_glsl_function(glsl, "vec3 fishEyeWithEdge(inout mediump vec3 currentColor, mediump vec3 edgeColor, vec2 texCoord, mediump vec2 _textureSize)\n{", QSB_EDGE_BLEND_GLSL)

    glsl = patch_qsb_main(glsl)

    target_decl = "uniform mediump sampler2D fishEyeEdgeSampler;\n"
    assert target_decl in glsl
    glsl = glsl.replace(
        target_decl,
        target_decl + "uniform mediump sampler2D fishEyeBlackSampler;\n",
        1,
    )

    m = GLSL_PAT.search(glsl)
    assert m, "Pattern not found in GLSL"
    v_scale, v_out, v_edge, v_cur = m.groups()
    has_tm = "toneMapFwd(" in glsl

    block = [
        "mediump float _e=smoothstep(.5,.501,length((fyTB-vec2(.5))*(fySB.x>fySB.y?vec2(fySB.x/fySB.y,1.):vec2(1.,fySB.y/fySB.x))));",
        f"vec3 _b=mix({v_edge}.xyz,vec3(0.),_e);",
        "vec2 _u=(fyTB-vec2(.5))*(fySB.x>fySB.y?vec2(fySB.x/fySB.y*.75,1.):vec2(.75,fySB.y/fySB.x))+.5;",
        "mediump vec4 _m=texture2D(fishEyeBlackSampler,_u);",
    ]
    if has_tm:
        block += [
            "vec3 _r;",
            "if(ubuf.hdrTransfer==1){_r=toneMapBwd(mix(toneMapFwd(max(_b,vec3(1e-6)),.4,.75,true),_m.rgb,_m.a),.4,.75,true);}",
            "else{_r=mix(_b,_m.rgb,_m.a);}",
            f"{v_out}=vec4(_r,mix({v_cur}.w,1.,_e));",
        ]
    else:
        block += [
            f"vec3 _r=mix(_b,_m.rgb,_m.a);",
            f"{v_out}=vec4(_r,mix({v_cur}.w,1.,_e));",
        ]

    glsl = glsl[: m.start()] + "\n".join(block) + glsl[m.end() :]
    glsl = re.sub(r"(?m)^[ \t]+", "", glsl)
    glsl_bytes = glsl.encode("utf-8")

    black_entry = (
        b"\x13\x00\x00\x00fishEyeBlackSampler\x28\x00\xff\xff\x0c\x00\x00\x00"
        + b"\x00" * 8
    )
    hdr = bytearray(dec[:h_idx])
    hdr[0x107d:0x107f] = b"\x0b\x00"
    struct.pack_into("<I", hdr, h_idx - 4, len(glsl_bytes))
    hdr = bytes(hdr[:0x11e8]) + black_entry + bytes(hdr[0x11e8:])
    return hdr + glsl_bytes + trailer


YUV_GPS_BLOB = 0x6B3EE3   # plain GLSL of CNvCompoundLut::RenderEffectYuvGps (COMPOUND_YUV_FISHEYE variant)
# aspectCorrectedUV is the centred image coordinate scaled so the circle has radius 0.5; the base layer is 4:3



# The newer ROM's fisheye (rear ultra-wide values: focalLength below 1.6, type 0). Same call shape as before; the edge blend,
# the radial mirror at the rim and the 0.97 anisotropy come from that ROM's fishEyeCalcCoords / fishEyeWithEdge.
CALC_COORDS_BLOB = 0x6B3B2C
EDGE_BLEND_BLOB = 0x6B3D7D


def write_text_blob(raw, va, text):
    comp_plus4, uncomp_len = struct.unpack(">II", raw[va - 8 : va])
    slot = comp_plus4 - 4
    old = zlib.decompress(bytes(raw[va : va + slot]))
    assert len(old) == uncomp_len
    data = text.encode("utf-8")
    comp = zopfli.zlib.compress(data, numiterations=100)
    assert zlib.decompress(comp) == data and len(comp) <= slot, (hex(va), len(comp), slot)
    struct.pack_into(">II", raw, va - 8, len(comp) + 4, len(data))
    raw[va : va + slot] = comp + b"\x00" * (slot - len(comp))
    return slot, len(comp)


def patch_yuv_gps_glsl(text: str) -> str:
    assert text.count(YUV_DECL) == 1
    text = text.replace(YUV_DECL, YUV_DECL_NEW, 1)
    for old, new in YUV_REPLACEMENTS:
        assert text.count(old) == 1, old
        text = text.replace(old, new, 1)
    # LightFlow adds its leaks with a screen blend that assumes a [0, 1] frame. Ultra HDR frames (isSDR == 0) go past 1, so the
    # blend runs in the tone-mapped range, the way this shader already wraps its layer blends.
    old = "mediump vec3 retColor = 1.0 - (1.0 - currentColor.rgb) * (1.0 - combineLeak);"
    assert text.count(old) == 1
    text = text.replace(
        old,
        "mediump vec3 leakBase = currentColor.rgb;\n"
        "if (isSDR == 0)\n"
        "leakBase = toneMapFwd(leakBase, 1.0f / 2.5f, 0.75f, true);\n"
        "mediump vec3 retColor = 1.0 - (1.0 - leakBase) * (1.0 - combineLeak);\n"
        "if (isSDR == 0)\n"
        "retColor = toneMapBwd(retColor, 1.0f / 2.5f, 0.75f, true);",
        1,
    )
    return text


def build_cave():
    with tempfile.TemporaryDirectory() as tmp:
        obj_ps = []
        for k, src in enumerate(ASM_SRCS):
            obj_p = os.path.join(tmp, f"cave{k}.o")
            subprocess.run(
                [os.path.join(NDK_BIN, "clang.exe"), "--target=aarch64-linux-android29", "-c", str(src), "-o", obj_p],
                check=True,
            )
            obj_ps.append(obj_p)
        elf_p = os.path.join(tmp, "cave.elf")
        bin_p = os.path.join(tmp, "cave.bin")
        defs = {
            "g_state": 0x10A8800,
            "fn_upload_layer": 0x79A390,
            "fn_obj_release": 0x9C02D8,
            "fn_desc_write": 0x831DEC,
            "fn_get_dummy_tex": 0x781FD4,
            "ret_setstr_hit": 0x76263C,
            "ret_setstr_miss": 0x762604,
            "ret_render_desc": 0x780ADC,
            "ret_dtor_body": 0x79765C,
            "ret_relres_body": 0x7979E0,
            "fn_set_uniform": 0xA579A0,
            "fn_gl_active": 0xA59180,
            "fn_gl_bind": 0xA57CF0,
            "fn_gl_param": 0xA59190,
            "ret_yuv_uniform": 0x78CB70,
            "ret_yuv_bind": 0x789E48,
            "ret_yuv_prep": 0x788D40,
            "ret_fish_flag": 0x78237C,
            # soft_focus.S
            "plt_alloc": 0xA584E0,
            "plt_copy_init": 0xA58690,
            "plt_ascii": 0xA57D40,
            "plt_copy": 0xA588D0,
            "plt_conv_blur": 0xA58560,
            "plt_blur": 0xA58570,
            "plt_blur_dtor": 0xA586D0,
            "plt_copy_dtor": 0xA586B0,
            "plt_sp_dtor": 0xA58510,
            "plt_compound": 0xA58940,
            "plt_set_origin": 0xA58AE0,
            "fn_release": 0x9C02D8,
            "c_copy_d": 0x114E10,
            "c_blur_d": 0x114D90,
            "str_copy": 0xF6F60,
            "ret_sf_entry": 0x76CCB4,
            "ret_sf_ccd": 0x76CE20,
            "ret_sf_neutral": 0x76D238,
            "ret_sf_route_800t": 0x76D374,
            "ret_sf_route_pt400": 0x76D404,
            "ret_sf_route_polaroid": 0x76D4E4,
            "lbl_sf_plain": 0x76D884,
            "lbl_sf_only": 0x76D5F0,
            "lbl_sf_done": 0x76D8A0,
            "ret_surface_blur": 0x7A4F60,
            "ret_lf_vertical": 0x78D89C,
            "ret_lf_flip_y": 0x78D834,
            "ret_lf_flip_x": 0x78DAA8,
        }
        subprocess.run(
            [
                os.path.join(NDK_BIN, "ld.lld.exe"),
                "-Ttext",
                hex(CODE_VA),
                *[f"--defsym={k}={hex(v)}" for k, v in defs.items()],
                *obj_ps,
                "-o",
                elf_p,
            ],
            check=True,
        )
        subprocess.run(
            [
                os.path.join(NDK_BIN, "llvm-objcopy.exe"),
                "-O",
                "binary",
                "--only-section=.text",
                elf_p,
                bin_p,
            ],
            check=True,
        )
        nm_out = subprocess.check_output(
            [os.path.join(NDK_BIN, "llvm-nm.exe"), elf_p], text=True
        )
        syms = {}
        for line in nm_out.splitlines():
            parts = line.split()
            if len(parts) >= 3:
                syms[parts[2]] = int(parts[0], 16)
        with open(bin_p, "rb") as f:
            code_bytes = f.read()
    return code_bytes, syms


def encode_b(src_va: int, dst_va: int) -> bytes:
    diff = dst_va - src_va
    assert (diff & 3) == 0 and -(1 << 27) <= diff < (1 << 27)
    return struct.pack("<I", 0x14000000 | ((diff >> 2) & 0x03FFFFFF))


def encode_bl(src_va: int, dst_va: int) -> bytes:
    diff = dst_va - src_va
    assert (diff & 3) == 0 and -(1 << 27) <= diff < (1 << 27)
    return struct.pack("<I", 0x94000000 | ((diff >> 2) & 0x03FFFFFF))


# (address, stock instruction word, hook symbol, branch kind): soft focus inside the single-effect hardware buffer route
SOFT_SITES = [
    (0x76CCB0, "a82643a9", "hook_sf_entry", "b"),
    (0x76CE1C, "08008852", "hook_sf_ccd", "b"),
    (0x76D234, "08008852", "hook_sf_neutral", "b"),
    (0x76D370, "0801092a", "hook_sf_route_800t", "b"),
    (0x76D400, "0801092a", "hook_sf_route_pt400", "b"),
    (0x76D4E0, "29010a2a", "hook_sf_route_polaroid", "b"),
    (0x76D4C8, "1ead0b94", "soft_compound", "bl"),
    (0x76D5E0, "d8ac0b94", "soft_compound", "bl"),
    (0x76D87C, "31ac0b94", "soft_compound", "bl"),
    (0x76D5E8, "08914239", "hook_sf_default", "b"),
    (0x7A4F5C, "ff8305d1", "hook_surface_blur", "b"),
]


def main():
    OUT_SO.parent.mkdir(parents=True, exist_ok=True)
    raw = bytearray(PHONE_SO.read_bytes())
    assert hashlib.md5(raw).hexdigest() == STOCK_MD5, "stock Meishe library differs from the analysed one"

    for idx, off in enumerate(PHONE_QSBS):
        comp_plus4, uncomp_len = struct.unpack(">II", raw[off - 8 : off])
        orig_comp_len = comp_plus4 - 4
        dec = zlib.decompress(bytes(raw[off : off + orig_comp_len]))
        assert len(dec) == uncomp_len
        new_dec = patch_qsb_blob(dec)
        new_comp = zopfli.zlib.compress(new_dec, numiterations=30)
        assert len(new_comp) <= orig_comp_len
        struct.pack_into(">II", raw, off - 8, len(new_comp) + 4, len(new_dec))
        raw[off : off + len(new_comp)] = new_comp
        raw[off + len(new_comp) : off + orig_comp_len] = b"\x00" * (
            orig_comp_len - len(new_comp)
        )
        print(f"QSB {idx:02d}: {orig_comp_len} -> {len(new_comp)} (saved {orig_comp_len - len(new_comp)}B)")

    comp_plus4, uncomp_len = struct.unpack(">II", raw[YUV_GPS_BLOB - 8 : YUV_GPS_BLOB])
    orig_comp_len = comp_plus4 - 4
    dec = zlib.decompress(bytes(raw[YUV_GPS_BLOB : YUV_GPS_BLOB + orig_comp_len]))
    assert len(dec) == uncomp_len
    new_dec = patch_yuv_gps_glsl(dec.decode("utf-8")).encode("utf-8")
    new_comp = zopfli.zlib.compress(new_dec, numiterations=100)
    assert zlib.decompress(new_comp) == new_dec and len(new_comp) <= orig_comp_len, (len(new_comp), orig_comp_len)
    struct.pack_into(">II", raw, YUV_GPS_BLOB - 8, len(new_comp) + 4, len(new_dec))
    raw[YUV_GPS_BLOB : YUV_GPS_BLOB + orig_comp_len] = new_comp + b"\x00" * (orig_comp_len - len(new_comp))
    print(f"YUV GPS GLSL: {orig_comp_len} -> {len(new_comp)}")

    print("fishEyeCalcCoords", write_text_blob(raw, CALC_COORDS_BLOB, CALC_COORDS_GLSL))
    print("fishEyeWithEdge", write_text_blob(raw, EDGE_BLEND_BLOB, EDGE_BLEND_GLSL))

    code_bytes, syms = build_cave()
    print(f"Cave size={len(code_bytes)}B at VA 0x{CODE_VA:x}")

    assert raw[0x762600:0x762604] == bytes.fromhex("ff830039")
    assert raw[0x780acc:0x780ad0] == bytes.fromhex("e36240f9")
    assert raw[0x780aec:0x780af0] == bytes.fromhex("82018052")
    assert raw[0x78CB6C:0x78CB70] == struct.pack("<I", 0x94000000 | (((0xA579A0 - 0x78CB6C) >> 2) & 0x3FFFFFF))
    assert raw[0x788D3C:0x788D40] == struct.pack("<I", 0x394862A8)
    assert raw[0x788D40:0x788D44] == struct.pack("<I", 0x34000000 | ((((0x789250 - 0x788D40) >> 2) & 0x7FFFF) << 5) | 8)
    # RenderEffectYuvGps: the input is scaled by 0.7 (output width >= 2600) or 0.85 (smaller) before the fisheye pass; the newer ROM
    # scales by 0.85 (>= 2600) or not at all. fmov d2, #1.0 replaces the 0.7 load and the fcsel operands swap.
    assert raw[0x788E68:0x788E6C] == struct.pack("<I", 0xFD400922)
    assert raw[0x788E74:0x788E78] == struct.pack("<I", 0x1E610C41)
    raw[0x788E68:0x788E6C] = struct.pack("<I", 0x1E6E1002)
    raw[0x788E74:0x788E78] = struct.pack("<I", 0x1E620C21)
    assert raw[0x782378:0x78237C] == struct.pack("<I", 0x39486281)   # ldrb w1, [x20, #0x218]
    assert raw[0x789E44:0x789E48] == struct.pack("<I", 0x52800000 | (0x84CB << 5))
    assert raw[0x797658:0x79765c] == bytes.fromhex("fd7bbea9")
    assert raw[0x7979dc:0x7979e0] == bytes.fromhex("fd7bbea9")

    raw[0x762600:0x762604] = encode_b(0x762600, syms["hook_set_string"])
    raw[0x780acc:0x780ad0] = encode_b(0x780acc, syms["hook_bind_base"])
    raw[0x780aec:0x780af0] = struct.pack("<I", 0x528001A2)
    raw[0x78CB6C:0x78CB70] = encode_b(0x78CB6C, syms["hook_yuv_uniform"])
    raw[0x788D3C:0x788D40] = encode_b(0x788D3C, syms["hook_yuv_prep"])
    raw[0x782378:0x78237C] = encode_b(0x782378, syms["hook_fish_flag"])
    raw[0x789E44:0x789E48] = encode_b(0x789E44, syms["hook_yuv_bind"])
    raw[0x797658:0x79765c] = encode_b(0x797658, syms["hook_mgr_dtor"])
    raw[0x7979dc:0x7979e0] = encode_b(0x7979dc, syms["hook_mgr_relres"])

    assert raw[0x78D898:0x78D89C].hex() == "829a4c39"
    raw[0x78D898:0x78D89C] = encode_b(0x78D898, syms["hook_lf_vertical"])
    assert raw[0x78D830:0x78D834].hex() == "82c64b39" and raw[0x78DAA4:0x78DAA8].hex() == "82c24b39"
    raw[0x78D830:0x78D834] = encode_b(0x78D830, syms["hook_lf_flip_y"])
    raw[0x78DAA4:0x78DAA8] = encode_b(0x78DAA4, syms["hook_lf_flip_x"])

    for addr, word, sym, kind in SOFT_SITES:
        assert raw[addr : addr + 4].hex() == word, hex(addr)
        raw[addr : addr + 4] = (encode_b if kind == "b" else encode_bl)(addr, syms[sym])

    e_phoff = struct.unpack_from("<Q", raw, 32)[0]
    e_phentsize, e_phnum = struct.unpack_from("<HH", raw, 54)
    assert e_phoff == 64 and e_phentsize == 56 and e_phnum == 9

    phdrs = [bytearray(raw[e_phoff + i * 56 : e_phoff + (i + 1) * 56]) for i in range(9)]
    phdr_total_bytes = NEW_PHNUM * 56
    struct.pack_into(
        "<QQQQQQ",
        phdrs[0],
        8,
        NEW_CAVE_OFFSET,
        NEW_CAVE_VA,
        NEW_CAVE_VA,
        phdr_total_bytes,
        phdr_total_bytes,
        8,
    )
    p_type, _, _, p_va, _, _, p_memsz, _ = struct.unpack("<IIQQQQQQ", phdrs[3])
    assert p_type == 1 and p_va == 0xA92938 and p_memsz == 0x615DB8
    struct.pack_into("<Q", phdrs[3], 40, 0x616DB8)

    cave_total_bytes = (CODE_VA - NEW_CAVE_VA) + len(code_bytes)
    phdrs.append(
        bytearray(
            struct.pack(
                "<IIQQQQQQ",
                1,
                5,
                NEW_CAVE_OFFSET,
                NEW_CAVE_VA,
                NEW_CAVE_VA,
                cave_total_bytes,
                cave_total_bytes,
                0x10000,
            )
        )
    )

    assert len(raw) <= NEW_CAVE_OFFSET
    raw.extend(b"\x00" * (NEW_CAVE_OFFSET - len(raw)))
    for p in phdrs:
        raw.extend(p)
    raw.extend(b"\x00" * ((CODE_VA - NEW_CAVE_VA) - phdr_total_bytes))
    raw.extend(code_bytes)

    struct.pack_into("<Q", raw, 32, NEW_CAVE_OFFSET)
    struct.pack_into("<H", raw, 56, NEW_PHNUM)

    with open(OUT_SO, "wb") as f:
        f.write(raw)
    print(f"Wrote {OUT_SO} ({len(raw)} bytes)")


if __name__ == "__main__":
    main()
