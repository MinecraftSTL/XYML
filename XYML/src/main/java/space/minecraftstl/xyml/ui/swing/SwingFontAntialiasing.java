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
package space.minecraftstl.xyml.ui.swing;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Properties;

/// Applies the persisted text antialiasing mode before Swing initializes its desktop rendering hints.
@NotNullByDefault
public final class SwingFontAntialiasing {
    /// Standard AWT text antialiasing system-property name.
    private static final String AWT_FONT_SETTINGS_PROPERTY = "awt.useSystemAAFontSettings";

    /// Prevents construction of the stateless startup adapter.
    private SwingFontAntialiasing() {
    }

    /// Applies a configured mode without overriding an explicit JVM property supplied by the user.
    ///
    /// @param mode persisted launcher text antialiasing mode
    /// @return whether this call installed an AWT property value
    public static boolean applyAtStartup(FontAntialiasingMode mode) {
        return applyTo(System.getProperties(), mode);
    }

    /// Applies one mode to injectable properties for deterministic startup tests.
    ///
    /// @param properties target system-property set
    /// @param mode configured mode
    /// @return whether the target was changed
    static boolean applyTo(Properties properties, FontAntialiasingMode mode) {
        Properties target = Objects.requireNonNull(properties, "properties");
        FontAntialiasingMode validatedMode = Objects.requireNonNull(mode, "mode");
        if (target.getProperty(AWT_FONT_SETTINGS_PROPERTY) != null) {
            return false;
        }
        @Nullable String awtValue = switch (validatedMode) {
            case AUTO -> null;
            case LCD -> "lcd";
            case GRAY -> "on";
        };
        if (awtValue == null) {
            return false;
        }
        target.setProperty(AWT_FONT_SETTINGS_PROPERTY, awtValue);
        return true;
    }
}
