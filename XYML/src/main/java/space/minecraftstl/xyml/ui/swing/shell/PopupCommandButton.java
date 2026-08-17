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

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.util.Objects;

/// Two-line command used at the top or bottom of a rounded shell popup.
///
/// Its stable 64-pixel height matches account, instance, and directory rows, leaving enough vertical space for the
/// launcher's maximum 20-pixel radius. The secondary line explains navigation without making the command look empty.
@NotNullByDefault
final class PopupCommandButton extends JButton {
    /// Height shared with every top-level selector row.
    static final int HEIGHT = 64;

    /// Width reserved for an icon so command text aligns with two-line list rows.
    private static final int ICON_SLOT_SIZE = 40;

    /// Horizontal distance between the icon slot and command text.
    private static final int ICON_TEXT_GAP = 12;

    /// Stable command title returned independently of the child-label implementation.
    private final String primaryText;

    /// Stable explanatory text shown on the weaker second line.
    private final String detailText;

    /// Command icon painted inside the fixed media slot.
    private final Icon commandIcon;

    /// Whether the current UI-delegate pass must omit its standard single-line text.
    private boolean suppressStandardText;

    /// Creates one stable-height popup command.
    ///
    /// @param primaryText visible command text
    /// @param detailText visible explanatory text
    /// @param icon command icon
    PopupCommandButton(String primaryText, String detailText, Icon icon) {
        this.primaryText = requireNonBlank(primaryText, "primaryText");
        this.detailText = requireNonBlank(detailText, "detailText");
        commandIcon = Objects.requireNonNull(icon, "icon");
        setText(this.primaryText);
        putClientProperty("JButton.buttonType", "toolBarButton");
        getAccessibleContext().setAccessibleName(this.primaryText);
        getAccessibleContext().setAccessibleDescription(this.detailText);
    }

    /// Returns a preferred width for the complete two-line command and its stable row height.
    ///
    /// @return preferred command size
    @Override
    public Dimension getPreferredSize() {
        Insets insets = getInsets();
        FontMetrics primaryMetrics = getFontMetrics(getFont());
        FontMetrics detailMetrics = getFontMetrics(detailFont());
        int textWidth = Math.max(
                primaryMetrics.stringWidth(primaryText),
                detailMetrics.stringWidth(detailText));
        return new Dimension(
                insets.left + ICON_SLOT_SIZE + ICON_TEXT_GAP + textWidth + insets.right,
                HEIGHT);
    }

    /// Keeps layout managers from shrinking the command below the shared row height.
    ///
    /// @return minimum command size
    @Override
    public Dimension getMinimumSize() {
        return new Dimension(0, HEIGHT);
    }

