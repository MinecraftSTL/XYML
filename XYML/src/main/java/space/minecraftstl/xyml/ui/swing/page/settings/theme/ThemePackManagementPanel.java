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
package space.minecraftstl.xyml.ui.swing.page.settings.theme;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.theme.ThemeReference;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingTextFields;
import space.minecraftstl.xyml.ui.swing.SwingTransparency;
import space.minecraftstl.xyml.ui.swing.choice.ChoiceListEntry;
import space.minecraftstl.xyml.ui.swing.choice.ChoiceLoadStatus;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceList;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import javax.swing.event.ListSelectionEvent;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Insets;
import java.awt.event.ActionListener;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/// Native Swing surface for searching, importing, selecting, locating, and deleting local theme packs.
///
/// The panel owns and closes its model. Package metadata reaches the reusable viewport renderer only after an
/// asynchronous inventory load; theme-pack images are never decoded by this surface.
@NotNullByDefault
public final class ThemePackManagementPanel extends JPanel implements AutoCloseable {
    /// Fixed toolbar command size in logical pixels.
    private static final int COMMAND_SIZE = 36;

    /// Fixed stable renderer row height measured by `ViewportChoiceList`.
    private static final int ROW_HEIGHT = 68;

    /// Managed inventory and mutation coordinator.
    private final ThemePackManagementModel model;

    /// Localized surface text.
    private final ThemePackManagementStrings strings;

    /// Native chooser, confirmation, and desktop integration boundary.
    private final ThemePackManagementInteractions interactions;

    /// Adaptive single-selection list driven by measured viewport rows.
    private final ViewportChoiceList<ThemePackItem> choiceList;

    /// Reusable row renderer that distinguishes selected, applied, and package-origin states.
    private final ThemePackItemRenderer itemRenderer;

    /// Lightweight local manifest-index search field.
    private final JTextField searchField = new JTextField();

    /// Reloads both embedded and installed package indexes.
    private final JButton refreshButton = new JButton();

    /// Opens the local archive chooser.
    private final JButton importButton = new JButton();

    /// Applies the exact selected theme reference.
    private final JButton applyButton = new JButton();

    /// Opens the exact selected installed package directory.
    private final JButton locateButton = new JButton();

    /// Deletes the exact selected installed package after confirmation.
    private final JButton deleteButton = new JButton();

    /// Factual loading, empty, ready, operation, or failure text.
    private final JLabel statusLabel = new JLabel();

    /// Snapshot transition subscription owned by this panel.
    private final Subscription modelSubscription;

    /// Listener updating commands after a loaded row is selected.
    private final javax.swing.event.ListSelectionListener selectionListener = this::selectionChanged;

    /// Listener selecting an applied row after its viewport slice finishes loading.
    private final ListDataListener listDataListener = createListDataListener();

    /// Guards initial inventory activation.
    private final AtomicBoolean activated = new AtomicBoolean();

    /// Guards terminal cleanup and late icon injection.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Background load for bundled toolbar SVGs, or `null` after rejected submission.
    private final @Nullable CompletableFuture<@Unmodifiable Map<String, Icon>> iconLoad;

    /// Last snapshot represented by the component tree.
    private ThemePackManagementSnapshot displayedSnapshot;

    /// Content revision already handed to the sparse viewport model.
    private long appliedContentRevision = -1L;

    /// Prevents programmatic selection changes from re-entering command updates.
    private boolean synchronizing;

