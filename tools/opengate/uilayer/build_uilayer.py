"""Build module/payload/og/camera.oemlayer.uilayer.so: the stock UI layer with the OpenGate blob.

The blob (og_ui_blob.c/.S) goes into the zero slack after the executable segment; two stock BLs
are redirected to it; the executable LOAD is extended over the blob and the data LOAD that ends in
.bss is widened to cover og_state (zero-filled by the loader). Needs the Android NDK (ANDROID_NDK
or the default SDK location).
"""
from __future__ import annotations

import os
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
PROJECT = HERE.parents[2]
sys.path.insert(0, str(HERE.parents[1]))
import refs  # noqa: E402

STOCK = refs.STOCK_ODM / "odm/lib64/camera.oemlayer.uilayer.so"
STOCK_SHA1 = "4932187bebff870204e3468bd93220c3381c95ed"
OUT = PROJECT / "module/payload/og/camera.oemlayer.uilayer.so"
NDK = Path(os.environ.get("ANDROID_NDK", Path.home() / "AppData/Local/Android/Sdk/ndk/29.0.14206865"))
BIN = NDK / "toolchains/llvm/prebuilt/windows-x86_64/bin"

CAVE = 0x290F0        # zero slack after the executable segment
STATE = 0x31100       # og_state[8], past .bss inside the last data page
PLT_PARSE = 0x28D68   # ParseSessionParams stream parser
PLT_STD = 0x28E28     # caps bitset standardization
SITE_CAPTURE = 0x1AA6C
SITE_SCOPE = 0x209F8


def bl(src: int, dst: int) -> bytes:
    off = (dst - src) // 4
    assert -(1 << 25) <= off < (1 << 25) and (dst - src) % 4 == 0
    return struct.pack("<I", 0x94000000 | (off & 0x3FFFFFF))


def build() -> None:
    import hashlib
    data = bytearray(STOCK.read_bytes())
    assert hashlib.sha1(data).hexdigest() == STOCK_SHA1, "stock UI layer differs from the analysed one"
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        objs = []
        for src, flags in (("og_ui_blob.c", ["-O2", "-ffreestanding", "-fno-stack-protector", "-fno-pic",
                                              "-mcmodel=small", "-fno-builtin", "-mbranch-protection=none"]),
                           ("og_ui_blob.S", [])):
            obj = tmp / (src + ".o")
            subprocess.run([str(BIN / "clang.exe"), "--target=aarch64-linux-android31", *flags, "-c",
                            str(HERE / src), "-o", str(obj)], check=True)
            objs.append(str(obj))
        elf = tmp / "blob.elf"
        subprocess.run([str(BIN / "ld.lld.exe"), "-nostdlib", f"-Ttext=0x{CAVE:x}", f"-Tbss=0x{STATE:x}",
                        f"--defsym=native_parse_streams=0x{PLT_PARSE:x}", f"--defsym=native_standardize=0x{PLT_STD:x}",
                        f"--defsym=og_state=0x{STATE:x}", "-o", str(elf), *objs], check=True)
        raw = tmp / "blob.bin"
        subprocess.run([str(BIN / "llvm-objcopy.exe"), "-O", "binary", "--only-section=.text", str(elf), str(raw)], check=True)
        nm = subprocess.run([str(BIN / "llvm-nm.exe"), str(elf)], capture_output=True, text=True, check=True).stdout
        sym = {l.split()[2]: int(l.split()[0], 16) for l in nm.splitlines() if len(l.split()) == 3}
        blob = raw.read_bytes()
    assert set(data[CAVE:CAVE + len(blob)]) == {0} and CAVE + len(blob) < 0x2C000
    assert data[SITE_CAPTURE:SITE_CAPTURE + 4] == bl(SITE_CAPTURE, PLT_PARSE)
    assert data[SITE_SCOPE:SITE_SCOPE + 4] == bl(SITE_SCOPE, PLT_STD)
    data[CAVE:CAVE + len(blob)] = blob
    data[SITE_CAPTURE:SITE_CAPTURE + 4] = bl(SITE_CAPTURE, sym["og_entry_capture"])
    data[SITE_SCOPE:SITE_SCOPE + 4] = bl(SITE_SCOPE, sym["og_entry_scope"])
    phoff = struct.unpack_from("<Q", data, 0x20)[0]
    phentsize, phnum = struct.unpack_from("<HH", data, 0x36)
    patched = set()
    for i in range(phnum):
        o = phoff + i * phentsize
        typ, flags, _, va, _, _, ms, _ = struct.unpack_from("<IIQQQQQQ", data, o)
        if typ == 1 and flags == 5 and va == 0x10000:
            end = (CAVE + len(blob) + 15) & ~15
            assert end > va + ms
            struct.pack_into("<QQ", data, o + 32, end - va, end - va)
            patched.add("exec")
        if typ == 1 and va == 0x30000:
            assert ms == 0x1040 and STATE + 32 <= 0x32000
            struct.pack_into("<Q", data, o + 40, STATE + 32 - va)
            patched.add("data")
    assert patched == {"exec", "data"}
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_bytes(data)
    print(f"blob {len(blob)} bytes at 0x{CAVE:x}, {hashlib.sha1(data).hexdigest()[:8]} {OUT.relative_to(PROJECT)}")


if __name__ == "__main__":
    build()
