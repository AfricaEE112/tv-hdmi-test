# Після перенесення: якщо результат шима (на Android 7 це null) одразу перевіряється
# з винятком "Required value was null", прибираємо перевірку (значення лишається null).
import os, re, sys
root = sys.argv[1]
fixed = 0
for dp, dn, fn in os.walk(root):
    for f in fn:
        p = os.path.join(dp, f)
        L = open(p, encoding='utf-8').read().split('\n')
        changed = False
        start = 0
        i = 0
        while i < len(L):
            l = L[i]
            if l.startswith('.method'):
                start = i
            if 'Lua/compat/C26;->' in l and re.search(r'->(m_|f_|c_|k_)', l):
                end = i
                while end < len(L) and not L[end].startswith('.end method'):
                    end += 1
                win = [(k, L[k].strip()) for k in range(i + 1, min(end, i + 10))
                       if L[k].strip() and not L[k].strip().startswith('.line')]
                if win and win[0][1].startswith('move-result-object'):
                    r = win[0][1].split()[-1]
                    for k, w in win[1:3]:
                        m = re.match(r'if-(eqz|nez) (\w+), (:\w+)', w)
                        if not (m and m.group(2) == r):
                            continue
                        if m.group(1) == 'eqz':
                            lab = [q for q in range(start, end) if L[q].strip() == m.group(3)]
                            tgt = [x.strip() for x in L[lab[0]:lab[0] + 10]] if lab else []
                        else:
                            tgt = [x.strip() for x in L[k + 1:k + 10]]
                        if any('Required value' in t or t.startswith('throw') for t in tgt):
                            ind = L[k][:len(L[k]) - len(L[k].lstrip())]
                            if m.group(1) == 'eqz':
                                L[k] = ind + '# (Android 7: null дозволено) ' + w
                            else:
                                L[k] = ind + 'goto ' + m.group(3) + '  # (Android 7: null дозволено)'
                            changed = True
                            fixed += 1
            i += 1
        if changed:
            open(p, 'w', encoding='utf-8').write('\n'.join(L))
print('null checks relaxed:', fixed)

# Друга частина: R8 перевіряє null через x.getClass(). Для об'єктів класів, яких немає
# в Android 7 (на ньому там завжди null), таку перевірку прибираємо.
import json
NEW = set(json.load(open(sys.argv[2]))) if len(sys.argv) > 2 else set()
GC = re.compile(r'^\s*invoke-virtual \{(\w+)\}, Ljava/lang/Object;->getClass\(\)Ljava/lang/Class;\s*$')
removed = 0
for dp, dn, fn in os.walk(root):
    for f in fn:
        p = os.path.join(dp, f)
        L = open(p, encoding='utf-8').read().split('\n')
        changed = False
        for i, l in enumerate(L):
            m = GC.match(l)
            if not m:
                continue
            r = m.group(1)
            # наступна інструкція не має забирати результат
            nxt = next((x.strip() for x in L[i + 1:i + 6] if x.strip() and not x.strip().startswith('.line')), '')
            if nxt.startswith('move-result'):
                continue
            # попередня інструкція, що записала r
            prev = [(k, L[k].strip()) for k in range(i - 1, max(0, i - 8), -1)
                    if L[k].strip() and not L[k].strip().startswith('.line')]
            if not prev:
                continue
            k, pl = prev[0]
            t = None
            mm = re.match(r'[is]get-object %s,.*:(L[^;]+;)$' % re.escape(r), pl)
            if mm:
                t = mm.group(1)
            elif pl == 'move-result-object %s' % r and len(prev) > 1:
                mi = re.search(r'\)(L[^;]+;)$', prev[1][1])
                if mi:
                    t = mi.group(1)
            if t and t in NEW:
                ind = l[:len(l) - len(l.lstrip())]
                L[i] = ind + '# (Android 7: тут null) ' + l.strip()
                changed = True
                removed += 1
        if changed:
            open(p, 'w', encoding='utf-8').write('\n'.join(L))
print('getClass null-checks removed:', removed)
