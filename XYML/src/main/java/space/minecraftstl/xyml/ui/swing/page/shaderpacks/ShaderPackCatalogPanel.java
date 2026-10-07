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
package space.minecraftstl.xyml.ui.swing.page.shaderpacks;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingHorizontalScrollPane;
import space.minecraftstl.xyml.ui.swing.SwingTransparency;
import space.minecraftstl.xyml.ui.swing.choice.CatalogIconSupport;
import space.minecraftstl.xyml.ui.swing.choice.RichValueListCellRenderer;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;
import space.minecraftstl.xyml.util.io.DeletionMode;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Swing management page for locally installed shader packs.
@NotNullByDefault
public final class ShaderPackCatalogPanel extends JPanel implements AutoCloseable {
    private static final javax.swing.Icon FALLBACK_ICON =
            CatalogIconSupport.placeholder(new java.awt.Color(112, 96, 150, 100));
    /// Owned catalog model.
    private final ShaderPackCatalogModel model;

    /// Localized content labels.
    private final ShaderPackCatalogStrings strings;

    /// Localized lifecycle labels.
    private final ShaderPackCatalogStatusStrings statusStrings;

    /// Localized action labels.
    private final ShaderPackCatalogActionStrings actionStrings;

    /// Native dialog and desktop boundary.
    private final ShaderPackCatalogInteractions interactions;

    /// Managed shaderpacks directory.
    private final Path shaderPackDirectory;

    /// Search field.
    private final JTextField searchField = new JTextField();

    /// Visible list model.
    private final DefaultListModel<ShaderPackCatalogItem> listModel = new DefaultListModel<>();

    /// Visible selection list.
    private final JList<ShaderPackCatalogItem> list = new JList<>(listModel);

    /// Card layout for lifecycle states.
    private final CardLayout contentCards = new CardLayout();

    /// Lifecycle state container.
    private final JPanel contentPanel = new JPanel(contentCards);

    /// Import command.
    private final JButton importButton = new JButton();

    /// Open-directory command.
    private final JButton openDirectoryButton = new JButton();

    /// Download command.
    private final JButton downloadButton = new JButton();

    /// Refresh command.
    private final JButton refreshButton = new JButton();

    /// Retry command.
    private final JButton retryButton = new JButton();

    /// Select-all command.
    private final JButton selectAllButton = new JButton();

    /// Batch-delete command.
    private final JButton deleteSelectedButton = new JButton();

    /// Single-item enabled toggle.
    private final JCheckBox enabledToggle = new JCheckBox();

    /// Single-item reveal command.
    private final JButton revealButton = new JButton();

    /// Single-item delete command.
    private final JButton deleteButton = new JButton();

    /// Idle card text.
    private final JTextArea idleText = stateText("shaderPacksIdle");

    /// Loading card text.
    private final JTextArea loadingText = stateText("shaderPacksLoading");

    /// Empty card text.
    private final JTextArea emptyText = stateText("shaderPacksEmpty");

    /// Failure card text.
    private final JTextArea failureText = stateText("shaderPacksFailed");

    /// Status band text.
    private final JTextArea statusText = stateText("shaderPacksStatus");

    /// File-name detail.
    private final JLabel iconLabel = new JLabel();

    /// Description detail.
    private final JTextArea descriptionValue = wrappingValue("shaderPacksDescription");

    /// File-name detail.
    private final JTextArea fileNameValue = wrappingValue("shaderPacksFileName");

    /// Path detail.
    private final JTextArea pathValue = wrappingValue("shaderPacksPath");

    /// Enabled-state detail.
    private final JTextArea enabledValue = wrappingValue("shaderPacksEnabled");

    /// Backend detail.
    private final JTextArea backendValue = wrappingValue("shaderPacksBackends");

    /// Current list selection listener.
    private final ListSelectionListener selectionListener = this::selectionChanged;

    /// Search listener.
    private final DocumentListener searchListener = createSearchListener();

    /// Showing listener.
    private final HierarchyListener showingListener = this::showingChanged;

    /// Model subscription.
    private Subscription subscription;

    /// Open-download command.
    private Runnable openDownloadsCommand = () -> { };

