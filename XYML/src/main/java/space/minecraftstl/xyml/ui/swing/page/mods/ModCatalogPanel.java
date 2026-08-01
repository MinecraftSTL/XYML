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
package space.minecraftstl.xyml.ui.swing.page.mods;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameRepository;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingTextFields;
import space.minecraftstl.xyml.ui.swing.SwingTransparency;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceList;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Independent Swing page for one installed-Mod catalog.
///
/// The page owns its model and viewport list lifecycle. Construction and every component mutation
/// occur on the EDT; all disk and desktop operations are delegated to background-aware model and
/// interaction contracts. No JavaFX type or network-capable service is referenced.
@NotNullByDefault
public final class ModCatalogPanel extends JPanel implements AutoCloseable {
    /// Toolkit-neutral installed-Mod model owned by this page.
    private final ModCatalogModel model;

    /// Localized page labels.
    private final ModCatalogStrings strings;

    /// Localized action presentation.
    private final ModCatalogActionStrings actionStrings;

    /// Dialog and asynchronous desktop interactions.
    private final ModCatalogInteractions interactions;

    /// Stable managed Mod directory used by import and directory-open commands.
    private final Path modsDirectory;

    /// Viewport-driven multi-choice list.
    private final ViewportChoiceList<ModCatalogItem> choiceList;

    /// Search field applied to the in-memory index.
    private final JTextField searchField = new JTextField();

    /// Enabled-state filter selector.
    private final JComboBox<ModCatalogFilter> filterBox = new JComboBox<>(ModCatalogFilter.values());

    /// Full-index refresh command.
    private final JButton refreshButton = new JButton();

    /// Local Mod import command.
    private final JButton importButton = new JButton();

    /// Managed-directory open command.
    private final JButton openDirectoryButton = new JButton();

    /// Selected-file reveal command.
    private final JButton revealButton = new JButton();

    /// Selected-file deletion command.
    private final JButton deleteButton = new JButton();

    /// Selects every logical row in the current filtered index.
    private final JButton selectAllButton = new JButton();

    /// Enables every selected rename-stable Mod key.
    private final JButton enableSelectedButton = new JButton();

    /// Disables every selected rename-stable Mod key.
    private final JButton disableSelectedButton = new JButton();

    /// Permanently deletes every selected rename-stable Mod key.
    private final JButton deleteSelectedButton = new JButton();

    /// Selected enabled-state binary control.
    private final JCheckBox enabledToggle;

    /// Current index lifecycle text.
    private final JLabel statusLabel = new JLabel();

    /// Current mutation lifecycle text.
    private final JLabel writeStatusLabel = new JLabel();

    /// Selected Mod primary title or empty-selection placeholder.
    private final JLabel detailTitle = new JLabel();

    /// Selected Mod identifier value.
    private final JLabel idValue = new JLabel();

    /// Selected Mod version value.
    private final JLabel versionValue = new JLabel();

    /// Selected target game version value.
    private final JLabel gameVersionValue = new JLabel();

    /// Selected detected loader value.
    private final JLabel loaderValue = new JLabel();

    /// Selected authors value.
    private final JLabel authorsValue = new JLabel();

    /// Selected current file value.
    private final JLabel fileValue = new JLabel();

    /// Selected complete plain-text description.
    private final JTextArea descriptionArea = new JTextArea();

    /// Search-document listener removed during closure.
    private final DocumentListener searchListener;

    /// Sparse-list data listener removed during closure.
    private final ListDataListener listDataListener;

    /// User selection listener removed during closure.
    private final ListSelectionListener selectionListener;

    /// Owned model subscription.
    private final Subscription modelSubscription;

    /// Last snapshot rendered by this panel.
    private ModCatalogSnapshot displayedSnapshot;

    /// Revision already applied to the viewport model.
    private long appliedContentRevision = -1L;

    /// Prevents programmatic control synchronization from issuing commands.
    private boolean synchronizing;

    /// Whether lifecycle teardown has completed.
    private boolean closed;

