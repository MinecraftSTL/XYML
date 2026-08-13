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

import com.formdev.flatlaf.FlatClientProperties;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Window;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;

/// Popup menu whose complete Swing content and native popup window share the configured launcher radius.
///
/// FlatLaf delegates Windows popup corners to DWM, which exposes only fixed small and normal radii. This component
/// disables that lossy mapping for shell selectors, clips their complete component tree, and applies the exact
/// logical radius to the heavyweight popup window when shaped windows are available.
@NotNullByDefault
final class RoundedPopupMenu extends JPopupMenu {
    /// Uniform space between popup content and the custom-painted outer border.
    private static final int OUTER_INSET = 1;

    /// Popup window shaped for the current display, or `null` while hidden or rendered as a lightweight popup.
    private @Nullable Window shapedWindow;

    /// Creates an exact-radius shell popup.
    RoundedPopupMenu() {
        configurePopupRendering();
    }

    /// Restores exact-radius rendering after a look-and-feel replacement.
    @Override
    public void updateUI() {
        super.updateUI();
        configurePopupRendering();
    }

    /// Shapes a newly shown heavyweight popup and restores cached popup windows when hiding.
    ///
    /// @param visible whether the popup should be visible
    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);
        if (visible) {
            applyPopupWindowShape();
        } else {
            resetPopupWindowShape();
        }
    }

    /// Reapplies the native shape after a visible popup changes size.
    ///
    /// @param size requested popup size
    @Override
    public void setPopupSize(Dimension size) {
        super.setPopupSize(size);
        if (isVisible()) {
            applyPopupWindowShape();
        }
    }

    /// Clips the popup background, border, and every child component to one exact rounded outline.
    ///
    /// @param graphics destination graphics
    @Override
    public void paint(Graphics graphics) {
        Graphics2D copy = (Graphics2D) graphics.create();
        try {
            copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Shape outline = createOutline(getWidth(), getHeight());
            copy.clip(outline);
            copy.setColor(getBackground());
            copy.fill(outline);
            super.paint(copy);
            paintRoundedBorder(copy);
        } finally {
            copy.dispose();
        }
    }

    /// Paints the current straight or curved outline after all popup children so they cannot cover it.
    ///
    /// @param graphics destination graphics
    private void paintRoundedBorder(Graphics2D graphics) {
        int radius = cornerRadius();
        @Nullable Color borderColor = UIManager.getColor("PopupMenu.borderColor");
        if (borderColor == null || getWidth() <= 1 || getHeight() <= 1) {
            return;
        }
        graphics.setColor(borderColor);
        graphics.setStroke(new BasicStroke(1.0F));
        Rectangle2D bounds = new Rectangle2D.Double(
                0.5,
                0.5,
                getWidth() - 1.0,
                getHeight() - 1.0);
        if (radius == 0) {
            graphics.draw(bounds);
            return;
        }
        double diameter = Math.min(radius * 2.0, Math.min(getWidth() - 1.0, getHeight() - 1.0));
        graphics.draw(new RoundRectangle2D.Double(
                bounds.getX(),
                bounds.getY(),
                bounds.getWidth(),
                bounds.getHeight(),
                diameter,
                diameter));
    }

    /// Keeps FlatLaf from replacing the requested radius with the Windows fixed-radius native border.
    private void configurePopupRendering() {
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(OUTER_INSET, OUTER_INSET, OUTER_INSET, OUTER_INSET));
        putClientProperty(FlatClientProperties.POPUP_BORDER_CORNER_RADIUS, 0);
        putClientProperty(FlatClientProperties.POPUP_DROP_SHADOW_PAINTED, Boolean.FALSE);
        putClientProperty(FlatClientProperties.POPUP_FORCE_HEAVY_WEIGHT, Boolean.TRUE);
    }

    /// Applies the current radius to the dedicated heavyweight popup window.
    private void applyPopupWindowShape() {
        @Nullable Component invoker = getInvoker();
        @Nullable Window ownerWindow = invoker == null ? null : SwingUtilities.getWindowAncestor(invoker);
        @Nullable Window popupWindow = SwingUtilities.getWindowAncestor(this);
        if (popupWindow == null || popupWindow == ownerWindow) {
            return;
        }

        @Nullable GraphicsConfiguration configuration = popupWindow.getGraphicsConfiguration();
        if (configuration == null || !configuration.getDevice().isWindowTranslucencySupported(
                GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSPARENT)) {
            return;
        }
        resetPopupWindowShape();
        popupWindow.setShape(cornerRadius() == 0
                ? null
                : createOutline(popupWindow.getWidth(), popupWindow.getHeight()));
        shapedWindow = popupWindow;
    }

    /// Removes a custom shape before FlatLaf caches this heavyweight window for another popup.
    private void resetPopupWindowShape() {
        @Nullable Window popupWindow = shapedWindow;
        shapedWindow = null;
        if (popupWindow != null && popupWindow.isDisplayable()) {
            popupWindow.setShape(null);
        }
    }

    /// Creates the current rectangular or rounded component outline.
    ///
    /// @param width outline width
    /// @param height outline height
    /// @return exact outline for painting or native shaping
    private Shape createOutline(int width, int height) {
        int radius = cornerRadius();
        if (radius == 0) {
            return new Rectangle2D.Double(0.0, 0.0, width, height);
        }
        double diameter = Math.min(radius * 2.0, Math.min(width, height));
        return new RoundRectangle2D.Double(0.0, 0.0, width, height, diameter, diameter);
    }

    /// Returns the non-negative popup corner radius currently installed by the theme manager.
    ///
    /// @return current logical radius
    private static int cornerRadius() {
        return Math.max(0, UIManager.getInt("PopupMenu.borderCornerRadius"));
    }
}
