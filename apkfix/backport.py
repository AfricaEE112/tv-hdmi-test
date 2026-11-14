# Перенесення застосунку з minSdk 26 на Android 7 (API 24).
# 1) генерує Java-шими: виклик справжнього API на Android 8+, запасна поведінка на 7;
# 2) переписує smali: виклики, поля й класи Android 8 ведуть у шими.
import json, os, re, sys, hashlib

SMALI = sys.argv[1]
MISSING = json.load(open(sys.argv[2]))
JAVA_OUT = sys.argv[3]
SHIM = 'Lua/compat/C26;'
SHIM_PKG, SHIM_CLS = 'ua.compat', 'C26'

miss_m = {n for k, l, n in MISSING if k == 'M'}
miss_f = {n for k, l, n in MISSING if k == 'F'}   # "Lowner;->NAME"
miss_c = {n for k, l, n in MISSING if k == 'C'}

# ---------------------------------------------------------------- типи

def parse_types(desc):
    """'(IJLjava/lang/String;[F)V' -> (['I','J','Ljava/lang/String;','[F'], 'V')"""
    m = re.match(r'\((.*)\)(.+)$', desc)
    params, ret = m.group(1), m.group(2)
    out, i = [], 0
    while i < len(params):
        j = i
        while params[j] == '[':
            j += 1
        if params[j] == 'L':
            j = params.index(';', j)
        out.append(params[i:j + 1])
        i = j + 1
    return out, ret

PRIM = {'V': 'void', 'Z': 'boolean', 'B': 'byte', 'S': 'short', 'C': 'char', 'I': 'int',
        'J': 'long', 'F': 'float', 'D': 'double'}

def jtype(t):
    dims = len(t) - len(t.lstrip('['))
    base = t[dims:]
    if base in PRIM:
        j = PRIM[base]
    else:
        j = base[1:-1].replace('/', '.').replace('$', '.')
    return j + '[]' * dims

def regs_of(t):
    return 2 if t in ('J', 'D') else 1

def default_ret(ret, owner=None, instance=False):
    if ret == 'V':
        return 'return;'
    if ret == 'Z':
        return 'return false;'
    if ret in PRIM:
        return 'return (%s) 0;' % PRIM[ret]
    if instance and ret == owner:
        return 'return self;'  # будівельник: повертає сам себе
    return 'return null;'

