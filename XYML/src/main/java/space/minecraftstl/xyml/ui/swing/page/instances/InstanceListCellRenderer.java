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
package space.minecraftstl.xyml.ui.swing.page.instances;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.image.InstanceIconData;
import space.minecraftstl.xyml.ui.swing.choice.ChoiceListEntry;
import space.minecraftstl.xyml.ui.swing.choice.ChoiceLoadStatus;
import space.minecraftstl.xyml.ui.swing.choice.RoundedListSelectionPainter;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.border.Border;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.WeakHashMap;

/// Renders one installed-instance row with a stable icon, name, detail, and full-row selection highlight.
///
/// The same 64-pixel row geometry is used for loaded, loading, and failed sparse entries. Icon
/// conversion is EDT-confined and weakly cached so scrolling does not repeatedly copy pixel data
/// or retain every icon encountered during a long session.
@NotNullByDefault
public final class InstanceListCellRenderer extends JPanel
        implements ListCellRenderer<ChoiceListEntry<InstanceListItem>> {
    /// Stable renderer height measured by the viewport loading strategy.
    public static final int ROW_HEIGHT = 64;

    /// Loading-state placeholder occupying the same 40-by-40 icon slot as loaded rows.
    private static final Icon LOADING_ICON = new StatePlaceholderIcon(false);

    /// Error-state placeholder occupying the same 40-by-40 icon slot as loaded rows.
    private static final Icon ERROR_ICON = new StatePlaceholderIcon(true);

    /// Fixed icon host.
    private final JLabel iconLabel = new JLabel();

    /// Primary instance-name label.
    private final JLabel nameLabel = new JLabel();

    /// Secondary version or loader label.
    private final JLabel detailLabel = new JLabel();

    /// Weak EDT-confined cache of immutable pixels converted to Swing icons.
    private final Map<InstanceIconData, Icon> iconCache = new WeakHashMap<>();

    /// List whose UI paints the current selection background, or `null` before first configuration.
    private @Nullable JList<?> selectionOwner;

    /// Logical row represented during the next paint.
    private int selectionIndex = -1;

    /// Whether the represented row is selected.
    private boolean selected;

    /// Creates the stable reusable instance renderer hierarchy.
    public InstanceListCellRenderer() {
        super(new BorderLayout(12, 0));
        setOpaque(false);
        setPreferredSize(new Dimension(280, ROW_HEIGHT));

        Dimension iconSize = new Dimension(InstanceIconData.WIDTH, InstanceIconData.HEIGHT);
        iconLabel.setName("instanceListIcon");
        iconLabel.setPreferredSize(iconSize);
        iconLabel.setMinimumSize(iconSize);
        iconLabel.setMaximumSize(iconSize);
        iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
        iconLabel.setVerticalAlignment(SwingConstants.CENTER);

        JPanel labels = new JPanel(new GridLayout(2, 1, 0, 2));
        labels.setOpaque(false);
        nameLabel.setName("instanceListName");
        detailLabel.setName("instanceListDetail");
        nameLabel.setVerticalAlignment(SwingConstants.BOTTOM);
        detailLabel.setVerticalAlignment(SwingConstants.TOP);
        labels.add(nameLabel);
        labels.add(detailLabel);

        add(iconLabel, BorderLayout.LINE_START);
        add(labels, BorderLayout.CENTER);
    }

    /// Configures the reusable hierarchy for one sparse instance row.
    ///
    /// @param list owning instance list
    /// @param entry loaded, loading, or failed row state
    /// @param index stable logical row index
    /// @param isSelected whether the row is currently selected
    /// @param cellHasFocus whether the row owns keyboard focus
    /// @return this reusable renderer component
    @Override
    public Component getListCellRendererComponent(
            JList<? extends ChoiceListEntry<InstanceListItem>> list,
            ChoiceListEntry<InstanceListItem> entry,
            int index,
            boolean isSelected,
            boolean cellHasFocus) {
        applyComponentOrientation(list.getComponentOrientation());
        selectionOwner = list;
        selectionIndex = index;
        selected = isSelected;
        configurePalette(list, isSelected, cellHasFocus);

        Font baseFont = list.getFont();
        nameLabel.setFont(baseFont.deriveFont(Font.BOLD));
        detailLabel.setFont(baseFont.deriveFont(Math.max(9.0F, baseFont.getSize2D() - 1.0F)));
        setToolTipText(null);

        @Nullable InstanceListItem item = entry.value();
        if (entry.status() == ChoiceLoadStatus.LOADED && item != null) {
            nameLabel.setText(item.name());
            detailLabel.setText(item.detail().isBlank() ? " " : item.detail());
            iconLabel.setIcon(iconFor(item.icon()));
        } else if (entry.status() == ChoiceLoadStatus.ERROR) {
            nameLabel.setText("!");
            detailLabel.setText(" ");
            iconLabel.setIcon(ERROR_ICON);
            @Nullable Throwable failure = entry.failure();
            setToolTipText(failure == null ? null : failure.getMessage());
        } else {
            nameLabel.setText("...");
            detailLabel.setText(" ");
            iconLabel.setIcon(LOADING_ICON);
        }
        return this;
    }

    /// Paints the list-owned rounded selection before the transparent renderer hierarchy.
    ///
    /// @param graphics destination graphics
    @Override
    protected void paintComponent(Graphics graphics) {
        @Nullable JList<?> owner = selectionOwner;
        if (selected && owner != null) {
            RoundedListSelectionPainter.paintSelectedBackground(
                    owner,
                    graphics,
                    selectionIndex,
                    getWidth(),
                    getHeight(),
                    getBackground());
        }
        super.paintComponent(graphics);
    }

    /// Applies list-owned theme colors and focus decoration to this renderer hierarchy.
    ///
    /// @param list owning list and palette source
    /// @param selected whether the row is selected
    /// @param focused whether the row has keyboard focus
    private void configurePalette(
            JList<? extends ChoiceListEntry<InstanceListItem>> list,
            boolean selected,
            boolean focused) {
        Color background = selected ? list.getSelectionBackground() : list.getBackground();
        Color foreground = selected ? list.getSelectionForeground() : list.getForeground();
        setOpaque(false);
        setBackground(background);
        setForeground(foreground);
        nameLabel.setForeground(foreground);
        detailLabel.setForeground(foreground);
        @Nullable Border lafBorder = UIManager.getBorder(focused
                ? "List.focusCellHighlightBorder"
                : "List.cellNoFocusBorder");
        Border focusBorder = lafBorder == null
                ? BorderFactory.createEmptyBorder(1, 1, 1, 1)
                : lafBorder;
        setBorder(BorderFactory.createCompoundBorder(
                focusBorder,
                BorderFactory.createEmptyBorder(7, 10, 7, 10)));
    }

    /// Returns a cached Swing icon for immutable toolkit-neutral pixels.
    ///
    /// @param iconData normalized immutable pixels
    /// @return fixed-size Swing icon
    private Icon iconFor(InstanceIconData iconData) {
        return iconCache.computeIfAbsent(iconData, InstanceListCellRenderer::createImageIcon);
    }

    /// Converts one immutable pixel value into a fixed-size Swing image icon.
    ///
    /// @param iconData normalized immutable pixels
    /// @return fixed-size Swing icon
    private static Icon createImageIcon(InstanceIconData iconData) {
        BufferedImage image = new BufferedImage(
                InstanceIconData.WIDTH,
                InstanceIconData.HEIGHT,
                BufferedImage.TYPE_INT_ARGB);
        image.setRGB(
                0,
                0,
                InstanceIconData.WIDTH,
                InstanceIconData.HEIGHT,
                iconData.copyArgbPixels(),
                0,
                InstanceIconData.WIDTH);
        return new ImageIcon(image);
    }

    /// Fixed-size placeholder icon used by loading and failed sparse rows.
    @NotNullByDefault
    private static final class StatePlaceholderIcon implements Icon {
        /// Whether this placeholder represents a failed row.
        private final boolean error;

        /// Creates one stable state placeholder.
        ///
        /// @param error whether to render an error marker instead of loading dots
        private StatePlaceholderIcon(boolean error) {
            this.error = error;
        }

        /// Paints a theme-aware state marker into the fixed icon slot.
        ///
        /// @param component renderer component supplying fallback colors
        /// @param graphics destination graphics
        /// @param x horizontal paint origin
        /// @param y vertical paint origin
        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                copy.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                Color marker = stateColor(component, error);
                int arc = Math.max(0, Math.min(
                        Math.min(getIconWidth(), getIconHeight()),
                        UIManager.getInt("Component.arc")));
                copy.setColor(withAlpha(marker, 48));
                copy.fillRoundRect(x, y, getIconWidth(), getIconHeight(), arc, arc);
                copy.setColor(withAlpha(marker, 180));
                copy.drawRoundRect(x, y, getIconWidth() - 1, getIconHeight() - 1, arc, arc);
                if (error) {
                    paintErrorMark(copy, marker, x, y);
                } else {
                    paintLoadingDots(copy, marker, x, y);
                }
            } finally {
                copy.dispose();
            }
        }

        /// Returns the fixed icon width.
        ///
        /// @return 40 pixels
        @Override
        public int getIconWidth() {
            return InstanceIconData.WIDTH;
        }

        /// Returns the fixed icon height.
        ///
        /// @return 40 pixels
        @Override
        public int getIconHeight() {
            return InstanceIconData.HEIGHT;
        }

        /// Resolves a state marker color from the active look and feel.
        ///
        /// @param component renderer component supplying a final fallback
        /// @param error whether to request an error accent
        /// @return non-null marker color
        private static Color stateColor(Component component, boolean error) {
            @Nullable Color themed = UIManager.getColor(error
                    ? "Actions.Red"
                    : "Label.disabledForeground");
            if (themed != null) {
                return themed;
            }
            @Nullable Color foreground = component.getForeground();
            return foreground == null ? Color.GRAY : foreground;
        }

        /// Returns one source color with a bounded replacement alpha channel.
        ///
        /// @param color opaque or translucent source color
        /// @param alpha replacement alpha from zero through 255
        /// @return replacement-alpha color
        private static Color withAlpha(Color color, int alpha) {
            return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
        }

        /// Paints a centered error exclamation mark.
        ///
        /// @param graphics destination graphics
        /// @param marker marker color
        /// @param x horizontal icon origin
        /// @param y vertical icon origin
        private static void paintErrorMark(Graphics2D graphics, Color marker, int x, int y) {
            Font font = graphics.getFont().deriveFont(Font.BOLD, 22.0F);
            graphics.setFont(font);
            graphics.setColor(marker);
            FontMetrics metrics = graphics.getFontMetrics(font);
            String text = "!";
            int textX = x + (InstanceIconData.WIDTH - metrics.stringWidth(text)) / 2;
            int textY = y + (InstanceIconData.HEIGHT - metrics.getHeight()) / 2 + metrics.getAscent();
            graphics.drawString(text, textX, textY);
        }

        /// Paints three centered loading dots.
        ///
        /// @param graphics destination graphics
        /// @param marker marker color
        /// @param x horizontal icon origin
        /// @param y vertical icon origin
        private static void paintLoadingDots(Graphics2D graphics, Color marker, int x, int y) {
            graphics.setColor(marker);
            int dotY = y + InstanceIconData.HEIGHT / 2 - 2;
            for (int offset = 0; offset < 3; offset++) {
                graphics.fillOval(x + 12 + offset * 7, dotY, 4, 4);
            }
        }
    }
}
