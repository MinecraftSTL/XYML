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

import javax.swing.JPopupMenu;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;

/// Popup menu whose complete Swing content and native popup window share the configured launcher radius.
///
/// FlatLaf delegates Windows popup corners to DWM, which exposes only fixed small and normal radii. This component
/// disables that lossy mapping for shell selectors, clips their complete component tree, and applies the exact
/// logical radius to the heavyweight popup window when shaped windows are available.
@NotNullByDefault
public final class RoundedPopupMenu extends JPopupMenu {
    /// Exact-radius component and native-window support.
    private final RoundedPopupSupport roundedSupport =
            new RoundedPopupSupport(this, "PopupMenu.borderCornerRadius");

    /// Creates an exact-radius shell popup.
    public RoundedPopupMenu() {
        roundedSupport.configurePopupRendering();
    }

    /// Restores exact-radius rendering after a look-and-feel replacement.
    @Override
    public void updateUI() {
        super.updateUI();
        if (roundedSupport != null) {
            roundedSupport.configurePopupRendering();
        }
    }

    /// Shapes a newly shown heavyweight popup and restores cached popup windows when hiding.
    ///
    /// @param visible whether the popup should be visible
    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);
        roundedSupport.popupVisibilityChanged(visible);
    }

    /// Reapplies the native shape after a visible popup changes size.
    ///
    /// @param size requested popup size
    @Override
    public void setPopupSize(Dimension size) {
        super.setPopupSize(size);
        roundedSupport.popupSizeChanged();
    }

    /// Clips the popup background, border, and every child component to one exact rounded outline.
    ///
    /// @param graphics destination graphics
    @Override
    public void paint(Graphics graphics) {
        Graphics2D copy = roundedSupport.createPaintGraphics(graphics);
        try {
            super.paint(copy);
            roundedSupport.paintRoundedBorder(copy);
        } finally {
            copy.dispose();
        }
    }

    /// Returns the outer radius that stays concentric with content inset from the popup edge.
    ///
    /// A zero content radius deliberately keeps the popup rectangular instead of introducing a one-pixel curve.
    ///
    /// @return current outer logical radius
    static int outerCornerRadius() {
        return RoundedPopupSupport.outerCornerRadius("PopupMenu.borderCornerRadius");
    }
}