# Особлива запасна поведінка на Android 7 (self, a0, a1...)
OVERRIDES = {
    'Landroid/media/AudioManager;->requestAudioFocus(Landroid/media/AudioFocusRequest;)I': 'return 1;',
    'Landroid/media/AudioManager;->abandonAudioFocusRequest(Landroid/media/AudioFocusRequest;)I': 'return 1;',
    'Landroid/view/ViewConfiguration;->getScaledHorizontalScrollFactor()F':
        'return 64f * android.content.res.Resources.getSystem().getDisplayMetrics().density;',
    'Landroid/view/ViewConfiguration;->getScaledVerticalScrollFactor()F':
        'return 64f * android.content.res.Resources.getSystem().getDisplayMetrics().density;',
    'Landroid/view/View;->setFocusable(I)V': 'if (self != null) self.setFocusable(a0 != 0); return;',
    'Landroid/graphics/Canvas;->clipOutRect(FFFF)Z':
        'return self != null && self.clipRect(a0, a1, a2, a3, android.graphics.Region.Op.DIFFERENCE);',
    'Landroid/graphics/Canvas;->clipOutRect(IIII)Z':
        'return self != null && self.clipRect((float) a0, (float) a1, (float) a2, (float) a3, android.graphics.Region.Op.DIFFERENCE);',
    'Landroid/graphics/Canvas;->clipOutRect(Landroid/graphics/Rect;)Z':
        'return self != null && self.clipRect(a0, android.graphics.Region.Op.DIFFERENCE);',
    'Landroid/graphics/Canvas;->clipOutRect(Landroid/graphics/RectF;)Z':
        'return self != null && self.clipRect(a0, android.graphics.Region.Op.DIFFERENCE);',
    'Landroid/graphics/Canvas;->clipOutPath(Landroid/graphics/Path;)Z':
        'return self != null && self.clipPath(a0, android.graphics.Region.Op.DIFFERENCE);',
    'Landroid/graphics/Bitmap;->createBitmap(Landroid/util/DisplayMetrics;IILandroid/graphics/Bitmap$Config;ZLandroid/graphics/ColorSpace;)Landroid/graphics/Bitmap;':
        'android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(a0, a1, a2, '
        'a3 == null ? android.graphics.Bitmap.Config.ARGB_8888 : a3); b.setHasAlpha(a4); return b;',
    'Ljava/lang/Byte;->toUnsignedInt(B)I': 'return a0 & 0xff;',
    'Ljava/lang/Integer;->compareUnsigned(II)I':
        'return Integer.compare(a0 + Integer.MIN_VALUE, a1 + Integer.MIN_VALUE);',
    'Ljava/lang/Integer;->toUnsignedString(I)Ljava/lang/String;': 'return Long.toString(a0 & 0xffffffffL);',
    'Ljava/lang/Long;->compareUnsigned(JJ)I': 'return Long.compare(a0 + Long.MIN_VALUE, a1 + Long.MIN_VALUE);',
    'Ljava/lang/Long;->toUnsignedString(J)Ljava/lang/String;':
        'return a0 >= 0 ? Long.toString(a0) : java.math.BigInteger.valueOf(a0)'
        '.add(java.math.BigInteger.ONE.shiftLeft(64)).toString();',
    'Ljava/lang/String;->join(Ljava/lang/CharSequence;Ljava/lang/Iterable;)Ljava/lang/String;':
        'StringBuilder sb = new StringBuilder(); boolean first = true; '
        'for (Object o : (Iterable<?>) a1) { if (!first) sb.append(a0); sb.append(o); first = false; } '
        'return sb.toString();',
    'Ljava/time/LocalDate;->toString()Ljava/lang/String;':
        'return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());',
}
# Поля: запасне значення, коли поле читається як значення (не для порівняння)
FIELD_VALUE = {
    'Landroid/graphics/Bitmap$Config;->HARDWARE': 'android.graphics.Bitmap.Config.ARGB_8888',
    'Landroid/graphics/Bitmap$Config;->RGBA_F16': 'android.graphics.Bitmap.Config.ARGB_8888',
}

# ---------------------------------------------------------------- генерація шимів

java = []
names = {}

def short(key):
    return 'x' + hashlib.md5(key.encode()).hexdigest()[:10]

def shim_method(key, kind):
    """kind: static | instance | ctor. Повертає (ім'я, дескриптор шима)."""
    nk = (key, kind)
    if nk in names:
        return names[nk]
    owner, rest = key.split('->')
    name, desc = rest[:rest.index('(')], rest[rest.index('('):]
    params, ret = parse_types(desc)
    jn = 'm_' + short(key + kind)
    args = ', '.join('%s a%d' % (jtype(t), i) for i, t in enumerate(params))
    call_args = ', '.join('a%d' % i for i in range(len(params)))
    jo = jtype(owner)
    if kind == 'ctor':
        sig = '(%s)%s' % (''.join(params), owner)
        body = 'if (Build.VERSION.SDK_INT >= 26) return new %s(%s);\n        return null;' % (jo, call_args)
        java.append('    public static %s %s(%s) throws Throwable {\n        %s\n    }' % (jo, jn, args, body))
    elif kind == 'static':
        sig = '(%s)%s' % (''.join(params), ret)
        real = '%s.%s(%s)' % (jo, name, call_args)
        r = ('%s;' % real) if ret == 'V' else 'return (%s) %s;' % (jtype(ret), real)
        fb = OVERRIDES.get(key, default_ret(ret))
        java.append('    public static %s %s(%s) throws Throwable {\n        if (Build.VERSION.SDK_INT >= 26) { %s%s }\n        %s\n    }'
                    % (jtype(ret), jn, args, r, ' return;' if ret == 'V' else '', fb))
    else:
        sig = '(%s%s)%s' % (owner, ''.join(params), ret)
        sargs = jo + ' self' + (', ' + args if args else '')
        real = 'self.%s(%s)' % (name, call_args)
        r = ('%s;' % real) if ret == 'V' else 'return (%s) %s;' % (jtype(ret), real)
        fb = OVERRIDES.get(key, default_ret(ret, owner, True))
        java.append('    public static %s %s(%s) throws Throwable {\n        if (Build.VERSION.SDK_INT >= 26 && self != null) { %s%s }\n        %s\n    }'
                    % (jtype(ret), jn, sargs, r, ' return;' if ret == 'V' else '', fb))
    names[nk] = (jn, sig)
    return names[nk]

