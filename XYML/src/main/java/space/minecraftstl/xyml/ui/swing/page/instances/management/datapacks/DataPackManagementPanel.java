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
package space.minecraftstl.xyml.ui.swing.page.instances.management.datapacks;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.addon.datapack.DataPack;
import space.minecraftstl.xyml.game.GameRepository;
import space.minecraftstl.xyml.game.World;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceList;
import space.minecraftstl.xyml.ui.swing.page.instances.management.worlds.DefaultWorldCatalogModel;
import space.minecraftstl.xyml.ui.swing.page.instances.management.worlds.WorldCatalogItem;
import space.minecraftstl.xyml.ui.swing.page.instances.management.worlds.WorldCatalogModel;
import space.minecraftstl.xyml.ui.swing.page.instances.management.worlds.WorldCatalogSnapshot;
import space.minecraftstl.xyml.ui.swing.page.instances.management.worlds.WorldCatalogStrings;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Lazy Swing manager for data packs installed in worlds belonging to one game instance.
///
/// The page starts no I/O during construction. Its owner calls [#activate()] when the tab becomes
/// relevant, which begins only the shared shallow `saves` index. Core `World` and `DataPack` values
/// are created on the supplied executor only after the user selects a visible loaded world row.
@NotNullByDefault
public final class DataPackManagementPanel extends JPanel implements AutoCloseable {
    /// Background model that lazily indexes direct child world directories and owns its own work.
    private final WorldCatalogModel worlds;

    /// Caller-owned executor for Core world and data-pack filesystem operations.
    private final Executor executor;

    /// Stable visible text and dialog labels.
    private final DataPackManagementStrings strings;

    /// Native chooser, confirmation, desktop, and error-dialog boundary.
    private final DataPackManagementInteractions interactions;

    /// Snapshot-to-viewport adapter for the selected world's data packs.
    private final DataPackViewportDataSource dataPackSource = new DataPackViewportDataSource();

    /// Sparse lazy list for the instance's direct child world folders.
    private final ViewportChoiceList<WorldCatalogItem> worldChoiceList;

    /// Sparse viewport list for data packs belonging to the selected readable world.
    private final ViewportChoiceList<DataPack.Pack> dataPackChoiceList;

    /// Status emitted by the lazy world index and displayed below the world selector.
    private final JLabel worldStatusLabel = new JLabel();

    /// Status emitted while the selected world's DataPack API is loading or mutating.
    private final JLabel dataPackStatusLabel = new JLabel();

    /// Opens a fresh shallow index after clearing stale selected-world state.
    private final JButton refreshWorldsButton = new JButton();

    /// Reveals the instance-level saves directory through the desktop interaction boundary.
    private final JButton openSavesButton = new JButton();

    /// Chooses and installs one local ZIP into the selected world's data-pack directory.
    private final JButton importDataPackButton = new JButton();

    /// Reveals the selected world's data-pack directory through the desktop interaction boundary.
    private final JButton openDataPacksButton = new JButton();

    /// Permanently deletes the selected data-pack row after explicit confirmation.
    private final JButton deleteDataPackButton = new JButton();

    /// Reacts when a selected world placeholder becomes a loaded viewport row.
    private final ListDataListener worldRowsListener = new ListDataListener() {
        /// Re-evaluates the current selected world after rows are added.
        @Override
        public void intervalAdded(ListDataEvent event) {
            selectedWorldChanged();
        }

        /// Re-evaluates the current selected world after rows are removed.
        @Override
        public void intervalRemoved(ListDataEvent event) {
            selectedWorldChanged();
        }

        /// Re-evaluates the current selected world after a placeholder resolves.
        @Override
        public void contentsChanged(ListDataEvent event) {
            selectedWorldChanged();
        }
    };

    /// Reacts to user world-list selection changes without handling intermediate drags.
    private final ListSelectionListener worldSelectionListener = this::worldSelectionChanged;