    /// Creates and activates an embeddable theme-pack management panel on the EDT.
    ///
    /// @param model owned management model
    /// @param strings localized text
    /// @param interactions chooser, confirmation, and desktop boundary
    /// @param iconExecutor caller-owned non-EDT executor for bundled SVG loading
    public ThemePackManagementPanel(
            ThemePackManagementModel model,
            ThemePackManagementStrings strings,
            ThemePackManagementInteractions interactions,
            Executor iconExecutor) {
        super(new java.awt.BorderLayout());
        EdtDispatcher.requireEventDispatchThread();
        this.model = Objects.requireNonNull(model, "model");
        this.strings = Objects.requireNonNull(strings, "strings");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        displayedSnapshot = this.model.snapshot();
        itemRenderer = new ThemePackItemRenderer(this.strings);
        choiceList = new ViewportChoiceList<>(this.model, itemRenderer);

        setName("themePackManagementPanel");
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder());
        add(createHeader(), java.awt.BorderLayout.NORTH);
        add(createListSurface(), java.awt.BorderLayout.CENTER);
        add(createFooter(), java.awt.BorderLayout.SOUTH);
        configureSearch();
        configureList();
        modelSubscription = this.model.subscribe(this::modelChanged);
        applySnapshot(displayedSnapshot);
        iconLoad = preloadCommandIcons(Objects.requireNonNull(iconExecutor, "iconExecutor"));
        activate();
    }

    /// Returns the adaptive list for host accessibility and focused integration tests.
    ///
    /// @return owned viewport list
    public ViewportChoiceList<ThemePackItem> choiceList() {
        EdtDispatcher.requireEventDispatchThread();
        return choiceList;
    }

    /// Returns the latest snapshot represented by the panel.
    ///
    /// @return displayed snapshot
    public ThemePackManagementSnapshot displayedSnapshot() {
        EdtDispatcher.requireEventDispatchThread();
        return displayedSnapshot;
    }

    /// Starts the initial asynchronous inventory request exactly once.
    public void activate() {
        EdtDispatcher.requireEventDispatchThread();
        if (!closed.get()
                && activated.compareAndSet(false, true)
                && model.snapshot().status() == ThemePackManagementStatus.IDLE) {
            model.refresh();
        }
    }

    /// Releases listeners, sparse viewport state, icon work, and the owned model.
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        EdtDispatcher.execute(() -> {
            modelSubscription.unsubscribe();
            choiceList.getList().removeListSelectionListener(selectionListener);
            choiceList.getChoiceModel().removeListDataListener(listDataListener);
            choiceList.close();
            if (iconLoad != null) {
                iconLoad.cancel(true);
            }
            model.close();
        });
    }

    /// Creates a two-row header that remains usable at narrow settings-page widths.
    private JComponent createHeader() {
        JPanel header = new JPanel(new MigLayout(
                "insets 0 0 10 0, fillx, wrap 2",
                "[grow,fill][]",
                "[]8[36!]"));
        header.setOpaque(false);
        JLabel title = new JLabel(strings.title());
        title.setName("themePacksTitle");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20.0F));
        header.add(title, "span 2, growx");

        searchField.setName("themePacksSearch");
        SwingTextFields.showClearButton(searchField);
        searchField.putClientProperty("JTextField.placeholderText", strings.searchLabel());
        searchField.getAccessibleContext().setAccessibleName(strings.searchLabel());
        header.add(searchField, "growx, h 36!");

        JPanel commands = new JPanel(new MigLayout("insets 0, gap 8", "[36!][36!]", "[36!]"));
        commands.setOpaque(false);
        configureIconButton(refreshButton, "themePacksRefresh", strings.refreshTooltip(), event -> refresh());
        configureIconButton(importButton, "themePacksImport", strings.importTooltip(), event -> chooseAndImport());
        commands.add(refreshButton, fixedCommandConstraint());
        commands.add(importButton, fixedCommandConstraint());
        header.add(commands);
        return header;
    }

    /// Creates the unframed adaptive theme choice list.
    private JComponent createListSurface() {
        choiceList.setName("themePacksChoiceList");
        SwingTransparency.revealBackgroundThroughScrollPane(choiceList);
        choiceList.getList().setName("themePacksList");
        choiceList.getList().setOpaque(false);
        choiceList.getList().setVisibleRowCount(0);
        choiceList.getList().getAccessibleContext().setAccessibleName(strings.title());
        return choiceList;
    }

    /// Creates a compact factual status row and fixed-size icon commands.
    private JComponent createFooter() {
        JPanel footer = new JPanel(new MigLayout(
                "insets 10 0 0 0, fillx",
                "[grow,fill][36!]8[36!]8[36!]",
                "[36!]"));
        footer.setOpaque(false);
        statusLabel.setName("themePacksStatus");
        footer.add(statusLabel, "growx");
        configureIconButton(applyButton, "themePacksApply", strings.applyTooltip(), event -> applySelected());
        configureIconButton(locateButton, "themePacksLocate", strings.locateTooltip(), event -> locateSelected());
        configureIconButton(deleteButton, "themePacksDelete", strings.deleteTooltip(), event -> deleteSelected());
        footer.add(applyButton, fixedCommandConstraint());
        footer.add(locateButton, fixedCommandConstraint());
        footer.add(deleteButton, fixedCommandConstraint());
        return footer;
    }

    /// Connects document changes to local manifest-index filtering.
    private void configureSearch() {
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            /// Applies inserted query text.
            @Override
            public void insertUpdate(DocumentEvent event) {
                queryChanged();
            }

            /// Applies removed query text.
            @Override
            public void removeUpdate(DocumentEvent event) {
                queryChanged();
            }

            /// Applies attribute changes for styled document compatibility.
            @Override
            public void changedUpdate(DocumentEvent event) {
                queryChanged();
            }
        });
    }

    /// Connects sparse row completion and single selection to action state.
    private void configureList() {
        choiceList.getList().addListSelectionListener(selectionListener);
        choiceList.getChoiceModel().addListDataListener(listDataListener);
    }

    /// Creates the sparse row completion listener.
    private ListDataListener createListDataListener() {
        return new ListDataListener() {
            /// Reconciles added rows.
            @Override
            public void intervalAdded(ListDataEvent event) {
                loadedRowsChanged();
            }

            /// Reconciles removed rows.
            @Override
            public void intervalRemoved(ListDataEvent event) {
                loadedRowsChanged();
            }

            /// Reconciles loaded or failed placeholders.
            @Override
            public void contentsChanged(ListDataEvent event) {
                loadedRowsChanged();
            }
        };
    }

    /// Applies a user search edit without any filesystem or image access.
    private void queryChanged() {
        EdtDispatcher.requireEventDispatchThread();
        if (!closed.get() && !synchronizing) {
            model.setQuery(searchField.getText());
        }
    }

    /// Reloads the complete inventory after an explicit refresh command.
    private void refresh() {
        model.refresh();
    }

    /// Chooses and imports one local archive, preserving chooser cancellation as a no-op.
    private void chooseAndImport() {
        @Nullable Path archive = interactions.chooseImportArchive(this);
        if (archive != null) {
            model.importArchive(archive);
        }
    }

    /// Applies the exact currently loaded selection.
    private void applySelected() {
        @Nullable ThemePackItem selected = choiceList.getSelectedValue();
        if (selected != null) {
            model.apply(selected);
        }
    }

    /// Revalidates and opens the exact currently selected installed directory.
    private void locateSelected() {
        @Nullable ThemePackItem selected = choiceList.getSelectedValue();
        if (selected != null && !selected.builtIn()) {
            model.locate(selected, interactions::revealInstalledDirectory);
        }
    }

    /// Confirms and deletes the complete installed package containing the current selection.
    private void deleteSelected() {
        @Nullable ThemePackItem selected = choiceList.getSelectedValue();
        if (selected != null && model.canDelete(selected) && interactions.confirmDelete(this, selected)) {
            model.delete(selected);
        }
    }

    /// Updates command state after one settled list selection event.
    private void selectionChanged(ListSelectionEvent event) {
        if (!event.getValueIsAdjusting() && !synchronizing) {
            updateActions();
        }
    }

    /// Selects the applied loaded row when possible and refreshes commands.
    private void loadedRowsChanged() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get()) {
            return;
        }
        selectAppliedRowIfNeeded();
        updateActions();
    }

    /// Dispatches one model transition to the EDT and coalesces to the latest snapshot.
    private void modelChanged(ValueChange<ThemePackManagementSnapshot> change) {
        Objects.requireNonNull(change, "change");
        EdtDispatcher.execute(() -> {
            if (!closed.get()) {
                applySnapshot(model.snapshot());
            }
        });
    }

    /// Applies one immutable snapshot without echoing programmatic search changes.
    private void applySnapshot(ThemePackManagementSnapshot newSnapshot) {
        EdtDispatcher.requireEventDispatchThread();
        synchronizing = true;
        try {
            displayedSnapshot = Objects.requireNonNull(newSnapshot, "newSnapshot");
            if (!searchField.getText().equals(newSnapshot.query())) {
                searchField.setText(newSnapshot.query());
            }
            if (appliedContentRevision != newSnapshot.contentRevision()) {
                appliedContentRevision = newSnapshot.contentRevision();
                choiceList.getList().clearSelection();
                choiceList.reloadData();
            }
            statusLabel.setText(statusText(newSnapshot));
            itemRenderer.setAppliedTheme(newSnapshot.appliedTheme());
            choiceList.getList().repaint();
        } finally {
            synchronizing = false;
        }
        selectAppliedRowIfNeeded();
        updateActions();
    }

    /// Chooses factual status text for current inventory and mutation state.
    private String statusText(ThemePackManagementSnapshot current) {
        if (current.operation() != ThemePackManagementOperation.NONE) {
            return switch (current.operation()) {
                case IMPORTING -> strings.importingText();
                case APPLYING -> strings.applyingText();
                case DELETING -> strings.deletingText();
                case LOCATING -> strings.locatingText();
                case NONE -> throw new IllegalStateException("Operation changed during status formatting");
            };
        }
        return switch (current.status()) {
            case IDLE, LOADING -> strings.loadingText();
            case FAILED -> strings.failureFormat().formatted(current.failureMessage());
            case CLOSED -> "";
            case READY -> {
                if (current.items().isEmpty()) {
                    yield current.totalItemCount() == 0 ? strings.emptyText() : strings.noResultsText();
                }
                yield strings.countFormat().formatted(current.items().size());
            }
        };
    }

    /// Selects the applied reference after its sparse viewport row becomes available.
    private void selectAppliedRowIfNeeded() {
        if (choiceList.getList().getSelectedIndex() >= 0) {
            return;
        }
        @Nullable ThemeReference applied = displayedSnapshot.appliedTheme();
        if (applied == null) {
            return;
        }
        for (int index = 0; index < choiceList.getChoiceModel().getSize(); index++) {
            @Nullable ThemePackItem item = choiceList.getChoiceModel().loadedValueAt(index);
            if (item != null && item.reference().equals(applied)) {
                synchronizing = true;
                try {
                    choiceList.getList().setSelectedIndex(index);
                } finally {
                    synchronizing = false;
                }
                return;
            }
        }
    }

    /// Applies busy, origin, and current-theme authorization to all commands.
    private void updateActions() {
        @Nullable ThemePackItem selected = choiceList.getSelectedValue();
        boolean busy = displayedSnapshot.busy();
        boolean ready = displayedSnapshot.status() == ThemePackManagementStatus.READY && !busy;
        refreshButton.setEnabled(!busy && displayedSnapshot.status() != ThemePackManagementStatus.CLOSED);
        importButton.setEnabled(ready);
        searchField.setEnabled(!busy && displayedSnapshot.status() != ThemePackManagementStatus.CLOSED);
        choiceList.getList().setEnabled(ready);
        applyButton.setEnabled(ready
                && selected != null
                && !selected.reference().equals(displayedSnapshot.appliedTheme()));
        locateButton.setEnabled(ready && selected != null && !selected.builtIn());
        deleteButton.setEnabled(ready && selected != null && model.canDelete(selected));
    }

    /// Configures one fixed-size icon-only command with accessible tooltip text.
    private static void configureIconButton(
            JButton button,
            String name,
            String tooltip,
            ActionListener listener) {
        button.setName(Objects.requireNonNull(name, "name"));
        button.setToolTipText(Objects.requireNonNull(tooltip, "tooltip"));
        button.getAccessibleContext().setAccessibleName(tooltip);
        button.getAccessibleContext().setAccessibleDescription(tooltip);
        button.setHorizontalAlignment(SwingConstants.CENTER);
        button.setMargin(new Insets(0, 0, 0, 0));
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.addActionListener(Objects.requireNonNull(listener, "listener"));
    }

    /// Returns the fixed command constraint shared by toolbar and footer buttons.
    private static String fixedCommandConstraint() {
        return "w " + COMMAND_SIZE + "!, h " + COMMAND_SIZE + "!";
    }

    /// Loads all bundled toolbar SVGs on a caller-owned background executor.
    private @Nullable CompletableFuture<@Unmodifiable Map<String, Icon>> preloadCommandIcons(Executor executor) {
        try {
            CompletableFuture<@Unmodifiable Map<String, Icon>> future = CompletableFuture.supplyAsync(
                    ThemePackManagementPanel::loadCommandIcons,
                    executor);
            future.whenComplete((
                    @Nullable @Unmodifiable Map<String, Icon> icons,
                    @Nullable Throwable failure) -> EdtDispatcher.execute(() -> {
                        if (!closed.get() && failure == null && icons != null) {
                            refreshButton.setIcon(icons.get("refresh"));
                            importButton.setIcon(icons.get("import"));
                            applyButton.setIcon(icons.get("apply"));
                            locateButton.setIcon(icons.get("locate"));
                            deleteButton.setIcon(icons.get("delete"));
                        }
                    }));
            return future;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /// Reads and constructs every bundled SVG icon off the EDT.
    private static @Unmodifiable Map<String, Icon> loadCommandIcons() {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Theme management icons must not load on the EDT");
        }
        Map<String, Icon> icons = new LinkedHashMap<>();
        icons.put("refresh", svgIcon("assets/swing/icons/refresh.svg"));
        icons.put("import", svgIcon("assets/swing/icons/file-import.svg"));
        icons.put("apply", svgIcon("assets/swing/icons/save.svg"));
        icons.put("locate", svgIcon("assets/swing/icons/folder-open.svg"));
        icons.put("delete", svgIcon("assets/swing/icons/delete.svg"));
        return Map.copyOf(icons);
    }

    /// Constructs one 18-pixel theme-aware SVG icon.
    private static FlatSVGIcon svgIcon(String resource) {
        FlatSVGIcon icon = new FlatSVGIcon(Objects.requireNonNull(resource, "resource"), 18, 18);
        icon.setColorFilter(new FlatSVGIcon.ColorFilter(ThemePackManagementPanel::resolveIconColor));
        return icon;
    }

    /// Resolves an SVG color from its current owning component and look and feel.
    private static Color resolveIconColor(@Nullable Component component, Color originalColor) {
        @Nullable Color foreground = component == null ? null : component.getForeground();
        if (foreground != null) {
            return foreground;
        }
        @Nullable Color fallback = UIManager.getColor("Button.foreground");
        return fallback == null ? Objects.requireNonNull(originalColor, "originalColor") : fallback;
    }

    /// Reusable stable-height renderer for loaded, loading, and failed viewport rows.
    @NotNullByDefault
    private static final class ThemePackItemRenderer extends JPanel
            implements ListCellRenderer<ChoiceListEntry<ThemePackItem>> {
        /// Localized origin and applied labels.
        private final ThemePackManagementStrings strings;

        /// Clipped primary theme name; the containing row paints the selected state.
        private final JLabel primary = new JLabel();

        /// Clipped package, version, and author metadata.
        private final JLabel secondary = new JLabel();

        /// Right-aligned applied or package-origin state.
        private final JLabel badge = new JLabel();

        /// Exact currently applied reference, or `null`.
        private @Nullable ThemeReference appliedTheme;

        /// Creates the stable reusable row surface.
        private ThemePackItemRenderer(ThemePackManagementStrings strings) {
            super(new java.awt.BorderLayout(12, 0));
            this.strings = Objects.requireNonNull(strings, "strings");
            setPreferredSize(new Dimension(320, ROW_HEIGHT));
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(7, 10, 7, 10));
            primary.setName("themePackRowPrimary");
            secondary.setName("themePackRowSecondary");
            badge.setName("themePackRowBadge");
            primary.setOpaque(false);
            primary.setFont(primary.getFont().deriveFont(Font.BOLD));
            secondary.setFont(secondary.getFont().deriveFont(secondary.getFont().getSize2D() - 1.0F));
            badge.setHorizontalAlignment(SwingConstants.TRAILING);
            badge.setPreferredSize(new Dimension(90, ROW_HEIGHT - 14));
            JPanel labels = new JPanel(new java.awt.GridLayout(2, 1, 0, 2));
            labels.setOpaque(false);
            labels.add(primary);
            labels.add(secondary);
            add(labels, java.awt.BorderLayout.CENTER);
            add(badge, java.awt.BorderLayout.EAST);
        }

        /// Updates the applied marker without rebuilding the list model.
        private void setAppliedTheme(@Nullable ThemeReference appliedTheme) {
            this.appliedTheme = appliedTheme;
        }

        /// Configures this single renderer for one sparse row state.
        @Override
        public Component getListCellRendererComponent(
                JList<? extends ChoiceListEntry<ThemePackItem>> list,
                ChoiceListEntry<ThemePackItem> entry,
                int index,
                boolean isSelected,
                boolean cellHasFocus) {
            setComponentOrientation(list.getComponentOrientation());
            setOpaque(isSelected);
            Color background = isSelected ? list.getSelectionBackground() : list.getBackground();
            Color foreground = isSelected ? list.getSelectionForeground() : list.getForeground();
            setBackground(background);
            primary.setForeground(foreground);
            secondary.setForeground(foreground);
            badge.setForeground(foreground);
            setBorder(cellHasFocus
                    ? UIManager.getBorder("List.focusCellHighlightBorder")
                    : UIManager.getBorder("List.cellNoFocusBorder"));

            @Nullable ThemePackItem item = entry.value();
            if (entry.status() == ChoiceLoadStatus.LOADED && item != null) {
                int primaryWidth = Math.max(96, list.getWidth() - 150);
                primary.setText(clip(item.displayName(), primary.getFontMetrics(primary.getFont()), primaryWidth));
                String detail = item.packageName() + " | " + item.version();
                if (!item.authors().isEmpty()) {
                    detail += " | " + item.authors();
                }
                secondary.setText(clip(detail, secondary.getFontMetrics(secondary.getFont()), primaryWidth));
                badge.setText(item.reference().equals(appliedTheme)
                        ? strings.appliedLabel()
                        : item.builtIn() ? strings.builtInLabel() : strings.installedLabel());
                setToolTipText(item.description());
                setEnabled(list.isEnabled());
            } else if (entry.status() == ChoiceLoadStatus.ERROR) {
                primary.setText("!");
                secondary.setText("");
                badge.setText("");
                @Nullable Throwable failure = entry.failure();
                setToolTipText(failure == null ? null : failure.getMessage());
                setEnabled(false);
            } else {
                primary.setText("...");
                secondary.setText("");
                badge.setText("");
                setToolTipText(null);
                setEnabled(false);
            }
            primary.setEnabled(isEnabled());
            secondary.setEnabled(isEnabled());
            badge.setEnabled(isEnabled());
            return this;
        }

        /// Clips one renderer string to a stable pixel budget with an ASCII ellipsis.
        private static String clip(String text, FontMetrics metrics, int maximumWidth) {
            String value = Objects.requireNonNull(text, "text");
            if (metrics.stringWidth(value) <= maximumWidth) {
                return value;
            }
            String suffix = "...";
            int suffixWidth = metrics.stringWidth(suffix);
            int end = value.length();
            while (end > 0 && metrics.stringWidth(value.substring(0, end)) + suffixWidth > maximumWidth) {
                end--;
            }
            return value.substring(0, end) + suffix;
        }
    }
}
