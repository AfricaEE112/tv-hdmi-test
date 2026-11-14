# Збирає з smali всі посилання на класи/методи/поля платформи
import os, re, sys, json, collections
root = sys.argv[1]
FW = re.compile(r'L(android|java|javax|dalvik|org/json|org/w3c|org/xml|org/xmlpull|org/apache/http|sun|libcore)/[^;]*;')
inv = re.compile(r'^\s*(invoke-[a-z/-]+)\s*\{[^}]*\},\s*(L[^;]+;)->([^(]+)(\([^)]*\)\S+)')
fld = re.compile(r'^\s*([si](?:get|put)[a-z-]*)\s+[^,]+(?:,\s*[^,]+)?,\s*(L[^;]+;)->([^:]+):(\S+)')
cls = re.compile(r'^\s*(new-instance|const-class|check-cast|instance-of|new-array|filled-new-array)\b.*?(\[*L[^;]+;)\s*$')
sup = re.compile(r'^\.(super|implements)\s+(L[^;]+;)')
methods = collections.defaultdict(set); fields = collections.defaultdict(set)
classes = collections.defaultdict(set); supers = collections.defaultdict(set)
for dp, dn, fn in os.walk(root):
    for f in fn:
        if not f.endswith('.smali'):
            continue
        p = os.path.join(dp, f)
        owner = None
        for line in open(p, encoding='utf-8', errors='replace'):
            if line.startswith('.class'):
                owner = line.split()[-1]
            m = inv.match(line)
            if m:
                c = m.group(2)
                if FW.fullmatch(c):
                    methods[(c, m.group(3) + m.group(4))].add(owner)
                continue
            m = fld.match(line)
            if m:
                c = m.group(2)
                if FW.fullmatch(c):
                    fields[(c, m.group(3))].add(owner)
                continue
            m = cls.match(line)
            if m:
                c = m.group(2).lstrip('[')
                if FW.fullmatch(c):
                    classes[c].add(owner)
                continue
            m = sup.match(line)
            if m and FW.fullmatch(m.group(2)):
                supers[m.group(2)].add(owner)
json.dump({
    'methods': [[c, s, sorted(o)] for (c, s), o in methods.items()],
    'fields': [[c, s, sorted(o)] for (c, s), o in fields.items()],
    'classes': [[c, sorted(o)] for c, o in classes.items()],
    'supers': [[c, sorted(o)] for c, o in supers.items()],
}, open(sys.argv[2], 'w'))
print(len(methods), 'methods,', len(fields), 'fields,', len(classes), 'classes,', len(supers), 'supers')
