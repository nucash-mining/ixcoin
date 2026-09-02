#!/bin/bash
# Install the iXcoin Core desktop wallet into the current user's ~/.local tree.
# No root required; everything lands under $HOME.
set -e

BUILD="${BUILD:-$HOME/Downloads/IXcoin/build/linux}"
BRAND="${BRAND:-$HOME/Downloads/IXcoin/brand/out}"
DIST="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

PREFIX="$HOME/.local"
BIN="$PREFIX/bin"
APPS="$PREFIX/share/applications"
ICONS="$PREFIX/share/icons/hicolor"

mkdir -p "$BIN" "$APPS" "$ICONS"

echo "==> installing binaries to $BIN"
for b in ixcoin-qt ixcoind ixcoin-cli ixcoin-tx; do
    src="$BUILD/src/$b"
    [ -x "$src" ] || src="$BUILD/src/qt/$b"
    if [ -x "$src" ]; then
        install -m 0755 "$src" "$BIN/$b"
        echo "    $b"
    else
        echo "    !! missing $b" >&2
    fi
done

echo "==> installing icons"
for s in 16 32 64 128 256; do
    d="$ICONS/${s}x${s}/apps"
    mkdir -p "$d"
    [ -f "$BRAND/ixcoin$s.png" ] && install -m 0644 "$BRAND/ixcoin$s.png" "$d/ixcoin.png"
done
mkdir -p "$ICONS/scalable/apps"
[ -f "$HOME/Downloads/IXcoin/brand/ixcoin.svg" ] && \
    install -m 0644 "$HOME/Downloads/IXcoin/brand/ixcoin.svg" "$ICONS/scalable/apps/ixcoin.svg"

echo "==> installing desktop entry"
install -m 0644 "$DIST/ixcoin-qt.desktop" "$APPS/ixcoin-qt.desktop"

command -v update-desktop-database >/dev/null && update-desktop-database "$APPS" 2>/dev/null || true
command -v gtk-update-icon-cache   >/dev/null && gtk-update-icon-cache -f -t "$ICONS" 2>/dev/null || true
command -v xdg-mime >/dev/null && xdg-mime default ixcoin-qt.desktop x-scheme-handler/ixcoin 2>/dev/null || true

echo
echo "Installed. Launch it from your applications menu as \"iXcoin Core\","
echo "or run: $BIN/ixcoin-qt"
case ":$PATH:" in
  *":$BIN:"*) ;;
  *) echo "Note: $BIN is not on your PATH; add it to use ixcoin-cli from a terminal." ;;
esac
