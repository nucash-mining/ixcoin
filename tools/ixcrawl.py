#!/usr/bin/env python3
"""Minimal IXCoin P2P crawler: getaddr-walks the network and reports reachable nodes."""
import socket, struct, hashlib, time, random, sys, json, threading, queue

MAGIC = bytes.fromhex('f1bab6db')
PORT  = 8337
PROTO = 70015
UA    = b'/ixcoin-seedcrawl:0.1/'

def dsha(b): return hashlib.sha256(hashlib.sha256(b).digest()).digest()

def msg(cmd, payload=b''):
    c = cmd.encode().ljust(12, b'\x00')
    return MAGIC + c + struct.pack('<I', len(payload)) + dsha(payload)[:4] + payload

def varint(n):
    if n < 0xfd: return struct.pack('<B', n)
    if n <= 0xffff: return b'\xfd' + struct.pack('<H', n)
    if n <= 0xffffffff: return b'\xfe' + struct.pack('<I', n)
    return b'\xff' + struct.pack('<Q', n)

def rd_varint(b, o):
    n = b[o]; o += 1
    if n < 0xfd: return n, o
    if n == 0xfd: return struct.unpack_from('<H', b, o)[0], o+2
    if n == 0xfe: return struct.unpack_from('<I', b, o)[0], o+4
    return struct.unpack_from('<Q', b, o)[0], o+8

def netaddr(ip='0.0.0.0', port=0):
    return struct.pack('<Q', 0) + b'\x00'*10 + b'\xff\xff' + socket.inet_aton(ip) + struct.pack('>H', port)

def version_payload():
    return (struct.pack('<iQq', PROTO, 1, int(time.time())) + netaddr() + netaddr()
            + struct.pack('<Q', random.getrandbits(64)) + varint(len(UA)) + UA
            + struct.pack('<i', 0) + b'\x01')

def recv_msgs(sock, buf):
    """Yield (cmd, payload) from buf, returning the leftover."""
    out = []
    while len(buf) >= 24:
        if buf[:4] != MAGIC:
            i = buf.find(MAGIC, 1)
            if i < 0: buf = b''; break
            buf = buf[i:]; continue
        ln = struct.unpack_from('<I', buf, 16)[0]
        if len(buf) < 24 + ln: break
        cmd = buf[4:16].rstrip(b'\x00').decode('ascii', 'replace')
        out.append((cmd, buf[24:24+ln]))
        buf = buf[24+ln:]
    return out, buf

def parse_addr(p):
    try:
        n, o = rd_varint(p, 0)
    except Exception:
        return []
    res = []
    for _ in range(min(n, 1000)):
        if o + 30 > len(p): break
        svc = struct.unpack_from('<Q', p, o+4)[0]
        ipb = p[o+12:o+28]; port = struct.unpack_from('>H', p, o+28)[0]
        o += 30
        if ipb[:12] == b'\x00'*10 + b'\xff\xff':
            ip = socket.inet_ntoa(ipb[12:])
            if not ip.startswith(('0.', '127.', '10.', '192.168.', '169.254.')):
                res.append((ip, port, svc))
        elif ipb[:6] == bytes.fromhex('fd87d87eeb43'):
            pass  # tor onion, unreachable without a proxy
        else:
            try: res.append((socket.inet_ntop(socket.AF_INET6, ipb), port, svc))
            except Exception: pass
    return res

def probe(ip, port, timeout=6, want_addrs=True):
    """Handshake with a node. Returns (info_dict|None, [peers])."""
    peers, info = [], None
    try:
        s = socket.create_connection((ip, port), timeout)
        s.settimeout(timeout)
        s.sendall(msg('version', version_payload()))
        buf = b''; deadline = time.time() + timeout * 2.5; sent_getaddr = False
        while time.time() < deadline:
            try: d = s.recv(65536)
            except socket.timeout: break
            if not d: break
            buf += d
            msgs, buf = recv_msgs(s, buf)
            for cmd, p in msgs:
                if cmd == 'version':
                    ver = struct.unpack_from('<i', p, 0)[0]
                    o = 80; ln, o = rd_varint(p, o)
                    ua = p[o:o+ln].decode('ascii', 'replace'); o += ln
                    h = struct.unpack_from('<i', p, o)[0]
                    info = {'ip': ip, 'port': port, 'proto': ver, 'subver': ua, 'height': h}
                    s.sendall(msg('verack'))
                elif cmd == 'verack' and want_addrs and not sent_getaddr:
                    s.sendall(msg('getaddr')); sent_getaddr = True
                elif cmd == 'ping':
                    s.sendall(msg('pong', p))
                elif cmd == 'addr':
                    peers += parse_addr(p)
                    if len(peers) > 50: deadline = 0
            if info and not want_addrs: break
        s.close()
    except Exception:
        pass
    return info, peers

def main():
    seeds = sys.argv[1:] or ['18.217.178.46', '91.121.45.149']
    todo = queue.Queue()
    seen = set()
    for x in seeds:
        todo.put((x, PORT)); seen.add((x, PORT))
    good, lock = {}, threading.Lock()

    def worker():
        while True:
            try: ip, port = todo.get(timeout=8)
            except queue.Empty: return
            info, peers = probe(ip, port)
            with lock:
                if info:
                    good[(ip, port)] = info
                    print(f"  UP  {ip}:{port} {info['subver']} h={info['height']}", flush=True)
                for pip, pport, svc in peers:
                    k = (pip, pport)
                    if k not in seen and len(seen) < 4000:
                        seen.add(k); todo.put(k)
            todo.task_done()

    ts = [threading.Thread(target=worker, daemon=True) for _ in range(60)]
    [t.start() for t in ts]
    [t.join() for t in ts]
    print(f"\nprobed {len(seen)} candidates, {len(good)} reachable")
    json.dump(sorted(good.values(), key=lambda x: -x['height']), open(sys.argv[0]+'.out.json','w'), indent=1)
    print("wrote", sys.argv[0]+'.out.json')

if __name__ == '__main__':
    main()
