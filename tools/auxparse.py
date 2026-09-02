#!/usr/bin/env python3
"""Parse iXcoin `headers` payloads including AuxPoW, and verify the framing by
checking that every header links to its parent and that the payload is consumed
exactly. Doubles as the reference for the Android SPV parser."""
import socket, struct, hashlib, time, random, sys, os, json

MAGIC = bytes.fromhex('f1bab6db'); PORT = 8337; PROTO = 110014
UA = b'/ixcoin-auxparse:0.1/'
VERSION_AUXPOW_BIT = 0x100
VERSION_CHAIN_START = 0x10000

def dsha(b): return hashlib.sha256(hashlib.sha256(b).digest()).digest()
def h2s(b):  return b[::-1].hex()
def msg(cmd,p=b''): return MAGIC+cmd.encode().ljust(12,b'\0')+struct.pack('<I',len(p))+dsha(p)[:4]+p
def varint(n):
    if n<0xfd: return struct.pack('<B',n)
    if n<=0xffff: return b'\xfd'+struct.pack('<H',n)
    if n<=0xffffffff: return b'\xfe'+struct.pack('<I',n)
    return b'\xff'+struct.pack('<Q',n)

class R:
    def __init__(self,b,o=0): self.b=b; self.o=o
    def take(self,n):
        if self.o+n > len(self.b): raise EOFError('want %d have %d'%(n,len(self.b)-self.o))
        v=self.b[self.o:self.o+n]; self.o+=n; return v
    def u8(self):  return self.take(1)[0]
    def u16(self): return struct.unpack('<H',self.take(2))[0]
    def u32(self): return struct.unpack('<I',self.take(4))[0]
    def i32(self): return struct.unpack('<i',self.take(4))[0]
    def u64(self): return struct.unpack('<Q',self.take(8))[0]
    def h256(self): return self.take(32)
    def varint(self):
        n=self.u8()
        if n<0xfd: return n
        if n==0xfd: return self.u16()
        if n==0xfe: return self.u32()
        return self.u64()
    def vbytes(self): return self.take(self.varint())

def read_tx(r):
    """Bitcoin transaction, tolerating the segwit marker."""
    start=r.o
    ver=r.i32()
    nin=r.varint()
    segwit=False
    if nin==0:                       # segwit marker 0x00 followed by flag
        flag=r.u8()
        if flag!=1: raise ValueError('bad segwit flag %d'%flag)
        segwit=True
        nin=r.varint()
    vin=[]
    for _ in range(nin):
        prev_hash=r.h256(); prev_n=r.u32(); script=r.vbytes(); seq=r.u32()
        vin.append({'hash':prev_hash,'n':prev_n,'script':script,'seq':seq})
    nout=r.varint()
    vout=[]
    for _ in range(nout):
        value=r.u64(); spk=r.vbytes(); vout.append({'value':value,'script':spk})
    if segwit:
        for _ in range(nin):
            for _ in range(r.varint()): r.vbytes()
    lock=r.u32()
    return {'version':ver,'vin':vin,'vout':vout,'locktime':lock,
            'segwit':segwit,'raw':r.b[start:r.o]}

def read_auxpow(r):
    tx = read_tx(r)                              # parent coinbase
    parent_hash = r.h256()                       # CMerkleTx::hashBlock
    cb_branch = [r.h256() for _ in range(r.varint())]
    cb_index  = r.i32()
    chain_branch = [r.h256() for _ in range(r.varint())]
    chain_index  = r.i32()
    parent_header = r.take(80)
    return {'coinbase':tx,'parent_block_hash':parent_hash,
            'coinbase_branch':cb_branch,'coinbase_index':cb_index,
            'chain_branch':chain_branch,'chain_index':chain_index,
            'parent_header':parent_header}

def read_header(r):
    raw80 = r.take(80)
    nver = struct.unpack('<i', raw80[:4])[0]
    hdr = {'raw':raw80, 'version':nver,
           'hash':dsha(raw80),
           'prev':raw80[4:36], 'merkle':raw80[36:68],
           'time':struct.unpack('<I',raw80[68:72])[0],
           'bits':struct.unpack('<I',raw80[72:76])[0],
           'nonce':struct.unpack('<I',raw80[76:80])[0],
           'chain_id':(nver >> 16) & 0xffff,
           'auxpow':None}
    if nver & VERSION_AUXPOW_BIT:
        hdr['auxpow'] = read_auxpow(r)
    hdr['txcount'] = r.varint()                  # always 0 in a headers message
    return hdr

