"""camera_unit_feature_config.protobuf edits for OpenGate.

Wire format only: the top message holds the string pool (field 1, repeated) and the mode table
(field 2). A mode entry is (1: mode name, 2: units); a unit is (1: name index, 2: features); a
feature is (1: name index, 2: key index, 3: options index, ..., 8: rules). Values that are pool
indices are varints into the pool.
"""
from __future__ import annotations

from dataclasses import dataclass

SIZE_FEATURE = "com.oplus.camera.feature.video_size"
SIZE_KEY = "pref_master_video_size_key"
OPEN_GATE = "video_size_open_gate"
FPS_KEY = "pref_master_video_fps_key"
FPS_BLOCKED = "[60,120]"
FPS_FALLBACK = "30"
# The 10x unit (rear_ultra_tele) stays without OpenGate.
REAR_UNITS = {"rear_main", "rear_wide", "rear_tele", "rear_sat"}
MODE = "master_video_mode"


@dataclass
class Field:
    number: int
    wire_type: int
    value: int | bytes


def _varint(data: bytes, offset: int) -> tuple[int, int]:
    value = shift = 0
    while True:
        byte = data[offset]
        offset += 1
        value |= (byte & 0x7F) << shift
        if byte < 0x80:
            return value, offset
        shift += 7


def parse(data: bytes) -> list[Field]:
    fields, offset = [], 0
    while offset < len(data):
        tag, offset = _varint(data, offset)
        number, wire = tag >> 3, tag & 7
        if number == 0:
            raise ValueError("field number zero")
        if wire == 0:
            value, offset = _varint(data, offset)
        elif wire == 2:
            size, offset = _varint(data, offset)
            value = bytes(data[offset:offset + size])
            if len(value) != size:
                raise ValueError("truncated")
            offset += size
        elif wire in (1, 5):
            size = 8 if wire == 1 else 4
            value = bytes(data[offset:offset + size])
            offset += size
        else:
            raise ValueError(f"wire type {wire}")
        fields.append(Field(number, wire, value))
    return fields


def _enc_varint(value: int) -> bytes:
    out = bytearray()
    while value >= 0x80:
        out.append(value & 0x7F | 0x80)
        value >>= 7
    out.append(value)
    return bytes(out)


def encode(fields: list[Field]) -> bytes:
    out = bytearray()
    for f in fields:
        out += _enc_varint(f.number << 3 | f.wire_type)
        if f.wire_type == 0:
            out += _enc_varint(int(f.value))
        elif f.wire_type == 2:
            out += _enc_varint(len(f.value)) + f.value
        else:
            out += f.value
    return bytes(out)


def _remap(data: bytes, src: list[str], dst: list[str]) -> bytes:
    out = []
    for f in parse(data):
        if f.wire_type == 0:
            text = src[int(f.value)]
            if text not in dst:
                dst.append(text)
            out.append(Field(f.number, 0, dst.index(text)))
        elif f.wire_type == 2:
            try:
                value = _remap(f.value, src, dst)
            except (ValueError, IndexError):
                value = f.value
            out.append(Field(f.number, 2, value))
        else:
            out.append(f)
    return encode(out)


class Graph:
    """The mode entry of one mode, editable unit by unit, written back in place."""

    def __init__(self, data: bytes, mode: str = MODE):
        self.top = parse(data)
        self.pool = [f.value.decode() for f in self.top if f.number == 1 and f.wire_type == 2]
        self.cpos = next(i for i, f in enumerate(self.top) if f.number == 2 and f.wire_type == 2)
        self.config = parse(self.top[self.cpos].value)
        self.mpos = next(i for i, f in enumerate(self.config) if f.number == 1 and f.wire_type == 2
                         and parse(f.value)[0].value == mode.encode())
        self.entry = parse(self.config[self.mpos].value)
        self.units = parse(self.entry[1].value)

    def name(self, message: bytes) -> str:
        return self.pool[int(parse(message)[0].value)]

    def features(self, unit: str) -> tuple[int, list[Field]]:
        pos = next(i for i, u in enumerate(self.units) if self.name(u.value) == unit)
        return pos, parse(parse(self.units[pos].value)[1].value)

    def set_features(self, pos: int, features: list[Field]) -> None:
        fields = parse(self.units[pos].value)
        fields[1] = Field(fields[1].number, 2, encode(features))
        self.units[pos] = Field(self.units[pos].number, 2, encode(fields))

    def unit_names(self) -> list[str]:
        return [self.name(u.value) for u in self.units]

    def write(self, rewrite_pool: bool = False) -> bytes:
        self.entry[1] = Field(self.entry[1].number, 2, encode(self.units))
        self.config[self.mpos] = Field(self.config[self.mpos].number, 2, encode(self.entry))
        self.top[self.cpos] = Field(2, 2, encode(self.config))
        if rewrite_pool:
            rest = [f for f in self.top if f.number != 1]
            return encode([Field(1, 2, t.encode()) for t in self.pool] + rest)
        return encode(self.top)


