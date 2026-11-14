# Повний пошук: посилання на методи/поля через класи застосунку, що успадковують платформу
import os, re, sys, json, gzip, collections
import xml.etree.ElementTree as ET
root, apigz, MIN = sys.argv[1], sys.argv[2], 24
tree = ET.parse(gzip.open(apigz))
C = {}
for c in tree.getroot().iter('class'):
    cs = int(float(c.get('since', '1')))
    e = {'since': cs, 'supers': [], 'm': {}, 'f': {}}
    for x in c:
        if x.tag in ('extends', 'implements'): e['supers'].append(x.get('name'))
        elif x.tag == 'method': e['m'][x.get('name')] = int(float(x.get('since', cs)))
        elif x.tag == 'field': e['f'][x.get('name')] = int(float(x.get('since', cs)))
    C[c.get('name')] = e
def fw_find(cn, key, kind, seen=None):
    seen = seen or set()
    if cn in seen or cn not in C: return None
    seen.add(cn); e = C[cn]; best = e[kind].get(key)
    for s in e['supers']:
        r = fw_find(s, key, kind, seen)
        if r is not None and (best is None or r < best): best = r
    return best
# класи застосунку
app = {}
cur = None
inv = re.compile(r'^\s*(invoke-[a-z/-]+)\s*\{[^}]*\},\s*(L[^;]+;)->([^(]+)(\([^)]*\)\S+)')
fld = re.compile(r'^\s*([si](?:get|put)[a-z-]*)\s+[^,]+(?:,\s*[^,]+)?,\s*(L[^;]+;)->([^:]+):(\S+)')
refs_m = collections.defaultdict(set); refs_f = collections.defaultdict(set)
for dp, dn, fn in os.walk(root):
    for f in fn:
        if not f.endswith('.smali'): continue
        info = {'super': None, 'ifs': [], 'm': set(), 'f': set()}
        name = None
        for line in open(os.path.join(dp, f), encoding='utf-8', errors='replace'):
            if line.startswith('.class'): name = line.split()[-1][1:-1]
            elif line.startswith('.super'): info['super'] = line.split()[-1][1:-1]
            elif line.startswith('.implements'): info['ifs'].append(line.split()[-1][1:-1])
            elif line.startswith('.method'):
                info['m'].add(line.split()[-1])
            elif line.startswith('.field'):
                info['f'].add(line.split()[-1].split(':')[0])
            else:
                m = inv.match(line)
                if m and not m.group(1).startswith('invoke-super'):
                    refs_m[(m.group(2)[1:-1], m.group(3) + m.group(4))].add(name)
                    continue
                m = fld.match(line)
                if m: refs_f[(m.group(2)[1:-1], m.group(3))].add(name)
        app[name] = info
def resolve(cn, key, kind, seen=None):
    """Повертає (рівень API, фреймворк-клас) або None, якщо визначено в застосунку."""
    seen = seen or set()
    if cn in seen: return None
    seen.add(cn)
    if cn in app:
        a = app[cn]
        if key in a[kind]: return None
        best = None
        for s in ([a['super']] if a['super'] else []) + a['ifs']:
            r = resolve(s, key, kind, seen)
            if r and (best is None or r[0] < best[0]): best = r
        return best
    if cn in C:
        lvl = fw_find(cn, key, 'm' if kind == 'm' else 'f')
        if lvl is None: return None
        return (max(lvl, C[cn]['since']), cn)
    return None
out = []
for (cn, key), owners in refs_m.items():
    if cn in app:
        r = resolve(cn, key, 'm')
        if r and MIN < r[0] <= 26: out.append(('M', r[0], cn, key, r[1], sorted(owners)))
for (cn, key), owners in refs_f.items():
    if cn in app:
        r = resolve(cn, key, 'f')
        if r and MIN < r[0] <= 26: out.append(('F', r[0], cn, key, r[1], sorted(owners)))
for o in out: print(o[0], o[1], o[2] + '->' + o[3], 'via', o[4], o[5][:4])
print('indirect refs <=26:', len(out))