def shim_field(fkey, ftype, op):
    """op: sget | sget_value | iget | iput"""
    nk = (fkey, op)
    if nk in names:
        return names[nk]
    owner, fname = fkey.split('->')
    jo, jt = jtype(owner), jtype(ftype)
    jn = 'f_' + short(fkey + op)
    dflt = 'null' if ftype[0] in 'L[' else ('false' if ftype == 'Z' else '(%s) 0' % jt)
    if op in ('sget', 'sget_value'):
        fb = FIELD_VALUE.get(fkey, dflt) if op == 'sget_value' else dflt
        java.append('    public static %s %s() {\n        if (Build.VERSION.SDK_INT >= 26) return %s.%s;\n        return %s;\n    }'
                    % (jt, jn, jo, fname, fb))
        sig = '()' + ftype
    elif op == 'iget':
        java.append('    public static %s %s(%s o) {\n        if (Build.VERSION.SDK_INT >= 26 && o != null) return o.%s;\n        return %s;\n    }'
                    % (jt, jn, jo, fname, dflt))
        sig = '(%s)%s' % (owner, ftype)
    else:
        java.append('    public static void %s(%s o, %s v) {\n        if (Build.VERSION.SDK_INT >= 26 && o != null) o.%s = v;\n    }'
                    % (jn, jo, jt, fname))
        sig = '(%s%s)V' % (owner, ftype)
    names[nk] = (jn, sig)
    return names[nk]

def shim_class(cdesc, op):
    nk = (cdesc, op)
    if nk in names:
        return names[nk]
    jo = jtype(cdesc)
    jn = {'const': 'c_', 'cast': 'k_', 'is': 'i_'}[op] + short(cdesc + op)
    if op == 'const':
        java.append('    public static Class<?> %s() {\n        return Build.VERSION.SDK_INT >= 26 ? %s.class : null;\n    }' % (jn, jo))
        sig = '()Ljava/lang/Class;'
    elif op == 'cast':
        java.append('    public static %s %s(Object o) {\n        if (o == null) return null;\n        return (%s) o;\n    }' % (jo, jn, jo))
        sig = '(Ljava/lang/Object;)' + cdesc
    else:
        java.append('    public static boolean %s(Object o) {\n        return Build.VERSION.SDK_INT >= 26 && o instanceof %s;\n    }' % (jn, jo))
        sig = '(Ljava/lang/Object;)Z'
    names[nk] = (jn, sig)
    return names[nk]

# ---------------------------------------------------------------- переписування smali

INV = re.compile(r'^(\s*)(invoke-(?:virtual|interface|static|direct|super)(?:/range)?)\s*\{([^}]*)\},\s*(L[^;]+;->[^(]+\([^)]*\)\S+)\s*$')
SGET = re.compile(r'^(\s*)(sget(?:-object|-wide|-boolean|-byte|-char|-short)?)\s+(\w+),\s*(L[^;]+;)->([^:]+):(\S+)\s*$')
IGET = re.compile(r'^(\s*)(iget(?:-object|-wide|-boolean|-byte|-char|-short)?)\s+(\w+),\s*(\w+),\s*(L[^;]+;)->([^:]+):(\S+)\s*$')
IPUT = re.compile(r'^(\s*)(iput(?:-object|-wide|-boolean|-byte|-char|-short)?)\s+(\w+),\s*(\w+),\s*(L[^;]+;)->([^:]+):(\S+)\s*$')
CC = re.compile(r'^(\s*)const-class\s+(\w+),\s*(\[*L[^;]+;)\s*$')
CHK = re.compile(r'^(\s*)check-cast\s+(\w+),\s*(L[^;]+;)\s*$')
INS = re.compile(r'^(\s*)instance-of\s+(\w+),\s*(\w+),\s*(L[^;]+;)\s*$')
NEW = re.compile(r'^(\s*)new-instance\s+(\w+),\s*(L[^;]+;)\s*$')

def move_for(t):
    return 'move-result-wide' if t in ('J', 'D') else ('move-result-object' if t[0] in 'L[' else 'move-result')

