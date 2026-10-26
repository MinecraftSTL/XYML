// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