def merkle_root_from_branch(leaf, branch, index):
    h = leaf
    for step in branch:
        if index & 1: h = dsha(step + h)
        else:         h = dsha(h + step)
        index >>= 1
    return h

# ---------------- network ----------------
def frames(buf):
    out=[]
    while len(buf)>=24:
        if buf[:4]!=MAGIC:
            i=buf.find(MAGIC,1)
            if i<0: return out,b''
            buf=buf[i:]; continue
        ln=struct.unpack_from('<I',buf,16)[0]
        if len(buf)<24+ln: break
        out.append((buf[4:16].rstrip(b'\0').decode(),buf[24:24+ln])); buf=buf[24+ln:]
    return out,buf

def fetch_headers(ip, locator_hex):
    s=socket.create_connection((ip,PORT),15); s.settimeout(40)
    na=struct.pack('<Q',0)+b'\0'*10+b'\xff\xff'+socket.inet_aton('0.0.0.0')+struct.pack('>H',0)
    ver=(struct.pack('<iQq',PROTO,1,int(time.time()))+na+na
         +struct.pack('<Q',random.getrandbits(64))+varint(len(UA))+UA+struct.pack('<i',0)+b'\x01')
    s.sendall(msg('version',ver))
    buf=b''; sent=False; t=time.time()+60
    while time.time()<t:
        d=s.recv(262144)
        if not d: break
        buf+=d; ms,buf=frames(buf)
        for cmd,p in ms:
            if cmd=='version': s.sendall(msg('verack'))
            elif cmd=='verack' and not sent:
                loc=bytes.fromhex(locator_hex)[::-1]
                s.sendall(msg('getheaders',struct.pack('<I',PROTO)+varint(1)+loc+b'\0'*32)); sent=True
            elif cmd=='ping': s.sendall(msg('pong',p))
            elif cmd=='headers': s.close(); return p
    s.close(); return None

def main():
    ip  = os.environ.get('IP','18.217.178.46')
    loc = os.environ.get('LOC','000000000048d833ff85e92a4fecd524a93b06ea0c20b1e8b4b154987fd01e73')
    p = fetch_headers(ip, loc)
    if not p: print("no headers"); return 1
    r = R(p); n = r.varint()
    print("payload %d bytes, %d headers" % (len(p), n))
    hdrs=[]; aux=0; linked=0; cbok=0
    for i in range(n):
        h = read_header(r)
        hdrs.append(h)
        if h['auxpow']: aux += 1
        if i and h['prev'] == hdrs[i-1]['hash']: linked += 1
    print("consumed %d / %d bytes  (exact=%s)" % (r.o, len(p), r.o == len(p)))
    print("auxpow headers: %d / %d" % (aux, n))
    print("prev-hash links verified: %d / %d" % (linked, n-1))
    # verify the aux coinbase merkle branch reproduces the parent block's merkle root
    for h in hdrs:
        a = h['auxpow']
        if not a: continue
        root = merkle_root_from_branch(dsha(a['coinbase']['raw']), a['coinbase_branch'], a['coinbase_index'])
        if root == a['parent_header'][36:68]: cbok += 1
    print("aux coinbase merkle roots matching parent header: %d / %d" % (cbok, aux))
    sample = next(h for h in hdrs if h['auxpow'])
    a = sample['auxpow']
    print("\nsample auxpow header %s" % h2s(sample['hash']))
    print("  nVersion       0x%08x (chain_id=%d)" % (sample['version'] & 0xffffffff, sample['chain_id']))
    print("  parent hash    %s" % h2s(dsha(a['parent_header'])))
    print("  coinbase br    %d hashes, index %d" % (len(a['coinbase_branch']), a['coinbase_index']))
    print("  chain br       %d hashes, index %d" % (len(a['chain_branch']), a['chain_index']))
    print("  coinbase segwit=%s, %d bytes" % (a['coinbase']['segwit'], len(a['coinbase']['raw'])))
    return 0

if __name__=='__main__': sys.exit(main())