    /// Creates a production page backed by the real repository and default platform interactions.
    ///
    /// The caller owns the executor and must keep it available until this panel is closed.
    ///
    /// @param repository real game repository
    /// @param instanceId managed instance identifier
    /// @param executor caller-owned background executor
    /// @param strings localized page labels
    /// @param statusStrings localized model statuses
    /// @param actionStrings localized commands
    public ModCatalogPanel(
            GameRepository repository,
            GameInstanceID instanceId,
            Executor executor,
            ModCatalogStrings strings,
            ModCatalogStatusStrings statusStrings,
            ModCatalogActionStrings actionStrings) {
        this(
                new DefaultModCatalogModel(
                        Objects.requireNonNull(repository, "repository"),
                        Objects.requireNonNull(instanceId, "instanceId"),
                        Objects.requireNonNull(executor, "executor"),
                        Objects.requireNonNull(statusStrings, "statusStrings")),
                strings,
                actionStrings,
                new DefaultModCatalogInteractions(actionStrings, executor));
    }

    /// Creates a page with injected model and interaction boundaries for headless tests.
    ///
    /// The panel owns and closes the supplied model.
    ///
    /// @param model installed-Mod model
    /// @param strings localized page labels
    /// @param actionStrings localized commands
    /// @param interactions dialog and desktop interactions
    public ModCatalogPanel(
            ModCatalogModel model,
            ModCatalogStrings strings,
            ModCatalogActionStrings actionStrings,
            ModCatalogInteractions interactions) {
        EdtDispatcher.requireEventDispatchThread();
        this.model = Objects.requireNonNull(model, "model");
        this.strings = Objects.requireNonNull(strings, "strings");
        this.actionStrings = Objects.requireNonNull(actionStrings, "actionStrings");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        modsDirectory = model.modsDirectory().toAbsolutePath().normalize();
        displayedSnapshot = model.snapshot();
        enabledToggle = new JCheckBox(strings.enabledLabel());
        choiceList = new ViewportChoiceList<>(model, ModCatalogItem::displayText);
        searchListener = createSearchListener();
        listDataListener = createListDataListener();
        selectionListener = this::selectionChanged;

        setName("modsCatalogPage");
        setLayout(new BorderLayout());
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder());
        add(createHeadingBand(), BorderLayout.NORTH);
        add(createCatalogSplit(), BorderLayout.CENTER);
        add(createStatusBand(), BorderLayout.SOUTH);
        configureList();
        configureControls();
        showDetails(null);

