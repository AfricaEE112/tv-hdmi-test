# Де результат шима (null на Android 7) одразу перевіряється з винятком
import os, re, sys
root = sys.argv[1]
hits = []
for dp, dn, fn in os.walk(root):
    for f in fn:
        p = os.path.join(dp, f); L = open(p, encoding='utf-8').read().split('\n')
        start = 0
        for i, l in enumerate(L):
            if l.startswith('.method'): start = i
            if 'Lua/compat/C26;->' in l and re.search(r'->(m_|f_|c_|k_)', l):
                end = i
                while end < len(L) and not L[end].startswith('.end method'): end += 1
                win = [(k, L[k].strip()) for k in range(i + 1, min(end, i + 10)) if L[k].strip() and not L[k].strip().startswith('.line')]
                if not win or not win[0][1].startswith('move-result-object'): continue
                r = win[0][1].split()[-1]
                for k, w in win[1:3]:
                    m = re.match(r'if-(eqz|nez) (\w+), (:\w+)', w)
                    if not (m and m.group(2) == r): continue
                    if m.group(1) == 'eqz':
                        lab = [q for q in range(start, end) if L[q].strip() == m.group(3)]
                        tgt = [x.strip() for x in L[lab[0]:lab[0] + 10]] if lab else []
                    else:
                        tgt = [x.strip() for x in L[k + 1:k + 10]]
                    if any('Required value' in t or t.startswith('throw') for t in tgt):
                        hits.append((os.path.relpath(p, root), L[start].split()[-1][:50], k + 1, w))
for h in hits: print(*h)
print(len(hits))