    /// Allows horizontal growth while retaining the shared row height.
    ///
    /// @return maximum command size
    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, HEIGHT);
    }

    /// Returns standard button text except while the UI delegate paints its unused single-line content.
    ///
    /// @return primary command text, or `null` only inside the delegated background paint pass
    @Override
    public @Nullable String getText() {
        return suppressStandardText ? null : super.getText();
    }

    /// Paints the standard button state followed by aligned primary and secondary text.
    ///
    /// @param graphics destination graphics
    @Override
    protected void paintComponent(Graphics graphics) {
        suppressStandardText = true;
        try {
            super.paintComponent(graphics);
        } finally {
            suppressStandardText = false;
        }
        Graphics2D copy = (Graphics2D) graphics.create();
        try {
            copy.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            paintContent(copy);
        } finally {
            copy.dispose();
        }
    }

    /// Returns the visible command title without exposing the internal label hierarchy.
    ///
    /// @return primary command text
    String primaryText() {
        return primaryText;
    }

    /// Returns the visible second-line explanation.
    ///
    /// @return explanatory command text
    String detailText() {
        return detailText;
    }

    /// Paints the icon slot and two text lines inside current button insets.
    ///
    /// @param graphics destination graphics
    private void paintContent(Graphics2D graphics) {
        Insets insets = getInsets();
        boolean leftToRight = getComponentOrientation().isLeftToRight();
        int contentWidth = Math.max(0, getWidth() - insets.left - insets.right);
        int iconSlotX = leftToRight
                ? insets.left
                : getWidth() - insets.right - ICON_SLOT_SIZE;
        int iconX = iconSlotX + (ICON_SLOT_SIZE - commandIcon.getIconWidth()) / 2;
        int iconY = (getHeight() - commandIcon.getIconHeight()) / 2;
        commandIcon.paintIcon(this, graphics, iconX, iconY);

        int textX = leftToRight
                ? iconSlotX + ICON_SLOT_SIZE + ICON_TEXT_GAP
                : insets.left;
        int textWidth = Math.max(0, contentWidth - ICON_SLOT_SIZE - ICON_TEXT_GAP);
        Font primaryFont = getFont();
        Font secondaryFont = detailFont();
        FontMetrics primaryMetrics = graphics.getFontMetrics(primaryFont);
        FontMetrics detailMetrics = graphics.getFontMetrics(secondaryFont);
        int textHeight = primaryMetrics.getHeight() + 2 + detailMetrics.getHeight();
        int primaryBaseline = (getHeight() - textHeight) / 2 + primaryMetrics.getAscent();
        int detailBaseline = primaryBaseline + primaryMetrics.getDescent() + 2 + detailMetrics.getAscent();

        Color primaryColor = primaryForeground();
        Color secondaryColor = secondaryForeground(primaryColor);
        paintLine(
                graphics,
                primaryText,
                primaryFont,
                primaryColor,
                textX,
                textWidth,
                primaryBaseline,
                leftToRight);
        paintLine(
                graphics,
                detailText,
                secondaryFont,
                secondaryColor,
                textX,
                textWidth,
                detailBaseline,
                leftToRight);
    }

    /// Returns the smaller font used for explanatory text.
    ///
    /// @return current secondary-line font
    private Font detailFont() {
        Font font = getFont();
        return font.deriveFont(Math.max(9.0F, font.getSize2D() - 1.0F));
    }

    /// Resolves the current primary foreground including disabled state.
    ///
    /// @return current command foreground
    private Color primaryForeground() {
        @Nullable Color currentForeground = getForeground();
        @Nullable Color disabledForeground = UIManager.getColor("Label.disabledForeground");
        @Nullable Color labelForeground = UIManager.getColor("Label.foreground");
        Color fallback = labelForeground == null ? Color.DARK_GRAY : labelForeground;
        return isEnabled()
                ? (currentForeground == null ? fallback : currentForeground)
                : (disabledForeground == null ? fallback : disabledForeground);
    }

    /// Resolves a weaker explanatory foreground for the active theme.
    ///
    /// @param primary resolved primary foreground
    /// @return current secondary-line foreground
    private Color secondaryForeground(Color primary) {
        @Nullable Color disabledForeground = UIManager.getColor("Label.disabledForeground");
        return disabledForeground == null ? primary : disabledForeground;
    }

    /// Paints one clipped line with current component orientation.
    ///
    /// @param graphics destination graphics
    /// @param text complete visible text
    /// @param font line font
    /// @param color line foreground
    /// @param x leading horizontal boundary in left-to-right coordinates
    /// @param width available line width
    /// @param baseline baseline coordinate
    /// @param leftToRight whether text starts at the left boundary
    private static void paintLine(
            Graphics2D graphics,
            String text,
            Font font,
            Color color,
            int x,
            int width,
            int baseline,
            boolean leftToRight) {
        graphics.setFont(font);
        graphics.setColor(color);
        FontMetrics metrics = graphics.getFontMetrics(font);
        String visibleText = clippedText(metrics, text, width);
        int drawX = leftToRight
                ? x
                : x + Math.max(0, width - metrics.stringWidth(visibleText));
        graphics.drawString(visibleText, drawX, baseline);
    }

    /// Truncates one line only when it cannot fit inside the popup width.
    ///
    /// @param metrics active line metrics
    /// @param text complete text
    /// @param width available width
    /// @return original or trailing-ellipsis text
    private static String clippedText(FontMetrics metrics, String text, int width) {
        if (width <= 0) {
            return "";
        }
        if (metrics.stringWidth(text) <= width) {
            return text;
        }
        String ellipsis = "...";
        int available = width - metrics.stringWidth(ellipsis);
        if (available <= 0) {
            return ellipsis;
        }
        int end = text.length();
        while (end > 0 && metrics.stringWidth(text.substring(0, end)) > available) {
            --end;
        }
        return text.substring(0, end) + ellipsis;
    }

    /// Rejects blank visible command text.
    ///
    /// @param value candidate text
    /// @param name parameter name
    /// @return validated text
    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