    /// Latest snapshot displayed by this panel.
    private ShaderPackCatalogSnapshot snapshot;

    /// Whether this panel is closed.
    private boolean closed;

    /// Whether an asynchronous mutation is active.
    private boolean writePending;

    /// Whether selection updates are programmatic.
    private boolean updatingSelection;

    /// Whether initial loading was requested.
    private boolean initialLoadRequested;

    /// Creates one shader-pack catalog panel.
    ///
    /// @param model owned catalog model
    /// @param strings localized content labels
    /// @param statusStrings localized lifecycle labels
    /// @param actionStrings localized action labels
    /// @param interactions native dialog and desktop boundary
    /// @param shaderPackDirectory managed shaderpacks directory
    public ShaderPackCatalogPanel(
            ShaderPackCatalogModel model,
            ShaderPackCatalogStrings strings,
            ShaderPackCatalogStatusStrings statusStrings,
            ShaderPackCatalogActionStrings actionStrings,
            ShaderPackCatalogInteractions interactions,
            Path shaderPackDirectory) {
        this.model = Objects.requireNonNull(model, "model");
        this.strings = Objects.requireNonNull(strings, "strings");
        this.statusStrings = Objects.requireNonNull(statusStrings, "statusStrings");
        this.actionStrings = Objects.requireNonNull(actionStrings, "actionStrings");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.shaderPackDirectory = Objects.requireNonNull(shaderPackDirectory, "shaderPackDirectory")
                .toAbsolutePath()
                .normalize();
        snapshot = model.snapshot();
        configureComponents();
        subscription = model.subscribe(this::modelChanged);
        addHierarchyListener(showingListener);
        applySnapshot(snapshot);
    }

    /// Installs the download navigation command.
    ///
    /// @param openDownloadsCommand command opening the shader download catalog
    public void setContentCommands(Runnable openDownloadsCommand) {
        EdtDispatcher.requireEventDispatchThread();
        this.openDownloadsCommand = Objects.requireNonNull(openDownloadsCommand, "openDownloadsCommand");
    }

