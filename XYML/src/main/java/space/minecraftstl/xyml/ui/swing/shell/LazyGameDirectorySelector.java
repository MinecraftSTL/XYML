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
import space.minecraftstl.xyml.ui.swing.page.settings.GameDirectoryManagementEntry;
import space.minecraftstl.xyml.ui.swing.page.settings.GameDirectoryManagementService;
import space.minecraftstl.xyml.ui.swing.page.settings.GameDirectoryManagementSnapshot;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
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
    private static final Icon FOLDER_ICON = new FlatSVGIcon("assets/swing/icons/folder.svg", 18, 18);

    /// Filled icon used by the process-wide current popup row.
    private static final Icon SELECTED_FOLDER_ICON = new FlatSVGIcon("assets/swing/icons/folder-fill.svg", 18, 18);

    /// Stable list row height used to derive visible rows from actual popup space.
    private static final int ROW_HEIGHT = 38;

    /// Height of each explicit popup command row.
    private static final int COMMAND_HEIGHT = 42;

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
    private final JButton manageButton = new JButton();

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
    /// @param manageCommand command opening the complete directory list
    /// @param revealInstancesCommand command revealing the persistent instances page after selection
    LazyGameDirectorySelector(
            GameDirectoryManagementService service,
            ShellRecentSelections recentSelections,
            String selectorLabel,
            String manageLabel,
            Runnable manageCommand,
            Runnable revealInstancesCommand) {
        super(new BorderLayout());
        EdtDispatcher.requireEventDispatchThread();
        this.service = Objects.requireNonNull(service, "service");
        this.recentSelections = Objects.requireNonNull(recentSelections, "recentSelections");
        this.revealInstancesCommand = Objects.requireNonNull(revealInstancesCommand, "revealInstancesCommand");
        configureComponents(
                Objects.requireNonNull(selectorLabel, "selectorLabel"),
                Objects.requireNonNull(manageLabel, "manageLabel"),
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
    JButton manageButton() {
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
            String manageLabel,
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
        manageButton.setText(manageLabel);
        manageButton.setIcon(new FlatSVGIcon("assets/swing/icons/format-list-bulleted.svg", 18, 18));
        manageButton.setHorizontalAlignment(SwingConstants.LEFT);
        manageButton.putClientProperty("JButton.buttonType", "toolBarButton");
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
        int listBudget = Math.max(ROW_HEIGHT, availablePopupHeight() - COMMAND_HEIGHT);
        int visibleRows = Math.min(Math.max(1, listModel.size()), Math.max(1, listBudget / ROW_HEIGHT));
        int width = Math.max(MINIMUM_POPUP_WIDTH, getWidth());
        popup.setPopupSize(new Dimension(width, visibleRows * ROW_HEIGHT + COMMAND_HEIGHT));
        popup.show(this, 0, getHeight());
    }

    /// Returns the actual vertical screen space below this selector.
    private int availablePopupHeight() {
        @Nullable GraphicsConfiguration configuration = getGraphicsConfiguration();
        if (configuration == null || !isShowing()) {
            int localHeight = getRootPane() == null ? 0 : getRootPane().getHeight() - getHeight();
            return Math.max(COMMAND_HEIGHT + ROW_HEIGHT, localHeight);
        }
        Rectangle screen = configuration.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        int workBottom = screen.y + screen.height - insets.bottom;
        int anchorBottom = getLocationOnScreen().y + getHeight();
        return Math.max(
                COMMAND_HEIGHT + ROW_HEIGHT,
                workBottom - anchorBottom - POPUP_SCREEN_MARGIN);
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

    /// Renders a directory name and path without a redundant radio indicator.
    @NotNullByDefault
    private static final class DirectoryRenderer extends DefaultListCellRenderer {
        /// Configures one in-memory directory row.
        @Override
        public Component getListCellRendererComponent(
                JList<?> owner,
                @Nullable Object value,
                int index,
                boolean selected,
                boolean focused) {
            Component component = super.getListCellRendererComponent(owner, value, index, selected, focused);
            setOpaque(selected);
            if (value instanceof GameDirectoryManagementEntry entry) {
                setText(entry.displayName());
                setToolTipText(entry.path().getPath());
                setIcon(entry.selected() ? SELECTED_FOLDER_ICON : FOLDER_ICON);
                setIconTextGap(8);
            } else {
                setText("");
                setToolTipText(null);
                setIcon(null);
            }
            return component;
        }
    }
}
