"""Tilt-shift photos take the plain MFNR chain: the four decision files shipped in module/payload.

The models that ship tilt-shift keep it out of the TurboHDR / HybridRaw families and let it fall to the
plain MFNR chain (ISP-rendered NV12, no HDR-referred intermediate). Here the same is done with one
prerequisite placed after PREREQUISITES_PRIVACY_NOLINT: a tilt-shift photo goes straight to ALGO_MFNR.
BasicTone is no longer needed to turn a 10-bit buffer into 8 bit, so its tilt-shift branch
(SINGLE_ALGO_BASIC_TONE, REAR_NORMAL with tiltshift_open) is removed.

The tilt-shift mode also has to ask for the use case that carries the capture_yuv streams
(hook/tilt/TiltShiftCapture).
"""
from __future__ import annotations

import json
import re
from pathlib import Path

PROJECT = Path(__file__).resolve().parent.parent
PAYLOAD = PROJECT / "module/payload"
DECISION = "oplus_camera_preview_decision_config.json"
FILES = [
    PAYLOAD / DECISION,
    PAYLOAD / "unlock25mp" / DECISION,
    PAYLOAD / "face200m" / DECISION,
    PAYLOAD / "face200m/unlock25mp" / DECISION,
]

ENTRY = {
    "mode": "PREREQUISITES_TILT_SHIFT_MFNR",
    "condition": {"and": {
        "eq": ["captureMode", "APS_CAPMODE_REAR_NORMAL"],
        "not_eq": ["paramsHolder->tiltshift_open", 0],
    }},
    "prerequisiteResult": None,
    "nextMode": "PREREQUISITES_MFNR_CHDR_NOLINT",
    "childMode": "ALGO_MFNR",
}

BASIC_TONE_TILT_BRANCH = re.compile(
    r'\s*"and5"\s*:\s*\{\s*"eq"\s*:\s*\[\s*"captureMode",\s*"APS_CAPMODE_REAR_NORMAL"\s*\],'
    r'\s*"not_eq"\s*:\s*\[\s*"paramsHolder->tiltshift_open",\s*0\s*\]\s*\},')
PRIVACY_TAIL = re.compile(
    r'("mode"\s*:\s*"PREREQUISITES_PRIVACY_NOLINT".*?"nextMode"\s*:\s*")PREREQUISITES_MFNR_CHDR_NOLINT'
    r'(",\s*"childMode"\s*:\s*"ALGO_PRIVACY_BK_MFNR"\s*\},)', re.S)


def entry_text(colon: str) -> str:
    """The new node in the file's own style: the shipped base file has no space after the colon, the generated ones do."""
    lines = json.dumps(ENTRY, indent=2, separators=(",", colon)).split("\n")
    return "\n".join("      " + line for line in lines)


def patch(text: str) -> str:
    colon = ": " if '"mode": ' in text else ":"
    start = re.search(r'"mode"\s*:\s*"SINGLE_ALGO_BASIC_TONE"', text).start()
    branch = BASIC_TONE_TILT_BRANCH.search(text, start)
    text = text[:branch.start()] + text[branch.end():]
    return PRIVACY_TAIL.sub(
        lambda m: m.group(1) + ENTRY["mode"] + m.group(2) + "\n" + entry_text(colon) + ",", text, count=1)


if __name__ == "__main__":
    for path in FILES:
        path.write_text(patch(path.read_text(encoding="utf-8")), encoding="utf-8", newline="\n")
        print("tilt-shift MFNR:", path.relative_to(PROJECT))
