"""OpenGate (pro video, full sensor readout) for the ultra wide, main and 3x lenses, X10 layout.

OpenGate opens a physical single-camera session (opmode 0x800B, 0x830B with HDR/10-bit). The
patched UI layer (uilayer/) gives these sessions private sensor-mode keys; module/payload/og/hw_rows.txt
maps them to the full-frame mode of the 4K readout family of each lens, and post-fs-data.sh inserts
the rows into the live CameraHWConfiguration.config. This script writes everything else from the
stock tuning files of the current ROM (refs.STOCK_ODM):

  tuning   the full-frame mode references the 4K pro-video feature1 nodes, in usecase 2 (realtime)
           and usecase 0 (OG offline IPE, OplusOfflineReprocess). Only references to real nodes.
           Ultra wide mode 3 has its own feature1 53/55 nodes (uc2 and uc0); they are moved to
           feature1 values no usecase 2 / usecase 0 request of that node uses, then referenced.

The menu (camera_unit_feature_config.protobuf) and the 17-item master.video.feature.list in
oplus_camera_config are shipped in module/payload.

Run: python tools/opengate/build_opengate.py
"""
from __future__ import annotations

import hashlib
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
PROJECT = HERE.parent.parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))
import refs  # noqa: E402
from chromatix import NO_REF, Selector, Tuning  # noqa: E402

PAYLOAD = PROJECT / "module/payload"

# lens: (full-frame target mode, {feature1: 4K pro-video source mode})
TUNING = {
    "main": (7, {53: 21, 55: 21, 57: 31}),
    "wide": (3, {53: 22, 55: 22, 57: 23}),
    "tele": (3, {53: 18, 55: 18, 57: 27}),
}
# Own feature1 nodes of the target that collide with the references: usecase -> {old: new}
MOVE = {"wide": {2: {53: 58, 55: 49}, 0: {53: 61, 55: 34}}}


def graft(lens: str, data: bytes) -> tuple[bytes, list[str]]:
    target, sources = TUNING[lens]
    old = Tuning(data)
    t = Tuning(data)
    log = []
    # Move colliding own nodes first; the reference grafting below reads the moved tree.
    if lens in MOVE:
        s = Selector(t)
        root = s.child(0, 1, target)
        for uc, moves in MOVE[lens].items():
            f0 = s.resolve(s.child(s.child(root, 2, uc), 3, 0))
            for c in list(s.children[f0]):
                r = t.rows[c]
                if r[1] & 0xFFFF == 4 and r[1] >> 16 in moves:
                    new = moves[r[1] >> 16]
                    assert s.child(f0, 4, new) is None
                    t.set_row(c, packed=new << 16 | 4)
                    log.append(f"uc{uc}: own feature1 {r[1] >> 16} moved to {new}")
        t = Tuning(t.serialize())
    base = Tuning(t.d)
    so = Selector(base)
    tnode = so.child(0, 1, target)
    assert tnode is not None and so.resolve(tnode) == tnode
    for uc in (2, 0):
        u = so.child(tnode, 2, uc)
        if u is None:
            u = t.add_row(2 << 16 | uc, tnode)
            f0 = t.add_row(0 << 16 | 3, u)
            log.append(f"uc{uc}: new branch")
        else:
            f0 = so.child(u, 3, 0)
            if f0 is None:
                if base.rows[u][4] == NO_REF:
                    host = so.resolve(u)
                else:
                    # Reference (possibly chunked) to a branch without feature children: make it an own
                    # node carrying the referenced node's modules, then add (3,0).
                    ref_base = so.resolve(u)
                    assert not so.children[ref_base]
                    for r in [r for r in base.rows if r[0] == u or (r[3] == u and r[1] == base.rows[u][1])]:
                        t.set_row(r[0], ref=NO_REF)
                    for e in so.modules[ref_base].values():
                        x = list(e)
                        x[4] = u
                        x[7] = len(t.syms)
                        t.syms.append(tuple(x))
                    host = u
                    log.append(f"uc{uc}: reference converted to own node with {len(so.modules[ref_base])} modules")
                f0 = t.add_row(0 << 16 | 3, host)
            else:
                f0 = so.resolve(f0)
        for f1, mode in sources.items():
            assert f0 >= len(base.rows) or so.child(f0, 4, f1) is None, f"{lens} uc{uc} already has feature1 {f1}"
            src = so.child(so.child(so.child(so.child(0, 1, mode), 2, 2), 3, 0), 4, f1)
            assert src is not None, f"{lens} source mode {mode} lacks feature1 {f1}"
            t.add_row(f1 << 16 | 4, f0, src)
    out = t.serialize()
    # Only the pro-video feature1 values (and the moved ones) may select differently.
    a, b = Selector(old), Selector(Tuning(out))
    feats = sorted({r[1] >> 16 for r in old.rows if r[1] & 0xFFFF == 4})
    allowed = set(sources) | {v for m in MOVE.get(lens, {}).values() for v in m.values()}
    changed = set()
    for k in a.children[0]:
        if old.rows[k][1] & 0xFFFF != 1:
            continue
        m = old.rows[k][1] >> 16
        for uc in (0, 1, 2, 6, 8):
            for f1 in feats:
                for effect in (0, 15):
                    req = [(1, m), (2, uc), (3, 0), (4, f1), (5, 0), (6, 0), (7, effect)]
                    if a.select(req) != b.select(req):
                        changed.add((m, uc, f1))
    stray = sorted(x for x in changed if x[2] not in allowed)
    assert not stray, (lens, stray)
    log.append(f"changed requests: {len(changed)}, all within feature1 {sorted(allowed)}")
    return out, log


def main() -> None:
    for lens in TUNING:
        data = (refs.STOCK_ODM / f"odm/lib64/camera/com.qti.tuned.lighthouse{lens}.bin").read_bytes()
        out, log = graft(lens, data)
        print(lens, *log, sep="\n  ")
        rel = f"module/payload/isp/com.qti.tuned.lighthouse{lens}.bin"
        (PROJECT / rel).write_bytes(out)
        print(hashlib.sha1(out).hexdigest()[:8], rel)


if __name__ == "__main__":
    main()
