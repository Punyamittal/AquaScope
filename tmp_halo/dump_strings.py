import re

path = r"c:\Users\punya mittal\aquascope\tmp_halo\fw\classes.dex"
d = open(path, "rb").read()
text = d.decode("latin1")
pat = re.compile(r"[A-Za-z][A-Za-z0-9_./$]{3,80}")
keys = sorted(
    {
        m.group(0)
        for m in pat.finditer(text)
        if re.search(
            r"TRANSACTION_|startLight|lightName|sceneId|VivoLightRecord|keepGoing|startLightJson|ForGame",
            m.group(0),
        )
    }
)
print("\n".join(keys))
print("count", len(keys))