    /// Builds all Swing controls.
    private void configureComponents() {
        setOpaque(false);
        setName("shaderPacksPage");
        setMinimumSize(new Dimension(0, 0));
        setLayout(new MigLayout("insets 0, fill, wrap 1", "[grow,fill]", "[]8[]12[grow,fill]8[]"));

        JPanel heading = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill][][][][]", "[40!]"));
        heading.setName("shaderPacksHeading");
        heading.setOpaque(false);
        JLabel title = new JLabel(strings.pageTitle());
        title.setName("shaderPacksPageTitle");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 28.0F));
        heading.add(title, "growx, wmin 0");
        configureIconButton(importButton, "shaderPacksImport", "assets/swing/icons/file-import.svg",
                actionStrings.importAction(), actionStrings.importTooltip(), this::chooseAndImport);
        heading.add(importButton, "w 40!, h 40!");
        configureIconButton(openDirectoryButton, "shaderPacksOpenDirectory", "assets/swing/icons/folder-open.svg",
                actionStrings.openDirectoryAction(), actionStrings.openDirectoryTooltip(), this::openDirectory);
        heading.add(openDirectoryButton, "w 40!, h 40!");
        configureIconButton(downloadButton, "shaderPacksDownload", "assets/swing/icons/nav-downloads.svg",
                i18n("download.shader"), i18n("download.shader"), () -> openDownloadsCommand.run());
        heading.add(downloadButton, "w 40!, h 40!");
        configureIconButton(refreshButton, "shaderPacksRefresh", "assets/swing/icons/refresh.svg",
                strings.refreshAction(), strings.refreshTooltip(), this::refresh);
        heading.add(refreshButton, "w 40!, h 40!");
        add(heading, "growx");

        add(createToolbar(), "growx, wmin 0");
        configureStateCards();
        contentPanel.setMinimumSize(new Dimension(0, 0));
        add(contentPanel, "grow, wmin 0, hmin 0");
        add(statusText, "growx, wmin 0, hmin 28, hmax 72");
        statusText.setRows(1);
    }

    /// Configures all lifecycle cards shown in the center region.
    private void configureStateCards() {
        idleText.setText(statusStrings.idleText());
        loadingText.setText(statusStrings.loadingText());
        emptyText.setText(statusStrings.emptyText());
        contentPanel.setName("shaderPacksContent");
        contentPanel.setOpaque(false);
        contentPanel.add(stateCard("shaderPacksIdleCard", idleText), "idle");
        contentPanel.add(stateCard("shaderPacksLoadingCard", loadingText), "loading");
        contentPanel.add(stateCard("shaderPacksEmptyCard", emptyText), "empty");
        contentPanel.add(createFailureCard(), "failed");
        contentPanel.add(createListPane(), "ready");
    }

    /// Creates the failure card with a retry command.
    ///
    /// @return failure card
    private JPanel createFailureCard() {
        JPanel card = new JPanel(new MigLayout("insets 24, fill, wrap 1", "[grow,center]", "[grow,center]12[]"));
        card.setName("shaderPacksFailedCard");
        card.setOpaque(false);
        card.add(failureText, "growx");
        configureTextButton(retryButton, "shaderPacksRetry", strings.retryAction(), this::refresh);
        card.add(retryButton, "center");
        return card;
    }

    /// Creates the search and selection toolbar.
    ///
    /// @return toolbar panel
    private JPanel createToolbar() {
        JPanel toolbar = new JPanel(new MigLayout("insets 0, fillx", "[]8[grow,fill]12[]8[]", "[40!]"));
        toolbar.setName("shaderPacksToolbar");
        toolbar.setOpaque(false);
        toolbar.add(new JLabel(i18n("search")));
        searchField.setName("shaderPacksSearch");
        searchField.getDocument().addDocumentListener(searchListener);
        toolbar.add(searchField, "growx, wmin 0, h 40!");
        configureTextButton(selectAllButton, "shaderPacksSelectAll", i18n("button.select_all"), this::selectAll);
        toolbar.add(selectAllButton, "h 40!");
        configureTextButton(deleteSelectedButton, "shaderPacksDeleteSelected", i18n("button.remove"),
                this::deleteSelected);
        toolbar.add(deleteSelectedButton, "h 40!");
        return toolbar;
    }

    /// Creates the list and details split.
    ///
    /// @return list pane
    private JComponent createListPane() {
        JPanel pane = new JPanel(new MigLayout("insets 0, fill", "[grow,fill]12[320!,fill]", "[grow,fill]"));
        pane.setName("shaderPacksWorkspace");
        pane.setOpaque(false);
        list.setName("shaderPacksList");
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.setOpaque(false);
        list.setCellRenderer(new RichValueListCellRenderer<>(
                ShaderPackCatalogItem::displayText,
                item -> item.description().isBlank() ? item.fileName() : item.description(),
                item -> item.enabled() ? strings.enabledText(true) : strings.disabledText(),
                item -> CatalogIconSupport.decode(item.icon(), FALLBACK_ICON),
                item -> item.path().toString(),
                item -> !item.valid()));
        list.addListSelectionListener(selectionListener);
        JScrollPane listScroll = new JScrollPane(list);
        listScroll.setName("shaderPacksListScroll");
        listScroll.setBorder(BorderFactory.createEmptyBorder());
        listScroll.setOpaque(false);
        listScroll.getViewport().setOpaque(false);
        listScroll.setMinimumSize(new Dimension(0, 0));
        pane.add(listScroll, "cell 0 0, grow, wmin 0, hmin 0");
        JScrollPane detailsScroll = new JScrollPane(createDetailsPanel());
        detailsScroll.setName("shaderPacksDetailsScroll");
        detailsScroll.setBorder(BorderFactory.createEmptyBorder());
        detailsScroll.setMinimumSize(new Dimension(0, 0));
        SwingTransparency.revealBackgroundThroughScrollPane(detailsScroll);
        pane.add(detailsScroll, "cell 1 0, grow, wmin 0, hmin 0");
        return new SwingHorizontalScrollPane(pane, "shaderPacksWorkspaceScroll", 600);
    }

    /// Creates the selected-item detail card.
    ///
    /// @return details panel
    private JPanel createDetailsPanel() {
        JPanel details = new JPanel(new MigLayout("insets 8 16 8 12, fillx, wrap 2", "[90!][grow,fill]"));
        details.setName("shaderPacksDetails");
        details.setOpaque(false);
        iconLabel.setName("shaderPacksIcon");
        iconLabel.setPreferredSize(new Dimension(CatalogIconSupport.ICON_SIZE, CatalogIconSupport.ICON_SIZE));
        details.add(iconLabel, "w 40!, h 40!");
        JLabel title = new JLabel(strings.detailsTitle());
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        details.add(title, "growx, wmin 0");
        details.add(new JLabel(strings.descriptionLabel()), "aligny top");
        details.add(descriptionValue, "growx, wmin 0");
        details.add(new JLabel(strings.fileNameLabel()), "aligny top");
        details.add(fileNameValue, "growx, wmin 0");
        details.add(new JLabel(strings.pathLabel()), "aligny top");
        details.add(pathValue, "growx, wmin 0");
        details.add(new JLabel(strings.enabledLabel()), "aligny top");
        details.add(enabledValue, "growx, wmin 0");
        details.add(new JLabel(strings.backendsLabel()), "aligny top");
        details.add(backendValue, "growx, wmin 0");
        JPanel actions = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill][][]", "[40!]"));
        actions.setOpaque(false);
        enabledToggle.setName("shaderPacksEnabledToggle");
        enabledToggle.setText(strings.enabledLabel());
        enabledToggle.addActionListener(this::toggleSelected);
        actions.add(enabledToggle, "growx");
        configureIconButton(revealButton, "shaderPacksReveal", "assets/swing/icons/folder-open.svg",
                actionStrings.revealAction(), actionStrings.revealTooltip(), this::revealSelected);
        actions.add(revealButton, "w 40!, h 40!");
        configureIconButton(deleteButton, "shaderPacksDelete", "assets/swing/icons/delete.svg",
                actionStrings.deleteAction(), actionStrings.deleteTooltip(), this::deleteSelectedPack);
        actions.add(deleteButton, "w 40!, h 40!");
        details.add(actions, "span 2, growx, gapy 12");
        return details;
    }

    /// Returns one state card.
    ///
    /// @param name card name
    /// @param text state text
    /// @return state card
    private static JPanel stateCard(String name, JTextArea text) {
        JPanel card = new JPanel(new MigLayout("insets 24, fill", "[grow,fill]", "[grow,center]"));
        card.setName(name);
        card.setOpaque(false);
        text.setRows(1);
        card.add(text, "growx");
        return card;
    }

    /// Creates one wrapping read-only text area.
    ///
    /// @param name stable component name
    /// @return text area
    private static JTextArea wrappingValue(String name) {
        JTextArea area = new JTextArea();
        area.setName(name);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setBorder(BorderFactory.createEmptyBorder());
        return area;
    }

    /// Creates one non-editable state label.
    ///
    /// @param name stable component name
    /// @return state text
    private static JTextArea stateText(String name) {
        JTextArea area = wrappingValue(name);
        area.setFocusable(false);
        return area;
    }

    /// Configures one text button.
    ///
    /// @param button target button
    /// @param name stable component name
    /// @param text visible text
    /// @param action action command
    private static void configureTextButton(JButton button, String name, String text, Runnable action) {
        button.setName(Objects.requireNonNull(name, "name"));
        button.setText(Objects.requireNonNull(text, "text"));
        button.setToolTipText(text);
        button.addActionListener(event -> action.run());
    }

    /// Configures one icon button.
    ///
    /// @param button target button
    /// @param name stable component name
    /// @param iconPath bundled SVG path
    /// @param accessibleName accessible name
    /// @param tooltip tooltip text
    /// @param action action command
    private static void configureIconButton(
            JButton button,
            String name,
            String iconPath,
            String accessibleName,
            String tooltip,
            Runnable action) {
        button.setName(Objects.requireNonNull(name, "name"));
        button.setText(null);
        button.setIcon(new FlatSVGIcon(iconPath, 18, 18));
        button.setToolTipText(Objects.requireNonNull(tooltip, "tooltip"));
        button.getAccessibleContext().setAccessibleName(Objects.requireNonNull(accessibleName, "accessibleName"));
        button.addActionListener(event -> action.run());
    }

    /// Creates the search document listener.
    ///
    /// @return search listener
    private DocumentListener createSearchListener() {
        return new DocumentListener() {
            /// Applies inserted text.
            @Override
            public void insertUpdate(DocumentEvent event) {
                rebuildList();
            }

            /// Applies removed text.
            @Override
            public void removeUpdate(DocumentEvent event) {
                rebuildList();
            }

            /// Applies attribute changes.
            @Override
            public void changedUpdate(DocumentEvent event) {
                rebuildList();
            }
        };
    }

    /// Starts the initial lazy load when visible.
    ///
    /// @param event hierarchy event
    private void showingChanged(HierarchyEvent event) {
        if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
            requestInitialLoad();
        }
    }

    /// Requests the initial load once.
    private void requestInitialLoad() {
        EdtDispatcher.requireEventDispatchThread();
        if (!closed && !initialLoadRequested) {
            initialLoadRequested = true;
            model.loadIfNeeded();
        }
    }

    /// Receives one asynchronous model transition.
    ///
    /// @param change model change
    private void modelChanged(ValueChange<ShaderPackCatalogSnapshot> change) {
        Objects.requireNonNull(change, "change");
        ShaderPackCatalogSnapshot next = change.currentValue();
        if (next == null) {
            return;
        }
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (!closed) {
                applySnapshot(next);
            }
        });
    }

    /// Applies one complete snapshot on the event-dispatch thread.
    ///
    /// @param next next snapshot
    private void applySnapshot(ShaderPackCatalogSnapshot next) {
        EdtDispatcher.requireEventDispatchThread();
        snapshot = Objects.requireNonNull(next, "next");
        rebuildList();
        switch (snapshot.status()) {
            case IDLE -> contentCards.show(contentPanel, "idle");
            case LOADING -> contentCards.show(contentPanel, "loading");
            case FAILED -> {
                failureText.setText(snapshot.statusText());
                contentCards.show(contentPanel, "failed");
            }
            case READY -> contentCards.show(contentPanel, snapshot.items().isEmpty() ? "empty" : "ready");
        }
        statusText.setText(snapshot.writeStatusText().isBlank() ? snapshot.statusText() : snapshot.writeStatusText());
        refreshButton.setEnabled(snapshot.status() != ShaderPackCatalogStatus.LOADING && !writePending);
        updateActionAvailability();
    }

    /// Rebuilds the visible filtered list while preserving a stable selected path.
    private void rebuildList() {
        if (!javax.swing.SwingUtilities.isEventDispatchThread()) {
            return;
        }
        Path previous = selectedPath();
        String query = searchField.getText().trim().toLowerCase(Locale.ROOT);
        updatingSelection = true;
        try {
            listModel.clear();
            for (ShaderPackCatalogItem item : snapshot.items()) {
                if (query.isEmpty() || item.displayText().toLowerCase(Locale.ROOT).contains(query)
                        || item.fileName().toLowerCase(Locale.ROOT).contains(query)) {
                    listModel.addElement(item);
                }
            }
            if (previous != null) {
                for (int index = 0; index < listModel.size(); index++) {
                    if (listModel.get(index).path().equals(previous)) {
                        list.setSelectedIndex(index);
                        break;
                    }
                }
            }
        } finally {
            updatingSelection = false;
        }
        if (listModel.isEmpty()) {
            showNoSelection();
        } else {
            selectionChanged(null);
        }
    }

    /// Handles a list selection change.
    ///
    /// @param event selection event, or null after a full rebuild
    private void selectionChanged(@Nullable ListSelectionEvent event) {
        if (updatingSelection || closed) {
            return;
        }
        List<ShaderPackCatalogItem> selected = selectedItems();
        if (selected.size() == 1) {
            showDetails(selected.get(0));
        } else {
            showNoSelection();
        }
        updateActionAvailability();
    }

    /// Displays one selected item.
    ///
    /// @param item selected item
    private void showDetails(ShaderPackCatalogItem item) {
        iconLabel.setIcon(CatalogIconSupport.decode(item.icon(), FALLBACK_ICON));
        descriptionValue.setText(item.description().isBlank() ? strings.descriptionUnavailableText() : item.description());
        fileNameValue.setText(item.fileName());
        pathValue.setText(item.path().toString());
        enabledValue.setText(strings.enabledText(item.enabled()));
        if (!item.valid()) {
            enabledValue.setText(enabledValue.getText() + " / " + strings.invalidText());
        }
        backendValue.setText(item.enabledBackends().isEmpty()
                ? strings.disabledText()
                : String.join(", ", item.enabledBackends().stream()
                        .map(ShaderPackBackend::displayName)
                        .toList()));
        enabledToggle.setSelected(item.enabled());
        enabledToggle.setToolTipText(actionStrings.enableTooltip());
    }

    /// Clears the details selection.
    private void showNoSelection() {
        iconLabel.setIcon(FALLBACK_ICON);
        descriptionValue.setText("");
        fileNameValue.setText("");
        pathValue.setText("");
        enabledValue.setText(strings.noSelectionText());
        backendValue.setText(snapshot.availableBackends().isEmpty()
                ? strings.noBackendText()
                : strings.disabledText());
        enabledToggle.setSelected(false);
    }

    /// Returns currently selected items in visible order.
    ///
    /// @return immutable selected items
    private List<ShaderPackCatalogItem> selectedItems() {
        List<ShaderPackCatalogItem> selected = new ArrayList<>();
        for (int index : list.getSelectedIndices()) {
            selected.add(listModel.get(index));
        }
        return List.copyOf(selected);
    }

    /// Returns the currently selected stable path.
    ///
    /// @return selected path, or null
    private @Nullable Path selectedPath() {
        int index = list.getSelectedIndex();
        return index < 0 || index >= listModel.size() ? null : listModel.get(index).path();
    }

    /// Updates command availability from current selection and write state.
    private void updateActionAvailability() {
        boolean writable = !closed && !writePending && snapshot.writeStatus() != ShaderPackCatalogWriteStatus.BUSY;
        int selectedCount = list.getSelectedIndices().length;
        boolean one = selectedCount == 1;
        @Nullable ShaderPackCatalogItem selected = singleSelectedItem();
        boolean oneValid = one && selected != null && selected.valid();
        boolean hasBackends = !snapshot.availableBackends().isEmpty();
        selectAllButton.setEnabled(writable && listModel.size() > 0 && selectedCount < listModel.size());
        deleteSelectedButton.setEnabled(writable && selectedCount > 0);
        enabledToggle.setEnabled(writable && oneValid && hasBackends);
        if (!hasBackends) {
            enabledToggle.setToolTipText(strings.noBackendText());
        }
        revealButton.setEnabled(writable && one && selectedPath() != null);
        deleteButton.setEnabled(writable && one && selectedPath() != null);
        importButton.setEnabled(writable);
        openDirectoryButton.setEnabled(!closed);
        downloadButton.setEnabled(!closed);
    }

    /// Refreshes the catalog.
    private void refresh() {
        EdtDispatcher.requireEventDispatchThread();
        model.refresh();
    }

    /// Opens a chooser and imports selected sources.
    private void chooseAndImport() {
        EdtDispatcher.requireEventDispatchThread();
        List<Path> sources = interactions.chooseImportFiles(this, shaderPackDirectory);
        if (!sources.isEmpty()) {
            startWrite(() -> model.importShaderPacks(sources));
        }
    }

    /// Opens the managed shaderpacks directory.
    private void openDirectory() {
        EdtDispatcher.requireEventDispatchThread();
        interactions.openDirectory(shaderPackDirectory).whenComplete((ignored, failure) -> {
            if (failure != null) {
                showFailure(actionStrings.openDirectoryFailedTitle(), failure);
            }
        });
    }

    /// Selects every visible row.
    private void selectAll() {
        EdtDispatcher.requireEventDispatchThread();
        if (!listModel.isEmpty()) {
            list.setSelectionInterval(0, listModel.size() - 1);
        }
    }

    /// Deletes every selected row.
    private void deleteSelected() {
        EdtDispatcher.requireEventDispatchThread();
        List<ShaderPackCatalogItem> selected = selectedItems();
        if (selected.isEmpty()) {
            return;
        }
        @Nullable DeletionMode mode = interactions.chooseDeleteModeSelected(this, selected.size());
        if (mode == null) {
            return;
        }
        List<Path> paths = selected.stream().map(ShaderPackCatalogItem::path).toList();
        startWrite(() -> model.deleteShaderPacks(paths, mode));
    }

    /// Toggles the selected pack through the backend chooser.
    ///
    /// @param event action event
    private void toggleSelected(ActionEvent event) {
        ShaderPackCatalogItem selected = singleSelectedItem();
        if (selected == null || !selected.valid()) {
            return;
        }
        Set<ShaderPackBackend> backends = snapshot.availableBackends().size() == 1
                ? snapshot.availableBackends()
                : interactions.chooseBackends(this, snapshot.availableBackends());
        if (backends == null || backends.isEmpty()) {
            showDetails(selected);
            return;
        }
        boolean enable = !selected.enabledBackends().containsAll(backends);
        startWrite(() -> model.setShaderPackEnabled(selected.path(), backends, enable));
    }

    /// Reveals the single selected pack.
    private void revealSelected() {
        ShaderPackCatalogItem selected = singleSelectedItem();
        if (selected == null) {
            return;
        }
        interactions.reveal(selected).whenComplete((ignored, failure) -> {
            if (failure != null) {
                showFailure(actionStrings.revealFailedTitle(), failure);
            }
        });
    }

    /// Deletes the single selected pack.
    private void deleteSelectedPack() {
        ShaderPackCatalogItem selected = singleSelectedItem();
        if (selected == null) {
            return;
        }
        @Nullable DeletionMode mode = interactions.chooseDeleteMode(this, selected);
        if (mode != null) {
            startWrite(() -> model.deleteShaderPack(selected.path(), mode));
        }
    }

    /// Returns the single selected item, or null.
    ///
    /// @return single selected item
    private @Nullable ShaderPackCatalogItem singleSelectedItem() {
        List<ShaderPackCatalogItem> selected = selectedItems();
        return selected.size() == 1 ? selected.get(0) : null;
    }

    /// Starts one asynchronous mutation and reports failures.
    ///
    /// @param operation operation returning a terminal snapshot
    private void startWrite(Supplier<CompletionStage<ShaderPackCatalogSnapshot>> operation) {
        if (closed || writePending) {
            return;
        }
        writePending = true;
        updateActionAvailability();
        CompletionStage<ShaderPackCatalogSnapshot> stage;
        try {
            stage = Objects.requireNonNull(operation.get(), "operation returned null");
        } catch (RuntimeException | Error failure) {
            writePending = false;
            applySnapshot(model.snapshot());
            showFailure(actionStrings.operationFailedTitle(), failure);
            return;
        }
        stage.whenComplete((next, failure) -> SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            writePending = false;
            if (next != null) {
                applySnapshot(next);
            } else {
                applySnapshot(model.snapshot());
                if (failure != null) {
                    showFailure(actionStrings.operationFailedTitle(), failure);
                }
            }
            updateActionAvailability();
        }));
    }

    /// Shows one failure detail on the event-dispatch thread.
    ///
    /// @param title localized title
    /// @param failure failure to show
    private void showFailure(String title, Throwable failure) {
        Throwable cause = unwrap(failure);
        String detail = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> interactions.showFailure(this, title, detail));
    }

    /// Unwraps completion wrappers.
    ///
    /// @param failure failure to unwrap
    /// @return deepest available cause
    private static Throwable unwrap(Throwable failure) {
        Throwable current = Objects.requireNonNull(failure, "failure");
        while (current.getCause() != null
                && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)) {
            current = current.getCause();
        }
        return current;
    }

    /// Closes this panel and its owned model.
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        removeHierarchyListener(showingListener);
        if (subscription != null) {
            subscription.unsubscribe();
            subscription = null;
        }
        model.close();
    }
}
