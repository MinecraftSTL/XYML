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
import com.formdev.flatlaf.extras.FlatSVGIcon;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.setting.GameDirectoryID;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.choice.RoundedListSelectionPainter;
import space.minecraftstl.xyml.ui.swing.page.settings.GameDirectoryManagementEntry;
import space.minecraftstl.xyml.ui.swing.page.settings.GameDirectoryManagementService;
import space.minecraftstl.xyml.ui.swing.page.settings.GameDirectoryManagementSnapshot;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.ListCellRenderer;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.border.Border;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsConfiguration;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Compact game-directory selector with an MRU-ordered list and one command opening the complete list page.
@NotNullByDefault
final class LazyGameDirectorySelector extends JPanel implements AutoCloseable {
    /// Outline icon used by inactive popup rows.
    private static final Icon FOLDER_ICON = new FlatSVGIcon("assets/swing/icons/folder.svg", 40, 40);

    /// Filled icon used by the process-wide current popup row.
    private static final Icon SELECTED_FOLDER_ICON = new FlatSVGIcon("assets/swing/icons/folder-fill.svg", 40, 40);

    /// Stable list row height shared with account and instance selector rows.
    static final int ROW_HEIGHT = 64;

    /// Height of each explicit popup command row.
    static final int COMMAND_HEIGHT = PopupCommandButton.HEIGHT;

    /// Minimum popup width retaining readable directory names.
    private static final int MINIMUM_POPUP_WIDTH = 300;

    /// Space retained around the popup inside its current screen work area.
    private static final int POPUP_SCREEN_MARGIN = 16;

    /// Current selected directory display and popup command.
    private final ShellDropdownButton valueButton = new ShellDropdownButton();

    /// Reusable popup hosting MRU entries and the complete-list command.
    private final RoundedPopupMenu popup = new RoundedPopupMenu();

    /// Exact in-memory directory rows in selector order.
    private final DefaultListModel<GameDirectoryManagementEntry> listModel = new DefaultListModel<>();

    /// Single-selection directory list without a redundant radio indicator.
    private final JList<GameDirectoryManagementEntry> list = new JList<>(listModel);

    /// Smooth-wheel scroll container for the bounded MRU directory list.
    private final JScrollPane directoryScrollPane = new JScrollPane(list);

    /// Rounded host clipping the complete directory single-choice region.
    private final RoundedChoicePanel choiceHost = new RoundedChoicePanel(new BorderLayout());

    /// Bottom command opening the complete directory list.
    private final PopupCommandButton manageButton;

    /// Directory selection service.
    private final GameDirectoryManagementService service;

    /// Independent selector history.
    private final ShellRecentSelections recentSelections;

    /// Command revealing persistent instance management after a directory selection.
    private final Runnable revealInstancesCommand;

    /// Whether programmatic row restoration suppresses selection commands.
    private boolean applyingSnapshot;

    /// Whether interaction has been permanently released.
    private boolean closed;

    /// Creates one title-bar directory selector.
    ///
    /// @param service directory source and selection sink
    /// @param recentSelections persistent compact-selector history
    /// @param selectorLabel accessible selector label
    /// @param manageLabel localized complete-list action
    /// @param manageDetail localized complete-list explanation
    /// @param manageCommand command opening the complete directory list
    /// @param revealInstancesCommand command revealing the persistent instances page after selection
    LazyGameDirectorySelector(
            GameDirectoryManagementService service,
            ShellRecentSelections recentSelections,
            String selectorLabel,
            String manageLabel,
            String manageDetail,
            Runnable manageCommand,
            Runnable revealInstancesCommand) {
        super(new BorderLayout());
        EdtDispatcher.requireEventDispatchThread();
        this.service = Objects.requireNonNull(service, "service");
        this.recentSelections = Objects.requireNonNull(recentSelections, "recentSelections");
        this.revealInstancesCommand = Objects.requireNonNull(revealInstancesCommand, "revealInstancesCommand");
        manageButton = new PopupCommandButton(
                Objects.requireNonNull(manageLabel, "manageLabel"),
                Objects.requireNonNull(manageDetail, "manageDetail"),
                new FlatSVGIcon("assets/swing/icons/format-list-bulleted.svg", 24, 24));
        configureComponents(
                Objects.requireNonNull(selectorLabel, "selectorLabel"),
                Objects.requireNonNull(manageCommand, "manageCommand"));
    }

