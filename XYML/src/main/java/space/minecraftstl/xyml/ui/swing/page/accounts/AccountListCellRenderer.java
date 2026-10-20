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
package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.ui.swing.choice.ChoiceListEntry;
import space.minecraftstl.xyml.ui.swing.choice.ChoiceLoadStatus;
import space.minecraftstl.xyml.ui.swing.choice.RoundedListSelectionPainter;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.Objects;

/// Renders account rows with a lazy real-skin avatar, two text lines, and full-row selection highlight.
@NotNullByDefault
public final class AccountListCellRenderer extends JPanel
        implements ListCellRenderer<ChoiceListEntry<AccountListItem>> {
    /// Stable row height used for loading, loaded, and failed sparse entries.
    public static final int ROW_HEIGHT = 64;

    /// Preferred width of the right-side drag handle.
    private static final int DRAG_HANDLE_WIDTH = 28;

    /// Preferred height of the right-side drag handle.
    private static final int DRAG_HANDLE_HEIGHT = 32;

    /// Horizontal hit width around the right-side drag handle.
    private static final int DRAG_HANDLE_HIT_WIDTH = 44;

    /// Vertical hit height around the right-side drag handle.
    private static final int DRAG_HANDLE_HIT_HEIGHT = 40;

    /// Right inset between the cell edge and the drag handle.
    private static final int DRAG_HANDLE_RIGHT_INSET = 10;

    /// Loading-state icon occupying the stable avatar slot.
    private static final Icon LOADING_ICON = new AccountStateIcon(false);

    /// Failed-state icon occupying the stable avatar slot.
    private static final Icon ERROR_ICON = new AccountStateIcon(true);

    /// Asynchronous shared account-avatar cache.
    private final AccountAvatarIconCache avatarCache = new AccountAvatarIconCache();

    /// Fixed avatar host.
    private final JLabel avatarLabel = new JLabel();

    /// Two-line text host whose child bounds are prepared for renderer-pane painting.
    private final JPanel labels = new JPanel(new GridLayout(2, 1, 0, 2));

    /// Primary profile-name label.
    private final JLabel nameLabel = new JLabel();

    /// Secondary account-provider and storage label.
    private final JLabel detailLabel = new JLabel();

    /// Right-side drag handle used to start account reordering.
    private final JLabel dragHandleLabel = new JLabel();

    /// List whose UI paints the current selection background, or `null` before first configuration.
    private @Nullable JList<?> selectionOwner;

    /// Logical row represented during the next paint.
    private int selectionIndex = -1;

    /// Whether the represented row is selected.
    private boolean selected;

    /// Whether the represented row owns keyboard focus.
    private boolean focused;

    /// Creates one reusable stable renderer hierarchy with account reordering enabled.
    public AccountListCellRenderer() {
        this(true);
    }

    /// Creates one reusable stable renderer hierarchy.
    ///
    /// @param showDragHandle whether loaded rows expose the account-reordering handle
    public AccountListCellRenderer(boolean showDragHandle) {
        super(new BorderLayout(12, 0));
        setOpaque(false);
        setPreferredSize(new Dimension(280, ROW_HEIGHT));

        Dimension avatarSize = new Dimension(
                AccountAvatarIconCache.ICON_SIZE,
                AccountAvatarIconCache.ICON_SIZE);
        avatarLabel.setName("accountListAvatar");
        avatarLabel.setPreferredSize(avatarSize);
        avatarLabel.setMinimumSize(avatarSize);
        avatarLabel.setMaximumSize(avatarSize);
        avatarLabel.setHorizontalAlignment(SwingConstants.CENTER);
        avatarLabel.setVerticalAlignment(SwingConstants.CENTER);

        labels.setOpaque(false);
        nameLabel.setName("accountListName");
        detailLabel.setName("accountListDetail");
        labels.add(nameLabel);
        labels.add(detailLabel);

        Dimension dragHandleSize = new Dimension(DRAG_HANDLE_WIDTH, DRAG_HANDLE_HEIGHT);
        dragHandleLabel.setName("accountListDragHandle");
        dragHandleLabel.setPreferredSize(dragHandleSize);
        dragHandleLabel.setMinimumSize(dragHandleSize);
        dragHandleLabel.setMaximumSize(dragHandleSize);
        dragHandleLabel.setHorizontalAlignment(SwingConstants.CENTER);
        dragHandleLabel.setVerticalAlignment(SwingConstants.CENTER);
        dragHandleLabel.setIcon(new DragHandleIcon());
        dragHandleLabel.setVisible(showDragHandle);

        add(avatarLabel, BorderLayout.LINE_START);
        add(labels, BorderLayout.CENTER);
        add(dragHandleLabel, BorderLayout.LINE_END);
    }

    /// Configures the reusable renderer for one sparse account row.
    ///
    /// @param list owning account list
    /// @param entry loaded, loading, or failed row
    /// @param index stable logical row index
    /// @param selected whether this row is selected
    /// @param focused whether this row has keyboard focus
    /// @return this reusable renderer component
    @Override
    public Component getListCellRendererComponent(
            JList<? extends ChoiceListEntry<AccountListItem>> list,
            ChoiceListEntry<AccountListItem> entry,
            int index,
            boolean selected,
            boolean focused) {
        applyComponentOrientation(list.getComponentOrientation());
        selectionOwner = list;
        selectionIndex = index;
        this.selected = selected;
        this.focused = focused;
        configurePalette(list, selected);
        Font font = list.getFont();
        nameLabel.setFont(font.deriveFont(Font.BOLD));
        detailLabel.setFont(font.deriveFont(Math.max(9.0F, font.getSize2D() - 1.0F)));
        setToolTipText(null);

        @Nullable AccountListItem item = entry.value();
        dragHandleLabel.setEnabled(
                list.isEnabled() && entry.status() == ChoiceLoadStatus.LOADED && item != null);
        if (entry.status() == ChoiceLoadStatus.LOADED && item != null) {
            nameLabel.setText(item.displayName());
            detailLabel.setText(item.detailText().isBlank() ? " " : item.detailText());
            @Nullable Icon avatar = avatarCache.iconFor(item, list);
            avatarLabel.setIcon(avatar == null ? LOADING_ICON : avatar);
        } else if (entry.status() == ChoiceLoadStatus.ERROR) {
            nameLabel.setText("!");
            detailLabel.setText(" ");
            avatarLabel.setIcon(ERROR_ICON);
            @Nullable Throwable failure = entry.failure();
            setToolTipText(failure == null ? null : failure.getMessage());
        } else {
            nameLabel.setText("...");
            detailLabel.setText(" ");
            avatarLabel.setIcon(LOADING_ICON);
        }
        prepareRendererLayout(list);
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
        if (focused && owner != null) {
            RoundedListSelectionPainter.paintFocusOutline(owner, graphics, getWidth(), getHeight());
        }
        super.paintComponent(graphics);
    }

    /// Assigns child bounds before Swing's renderer pane paints this reusable complex component.
    ///
    /// A renderer is not a normal child of the list hierarchy, so offscreen and first-frame painting
    /// cannot rely on a later container validation pass to lay out nested labels.
    ///
    /// @param list owning list whose current width determines the row surface
    private void prepareRendererLayout(JList<?> list) {
        int width = Math.max(getPreferredSize().width, list.getWidth());
        setSize(width, ROW_HEIGHT);
        doLayout();
        labels.doLayout();
    }

    /// Applies list-owned theme colors and focus decoration.
    ///
    /// @param list owning list and palette source
    /// @param selected whether the row is selected
    private void configurePalette(
            JList<? extends ChoiceListEntry<AccountListItem>> list,
            boolean selected) {
        setOpaque(false);
        Color background = selected ? list.getSelectionBackground() : list.getBackground();
        Color foreground = selected ? list.getSelectionForeground() : list.getForeground();
        setBackground(background);
        setForeground(foreground);
        nameLabel.setForeground(foreground);
        detailLabel.setForeground(foreground);
        dragHandleLabel.setForeground(foreground);
        setBorder(BorderFactory.createCompoundBorder(
                RoundedListSelectionPainter.createCellInsetsBorder(list),
                BorderFactory.createEmptyBorder(7, 10, 7, 10)));
    }

    /// Returns the list-coordinate hit area that starts dragging one loaded account row.
    ///
    /// @param cellBounds bounds of the row in list coordinates
    /// @return mutable drag-handle hit rectangle
    public static Rectangle dragHandleBounds(Rectangle cellBounds) {
        Objects.requireNonNull(cellBounds, "cellBounds");
        int x = cellBounds.x + cellBounds.width - DRAG_HANDLE_RIGHT_INSET - DRAG_HANDLE_HIT_WIDTH;
        int y = cellBounds.y + Math.max(0, (cellBounds.height - DRAG_HANDLE_HIT_HEIGHT) / 2);
        return new Rectangle(x, y, DRAG_HANDLE_HIT_WIDTH, DRAG_HANDLE_HIT_HEIGHT);
    }

    /// Paints the six-dot drag affordance using the owning row foreground.
    @NotNullByDefault
    private static final class DragHandleIcon implements Icon {
        /// Paints the six-dot affordance with enabled or disabled theme color.
        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                @Nullable Color themed = component.isEnabled()
                        ? component.getForeground()
                        : UIManager.getColor("Label.disabledForeground");
                Color marker = themed == null ? Color.GRAY : themed;
                copy.setColor(new Color(marker.getRed(), marker.getGreen(), marker.getBlue(), 150));
                for (int row = 0; row < 3; row++) {
                    for (int column = 0; column < 2; column++) {
                        copy.fillOval(x + 4 + column * 6, y + 6 + row * 6, 3, 3);
                    }
                }
            } finally {
                copy.dispose();
            }
        }

        /// Returns the icon width.
        @Override
        public int getIconWidth() {
            return 18;
        }

        /// Returns the icon height.
        @Override
        public int getIconHeight() {
            return 24;
        }
    }

    /// Fixed theme-aware placeholder for account avatar loading and failure states.
    @NotNullByDefault
    private static final class AccountStateIcon implements Icon {
        /// Whether this icon represents a failed row.
        private final boolean error;

        /// Creates one placeholder state icon.
        ///
        /// @param error whether to paint an error marker
        private AccountStateIcon(boolean error) {
            this.error = error;
        }

        /// Paints one theme-shaped placeholder and state mark.
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
                @Nullable Color themed = UIManager.getColor(error ? "Actions.Red" : "Label.disabledForeground");
                Color marker = themed == null ? Color.GRAY : themed;
                copy.setColor(new Color(marker.getRed(), marker.getGreen(), marker.getBlue(), 48));
                int arc = Math.max(0, Math.min(
                        Math.min(getIconWidth(), getIconHeight()),
                        UIManager.getInt("Component.arc")));
                copy.fillRoundRect(x, y, getIconWidth(), getIconHeight(), arc, arc);
                copy.setColor(marker);
                if (error) {
                    copy.drawString("!", x + 18, y + 25);
                } else {
                    for (int offset = 0; offset < 3; ++offset) {
                        copy.fillOval(x + 12 + offset * 7, y + 18, 4, 4);
                    }
                }
            } finally {
                copy.dispose();
            }
        }

        /// Returns the stable icon width.
        ///
        /// @return 40 pixels
        @Override
        public int getIconWidth() {
            return AccountAvatarIconCache.ICON_SIZE;
        }

        /// Returns the stable icon height.
        ///
        /// @return 40 pixels
        @Override
        public int getIconHeight() {
            return AccountAvatarIconCache.ICON_SIZE;
        }
    }
}
