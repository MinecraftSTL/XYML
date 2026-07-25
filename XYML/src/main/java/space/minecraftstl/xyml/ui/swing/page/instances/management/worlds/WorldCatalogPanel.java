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
import space.minecraftstl.xyml.game.GameRepository;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceList;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
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

    /// Creates the production panel with the real repository adapter and desktop interactions.
    ///
    /// @param repository managed game repository
    /// @param instanceId stable managed instance identifier
    /// @param executor caller-owned background executor
    public WorldCatalogPanel(GameRepository repository, String instanceId, Executor executor) {
        this(
                new DefaultWorldCatalogModel(
                        Objects.requireNonNull(repository, "repository"),
                        Objects.requireNonNull(instanceId, "instanceId"),
                        Objects.requireNonNull(executor, "executor"),
                        WorldCatalogStrings.english()),
                WorldCatalogStrings.english(),
                new DefaultWorldCatalogInteractions(WorldCatalogStrings.english(), executor));
    }

    /// Creates a panel with injected filesystem and desktop boundaries for deterministic tests.
    ///
    /// The panel owns the supplied model and closes it after detaching every Swing listener.
    ///
    /// @param model catalog model
    /// @param strings stable page text
    /// @param interactions dialog and desktop interaction boundary
    public WorldCatalogPanel(
            WorldCatalogModel model,
            WorldCatalogStrings strings,
            WorldCatalogInteractions interactions) {
        super(new BorderLayout());
        EdtDispatcher.requireEventDispatchThread();
        this.model = Objects.requireNonNull(model, "model");
        this.strings = Objects.requireNonNull(strings, "strings");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        displayedSnapshot = this.model.snapshot();
        choiceList = new ViewportChoiceList<>(this.model, WorldCatalogItem::displayText);
        listDataListener = createListDataListener();
        selectionListener = this::selectionChanged;

        setName("worldsCatalogPage");
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
        listSurface.setBorder(BorderFactory.createEmptyBorder(8, 16, 12, 8));
        choiceList.setName("worldsChoiceList");
        choiceList.getList().setName("worldsList");
        choiceList.getList().getAccessibleContext().setAccessibleName(strings.title());
        listSurface.add(choiceList, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                listSurface,
                createDetailsSurface());
        split.setName("worldsCatalogSplit");
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setContinuousLayout(true);
        split.setResizeWeight(0.46D);
        split.setDividerLocation(0.46D);
        return split;
    }

    /// Creates selected-world metadata and icon-only row actions.
    ///
    /// @return unframed detail surface
    private JComponent createDetailsSurface() {
        JPanel details = new JPanel(new MigLayout(
                "insets 12 16 12 12, fill, wrap 2",
                "[110!][grow,fill]",
                "[]10[][][][][][]12[]"));
        details.setName("worldsDetails");
        detailTitle.setName("worldsDetailTitle");
        detailTitle.setFont(detailTitle.getFont().deriveFont(Font.BOLD, 20.0F));
        details.add(detailTitle, "span 2, growx");
        addDetailRow(details, strings.directoryNameLabel(), directoryValue, "worldsDirectory");
        addDetailRow(details, strings.pathLabel(), pathValue, "worldsPath");
        addDetailRow(details, strings.gameVersionLabel(), gameVersionValue, "worldsGameVersion");
        addDetailRow(details, strings.lastPlayedLabel(), lastPlayedValue, "worldsLastPlayed");
        addDetailRow(details, strings.lockedLabel(), lockedValue, "worldsLocked");
        addDetailRow(details, strings.readabilityLabel(), readabilityValue, "worldsMetadata");

        JPanel actions = new JPanel(new MigLayout("insets 0, gap 8", "[40!][40!]", "[40!]"));
        actions.setOpaque(false);
        configureIconButton(
                openWorldButton,
                "worldsOpenSelected",
                "assets/swing/icons/folder-open.svg",
                strings.openWorldTooltip(),
                this::openSelectedWorld);
        configureIconButton(
                deleteButton,
                "worldsDelete",
                "assets/swing/icons/delete.svg",
                strings.deleteTooltip(),
                this::deleteSelectedWorld);
        actions.add(openWorldButton, "w 40!, h 40!");
        actions.add(deleteButton, "w 40!, h 40!");
        details.add(actions, "span 2, right");
        return details;
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
            choiceList.getList().setEnabled(snapshot.listEnabled());
            statusLabel.setText(snapshot.statusText());
            operationLabel.setText(snapshot.operationText());
            refreshButton.setEnabled(snapshot.refreshEnabled());
            importButton.setEnabled(snapshot.status() == WorldCatalogStatus.READY && !snapshot.operationPending());
            openSavesButton.setEnabled(!snapshot.operationPending());
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
        directoryValue.setText(world == null ? "" : world.directoryName());
        pathValue.setText(world == null ? "" : world.path().toString());
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
                && !displayedSnapshot.operationPending();
        openWorldButton.setEnabled(usableSelection);
        deleteButton.setEnabled(usableSelection && Objects.requireNonNull(world).readable());
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
                showFailure(new IllegalStateException("World import inspection completed without a candidate"));
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
        choiceList.getList().removeListSelectionListener(selectionListener);
        choiceList.getChoiceModel().removeListDataListener(listDataListener);
        modelSubscription.unsubscribe();
        choiceList.close();
        model.close();
        refreshButton.setEnabled(false);
        importButton.setEnabled(false);
        openSavesButton.setEnabled(false);
        openWorldButton.setEnabled(false);
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
