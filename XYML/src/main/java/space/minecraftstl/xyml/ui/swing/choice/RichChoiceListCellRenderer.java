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
package space.minecraftstl.xyml.ui.swing.choice;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.Icon;
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
import java.awt.Insets;
import java.awt.RenderingHints;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.function.Function;

/// Renders a viewport row with an icon, two clipped metadata lines, and a compact state badge.
///
/// The renderer is deliberately data-source agnostic. Callers provide pure presentation
/// functions, so the Swing thread never needs to inspect an archive, directory, or network
/// resource while painting a row. A fixed preferred height is used for all sparse states and text
/// is clipped against the list's current width to keep narrow responsive layouts horizontal-scroll
/// free.
///
/// @param <T> loaded row value type
@NotNullByDefault
public final class RichChoiceListCellRenderer<T extends Object> extends JPanel
        implements ListCellRenderer<ChoiceListEntry<T>> {
    /// Stable height measured by [ViewportChoiceList] for every row state.
    public static final int ROW_HEIGHT = 68;

    /// Fixed icon slot dimensions retained while a row is loading or failed.
    private static final int ICON_SIZE = 40;

    /// Maximum badge width before the list becomes narrow.
    private static final int MAX_BADGE_WIDTH = 112;

    /// Minimum width reserved for one badge glyph when the row has enough room.
    private static final int MIN_BADGE_WIDTH = 8;

    /// Normal horizontal gap between the icon, labels, and badge.
    private static final int NORMAL_HORIZONTAL_GAP = 12;

    /// Compact horizontal gap used when the list cannot fit the normal row geometry.
    private static final int COMPACT_HORIZONTAL_GAP = 6;

    /// Placeholder occupying the loaded-row icon slot during asynchronous loading.
    private static final Icon LOADING_ICON = new StatePlaceholderIcon(false);

    /// Placeholder occupying the loaded-row icon slot after a failed request.
    private static final Icon ERROR_ICON = new StatePlaceholderIcon(true);

    /// Primary row title provider.
    private final Function<? super T, String> primaryTextProvider;

    /// Secondary description and metadata provider.
    private final Function<? super T, String> secondaryTextProvider;

    /// Right-aligned badge provider.
    private final Function<? super T, String> badgeTextProvider;

    /// Loaded-row icon provider.
    private final Function<? super T, Icon> iconProvider;

    /// Loaded-row tooltip provider.
    private final Function<? super T, String> tooltipProvider;

    /// Fixed icon host.
    private final JLabel iconLabel = new JLabel();

    /// Two-line center host.
    private final JPanel labels = new JPanel(new GridLayout(2, 1, 0, 2));

    /// Clipped primary row title.
    private final JLabel primaryLabel = new JLabel();

    /// Clipped secondary description and metadata.
    private final JLabel secondaryLabel = new JLabel();

    /// Clipped right-aligned status or version badge.
    private final JLabel badgeLabel = new JLabel();

    /// EDT-confined icon cache keyed by loaded row values.
    private final Map<T, Icon> iconCache = new WeakHashMap<>();

    /// List whose UI owns selection painting, or null before first configuration.
    private @Nullable JList<?> selectionOwner;

    /// Logical row represented during the next paint.
    private int selectionIndex = -1;

    /// Whether the represented row is selected.
    private boolean selected;

    /// Whether the represented row owns keyboard focus.
    private boolean focused;

    /// Current icon slot size after responsive geometry negotiation.
    private int iconSlotSize = ICON_SIZE;

    /// Current horizontal gap after responsive geometry negotiation.
    private int horizontalGap = NORMAL_HORIZONTAL_GAP;

    /// Creates a reusable rich row renderer.
    ///
    /// Every provider must return a non-null value. Providers should only format already loaded
    /// data; potentially blocking metadata reads belong in the viewport data source.
    ///
    /// @param primaryTextProvider primary row title provider
    /// @param secondaryTextProvider secondary description and metadata provider
    /// @param badgeTextProvider right-aligned state or version provider
    /// @param iconProvider loaded-row icon provider
    /// @param tooltipProvider loaded-row tooltip provider
    public RichChoiceListCellRenderer(
            Function<? super T, String> primaryTextProvider,
            Function<? super T, String> secondaryTextProvider,
            Function<? super T, String> badgeTextProvider,
            Function<? super T, Icon> iconProvider,
            Function<? super T, String> tooltipProvider) {
        super(new BorderLayout(NORMAL_HORIZONTAL_GAP, 0));
        this.primaryTextProvider = Objects.requireNonNull(primaryTextProvider, "primaryTextProvider");
        this.secondaryTextProvider = Objects.requireNonNull(secondaryTextProvider, "secondaryTextProvider");
        this.badgeTextProvider = Objects.requireNonNull(badgeTextProvider, "badgeTextProvider");
        this.iconProvider = Objects.requireNonNull(iconProvider, "iconProvider");
        this.tooltipProvider = Objects.requireNonNull(tooltipProvider, "tooltipProvider");

        setOpaque(false);
        setPreferredSize(new Dimension(320, ROW_HEIGHT));
        setMinimumSize(new Dimension(0, ROW_HEIGHT));

        Dimension iconSize = new Dimension(ICON_SIZE, ICON_SIZE);
        iconLabel.setName("richChoiceListIcon");
        iconLabel.setPreferredSize(iconSize);
        iconLabel.setMinimumSize(iconSize);
        iconLabel.setMaximumSize(iconSize);
        iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
        iconLabel.setVerticalAlignment(SwingConstants.CENTER);

        labels.setName("richChoiceListLabels");
        labels.setOpaque(false);
        labels.setMinimumSize(new Dimension(0, 0));
        primaryLabel.setName("richChoiceListPrimary");
        secondaryLabel.setName("richChoiceListSecondary");
        primaryLabel.setVerticalAlignment(SwingConstants.BOTTOM);
        secondaryLabel.setVerticalAlignment(SwingConstants.TOP);
        labels.add(primaryLabel);
        labels.add(secondaryLabel);

        badgeLabel.setName("richChoiceListBadge");
        badgeLabel.setHorizontalAlignment(SwingConstants.TRAILING);
        badgeLabel.setMinimumSize(new Dimension(0, 0));

        add(iconLabel, BorderLayout.LINE_START);
        add(labels, BorderLayout.CENTER);
        add(badgeLabel, BorderLayout.LINE_END);
    }

    /// Configures this reusable renderer for one sparse row state.
    ///
    /// @param list owning list whose current width and palette are authoritative
    /// @param entry loaded, loading, or failed row state
    /// @param index stable logical row index
    /// @param isSelected whether this row is selected
    /// @param cellHasFocus whether this row owns keyboard focus
    /// @return this reusable renderer component
    @Override
    public Component getListCellRendererComponent(
            JList<? extends ChoiceListEntry<T>> list,
            ChoiceListEntry<T> entry,
            int index,
            boolean isSelected,
            boolean cellHasFocus) {
        applyComponentOrientation(list.getComponentOrientation());
        selectionOwner = list;
        selectionIndex = index;
        selected = isSelected;
        focused = cellHasFocus;
        configurePalette(list, isSelected);
        Font baseFont = list.getFont();
        primaryLabel.setFont(baseFont.deriveFont(Font.BOLD));
        secondaryLabel.setFont(baseFont.deriveFont(Math.max(9.0F, baseFont.getSize2D() - 1.0F)));
        badgeLabel.setFont(baseFont.deriveFont(Math.max(
                8.0F,
                baseFont.getSize2D() - 1.0F)));
        setToolTipText(null);

        @Nullable T value = entry.value();
        String badgeText = "";
        if (entry.status() == ChoiceLoadStatus.LOADED && value != null) {
            badgeText = Objects.requireNonNull(badgeTextProvider.apply(value), "badgeTextProvider result");
        }
        configureGeometry(list, badgeText);
        if (entry.status() == ChoiceLoadStatus.LOADED && value != null) {
            String primaryText = Objects.requireNonNull(primaryTextProvider.apply(value),
                    "primaryTextProvider result");
            String secondaryText = Objects.requireNonNull(secondaryTextProvider.apply(value),
                    "secondaryTextProvider result");
            int badgeWidth = badgeText.isBlank() ? 0 : badgeWidth(list, badgeText);
            int textWidth = textWidth(list, badgeWidth);
            primaryLabel.setText(clip(primaryText, primaryLabel.getFontMetrics(
                    primaryLabel.getFont()), textWidth));
            secondaryLabel.setText(clip(secondaryText, secondaryLabel.getFontMetrics(
                    secondaryLabel.getFont()), textWidth));
            badgeLabel.setPreferredSize(new Dimension(badgeWidth, ROW_HEIGHT - 12));
            badgeLabel.setText(clip(badgeText, badgeLabel.getFontMetrics(
                    badgeLabel.getFont()), badgeWidth));
            iconLabel.setIcon(iconFor(value));
            String tooltip = Objects.requireNonNull(tooltipProvider.apply(value), "tooltipProvider result");
            setToolTipText(tooltip.isBlank() ? null : tooltip);
            primaryLabel.getAccessibleContext().setAccessibleName(primaryText);
            secondaryLabel.getAccessibleContext().setAccessibleName(secondaryText);
            badgeLabel.getAccessibleContext().setAccessibleName(badgeText.isBlank() ? null : badgeText);
            getAccessibleContext().setAccessibleName(primaryText);
            getAccessibleContext().setAccessibleDescription(tooltip.isBlank() ? secondaryText : tooltip);
            setEnabled(list.isEnabled());
        } else if (entry.status() == ChoiceLoadStatus.ERROR) {
            primaryLabel.setText("!");
            secondaryLabel.setText(" ");
            badgeLabel.setText("");
            badgeLabel.setPreferredSize(new Dimension(0, ROW_HEIGHT - 12));
            iconLabel.setIcon(ERROR_ICON);
            @Nullable Throwable failure = entry.failure();
            setToolTipText(failure == null ? null : failure.getMessage());
            primaryLabel.getAccessibleContext().setAccessibleName("!");
            secondaryLabel.getAccessibleContext().setAccessibleName(null);
            badgeLabel.getAccessibleContext().setAccessibleName(null);
            getAccessibleContext().setAccessibleName("!");
            getAccessibleContext().setAccessibleDescription(failure == null ? null : failure.getMessage());
            setEnabled(false);
        } else {
            primaryLabel.setText("...");
            secondaryLabel.setText(" ");
            badgeLabel.setText("");
            badgeLabel.setPreferredSize(new Dimension(0, ROW_HEIGHT - 12));
            iconLabel.setIcon(LOADING_ICON);
            setToolTipText(null);
            primaryLabel.getAccessibleContext().setAccessibleName("...");
            secondaryLabel.getAccessibleContext().setAccessibleName(null);
            badgeLabel.getAccessibleContext().setAccessibleName(null);
            getAccessibleContext().setAccessibleName("...");
            getAccessibleContext().setAccessibleDescription(null);
            setEnabled(false);
        }
        primaryLabel.setEnabled(isEnabled());
        secondaryLabel.setEnabled(isEnabled());
        badgeLabel.setEnabled(isEnabled());
        prepareRendererLayout(list);
        return this;
    }

    /// Paints list-owned selection and focus before child labels and icons.
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
        if (focused && owner != null) {
            RoundedListSelectionPainter.paintFocusOutline(owner, graphics, getWidth(), getHeight());
        }
        super.paintComponent(graphics);
    }

    /// Applies list palette and preserves current look-and-feel cell insets.
    ///
    /// @param list owning list
    /// @param isSelected whether the row is selected
    private void configurePalette(JList<? extends ChoiceListEntry<T>> list, boolean isSelected) {
        Color background = isSelected ? list.getSelectionBackground() : list.getBackground();
        Color foreground = isSelected ? list.getSelectionForeground() : list.getForeground();
        setOpaque(false);
        setBackground(background);
        setForeground(foreground);
        primaryLabel.setForeground(foreground);
        secondaryLabel.setForeground(foreground);
        badgeLabel.setForeground(foreground);
        Border cellInsetsBorder = RoundedListSelectionPainter.createCellInsetsBorder(list);
        Insets cellInsets = cellInsetsBorder.getBorderInsets(this);
        int width = list.getWidth() > 0 ? list.getWidth() : getPreferredSize().width;
        int availableWidth = width - cellInsets.left - cellInsets.right;
        if (availableWidth < 0) {
            cellInsetsBorder = BorderFactory.createEmptyBorder();
            availableWidth = width;
        }
        int horizontalPadding = Math.min(10, Math.max(0, availableWidth / 4));
        setBorder(BorderFactory.createCompoundBorder(
                cellInsetsBorder,
                BorderFactory.createEmptyBorder(6, horizontalPadding, 6, horizontalPadding)));
    }

    /// Computes the center-label width from the list's current allocated width.
    ///
    /// @param list owning list
    /// @param badgeWidth allocated badge width
    /// @return positive clipping width
    private int textWidth(JList<?> list, int badgeWidth) {
        int width = list.getWidth() > 0 ? list.getWidth() : getPreferredSize().width;
        Insets insets = getInsets();
        return Math.max(1, width - insets.left - insets.right - iconSlotSize
                - horizontalGap - badgeWidth - horizontalGap);
    }

    /// Computes a bounded badge width that still leaves room for the title.
    ///
    /// @param list owning list
    /// @param badgeText loaded-row badge text
    /// @return non-negative badge width
    private int badgeWidth(JList<?> list, String badgeText) {
        int width = list.getWidth() > 0 ? list.getWidth() : getPreferredSize().width;
        Insets insets = getInsets();
        int contentWidth = Math.max(0, width - insets.left - insets.right);
        int remaining = Math.max(0, contentWidth - iconSlotSize - horizontalGap * 2);
        if (remaining == 0) {
            return 0;
        }
        FontMetrics metrics = badgeLabel.getFontMetrics(badgeLabel.getFont());
        int firstCharacterWidth = metrics.charWidth(badgeText.charAt(0));
        int minimum = Math.max(1, Math.min(remaining, firstCharacterWidth));
        return Math.min(remaining, Math.min(MAX_BADGE_WIDTH, Math.max(minimum, remaining / 3)));
    }

    /// Negotiates icon size and horizontal spacing from the list's current allocated width.
    ///
    /// The compact geometry reserves a visible badge before the center labels receive any extra
    /// space, preventing enabled-state text from disappearing or overlapping the icon at narrow
    /// window sizes.
    ///
    /// @param list owning list
    /// @param badgeText actual loaded-row badge text, or an empty string for sparse states
    private void configureGeometry(JList<?> list, String badgeText) {
        int width = list.getWidth() > 0 ? list.getWidth() : getPreferredSize().width;
        Insets insets = getInsets();
        int contentWidth = Math.max(1, width - insets.left - insets.right);
        horizontalGap = contentWidth < 180 ? COMPACT_HORIZONTAL_GAP : NORMAL_HORIZONTAL_GAP;
        iconSlotSize = contentWidth < 150
                ? Math.max(18, Math.min(ICON_SIZE, contentWidth / 4))
                : ICON_SIZE;
        int badgeMinimum = badgeText.isBlank()
                ? 0
                : Math.min(contentWidth, Math.max(
                        MIN_BADGE_WIDTH,
                        badgeLabel.getFontMetrics(badgeLabel.getFont()).charWidth(badgeText.charAt(0))));
        int totalWithoutBadge = iconSlotSize + horizontalGap * 2;
        if (totalWithoutBadge + badgeMinimum > contentWidth) {
            horizontalGap = Math.max(0, Math.min(
                    horizontalGap,
                    (contentWidth - badgeMinimum - iconSlotSize) / 2));
            if (iconSlotSize + horizontalGap * 2 + badgeMinimum > contentWidth) {
                iconSlotSize = Math.max(0, contentWidth - badgeMinimum - horizontalGap * 2);
            }
        }
        BorderLayout layout = (BorderLayout) getLayout();
        layout.setHgap(horizontalGap);
        Dimension iconSize = new Dimension(iconSlotSize, iconSlotSize);
        iconLabel.setPreferredSize(iconSize);
        iconLabel.setMinimumSize(iconSize);
        iconLabel.setMaximumSize(iconSize);
    }

    /// Assigns child bounds before Swing's renderer pane paints this reusable hierarchy.
    ///
    /// @param list owning list whose width determines the row surface
    private void prepareRendererLayout(JList<?> list) {
        int width = list.getWidth() > 0 ? list.getWidth() : getPreferredSize().width;
        setSize(Math.max(1, width), ROW_HEIGHT);
        doLayout();
        labels.doLayout();
    }

    /// Returns a cached icon for one loaded value.
    ///
    /// @param value loaded row value
    /// @return non-null row icon
    private Icon iconFor(T value) {
        @Nullable Icon cached = iconCache.get(value);
        if (cached != null) {
            return cached;
        }
        Icon icon = Objects.requireNonNull(iconProvider.apply(value), "iconProvider result");
        iconCache.put(value, icon);
        return icon;
    }

    /// Clips text to a measured pixel budget without allowing a label to widen the list.
    ///
    /// @param text source text
    /// @param metrics target font metrics
    /// @param maximumWidth maximum pixel width
    /// @return clipped text or an empty string when no glyph fits
    private static String clip(String text, FontMetrics metrics, int maximumWidth) {
        String value = Objects.requireNonNull(text, "text");
        if (maximumWidth <= 0) {
            return "";
        }
        if (metrics.stringWidth(value) <= maximumWidth) {
            return value;
        }
        String suffix = "...";
        int suffixWidth = metrics.stringWidth(suffix);
        int end = value.length();
        while (end > 0 && metrics.stringWidth(value.substring(0, end)) + suffixWidth > maximumWidth) {
            end--;
        }
        if (end > 0) {
            return value.substring(0, end) + suffix;
        }
        int fittingCharacters = 0;
        while (fittingCharacters < value.length()
                && metrics.stringWidth(value.substring(0, fittingCharacters + 1)) <= maximumWidth) {
            fittingCharacters++;
        }
        return fittingCharacters == 0 ? "" : value.substring(0, fittingCharacters);
    }

    /// Fixed-size placeholder icon used while loading and after a failed range request.
    @NotNullByDefault
    private static final class StatePlaceholderIcon implements Icon {
        /// Whether this placeholder represents an error rather than loading.
        private final boolean error;

        /// Creates one state placeholder.
        ///
        /// @param error whether to paint an error marker
        private StatePlaceholderIcon(boolean error) {
            this.error = error;
        }

        /// Paints a theme-aware marker in the fixed icon slot.
        ///
        /// @param component palette source
        /// @param graphics destination graphics
        /// @param x horizontal origin
        /// @param y vertical origin
        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                @Nullable Color themed = UIManager.getColor(error
                        ? "Actions.Red"
                        : "Label.disabledForeground");
                Color marker = themed == null ? Color.GRAY : themed;
                int arc = Math.max(0, Math.min(ICON_SIZE, UIManager.getInt("Component.arc")));
                copy.setColor(new Color(marker.getRed(), marker.getGreen(), marker.getBlue(), 48));
                copy.fillRoundRect(x, y, ICON_SIZE, ICON_SIZE, arc, arc);
                copy.setColor(new Color(marker.getRed(), marker.getGreen(), marker.getBlue(), 180));
                if (error) {
                    Font font = copy.getFont().deriveFont(Font.BOLD, 22.0F);
                    copy.setFont(font);
                    FontMetrics metrics = copy.getFontMetrics(font);
                    copy.drawString("!", x + (ICON_SIZE - metrics.stringWidth("!")) / 2,
                            y + (ICON_SIZE - metrics.getHeight()) / 2 + metrics.getAscent());
                } else {
                    int dotY = y + ICON_SIZE / 2 - 2;
                    for (int offset = 0; offset < 3; offset++) {
                        copy.fillOval(x + 12 + offset * 7, dotY, 4, 4);
                    }
                }
            } finally {
                copy.dispose();
            }
        }

        /// Returns the fixed icon width.
        ///
        /// @return icon width in pixels
        @Override
        public int getIconWidth() {
            return ICON_SIZE;
        }

        /// Returns the fixed icon height.
        ///
        /// @return icon height in pixels
        @Override
        public int getIconHeight() {
            return ICON_SIZE;
        }
    }
}
