#!/usr/bin/env python3
import cairosvg, io, os, sys
from PIL import Image
B = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(B, 'out'); os.makedirs(OUT, exist_ok=True)

MASTER = os.path.join(B, 'ixcoin-logo.png')


def render(src, px):
    """Rasterise a brand source at px*px.

    The mainnet mark is a raster master (ixcoin-logo.png); the testnet variant is
    still SVG. Downscaling the master with LANCZOS keeps the coin's bevel and the
    soft outer halo readable all the way down to 16px, which a nearest-neighbour
    or bilinear reduction turns to mush.
    """
    if src.endswith('.png'):
        return Image.open(src).convert('RGBA').resize((px, px), Image.LANCZOS)
    buf = io.BytesIO()
    cairosvg.svg2png(url=src, write_to=buf, output_width=px, output_height=px)
    buf.seek(0)
    return Image.open(buf).convert('RGBA')

def write_xpm(img, path, name):
    """XPM3 writer: PIL cannot save XPM, and the Linux desktop entries reference them."""
    im = img.convert('RGBA')
    q = im.convert('RGB').quantize(colors=255, dither=Image.Dither.NONE)
    pal = q.getpalette() or []
    ncolors = min(len(pal) // 3, 255)
    alpha = im.getchannel('A')
    printable = "".join(chr(c) for c in range(0x20, 0x7f) if chr(c) not in '"\\')
    syms = [a + b for a in printable for b in printable]
    syms = [s for s in syms if s.strip()]  # never collide with the transparent symbol
    blank = "  "
    used = syms[:ncolors]
    w, h = im.size
    lines = [f'"{w} {h} {ncolors + 1} 2"', f'"{blank}\tc None"']
    for i in range(ncolors):
        r, g, b = pal[i * 3:i * 3 + 3]
        lines.append(f'"{used[i]}\tc #{r:02X}{g:02X}{b:02X}"')
    qd, ad = q.load(), alpha.load()
    for y in range(h):
        lines.append('"' + "".join(
            blank if ad[x, y] < 128 else used[min(qd[x, y], ncolors - 1)]
            for x in range(w)) + '"')
    with open(path, 'w') as f:
        f.write("/* XPM */\nstatic char * %s_xpm[] = {\n%s};\n" % (name, ",\n".join(lines)))

main = MASTER if os.path.exists(MASTER) else os.path.join(B, 'ixcoin.svg')
test = os.path.join(B, 'ixcoin-testnet.svg')

# PNGs
for px in (16, 32, 64, 128, 256, 512, 1024):
    render(main, px).save(f'{OUT}/ixcoin{px}.png')
render(test, 256).save(f'{OUT}/ixcoin_testnet256.png')

# XPMs for the Linux desktop entries
for px in (16, 32, 64, 128, 256):
    write_xpm(render(main, px), f'{OUT}/ixcoin{px}.xpm', f'ixcoin{px}')

# Windows .ico (multi-resolution)
sizes = [16, 24, 32, 48, 64, 128, 256]
render(main, 256).save(f'{OUT}/ixcoin.ico', format='ICO',
                       sizes=[(s, s) for s in sizes])
render(test, 256).save(f'{OUT}/ixcoin_testnet.ico', format='ICO',
                       sizes=[(s, s) for s in sizes])

# macOS .icns
try:
    render(main, 1024).save(f'{OUT}/ixcoin.icns', format='ICNS')
except Exception as e:
    print('icns skipped:', e)

print('wrote:', sorted(os.listdir(OUT)))
