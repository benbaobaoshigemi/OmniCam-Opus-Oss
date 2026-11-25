"""Vendor files in the module: the vendor's originals, the shipped files and the differences between them.

stock/manifest.json lists every module file that comes from a vendor build: where its original is
(`from`, a source name and the path in that source), the original's hash, the shipped file's hash, and
how one becomes the other. The originals are kept in stock/originals/<source>/<path>, the shipped files
under module/, the differences under stock/diffs/:

  bsdiff  binary difference (bsdiff4), stock/diffs/<out without module/>.bsdiff
  text    unified diff of the text, stock/diffs/<out without module/>.patch; `decrypt` means the original is
          an OPLUS encrypted config and the diff starts from its plaintext (the key comes from the same build's
          /odm/lib64/libOplusSecurity.so, kept with the originals); `reformat` means the original JSON is first
          re-serialized the way the module's scripts write it, so the diff holds only real edits
  rename  the original with one name replaced by another of the same length (`rename`: [old, new])
  copy    the original unchanged; the shipped file is the original, not stored twice
  build   built from this repository's source by `built_by`; no original

`made_by` names the script that produced the file from the original when there is one; the diff is what
reproduces the file, the script is how it was made.

Commands:
  apply  rebuild the module files from stock/originals and the differences, checking every hash
           --only PATTERN    only entries whose `out` contains PATTERN
           --into DIR        write under DIR instead of this repository
  check  verify that each module file is reproduced by its original and difference
  make   after a vendor file changed: copy the originals in from the workspace (tools/refs.py), cut the
         differences against the current module files, refresh the hashes, then check
"""
from __future__ import annotations

import argparse
import difflib
import hashlib
import json
import os
import re
import shutil
import sys
from pathlib import Path

import bsdiff4

PROJECT = Path(__file__).resolve().parent.parent
STOCK = PROJECT / "stock"
MANIFEST = STOCK / "manifest.json"
DIFFS = STOCK / "diffs"
ORIGINALS = STOCK / "originals"
SECURITY_LIB = "/odm/lib64/libOplusSecurity.so"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def load_manifest() -> dict:
    return json.loads(MANIFEST.read_text(encoding="utf-8"))


def save_manifest(manifest: dict) -> None:
    MANIFEST.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")


def split_from(entry: dict) -> tuple[str, str]:
    source, _, path = entry["from"].partition(":")
    return source, path


def original_path(source: str, path: str) -> Path:
    return ORIGINALS / source / path.lstrip("/")


# ---------------------------------------------------------------- originals

class Sources:
    """The original bytes of `source:path`, from stock/originals. With `refresh` (make), each original is
    first copied in from the workspace (tools/refs.py)."""

    def __init__(self, refresh: bool = False):
        self.refresh = refresh
        self.produced: dict[str, bytes] = {}
        self.used: set[Path] = set()

    def read(self, source: str, path: str) -> bytes | None:
        if source == "module":
            # Another module file, as rebuilt earlier in this run or as it stands in the tree.
            if path in self.produced:
                return self.produced[path]
            current = PROJECT / path
            return current.read_bytes() if current.is_file() else None
        stored = original_path(source, path)
        if self.refresh:
            found = local_original(source, path)
            if found is None:
                raise LookupError(f"{source}:{path} not in the workspace")
            stored.parent.mkdir(parents=True, exist_ok=True)
            if not stored.is_file() or stored.read_bytes() != found.read_bytes():
                shutil.copyfile(found, stored)
        self.used.add(stored)
        return stored.read_bytes() if stored.is_file() else None


def ndk_file(path: str) -> Path | None:
    """`path` is <version>/<path inside the NDK>; looks under ANDROID_NDK_ROOT's parent or the SDK's ndk/."""
    version, _, inner = path.lstrip("/").partition("/")
    bases = []
    if os.environ.get("ANDROID_HOME"):
        bases.append(Path(os.environ["ANDROID_HOME"]) / "ndk")
    bases.append(Path(os.environ.get("LOCALAPPDATA", "~")).expanduser() / "Android/Sdk/ndk")
    for base in bases:
        # The NDK names its host directory per platform; the manifest records the Windows one.
        for host in ("windows-x86_64", "linux-x86_64", "darwin-x86_64"):
            candidate = base / version / inner.replace("windows-x86_64", host)
            if candidate.is_file():
                return candidate
    return None


