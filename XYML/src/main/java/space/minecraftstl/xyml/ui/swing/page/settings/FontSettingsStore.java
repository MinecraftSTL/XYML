// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.ui.swing.FontAntialiasingMode;

/// Owns reads and writes for launcher-wide font preferences split across core and per-user settings.
@NotNullByDefault
interface FontSettingsStore extends AutoCloseable {
    /// Returns the latest immutable font state.
    ///
    /// @return current font settings snapshot
    FontSettingsSnapshot snapshot();

    /// Registers for future font-setting snapshots.
    ///
    /// @param listener ordered snapshot listener
    /// @return independently removable registration
    Subscription subscribe(ValueChangeListener<FontSettingsSnapshot> listener);

    /// Persists the launcher UI font family.
    ///
    /// @param family selected family, or `null` for the look-and-feel default
    void setLauncherFontFamily(@Nullable String family);

    /// Persists the game-log font family.
    ///
    /// @param family selected family, or `null` for the platform monospaced family
    void setLogFontFamily(@Nullable String family);

    /// Persists a positive finite game-log font size.
    ///
    /// @param size font size in logical pixels
    void setLogFontSize(double size);

    /// Persists the restart-sensitive text antialiasing preference.
    ///
    /// @param mode selected text antialiasing mode
    void setAntialiasingMode(FontAntialiasingMode mode);

    /// Releases every store-owned property listener.
    @Override
    void close();
}
