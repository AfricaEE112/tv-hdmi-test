# Що з посилань застосунку з'явилось пізніше за API 24
import gzip, json, sys, collections
import xml.etree.ElementTree as ET
MIN = int(sys.argv[3]) if len(sys.argv) > 3 else 24
tree = ET.parse(gzip.open(sys.argv[1]))
C = {}
for c in tree.getroot().iter('class'):
    cs = int(float(c.get('since', '1')))
    ent = {'since': cs, 'supers': [], 'm': {}, 'f': {}}
    for e in c:
        if e.tag in ('extends', 'implements'):
            ent['supers'].append(e.get('name'))
        elif e.tag == 'method':
            ent['m'][e.get('name')] = int(float(e.get('since', cs)))
        elif e.tag == 'field':
            ent['f'][e.get('name')] = int(float(e.get('since', cs)))
    C[c.get('name')] = ent
def find(cname, key, kind, seen=None):
    seen = seen or set()
    if cname in seen or cname not in C:
        return None
    seen.add(cname)
    e = C[cname]
    best = e[kind].get(key)
    for s in e['supers']:
        r = find(s, key, kind, seen)
        if r is not None and (best is None or r < best):
            best = r
    return best
refs = json.load(open(sys.argv[2]))
out = collections.OrderedDict()
def owners_short(o):
    return sorted({x.split('/')[0] + '/' + x.split('/')[1] if x.count('/') > 1 else x for x in o})[:4]
bad = []
for c, s, o in refs['methods']:
    cn = c[1:-1]
    if cn not in C:
        continue  # не платформа (наприклад, своє в android/ пакеті з бібліотек)
    since = find(cn, s, 'm')
    cls_since = C[cn]['since']
    lvl = max(cls_since, since if since is not None else 0)
    if since is None:
        lvl = 999 if not s.startswith('<init>') else cls_since
    if lvl > MIN:
        bad.append(('M', lvl, c + '->' + s, o))
for c, s, o in refs['fields']:
    cn = c[1:-1]
    if cn not in C:
        continue
    since = find(cn, s, 'f')
    lvl = max(C[cn]['since'], since if since is not None else 999)
    if lvl > MIN:
        bad.append(('F', lvl, c + '->' + s, o))
for c, o in refs['classes'] + refs['supers']:
    cn = c[1:-1]
    if cn in C and C[cn]['since'] > MIN:
        bad.append(('C', C[cn]['since'], c, o))
bad.sort(key=lambda b: (b[1], b[2]))
if len(sys.argv) > 4:
    json.dump([[k, lvl, n] for k, lvl, n, o in bad if lvl <= int(sys.argv[4])], open(sys.argv[5], 'w'), indent=0)
app = lambda o: [x for x in o if x and x.startswith('Lua/')]
print('total', len(bad))
for k, lvl, n, o in bad:
    print('%s %3d %-90s %4d %s' % (k, lvl, n[:90], len(o), ' '.join(sorted(o)[:3])))