    /// Re-evaluates data-pack commands when a selected placeholder resolves.
    private final ListDataListener dataPackRowsListener = new ListDataListener() {
        /// Updates commands after a data-pack row is added.
        @Override
        public void intervalAdded(ListDataEvent event) {
            updateActionState();
        }

        /// Updates commands after a data-pack row is removed.
        @Override
        public void intervalRemoved(ListDataEvent event) {
            updateActionState();
        }

        /// Updates commands after a data-pack placeholder resolves.
        @Override
        public void contentsChanged(ListDataEvent event) {
            updateActionState();
        }
    };

    /// Reacts to concrete data-pack selections without handling intermediate drags.
    private final ListSelectionListener dataPackSelectionListener = event -> {
        if (!event.getValueIsAdjusting()) {
            updateActionState();
        }
    };

    /// Subscription for world-index state changes, released together with the owned model.
    private final Subscription worldSubscription;

    /// Prevents late background callbacks from mutating released Swing controls.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Last rendered world-index state used to restore list enablement after data-pack mutations.
    private WorldCatalogSnapshot displayedWorldSnapshot;

    /// Last world-index revision whose list values have been invalidated in the Swing viewport.
    private long appliedWorldContentRevision = -1L;

    /// Selected world path currently loading or represented by [#selectedWorld].
    private @Nullable Path selectedWorldPath;

    /// Current Core world and DataPack context, or `null` before a successful selection load.
    private @Nullable SelectedWorld selectedWorld;

    /// Whether an import or deletion currently owns the selected data-pack manager.
    private boolean dataPackOperationPending;

    /// Whether this page has started its lazy world-directory index.
    private boolean activated;

    /// Creates a production data-pack manager for one managed instance.
    ///
    /// @param repository repository used to resolve the instance's effective run directory
    /// @param instanceId stable managed instance identifier
    /// @param executor caller-owned executor for Core and local filesystem work
    public DataPackManagementPanel(GameRepository repository, String instanceId, Executor executor) {
        this(
                new DefaultWorldCatalogModel(
                        Objects.requireNonNull(repository, "repository"),
                        requireNonBlank(instanceId, "instanceId"),
                        Objects.requireNonNull(executor, "executor"),
                        WorldCatalogStrings.localized()),
                DataPackManagementStrings.localized(),
                new DefaultDataPackManagementInteractions(DataPackManagementStrings.localized(), executor),
                executor);
    }

    /// Creates a data-pack manager with explicit seams for focused Swing verification.
    ///
    /// Ownership of `worlds` transfers to this panel and [#close()] closes it exactly once.
    ///
    /// @param worlds lazy world catalog for one instance
    /// @param strings stable visible text
    /// @param interactions native dialog and desktop interaction boundary
    /// @param executor caller-owned background executor for World and DataPack work
    DataPackManagementPanel(
            WorldCatalogModel worlds,
            DataPackManagementStrings strings,
            DataPackManagementInteractions interactions,
            Executor executor) {
        super(new MigLayout(
                "insets 0, fill, wrap 1",
                "[grow,fill]",
                "[]8[grow,fill]8[]"));
        EdtDispatcher.requireEventDispatchThread();
        this.worlds = Objects.requireNonNull(worlds, "worlds");
        this.strings = Objects.requireNonNull(strings, "strings");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.executor = Objects.requireNonNull(executor, "executor");
        displayedWorldSnapshot = this.worlds.snapshot();
        worldChoiceList = new ViewportChoiceList<>(this.worlds, WorldCatalogItem::displayText);
        dataPackChoiceList = new ViewportChoiceList<>(dataPackSource, this::dataPackText);

        configureComponents();
        worldChoiceList.getList().addListSelectionListener(worldSelectionListener);
        worldChoiceList.getChoiceModel().addListDataListener(worldRowsListener);
        dataPackChoiceList.getList().addListSelectionListener(dataPackSelectionListener);
        dataPackChoiceList.getChoiceModel().addListDataListener(dataPackRowsListener);
        worldSubscription = this.worlds.subscribe(change -> {
            @Nullable WorldCatalogSnapshot snapshot = change.currentValue();
            if (snapshot != null) {
                EdtDispatcher.execute(() -> applyWorldSnapshot(snapshot));
            }
        });
        applyWorldSnapshot(displayedWorldSnapshot);
    }

