// Copyright (c) 2026 The iXcoin developers
// Distributed under the MIT software license, see the accompanying
// file COPYING or http://www.opensource.org/licenses/mit-license.php.

#ifndef BITCOIN_QT_THEME_H
#define BITCOIN_QT_THEME_H

#include <QColor>
#include <QString>
#include <QStringList>

/** Application-wide visual themes.
 *
 * A theme is a Qt style sheet stored in the Qt resource bundle. Themes are
 * applied to the whole QApplication, so switching one takes effect live for
 * every open window without a restart.
 */
namespace Theme {

/** Internal identifiers. These are what get written to QSettings, so they
 *  must stay stable across releases. */
static const char* const DARK   = "dark";
static const char* const LIGHT  = "light";
static const char* const NATIVE = "native";

/** Theme used when the user has never chosen one. */
QString defaultTheme();

/** Identifiers of every selectable theme, in display order. */
QStringList available();

/** Human-readable, translated name for an identifier. */
QString displayName(const QString& id);

/** The identifier stored in QSettings, falling back to defaultTheme(). */
QString current();

/** Apply a theme to the running QApplication and remember the choice.
 *  Unknown identifiers fall back to the native platform style. */
void apply(const QString& id);

/** Apply whatever theme is currently configured. */
void applyCurrent();

/** Semantic colours that have to be resolved in C++ (item views paint their
 *  own text, so a style sheet alone cannot reach them). Each resolves against
 *  the active theme. */
enum ColorRole {
    Text,               //!< default foreground
    Muted,              //!< de-emphasised text, e.g. unconfirmed
    BareAddress,        //!< address shown without a label
    Negative,           //!< outgoing amount
    Danger,             //!< needs attention
    StatusOpenUntil,    //!< tx locked until a date/height
    StatusOffline,      //!< tx not broadcast
    Accent              //!< the iXcoin gold
};

/** Resolve a semantic colour for the active theme. */
QColor color(ColorRole role);

/** `QLabel { color: ... }` snippets for the inline status labels, so they stay
 *  legible whichever theme is active. Pass an empty role for "reset". */
QString labelStyle(ColorRole role);
QString errorStyle();
QString successStyle();

} // namespace Theme

#endif // BITCOIN_QT_THEME_H
