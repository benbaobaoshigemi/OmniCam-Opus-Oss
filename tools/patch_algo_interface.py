"""Builds module/payload/algo/libAlgoInterface.so from the stock engine library of the current ROM.

Patches (addresses are file offsets = link addresses of the stock library):
  * POP SDR sliders: the BasicTone gate of RearNormal / FrontNormal addCommonPipelines and the all-zero
    pre-check of basicToneProcessV1 become NOPs (docs/MODULE.md, "POP").
  * Colour palette nodes: the four addCommonPipelines (RearNormal, RearBokeh, FrontBokeh, FrontNormal)
    branch to the routines of algo/palette_nodes.S.
  * Master grain: algo/grain_gate.S, entered from setMesheFilterParam and doFilterProcessV2.
The added code lives in the zero slack after the executable segment, which is extended over it.
Needs the Android NDK (ANDROID_NDK or the default SDK location).

Run: python tools/patch_algo_interface.py
"""
from __future__ import annotations

import hashlib
import os
import struct
import subprocess
import tempfile
from pathlib import Path

import refs

HERE = Path(__file__).resolve().parent
PROJECT = HERE.parent
STOCK = refs.STOCK_ODM / "odm/lib64/libAlgoInterface.so"
STOCK_SHA256 = "e7d6ff3aff5147bb4feb31d31f35a2b0699118949800390fd76f1895442846c6"
OUT = PROJECT / "module/payload/algo/libAlgoInterface.so"
NDK = Path(os.environ.get("ANDROID_NDK", Path.home() / "AppData/Local/Android/Sdk/ndk/29.0.14206865"))
BIN = NDK / "toolchains/llvm/prebuilt/windows-x86_64/bin"

CAVE = 0x28AF000          # zero slack after the executable segment (ends 0x28AEFC0, next segment 0x28B0000)
SEGMENT_END = 0x28B0000
RX_PHDR = 2               # the R E LOAD segment

# (address, stock word, replacement or None for the branch into the cave)
NOP = 0xD503201F
NOPS = [
    (0x2457EDC, 0x36000440),   # RearNormal addCommonPipelines: tbz w0, #0, <skip BasicTone>
    (0x24652A0, 0x36000440),   # FrontNormal addCommonPipelines
    (0x1FE1514, 0x340001E8),   # basicToneProcessV1: cbz w8, <all four values zero>
]
LDR_X8_X22 = 0xF94002C8        # ldr x8, [x22]: first instruction after the gates of each addCommonPipelines
PALETTE_HOOKS = {              # entry label -> address of the displaced instruction
    "entry_rearnormal": 0x2457F7C,
    "entry_rearbokeh": 0x24AB62C,
    "entry_frontbokeh": 0x24E7774,
    "entry_frontnormal": 0x2464F84,
}
PALETTE_SKIP_REAR = 0x2458070  # RearNormal: continue here when the routine reports the palette handled
GRAIN_JOIN = (0x1A67E90, 0x394002E9)    # setMesheFilterParam: ldrb w9, [x23], first instruction after the grain blocks
SKIP_CHECK = (0x1A6AB14, 0x3500094B)    # doFilterProcessV2: cbnz w11, <process>

PALETTE_DEFS = {
    "GOT_IS_ALGO_ENABLED": 0x28CF218,   # JUMP_SLOT of android::isAlgoEnabled(AlgoProcessData*, AlgoType)
    "FN_META_BOOL": 0x15395A0,          # metadata bool getter used by the stock gates
    "FN_ADD_DEFINITE": 0x208A998,       # ApsCapDecisionMode::addDefinitePipeline
}
GRAIN_DEFS = {
    "GRAIN_AMOUNT_SLOT": 0x50,          # setMesheFilterParam: filter_grain_amount float
    "GRAIN_FRAME_BASE": 0x120,          # x22 = sp + 0x120 before the grain blocks
    "SKIP_STRING_SLOT": 0x318,          # doFilterProcessV2: scratch std::string of the retro_filter_intensity read
}
GRAIN_SYMS = {
    "grain_join_next": GRAIN_JOIN[0] + 4,
    "grain_block": 0x1A6769C,           # grain block entry that loads w24 = 0x8110
    "skip_after": SKIP_CHECK[0] + 8,
    "skip_process": 0x1A6AC3C,
    "skip_read_float": 0x1543394,       # metadata float getter
    "key_grain_amount": 0x1F7B3C,       # "filter_grain_amount"
}


