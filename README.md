# iXcoin wallets

Desktop, server and mobile wallets for **iXcoin (IXC)** — a 2011 Bitcoin fork that
has been merged-mined with Bitcoin since block 45,000.

| Platform | What it is | State |
|---|---|---|
| Linux | `ixcoin-qt`, `ixcoind`, `ixcoin-cli`, `ixcoin-tx` | builds and runs, fully synced |
| Windows (x86-64) | the same four, cross-compiled, fully static | builds and runs |
| Android | `net.ixcoin.wallet` — an SPV (light) wallet | builds, installs, syncs |
| macOS / iOS | see [Porting](#porting) | not built here |

```
ixcoin-core/     iXcoin Core 0.14.1 with the fixes below
android-wallet/  Android SPV wallet (Kotlin + Jetpack Compose)
brand/           logo source + generated icon sets
tools/           network crawler, AuxPoW parser, difficulty checker
```

---

## Why this fork of the source exists

Upstream iXcoin Core 0.14.1 is from 2017 and does not build on a current
toolchain. These are the changes, all of them necessary:

**Toolchain (Ubuntu 22.04, gcc 10, Qt 5.15, OpenSSL 3.0)**

- `httpserver.cpp` — add `<deque>`; newer libstdc++ no longer pulls it in transitively.
- boost ≥ 1.73 requires comparators passed to `multi_index` to be const-callable:
  `miner.h`, `txmempool.h` (five comparators, plus `UseDescendantScore`).
- boost ≥ 1.76 removed the global `_1` placeholders — ten files now include
  `<boost/bind.hpp>`, and the build defines `BOOST_BIND_GLOBAL_PLACEHOLDERS`.
- `trafficgraphwidget.cpp` — add `<QPainterPath>` for Qt 5.15.
- `qt/paymentrequestplus.cpp` — `EVP_MD_CTX` is opaque from OpenSSL 1.1;
  ported to `EVP_MD_CTX_new/free` with a shim so the OpenSSL 1.0.1k used by
  `depends` still builds. The old code also leaked the context on the throw path.

**RPC backports**

- `getblock` now accepts a **verbosity** of 0/1/2 as well as the old boolean.
  Verbosity 2 (full transaction detail) is what block explorers and indexers
  expect; without it they cannot index the chain without a `txindex`.
- `getblockchaininfo` now reports **`size_on_disk`**.

Both are faithful backports; existing boolean callers are unaffected.

**Network**

- `chainparamsseeds.h` regenerated. The project's DNS seeds
  (`uk`/`nyc`/`sgp.ixcoin.co`) no longer resolve and only one of the three
  hard-coded addresses still answered, so a fresh install could barely reach the
  network. The current list came from a live crawl (`tools/ixcrawl.py`).

**Interface**

- A dark and a light theme (`src/qt/res/css/`), selectable in
  Options → Display and applied live; `qt/theme.{h,cpp}` resolves the colours
  that item views paint themselves.
- iXcoin branding throughout, replacing the inherited Bitcoin artwork.
- The recent-transactions panel now fills the window instead of being pinned
  to a fixed height.
- **Peers** and **Console** are one click away on the toolbar, as well as in Help.

---

## Building

### Linux

```sh
# Berkeley DB 4.8 first, so wallet.dat stays portable.
# NB: 0.14's bitcoin_find_bdb48.m4 ignores BDB_CFLAGS/BDB_LIBS — it searches the
# compiler's own path, so pass CPPFLAGS/LDFLAGS instead.
./autogen.sh
./configure --with-gui=qt5 \
  CPPFLAGS="-I$BDB_PREFIX/include" LDFLAGS="-L$BDB_PREFIX/lib" \
  CXXFLAGS="-O2 -DBOOST_BIND_GLOBAL_PLACEHOLDERS"
make -j"$(nproc)"
```

`install-linux.sh` installs the binaries, icons and a desktop entry under
`~/.local` — no root needed.

### Windows (cross-compiled from Linux)

The mingw-w64 **posix** thread model is required. The default `-win32` model has
no `std::mutex` and `httpserver.cpp` will not compile.

```sh
# point PATH at the -posix variants first
make -C depends HOST=x86_64-w64-mingw32 NO_UPNP=1
CONFIG_SITE=depends/x86_64-w64-mingw32/share/config.site ./configure --prefix=/
make -j"$(nproc)"
```

The result is fully static — no Qt DLLs, no runtime to install.

### Android

```sh
cd android-wallet && ./gradlew assembleDebug
```

---

## The Android wallet

Kotlin + Jetpack Compose, using **bitcoinj 0.15.10** for keys, signing and
transaction building. iXcoin's consensus differs from Bitcoin's in ways stock
bitcoinj cannot handle, so three pieces are supplied here:

- **`AuxPoW` / `IxcoinBlock`** — from block 45,000 a header is 80 bytes *plus* a
  variable-length merged-mining proof (~587 bytes on average), not 81. Stock
  bitcoinj desynchronises on the first one. Parsing hooks into
  `Block.parseTransactions`, which bitcoinj documents as the extension point for
  variable-size headers.
- **`IxcoinMainNetParams.checkDifficultyTransitions`** — iXcoin retargets every
  144 blocks on a one-day window above height 20,055 (2,016 / two weeks below),
  changes its look-back window at height 43,000, and damps asymmetrically after
  the revision. Verified against the live chain: `tools/checkdiff.py` reproduces
  every retarget point in the chain's history.
- **`IxcoinSerializer`** — drops post-handshake messages bitcoinj 0.15 does not
  know (`sendcmpct`, `feefilter`). Its `UnknownMessage` assigns `length = 0`
  only *after* the superclass has already validated it, so any unknown message
  with a payload throws and the peer is dropped.
- **`IxcoinPeerGroup`** — bitcoinj will not choose a download peer that lacks
  `NODE_WITNESS`. SegWit was never activated on iXcoin, so every peer is
  rejected and the chain download silently never starts.

Two further things that only show up on a real device:

- The BIP14 user-agent string may not contain `(` or `)`; bitcoinj throws and
  the whole SPV stack fails to start.
- bitcoinj only elects a download peer once `connected > maxConnections / 2`.
  iXcoin's reachable network is small, so a conventional `maxConnections` can
  never be half-filled and the download never begins.

Tests under `app/src/test/` parse 4,000 real headers captured from the network
and check framing, chain linkage and every AuxPoW merkle branch, plus a live
end-to-end sync against a real node.

---

## Porting

**macOS.** `depends` can cross-compile from Linux, but it needs Apple's macOS
SDK, which is free to download and *not* redistributable — extracting it
effectively requires a Mac. A GitHub Actions `macos-latest` runner builds it
without owning one.

**iOS.** Cannot be built anywhere but macOS, and there is no JVM on iOS, so the
SPV core would have to be reimplemented in Swift or Kotlin/Native rather than
recompiled.

---

## Network note

iXcoin's reachable network is small. If a node cannot find peers, the addresses
in `ixcoin-core/contrib/seeds/nodes_main.txt` are the ones that answered a live
crawl; `tools/ixcrawl.py` will find the current set.

## License

MIT, as inherited from Bitcoin Core. See `ixcoin-core/COPYING`.
