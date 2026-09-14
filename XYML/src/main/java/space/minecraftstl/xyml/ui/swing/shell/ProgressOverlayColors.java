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
package space.minecraftstl.xyml.ui.swing.shell;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.UIManager;
import java.awt.Color;

/// Resolves the shared translucent contrast color used by task progress overlays.
@NotNullByDefault
final class ProgressOverlayColors {
    /// Alpha used by progress overlays so the underlying control remains visible.
    private static final int OVERLAY_ALPHA = 72;

    /// Prevents construction of this color utility.
    private ProgressOverlayColors() {
    }

    /// Resolves a black or white overlay from the actual control surface.
    ///
    /// The component background is preferred because navigation and launch controls may use a surface different from
    /// the global panel or list defaults. UI defaults remain fallbacks for delegates that leave the component unset.
    ///
    /// @param component control receiving the overlay
    /// @return translucent black for light surfaces or white for dark surfaces
    static Color fillColor(JComponent component) {
        @Nullable Color surface = component.getBackground();
        if (surface == null) {
            surface = UIManager.getColor("Button.background");
        }
        if (surface == null) {
            surface = UIManager.getColor("Panel.background");
        }
        if (surface == null) {
            surface = UIManager.getColor("List.background");
        }
        Color resolvedSurface = surface == null ? new Color(32, 32, 32) : surface;
        int luminance = resolvedSurface.getRed() * 299
                + resolvedSurface.getGreen() * 587
                + resolvedSurface.getBlue() * 114;
        Color fill = luminance >= 128_000 ? Color.BLACK : Color.WHITE;
        return new Color(fill.getRed(), fill.getGreen(), fill.getBlue(), OVERLAY_ALPHA);
    }
}