def local_original(source: str, path: str) -> Path | None:
    """The workspace's copy of an original (tools/refs.py), for `make` and `check`."""
    sys.path.insert(0, str(PROJECT / "tools"))
    import refs  # noqa: E402
    rel = path.lstrip("/")
    candidates = {
        "pma110": [refs.STOCK_ODM / rel, refs.PULLED_LIBS / Path(rel).name],
        # The workspace keeps the X10's /odm/etc/camera as x10_pmw110/camera and its libraries pulled by name.
        "pmw110": [refs.X10_ODM / rel.replace("odm/etc/", "", 1), refs.PULLED_LIBS / ("x10_" + Path(rel).name)],
        "rmx5200": [refs.RMX5200 / rel],
        "cph2745": [refs.CPH2745 / rel],
        "x8u": [refs.ROM / "x8u" / rel],
        "xiaomi17u": [refs.XIAOMI_17U / rel],
    }.get(source, [])
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    if source == "ndk":
        return ndk_file(path)
    return None


# ---------------------------------------------------------------- OPLUS encrypted configs

def config_key(sources: Sources) -> bytes:
    security = sources.read("pma110", SECURITY_LIB)
    if security is None:
        raise LookupError(f"pma110:{SECURITY_LIB} (the config key)")
    return bytes(value ^ 0x3F for value in security[0x7AB:0x7AB + 32])[::2]


def decrypt(data: bytes, sources: Sources) -> bytes:
    if data[:2] != b"\x01\x01":
        return data
    from Crypto.Cipher import AES
    from Crypto.Util.Padding import unpad
    if data[-1] != 0xFF:
        raise ValueError("unexpected encrypted config envelope")
    return unpad(AES.new(config_key(sources), AES.MODE_ECB).decrypt(data[4:-4]), 16)


# ---------------------------------------------------------------- text differences

NO_NEWLINE = "\\ No newline at end of file\n"


def text_lines(data: bytes | str) -> list[str]:
    """Lines split at \\n only: the configs carry raw control characters that str.splitlines would cut at."""
    text = data.decode("utf-8", "surrogateescape") if isinstance(data, bytes) else data
    return re.split(r"(?<=\n)", text)[:-1] + ([] if text.endswith("\n") or not text else [text.rsplit("\n", 1)[-1]])


def strip_json_comments(text: str) -> str:
    """Drops // and /* */ comments outside strings; the vendor configs annotate values this way."""
    out, i, n = [], 0, len(text)
    while i < n:
        c = text[i]
        if c == '"':
            j = i + 1
            while j < n and text[j] != '"':
                j += 2 if text[j] == "\\" else 1
            out.append(text[i:j + 1])
            i = j + 1
        elif text.startswith("//", i):
            while i < n and text[i] != "\n":
                i += 1
        elif text.startswith("/*", i):
            i = text.index("*/", i + 2) + 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def reformat(data: bytes, spec: dict | None) -> bytes:
    """The original re-serialized the way the module's scripts write JSON, so the diff holds only real edits."""
    if not spec:
        return data
    tree = json.loads(strip_json_comments(data.decode("utf-8", "surrogateescape")), strict=False)
    text = json.dumps(tree, ensure_ascii=False, indent=spec["indent"]) + ("\n" if spec["newline"] else "")
    return text.encode("utf-8", "surrogateescape")


def cut_text(old: bytes, new: bytes, label: str) -> str:
    out = []
    for line in difflib.unified_diff(text_lines(old), text_lines(new), f"a/{label}", f"b/{label}", n=3):
        out.append(line)
        if not line.endswith("\n"):
            out.append("\n" + NO_NEWLINE)
    return "".join(out)


