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
package space.minecraftstl.xyml.ui.swing.page.instances.management.worlds;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameRepository;
import space.minecraftstl.xyml.game.launch.LaunchSession;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingTransparency;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceList;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Lazy Swing management page for one instance's single-player worlds.
///
/// Construction only creates Swing controls and subscribes to an idle model. The owner must call
/// `activate` when its Worlds tab becomes selected; that single transition begins the shallow
/// directory index. The embedded `ViewportChoiceList` then loads NBT metadata only around the
/// actual visible rows and keeps its adaptive bounded cache independent of this panel.
@NotNullByDefault
public final class WorldCatalogPanel extends JPanel implements AutoCloseable {
    /// Pure background model owned and closed by this page.
    private final WorldCatalogModel model;

    /// Stable page, status, and interaction text.
    private final WorldCatalogStrings strings;

    /// Native dialog and desktop boundary.
    private final WorldCatalogInteractions interactions;

    /// Existing launch-service callbacks bound to this managed instance, or an unavailable boundary.
    private final WorldQuickPlayActions quickPlayActions;

    /// Viewport-driven sparse list backed by the shallow source index.
    private final ViewportChoiceList<WorldCatalogItem> choiceList;

    /// Refreshes only the shallow directory source.
    private final JButton refreshButton = new JButton();

    /// Starts ZIP archive selection and Core preflight.
    private final JButton importButton = new JButton();

    /// Opens the managed saves directory.
    private final JButton openSavesButton = new JButton();

    /// Opens the exact selected world directory.
    private final JButton openWorldButton = new JButton();

    /// Launches the managed instance and enters the exact selected world.
    private final JButton quickPlayButton = new JButton();

    /// Generates a standalone script that enters the exact selected world.
    private final JButton launchScriptButton = new JButton();

    /// Copies the exact selected readable and unlocked world.
    private final JButton copyButton = new JButton();

    /// Exports the exact selected readable and unlocked world to ZIP.
    private final JButton exportButton = new JButton();

    /// Permanently deletes the exact selected readable world after confirmation.
    private final JButton deleteButton = new JButton();

    /// Current shallow-index status.
    private final JLabel statusLabel = new JLabel();

    /// Current or failed import/delete status.
    private final JLabel operationLabel = new JLabel();

    /// Selected world primary label.
    private final JLabel detailTitle = new JLabel();

    /// Selected directory name.
    private final JLabel directoryValue = new JLabel();

    /// Selected complete path.
    private final JLabel pathValue = new JLabel();

    /// Selected recorded game version.
    private final JLabel gameVersionValue = new JLabel();

    /// Selected last-played timestamp.
    private final JLabel lastPlayedValue = new JLabel();

    /// Selected session-lock state.
    private final JLabel lockedValue = new JLabel();

    /// Selected Core metadata readability state.
    private final JLabel readabilityValue = new JLabel();

    /// Listener that reapplies selected details after sparse rows finish loading.
    private final ListDataListener listDataListener;

    /// Listener that changes details when user selection changes.
    private final ListSelectionListener selectionListener;

    /// Owned model subscription released on close.
    private final Subscription modelSubscription;

    /// Guards requested activation so construction never starts a scan.
    private final AtomicBoolean activated = new AtomicBoolean();

    /// Guards terminal component cleanup and late asynchronous failures.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Last snapshot applied to the component tree.
    private WorldCatalogSnapshot displayedSnapshot;

    /// Source revision already handed to the sparse list.
    private long appliedContentRevision = -1L;

    /// Prevents programmatic reloads from interpreting transient selection events as user input.
    private boolean synchronizing;

    /// Local quick-play or launch-script status, or null while neither command owns the page.
    private @Nullable String quickPlayOperationText;

    /// Monotonic identity that prevents stale command completions from mutating a later operation.
    private long quickPlayOperationRevision;

