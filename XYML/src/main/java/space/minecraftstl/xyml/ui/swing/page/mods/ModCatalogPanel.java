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
import space.minecraftstl.xyml.game.GameRepository;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
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
import javax.swing.JTextArea;
import javax.swing.JTextField;
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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

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

    /// Viewport-driven single-choice list.
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
            String instanceId,
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
        JPanel filters = new JPanel(new MigLayout(
                "insets 0, fillx",
                "[][grow,fill]8[][140!]",
                "[32!]"));
        filters.setOpaque(false);
        JLabel searchLabel = new JLabel(strings.searchLabel());
        filters.add(searchLabel);
        searchField.setName("modsSearch");
        searchField.getAccessibleContext().setAccessibleName(strings.searchLabel());
        filters.add(searchField, "growx");
        JLabel filterLabel = new JLabel(strings.filterLabel());
        filters.add(filterLabel);
        filterBox.setName("modsFilter");
        filterBox.setRenderer(new FilterRenderer(strings));
        filterBox.getAccessibleContext().setAccessibleName(strings.filterLabel());
        filters.add(filterBox, "growx");
        listSurface.add(filters, BorderLayout.NORTH);
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
        return split;
    }

    /// Creates the unframed selected-Mod details surface.
    ///
    /// @return details panel
    private JComponent createDetailsSurface() {
        JPanel details = new JPanel(new MigLayout(
                "insets 12 16 12 12, fill, wrap 2",
                "[110!][grow,fill]",
                "[]10[][][][][][]10[grow,fill]12[]"));
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
        descriptionArea.setRows(6);
        descriptionArea.setOpaque(false);
        JScrollPane descriptionScroll = new JScrollPane(descriptionArea);
        descriptionScroll.setBorder(BorderFactory.createEmptyBorder());
        SwingTransparency.revealBackgroundThroughScrollPane(descriptionScroll);
        details.add(descriptionScroll, "grow, push");

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
        return details;
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

    /// Delegates one loaded user selection by stable key.
    ///
    /// @param event list selection event
    private void selectionChanged(ListSelectionEvent event) {
        if (closed || synchronizing || event.getValueIsAdjusting()) {
            return;
        }
        int selectedIndex = choiceList.getList().getSelectedIndex();
        if (selectedIndex < 0) {
            model.clearSelection();
            showDetails(null);
            updateSelectionActions();
            return;
        }
        @Nullable ModCatalogItem selected = choiceList.getSelectedValue();
        if (selected != null) {
            model.selectMod(selected.localKey());
            showDetails(selected);
        }
        updateSelectionActions();
    }

    /// Rechecks whether the selected placeholder has become a loaded row.
    private void loadedRowsChanged() {
        if (closed || synchronizing) {
            return;
        }
        @Nullable ModCatalogItem selected = choiceList.getSelectedValue();
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
            if (appliedContentRevision != snapshot.contentRevision()) {
                appliedContentRevision = snapshot.contentRevision();
                choiceList.reloadData();
            }
            if (!searchField.getText().equals(snapshot.searchQuery())) {
                searchField.setText(snapshot.searchQuery());
            }
            filterBox.setSelectedItem(snapshot.filter());
            choiceList.getList().setEnabled(snapshot.listEnabled());
            if (snapshot.selectedIndex().isPresent()) {
                choiceList.getList().setSelectedIndex(snapshot.selectedIndex().getAsInt());
            } else {
                choiceList.getList().clearSelection();
            }
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
        @Nullable ModCatalogItem selected = choiceList.getSelectedValue();
        showDetails(selected);
        updateSelectionActions();
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
        @Nullable ModCatalogItem selected = choiceList.getSelectedValue();
        boolean mutable = selected != null
                && displayedSnapshot.listEnabled()
                && displayedSnapshot.writeStatus() != ModCatalogWriteStatus.BUSY;
        enabledToggle.setEnabled(mutable);
        revealButton.setEnabled(mutable);
        deleteButton.setEnabled(mutable);
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
        @Nullable ModCatalogItem selected = choiceList.getSelectedValue();
        if (selected != null) {
            observeFailure(interactions.reveal(selected.path()));
        }
    }

    /// Confirms and submits permanent deletion of the exact selected Mod.
    private void deleteSelected() {
        @Nullable ModCatalogItem selected = choiceList.getSelectedValue();
        if (selected != null && interactions.confirmDelete(this, selected)) {
            observeFailure(model.deleteMod(selected.localKey()));
        }
    }

    /// Submits one enabled-state change from the checkbox.
    private void toggleSelected() {
        if (closed || synchronizing) {
            return;
        }
        @Nullable ModCatalogItem selected = choiceList.getSelectedValue();
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
