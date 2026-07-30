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
package space.minecraftstl.xyml.ui.swing.page.downloads.loaders;

import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.download.RemoteVersion;
import space.minecraftstl.xyml.setting.DownloadProviders;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;
import space.minecraftstl.xyml.ui.swing.choice.ChoiceListEntry;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceList;

import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import java.awt.Component;
import java.awt.Font;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/// Embeddable Swing control for selecting compatible game loaders before an installation starts.
///
/// The surrounding game-version page owns the base Minecraft version and calls
/// [#selectGameVersion(String)]. Selecting a loader card is local-only. A Core VersionList refresh
/// starts only after the user explicitly presses the loader-version action, while the returned rows
/// use [ViewportChoiceList] to materialize only the current measured viewport. Added selections keep
/// their original [RemoteVersion] objects in parent-before-API installation order.
@NotNullByDefault
public final class LoaderSelectionWizardPanel extends JPanel implements AutoCloseable {
    /// Toolkit-neutral catalog model that contacts its source only after an explicit refresh command.
    private final DefaultGameLoaderCatalogModel catalogModel;

    /// Launcher-owned executor used to start one explicit catalog refresh away from the EDT.
    private final Executor workerExecutor;

    /// Localized visible text and loader display names.
    private final LoaderSelectionWizardStrings strings;

    /// Already-loaded catalog rows exposed to the viewport list without further network work.
    private final LoaderVersionViewportDataSource versionDataSource = new LoaderVersionViewportDataSource();

    /// Shared single-choice viewport list for potentially large loader version catalogs.
    private final ViewportChoiceList<GameLoaderCatalogItem> versionChoiceList;

    /// Stable kind-card controls keyed by their loader kind.
    private final Map<GameLoaderKind, JButton> kindButtons = new EnumMap<>(GameLoaderKind.class);

    /// Added loader versions keyed by kind before dependency-safe output ordering.
    private final Map<GameLoaderKind, GameLoaderCatalogItem> selectedByKind = new LinkedHashMap<>();

    /// Loader kinds already installed in an existing instance and retained outside this staged selection.
    ///
    /// Retained kinds participate in compatibility and API-parent checks, but are intentionally absent
    /// from [#selectedRemoteVersions()] because this control must submit only newly selected exact
    /// [RemoteVersion] objects to an installation task.
    private final Set<GameLoaderKind> retainedLoaderKinds = EnumSet.noneOf(GameLoaderKind.class);

    /// Small visual model of the currently selected installer components.
    private final DefaultListModel<GameLoaderCatalogItem> selectedLoaderListModel = new DefaultListModel<>();

    /// Callbacks notified after selected installation components change.
    private final List<LoaderSelectionListener> selectionListeners = new ArrayList<>();

    /// Displays the externally selected base Minecraft version.
    private final JLabel gameVersionValue = new JLabel();

    /// Displays the currently browsed loader catalog.
    private final JLabel selectedKindValue = new JLabel();

    /// Starts an explicit remote refresh for the selected loader catalog.
    private final JButton loadVersionsButton = new JButton();

    /// Adds the selected materialized loader version to the installation selection.
    private final JButton addSelectionButton = new JButton();

    /// Displays selected loader components in dependency-safe order.
    private final JList<GameLoaderCatalogItem> selectedLoaderList = new JList<>(selectedLoaderListModel);

    /// Removes the selected installer component when doing so preserves API parent requirements.
    private final JButton removeSelectionButton = new JButton();

    /// Concise current selection summary intended for the containing installation page.
    private final JLabel selectionSummaryLabel = new JLabel();

    /// User-visible progress, validation, and failure feedback.
    private final JLabel statusLabel = new JLabel();

    /// Monotonic explicit-refresh identity used to discard stale worker completions.
    private final AtomicLong refreshRevision = new AtomicLong();

    /// Selected materialized catalog row, or null while no loaded version row is selected.
    private @Nullable GameLoaderCatalogItem selectedCatalogItem;

    /// Whether the current explicit catalog refresh is in progress.
    private boolean catalogLoading;

    /// Whether close has rejected future interactions and queued worker completions.
    private volatile boolean closed;

