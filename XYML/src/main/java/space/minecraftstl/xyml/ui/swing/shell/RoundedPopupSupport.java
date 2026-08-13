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
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Window;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Objects;
import java.util.function.DoubleSupplier;

/// Applies an exact launcher radius to one popup's Swing surface and heavyweight native window.
///
/// FlatLaf maps Windows popup radii to two DWM presets. This support object disables that lossy native mapping,
/// clips all popup descendants to the configured outline, and restores the cached heavyweight window after hiding.
@NotNullByDefault
final class RoundedPopupSupport {
    /// Uniform distance from the popup border to its content.
    private static final int OUTER_INSET = 1;

    /// Popup whose component and native window are controlled by this support object.
    private final JPopupMenu popup;

    /// Supplies the current outer radius in component coordinates.
    private final DoubleSupplier outerCornerRadiusSupplier;

    /// Popup window shaped for the current display, or `null` while hidden or lightweight.
    private @Nullable Window shapedWindow;

    /// Creates exact-radius support for one popup surface.
    ///
    /// @param popup popup to configure and shape
    /// @param cornerRadiusKey UI defaults key containing the inner radius
    RoundedPopupSupport(JPopupMenu popup, String cornerRadiusKey) {
        this(popup, () -> outerCornerRadius(cornerRadiusKey));
    }

    /// Creates exact-radius support using geometry supplied by the owning popup.
    ///
    /// @param popup popup to configure and shape
    /// @param outerCornerRadiusSupplier current outer radius in component coordinates
    RoundedPopupSupport(JPopupMenu popup, DoubleSupplier outerCornerRadiusSupplier) {
        this.popup = Objects.requireNonNull(popup, "popup");
        this.outerCornerRadiusSupplier = Objects.requireNonNull(
                outerCornerRadiusSupplier,
                "outerCornerRadiusSupplier");
    }

    /// Configures transparent exact-radius painting and forces a dedicated heavyweight popup window.
    void configurePopupRendering() {
        popup.setOpaque(false);
        popup.setBorder(BorderFactory.createEmptyBorder(
                OUTER_INSET,
                OUTER_INSET,
                OUTER_INSET,
                OUTER_INSET));
        popup.putClientProperty(FlatClientProperties.POPUP_BORDER_CORNER_RADIUS, 0);
        popup.putClientProperty(FlatClientProperties.POPUP_DROP_SHADOW_PAINTED, Boolean.FALSE);
        popup.putClientProperty(FlatClientProperties.POPUP_FORCE_HEAVY_WEIGHT, Boolean.TRUE);
    }

    /// Creates a clipped antialiased graphics copy and paints the popup background inside its exact outline.
    ///
    /// @param graphics destination graphics
    /// @return owned graphics copy that the caller must dispose
    Graphics2D createPaintGraphics(Graphics graphics) {
        Graphics2D copy = (Graphics2D) Objects.requireNonNull(graphics, "graphics").create();
        copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Shape outline = createOutline(popup.getWidth(), popup.getHeight());
        copy.clip(outline);
        copy.setColor(popup.getBackground());
        copy.fill(outline);
        return copy;
    }

    /// Paints the current straight or curved outline after every popup child.
    ///
    /// @param graphics clipped popup graphics
    void paintRoundedBorder(Graphics2D graphics) {
        double radius = outerCornerRadius();
        @Nullable Color borderColor = UIManager.getColor("PopupMenu.borderColor");
        if (borderColor == null || popup.getWidth() <= 1 || popup.getHeight() <= 1) {
            return;
        }
        graphics.setColor(borderColor);
        graphics.setStroke(new BasicStroke(1.0F));
        Rectangle2D bounds = new Rectangle2D.Double(
                0.5,
                0.5,
                popup.getWidth() - 1.0,
                popup.getHeight() - 1.0);
        if (radius == 0) {
            graphics.draw(bounds);
            return;
        }
        double diameter = Math.min(radius * 2.0, Math.min(popup.getWidth() - 1.0, popup.getHeight() - 1.0));
        graphics.draw(new RoundRectangle2D.Double(
                bounds.getX(),
                bounds.getY(),
                bounds.getWidth(),
                bounds.getHeight(),
                diameter,
                diameter));
    }

    /// Applies or clears the native popup shape after a visibility transition.
    ///
    /// @param visible whether the popup is now visible
    void popupVisibilityChanged(boolean visible) {
        if (visible) {
            applyPopupWindowShape();
        } else {
            resetPopupWindowShape();
        }
    }

    /// Reapplies the native shape after a visible popup changes size.
    void popupSizeChanged() {
        if (popup.isVisible()) {
            applyPopupWindowShape();
        }
    }

    /// Returns the outer radius concentric with content inset one pixel from the popup edge.
    ///
    /// A zero content radius deliberately keeps the popup rectangular.
    ///
    /// @return current outer logical radius
    double outerCornerRadius() {
        return Math.max(0.0, outerCornerRadiusSupplier.getAsDouble());
    }

    /// Computes the concentric outer radius for one configured popup key.
    ///
    /// @param cornerRadiusKey UI defaults key containing the inner radius
    /// @return zero for a square surface, otherwise the inner radius plus one pixel
    static int outerCornerRadius(String cornerRadiusKey) {
        int contentRadius = Math.max(0, UIManager.getInt(Objects.requireNonNull(cornerRadiusKey, "cornerRadiusKey")));
        return contentRadius == 0 ? 0 : contentRadius + OUTER_INSET;
    }

    /// Computes an outer radius concentric with one inset element.
    ///
    /// @param elementCornerRadius actual painted element radius
    /// @param spacing distance from the element bounds to the outer bounds
    /// @return zero for a square element, otherwise element radius plus non-negative spacing
    static double concentricOuterCornerRadius(double elementCornerRadius, double spacing) {
        return elementCornerRadius <= 0.0
                ? 0.0
                : elementCornerRadius + Math.max(0.0, spacing);
    }

    /// Applies the current radius to the dedicated heavyweight popup window.
    private void applyPopupWindowShape() {
        @Nullable Component invoker = popup.getInvoker();
        @Nullable Window ownerWindow = invoker == null ? null : SwingUtilities.getWindowAncestor(invoker);
        @Nullable Window popupWindow = SwingUtilities.getWindowAncestor(popup);
        if (popupWindow == null || popupWindow == ownerWindow) {
            return;
        }

        @Nullable GraphicsConfiguration configuration = popupWindow.getGraphicsConfiguration();
        if (configuration == null || !configuration.getDevice().isWindowTranslucencySupported(
                GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSPARENT)) {
            return;
        }
        resetPopupWindowShape();
        popupWindow.setShape(outerCornerRadius() == 0.0
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

    /// Creates the current rectangular or rounded popup outline.
    ///
    /// @param width outline width
    /// @param height outline height
    /// @return exact outline for painting or native shaping
    private Shape createOutline(int width, int height) {
        double radius = outerCornerRadius();
        if (radius == 0.0) {
            return new Rectangle2D.Double(0.0, 0.0, width, height);
        }
        double diameter = Math.min(radius * 2.0, Math.min(width, height));
        return new RoundRectangle2D.Double(0.0, 0.0, width, height, diameter, diameter);
    }
}