stats = {'invoke': 0, 'ctor': 0, 'field': 0, 'class': 0, 'skipped_super': 0}

def reg_num(r, locals_):
    return int(r[1:]) + (locals_ if r[0] == 'p' else 0)

def regspec(regs, locals_):
    """Список регістрів -> рядок для invoke-static або /range."""
    if all(reg_num(r, locals_) < 16 for r in regs) and len(regs) <= 5:
        return 'invoke-static', '{%s}' % ', '.join(regs)
    nums = [reg_num(r, locals_) for r in regs]
    if nums == list(range(nums[0], nums[0] + len(nums))):
        return 'invoke-static/range', '{%s .. %s}' % (regs[0], regs[-1])
    return None, None

def expand_range(spec):
    spec = spec.strip()
    if '..' in spec:
        a, b = [x.strip() for x in spec.split('..')]
        pre = a[0]
        return ['%s%d' % (pre, i) for i in range(int(a[1:]), int(b[1:]) + 1)]
    return [x.strip() for x in spec.split(',') if x.strip()]

def next_instr(lines, i):
    for j in range(i + 1, min(len(lines), i + 6)):
        s = lines[j].strip()
        if s and not s.startswith('.line') and not s.startswith(':') and not s.startswith('#'):
            return s
    return ''

def rewrite_method(body, locals_):
    out = []
    pending_new = {}
    tolerant = any('.catch Ljava/lang/NoSuchFieldError;' in l or '.catch Ljava/lang/NoSuchMethodError;' in l
                   or '.catch Ljava/lang/NoClassDefFoundError;' in l for l in body)
    for i, line in enumerate(body):
        m = NEW.match(line)
        if m and m.group(3) in miss_c:
            pending_new[m.group(2)] = m.group(3)
            out.append(m.group(1) + '# new-instance %s (Android 8+) -> шим' % m.group(3))
            stats['ctor'] += 1
            continue
        m = INV.match(line)
        if m:
            ind, op, regs, ref = m.groups()
            if ref in miss_m:
                if op.startswith('invoke-super'):
                    stats['skipped_super'] += 1
                    out.append(line)
                    continue
                rl = expand_range(regs)
                owner = ref.split('->')[0]
                if '-><init>(' in ref:
                    this = rl[0]
                    jn, sig = shim_method(ref, 'ctor')
                    kind, spec = regspec(rl[1:], locals_) if len(rl) > 1 else ('invoke-static', '{}')
                    assert kind, line
                    out.append('%s%s %s, %s->%s%s' % (ind, kind, spec, SHIM, jn, sig))
                    out.append('%smove-result-object %s' % (ind, this))
                    continue
                kindn = 'static' if op.startswith('invoke-static') else 'instance'
                jn, sig = shim_method(ref, kindn)
                kind, spec = regspec(rl, locals_)
                assert kind, line
                out.append('%s%s %s, %s->%s%s' % (ind, kind, spec, SHIM, jn, sig))
                stats['invoke'] += 1
                continue
        if not tolerant:
            m = SGET.match(line)
            if m and (m.group(4) + '->' + m.group(5)) in miss_f:
                ind, op, dst, owner, fname, ftype = m.groups()
                nxt = next_instr(body, i)
                cmp_ = re.match(r'if-(eq|ne)\s+(\w+),\s*(\w+)', nxt) and dst in re.match(r'if-(eq|ne)\s+(\w+),\s*(\w+)', nxt).groups()[1:]
                jn, sig = shim_field(owner + '->' + fname, ftype, 'sget' if cmp_ else 'sget_value')
                out.append('%sinvoke-static {}, %s->%s%s' % (ind, SHIM, jn, sig))
                out.append('%s%s %s' % (ind, move_for(ftype), dst))
                stats['field'] += 1
                continue
            m = IGET.match(line)
            if m and (m.group(5) + '->' + m.group(6)) in miss_f:
                ind, op, dst, obj, owner, fname, ftype = m.groups()
                jn, sig = shim_field(owner + '->' + fname, ftype, 'iget')
                kind, spec = regspec([obj], locals_)
                out.append('%s%s %s, %s->%s%s' % (ind, kind, spec, SHIM, jn, sig))
                out.append('%s%s %s' % (ind, move_for(ftype), dst))
                stats['field'] += 1
                continue
            m = IPUT.match(line)
            if m and (m.group(5) + '->' + m.group(6)) in miss_f:
                ind, op, val, obj, owner, fname, ftype = m.groups()
                jn, sig = shim_field(owner + '->' + fname, ftype, 'iput')
                vals = [val] if ftype not in ('J', 'D') else [val, val[0] + str(int(val[1:]) + 1)]
                assert all(reg_num(r, locals_) < 16 for r in [obj] + vals), line
                out.append('%sinvoke-static {%s}, %s->%s%s' % (ind, ', '.join([obj] + vals), SHIM, jn, sig))
                stats['field'] += 1
                continue
        m = CC.match(line)
        if m and m.group(3).lstrip('[') in miss_c:
            ind, dst, c = m.groups()
            jn, sig = shim_class(c, 'const')
            out.append('%sinvoke-static {}, %s->%s%s' % (ind, SHIM, jn, sig))
            out.append('%smove-result-object %s' % (ind, dst))
            stats['class'] += 1
            continue
        m = CHK.match(line)
        if m and m.group(3) in miss_c:
            ind, r, c = m.groups()
            jn, sig = shim_class(c, 'cast')
            kind, spec = regspec([r], locals_)
            out.append('%s%s %s, %s->%s%s' % (ind, kind, spec, SHIM, jn, sig))
            out.append('%smove-result-object %s' % (ind, r))
            stats['class'] += 1
            continue
        m = INS.match(line)
        if m and m.group(4) in miss_c:
            ind, dst, src, c = m.groups()
            jn, sig = shim_class(c, 'is')
            kind, spec = regspec([src], locals_)
            out.append('%s%s %s, %s->%s%s' % (ind, kind, spec, SHIM, jn, sig))
            out.append('%smove-result %s' % (ind, dst))
            stats['class'] += 1
            continue
        out.append(line)
    return out