def apply_text(old: bytes, patch: str) -> bytes:
    """Strict apply: every context and removed line must match where the patch says."""
    original = text_lines(old)
    lines = text_lines(patch)
    hunk = re.compile(r"^@@ -(\d+)(?:,(\d+))? \+\d+(?:,\d+)? @@")
    i = 0
    while i < len(lines) and not lines[i].startswith("@@"):
        i += 1
    out: list[str] = []
    cursor = 0
    while i < len(lines):
        match = hunk.match(lines[i])
        if not match:
            raise ValueError(f"malformed hunk header: {lines[i]!r}")
        start = int(match.group(1)) - (0 if match.group(2) == "0" else 1)
        out.extend(original[cursor:start])
        cursor = start
        i += 1
        added = False  # whether the previous patch line was an added one, for "\ No newline at end of file"
        while i < len(lines) and not lines[i].startswith("@@"):
            tag, content = lines[i][0], lines[i][1:]
            if tag == "\\":
                # Context and removed lines are taken from the original, which already ends as it does.
                if added:
                    out[-1] = out[-1][:-1]
            elif tag in " -":
                expected = original[cursor] if cursor < len(original) else None
                if expected is None or expected.removesuffix("\n") != content.removesuffix("\n"):
                    raise ValueError(f"context mismatch at original line {cursor + 1}")
                cursor += 1
                if tag == " ":
                    out.append(expected)
                added = False
            elif tag == "+":
                out.append(content)
                added = True
            else:
                raise ValueError(f"unexpected line: {lines[i]!r}")
            i += 1
    out.extend(original[cursor:])
    return "".join(out).encode("utf-8", "surrogateescape")


# ---------------------------------------------------------------- one entry

def diff_path(entry: dict) -> Path:
    suffix = {"bsdiff": ".bsdiff", "text": ".patch"}[entry["kind"]]
    return DIFFS / (entry["out"].removeprefix("module/") + suffix)


def produce(entry: dict, sources: Sources) -> bytes:
    kind = entry["kind"]
    if kind == "build":
        raise LookupError(f"built by {entry['built_by']}")
    source, path = split_from(entry)
    # A copied original is the shipped file itself.
    original = (PROJECT / entry["out"]).read_bytes() if kind == "copy" else sources.read(source, path)
    if original is None:
        raise LookupError(entry["from"])
    if sha256(original) != entry["stock_sha256"]:
        raise ValueError(f"{entry['from']} is not the original the difference was cut from "
                         f"(sha256 {sha256(original)[:16]}, expected {entry['stock_sha256'][:16]})")
    if kind == "copy":
        return original
    if kind == "rename":
        old, new = (name.encode() for name in entry["rename"])
        if len(old) != len(new) or original.count(old) != 1:
            raise ValueError(f"{entry['out']}: rename does not apply")
        return original.replace(old, new)
    if kind == "bsdiff":
        return bsdiff4.patch(original, diff_path(entry).read_bytes())
    if kind == "text":
        return apply_text(text_base(entry, original, sources), read_patch(entry))
    raise ValueError(f"unknown kind {kind}")


def text_base(entry: dict, original: bytes, sources: Sources) -> bytes:
    base = decrypt(original, sources) if entry.get("decrypt") else original
    return reformat(base, entry.get("reformat"))


def read_patch(entry: dict) -> str:
    with open(diff_path(entry), encoding="utf-8", errors="surrogateescape", newline="") as f:
        return f.read()


def best_reformat(base: bytes, out: bytes) -> dict | None:
    """Raw or re-serialized original, whichever leaves the smaller diff."""
    best, best_size = None, len(cut_text(base, out, ""))
    for newline in (True, False):
        spec = {"indent": 2, "newline": newline}
        try:
            size = len(cut_text(reformat(base, spec), out, ""))
        except ValueError:
            return best
        if size < best_size:
            best, best_size = spec, size
    return best


