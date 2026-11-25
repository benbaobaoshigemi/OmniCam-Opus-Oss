"""Builds module/system/odm/lib64/libBasicTonePhoto.so from router.c with the Android NDK.

The NDK is taken from ANDROID_NDK_HOME, else the newest one under the Android SDK (ANDROID_HOME, or the
default SDK location). Usage: python tools/basictone_router/build.py [--output PATH]
"""
from __future__ import annotations

import argparse
import os
import subprocess
from pathlib import Path

HERE = Path(__file__).resolve().parent
PROJECT = HERE.parent.parent
OUTPUT = PROJECT / "module/system/odm/lib64/libBasicTonePhoto.so"
API_LEVEL = 29


def ndk() -> Path:
    if os.environ.get("ANDROID_NDK_HOME"):
        return Path(os.environ["ANDROID_NDK_HOME"])
    sdk = Path(os.environ.get("ANDROID_HOME") or Path(os.environ.get("LOCALAPPDATA", "~")) / "Android/Sdk").expanduser()
    versions = sorted((sdk / "ndk").iterdir(), key=lambda p: [int(x) for x in p.name.split(".")])
    if not versions:
        raise SystemExit("No NDK found; set ANDROID_NDK_HOME")
    return versions[-1]


def clang(root: Path) -> Path:
    for host in ("windows-x86_64", "linux-x86_64", "darwin-x86_64"):
        for name in ("clang.exe", "clang"):
            path = root / "toolchains/llvm/prebuilt" / host / "bin" / name
            if path.is_file():
                return path
    raise SystemExit(f"No clang in {root}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=OUTPUT)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([
        str(clang(ndk())), f"--target=aarch64-linux-android{API_LEVEL}",
        "-std=c11", "-O2", "-fPIC", "-shared", "-fvisibility=hidden", "-mbranch-protection=standard",
        "-Wall", "-Wextra", "-Werror",
        "-Wl,-soname,libBasicTonePhoto.so", "-Wl,-z,now", "-Wl,--pack-dyn-relocs=relr",
        str(HERE / "router.c"), "-ldl", "-llog", "-o", str(args.output),
    ], check=True)
    print(args.output)


if __name__ == "__main__":
    main()