def _size_feature(g: Graph, item: Field) -> bool:
    parts = parse(item.value)
    return g.pool[parts[0].value] == SIZE_FEATURE


def add_open_gate(data: bytes, donor: bytes) -> bytes:
    """Offer video_size_open_gate on the rear units, with the donor's size rule for it."""
    g, d = Graph(data), Graph(donor)
    for name in g.unit_names():
        if name not in REAR_UNITS:
            continue
        pos, features = g.features(name)
        _, donor_features = d.features(name)
        donor_pref = next(parse(x.value) for x in donor_features
                          if _size_feature(d, x) and d.pool[parse(x.value)[1].value] == SIZE_KEY)
        donor_rule = next(x for x in donor_pref if x.number == 8 and x.wire_type == 2
                          and d.pool[parse(x.value)[0].value] == OPEN_GATE)
        rule = _remap(donor_rule.value, d.pool, g.pool)
        keys = set()
        for i, item in enumerate(features):
            if not _size_feature(g, item):
                continue
            parts = parse(item.value)
            key = g.pool[parts[1].value]
            keys.add(key)
            options = g.pool[parts[2].value]
            assert OPEN_GATE not in options and options.startswith("[") and options.endswith("]")
            extended = options[:-1] + "," + OPEN_GATE + "]"
            if extended not in g.pool:
                g.pool.append(extended)
            parts[2] = Field(3, 0, g.pool.index(extended))
            if key == SIZE_KEY:
                parts.append(Field(8, 2, rule))
            features[i] = Field(item.number, 2, encode(parts))
        assert keys == {SIZE_FEATURE, SIZE_KEY}, (name, keys)
        g.set_features(pos, features)
    return g.write(rewrite_pool=True)


def restrict_open_gate_fps(data: bytes) -> bytes:
    """In the OpenGate size rule of every rear unit: fps [60,120] -> 30."""
    g = Graph(data)
    for text in (FPS_KEY, FPS_BLOCKED, FPS_FALLBACK, OPEN_GATE):
        assert text in g.pool, text
    clause = encode([Field(1, 0, g.pool.index(FPS_KEY)), Field(2, 0, g.pool.index(FPS_BLOCKED)),
                     Field(3, 0, g.pool.index(FPS_FALLBACK))])
    done = set()
    for name in g.unit_names():
        if name not in REAR_UNITS:
            continue
        pos, features = g.features(name)
        for i, item in enumerate(features):
            parts = parse(item.value)
            if g.pool[parts[0].value] != SIZE_FEATURE or g.pool[parts[1].value] != SIZE_KEY:
                continue
            rules = [j for j, x in enumerate(parts) if x.number == 8 and x.wire_type == 2
                     and g.pool[parse(x.value)[0].value] == OPEN_GATE]
            assert len(rules) == 1, name
            rule = parse(parts[rules[0]].value)
            cpos = next(j for j, x in enumerate(rule) if x.number == 2 and x.wire_type == 2)
            clauses = parse(rule[cpos].value)
            fps = [j for j, c in enumerate(clauses) if g.pool[parse(c.value)[0].value] == FPS_KEY]
            if fps:
                clauses[fps[0]] = Field(clauses[fps[0]].number, 2, clause)
            else:
                clauses.append(Field(1, 2, clause))
            rule[cpos] = Field(rule[cpos].number, 2, encode(clauses))
            parts[rules[0]] = Field(8, 2, encode(rule))
            features[i] = Field(item.number, 2, encode(parts))
            done.add(name)
        g.set_features(pos, features)
    assert done == REAR_UNITS, REAR_UNITS - done
    return g.write()