changed = 0
for dp, dn, fn in os.walk(SMALI):
    for f in fn:
        if not f.endswith('.smali'):
            continue
        p = os.path.join(dp, f)
        lines = open(p, encoding='utf-8').read().split('\n')
        res, i, touched = [], 0, False
        while i < len(lines):
            l = lines[i]
            if l.startswith('.method'):
                j = i
                while not lines[j].startswith('.end method'):
                    j += 1
                body = lines[i:j + 1]
                locals_ = 0
                for b in body[:4]:
                    mm = re.match(r'\s*\.locals\s+(\d+)', b)
                    if mm:
                        locals_ = int(mm.group(1))
                    mm = re.match(r'\s*\.registers\s+(\d+)', b)
                    if mm:
                        # .registers = locals + params; рахуємо параметри
                        sigm = re.search(r'\(([^)]*)\)', l)
                        ps, _ = parse_types('(%s)V' % sigm.group(1))
                        n_par = sum(regs_of(t) for t in ps) + (0 if ' static ' in l else 1)
                        locals_ = int(mm.group(1)) - n_par
                nb = rewrite_method(body, locals_)
                if nb != body:
                    touched = True
                res.extend(nb)
                i = j + 1
            else:
                res.append(l)
                i += 1
        if touched:
            open(p, 'w', encoding='utf-8').write('\n'.join(res))
            changed += 1

# Допоміжне: переміщення файлу без java.nio (для DataStore на Android 7)
java.append('''    public static void moveFile(java.io.File src, java.io.File dst) throws java.io.IOException {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                java.nio.file.Files.move(src.toPath(), dst.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (java.io.IOException e) {
                // далі: звичайне перейменування
            }
        }
        if (src.renameTo(dst)) return;
        if (dst.exists() && dst.delete() && src.renameTo(dst)) return;
        throw new java.io.IOException("Unable to rename " + src + " to " + dst);
    }''')

os.makedirs(os.path.dirname(JAVA_OUT), exist_ok=True)
with open(JAVA_OUT, 'w', encoding='utf-8') as fo:
    fo.write('package %s;\n\nimport android.os.Build;\n\n' % SHIM_PKG)
    fo.write('/** Згенеровано backport.py: API Android 8 з запасною поведінкою для Android 7. */\n')
    fo.write('@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})\npublic final class %s {\n\n' % SHIM_CLS)
    fo.write('\n\n'.join(java))
    fo.write('\n}\n')
print('files changed:', changed, stats, 'shims:', len(java))