    /// Creates a production wizard with current launcher sources, I/O scheduler, and localized text.
    ///
    /// Construction only retains the mutable launcher download provider. It does not refresh any
    /// version list or create a source task; remote work still requires an explicit button press.
    public LoaderSelectionWizardPanel() {
        this(
                new DefaultGameLoaderCatalogModel(new DownloadProviderGameLoaderCatalogSource(
                        DownloadProviders.getDownloadProvider())),
                Schedulers.io(),
                LoaderSelectionWizardStrings.launcherLocalized());
    }

    /// Creates a production wizard for composition code that prefers an explicit named factory.
    ///
    /// @return a fully local, initially offline loader-selection control
    public static LoaderSelectionWizardPanel createForLauncher() {
        return new LoaderSelectionWizardPanel();
    }

    /// Creates a wizard with model, executor, and text seams suitable for focused tests or embedding.
    ///
    /// The caller retains ownership of the model source and worker executor. This panel closes only
    /// the model and its own viewport resources.
    ///
    /// @param catalogModel selected-loader catalog model
    /// @param workerExecutor executor that starts explicit source refreshes
    /// @param strings visible wizard text
    public LoaderSelectionWizardPanel(
            DefaultGameLoaderCatalogModel catalogModel,
            Executor workerExecutor,
            LoaderSelectionWizardStrings strings) {
        super(new MigLayout(
                "insets 0, fill, wrap 1",
                "[grow,fill]",
                "[]8[]8[]8[]8[grow,fill]8[]8[]"));
        EdtDispatcher.requireEventDispatchThread();
        this.catalogModel = Objects.requireNonNull(catalogModel, "catalogModel");
        this.workerExecutor = Objects.requireNonNull(workerExecutor, "workerExecutor");
        this.strings = Objects.requireNonNull(strings, "strings");
        versionChoiceList = new ViewportChoiceList<>(versionDataSource, this::formatCatalogItem);
        configureComponents();
        setStatus(strings.awaitingGameVersionStatus());
        refreshView();
    }

    /// Returns the single-choice, viewport-driven loader-version list.
    ///
    /// Callers must access the returned Swing component on the event dispatch thread.
    ///
    /// @return the owned lazy version list
    public ViewportChoiceList<GameLoaderCatalogItem> versionChoiceList() {
        return versionChoiceList;
    }