    /// Applies current directory content, selected value, and independent recent-use order.
    ///
    /// @param snapshot current directory service state
    void applySnapshot(GameDirectoryManagementSnapshot snapshot) {
        EdtDispatcher.requireEventDispatchThread();
        GameDirectoryManagementSnapshot state = Objects.requireNonNull(snapshot, "snapshot");
        List<String> sourceIds = new ArrayList<>(state.entries().size());
        Map<String, GameDirectoryManagementEntry> entriesById = new HashMap<>();
        @Nullable GameDirectoryManagementEntry selected = null;
        for (GameDirectoryManagementEntry entry : state.entries()) {
            String stableId = entry.id().toString();
            sourceIds.add(stableId);
            entriesById.put(stableId, entry);
            if (entry.selected()) {
                selected = entry;
            }
        }
        if (selected != null) {
            recentSelections.recordDirectory(selected.id().toString());
        }
        @Unmodifiable List<String> orderedIds = recentSelections.orderDirectories(sourceIds);
        applyingSnapshot = true;
        try {
            listModel.clear();
            for (String stableId : orderedIds) {
                @Nullable GameDirectoryManagementEntry entry = entriesById.get(stableId);
                if (entry != null) {
                    listModel.addElement(entry);
                }
            }
            list.setSelectedValue(selected, true);
        } finally {
            applyingSnapshot = false;
        }
        valueButton.setText(selected == null ? "" : selected.displayName());
        valueButton.setToolTipText(selected == null ? null : selected.path().getPath());
        boolean available = !closed && selected != null;
        valueButton.setEnabled(!closed && !state.entries().isEmpty());
        list.setEnabled(!closed && !state.entries().isEmpty());
        manageButton.setEnabled(!closed);
        valueButton.getAccessibleContext().setAccessibleDescription(
                available ? valueButton.getToolTipText() : null);
        popup.setVisible(false);
    }

    /// Returns the current-value popup button for focused geometry tests.
    ///
    /// @return stable selected-value button
    ShellDropdownButton valueButton() {
        return valueButton;
    }

    /// Returns the complete-list command for focused popup tests.
    ///
    /// @return stable management button
    PopupCommandButton manageButton() {
        return manageButton;
    }

    /// Returns the MRU-ordered directory list for focused tests.
    ///
    /// @return stable list component
    JList<GameDirectoryManagementEntry> directoryList() {
        return list;
    }

    /// Returns the popup list scroll container for focused behavior tests.
    ///
    /// @return stable smooth-scrolling popup container
    JScrollPane directoryScrollPane() {
        return directoryScrollPane;
    }

    /// Returns the popup size after deriving it from current item count and available geometry.
    ///
    /// @return current popup size
    Dimension preparePopupSize() {
        EdtDispatcher.requireEventDispatchThread();
        Insets popupInsets = popup.getInsets();
        int popupVerticalInsets = popupInsets.top + popupInsets.bottom;
        int listBudget = Math.max(
                ROW_HEIGHT,
                availablePopupHeight() - COMMAND_HEIGHT - popupVerticalInsets);
        int visibleRows = Math.min(Math.max(1, listModel.size()), Math.max(1, listBudget / ROW_HEIGHT));
        int width = Math.max(MINIMUM_POPUP_WIDTH, getWidth());
        int popupHeight = visibleRows * ROW_HEIGHT + COMMAND_HEIGHT + popupVerticalInsets;
        Dimension size = new Dimension(width, popupHeight);
        popup.setPopupSize(size);
        return size;
    }

    /// Releases popup interaction.
    @Override
    public void close() {
        EdtDispatcher.requireEventDispatchThread();
        if (!closed) {
            closed = true;
            popup.setVisible(false);
            valueButton.setEnabled(false);
            list.setEnabled(false);
            manageButton.setEnabled(false);
        }
    }