    /// Creates the production panel with real repository, desktop, and quick-play boundaries.
    ///
    /// @param repository managed game repository
    /// @param instanceId stable managed instance identifier
    /// @param executor caller-owned background executor
    /// @param quickPlayActions non-blocking launcher callbacks already bound to this instance
    public WorldCatalogPanel(
            GameRepository repository,
            GameInstanceID instanceId,
            Executor executor,
            WorldQuickPlayActions quickPlayActions) {
        this(
                new DefaultWorldCatalogModel(
                        Objects.requireNonNull(repository, "repository"),
                        Objects.requireNonNull(instanceId, "instanceId"),
                        Objects.requireNonNull(executor, "executor"),
                        WorldCatalogStrings.localized()),
                WorldCatalogStrings.localized(),
                new DefaultWorldCatalogInteractions(WorldCatalogStrings.localized(), executor),
                quickPlayActions);
    }

    /// Creates a panel with injected catalog, interaction, and quick-play boundaries for deterministic tests.
    ///
    /// The panel owns the supplied model and closes it after detaching every Swing listener.
    ///
    /// @param model catalog model
    /// @param strings stable page text
    /// @param interactions dialog and desktop interaction boundary
    /// @param quickPlayActions non-blocking launcher callbacks already bound to this instance
    public WorldCatalogPanel(
            WorldCatalogModel model,
            WorldCatalogStrings strings,
            WorldCatalogInteractions interactions,
            WorldQuickPlayActions quickPlayActions) {
        super(new BorderLayout());
        EdtDispatcher.requireEventDispatchThread();
        this.model = Objects.requireNonNull(model, "model");
        this.strings = Objects.requireNonNull(strings, "strings");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.quickPlayActions = Objects.requireNonNull(quickPlayActions, "quickPlayActions");
        displayedSnapshot = this.model.snapshot();
        choiceList = new ViewportChoiceList<>(this.model, WorldCatalogItem::displayText);
        listDataListener = createListDataListener();
        selectionListener = this::selectionChanged;

        setName("worldsCatalogPage");
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder());
        add(createHeadingBand(), BorderLayout.NORTH);
        add(createCatalogSplit(), BorderLayout.CENTER);
        add(createStatusBand(), BorderLayout.SOUTH);
        configureList();
        showDetails(null);
        modelSubscription = this.model.subscribe(change -> {
            @Nullable WorldCatalogSnapshot snapshot = change.currentValue();
            if (snapshot != null) {
                EdtDispatcher.execute(() -> applySnapshot(snapshot));
            }
        });
        applySnapshot(displayedSnapshot);
    }

    /// Returns the visible tab title.
    ///
    /// @return non-blank page title
    public String title() {
        return strings.title();
    }

    /// Returns the sparse viewport list for host integration and focused UI tests.
    ///
    /// @return owned viewport-driven list
    public ViewportChoiceList<WorldCatalogItem> choiceList() {
        return choiceList;
    }

    /// Returns the latest snapshot rendered by this panel.
    ///
    /// @return immutable rendered catalog snapshot
    public WorldCatalogSnapshot displayedSnapshot() {
        EdtDispatcher.requireEventDispatchThread();
        return displayedSnapshot;
    }

    /// Starts the first shallow directory scan after the host selects this exact tab.
    ///
    /// Calling this repeatedly is harmless; the model itself suppresses redundant idle loads.
    public void activate() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get()) {
            return;
        }
        if (activated.compareAndSet(false, true)) {
            model.loadIfNeeded();
        }
    }

    /// Releases the sparse list, model subscription, and owned model exactly once.
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        EdtDispatcher.executeAndWait(this::closeOnEventDispatchThread);
    }

    /// Creates the title and global fixed-size icon commands.
    ///
    /// @return heading band
    private JComponent createHeadingBand() {
        JPanel heading = new JPanel(new MigLayout(
                "insets 12 16 8 16, fillx",
                "[grow,fill][]8[]8[]",
                "[40!]"));
        heading.setOpaque(false);
        JLabel title = new JLabel(strings.title());
        title.setName("worldsPageTitle");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 26.0F));
        heading.add(title, "growx");
        configureIconButton(
                refreshButton,
                "worldsRefresh",
                "assets/swing/icons/refresh.svg",
                strings.refreshTooltip(),
                this::refresh);
        heading.add(refreshButton, "w 40!, h 40!");
        configureIconButton(
                importButton,
                "worldsImport",
                "assets/swing/icons/file-import.svg",
                strings.importTooltip(),
                this::chooseAndImport);
        heading.add(importButton, "w 40!, h 40!");
        configureIconButton(
                openSavesButton,
                "worldsOpenSaves",
                "assets/swing/icons/folder-open.svg",
                strings.openSavesTooltip(),
                this::openSavesDirectory);
        heading.add(openSavesButton, "w 40!, h 40!");
        return heading;
    }

    /// Creates a stable split between the viewport list and the selected-world details.
    ///
    /// @return unframed catalog split component
    private JComponent createCatalogSplit() {
        JPanel listSurface = new JPanel(new BorderLayout());
        listSurface.setOpaque(false);
        listSurface.setBorder(BorderFactory.createEmptyBorder(8, 16, 12, 8));
        listSurface.setMinimumSize(new Dimension(0, 0));
        choiceList.setName("worldsChoiceList");
        choiceList.setOpaque(false);
        choiceList.getViewport().setOpaque(false);
        choiceList.getList().setName("worldsList");
        choiceList.getList().setOpaque(false);
        choiceList.getList().getAccessibleContext().setAccessibleName(strings.title());
        listSurface.add(choiceList, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                listSurface,
                createDetailsSurface());
        split.setName("worldsCatalogSplit");
        split.setOpaque(false);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setContinuousLayout(true);
        split.setResizeWeight(0.46D);
        split.setDividerLocation(0.46D);
        split.setMinimumSize(new Dimension(0, 0));
        return split;
    }

    /// Creates selected-world metadata and icon-only row actions.
    ///
    /// @return transparent vertically scrollable detail surface
    private JComponent createDetailsSurface() {
        JPanel details = new JPanel(new MigLayout(
                "insets 8 16 8 12, fillx, wrap 2",
                "[110!][grow,fill]",
                "[]8[][][][][][]8[]"));
        details.setName("worldsDetails");
        details.setOpaque(false);
        detailTitle.setName("worldsDetailTitle");
        detailTitle.setFont(detailTitle.getFont().deriveFont(Font.BOLD, 20.0F));
        details.add(detailTitle, "span 2, growx");
        addDetailRow(details, strings.directoryNameLabel(), directoryValue, "worldsDirectory");
        addDetailRow(details, strings.pathLabel(), pathValue, "worldsPath");
        addDetailRow(details, strings.gameVersionLabel(), gameVersionValue, "worldsGameVersion");
        addDetailRow(details, strings.lastPlayedLabel(), lastPlayedValue, "worldsLastPlayed");
        addDetailRow(details, strings.lockedLabel(), lockedValue, "worldsLocked");
        addDetailRow(details, strings.readabilityLabel(), readabilityValue, "worldsMetadata");

        JPanel actions = new JPanel(new MigLayout(
                "insets 0, gap 8",
                "[40!][40!][40!][40!][40!][40!]",
                "[40!]"));
        actions.setOpaque(false);
        configureIconButton(
                quickPlayButton,
                "worldsQuickPlay",
                "assets/swing/icons/rocket-launch.svg",
                strings.quickPlayTooltip(),
                this::launchSelectedWorld);
        configureIconButton(
                launchScriptButton,
                "worldsLaunchScript",
                "assets/swing/icons/script.svg",
                strings.launchScriptTooltip(),
                this::generateSelectedWorldLaunchScript);
        configureIconButton(
                openWorldButton,
                "worldsOpenSelected",
                "assets/swing/icons/folder-open.svg",
                strings.openWorldTooltip(),
                this::openSelectedWorld);
        configureIconButton(
                copyButton,
                "worldsCopy",
                "assets/swing/icons/content-copy.svg",
                strings.copyTooltip(),
                this::copySelectedWorld);
        configureIconButton(
                exportButton,
                "worldsExport",
                "assets/swing/icons/output.svg",
                strings.exportTooltip(),
                this::exportSelectedWorld);
        configureIconButton(
                deleteButton,
                "worldsDelete",
                "assets/swing/icons/delete.svg",
                strings.deleteTooltip(),
                this::deleteSelectedWorld);
        actions.add(quickPlayButton, "w 40!, h 40!");
        actions.add(launchScriptButton, "w 40!, h 40!");
        actions.add(openWorldButton, "w 40!, h 40!");
        actions.add(copyButton, "w 40!, h 40!");
        actions.add(exportButton, "w 40!, h 40!");
        actions.add(deleteButton, "w 40!, h 40!");
        details.add(actions, "span 2, right");

        JScrollPane scroll = new JScrollPane(details);
        scroll.setName("worldsDetailsScroll");
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setMinimumSize(new Dimension(0, 0));
        SwingTransparency.revealBackgroundThroughScrollPane(scroll);
        return scroll;
    }

    /// Creates the compact index and mutation status footer.
    ///
    /// @return status band
    private JComponent createStatusBand() {
        JPanel status = new JPanel(new MigLayout(
                "insets 4 16 12 16, fillx",
                "[grow,fill][grow,fill]",
                "[]"));
        status.setOpaque(false);
        statusLabel.setName("worldsStatus");
        operationLabel.setName("worldsOperationStatus");
        status.add(statusLabel, "growx");
        status.add(operationLabel, "growx, alignx right");
        return status;
    }

    /// Adds one label/value row with a deterministic value component name.
    ///
    /// @param panel target details surface
    /// @param labelText non-blank static label
    /// @param value reusable value label
    /// @param name deterministic component name
    private static void addDetailRow(JPanel panel, String labelText, JLabel value, String name) {
        panel.add(new JLabel(Objects.requireNonNull(labelText, "labelText")));
        value.setName(Objects.requireNonNull(name, "name"));
        panel.add(value, "growx");
    }

    /// Configures sparse-row selection and loaded-range listeners.
    private void configureList() {
        choiceList.getList().addListSelectionListener(selectionListener);
        choiceList.getChoiceModel().addListDataListener(listDataListener);
    }

    /// Creates the listener that updates details after a placeholder becomes a loaded world row.
    ///
    /// @return sparse list-data listener
    private ListDataListener createListDataListener() {
        return new ListDataListener() {
            /// Reconciles added loaded rows.
            @Override
            public void intervalAdded(ListDataEvent event) {
                selectedRowChanged();
            }

            /// Reconciles invalidated rows.
            @Override
            public void intervalRemoved(ListDataEvent event) {
                selectedRowChanged();
            }

            /// Reconciles replaced loaded, loading, or error rows.
            @Override
            public void contentsChanged(ListDataEvent event) {
                selectedRowChanged();
            }
        };
    }

    /// Reacts to a completed non-adjusting user selection change.
    ///
    /// @param event list-selection event
    private void selectionChanged(ListSelectionEvent event) {
        if (!event.getValueIsAdjusting()) {
            selectedRowChanged();
        }
    }

    /// Renders the currently loaded selection and updates selection-specific commands.
    private void selectedRowChanged() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || synchronizing) {
            return;
        }
        @Nullable WorldCatalogItem selected = choiceList.getSelectedValue();
        showDetails(selected);
        updateSelectionActions(selected);
    }

    /// Applies a committed background snapshot to Swing controls.
    ///
    /// @param snapshot latest immutable model state
    private void applySnapshot(WorldCatalogSnapshot snapshot) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get()) {
            return;
        }
        synchronizing = true;
        try {
            displayedSnapshot = Objects.requireNonNull(snapshot, "snapshot");
            if (appliedContentRevision != snapshot.contentRevision()) {
                appliedContentRevision = snapshot.contentRevision();
                choiceList.reloadData();
            }
            boolean commandIdle = quickPlayOperationText == null;
            choiceList.getList().setEnabled(snapshot.listEnabled() && commandIdle);
            statusLabel.setText(snapshot.statusText());
            operationLabel.setText(commandIdle
                    ? snapshot.operationText()
                    : Objects.requireNonNull(quickPlayOperationText));
            refreshButton.setEnabled(snapshot.refreshEnabled() && commandIdle);
            importButton.setEnabled(
                    snapshot.status() == WorldCatalogStatus.READY
                            && !snapshot.operationPending()
                            && commandIdle);
            openSavesButton.setEnabled(!snapshot.operationPending() && commandIdle);
        } finally {
            synchronizing = false;
        }
        @Nullable WorldCatalogItem selected = choiceList.getSelectedValue();
        showDetails(selected);
        updateSelectionActions(selected);
    }

    /// Renders a loaded row or the no-selection state without touching model state.
    ///
    /// @param world selected loaded row, or null for no loaded selection
    private void showDetails(@Nullable WorldCatalogItem world) {
        detailTitle.setText(world == null ? strings.emptySelectionText() : world.displayText());
        directoryValue.setText(world == null ? strings.unavailableValue() : world.directoryName());
        pathValue.setText(world == null ? strings.unavailableValue() : world.path().toString());
        gameVersionValue.setText(world == null || world.gameVersion() == null
                ? strings.unavailableValue()
                : Objects.requireNonNull(world.gameVersion()));
        lastPlayedValue.setText(world == null ? strings.unavailableValue() : formatLastPlayed(world.lastPlayed()));
        lockedValue.setText(world == null || !world.readable()
                ? strings.unavailableValue()
                : world.locked() ? strings.lockedValue() : strings.unlockedValue());
        readabilityValue.setText(world == null
                ? strings.unavailableValue()
                : world.readable() ? strings.readableValue() : strings.unreadableValue());
        readabilityValue.setToolTipText(world == null ? null : world.failureDetail());
    }

    /// Updates selected-row actions from the loaded selection and latest snapshot.
    ///
    /// @param world loaded selected row, or null for no selection or a placeholder
    private void updateSelectionActions(@Nullable WorldCatalogItem world) {
        boolean usableSelection = world != null
                && displayedSnapshot.listEnabled()
                && !displayedSnapshot.operationPending()
                && quickPlayOperationText == null;
        openWorldButton.setEnabled(usableSelection);
        boolean mutableSelection = usableSelection
                && Objects.requireNonNull(world).readable()
                && !world.locked();
        boolean launchableSelection = mutableSelection && quickPlayActions.available();
        quickPlayButton.setEnabled(launchableSelection);
        launchScriptButton.setEnabled(launchableSelection);
        copyButton.setEnabled(mutableSelection);
        exportButton.setEnabled(mutableSelection);
        deleteButton.setEnabled(mutableSelection);
    }

    /// Starts an explicit fresh shallow directory index.
    private void refresh() {
        if (!closed.get()) {
            model.refresh();
        }
    }

    /// Opens the ZIP chooser, performs Core preflight off the EDT, then asks for the destination name.
    private void chooseAndImport() {
        if (closed.get()) {
            return;
        }
        @Nullable Path archive;
        try {
            archive = interactions.chooseWorldArchive(this, model.savesDirectory());
        } catch (RuntimeException failure) {
            showFailure(failure);
            return;
        }
        if (archive == null) {
            return;
        }
        model.inspectImport(archive).whenComplete((candidate, failure) -> EdtDispatcher.execute(() -> {
            if (closed.get()) {
                return;
            }
            if (failure != null) {
                showFailure(failure);
                return;
            }
            if (candidate == null) {
                showFailure(new IllegalStateException(i18n("swing.world_catalog.import_missing_candidate")));
                return;
            }
            @Nullable String targetName = interactions.chooseWorldName(this, candidate);
            if (targetName != null) {
                observeFailure(model.installWorld(candidate, targetName));
            }
        }));
    }

    /// Schedules opening the current saves directory without triggering an index.
    private void openSavesDirectory() {
        try {
            observeFailure(interactions.openDirectory(model.savesDirectory()));
        } catch (RuntimeException failure) {
            showFailure(failure);
        }
    }

    /// Schedules opening the exact selected world directory.
    private void openSelectedWorld() {
        @Nullable WorldCatalogItem selected = choiceList.getSelectedValue();
        if (selected != null) {
            observeFailure(interactions.openDirectory(selected.path()));
        }
    }

    /// Starts ordinary launch preparation with the selected world folder captured in its immutable request.
    private void launchSelectedWorld() {
        @Nullable WorldCatalogItem selected = launchableSelection();
        if (selected == null) {
            return;
        }
        long revision = beginQuickPlayOperation(strings.launchingText());
        final LaunchSession session;
        try {
            session = quickPlayActions.launch(selected);
        } catch (RuntimeException | Error failure) {
            completeQuickPlayLaunch(revision, failure);
            return;
        }
        try {
            Objects.requireNonNull(
                            session.completion(),
                            i18n("swing.world_catalog.launch_missing_completion"))
                    .whenComplete((process, failure) -> EdtDispatcher.execute(
                            () -> completeQuickPlayLaunch(revision, failure)));
        } catch (RuntimeException | Error failure) {
            completeQuickPlayLaunch(revision, failure);
        }
    }

    /// Chooses a local destination and starts quick-play script generation through the existing launch chain.
    private void generateSelectedWorldLaunchScript() {
        @Nullable WorldCatalogItem selected = launchableSelection();
        if (selected == null) {
            return;
        }
        final @Nullable Path destination;
        try {
            destination = interactions.chooseLaunchScriptDestination(this, selected);
        } catch (RuntimeException | Error failure) {
            showFailure(failure);
            return;
        }
        if (destination == null) {
            return;
        }
        long revision = beginQuickPlayOperation(strings.generatingLaunchScriptText());
        final CompletionStage<Path> completion;
        try {
            completion = quickPlayActions.exportLaunchScript(selected, destination);
        } catch (RuntimeException | Error failure) {
            completeLaunchScript(revision, null, failure);
            return;
        }
        try {
            completion.whenComplete((scriptFile, failure) -> EdtDispatcher.execute(
                    () -> completeLaunchScript(revision, scriptFile, failure)));
        } catch (RuntimeException | Error failure) {
            completeLaunchScript(revision, null, failure);
        }
    }

    /// Returns the exact selected row only while quick play can safely start.
    ///
    /// @return launchable selected row, or null while unavailable or busy
    private @Nullable WorldCatalogItem launchableSelection() {
        @Nullable WorldCatalogItem selected = choiceList.getSelectedValue();
        if (closed.get()
                || quickPlayOperationText != null
                || !quickPlayActions.available()
                || selected == null
                || !selected.readable()
                || selected.locked()) {
            return null;
        }
        return selected;
    }

    /// Claims the page-local quick-play slot and refreshes command availability.
    ///
    /// @param statusText non-blank local operation status
    /// @return unique operation revision
    private long beginQuickPlayOperation(String statusText) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || quickPlayOperationText != null) {
            throw new IllegalStateException("A world quick-play operation is already active or closed");
        }
        String checkedStatus = Objects.requireNonNull(statusText, "statusText");
        if (checkedStatus.isBlank()) {
            throw new IllegalArgumentException("statusText must not be blank");
        }
        quickPlayOperationText = checkedStatus;
        long revision = ++quickPlayOperationRevision;
        applySnapshot(displayedSnapshot);
        return revision;
    }

    /// Releases one matching process-preparation slot and reports a terminal failure.
    ///
    /// @param revision operation identity
    /// @param failure terminal failure, or null after process creation
    private void completeQuickPlayLaunch(long revision, @Nullable Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        if (!finishQuickPlayOperation(revision)) {
            return;
        }
        if (failure != null) {
            showFailure(failure);
        }
    }

    /// Releases one matching script-generation slot and reports its exact terminal result.
    ///
    /// @param revision operation identity
    /// @param scriptFile generated script path, or null after failure
    /// @param failure terminal failure, or null after success
    private void completeLaunchScript(
            long revision,
            @Nullable Path scriptFile,
            @Nullable Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        if (!finishQuickPlayOperation(revision)) {
            return;
        }
        if (failure != null) {
            showFailure(failure);
        } else if (scriptFile == null) {
            showFailure(new IllegalStateException(i18n("swing.world_catalog.launch_script_missing_path")));
        } else {
            interactions.launchScriptSucceeded(this, scriptFile);
        }
    }

    /// Clears one current page-local operation without accepting stale completion callbacks.
    ///
    /// @param revision operation identity
    /// @return whether this completion owned the current slot
    private boolean finishQuickPlayOperation(long revision) {
        if (closed.get() || quickPlayOperationText == null || revision != quickPlayOperationRevision) {
            return false;
        }
        quickPlayOperationText = null;
        applySnapshot(displayedSnapshot);
        return true;
    }

    /// Prompts for a sibling name and delegates one lock-aware world copy.
    private void copySelectedWorld() {
        @Nullable WorldCatalogItem selected = choiceList.getSelectedValue();
        if (selected == null || !selected.readable() || selected.locked()) {
            return;
        }
        @Nullable String targetName;
        try {
            targetName = interactions.chooseCopyName(this, selected);
        } catch (RuntimeException failure) {
            showFailure(failure);
            return;
        }
        if (targetName != null) {
            observeFailure(model.copyWorld(selected, targetName));
        }
    }

    /// Chooses a ZIP destination and delegates one lock-aware atomic world export.
    private void exportSelectedWorld() {
        @Nullable WorldCatalogItem selected = choiceList.getSelectedValue();
        if (selected == null || !selected.readable() || selected.locked()) {
            return;
        }
        @Nullable Path archive;
        try {
            archive = interactions.chooseExportArchive(this, selected);
        } catch (RuntimeException failure) {
            showFailure(failure);
            return;
        }
        if (archive != null) {
            observeFailure(model.exportWorld(selected, archive));
        }
    }

    /// Confirms and delegates permanent deletion for one readable selected row.
    private void deleteSelectedWorld() {
        @Nullable WorldCatalogItem selected = choiceList.getSelectedValue();
        if (selected != null && selected.readable() && interactions.confirmDelete(this, selected)) {
            observeFailure(model.deleteWorld(selected));
        }
    }

    /// Shows asynchronous desktop and model failures once on the EDT while the panel remains open.
    ///
    /// @param stage observed stage
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

    /// Displays concise detail for one failure on the EDT.
    ///
    /// @param failure original synchronous or asynchronous failure
    private void showFailure(Throwable failure) {
        interactions.showFailure(this, strings.failureTitle(), failureDetail(failure));
    }

    /// Formats an epoch timestamp for the current desktop locale, with a stable fallback.
    ///
    /// @param lastPlayed epoch milliseconds from `level.dat`
    /// @return localized timestamp or the unavailable placeholder
    private String formatLastPlayed(long lastPlayed) {
        if (lastPlayed <= 0L) {
            return strings.unavailableValue();
        }
        try {
            return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(lastPlayed));
        } catch (DateTimeException failure) {
            return Long.toString(lastPlayed);
        }
    }

    /// Configures one bundled icon command with a fixed layout footprint and tooltip.
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
        button.setName(Objects.requireNonNull(name, "name"));
        button.setText(null);
        button.setIcon(new FlatSVGIcon(Objects.requireNonNull(iconPath, "iconPath"), 18, 18));
        button.setToolTipText(Objects.requireNonNull(tooltip, "tooltip"));
        button.getAccessibleContext().setAccessibleName(tooltip);
        button.setPreferredSize(new Dimension(40, 40));
        button.addActionListener(event -> Objects.requireNonNull(action, "action").run());
    }

    /// Detaches all UI listeners and closes owned resources on the EDT.
    private void closeOnEventDispatchThread() {
        quickPlayOperationRevision++;
        quickPlayOperationText = null;
        choiceList.getList().removeListSelectionListener(selectionListener);
        choiceList.getChoiceModel().removeListDataListener(listDataListener);
        modelSubscription.unsubscribe();
        choiceList.close();
        model.close();
        refreshButton.setEnabled(false);
        importButton.setEnabled(false);
        openSavesButton.setEnabled(false);
        openWorldButton.setEnabled(false);
        quickPlayButton.setEnabled(false);
        launchScriptButton.setEnabled(false);
        copyButton.setEnabled(false);
        exportButton.setEnabled(false);
        deleteButton.setEnabled(false);
        removeAll();
    }

    /// Removes asynchronous wrapper exceptions and returns concise failure detail.
    ///
    /// @param failure original terminal failure
    /// @return exception message or simple type name
    private static String failureDetail(Throwable failure) {
        Throwable current = Objects.requireNonNull(failure, "failure");
        if (current instanceof CompletionException && current.getCause() != null) {
            current = Objects.requireNonNull(current.getCause());
        }
        @Nullable String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName()
                : message;
    }
}
