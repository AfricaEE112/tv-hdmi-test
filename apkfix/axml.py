# Мінімальний розбір бінарного AndroidManifest.xml
import struct, sys, zipfile
def parse(data):
    out=[]
    def u16(o): return struct.unpack_from('<H',data,o)[0]
    def u32(o): return struct.unpack_from('<I',data,o)[0]
    pos=8; strings=[]; resids=[]; depth=0
    while pos < len(data):
        ctype=u16(pos); hsize=u16(pos+2); csize=u32(pos+4)
        if ctype==0x0001:
            n=u32(pos+8); flags=u32(pos+16); sstart=u32(pos+20)
            utf8=flags & (1<<8)
            offs=[u32(pos+28+4*i) for i in range(n)]
            base=pos+sstart
            for o in offs:
                p=base+o
                if utf8:
                    l=data[p]; p+=1
                    if l&0x80: p+=1
                    l=data[p]; p+=1
                    if l&0x80: l=((l&0x7f)<<8)|data[p]; p+=1
                    strings.append(data[p:p+l].decode('utf-8','replace'))
                else:
                    l=u16(p); p+=2
                    if l&0x8000: l=((l&0x7fff)<<16)|u16(p); p+=2
                    strings.append(data[p:p+2*l].decode('utf-16le','replace'))
        elif ctype==0x0180:
            resids=[u32(pos+8+4*i) for i in range((csize-8)//4)]
        elif ctype==0x0102:
            name=strings[u32(pos+20)]
            acount=u16(pos+28); astart=u16(pos+24)
            attrs=[]
            for i in range(acount):
                a=pos+16+astart+i*20
                an=u32(a+4); raw=u32(a+8); dtype=data[a+15]; dval=u32(a+16)
                aname=strings[an] if an<len(strings) else '?'
                if raw!=0xffffffff: v=strings[raw]
                elif dtype==0x12: v='true' if dval else 'false'
                elif dtype in (0x10,0x11): v=str(dval if dval<2**31 else dval-2**32)
                elif dtype==0x01: v='@0x%08x'%dval
                else: v='(%d)0x%x'%(dtype,dval)
                attrs.append('%s=%s'%(aname,v))
            out.append('  '*depth+'<'+name+' '+' '.join(attrs)+'>')
            depth+=1
        elif ctype==0x0103:
            depth-=1
        pos+=csize
    return out
z=zipfile.ZipFile(sys.argv[1])
print('\n'.join(parse(z.read('AndroidManifest.xml'))))