    /// Builds the single-button selector and two-part popup.
    private void configureComponents(
            String selectorLabel,
            Runnable manageCommand) {
        setName("shellGameDirectorySelector");
        setOpaque(false);
        valueButton.setName("shellGameDirectoryValue");
        valueButton.setIcon(new FlatSVGIcon("assets/swing/icons/folder-open.svg", 18, 18));
        valueButton.setIconTextGap(8);
        valueButton.bindPopup(popup, this::showPopup);
        valueButton.getAccessibleContext().setAccessibleName(selectorLabel);
        add(valueButton, BorderLayout.CENTER);

        popup.setName("shellGameDirectoryPopup");
        popup.setLayout(new BorderLayout());
        list.setName("shellGameDirectoryPopupList");
        list.setFixedCellHeight(ROW_HEIGHT);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DirectoryRenderer());
        list.setOpaque(false);
        list.addListSelectionListener(event -> {
            if (!closed && !applyingSnapshot && !event.getValueIsAdjusting()) {
                submitSelection();
            }
        });
        directoryScrollPane.setName("shellGameDirectoryPopupScroll");
        directoryScrollPane.putClientProperty(
                FlatClientProperties.SCROLL_PANE_SMOOTH_SCROLLING,
                Boolean.TRUE);
        directoryScrollPane.setBorder(BorderFactory.createEmptyBorder());
        directoryScrollPane.setOpaque(false);
        directoryScrollPane.getViewport().setOpaque(false);
        directoryScrollPane.getVerticalScrollBar().setUnitIncrement(ROW_HEIGHT);
        choiceHost.setName("shellGameDirectoryChoices");
        choiceHost.add(directoryScrollPane, BorderLayout.CENTER);
        popup.add(choiceHost, BorderLayout.CENTER);

