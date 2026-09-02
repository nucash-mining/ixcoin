// Copyright (c) 2026 The iXcoin developers
// Distributed under the MIT software license, see the accompanying
// file COPYING or http://www.opensource.org/licenses/mit-license.php.

#include "theme.h"

#include <QApplication>
#include <QCoreApplication>
#include <QFile>
#include <QSettings>
#include <QTextStream>

namespace {
const char* const SETTINGS_KEY = "strTheme";

QString loadStyleSheet(const QString& id)
{
    QFile f(QString(":/css/%1").arg(id));
    if (!f.open(QFile::ReadOnly | QFile::Text))
        return QString();
    QTextStream in(&f);
    return in.readAll();
}
} // namespace

namespace Theme {

QString defaultTheme()
{
    return DARK;
}

QStringList available()
{
    QStringList ids;
    ids << DARK << LIGHT << NATIVE;
    return ids;
}

QString displayName(const QString& id)
{
    if (id == DARK)   return QCoreApplication::translate("Theme", "Dark");
    if (id == LIGHT)  return QCoreApplication::translate("Theme", "Light");
    if (id == NATIVE) return QCoreApplication::translate("Theme", "System default");
    return id;
}

QString current()
{
    QSettings settings;
    const QString id = settings.value(SETTINGS_KEY, defaultTheme()).toString();
    return available().contains(id) ? id : defaultTheme();
}

void apply(const QString& id)
{
    const QString wanted = available().contains(id) ? id : defaultTheme();

    QSettings settings;
    settings.setValue(SETTINGS_KEY, wanted);

    if (!qApp)
        return;

    // NATIVE means "get out of the way": clear the sheet and let the platform
    // style render everything.
    qApp->setStyleSheet(wanted == NATIVE ? QString() : loadStyleSheet(wanted));
}

void applyCurrent()
{
    apply(current());
}

QColor color(ColorRole role)
{
    const bool dark = (current() != LIGHT); // native falls back to the dark ramp
    switch (role) {
    case Text:            return dark ? QColor(0xe4, 0xe8, 0xf1) : QColor(0x1b, 0x20, 0x30);
    case Muted:           return dark ? QColor(0x98, 0xa0, 0xb3) : QColor(0x5d, 0x67, 0x80);
    case BareAddress:     return dark ? QColor(0x8a, 0x93, 0xa8) : QColor(0x6b, 0x74, 0x8b);
    case Negative:        return dark ? QColor(0xff, 0x7a, 0x7f) : QColor(0xc0, 0x1c, 0x28);
    case Danger:          return dark ? QColor(0xff, 0x9d, 0x66) : QColor(0xb5, 0x53, 0x0a);
    case StatusOpenUntil: return dark ? QColor(0x6f, 0x9d, 0xff) : QColor(0x24, 0x50, 0xb8);
    case StatusOffline:   return dark ? QColor(0x6a, 0x72, 0x86) : QColor(0x9d, 0xa5, 0xb8);
    case Accent:          return dark ? QColor(0xf2, 0xb3, 0x2a) : QColor(0xc8, 0x88, 0x0c);
    }
    return dark ? QColor(0xe4, 0xe8, 0xf1) : QColor(0x1b, 0x20, 0x30);
}

QString labelStyle(ColorRole role)
{
    return QString("QLabel { color: %1; }").arg(color(role).name());
}

QString errorStyle()
{
    return labelStyle(Negative);
}

QString successStyle()
{
    const bool dark = (current() != LIGHT);
    return QString("QLabel { color: %1; }")
        .arg(dark ? QString("#3ddc97") : QString("#137a4d"));
}

} // namespace Theme
