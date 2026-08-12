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

import javax.swing.UIDefaults;
import java.util.Objects;

/// Holds visual measurements shared by the Swing look and feel.
///
/// @param cornerRadius the component corner radius in logical pixels
@NotNullByDefault
public record SwingDesignTokens(int cornerRadius) {
    /// Largest FlatLaf checkbox arc diameter that retains a rounded-square silhouette.
    private static final int MAXIMUM_CHECK_BOX_ARC_DIAMETER = 6;

    /// Creates validated Swing design tokens.
    ///
    /// @param cornerRadius the component corner radius in logical pixels
    public SwingDesignTokens {
        if (cornerRadius < 0) {
            throw new IllegalArgumentException("cornerRadius must not be negative");
        }
        if (cornerRadius > Integer.MAX_VALUE / 2) {
            throw new IllegalArgumentException("cornerRadius is too large to convert to an arc diameter");
        }
    }

    /// Returns a copy with a different component corner radius.
    ///
    /// @param value the new radius in logical pixels
    /// @return design tokens containing the requested radius
    public SwingDesignTokens withCornerRadius(int value) {
        return new SwingDesignTokens(value);
    }

    /// Applies the radius to FlatLaf's supported component defaults.
    ///
    /// @param defaults the active Swing defaults to update
    public void applyTo(UIDefaults defaults) {
        Objects.requireNonNull(defaults);

        int arcDiameter = cornerRadius * 2;
        defaults.put("Component.arc", arcDiameter);
        defaults.put("Button.arc", arcDiameter);
        defaults.put("CheckBox.arc", Math.min(arcDiameter, MAXIMUM_CHECK_BOX_ARC_DIAMETER));
        defaults.put("TextComponent.arc", arcDiameter);
        defaults.put("ProgressBar.arc", arcDiameter);
        defaults.put("ScrollBar.thumbArc", arcDiameter);
        defaults.put("ScrollBar.trackArc", arcDiameter);
    }
}
