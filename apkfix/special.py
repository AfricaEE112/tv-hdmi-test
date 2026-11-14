# Точкові правки перед загальним перенесенням
import re, sys
base = sys.argv[1].rstrip('/') + '/'
p = base + 'lu0.smali'; s = open(p, encoding='utf-8').read()
m = re.search(r'\.method public final y\(\)Landroid/view/autofill/AutofillManager;.*?\.end method', s, re.S)
nb = m.group(0).replace('    invoke-static {p0}, Lyn0;->k(Ljava/lang/String;)V\n', '    # (Android 7: AutofillManager немає, повертаємо null)\n', 1)
assert nb != m.group(0)
open(p, 'w', encoding='utf-8').write(s.replace(m.group(0), nb))
p = base + 'rc4.smali'; s = open(p, encoding='utf-8').read()
m = re.search(r'\.method public static final a\(Ljava/io/File;Ljava/io/File;\)V.*?\.end method', s, re.S)
open(p, 'w', encoding='utf-8').write(s.replace(m.group(0), '''.method public static final a(Ljava/io/File;Ljava/io/File;)V
    .locals 0

    invoke-static {p0, p1}, Lua/compat/C26;->moveFile(Ljava/io/File;Ljava/io/File;)V

    return-void
.end method'''))
print('special ok')
