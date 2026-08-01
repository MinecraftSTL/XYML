/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
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
