"""QTI Chromatix V6 tuning files (com.qti.tuned.*.bin): read, select modules like the parser, write back.

A file has four sections: symbols (module entries), data, mode tree rows, blobs. Tree rows are
(id, packed, group, parent, reference, flag) with packed = value << 16 | type; types are
0 default, 1 sensor, 2 usecase, 3 feature0, 4 feature1, 5 feature2, 6 scene, 7 effect.
A row whose packed value equals its parent's is a chunk of the parent node. A row with a
reference resolves to the referenced node (its children and modules); reference rows carry no
modules of their own. Module selection walks the request from the root and keeps the deepest
entry of each module type.
"""
from __future__ import annotations

import struct
from collections import defaultdict
from pathlib import Path

NO_REF = 0xFFFFFFFF


def _crc_table() -> list[int]:
    # Reflected CRC-64 table of the target's ParserCRC64 (polynomial 0x42f0e1eba9ea3693).
    table = []
    for i in range(256):
        c = i
        for _ in range(8):
            c = (c >> 1) ^ 0x42F0E1EBA9EA3693 if c & 1 else c >> 1
        table.append(c)
    return table


_TABLE = _crc_table()


def crc64(payload: bytes) -> int:
    # The parser checksums the payload followed by its little-endian uint32 length.
    value = 0xFFFFFFFFFFFFFFFF
    for byte in payload + struct.pack("<I", len(payload)):
        value = _TABLE[(value ^ byte) & 255] ^ (value >> 8)
    return value ^ 0xFFFFFFFFFFFFFFFF


class Tuning:
    def __init__(self, data: bytes):
        self.d = d = data
        assert d.startswith(b"QTI Chromatix Header")
        assert struct.unpack_from("<I", d, 0x1C)[0] + 8 == len(d)
        assert crc64(d[:-8]) == struct.unpack_from("<Q", d, len(d) - 8)[0], "checksum"
        assert struct.unpack_from("<I", d, 0xA4)[0] == 4
        self.sections = {k: (o, n) for k, o, n in (struct.unpack_from("<3I", d, 0xA8 + 12 * i) for i in range(4))}
        so, ss = self.sections[0]
        self.syms = [struct.unpack_from("<QHHIIIII", d, p) for p in range(so + 32, so + ss, 32)]
        self.dataoff = self.sections[1][0]
        mo, ms = self.sections[2]
        assert d[mo:mo + 7] == b"Default" and (ms - 32) % 24 == 0
        self.rows = [struct.unpack_from("<6I", d, p) for p in range(mo + 32, mo + ms, 24)]
        assert all(r[0] == i for i, r in enumerate(self.rows))

    @classmethod
    def load(cls, path: Path) -> "Tuning":
        return cls(Path(path).read_bytes())

    def add_row(self, packed: int, parent: int, ref: int = NO_REF) -> int:
        mid = len(self.rows)
        self.rows.append((mid, packed, 0, parent, ref, 0))
        return mid

    def set_row(self, mid: int, **change) -> None:
        r = dict(zip(("id", "packed", "group", "parent", "ref", "flag"), self.rows[mid]))
        r.update(change)
        self.rows[mid] = tuple(r[k] for k in ("id", "packed", "group", "parent", "ref", "flag"))

    def serialize(self) -> bytes:
        d = self.d
        sections = {k: d[o:o + n] for k, (o, n) in self.sections.items()}
        sections[0] = sections[0][:32] + b"".join(struct.pack("<QHHIIIII", *r) for r in self.syms)
        sections[2] = sections[2][:32] + b"".join(struct.pack("<6I", *r) for r in self.rows)
        order = sorted(self.sections, key=lambda k: self.sections[k][0])
        header = bytearray(d[:self.sections[order[0]][0]])
        payload = bytearray()
        for kind in order:
            offset = len(header) + len(payload)
            for i in range(4):
                if struct.unpack_from("<I", header, 0xA8 + 12 * i)[0] == kind:
                    struct.pack_into("<3I", header, 0xA8 + 12 * i, kind, offset, len(sections[kind]))
                    break
            payload.extend(sections[kind])
        end = max(o + n for o, n in self.sections.values())
        assert end == len(d) - 8
        result = bytearray(header + payload + d[end:])
        struct.pack_into("<I", result, 0x1C, len(result) - 8)
        body = bytes(result[:-8])
        return body + struct.pack("<Q", crc64(body))


class Selector:
    def __init__(self, t: Tuning):
        self.t = t
        self.nodes = {r[0]: r for r in t.rows}
        self.children = defaultdict(list)
        self.modules = defaultdict(dict)
        self.canonical = {0: 0}
        for mid, packed, _, parent, _, _ in t.rows[1:]:
            parent = self.canonical[parent]
            if packed == self.nodes[parent][1]:
                self.canonical[mid] = parent
            else:
                self.canonical[mid] = mid
                self.children[parent].append(mid)
        for e in t.syms:
            node = self.canonical[e[4]]
            self.modules[node].setdefault(e[0], e)

    def resolve(self, node: int) -> int:
        seen = set()
        while self.nodes[node][4] != NO_REF:
            assert node not in seen
            seen.add(node)
            node = self.canonical[self.nodes[node][4]]
        return node

    def child(self, node: int, typ: int, value: int) -> int | None:
        found = [k for k in self.children[self.resolve(node)] if self.nodes[k][1] == value << 16 | typ]
        assert len(found) <= 1
        return found[0] if found else None

    def select(self, request: list[tuple[int, int]]) -> dict[int, bytes]:
        node = 0
        selected = dict(self.modules[self.resolve(0)])
        for typ, value in request:
            node = self.child(node, typ, value)
            if node is None:
                break
            selected.update(self.modules[self.resolve(node)])
        return {h: self.t.d[self.t.dataoff + e[5]:self.t.dataoff + e[5] + e[6]] for h, e in selected.items()}

    def is_chunked(self, node: int) -> bool:
        return any(r[3] == node and r[1] == self.nodes[node][1] for r in self.t.rows)
