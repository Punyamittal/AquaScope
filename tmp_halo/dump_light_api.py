import struct
from collections import defaultdict

path = r"c:\Users\punya mittal\aquascope\tmp_halo\fw\classes.dex"


def u4(d, o):
    return struct.unpack_from("<I", d, o)[0]


def u2(d, o):
    return struct.unpack_from("<H", d, o)[0]


with open(path, "rb") as f:
    d = f.read()
assert d[:4] == b"dex\n"

string_ids_size = u4(d, 0x38)
string_ids_off = u4(d, 0x3C)
type_ids_size = u4(d, 0x40)
type_ids_off = u4(d, 0x44)
proto_ids_off = u4(d, 0x4C)
method_ids_size = u4(d, 0x58)
method_ids_off = u4(d, 0x5C)


def uleb(i):
    n = s = 0
    while True:
        b = d[i]
        i += 1
        n |= (b & 0x7F) << s
        if b < 0x80:
            return n, i
        s += 7


def get_string(idx):
    off = u4(d, string_ids_off + idx * 4)
    size, p = uleb(off)
    return d[p : p + size].decode("utf-8", "replace")


type_cache = [get_string(u4(d, type_ids_off + i * 4)) for i in range(type_ids_size)]

print("TYPES")
for i, n in enumerate(type_cache):
    if "vivolight" in n.lower() or "VivoLight" in n:
        print(i, n)

print("\nMETHODS by class")
by = defaultdict(list)
for i in range(method_ids_size):
    o = method_ids_off + i * 8
    c = u2(d, o)
    p = u2(d, o + 2)
    n = u4(d, o + 4)
    cn = type_cache[c]
    if "vivolight" not in cn.lower() and "VivoLight" not in cn:
        continue
    po = proto_ids_off + p * 12
    ret = type_cache[u4(d, po + 4)]
    params_off = u4(d, po + 8)
    params = []
    if params_off:
        sz = u4(d, params_off)
        for k in range(sz):
            params.append(type_cache[u2(d, params_off + 4 + k * 2)])
    by[cn].append((get_string(n), ret, params))

for cn, ms in by.items():
    print("\n==", cn)
    for name, ret, params in ms:
        print(" ", ret, f"{name}({', '.join(params)})")

field_ids_size = u4(d, 0x50)
field_ids_off = u4(d, 0x54)
print("\nFIELDS")
for i in range(field_ids_size):
    o = field_ids_off + i * 8
    c = u2(d, o)
    t = u2(d, o + 2)
    n = u4(d, o + 4)
    cn = type_cache[c]
    if "vivolight" not in cn.lower() and "VivoLight" not in cn:
        continue
    print(" ", cn, type_cache[t], get_string(n))