        manageButton.setName("shellGameDirectoryManagement");
        manageButton.addActionListener(event -> {
            if (!closed) {
                popup.setVisible(false);
                manageCommand.run();
            }
        });
        popup.add(manageButton, BorderLayout.SOUTH);
    }

    /// Shows a popup whose list height follows its exact item count and available screen space.
    private void showPopup() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            return;
        }
        Dimension size = preparePopupSize();
        popup.show(this, 0, getHeight());
        popup.setPopupSize(size);
    }

    /// Returns the actual vertical screen space below this selector.
    private int availablePopupHeight() {
        @Nullable GraphicsConfiguration configuration = getGraphicsConfiguration();
        if (configuration == null || !isShowing()) {
            int localHeight = getRootPane() == null ? 0 : getRootPane().getHeight() - getHeight();
            return Math.max(minimumPopupHeight(), localHeight);
        }
        Rectangle screen = configuration.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        int workBottom = screen.y + screen.height - insets.bottom;
        int anchorBottom = getLocationOnScreen().y + getHeight();
        return Math.max(
                minimumPopupHeight(),
                workBottom - anchorBottom - POPUP_SCREEN_MARGIN);
    }

    /// Returns the complete popup height needed for one choice row, its command, and outer spacing.
    ///
    /// @return minimum usable popup height
    private int minimumPopupHeight() {
        Insets popupInsets = popup.getInsets();
        return COMMAND_HEIGHT + ROW_HEIGHT + popupInsets.top + popupInsets.bottom;
    }

    /// Commits one exact stable directory selection.
    private void submitSelection() {
        @Nullable GameDirectoryManagementEntry selected = list.getSelectedValue();
        if (selected == null || selected.selected()) {
            return;
        }
        GameDirectoryID id = selected.id();
        service.select(id);
        popup.setVisible(false);
        revealInstancesCommand.run();
    }

    /// Renders a directory name and path in the same two-line, 64-pixel geometry as other toolbar selectors.
    @NotNullByDefault
    private static final class DirectoryRenderer extends JPanel
            implements ListCellRenderer<GameDirectoryManagementEntry> {
        /// Fixed folder-icon host aligned with other selector media slots.
        private final JLabel iconLabel = new JLabel();

        /// Primary directory display name.
        private final JLabel nameLabel = new JLabel();

        /// Weaker persisted directory path.
        private final JLabel pathLabel = new JLabel();

        /// Two-line text host laid out explicitly for renderer-pane painting.
        private final JPanel labels = new JPanel(new GridLayout(2, 1, 0, 2));

        /// Owning list used for rounded selection painting, or `null` before first configuration.
        private @Nullable JList<?> selectionOwner;

        /// Selected row index used by FlatLaf selection geometry.
        private int selectionIndex = -1;

        /// Whether the represented row is selected.
        private boolean selected;

        /// Creates one reusable directory renderer with stable row geometry.
        private DirectoryRenderer() {
            super(new BorderLayout(12, 0));
            setOpaque(false);
            setPreferredSize(new Dimension(280, ROW_HEIGHT));

            Dimension iconSize = new Dimension(40, 40);
            iconLabel.setName("gameDirectoryListIcon");
            iconLabel.setPreferredSize(iconSize);
            iconLabel.setMinimumSize(iconSize);
            iconLabel.setMaximumSize(iconSize);
            iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
            iconLabel.setVerticalAlignment(SwingConstants.CENTER);

            labels.setOpaque(false);
            nameLabel.setName("gameDirectoryListName");
            pathLabel.setName("gameDirectoryListPath");
            nameLabel.setVerticalAlignment(SwingConstants.BOTTOM);
            pathLabel.setVerticalAlignment(SwingConstants.TOP);
            labels.add(nameLabel);
            labels.add(pathLabel);

            add(iconLabel, BorderLayout.LINE_START);
            add(labels, BorderLayout.CENTER);
        }

        /// Configures one in-memory directory row and its path detail.
        ///
        /// @param owner owning list
        /// @param value represented directory
        /// @param index row index
        /// @param selected whether the row is selected
        /// @param focused whether the row owns keyboard focus
        /// @return reusable renderer component
        @Override
        public Component getListCellRendererComponent(
                JList<? extends GameDirectoryManagementEntry> owner,
                GameDirectoryManagementEntry value,
                int index,
                boolean selected,
                boolean focused) {
            applyComponentOrientation(owner.getComponentOrientation());
            selectionOwner = owner;
            selectionIndex = index;
            this.selected = selected;
            configurePalette(owner, selected, focused);

            Font font = owner.getFont();
            nameLabel.setFont(font.deriveFont(Font.BOLD));
            pathLabel.setFont(font.deriveFont(Math.max(9.0F, font.getSize2D() - 1.0F)));
            nameLabel.setText(value.displayName());
            pathLabel.setText(value.path().getPath());
            iconLabel.setIcon(value.selected() ? SELECTED_FOLDER_ICON : FOLDER_ICON);
            setToolTipText(value.path().getPath());
            prepareRendererLayout(owner);
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

        /// Assigns nested child bounds before Swing's renderer pane paints this reusable hierarchy.
        ///
        /// @param owner owning list whose current width determines the row surface
        private void prepareRendererLayout(JList<?> owner) {
            int width = Math.max(getPreferredSize().width, owner.getWidth());
            setSize(width, ROW_HEIGHT);
            doLayout();
            labels.doLayout();
        }

        /// Applies list-owned colors, including a weaker unselected path foreground.
        ///
        /// @param owner owning list and palette source
        /// @param selected whether the row is selected
        /// @param focused whether the row owns keyboard focus
        private void configurePalette(
                JList<? extends GameDirectoryManagementEntry> owner,
                boolean selected,
                boolean focused) {
            Color background = selected ? owner.getSelectionBackground() : owner.getBackground();
            Color foreground = selected ? owner.getSelectionForeground() : owner.getForeground();
            @Nullable Color disabledForeground = UIManager.getColor("Label.disabledForeground");
            setBackground(background);
            setForeground(foreground);
            nameLabel.setForeground(foreground);
            pathLabel.setForeground(selected || disabledForeground == null ? foreground : disabledForeground);
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
    }
}
