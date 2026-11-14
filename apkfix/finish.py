# Додати classes2.dex (шими), підписати v1 нашим ключем, вирівняти по 4 байти
import sys, zipfile, subprocess, struct, os
src, shim, out = sys.argv[1], sys.argv[2], sys.argv[3]
tmp = out + '.tmp.apk'
with zipfile.ZipFile(src) as zi, zipfile.ZipFile(tmp, 'w') as zo:
    for info in zi.infolist():
        if info.filename.startswith('META-INF/') and info.filename.split('/')[-1].split('.')[-1] in ('SF', 'RSA', 'DSA', 'EC', 'MF'):
            continue
        zo.writestr(info, zi.read(info.filename), compress_type=info.compress_type)
    zo.write(shim, 'classes2.dex', compress_type=zipfile.ZIP_DEFLATED)
subprocess.check_call(['jarsigner', '-keystore', sys.argv[4], '-storepass', 'tvlauncher', '-keypass', 'tvlauncher',
                       '-digestalg', 'SHA-256', '-sigalg', 'SHA256withRSA', '-sigfile', 'CERT', tmp, 'tv'],
                      stdout=subprocess.DEVNULL)
# Вирівнювання STORED-записів (як zipalign): дані з адреси, кратної 4
with zipfile.ZipFile(tmp) as zi, open(out, 'wb') as fo:
    zo = zipfile.ZipFile(fo, 'w')
    for info in zi.infolist():
        data = zi.read(info.filename)
        ni = zipfile.ZipInfo(info.filename, date_time=info.date_time)
        ni.compress_type = info.compress_type
        ni.external_attr = info.external_attr
        if info.compress_type == zipfile.ZIP_STORED:
            off = fo.tell() + 30 + len(info.filename.encode())
            pad = (-(off + 4)) % 4
            ni.extra = struct.pack('<HHH', 0xd935, 2 + pad, 4) + b'\0' * pad
            pad2 = (-(off + len(ni.extra))) % 4
            if pad2:
                ni.extra = struct.pack('<HHH', 0xd935, 2 + pad + pad2, 4) + b'\0' * (pad + pad2)
        zo.writestr(ni, data)
    zo.close()
os.remove(tmp)
