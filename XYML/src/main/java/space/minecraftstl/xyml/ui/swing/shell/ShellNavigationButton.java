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

import com.formdev.flatlaf.extras.FlatSVGIcon;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.ui.swing.SwingOverlayColors;

import javax.swing.JToggleButton;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.util.Objects;
import java.util.OptionalDouble;

/// Provides a stable icon-and-label target for one top-level destination.
@NotNullByDefault
final class ShellNavigationButton extends JToggleButton {
    /// Destination selected by this button.
    private final ShellPageId page;

    /// Localized label retained for the dynamic task-count accessible name.
    private final String accessibleLabel;

    /// Number of active top-level task executions represented by this button.
    private int activeTaskCount;

    /// Aggregate active-task progress, or empty when no active execution has progress.
    private OptionalDouble activeTaskProgress = OptionalDouble.empty();

    /// Creates an accessible navigation target with a keyboard mnemonic.
    ///
    /// @param page the represented destination
    /// @param presentation the localized button presentation
    ShellNavigationButton(ShellPageId page, ShellPagePresentation presentation) {
        super(Objects.requireNonNull(presentation).label(), createNavigationIcon(page));
        this.page = page;
        accessibleLabel = presentation.label();
        setMnemonic(presentation.mnemonic());
        setHorizontalAlignment(LEFT);
        setIconTextGap(12);
        setMargin(new Insets(10, 14, 10, 14));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setFocusable(true);
        setFocusPainted(true);
        putClientProperty("JButton.buttonType", "toolBarButton");
        getAccessibleContext().setAccessibleName(presentation.label());
    }

    /// Returns the represented destination.
    ///
    /// @return the destination selected by this button
    ShellPageId page() {
        return page;
    }

    /// Updates the active top-level count and aggregate fill shown over this button.
    ///
    /// @param count active top-level workflows, never displayed above `64+`
    /// @param progress aggregate progress, or empty when the aggregate is unknown
    void setTaskIndicator(int count, OptionalDouble progress) {
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }
        activeTaskCount = count;
        activeTaskProgress = Objects.requireNonNull(progress, "progress");
        String badge = count == 0 ? "" : count > 64 ? " 64+" : " " + count;
        setToolTipText(accessibleLabel + badge);
        getAccessibleContext().setAccessibleName(accessibleLabel + badge);
        repaint();
    }

    /// Returns the exact active top-level count represented by this button.
    ///
    /// @return active top-level workflow count
    int activeTaskCount() {
        return activeTaskCount;
    }

    /// Returns the current aggregate active-task progress.
    ///
    /// @return normalized aggregate progress, or empty when no progress is displayed
    OptionalDouble activeTaskProgress() {
        return activeTaskProgress;
    }

    /// Paints the aggregate progress fill and active-count badge over the normal themed button.
    ///
    /// The fill is intentionally translucent so the icon remains readable in both light and dark themes. It grows
    /// from the top edge to the bottom edge, making the filled fraction directly visible without changing layout.
    ///
    /// @param graphics Swing paint context
    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D copy = (Graphics2D) graphics.create();
        try {
            copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (activeTaskProgress.isPresent() && getWidth() > 0 && getHeight() > 0) {
                double fraction = Math.max(0.0D, Math.min(1.0D, activeTaskProgress.getAsDouble()));
                copy.setColor(SwingOverlayColors.contrastOverlay(this));
                int arc = Math.max(0, Math.min(
                        Math.min(getWidth(), getHeight()),
                        UIManager.getInt("Button.arc")));
                copy.clip(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), arc, arc));
                copy.fillRect(0, 0, getWidth(), (int) Math.ceil(getHeight() * fraction));
            }
            if (activeTaskCount > 0) {
                String text = activeTaskCount > 64 ? "64+" : Integer.toString(activeTaskCount);
                Font font = getFont().deriveFont(Font.BOLD, Math.max(9.0F, getFont().getSize2D() - 2.0F));
                copy.setFont(font);
                int textWidth = copy.getFontMetrics().stringWidth(text);
                int badgeWidth = Math.max(16, textWidth + 7);
                int badgeHeight = 16;
                int x = Math.max(1, getWidth() - badgeWidth - 2);
                int y = 2;
                Color badgeColor = taskBadgeColor();
                copy.setColor(badgeColor);
                copy.fillRoundRect(x, y, badgeWidth, badgeHeight, badgeHeight, badgeHeight);
                copy.setColor(badgeTextColor(badgeColor));
                copy.drawString(text, x + (badgeWidth - textWidth) / 2, y + 12);
            }
        } finally {
            copy.dispose();
        }
    }

    /// Resolves the current FlatLaf accent used for task progress and count badges.
    ///
    /// @return theme accent, or null when the look and feel does not expose one
    private static @Nullable Color themeAccentColor() {
        @Nullable Color accent = UIManager.getColor("Component.accentColor");
        if (accent != null) {
            return accent;
        }
        accent = UIManager.getColor("Button.default.background");
        if (accent != null) {
            return accent;
        }
        return UIManager.getColor("ToggleButton.selectedBackground");
    }

    /// Returns a themed, opaque badge color with enough alpha to stay legible over the button surface.
    ///
    /// @return badge background color
    private static Color taskBadgeColor() {
        @Nullable Color accent = themeAccentColor();
        if (accent == null) {
            accent = UIManager.getColor("Button.background");
        }
        return accent == null ? new Color(72, 126, 196) : accent;
    }

    /// Selects black or white badge text according to the resolved theme color's luminance.
    ///
    /// @param background badge background color
    /// @return high-contrast badge text color
    private static Color badgeTextColor(Color background) {
        int luminance = background.getRed() * 299
                + background.getGreen() * 587
                + background.getBlue() * 114;
        return luminance >= 160_000 ? Color.BLACK : Color.WHITE;
    }

    /// Creates a theme-aware navigation icon for one destination.
    ///
    /// @param page destination represented by the returned icon
    /// @return configured 20-pixel SVG icon
    private static FlatSVGIcon createNavigationIcon(ShellPageId page) {
        FlatSVGIcon icon = new FlatSVGIcon(iconResource(page), 20, 20);
        icon.setColorFilter(new FlatSVGIcon.ColorFilter(ShellNavigationButton::resolveIconColor));
        return icon;
    }

    /// Resolves a navigation icon color from the owning button's active theme state.
    ///
    /// @param component owning component, or null during standalone image rendering
    /// @param originalColor SVG-authored fallback color
    /// @return component foreground when available, otherwise the SVG fallback
    private static Color resolveIconColor(@Nullable Component component, Color originalColor) {
        Color fallback = Objects.requireNonNull(originalColor, "originalColor");
        @Nullable Color foreground = component == null ? null : component.getForeground();
        return foreground == null ? fallback : foreground;
    }

    /// Maps one destination to the corresponding bundled Material SVG asset.
    ///
    /// @param page destination represented by the requested icon
    /// @return classpath-relative SVG resource path
    private static String iconResource(ShellPageId page) {
        return switch (Objects.requireNonNull(page, "page")) {
            case INSTANCES -> "assets/swing/icons/nav-instances.svg";
            case DOWNLOADS -> "assets/swing/icons/nav-downloads.svg";
            case TASKS -> "assets/swing/icons/task-manager.svg";
            case ACCOUNTS -> "assets/swing/icons/nav-accounts.svg";
            case SETTINGS -> "assets/swing/icons/nav-settings.svg";
        };
    }
}