def cut(entry: dict, sources: Sources) -> None:
    """Records the original's hash and cuts the difference to the current module file."""
    out = (PROJECT / entry["out"]).read_bytes()
    entry["sha256"] = sha256(out)
    kind = entry["kind"]
    if kind == "build":
        return
    source, path = split_from(entry)
    if kind == "copy":
        found = local_original(source, path)
        if found is None:
            raise LookupError(f"{entry['from']} not in the workspace")
        if found.read_bytes() != out:
            raise ValueError(f"{entry['out']} is marked copy but differs from {entry['from']}")
        entry["stock_sha256"] = entry["sha256"]
        return
    original = sources.read(source, path)
    if original is None:
        raise LookupError(entry["from"])
    entry["stock_sha256"] = sha256(original)
    if kind in ("bsdiff", "text"):
        target = diff_path(entry)
        target.parent.mkdir(parents=True, exist_ok=True)
        if kind == "bsdiff":
            target.write_bytes(bsdiff4.diff(original, out))
        else:
            entry.pop("decrypt", None)
            entry.pop("reformat", None)
            if original[:2] == b"\x01\x01":
                entry["decrypt"] = True
            spec = best_reformat(text_base(entry, original, sources), out)
            if spec:
                entry["reformat"] = spec
            with open(target, "w", encoding="utf-8", errors="surrogateescape", newline="") as f:
                f.write(cut_text(text_base(entry, original, sources), out, entry["out"]))


# ---------------------------------------------------------------- commands

def selected(manifest: dict, only: str | None) -> list[dict]:
    """The entries to handle, each after the module file it is derived from."""
    entries = [e for e in manifest["files"] if not only or only in e["out"]]
    done: set[str] = set()
    ordered: list[dict] = []
    while entries:
        ready = [e for e in entries
                 if not e.get("from", "").startswith("module:") or split_from(e)[1] in done
                 or split_from(e)[1] not in {x["out"] for x in entries}]
        if not ready:
            raise ValueError("circular module sources")
        for e in ready:
            ordered.append(e)
            done.add(e["out"])
            entries.remove(e)
    return ordered


def run_apply(args) -> int:
    manifest = load_manifest()
    sources = Sources()
    failed = []
    for entry in selected(manifest, args.only):
        if entry["kind"] == "build":
            print("build", entry["out"], "with", entry["built_by"])
            continue
        data = produce(entry, sources)
        if sha256(data) != entry["sha256"]:
            failed.append(entry["out"])
            print("FAILED", entry["out"])
            continue
        target = (args.into or PROJECT) / entry["out"]
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        sources.produced[entry["out"]] = data
        print("ok", entry["out"])
    return 1 if failed else 0


def run_check(args) -> int:
    manifest = load_manifest()
    sources = Sources()
    bad = 0
    for entry in selected(manifest, args.only):
        current = PROJECT / entry["out"]
        if entry["kind"] == "build" and not current.is_file():
            print("unbuilt", entry["out"], f"({entry['built_by']})")
            continue
        if not current.is_file():
            print("absent", entry["out"])
            bad += 1
            continue
        if sha256(current.read_bytes()) != entry["sha256"]:
            print("CHANGED", entry["out"], "(run make)")
            bad += 1
            continue
        if entry["kind"] == "build":
            print("ok", entry["out"], "(built)")
            continue
        if sha256(produce(entry, sources)) != entry["sha256"]:
            print("FAILED", entry["out"])
            bad += 1
        else:
            print("ok", entry["out"])
    return 1 if bad else 0


def run_make(args) -> int:
    manifest = load_manifest()
    sources = Sources(refresh=True)
    wanted = {diff_path(e) for e in manifest["files"] if e["kind"] in ("bsdiff", "text")}
    for entry in selected(manifest, args.only):
        cut(entry, sources)
        if entry.get("decrypt"):
            sources.read("pma110", SECURITY_LIB)
        print("cut", entry["out"])
    save_manifest(manifest)
    if not args.only:
        # Differences and originals that no entry uses any more.
        for root, keep in ((DIFFS, wanted), (ORIGINALS, sources.used)):
            for stale in root.rglob("*"):
                if stale.is_file() and stale not in keep:
                    stale.unlink()
                    print("removed", stale.relative_to(PROJECT).as_posix())
    return run_check(args)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    apply = sub.add_parser("apply")
    apply.add_argument("--only")
    apply.add_argument("--into", type=Path, help="write under DIR instead of this repository")
    check = sub.add_parser("check")
    check.add_argument("--only")
    make = sub.add_parser("make")
    make.add_argument("--only")
    args = parser.parse_args()
    return {"apply": run_apply, "check": run_check, "make": run_make}[args.command](args)


if __name__ == "__main__":
    sys.exit(main())