def branch(src: int, dst: int) -> int:
    assert (dst - src) % 4 == 0 and -(1 << 27) <= dst - src < (1 << 27)
    return 0x14000000 | (((dst - src) >> 2) & 0x3FFFFFF)


def run(args: list) -> str:
    done = subprocess.run([str(a) for a in args], capture_output=True, text=True)
    if done.returncode:
        raise SystemExit(done.stderr)
    return done.stdout


def build_cave() -> tuple[bytes, dict[str, int]]:
    entry_offsets = {"entry_rearnormal": 0x0, "entry_rearbokeh": 0x460, "entry_frontbokeh": 0x498, "entry_frontnormal": 0x4D0}
    defs = {f"ENTRY{i}": CAVE + off for i, off in enumerate(entry_offsets.values())}
    syms = dict(GRAIN_SYMS)
    syms.update({
        "rear_skip": PALETTE_SKIP_REAR,
        "rear_cont": PALETTE_HOOKS["entry_rearnormal"] + 4,
        "rearbokeh_cont": PALETTE_HOOKS["entry_rearbokeh"] + 4,
        "frontbokeh_cont": PALETTE_HOOKS["entry_frontbokeh"] + 4,
        "frontnormal_cont": PALETTE_HOOKS["entry_frontnormal"] + 4,
    })
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        objs = []
        for name, extra in (("palette_nodes", {**defs, **PALETTE_DEFS}), ("grain_gate", GRAIN_DEFS)):
            obj = tmp / (name + ".o")
            run([BIN / "clang.exe", "--target=aarch64-linux-android31", "-c", HERE / "algo" / (name + ".S"), "-o", obj,
                 *[f"-D{k}={v}" for k, v in extra.items()]])
            objs.append(obj)
        elf = tmp / "cave.elf"
        run([BIN / "ld.lld.exe", "-nostdlib", "-e", "0", f"-Ttext=0x{CAVE:x}",
             *[f"--defsym={k}=0x{v:x}" for k, v in syms.items()], "-o", elf, *objs])
        raw = tmp / "cave.bin"
        run([BIN / "llvm-objcopy.exe", "-O", "binary", "--only-section=.text", elf, raw])
        nm = run([BIN / "llvm-nm.exe", elf])
        sym = {l.split()[2]: int(l.split()[0], 16) for l in nm.splitlines() if len(l.split()) == 3}
        blob = raw.read_bytes()
    for entry, off in entry_offsets.items():
        assert sym[entry] == CAVE + off, (entry, hex(sym[entry]))
    return blob, sym


def main() -> None:
    data = bytearray(STOCK.read_bytes())
    assert hashlib.sha256(data).hexdigest() == STOCK_SHA256, "stock engine library differs from the analysed one"
    word = lambda at: struct.unpack_from("<I", data, at)[0]  # noqa: E731
    put = lambda at, value: struct.pack_into("<I", data, at, value)  # noqa: E731

    blob, sym = build_cave()
    assert CAVE + len(blob) <= SEGMENT_END and not any(data[CAVE:CAVE + len(blob)]), "no free slack for the added code"
    data[CAVE:CAVE + len(blob)] = blob

    for at, original in NOPS:
        assert word(at) == original, hex(at)
        put(at, NOP)
    for entry, at in PALETTE_HOOKS.items():
        assert word(at) == LDR_X8_X22, hex(at)
        put(at, branch(at, sym[entry]))
    for (at, original), target in ((GRAIN_JOIN, "grain_join"), (SKIP_CHECK, "skip_gate")):
        assert word(at) == original, hex(at)
        put(at, branch(at, sym[target]))

    phoff = struct.unpack_from("<Q", data, 0x20)[0]
    o = phoff + RX_PHDR * 56
    typ, flags, _, va, _, filesz, memsz, _ = struct.unpack_from("<IIQQQQQQ", data, o)
    assert typ == 1 and flags == 5 and va + filesz == filesz + 0x150C000 and va + filesz < CAVE
    struct.pack_into("<QQ", data, o + 32, SEGMENT_END - va, SEGMENT_END - va)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_bytes(data)
    print(f"cave {len(blob)} bytes at 0x{CAVE:x}, {hashlib.sha1(data).hexdigest()[:8]} {OUT.relative_to(PROJECT)}")


if __name__ == "__main__":
    main()