    /// Returns the visible tab title.
    ///
    /// @return non-blank page title
    public String title() {
        return strings.title();
    }

    /// Returns the lazy world selector for host tab integration and focused tests.
    ///
    /// @return owned sparse single-choice world list
    public ViewportChoiceList<WorldCatalogItem> worldChoiceList() {
        return worldChoiceList;
    }

    /// Returns the selected-world data-pack viewport list for host integration and focused tests.
    ///
    /// @return owned sparse single-choice data-pack list
    public ViewportChoiceList<DataPack.Pack> dataPackChoiceList() {
        return dataPackChoiceList;
    }

    /// Starts the first shallow `saves` index after the owning tab becomes relevant.
    ///
    /// Repeated calls are harmless and do not re-index a ready catalog; the existing world model
    /// enforces that lifecycle so hidden management tabs do not perform unnecessary startup work.
    public void activate() {
        EdtDispatcher.requireEventDispatchThread();
        if (!closed.get() && !activated) {
            activated = true;
            worlds.loadIfNeeded();
        }
    }

    /// Releases listeners, sparse models, and the owned lazy world catalog exactly once.
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        EdtDispatcher.executeAndWait(this::closeOnEventDispatchThread);
    }

    /// Creates the fixed toolbar, two sparse choice lists, and lifecycle status area.
    private void configureComponents() {
        setName("dataPackManagement");
        setOpaque(false);

        JPanel heading = new JPanel(new MigLayout(
                "insets 12 16 4 16, fillx",
                "[grow,fill][]8[]8[]8[]8[]",
                "[40!]"));
        heading.setOpaque(false);
        JLabel title = new JLabel(strings.title());
        title.setName("dataPackManagementTitle");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22.0F));
        heading.add(title, "growx");
        configureIconButton(
                refreshWorldsButton,
                "dataPacksRefreshWorlds",
                "assets/swing/icons/refresh.svg",
                strings.refreshTooltip(),
                this::refreshWorlds);
        configureIconButton(
                openSavesButton,
                "dataPacksOpenSaves",
                "assets/swing/icons/folder-open.svg",
                strings.openSavesTooltip(),
                this::openSavesDirectory);
        configureIconButton(
                importDataPackButton,
                "dataPacksImport",
                "assets/swing/icons/file-import.svg",
                strings.importTooltip(),
                this::chooseAndImportDataPack);
        configureIconButton(
                openDataPacksButton,
                "dataPacksOpenDirectory",
                "assets/swing/icons/folder-open.svg",
                strings.openDataPacksTooltip(),
                this::openDataPacksDirectory);
        configureIconButton(
                deleteDataPackButton,
                "dataPacksDelete",
                "assets/swing/icons/delete.svg",
                strings.deleteTooltip(),
                this::deleteSelectedDataPack);
        heading.add(refreshWorldsButton, "w 40!, h 40!");
        heading.add(openSavesButton, "w 40!, h 40!");
        heading.add(importDataPackButton, "w 40!, h 40!");
        heading.add(openDataPacksButton, "w 40!, h 40!");
        heading.add(deleteDataPackButton, "w 40!, h 40!");
        add(heading, "growx");

        worldChoiceList.setName("dataPackWorldChoiceList");
        worldChoiceList.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        worldChoiceList.getList().setName("dataPackWorldList");
        dataPackChoiceList.setName("dataPackChoiceList");
        dataPackChoiceList.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        dataPackChoiceList.getList().setName("dataPackList");

        JPanel worldsPanel = new JPanel(new BorderLayout(0, 6));
        worldsPanel.setOpaque(false);
        worldsPanel.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 4));
        worldsPanel.setMinimumSize(new Dimension(0, 0));
        worldsPanel.add(sectionHeading(strings.worldsLabel(), "dataPackWorldsLabel"), BorderLayout.NORTH);
        worldsPanel.add(worldChoiceList, BorderLayout.CENTER);

        JPanel packsPanel = new JPanel(new BorderLayout(0, 6));
        packsPanel.setOpaque(false);
        packsPanel.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 12));
        packsPanel.setMinimumSize(new Dimension(0, 0));
        packsPanel.add(sectionHeading(strings.dataPacksLabel(), "dataPackListLabel"), BorderLayout.NORTH);
        packsPanel.add(dataPackChoiceList, BorderLayout.CENTER);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, worldsPanel, packsPanel);
        splitPane.setName("dataPackManagementSplit");
        splitPane.setOpaque(false);
        splitPane.setResizeWeight(0.42D);
        splitPane.setBorder(BorderFactory.createEmptyBorder());
        splitPane.setContinuousLayout(true);
        splitPane.setMinimumSize(new Dimension(0, 0));
        add(splitPane, "grow, push");

        JPanel status = new JPanel(new MigLayout(
                "insets 4 16 12 16, fillx, wrap 1",
                "[grow,fill]",
                "[]2[]"));
        status.setOpaque(false);
        worldStatusLabel.setName("dataPackWorldStatus");
        dataPackStatusLabel.setName("dataPackStatus");
        status.add(worldStatusLabel, "growx");
        status.add(dataPackStatusLabel, "growx");
        add(status, "growx");
    }

    /// Builds one semantic section heading with a predictable compact text scale.
    ///
    /// @param text visible non-blank heading text
    /// @param name deterministic component name
    /// @return configured heading label
    private static JLabel sectionHeading(String text, String name) {
        JLabel label = new JLabel(requireNonBlank(text, "text"));
        label.setName(requireNonBlank(name, "name"));
        label.setFont(label.getFont().deriveFont(Font.BOLD, 15.0F));
        return label;
    }

    /// Formats one loaded data-pack row without resolving more metadata on the EDT.
    ///
    /// @param dataPack loaded Core data-pack value
    /// @return concise stable identifier and requested active state
    private String dataPackText(DataPack.Pack dataPack) {
        DataPack.Pack pack = Objects.requireNonNull(dataPack, "dataPack");
        return pack.getId() + " - " + (pack.isActive() ? strings.activeText() : strings.inactiveText());
    }

    /// Applies a lazy-world-model transition to the Swing controls on the EDT.
    ///
    /// @param snapshot latest immutable world-index snapshot
    private void applyWorldSnapshot(WorldCatalogSnapshot snapshot) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get()) {
            return;
        }
        displayedWorldSnapshot = Objects.requireNonNull(snapshot, "snapshot");
        if (appliedWorldContentRevision != snapshot.contentRevision()) {
            appliedWorldContentRevision = snapshot.contentRevision();
            clearSelectedWorld();
            worldChoiceList.reloadData();
        }
        worldStatusLabel.setText(snapshot.statusText());
        worldChoiceList.getList().setEnabled(snapshot.listEnabled() && !dataPackOperationPending);
        refreshWorldsButton.setEnabled(snapshot.refreshEnabled() && !dataPackOperationPending);
        openSavesButton.setEnabled(!snapshot.operationPending() && !dataPackOperationPending);
        updateActionState();
    }

    /// Handles a non-adjusting world-list selection or an asynchronously resolved selected row.
    ///
    /// @param event list-selection event
    private void worldSelectionChanged(ListSelectionEvent event) {
        if (!event.getValueIsAdjusting()) {
            selectedWorldChanged();
        }
    }

    /// Starts DataPack discovery when the selected lazy world row has materialized.
    private void selectedWorldChanged() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || dataPackOperationPending) {
            return;
        }
        @Nullable WorldCatalogItem selected = worldChoiceList.getSelectedValue();
        if (selected == null) {
            if (selectedWorldPath != null) {
                clearSelectedWorld();
            }
            return;
        }
        if (selected.path().equals(selectedWorldPath)) {
            return;
        }
        clearSelectedWorld();
        selectedWorldPath = selected.path();
        if (!selected.readable()) {
            dataPackStatusLabel.setText(strings.unreadableWorldText());
            updateActionState();
            return;
        }
        dataPackStatusLabel.setText(strings.loadingPacksText());
        updateActionState();
        try {
            executor.execute(() -> loadSelectedWorldOnExecutor(selected));
        } catch (RuntimeException failure) {
            applySelectedWorldFailure(selected.path(), failure);
        }
    }

    /// Loads one selected world and its DataPack snapshot entirely outside the EDT.
    ///
    /// @param selected loaded world row selected by the user
    private void loadSelectedWorldOnExecutor(WorldCatalogItem selected) {
        try {
            requireBackgroundThread();
            World world = new World(selected.path());
            Path dataPackDirectory = world.getFile().resolve("datapacks").toAbsolutePath().normalize();
            DataPack dataPack = new DataPack(dataPackDirectory);
            if (world.supportDataPacks() && Files.isDirectory(dataPackDirectory)) {
                dataPack.loadFromDir();
            }
            List<DataPack.Pack> packs = dataPack.getPacks();
            SelectedWorld loaded = new SelectedWorld(world, dataPack, dataPackDirectory, world.supportDataPacks());
            EdtDispatcher.execute(() -> applySelectedWorld(selected.path(), loaded, packs));
        } catch (IOException | RuntimeException failure) {
            applySelectedWorldFailure(selected.path(), failure);
        }
    }

    /// Commits one selected world's immutable DataPack snapshot to the sparse data-pack list.
    ///
    /// @param path world path that initiated the current selection request
    /// @param loaded Core world and data-pack context
    /// @param packs immutable discovered data-pack snapshot
    private void applySelectedWorld(Path path, SelectedWorld loaded, List<? extends DataPack.Pack> packs) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || !Objects.requireNonNull(path, "path").equals(selectedWorldPath)) {
            return;
        }
        selectedWorld = Objects.requireNonNull(loaded, "loaded");
        dataPackSource.replacePacks(packs);
        dataPackChoiceList.reloadData();
        dataPackStatusLabel.setText(loaded.supportsDataPacks()
                ? strings.packsReadyText(packs.size())
                : strings.unsupportedWorldText());
        updateActionState();
    }

    /// Renders a world loading failure only when it still belongs to the current selection.
    ///
    /// @param path world path that initiated the failed request
    /// @param failure original Core or filesystem failure
    private void applySelectedWorldFailure(Path path, Throwable failure) {
        EdtDispatcher.execute(() -> {
            if (closed.get() || !Objects.requireNonNull(path, "path").equals(selectedWorldPath)) {
                return;
            }
            selectedWorld = null;
            dataPackSource.replacePacks(List.of());
            dataPackChoiceList.reloadData();
            dataPackStatusLabel.setText(strings.unreadableWorldText());
            updateActionState();
            showFailure(failure);
        });
    }

    /// Clears selection-specific DataPack state before a new world or world-index revision is used.
    private void clearSelectedWorld() {
        EdtDispatcher.requireEventDispatchThread();
        selectedWorldPath = null;
        selectedWorld = null;
        dataPackOperationPending = false;
        dataPackChoiceList.getList().clearSelection();
        dataPackSource.replacePacks(List.of());
        dataPackChoiceList.reloadData();
        dataPackStatusLabel.setText(strings.selectWorldText());
    }

    /// Starts a fresh shallow world-directory index and discards stale selected-world data.
    private void refreshWorlds() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || dataPackOperationPending || !displayedWorldSnapshot.refreshEnabled()) {
            return;
        }
        clearSelectedWorld();
        worldChoiceList.getList().clearSelection();
        try {
            worlds.refresh();
        } catch (RuntimeException failure) {
            showFailure(failure);
        }
    }

    /// Opens the managed instance's saves directory without requiring a prior index result.
    private void openSavesDirectory() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || dataPackOperationPending) {
            return;
        }
        try {
            observeFailure(interactions.openDirectory(worlds.savesDirectory()));
        } catch (RuntimeException failure) {
            showFailure(failure);
        }
    }

    /// Opens a ZIP chooser and schedules true Core DataPack installation for the selected world.
    private void chooseAndImportDataPack() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable SelectedWorld context = usableSelectedWorld();
        if (context == null) {
            return;
        }
        @Nullable Path archive;
        try {
            archive = interactions.chooseDataPackArchive(this, context.dataPackDirectory());
        } catch (RuntimeException failure) {
            showFailure(failure);
            return;
        }
        if (archive == null) {
            return;
        }
        beginDataPackOperation();
        Path selectedArchive = archive;
        try {
            executor.execute(() -> installDataPackOnExecutor(context, selectedArchive));
        } catch (RuntimeException failure) {
            finishDataPackOperation(context, failure);
        }
    }

    /// Installs one selected ZIP through the Core DataPack API outside the EDT.
    ///
    /// @param context stable selected-world data-pack context
    /// @param archive selected local ZIP path
    private void installDataPackOnExecutor(SelectedWorld context, Path archive) {
        try {
            requireBackgroundThread();
            Path normalizedArchive = Objects.requireNonNull(archive, "archive").toAbsolutePath().normalize();
            if (!Files.isRegularFile(normalizedArchive)) {
                throw new IOException(i18n("swing.datapack_management.archive_missing", normalizedArchive));
            }
            Files.createDirectories(context.dataPackDirectory());
            context.dataPack().installPack(normalizedArchive, context.world().getGameVersion());
            finishDataPackOperation(context, null);
        } catch (IOException | RuntimeException failure) {
            finishDataPackOperation(context, failure);
        }
    }

    /// Reveals the selected world's data-pack directory through the platform interaction boundary.
    private void openDataPacksDirectory() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable SelectedWorld context = usableSelectedWorld();
        if (context != null) {
            try {
                observeFailure(interactions.openDirectory(context.dataPackDirectory()));
            } catch (RuntimeException failure) {
                showFailure(failure);
            }
        }
    }

    /// Confirms and schedules permanent deletion of the selected loaded DataPack row.
    private void deleteSelectedDataPack() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable SelectedWorld context = usableSelectedWorld();
        @Nullable DataPack.Pack selected = dataPackChoiceList.getSelectedValue();
        if (context == null || selected == null) {
            return;
        }
        boolean confirmed;
        try {
            confirmed = interactions.confirmDelete(this, selected);
        } catch (RuntimeException failure) {
            showFailure(failure);
            return;
        }
        if (!confirmed) {
            return;
        }
        beginDataPackOperation();
        try {
            executor.execute(() -> deleteDataPackOnExecutor(context, selected));
        } catch (RuntimeException failure) {
            finishDataPackOperation(context, failure);
        }
    }

    /// Deletes one selected pack through its owning Core DataPack manager outside the EDT.
    ///
    /// @param context stable selected-world data-pack context
    /// @param selected durable selected pack row
    private void deleteDataPackOnExecutor(SelectedWorld context, DataPack.Pack selected) {
        try {
            requireBackgroundThread();
            context.dataPack().deletePack(selected);
            finishDataPackOperation(context, null);
        } catch (IOException | RuntimeException failure) {
            finishDataPackOperation(context, failure);
        }
    }

    /// Marks an import or deletion pending and disables world/pack commands until it completes.
    private void beginDataPackOperation() {
        EdtDispatcher.requireEventDispatchThread();
        dataPackOperationPending = true;
        dataPackStatusLabel.setText(strings.loadingPacksText());
        updateActionState();
    }

    /// Commits a Core mutation result to the current sparse data-pack snapshot on the EDT.
    ///
    /// @param context selected-world context that initiated the operation
    /// @param failure operation failure, or `null` after success
    private void finishDataPackOperation(SelectedWorld context, @Nullable Throwable failure) {
        EdtDispatcher.execute(() -> {
            if (closed.get() || selectedWorld != context) {
                return;
            }
            dataPackOperationPending = false;
            if (failure == null) {
                List<DataPack.Pack> packs = context.dataPack().getPacks();
                dataPackSource.replacePacks(packs);
                dataPackChoiceList.getList().clearSelection();
                dataPackChoiceList.reloadData();
                dataPackStatusLabel.setText(strings.packsReadyText(packs.size()));
            } else {
                dataPackStatusLabel.setText(strings.packsReadyText(dataPackSource.exactItemCount().orElse(0)));
                showFailure(failure);
            }
            updateActionState();
        });
    }

    /// Returns the current world context when it can receive data-pack commands.
    ///
    /// @return usable Core world context, or `null` for no selection, unsupported worlds, or mutations
    private @Nullable SelectedWorld usableSelectedWorld() {
        if (closed.get() || dataPackOperationPending || selectedWorld == null || !selectedWorld.supportsDataPacks()) {
            return null;
        }
        return selectedWorld;
    }

    /// Applies enabled states derived from the current world index, selection, and operation ownership.
    private void updateActionState() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get()) {
            refreshWorldsButton.setEnabled(false);
            openSavesButton.setEnabled(false);
            importDataPackButton.setEnabled(false);
            openDataPacksButton.setEnabled(false);
            deleteDataPackButton.setEnabled(false);
            worldChoiceList.getList().setEnabled(false);
            dataPackChoiceList.getList().setEnabled(false);
            return;
        }
        boolean usableWorld = usableSelectedWorld() != null;
        @Nullable DataPack.Pack selectedPack = dataPackChoiceList.getSelectedValue();
        worldChoiceList.getList().setEnabled(displayedWorldSnapshot.listEnabled() && !dataPackOperationPending);
        dataPackChoiceList.getList().setEnabled(usableWorld);
        refreshWorldsButton.setEnabled(displayedWorldSnapshot.refreshEnabled() && !dataPackOperationPending);
        openSavesButton.setEnabled(!displayedWorldSnapshot.operationPending() && !dataPackOperationPending);
        importDataPackButton.setEnabled(usableWorld);
        openDataPacksButton.setEnabled(usableWorld);
        deleteDataPackButton.setEnabled(usableWorld && selectedPack != null);
    }

    /// Observes a desktop interaction and shows its failure while the panel remains open.
    ///
    /// @param stage interaction stage to observe
    private void observeFailure(CompletionStage<?> stage) {
        Objects.requireNonNull(stage, "stage").whenComplete((@Nullable Object ignored, @Nullable Throwable failure) -> {
            if (failure != null) {
                EdtDispatcher.execute(() -> {
                    if (!closed.get()) {
                        showFailure(failure);
                    }
                });
            }
        });
    }

    /// Converts an asynchronous wrapper failure into a concise native dialog message.
    ///
    /// @param failure original operation failure
    private void showFailure(Throwable failure) {
        interactions.showFailure(this, strings.failureTitle(), failureDetail(failure));
    }

    /// Produces the short user-visible message from a possible completion wrapper.
    ///
    /// @param failure original operation failure
    /// @return non-blank concise detail
    private static String failureDetail(Throwable failure) {
        Throwable current = Objects.requireNonNull(failure, "failure");
        if (current instanceof CompletionException && current.getCause() != null) {
            current = Objects.requireNonNull(current.getCause(), "completion cause");
        }
        @Nullable String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    /// Configures one fixed-size icon command with accessible text and no redundant visible label.
    ///
    /// @param button target button
    /// @param name deterministic component name
    /// @param iconPath bundled SVG icon path
    /// @param tooltip visible and assistive command text
    /// @param action EDT command callback
    private static void configureIconButton(
            JButton button,
            String name,
            String iconPath,
            String tooltip,
            Runnable action) {
        button.setName(requireNonBlank(name, "name"));
        button.setText(null);
        button.setIcon(new FlatSVGIcon(Objects.requireNonNull(iconPath, "iconPath"), 18, 18));
        button.setToolTipText(requireNonBlank(tooltip, "tooltip"));
        button.getAccessibleContext().setAccessibleName(tooltip);
        button.setPreferredSize(new Dimension(40, 40));
        button.addActionListener(event -> Objects.requireNonNull(action, "action").run());
    }

    /// Releases all listeners and owned model resources on the Swing event-dispatch thread.
    private void closeOnEventDispatchThread() {
        worldSubscription.unsubscribe();
        worldChoiceList.getList().removeListSelectionListener(worldSelectionListener);
        worldChoiceList.getChoiceModel().removeListDataListener(worldRowsListener);
        dataPackChoiceList.getList().removeListSelectionListener(dataPackSelectionListener);
        dataPackChoiceList.getChoiceModel().removeListDataListener(dataPackRowsListener);
        worldChoiceList.close();
        dataPackChoiceList.close();
        worlds.close();
        selectedWorld = null;
        selectedWorldPath = null;
        dataPackOperationPending = false;
        removeAll();
    }

    /// Rejects blank stable values supplied through public construction parameters.
    ///
    /// @param value candidate value
    /// @param name parameter name for diagnostics
    /// @return validated non-blank value
    private static String requireNonBlank(String value, String name) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checked;
    }

    /// Rejects accidental blocking Core or file-system work on Swing's event-dispatch thread.
    private static void requireBackgroundThread() {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Data-pack Core work must not run on the EDT");
        }
    }

    /// Holds one successfully reopened world and the DataPack manager for its durable directory.
    @NotNullByDefault
    private static final class SelectedWorld {
        /// Reopened Core world used to obtain version-specific installation behavior.
        private final World world;

        /// Core data-pack manager whose snapshot and mutations own the selected directory.
        private final DataPack dataPack;

        /// Normalized per-world data-pack directory.
        private final Path dataPackDirectory;

        /// Whether the parsed world version permits data-pack management.
        private final boolean supportsDataPacks;

        /// Creates one immutable selected-world operation context.
        ///
        /// @param world reopened Core world
        /// @param dataPack Core manager for the world directory
        /// @param dataPackDirectory normalized manager directory
        /// @param supportsDataPacks whether the world version supports data packs
        private SelectedWorld(
                World world,
                DataPack dataPack,
                Path dataPackDirectory,
                boolean supportsDataPacks) {
            this.world = Objects.requireNonNull(world, "world");
            this.dataPack = Objects.requireNonNull(dataPack, "dataPack");
            this.dataPackDirectory = Objects.requireNonNull(dataPackDirectory, "dataPackDirectory");
            this.supportsDataPacks = supportsDataPacks;
        }

        /// Returns the reopened Core world.
        ///
        /// @return non-null selected world
        private World world() {
            return world;
        }

        /// Returns the Core data-pack manager for the selected world.
        ///
        /// @return non-null data-pack manager
        private DataPack dataPack() {
            return dataPack;
        }

        /// Returns the normalized selected-world data-pack directory.
        ///
        /// @return non-null local directory
        private Path dataPackDirectory() {
            return dataPackDirectory;
        }

        /// Returns whether the selected world can use the data-pack API.
        ///
        /// @return whether the selected world version supports data packs
        private boolean supportsDataPacks() {
            return supportsDataPacks;
        }
    }
}