    /// Adds a listener that observes selected installation components on the EDT.
    ///
    /// @param listener listener to retain until removed or panel closure
    public void addSelectionListener(LoaderSelectionListener listener) {
        EdtDispatcher.requireEventDispatchThread();
        selectionListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /// Removes a previously retained selection listener.
    ///
    /// @param listener listener to remove
    public void removeSelectionListener(LoaderSelectionListener listener) {
        EdtDispatcher.requireEventDispatchThread();
        selectionListeners.remove(Objects.requireNonNull(listener, "listener"));
    }

    /// Replaces the loader kinds retained by an already existing game instance.
    ///
    /// This lets an instance-management page reuse the same remote catalog without allowing a staged
    /// loader to conflict with an installed component. Supplying the same kind remains valid for an
    /// update, while an API may rely on its already-installed parent. Replacing the retained baseline
    /// clears every staged catalog and selection because those rows were validated against the prior
    /// instance state.
    ///
    /// @param loaderKinds non-null installed loader kinds to retain outside the staged task
    public void setRetainedLoaderKinds(Collection<GameLoaderKind> loaderKinds) {
        EdtDispatcher.requireEventDispatchThread();
        requireOpen();
        Collection<GameLoaderKind> nonNullLoaderKinds = Objects.requireNonNull(loaderKinds, "loaderKinds");
        retainedLoaderKinds.clear();
        for (GameLoaderKind loaderKind : nonNullLoaderKinds) {
            retainedLoaderKinds.add(Objects.requireNonNull(loaderKind, "loaderKinds contains null"));
        }
        refreshRevision.incrementAndGet();
        catalogLoading = false;
        clearCatalogRows();
        clearSelectedLoaders();
        setStatus(catalogModel.snapshot().gameVersion().isEmpty()
                ? strings.awaitingGameVersionStatus()
                : strings.awaitingLoaderStatus());
        refreshView();
        publishSelectionChanged();
    }

    /// Selects the base Minecraft version and clears all dependent catalog and install selections.
    ///
    /// @param gameVersion non-blank Minecraft version selected by the surrounding page
    public void selectGameVersion(String gameVersion) {
        EdtDispatcher.requireEventDispatchThread();
        requireOpen();
        catalogModel.selectGameVersion(gameVersion);
        refreshRevision.incrementAndGet();
        catalogLoading = false;
        clearCatalogRows();
        clearSelectedLoaders();
        setStatus(strings.awaitingLoaderStatus());
        refreshView();
        publishSelectionChanged();
    }

    /// Clears the base Minecraft version and every dependent loader selection.
    public void clearGameVersion() {
        EdtDispatcher.requireEventDispatchThread();
        requireOpen();
        catalogModel.clearGameVersion();
        refreshRevision.incrementAndGet();
        catalogLoading = false;
        clearCatalogRows();
        clearSelectedLoaders();
        setStatus(strings.awaitingGameVersionStatus());
        refreshView();
        publishSelectionChanged();
    }

    /// Returns an immutable exact Core version snapshot in dependency-safe installation order.
    ///
    /// @return immutable selected original RemoteVersion objects
    public @Unmodifiable List<RemoteVersion> selectedRemoteVersions() {
        EdtDispatcher.requireEventDispatchThread();
        return selectionSnapshot().selectedRemoteVersions();
    }

    /// Returns the current immutable integration snapshot for a containing installation page.
    ///
    /// @return selected base version, exact Core versions, and concise visible summary
    public LoaderSelectionSnapshot selectionSnapshot() {
        EdtDispatcher.requireEventDispatchThread();
        @Unmodifiable List<GameLoaderCatalogItem> selectedItems = selectedItemsInInstallOrder();
        List<RemoteVersion> remoteVersions = new ArrayList<>(selectedItems.size());
        for (GameLoaderCatalogItem item : selectedItems) {
            remoteVersions.add(item.remoteVersion());
        }
        @Unmodifiable List<RemoteVersion> immutableRemoteVersions = List.copyOf(remoteVersions);
        return new LoaderSelectionSnapshot(
                catalogModel.snapshot().gameVersion(),
                immutableRemoteVersions,
                formatSelectionSummary(selectedItems));
    }

    /// Returns the concise current loader-selection summary.
    ///
    /// @return non-null summary text
    public String selectionSummary() {
        EdtDispatcher.requireEventDispatchThread();
        return selectionSnapshot().summary();
    }

    /// Releases panel-owned listeners and rejects stale source callbacks.
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        refreshRevision.incrementAndGet();
        SwingUiDispatcher.INSTANCE.dispatchOrRun(this::closeOnEventDispatchThread);
    }

