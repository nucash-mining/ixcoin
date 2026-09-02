// Copyright (c) 2011-2015 The Bitcoin Core developers
// Distributed under the MIT software license, see the accompanying
// file COPYING or http://www.opensource.org/licenses/mit-license.php.

#ifndef BITCOIN_QT_GUICONSTANTS_H
#define BITCOIN_QT_GUICONSTANTS_H

#include "theme.h"

/* Milliseconds between model updates */
static const int MODEL_UPDATE_DELAY = 250;

/* AskPassphraseDialog -- Maximum passphrase length */
static const int MAX_PASSPHRASE_SIZE = 1024;

/* BitcoinGUI -- Size of icons in status bar */
static const int STATUSBAR_ICONSIZE = 16;

static const bool DEFAULT_SPLASHSCREEN = true;

/* Invalid field background style */
#define STYLE_INVALID "background:#FF8080"

/* Item views paint their own text, so these cannot come from the style sheet;
   they are resolved against the active theme instead. See qt/theme.h. */
/* Transaction list -- unconfirmed transaction */
#define COLOR_UNCONFIRMED Theme::color(Theme::Muted)
/* Transaction list -- negative amount */
#define COLOR_NEGATIVE Theme::color(Theme::Negative)
/* Transaction list -- bare address (without label) */
#define COLOR_BAREADDRESS Theme::color(Theme::BareAddress)
/* Transaction list -- TX status decoration - open until date */
#define COLOR_TX_STATUS_OPENUNTILDATE Theme::color(Theme::StatusOpenUntil)
/* Transaction list -- TX status decoration - offline */
#define COLOR_TX_STATUS_OFFLINE Theme::color(Theme::StatusOffline)
/* Transaction list -- TX status decoration - danger, tx needs attention */
#define COLOR_TX_STATUS_DANGER Theme::color(Theme::Danger)
/* Transaction list -- TX status decoration - default foreground */
#define COLOR_BLACK Theme::color(Theme::Text)

/* Tooltips longer than this (in characters) are converted into rich text,
   so that they can be word-wrapped.
 */
static const int TOOLTIP_WRAP_THRESHOLD = 80;

/* Maximum allowed URI length */
static const int MAX_URI_LENGTH = 255;

/* QRCodeDialog -- size of exported QR Code image */
#define QR_IMAGE_SIZE 300

/* Number of frames in spinner animation */
#define SPINNER_FRAMES 36

#define QAPP_ORG_NAME "iXcoin"
#define QAPP_ORG_DOMAIN "ixcoin.info"
#define QAPP_APP_NAME_DEFAULT "iXcoin-Qt"
#define QAPP_APP_NAME_TESTNET "iXcoin-Qt-testnet"

#endif // BITCOIN_QT_GUICONSTANTS_H
