#!/usr/bin/env python3
"""Minimal RFB (VNC) client that grabs one framebuffer image. Used to screenshot
Qt apps running on the offscreen `-platform vnc` display."""
import socket, struct, sys, time
from PIL import Image

def recvn(s, n):
    b = b''
    while len(b) < n:
        d = s.recv(n - len(b))
        if not d:
            raise IOError('short read: wanted %d, got %d' % (n, len(b)))
        b += d
    return b

def grab(host='127.0.0.1', port=5900, out='shot.png', timeout=20):
    s = socket.create_connection((host, port), timeout)
    s.settimeout(timeout)

    ver = recvn(s, 12)                      # e.g. b'RFB 003.008\n'
    try:
        minor = int(ver[8:11])
    except ValueError:
        minor = 3
    # Qt's VNC platform plugin only speaks 3.3, which has a different (older)
    # security handshake than 3.7+.
    minor = 3 if minor < 7 else (8 if minor >= 8 else 7)
    s.sendall(b'RFB 003.%03d\n' % minor)

    if minor == 3:
        sec = struct.unpack('>I', recvn(s, 4))[0]
        if sec == 0:
            ln = struct.unpack('>I', recvn(s, 4))[0]
            raise IOError('server refused: %s' % recvn(s, ln).decode('utf8', 'replace'))
        if sec != 1:
            raise IOError('server requires auth (type %d)' % sec)
    else:
        n = recvn(s, 1)[0]
        if n == 0:                          # failure: 4-byte reason length + text
            ln = struct.unpack('>I', recvn(s, 4))[0]
            raise IOError('server refused: %s' % recvn(s, ln).decode('utf8', 'replace'))
        types = recvn(s, n)
        if 1 not in types:
            raise IOError('server requires auth, types=%s' % list(types))
        s.sendall(b'\x01')                  # security type 1 = None
        res = struct.unpack('>I', recvn(s, 4))[0]
        if res != 0:
            raise IOError('security handshake failed (%d)' % res)

    s.sendall(b'\x01')                      # ClientInit, shared
    w, h = struct.unpack('>HH', recvn(s, 4))
    recvn(s, 16)                            # server pixel format (we override it)
    nl = struct.unpack('>I', recvn(s, 4))[0]
    recvn(s, nl)

    # SetPixelFormat: 32bpp, depth 24, little-endian, true colour, RGB at 16/8/0.
    # Little-endian puts the bytes on the wire as B,G,R,X -> PIL raw mode BGRX.
    fmt = struct.pack('>BBBBHHHBBBxxx', 32, 24, 0, 1, 255, 255, 255, 16, 8, 0)
    s.sendall(b'\x00\x00\x00\x00' + fmt)
    # SetEncodings: Raw only
    s.sendall(struct.pack('>BxHi', 2, 1, 0))

    img = Image.new('RGB', (w, h), (0, 0, 0))
    deadline = time.time() + timeout
    # full (non-incremental) update
    s.sendall(struct.pack('>BBHHHH', 3, 0, 0, 0, w, h))
    covered = 0
    while covered < w * h and time.time() < deadline:
        msg = recvn(s, 1)[0]
        if msg != 0:                        # ignore bell / cut-text / colour-map
            if msg == 2:
                continue
            if msg == 3:
                recvn(s, 3); ln = struct.unpack('>I', recvn(s, 4))[0]; recvn(s, ln); continue
            if msg == 1:
                recvn(s, 3); nc = struct.unpack('>H', recvn(s, 2))[0]; recvn(s, nc * 6); continue
            continue
        recvn(s, 1)
        nrects = struct.unpack('>H', recvn(s, 2))[0]
        for _ in range(nrects):
            rx, ry, rw, rh = struct.unpack('>HHHH', recvn(s, 8))
            enc = struct.unpack('>i', recvn(s, 4))[0]
            if enc != 0:
                raise IOError('unexpected encoding %d' % enc)
            data = recvn(s, rw * rh * 4)
            if rw and rh:
                tile = Image.frombytes('RGB', (rw, rh), data, 'raw', 'BGRX')
                img.paste(tile, (rx, ry))
                covered += rw * rh
    s.close()
    img.save(out)
    return w, h

if __name__ == '__main__':
    out = sys.argv[1] if len(sys.argv) > 1 else 'shot.png'
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 5900
    print(grab(port=port, out=out), '->', out)
