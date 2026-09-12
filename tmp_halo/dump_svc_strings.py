import re

for path in [
    r"c:\Users\punya mittal\aquascope\tmp_halo\svc\classes.dex",
    r"c:\Users\punya mittal\aquascope\tmp_halo\svc\classes2.dex",
]:
    print("====", path)
    d = open(path, "rb").read().decode("latin1")
    pat = re.compile(r"[ -~]{6,120}")
    hits = []
    for m in pat.finditer(d):
        t = m.group(0)
        if re.search(
            r"startLight|whiteList|whitelist|hasLight|rear_light|json|not support|ForGame|FromUser|packageName",
            t,
            re.I,
        ):
            hits.append(t)
    for t in sorted(set(hits))[:250]:
        print(t)
    print("count", len(set(hits)))
