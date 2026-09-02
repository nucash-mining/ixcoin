#!/usr/bin/env python3
"""Validate the iXcoin retarget algorithm against the real chain by recomputing
nBits at retarget heights and comparing to what the chain actually has."""
import json, subprocess, sys, os

CLI = os.path.expanduser('~/Downloads/IXcoin/build/scratch/src/ixcoin-cli')
DD  = '/tmp/claude-1000/-home-nuts-Documents/700056b7-9ef9-46e9-92e3-40b491493691/scratchpad/ixtest'

TARGET_SPACING = 600
TS_ORIG, TS_REV = 14*24*3600, 24*3600
REVISED_HEIGHT, FULL_WINDOW_HEIGHT = 20055, 43000
POW_LIMIT = 0x00000000FFFF0000000000000000000000000000000000000000000000000000

def rpc(*args):
    out = subprocess.check_output([CLI, '-datadir='+DD] + list(args))
    return out.decode().strip()

_cache = {}
def header(h):
    if h not in _cache:
        _cache[h] = json.loads(rpc('getblockheader', rpc('getblockhash', str(h))))
    return _cache[h]

def revised(h): return h > REVISED_HEIGHT
def timespan_for(h): return TS_REV if revised(h) else TS_ORIG
def interval_for(h): return timespan_for(h) // TARGET_SPACING

def decode_compact(bits):
    size = bits >> 24
    word = bits & 0x007fffff
    if size <= 3: return word >> (8 * (3 - size))
    return word << (8 * (size - 3))

def encode_compact(n):
    if n == 0: return 0
    size = (n.bit_length() + 7) // 8
    if size <= 3: compact = n << (8 * (3 - size))
    else:         compact = n >> (8 * (size - 3))
    if compact & 0x00800000:
        compact >>= 8; size += 1
    return compact | (size << 24)

def damp(actual, target, rev):
    if not rev:
        return max(target // 4, min(actual, target * 4))
    two = target // 50
    if actual < target:
        if actual < two * 16: return two * 45
        if actual < two * 32: return two * 47
        return two * 49
    if actual > target * 4: return target * 4
    return actual

def expected_bits(height):
    prev = header(height - 1)
    itv = interval_for(height)
    back = itv - 1
    if height >= FULL_WINDOW_HEIGHT and height != itv:
        back = itv
    first = header(height - 1 - back)
    ts = timespan_for(height)
    actual = prev['time'] - first['time']
    actual = damp(actual, ts, revised(height))
    new = decode_compact(int(prev['bits'], 16)) * actual // ts
    if new > POW_LIMIT: new = POW_LIMIT
    return encode_compact(new)

def main():
    tip = int(rpc('getblockcount'))
    tests = []
    # retarget heights sampled across the chain's history
    for h in [2016, 20160, 20304, 43056, 43200, 100080, 250128, 500112, 750096, 1000080]:
        itv = interval_for(h)
        if h % itv != 0:
            h = ((h // itv) + 1) * itv     # snap to the next real retarget point
        if 0 < h <= tip: tests.append(h)
    # plus the most recent retarget
    itv = interval_for(tip); tests.append((tip // itv) * itv)
    ok = bad = 0
    for h in sorted(set(tests)):
        try:
            exp = expected_bits(h)
            act = int(header(h)['bits'], 16)
        except Exception as e:
            print("  h=%-9d ERROR %s" % (h, e)); bad += 1; continue
        good = exp == act
        ok, bad = ok + good, bad + (not good)
        print("  h=%-9d interval=%-5d expected=%08x actual=%08x  %s"
              % (h, interval_for(h), exp, act, "OK" if good else "MISMATCH"))
    print("\n%d/%d retarget points reproduced" % (ok, ok + bad))
    # also verify difficulty is carried over between retargets
    carry_ok = carry_bad = 0
    for h in [100000, 500000, 900000, tip - 5]:
        itv = interval_for(h)
        if h % itv == 0: h += 1
        a, b = header(h - 1)['bits'], header(h)['bits']
        if a == b: carry_ok += 1
        else: carry_bad += 1; print("  carry MISMATCH at %d: %s -> %s" % (h, a, b))
    print("%d/%d non-retarget heights carry difficulty unchanged" % (carry_ok, carry_ok + carry_bad))
    return 0 if bad == 0 and carry_bad == 0 else 1

if __name__ == '__main__': sys.exit(main())