        modelSubscription = model.subscribe(change -> {
            @Nullable ModCatalogSnapshot current = change.currentValue();
            if (current != null) {
                EdtDispatcher.execute(() -> applySnapshot(current));
            }
        });
        applySnapshot(displayedSnapshot);
        model.loadIfNeeded();
    }

    /// Returns the viewport list for host integration and deterministic tests.
    ///
    /// @return owned viewport list
    public ViewportChoiceList<ModCatalogItem> choiceList() {
        return choiceList;
    }

    /// Returns the latest snapshot rendered by the panel.
    ///
    /// @return displayed snapshot
    public ModCatalogSnapshot displayedSnapshot() {
        return displayedSnapshot;
    }

    /// Creates the title and global icon-command band.
    ///
    /// @return unframed heading component
    private JComponent createHeadingBand() {
        JPanel headingBand = new JPanel(new MigLayout(
                "insets 16 16 8 16, fillx",
                "[grow,fill][]8[]8[]",
                "[40!]"));
        headingBand.setOpaque(false);
        JLabel heading = new JLabel(strings.title());
        heading.setName("modsPageTitle");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 26.0F));
        headingBand.add(heading, "growx");
        configureIconButton(
                refreshButton,
                "modsRefresh",
                "assets/swing/icons/refresh.svg",
                actionStrings.refreshAction(),
                actionStrings.refreshTooltip(),
                model::refresh);
        headingBand.add(refreshButton, "w 40!, h 40!");
        configureIconButton(
                importButton,
                "modsImport",
                "assets/swing/icons/file-import.svg",
                actionStrings.importAction(),
                actionStrings.importTooltip(),
                this::chooseAndImport);
        headingBand.add(importButton, "w 40!, h 40!");
        configureIconButton(
                openDirectoryButton,
                "modsOpenDirectory",
                "assets/swing/icons/folder-open.svg",
                actionStrings.openDirectoryAction(),
                actionStrings.openDirectoryTooltip(),
                this::openDirectory);
        headingBand.add(openDirectoryButton, "w 40!, h 40!");
        return headingBand;
    }

    /// Creates list controls and single-selection details in one stable split.
    ///
    /// @return borderless split pane
    private JComponent createCatalogSplit() {
        JPanel listSurface = new JPanel(new BorderLayout(0, 8));
        listSurface.setOpaque(false);
        listSurface.setBorder(BorderFactory.createEmptyBorder(8, 16, 12, 8));
        listSurface.setMinimumSize(new Dimension(0, 0));
        JPanel filters = new JPanel(new MigLayout(
                "insets 0, fillx",
                "[][grow,fill]8[][140!]",
                "[32!]"));
        filters.setOpaque(false);
        JLabel searchLabel = new JLabel(strings.searchLabel());
        filters.add(searchLabel);
        searchField.setName("modsSearch");
        SwingTextFields.showClearButton(searchField);
        searchField.getAccessibleContext().setAccessibleName(strings.searchLabel());
        filters.add(searchField, "growx");
        JLabel filterLabel = new JLabel(strings.filterLabel());
        filters.add(filterLabel);
        filterBox.setName("modsFilter");
        filterBox.setRenderer(new FilterRenderer(strings));
        filterBox.getAccessibleContext().setAccessibleName(strings.filterLabel());
        filters.add(filterBox, "growx");
        JPanel listControls = new JPanel(new BorderLayout(0, 6));
        listControls.setOpaque(false);
        listControls.add(filters, BorderLayout.NORTH);
        listControls.add(createBatchToolbar(), BorderLayout.SOUTH);
        listSurface.add(listControls, BorderLayout.NORTH);
        choiceList.setName("modsChoiceList");
        SwingTransparency.revealBackgroundThroughScrollPane(choiceList);
        choiceList.getList().setName("modsList");
        choiceList.getList().setOpaque(false);
        listSurface.add(choiceList, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                listSurface,
                createDetailsSurface());
        split.setName("modsCatalogSplit");
        split.setOpaque(false);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setContinuousLayout(true);
        split.setResizeWeight(0.44D);
        split.setDividerLocation(0.44D);
        split.setMinimumSize(new Dimension(0, 0));
        return split;
    }

    /// Creates compact logical-selection commands without materializing off-screen rows.
    ///
    /// @return transparent batch command toolbar
    private JComponent createBatchToolbar() {
        JPanel toolbar = new JPanel(new MigLayout(
                "insets 0, fillx",
                "[grow,fill]6[grow,fill]6[grow,fill]6[grow,fill]",
                "[36!]"));
        toolbar.setName("modsBatchToolbar");
        toolbar.setOpaque(false);
        configureTextButton(
                selectAllButton,
                "modsSelectAll",
                i18n("button.select_all"),
                this::selectAllMods);
        configureTextButton(
                enableSelectedButton,
                "modsEnableSelected",
                i18n("mods.enable"),
                () -> setSelectedModsEnabled(true));
        configureTextButton(
                disableSelectedButton,
                "modsDisableSelected",
                i18n("mods.disable"),
                () -> setSelectedModsEnabled(false));
        configureTextButton(
                deleteSelectedButton,
                "modsDeleteSelected",
                i18n("button.remove"),
                this::deleteSelectedMods);
        toolbar.add(selectAllButton, "growx, h 36!");
        toolbar.add(enableSelectedButton, "growx, h 36!");
        toolbar.add(disableSelectedButton, "growx, h 36!");
        toolbar.add(deleteSelectedButton, "growx, h 36!");
        return toolbar;
    }

    /// Creates the unframed selected-Mod details surface.
    ///
    /// @return compact details panel with an as-needed transparent vertical scrollbar
    private JComponent createDetailsSurface() {
        JPanel details = new JPanel(new MigLayout(
                "insets 8 16 8 12, fillx, wrap 2",
                "[110!][grow,fill]",
                "[]8[][][][][][]8[]8[]"));
        details.setName("modsDetails");
        details.setOpaque(false);
        detailTitle.setName("modsDetailTitle");
        detailTitle.setFont(detailTitle.getFont().deriveFont(Font.BOLD, 20.0F));
        details.add(detailTitle, "span 2, growx");
        addDetailRow(details, strings.idLabel(), idValue, "modsDetailId");
        addDetailRow(details, strings.versionLabel(), versionValue, "modsDetailVersion");
        addDetailRow(details, strings.gameVersionLabel(), gameVersionValue, "modsDetailGameVersion");
        addDetailRow(details, strings.loaderLabel(), loaderValue, "modsDetailLoader");
        addDetailRow(details, strings.authorsLabel(), authorsValue, "modsDetailAuthors");
        addDetailRow(details, strings.fileLabel(), fileValue, "modsDetailFile");

        JLabel descriptionLabel = new JLabel(strings.descriptionLabel());
        details.add(descriptionLabel, "aligny top");
        descriptionArea.setName("modsDetailDescription");
        descriptionArea.setEditable(false);
        descriptionArea.setLineWrap(true);
        descriptionArea.setWrapStyleWord(true);
        descriptionArea.setRows(4);
        descriptionArea.setOpaque(false);
        JScrollPane descriptionScroll = new JScrollPane(descriptionArea);
        descriptionScroll.setName("modsDescriptionScroll");
        descriptionScroll.setBorder(BorderFactory.createEmptyBorder());
        SwingTransparency.revealBackgroundThroughScrollPane(descriptionScroll);
        details.add(descriptionScroll, "growx");

        JPanel actions = new JPanel(new MigLayout(
                "insets 0, fillx",
                "[grow,fill][]8[]",
                "[40!]"));
        actions.setOpaque(false);
        enabledToggle.setName("modsEnabled");
        actions.add(enabledToggle, "growx, h 40!");
        configureIconButton(
                revealButton,
                "modsReveal",
                "assets/swing/icons/folder-open.svg",
                actionStrings.revealAction(),
                actionStrings.revealTooltip(),
                this::revealSelected);
        actions.add(revealButton, "w 40!, h 40!");
        configureIconButton(
                deleteButton,
                "modsDelete",
                "assets/swing/icons/delete.svg",
                actionStrings.deleteAction(),
                actionStrings.deleteTooltip(),
                this::deleteSelected);
        actions.add(deleteButton, "w 40!, h 40!");
        details.add(actions, "span 2, growx");

        JScrollPane scroll = new JScrollPane(details);
        scroll.setName("modsDetailsScroll");
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setMinimumSize(new Dimension(0, 0));
        SwingTransparency.revealBackgroundThroughScrollPane(scroll);
        return scroll;
    }

    /// Creates compact index and mutation status text.
    ///
    /// @return status band
    private JComponent createStatusBand() {
        JPanel statusBand = new JPanel(new MigLayout(
                "insets 4 16 12 16, fillx",
                "[grow,fill][grow,fill]",
                "[]"));
        statusBand.setOpaque(false);
        statusLabel.setName("modsStatus");
        writeStatusLabel.setName("modsWriteStatus");
        statusBand.add(statusLabel, "growx");
        statusBand.add(writeStatusLabel, "growx, alignx right");
        return statusBand;
    }

    /// Adds one read-only details label and value row.
    ///
    /// @param panel target details panel
    /// @param labelText localized label
    /// @param value reusable value label
    /// @param valueName deterministic component name
    private static void addDetailRow(
            JPanel panel,
            String labelText,
            JLabel value,
            String valueName) {
        panel.add(new JLabel(labelText));
        value.setName(valueName);
        panel.add(value, "growx");
    }

    /// Installs list listeners used for sparse loading and stable selection.
    private void configureList() {
        choiceList.getList().setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        choiceList.getList().addListSelectionListener(selectionListener);
        choiceList.getChoiceModel().addListDataListener(listDataListener);
    }

    /// Installs search, filter, and enabled-state command listeners.
    private void configureControls() {
        searchField.getDocument().addDocumentListener(searchListener);
        filterBox.addActionListener(event -> {
            if (!closed && !synchronizing) {
                @Nullable ModCatalogFilter selected = (ModCatalogFilter) filterBox.getSelectedItem();
                if (selected != null) {
                    model.setFilter(selected);
                }
            }
        });
        enabledToggle.addActionListener(event -> toggleSelected());
    }

    /// Creates search callbacks after all listener-dependent fields are initialized.
    ///
    /// @return document listener
    private DocumentListener createSearchListener() {
        return new DocumentListener() {
            /// Applies inserted query text.
            @Override
            public void insertUpdate(DocumentEvent event) {
                searchChanged();
            }

            /// Applies removed query text.
            @Override
            public void removeUpdate(DocumentEvent event) {
                searchChanged();
            }

            /// Applies attribute changes as ordinary query changes.
            @Override
            public void changedUpdate(DocumentEvent event) {
                searchChanged();
            }
        };
    }

    /// Creates sparse row callbacks that reconcile a selected loading placeholder.
    ///
    /// @return list data listener
    private ListDataListener createListDataListener() {
        return new ListDataListener() {
            /// Reconciles inserted sparse rows.
            @Override
            public void intervalAdded(ListDataEvent event) {
                loadedRowsChanged();
            }

            /// Reconciles removed sparse rows.
            @Override
            public void intervalRemoved(ListDataEvent event) {
                loadedRowsChanged();
            }

            /// Reconciles loading, error, and loaded transitions.
            @Override
            public void contentsChanged(ListDataEvent event) {
                loadedRowsChanged();
            }
        };
    }

    /// Applies current search text unless controls are synchronizing.
    private void searchChanged() {
        if (!synchronizing && !closed) {
            model.setSearchQuery(searchField.getText());
        }
    }

    /// Delegates one loaded single selection and keeps multi-selection panel-owned.
    ///
    /// @param event list selection event
    private void selectionChanged(ListSelectionEvent event) {
        if (closed || synchronizing || event.getValueIsAdjusting()) {
            return;
        }
        int selectedCount = choiceList.getList().getSelectedIndices().length;
        if (selectedCount == 0) {
            model.clearSelection();
            showDetails(null);
            updateSelectionActions();
            return;
        }
        if (selectedCount > 1) {
            model.clearSelection();
            showDetails(null);
            updateSelectionActions();
            return;
        }
        @Nullable ModCatalogItem selected = singleSelectedItem();
        if (selected != null) {
            model.selectMod(selected.localKey());
        }
        showDetails(selected);
        updateSelectionActions();
    }

    /// Rechecks whether the selected placeholder has become a loaded row.
    private void loadedRowsChanged() {
        if (closed || synchronizing) {
            return;
        }
        @Nullable ModCatalogItem selected = singleSelectedItem();
        if (selected != null) {
            model.selectMod(selected.localKey());
        }
        showDetails(selected);
        updateSelectionActions();
    }

    /// Applies one immutable model snapshot to Swing state.
    ///
    /// @param snapshot latest model snapshot
    private void applySnapshot(ModCatalogSnapshot snapshot) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            return;
        }
        synchronizing = true;
        try {
            displayedSnapshot = snapshot;
            boolean contentChanged = appliedContentRevision != snapshot.contentRevision();
            if (contentChanged) {
                appliedContentRevision = snapshot.contentRevision();
                choiceList.getList().clearSelection();
                choiceList.reloadData();
            }
            if (!searchField.getText().equals(snapshot.searchQuery())) {
                searchField.setText(snapshot.searchQuery());
            }
            filterBox.setSelectedItem(snapshot.filter());
            choiceList.getList().setEnabled(snapshot.listEnabled());
            restoreSelection(snapshot, contentChanged);
            statusLabel.setText(snapshot.statusText());
            writeStatusLabel.setText(snapshot.writeStatusText());
            boolean mutationIdle = snapshot.writeStatus() != ModCatalogWriteStatus.BUSY;
            searchField.setEnabled(mutationIdle);
            filterBox.setEnabled(mutationIdle);
            refreshButton.setEnabled(snapshot.refreshEnabled());
            importButton.setEnabled(snapshot.status() == ModCatalogStatus.READY && mutationIdle);
            openDirectoryButton.setEnabled(mutationIdle);
        } finally {
            synchronizing = false;
        }
        @Nullable ModCatalogItem selected = singleSelectedItem();
        showDetails(selected);
        updateSelectionActions();
    }

    /// Restores a model-owned single selection without collapsing a current panel-owned multi-selection.
    ///
    /// @param snapshot latest model state
    /// @param contentChanged whether logical list indexes were replaced
    private void restoreSelection(ModCatalogSnapshot snapshot, boolean contentChanged) {
        JList<?> list = choiceList.getList();
        if (snapshot.selectedIndex().isPresent()) {
            int selectedIndex = snapshot.selectedIndex().getAsInt();
            int itemCount = snapshot.itemCount().orElse(0);
            if (selectedIndex >= 0 && selectedIndex < itemCount) {
                if (!contentChanged
                        && list.getSelectedIndices().length > 1
                        && list.isSelectedIndex(selectedIndex)) {
                    return;
                }
                list.setSelectedIndex(selectedIndex);
                return;
            }
        }
        if (contentChanged || list.getSelectedIndices().length <= 1) {
            list.clearSelection();
        }
    }

    /// Renders one loaded selection or the empty-selection state.
    ///
    /// @param item selected loaded row, or `null`
    private void showDetails(@Nullable ModCatalogItem item) {
        synchronizing = true;
        try {
            detailTitle.setText(item == null ? strings.emptySelectionText() : item.displayText());
            idValue.setText(item == null ? "" : item.modId());
            versionValue.setText(item == null ? "" : item.version());
            gameVersionValue.setText(item == null ? "" : item.gameVersion());
            loaderValue.setText(item == null ? "" : item.loaderType().name());
            authorsValue.setText(item == null ? "" : item.authors());
            fileValue.setText(item == null ? "" : item.path().toString());
            descriptionArea.setText(item == null ? "" : item.description());
            descriptionArea.setCaretPosition(0);
            enabledToggle.setSelected(item != null && item.enabled());
        } finally {
            synchronizing = false;
        }
    }

    /// Updates selected-row commands from loaded selection and model state.
    private void updateSelectionActions() {
        @Nullable ModCatalogSnapshot writable = currentWritableSnapshot();
        int selectedCount = choiceList.getList().getSelectedIndices().length;
        int visibleCount = model.filteredLocalKeys().size();
        @Nullable ModCatalogItem selected = singleSelectedItem();
        boolean singleMutable = writable != null && selected != null;
        enabledToggle.setEnabled(singleMutable);
        revealButton.setEnabled(singleMutable);
        deleteButton.setEnabled(singleMutable);
        selectAllButton.setEnabled(writable != null
                && visibleCount > 0
                && selectedCount < visibleCount);
        enableSelectedButton.setEnabled(writable != null && selectedCount > 0);
        disableSelectedButton.setEnabled(writable != null && selectedCount > 0);
        deleteSelectedButton.setEnabled(writable != null && selectedCount > 0);
    }

    /// Returns the loaded row only when exactly one logical list index is selected.
    ///
    /// @return exact loaded single selection, or null for none, multiple, or a placeholder
    private @Nullable ModCatalogItem singleSelectedItem() {
        return choiceList.getList().getSelectedIndices().length == 1
                ? choiceList.getSelectedValue()
                : null;
    }

    /// Returns a stable writable snapshot shared by displayed Swing state and the model.
    ///
    /// @return writable current snapshot, or null when stale, loading, empty, or busy
    private @Nullable ModCatalogSnapshot currentWritableSnapshot() {
        ModCatalogSnapshot current = model.snapshot();
        return !closed
                && current.contentRevision() == displayedSnapshot.contentRevision()
                && current.status() == ModCatalogStatus.READY
                && current.listEnabled()
                && current.writeStatus() != ModCatalogWriteStatus.BUSY
                ? current
                : null;
    }

    /// Captures selected logical indexes as immutable rename-stable keys without loading rows.
    ///
    /// @return immutable selected keys in filtered list order, or empty for stale indexes
    private @Unmodifiable List<String> selectedLocalKeys() {
        int[] selectedIndices = choiceList.getList().getSelectedIndices();
        @Unmodifiable List<String> visibleKeys = model.filteredLocalKeys();
        List<String> selectedKeys = new ArrayList<>(selectedIndices.length);
        for (int selectedIndex : selectedIndices) {
            if (selectedIndex < 0 || selectedIndex >= visibleKeys.size()) {
                return List.of();
            }
            selectedKeys.add(visibleKeys.get(selectedIndex));
        }
        return List.copyOf(selectedKeys);
    }

    /// Revalidates a stable-key selection after a potentially modal interaction.
    ///
    /// @param expectedRevision captured content revision
    /// @param expectedKeys captured stable keys
    /// @return whether the exact writable selection remains current
    private boolean isBatchSelectionCurrent(
            long expectedRevision,
            @Unmodifiable List<String> expectedKeys) {
        @Nullable ModCatalogSnapshot current = currentWritableSnapshot();
        return current != null
                && current.contentRevision() == expectedRevision
                && selectedLocalKeys().equals(expectedKeys);
    }

    /// Selects every logical row without accessing sparse row values.
    private void selectAllMods() {
        @Nullable ModCatalogSnapshot snapshot = currentWritableSnapshot();
        if (snapshot == null) {
            return;
        }
        int itemCount = snapshot.itemCount().orElse(0);
        if (itemCount > 0) {
            choiceList.getList().setSelectionInterval(0, itemCount - 1);
        }
        updateSelectionActions();
    }

    /// Submits one enabled state for the exact selected stable-key batch.
    ///
    /// @param enabled desired enabled state
    private void setSelectedModsEnabled(boolean enabled) {
        @Nullable ModCatalogSnapshot snapshot = currentWritableSnapshot();
        if (snapshot == null) {
            return;
        }
        @Unmodifiable List<String> selectedKeys = selectedLocalKeys();
        if (!selectedKeys.isEmpty()
                && isBatchSelectionCurrent(snapshot.contentRevision(), selectedKeys)) {
            observeFailure(model.setModsEnabled(selectedKeys, enabled));
        }
    }

    /// Confirms and permanently deletes the exact selected stable-key batch.
    private void deleteSelectedMods() {
        @Nullable ModCatalogSnapshot snapshot = currentWritableSnapshot();
        if (snapshot == null) {
            return;
        }
        @Unmodifiable List<String> selectedKeys = selectedLocalKeys();
        if (selectedKeys.isEmpty()
                || !interactions.confirmDeleteSelected(this, selectedKeys.size())) {
            return;
        }
        if (isBatchSelectionCurrent(snapshot.contentRevision(), selectedKeys)) {
            observeFailure(model.deleteMods(selectedKeys));
        }
    }

    /// Opens the chooser and submits selected archives as one serialized import.
    private void chooseAndImport() {
        List<Path> sources = interactions.chooseImportFiles(this, modsDirectory);
        if (!sources.isEmpty()) {
            observeFailure(model.importMods(sources));
        }
    }

    /// Schedules creation and opening of the managed Mod directory.
    private void openDirectory() {
        observeFailure(interactions.openDirectory(modsDirectory));
    }

    /// Schedules revealing the exact selected Mod file.
    private void revealSelected() {
        @Nullable ModCatalogItem selected = singleSelectedItem();
        if (selected != null) {
            observeFailure(interactions.reveal(selected.path()));
        }
    }

    /// Confirms and submits permanent deletion of the exact selected Mod.
    private void deleteSelected() {
        @Nullable ModCatalogItem selected = singleSelectedItem();
        if (selected != null && interactions.confirmDelete(this, selected)) {
            observeFailure(model.deleteMod(selected.localKey()));
        }
    }

    /// Submits one enabled-state change from the checkbox.
    private void toggleSelected() {
        if (closed || synchronizing) {
            return;
        }
        @Nullable ModCatalogItem selected = singleSelectedItem();
        if (selected != null) {
            observeFailure(model.setModEnabled(selected.localKey(), enabledToggle.isSelected()));
        }
    }

    /// Shows asynchronous model or desktop failures exactly once while open.
    ///
    /// @param stage observed asynchronous operation
    private void observeFailure(CompletionStage<?> stage) {
        stage.whenComplete((@Nullable Object ignored, @Nullable Throwable failure) -> {
            if (failure != null) {
                EdtDispatcher.execute(() -> {
                    if (!closed) {
                        interactions.showFailure(
                                this,
                                actionStrings.errorTitle(),
                                failureDetail(failure));
                    }
                });
            }
        });
    }

    /// Returns concise detail after removing asynchronous wrapper exceptions.
    ///
    /// @param failure asynchronous failure
    /// @return original message or type name
    private static String failureDetail(Throwable failure) {
        Throwable current = failure;
        if (current instanceof CompletionException && current.getCause() != null) {
            current = Objects.requireNonNull(current.getCause());
        }
        @Nullable String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName()
                : message;
    }

    /// Configures one fixed-size bundled SVG icon command.
    ///
    /// @param button target button
    /// @param name deterministic component name
    /// @param iconPath bundled icon path
    /// @param accessibleName localized command name
    /// @param tooltip localized tooltip
    /// @param action command callback
    private static void configureIconButton(
            JButton button,
            String name,
            String iconPath,
            String accessibleName,
            String tooltip,
            Runnable action) {
        button.setName(name);
        button.setIcon(new FlatSVGIcon(iconPath, 18, 18));
        button.setToolTipText(tooltip);
        button.getAccessibleContext().setAccessibleName(accessibleName);
        button.setPreferredSize(new Dimension(40, 40));
        button.addActionListener(event -> action.run());
    }

    /// Configures one localized text command with matching accessibility text.
    ///
    /// @param button target command button
    /// @param name deterministic component name
    /// @param text localized visible and accessible text
    /// @param action command callback
    private static void configureTextButton(
            JButton button,
            String name,
            String text,
            Runnable action) {
        button.setName(Objects.requireNonNull(name, "name"));
        button.setText(Objects.requireNonNull(text, "text"));
        button.setToolTipText(text);
        button.getAccessibleContext().setAccessibleName(text);
        button.getAccessibleContext().setAccessibleDescription(text);
        Runnable checkedAction = Objects.requireNonNull(action, "action");
        button.addActionListener(event -> checkedAction.run());
    }

    /// Detaches listeners, cancels sparse loads, and closes the owned model.
    @Override
    public void close() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            return;
        }
        closed = true;
        searchField.setEnabled(false);
        filterBox.setEnabled(false);
        refreshButton.setEnabled(false);
        importButton.setEnabled(false);
        openDirectoryButton.setEnabled(false);
        enabledToggle.setEnabled(false);
        revealButton.setEnabled(false);
        deleteButton.setEnabled(false);
        selectAllButton.setEnabled(false);
        enableSelectedButton.setEnabled(false);
        disableSelectedButton.setEnabled(false);
        deleteSelectedButton.setEnabled(false);
        searchField.getDocument().removeDocumentListener(searchListener);
        choiceList.getChoiceModel().removeListDataListener(listDataListener);
        choiceList.getList().removeListSelectionListener(selectionListener);
        modelSubscription.unsubscribe();
        choiceList.close();
        model.close();
    }

    /// Localizes enabled-state enum values without changing model identity.
    @NotNullByDefault
    private static final class FilterRenderer extends DefaultListCellRenderer {
        /// Localized labels.
        private final ModCatalogStrings strings;

        /// Creates one reusable filter renderer.
        ///
        /// @param strings localized labels
        private FilterRenderer(ModCatalogStrings strings) {
            this.strings = strings;
        }

        /// Renders one filter using its localized label.
        @Override
        public Component getListCellRendererComponent(
                JList<?> list,
                @Nullable Object value,
                int index,
                boolean isSelected,
                boolean cellHasFocus) {
            Object displayValue = value instanceof ModCatalogFilter filter
                    ? strings.filterText(filter)
                    : "";
            return super.getListCellRendererComponent(
                    list, displayValue, index, isSelected, cellHasFocus);
        }
    }
}
