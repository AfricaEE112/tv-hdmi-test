# Показати контекст використання заданих посилань у smali
import os, re, sys
root, pat = sys.argv[1], re.compile(sys.argv[2])
before = int(sys.argv[3]) if len(sys.argv) > 3 else 4
after = int(sys.argv[4]) if len(sys.argv) > 4 else 4
for dp, dn, fn in os.walk(root):
    for f in fn:
        p = os.path.join(dp, f)
        lines = open(p, encoding='utf-8', errors='replace').read().split('\n')
        meth = None
        for i, l in enumerate(lines):
            if l.startswith('.method'):
                meth = l
            if pat.search(l) and not l.startswith('.'):
                print('== %s :: %s' % (os.path.relpath(p, root), meth))
                for k in range(max(0, i - before), min(len(lines), i + after + 1)):
                    s = lines[k].strip()
                    if s and not s.startswith('.line') and not s.startswith('.param'):
                        print(('>> ' if k == i else '   ') + s)
