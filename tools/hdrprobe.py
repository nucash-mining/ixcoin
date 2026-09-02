#!/usr/bin/env python3
"""Ask a live iXcoin node for headers and report how they are framed on the wire.
Determines what an SPV client has to parse (in particular: AuxPoW payloads)."""
import socket, struct, hashlib, time, random, sys, binascii

MAGIC = bytes.fromhex('f1bab6db'); PORT = 8337; PROTO = 110014
UA = b'/ixcoin-hdrprobe:0.1/'
GENESIS = bytes.fromhex(__import__('os').environ.get('LOC','0000000001534ef8893b025b9c1da67250285e35c9f76cae36a4904fdf72c591'))[::-1]

def dsha(b): return hashlib.sha256(hashlib.sha256(b).digest()).digest()
def msg(cmd,p=b''): return MAGIC+cmd.encode().ljust(12,b'\0')+struct.pack('<I',len(p))+dsha(p)[:4]+p
def varint(n):
    if n<0xfd: return struct.pack('<B',n)
    if n<=0xffff: return b'\xfd'+struct.pack('<H',n)
    if n<=0xffffffff: return b'\xfe'+struct.pack('<I',n)
    return b'\xff'+struct.pack('<Q',n)
def rd_varint(b,o):
    n=b[o]; o+=1
    if n<0xfd: return n,o
    if n==0xfd: return struct.unpack_from('<H',b,o)[0],o+2
    if n==0xfe: return struct.unpack_from('<I',b,o)[0],o+4
    return struct.unpack_from('<Q',b,o)[0],o+8
def netaddr(): return struct.pack('<Q',0)+b'\0'*10+b'\xff\xff'+socket.inet_aton('0.0.0.0')+struct.pack('>H',0)

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

def main(ip, locator):
    s=socket.create_connection((ip,PORT),15); s.settimeout(25)
    ver=(struct.pack('<iQq',PROTO,1,int(time.time()))+netaddr()+netaddr()
         +struct.pack('<Q',random.getrandbits(64))+varint(len(UA))+UA+struct.pack('<i',0)+b'\x01')
    s.sendall(msg('version',ver))
    buf=b''; sent=False; t=time.time()+30
    while time.time()<t:
        d=s.recv(65536)
        if not d: break
        buf+=d; ms,buf=frames(buf)
        for cmd,p in ms:
            if cmd=='version':
                s.sendall(msg('verack'))
            elif cmd=='verack' and not sent:
                gh=struct.pack('<I',PROTO)+varint(1)+locator+b'\x00'*32
                s.sendall(msg('getheaders',gh)); sent=True
            elif cmd=='ping':
                s.sendall(msg('pong',p))
            elif cmd=='headers':
                n,o=rd_varint(p,0)
                print("headers message: %d bytes, count=%d" % (len(p),n))
                sizes=[]
                for i in range(min(n,2000)):
                    start=o
                    if o+80>len(p): print("  truncated at %d"%i); break
                    hdr=p[o:o+80]; o+=80
                    nver=struct.unpack_from('<i',hdr,0)[0]
                    txn,o=rd_varint(p,o)   # tx count (0 for headers)
                    sizes.append((i,nver,o-start,txn))
                for row in sizes[:4]+sizes[-4:]:
                    i,nver,sz,txn=row
                    print("  hdr %-6d nVersion=0x%08x  wire_size=%3d  txcount_field=%d  auxpow_bit=%s chainid=%d"
                          % (i,nver&0xffffffff,sz,txn,bool(nver & 0x100),(nver>>16)&0xffff))
                allsz=set(x[2] for x in sizes)
                print("  distinct wire sizes:", sorted(allsz))
                s.close(); return
    print("no headers received")

if __name__=='__main__':
    main(sys.argv[1] if len(sys.argv)>1 else '18.217.178.46', GENESIS)
