// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