    /// Builds static Swing controls and listeners without accessing the catalog source.
    private void configureComponents() {
        setName("loaderSelectionWizard");
        setOpaque(false);

        JPanel headingBand = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill]", "[]"));
        headingBand.setOpaque(false);
        JLabel heading = new JLabel(strings.pageTitle());
        heading.setName("loaderSelectionTitle");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 28.0F));
        headingBand.add(heading, "growx");
        add(headingBand, "growx");

        JPanel gameVersionBand = new JPanel(new MigLayout("insets 0, fillx", "[][grow,fill]", "[32!]") );
        gameVersionBand.setOpaque(false);
        JLabel gameVersionLabel = new JLabel(strings.gameVersionLabel());
        gameVersionLabel.setLabelFor(gameVersionValue);
        gameVersionBand.add(gameVersionLabel);
        gameVersionValue.setName("loaderBaseGameVersion");
        gameVersionBand.add(gameVersionValue, "growx");
        add(gameVersionBand, "growx");

        JLabel kindHeading = new JLabel(strings.loaderKindsLabel());
        kindHeading.setName("loaderKindsLabel");
        add(kindHeading, "growx");

        JPanel kindGrid = new JPanel(new MigLayout(
                "insets 0, fillx, wrap 3",
                "[grow,fill][grow,fill][grow,fill]",
                "[36!]8[36!]8[36!]8[36!]") );
        kindGrid.setName("loaderKinds");
        kindGrid.setOpaque(false);
        for (GameLoaderKind kind : GameLoaderKind.values()) {
            JButton kindButton = new JButton(strings.loaderName(kind));
            kindButton.setName("loaderKind_" + kind.name());
            kindButton.addActionListener(event -> selectLoaderCatalog(kind));
            kindButtons.put(kind, kindButton);
            kindGrid.add(kindButton, "growx, h 36!");
        }
        add(kindGrid, "growx");

        JPanel catalogBand = new JPanel(new MigLayout(
                "insets 0, fillx",
                "[][grow,fill][180!]",
                "[40!]") );
        catalogBand.setOpaque(false);
        JLabel selectedKindLabel = new JLabel(strings.selectedKindLabel());
        selectedKindLabel.setLabelFor(selectedKindValue);
        catalogBand.add(selectedKindLabel);
        selectedKindValue.setName("loaderSelectedKind");
        catalogBand.add(selectedKindValue, "growx");
        loadVersionsButton.setName("loaderLoadVersions");
        loadVersionsButton.addActionListener(event -> loadSelectedLoaderVersions());
        catalogBand.add(loadVersionsButton, "growx, h 40!");
        add(catalogBand, "growx");

        JLabel versionsLabel = new JLabel(strings.versionListLabel());
        versionsLabel.setLabelFor(versionChoiceList.getList());
        add(versionsLabel, "growx");
        versionChoiceList.setName("loaderVersionList");
        versionChoiceList.setOpaque(false);
        versionChoiceList.getViewport().setOpaque(false);
        JList<ChoiceListEntry<GameLoaderCatalogItem>> versionList = versionChoiceList.getList();
        versionList.setName("loaderVersionListView");
        versionList.setOpaque(false);
        versionList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                selectedCatalogRowChanged();
            }
        });
        add(versionChoiceList, "grow, h 180:320:");

        JPanel selectionBand = new JPanel(new MigLayout(
                "insets 0, fillx",
                "[grow,fill][180!]",
                "[40!]") );
        selectionBand.setOpaque(false);
        addSelectionButton.setName("loaderAddSelection");
        addSelectionButton.setText(strings.addAction());
        addSelectionButton.addActionListener(event -> addSelectedCatalogItem());
        selectionBand.add(addSelectionButton, "growx, h 40!");
        removeSelectionButton.setName("loaderRemoveSelection");
        removeSelectionButton.setText(strings.removeAction());
        removeSelectionButton.addActionListener(event -> removeSelectedLoader());
        selectionBand.add(removeSelectionButton, "growx, h 40!");
        add(selectionBand, "growx");

        JLabel selectedLoadersLabel = new JLabel(strings.selectedLoadersLabel());
        selectedLoadersLabel.setLabelFor(selectedLoaderList);
        add(selectedLoadersLabel, "growx");
        selectedLoaderList.setName("loaderSelectedList");
        selectedLoaderList.setOpaque(false);
        selectedLoaderList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        selectedLoaderList.setCellRenderer(new SelectedLoaderRenderer(strings));
        selectedLoaderList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                refreshView();
            }
        });
        add(selectedLoaderList, "growx, h 72:120:");

        selectionSummaryLabel.setName("loaderSelectionSummary");
        add(selectionSummaryLabel, "growx, h 24!");
        statusLabel.setName("loaderSelectionStatus");
        add(statusLabel, "growx, h 24!");
    }

    /// Selects a compatible loader catalog locally without refreshing its version list.
    ///
    /// @param kind compatible loader kind selected by the user
    private void selectLoaderCatalog(GameLoaderKind kind) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || catalogLoading) {
            return;
        }
        GameLoaderCatalogSnapshot snapshot = catalogModel.snapshot();
        if (snapshot.gameVersion().isEmpty() || !snapshot.availableKinds().contains(kind)) {
            return;
        }
        if (selectedByKind.containsKey(kind)) {
            setStatus(strings.alreadySelectedStatus());
            refreshView();
            return;
        }
        if (!GameLoaderCompatibilityMatrix.hasRequiredParent(kind, effectiveLoaderKinds())) {
            setStatus(strings.parentRequiredStatus());
            refreshView();
            return;
        }
        if (!GameLoaderCompatibilityMatrix.conflictsWith(kind, effectiveLoaderKinds()).isEmpty()) {
            setStatus(strings.conflictStatus());
            refreshView();
            return;
        }
        catalogModel.selectLoaderKind(kind);
        refreshRevision.incrementAndGet();
        clearCatalogRows();
        setStatus(strings.awaitingLoaderStatus());
        refreshView();
    }

    /// Starts a remote version-list refresh only after an explicit user command.
    private void loadSelectedLoaderVersions() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || catalogLoading || catalogModel.snapshot().loaderKind().isEmpty()) {
            return;
        }
        selectedCatalogItem = null;
        versionChoiceList.getList().clearSelection();
        catalogLoading = true;
        long requestIdentity = refreshRevision.incrementAndGet();
        setStatus(strings.loadingVersionsStatus());
        refreshView();
        try {
            workerExecutor.execute(() -> beginExplicitRefresh(requestIdentity));
        } catch (RuntimeException failure) {
            completeExplicitRefresh(requestIdentity, null, failure);
        }
    }

    /// Starts one model refresh from the configured worker executor.
    ///
    /// @param requestIdentity panel request identity captured before worker submission
    private void beginExplicitRefresh(long requestIdentity) {
        final CompletionStage<GameLoaderCatalogSnapshot> completionStage;
        try {
            completionStage = catalogModel.refreshAsync();
        } catch (RuntimeException failure) {
            completeExplicitRefresh(requestIdentity, null, failure);
            return;
        }
        completionStage.whenComplete((@Nullable GameLoaderCatalogSnapshot snapshot, @Nullable Throwable failure) ->
                completeExplicitRefresh(requestIdentity, snapshot, failure));
    }

    /// Dispatches an explicit refresh completion to the EDT for current-request application.
    ///
    /// @param requestIdentity panel request identity captured before worker submission
    /// @param snapshot resulting model state, or null after an exceptional completion
    /// @param failure exceptional completion failure, or null after model completion
    private void completeExplicitRefresh(
            long requestIdentity,
            @Nullable GameLoaderCatalogSnapshot snapshot,
            @Nullable Throwable failure) {
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> applyExplicitRefresh(
                requestIdentity,
                snapshot,
                failure));
    }

    /// Applies only the current explicit refresh result and refreshes the local viewport source.
    ///
    /// @param requestIdentity panel request identity captured before worker submission
    /// @param snapshot resulting model state, or null after an exceptional completion
    /// @param failure exceptional completion failure, or null after model completion
    private void applyExplicitRefresh(
            long requestIdentity,
            @Nullable GameLoaderCatalogSnapshot snapshot,
            @Nullable Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || requestIdentity != refreshRevision.get()) {
            return;
        }
        catalogLoading = false;
        if (failure != null || snapshot == null || snapshot.status() == GameLoaderCatalogStatus.FAILED) {
            clearCatalogRows();
            setStatus(strings.loadFailedStatus());
        } else if (snapshot.status() == GameLoaderCatalogStatus.READY) {
            versionDataSource.replaceItems(snapshot.items());
            versionChoiceList.reloadData();
            setStatus(snapshot.items().isEmpty() ? strings.noVersionsStatus() : strings.selectVersionStatus());
        } else {
            clearCatalogRows();
            setStatus(strings.loadFailedStatus());
        }
        refreshView();
    }

    /// Re-evaluates the currently materialized catalog row after a list selection change.
    private void selectedCatalogRowChanged() {
        EdtDispatcher.requireEventDispatchThread();
        selectedCatalogItem = versionChoiceList.getSelectedValue();
        refreshView();
    }

    /// Adds one selected catalog row after matrix conflict and API-parent validation.
    private void addSelectedCatalogItem() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || catalogLoading) {
            return;
        }
        @Nullable GameLoaderCatalogItem candidate = selectedCatalogItem;
        if (candidate == null) {
            setStatus(strings.selectVersionStatus());
            return;
        }
        GameLoaderKind candidateKind = candidate.kind();
        if (selectedByKind.containsKey(candidateKind)) {
            setStatus(strings.alreadySelectedStatus());
            refreshView();
            return;
        }
        if (!GameLoaderCompatibilityMatrix.hasRequiredParent(candidateKind, effectiveLoaderKinds())) {
            setStatus(strings.parentRequiredStatus());
            refreshView();
            return;
        }
        if (!GameLoaderCompatibilityMatrix.conflictsWith(candidateKind, effectiveLoaderKinds()).isEmpty()) {
            setStatus(strings.conflictStatus());
            refreshView();
            return;
        }
        selectedByKind.put(candidateKind, candidate);
        synchronizeSelectedList();
        setStatus(strings.selectionAddedStatus());
        refreshView();
        publishSelectionChanged();
    }

    /// Removes one selected loader when that does not leave a selected API without its parent.
    private void removeSelectedLoader() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            return;
        }
        @Nullable GameLoaderCatalogItem selectedItem = selectedLoaderList.getSelectedValue();
        if (selectedItem == null) {
            return;
        }
        GameLoaderKind selectedKind = selectedItem.kind();
        if (hasDependentSelectedApi(selectedKind)) {
            setStatus(strings.dependentSelectionStatus());
            refreshView();
            return;
        }
        selectedByKind.remove(selectedKind);
        synchronizeSelectedList();
        setStatus(strings.selectionRemovedStatus());
        refreshView();
        publishSelectionChanged();
    }

    /// Returns whether an added API currently depends on the supplied loader kind.
    ///
    /// @param kind potential parent loader
    /// @return whether removing the supplied kind would violate an API parent prerequisite
    private boolean hasDependentSelectedApi(GameLoaderKind kind) {
        for (GameLoaderKind selectedKind : selectedByKind.keySet()) {
            Optional<GameLoaderKind> requiredParent = GameLoaderCompatibilityMatrix.requiredParent(selectedKind);
            if (requiredParent.isPresent()
                    && requiredParent.get() == kind
                    && !retainedLoaderKinds.contains(kind)) {
                return true;
            }
        }
        return false;
    }

    /// Clears catalog rows and invalidates visible sparse-list state without source work.
    private void clearCatalogRows() {
        selectedCatalogItem = null;
        versionDataSource.replaceItems(List.of());
        versionChoiceList.getList().clearSelection();
        versionChoiceList.reloadData();
    }

    /// Clears all added installation components and their compact list representation.
    private void clearSelectedLoaders() {
        selectedByKind.clear();
        selectedLoaderListModel.clear();
        selectedLoaderList.clearSelection();
    }

    /// Rebuilds the small selected-component list in dependency-safe install order.
    private void synchronizeSelectedList() {
        @Unmodifiable List<GameLoaderCatalogItem> orderedItems = selectedItemsInInstallOrder();
        selectedLoaderListModel.clear();
        for (GameLoaderCatalogItem item : orderedItems) {
            selectedLoaderListModel.addElement(item);
        }
    }

    /// Returns selected catalog items in parent-before-API installation order.
    ///
    /// @return immutable exact catalog items in safe task order
    private @Unmodifiable List<GameLoaderCatalogItem> selectedItemsInInstallOrder() {
        List<GameLoaderCatalogItem> orderedItems = new ArrayList<>(selectedByKind.size());
        Set<GameLoaderKind> emittedKinds = EnumSet.noneOf(GameLoaderKind.class);
        for (GameLoaderCatalogItem item : selectedByKind.values()) {
            appendItemWithParent(item, emittedKinds, orderedItems);
        }
        return List.copyOf(orderedItems);
    }

    /// Appends one item after its selected required parent, if any.
    ///
    /// @param item selected catalog item to append
    /// @param emittedKinds kinds already placed in task order
    /// @param orderedItems mutable task-order destination
    private void appendItemWithParent(
            GameLoaderCatalogItem item,
            Set<GameLoaderKind> emittedKinds,
            List<GameLoaderCatalogItem> orderedItems) {
        GameLoaderKind kind = item.kind();
        if (emittedKinds.contains(kind)) {
            return;
        }
        Optional<GameLoaderKind> requiredParent = GameLoaderCompatibilityMatrix.requiredParent(kind);
        if (requiredParent.isPresent()) {
            @Nullable GameLoaderCatalogItem parentItem = selectedByKind.get(requiredParent.get());
            if (parentItem != null) {
                appendItemWithParent(parentItem, emittedKinds, orderedItems);
            } else if (!retainedLoaderKinds.contains(requiredParent.get())) {
                throw new IllegalStateException("Selected API does not retain its required parent");
            }
        }
        emittedKinds.add(kind);
        orderedItems.add(item);
    }

    /// Synchronizes enabled controls and all model-derived labels after an EDT state change.
    private void refreshView() {
        EdtDispatcher.requireEventDispatchThread();
        GameLoaderCatalogSnapshot snapshot = catalogModel.snapshot();
        Optional<String> gameVersion = snapshot.gameVersion();
        boolean hasGameVersion = gameVersion.isPresent();
        gameVersionValue.setText(gameVersion.orElse("-"));

        for (Map.Entry<GameLoaderKind, JButton> entry : kindButtons.entrySet()) {
            boolean available = hasGameVersion && snapshot.availableKinds().contains(entry.getKey());
            entry.getValue().setVisible(available);
            entry.getValue().setEnabled(available
                    && !catalogLoading
                    && !closed
                    && canSelectLoaderCatalog(entry.getKey()));
        }

        Optional<GameLoaderKind> selectedKind = snapshot.loaderKind();
        selectedKindValue.setText(selectedKind.map(strings::loaderName).orElse("-"));
        loadVersionsButton.setText(catalogLoading
                ? strings.loadingVersionsAction()
                : strings.loadVersionsAction());
        loadVersionsButton.setEnabled(!closed
                && !catalogLoading
                && selectedKind.isPresent()
                && canSelectLoaderCatalog(selectedKind.orElseThrow()));

        boolean versionSelectionEnabled = !closed
                && !catalogLoading
                && snapshot.status() == GameLoaderCatalogStatus.READY;
        versionChoiceList.setEnabled(versionSelectionEnabled);
        versionChoiceList.getList().setEnabled(versionSelectionEnabled);

        @Nullable GameLoaderCatalogItem candidate = selectedCatalogItem;
        addSelectionButton.setEnabled(!closed && !catalogLoading && candidate != null && canAdd(candidate));
        @Nullable GameLoaderCatalogItem selectedItem = selectedLoaderList.getSelectedValue();
        removeSelectionButton.setEnabled(!closed
                && selectedItem != null
                && !hasDependentSelectedApi(selectedItem.kind()));
        selectionSummaryLabel.setText(selectionSnapshot().summary());
        revalidate();
        repaint();
    }

    /// Returns whether one selected catalog item can join the current installer selection.
    ///
    /// @param candidate materialized catalog item to validate
    /// @return whether the item is unique, compatible, and parent-complete
    private boolean canAdd(GameLoaderCatalogItem candidate) {
        GameLoaderKind kind = candidate.kind();
        return !selectedByKind.containsKey(kind)
                && GameLoaderCompatibilityMatrix.hasRequiredParent(kind, effectiveLoaderKinds())
                && GameLoaderCompatibilityMatrix.conflictsWith(kind, effectiveLoaderKinds()).isEmpty();
    }

    /// Returns whether a loader catalog may be entered without violating the current install selection.
    ///
    /// The same predicate drives card enablement and the defensive command handler, so a user cannot
    /// browse or refresh a version list that would be impossible to add because it is duplicate,
    /// conflicts with a selected component, or lacks an API parent prerequisite.
    ///
    /// @param kind compatible loader kind to validate
    /// @return whether the catalog is eligible for a user selection
    private boolean canSelectLoaderCatalog(GameLoaderKind kind) {
        GameLoaderKind nonNullKind = Objects.requireNonNull(kind, "kind");
        return !selectedByKind.containsKey(nonNullKind)
                && GameLoaderCompatibilityMatrix.hasRequiredParent(nonNullKind, effectiveLoaderKinds())
                && GameLoaderCompatibilityMatrix.conflictsWith(nonNullKind, effectiveLoaderKinds()).isEmpty();
    }

    /// Returns every installed and staged loader kind used for compatibility checks.
    ///
    /// A staged row of the same kind as a retained component is an update, not a duplicate conflict;
    /// the historical matrix has no self-conflicts, so this union safely authorizes that operation.
    ///
    /// @return mutable local compatibility set containing no null kind
    private Set<GameLoaderKind> effectiveLoaderKinds() {
        Set<GameLoaderKind> kinds = EnumSet.noneOf(GameLoaderKind.class);
        kinds.addAll(retainedLoaderKinds);
        kinds.addAll(selectedByKind.keySet());
        return kinds;
    }

    /// Formats one visible catalog row without replacing its original RemoteVersion object.
    ///
    /// @param item exact selected-loader catalog item
    /// @return concise localized loader and self-version text
    private String formatCatalogItem(GameLoaderCatalogItem item) {
        GameLoaderCatalogItem nonNullItem = Objects.requireNonNull(item, "item");
        return strings.loaderName(nonNullItem.kind()) + " " + nonNullItem.remoteVersion().getSelfVersion();
    }

    /// Formats selected exact catalog rows into a concise visible summary.
    ///
    /// @param selectedItems exact selected rows in task order
    /// @return concise non-null summary
    private String formatSelectionSummary(List<GameLoaderCatalogItem> selectedItems) {
        if (selectedItems.isEmpty()) {
            return strings.emptySelectionSummary();
        }
        StringJoiner joiner = new StringJoiner(", ");
        for (GameLoaderCatalogItem item : selectedItems) {
            joiner.add(formatCatalogItem(item));
        }
        return joiner.toString();
    }

    /// Updates the user-visible status label.
    ///
    /// @param status non-null status text
    private void setStatus(String status) {
        statusLabel.setText(Objects.requireNonNull(status, "status"));
    }

    /// Publishes the current immutable selection to a stable listener snapshot.
    private void publishSelectionChanged() {
        LoaderSelectionSnapshot snapshot = selectionSnapshot();
        for (LoaderSelectionListener listener : List.copyOf(selectionListeners)) {
            listener.selectionChanged(snapshot);
        }
    }

    /// Closes model and viewport resources on the event dispatch thread.
    private void closeOnEventDispatchThread() {
        EdtDispatcher.requireEventDispatchThread();
        versionChoiceList.close();
        catalogModel.close();
        selectionListeners.clear();
        for (JButton button : kindButtons.values()) {
            button.setEnabled(false);
        }
        loadVersionsButton.setEnabled(false);
        addSelectionButton.setEnabled(false);
        removeSelectionButton.setEnabled(false);
    }

    /// Rejects public mutable operations after close.
    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Loader-selection wizard is closed");
        }
    }

    /// Renders selected installer components with their localized kind and exact self version.
    @NotNullByDefault
    private static final class SelectedLoaderRenderer extends DefaultListCellRenderer {
        /// Text bundle used to resolve localized loader names.
        private final LoaderSelectionWizardStrings strings;

        /// Creates a renderer with the panel's immutable text bundle.
        ///
        /// @param strings localized loader display text
        private SelectedLoaderRenderer(LoaderSelectionWizardStrings strings) {
            this.strings = Objects.requireNonNull(strings, "strings");
        }

        /// Renders one selected exact catalog item.
        ///
        /// @param list selected-loader JList
        /// @param value selected catalog item, or null for a transient Swing renderer state
        /// @param index row index
        /// @param isSelected whether the row is selected
        /// @param cellHasFocus whether the row has keyboard focus
        /// @return configured reusable renderer component
        @Override
        public Component getListCellRendererComponent(
                JList<?> list,
                @Nullable Object value,
                int index,
                boolean isSelected,
                boolean cellHasFocus) {
            @Nullable GameLoaderCatalogItem item = value instanceof GameLoaderCatalogItem catalogItem
                    ? catalogItem
                    : null;
            String text = item == null
                    ? ""
                    : strings.loaderName(item.kind()) + " " + item.remoteVersion().getSelfVersion();
            Component component = super.getListCellRendererComponent(
                    list,
                    text,
                    index,
                    isSelected,
                    cellHasFocus);
            setOpaque(isSelected);
            return component;
        }
    }
}
