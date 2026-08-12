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
package space.minecraftstl.xyml.ui.swing.page.instances.management.addonupdates;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.addon.RemoteAddon;
import space.minecraftstl.xyml.addon.RemoteAddonRepository;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameRepository;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.setting.DownloadProviders;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskExecutor;
import space.minecraftstl.xyml.task.TaskListener;
import space.minecraftstl.xyml.task.presentation.TaskExecutorPresentationModel;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;
import space.minecraftstl.xyml.ui.swing.page.downloads.RemoteAddonChangelogDialog;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressHostPanel;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.Font;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Lazy Swing page for checking installed Mods and resource packs for real compatible updates.
///
/// Construction only creates viewport-rendered table controls. Local file discovery and every
/// remote metadata request start exclusively after the user invokes `checkForUpdates`. Completed
/// rows retain their exact Core updates so an explicit checked selection can run as one presented task.
@NotNullByDefault
public final class AddonUpdatesPanel extends JPanel implements AutoCloseable {
    /// Collision-resistant timestamp used for the default exported CSV name.
    private static final DateTimeFormatter EXPORT_FILE_TIME = DateTimeFormatter.ofPattern(
            "yyyy-MM-dd'T'HH-mm-ss",
            Locale.ROOT);

    /// Blocking access to local add-ons and remote source metadata.
    private final AddonUpdateScanAccess scanAccess;

    /// Creates one stopped Core task for the exact checked update items.
    private final AddonUpdateApplicationService applicationService;

    /// Native browser, file-explorer, and dialog boundary.
    private final AddonUpdatesInteractions interactions;

    /// Caller-owned executor for blocking scan work.
    private final Executor executor;

    /// Stable visible labels.
    private final AddonUpdatesStrings strings;

    /// Viewport-backed table model retaining only completed lightweight result rows.
    private final AddonUpdateTableModel tableModel;

    /// Table that delegates painting to its scroll-pane viewport.
    private final JTable resultsTable;

    /// Explicit command that starts local and remote inspection.
    private final JButton checkButton = new JButton();

    /// Exports every actionable result row to a user-selected CSV file.
    private final JButton exportButton = new JButton();

    /// Opens the selected row's exact remote project page when resolved.
    private final JButton openSourceButton = new JButton();

    /// Loads and displays the selected row's exact remote version changelog.
    private final JButton changelogButton = new JButton();

    /// Opens the selected row's containing local directory.
    private final JButton revealLocalButton = new JButton();

    /// Selects or clears every actionable update row without affecting failure rows.
    private final JCheckBox selectAllCheckBox = new JCheckBox();

    /// Starts one task for the currently checked exact update rows.
    private final JButton updateButton = new JButton();

    /// Displays idle, scanning, empty, and partial-failure state.
    private final JLabel statusLabel = new JLabel();

    /// Owns the one current update-task presentation.
    private final TaskProgressHostPanel progressHost;

    /// Owned table selection listener detached during closure.
    private final ListSelectionListener selectionListener;

    /// Guards one in-flight network scan and prevents concurrent duplicate calls.
    private final AtomicBoolean scanning = new AtomicBoolean();

    /// Guards one in-flight CSV write and prevents duplicate export commands.
    private final AtomicBoolean exporting = new AtomicBoolean();

    /// Guards one in-flight changelog request and prevents duplicate dialog loads.
    private final AtomicBoolean changelogLoading = new AtomicBoolean();

    /// Guards terminal component teardown and late background callbacks.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Monotonic scan identifier used to ignore stale background results.
    private long scanRevision;

    /// Latest fully applied result, or `null` before the first successful check.
    private @Nullable AddonUpdateScanResult displayedResult;

    /// Terminal update summary to combine with the automatic post-update scan result.
    private @Nullable String pendingCompletionStatus;

    /// Executor currently applying checked updates, or `null` while no update task is active.
    private @Nullable TaskExecutor activeExecutor;

    /// Presentation bound to the progress host until the next task or panel closure.
    private @Nullable TaskExecutorPresentationModel activePresentation;

    /// Terminal-listener registration owned by the active update task.
    private @Nullable Subscription activeCompletionSubscription;

    /// Creates a production panel for one managed instance without starting a check.
    ///
    /// @param repository repository containing the selected instance
    /// @param instanceId stable non-blank selected instance identifier
    /// @param executor caller-owned background executor
    public AddonUpdatesPanel(GameRepository repository, GameInstanceID instanceId, Executor executor) {
        this(
                repository,
                instanceId,
                executor,
                TaskProgressStrings.localized(),
                null,
                Duration.ZERO);
    }

    /// Creates a production panel with the host's shared task-progress presentation settings.
    ///
    /// @param repository repository containing the selected instance
    /// @param instanceId stable non-blank selected instance identifier
    /// @param executor caller-owned background executor
    /// @param taskProgressStrings localized task-progress labels
    /// @param animator optional shared motion-aware progress animator
    /// @param progressAnimationDuration non-negative determinate progress animation duration
    public AddonUpdatesPanel(
            GameRepository repository,
            GameInstanceID instanceId,
            Executor executor,
            TaskProgressStrings taskProgressStrings,
            @Nullable SwingAnimator animator,
            Duration progressAnimationDuration) {
        this(
                new RepositoryAddonUpdateScanAccess(
                        Objects.requireNonNull(repository, "repository"),
                        Objects.requireNonNull(instanceId, "instanceId"),
                        DownloadProviders.getDownloadProvider()),
                new RepositoryAddonUpdateApplicationService(DownloadProviders.getDownloadProvider()),
                Objects.requireNonNull(executor, "executor"),
                AddonUpdatesStrings.localized(),
                new DefaultAddonUpdatesInteractions(executor),
                taskProgressStrings,
                animator,
                progressAnimationDuration);
    }

    /// Creates a deterministic page with explicit local, remote, and native interaction boundaries.
    ///
    /// This constructor is package-visible for focused headless tests. The panel never shuts down
    /// the caller-owned scan executor. Closing cancels its active update task and suppresses late callbacks.
    ///
    /// @param scanAccess blocking scan boundary
    /// @param applicationService exact checked-update task boundary
    /// @param executor caller-owned background executor
    /// @param strings stable visible labels
    /// @param interactions native desktop boundary
    /// @param taskProgressStrings localized task-progress labels
    /// @param animator optional shared motion-aware progress animator
    /// @param progressAnimationDuration non-negative determinate progress animation duration
    AddonUpdatesPanel(
            AddonUpdateScanAccess scanAccess,
            AddonUpdateApplicationService applicationService,
            Executor executor,
            AddonUpdatesStrings strings,
            AddonUpdatesInteractions interactions,
            TaskProgressStrings taskProgressStrings,
            @Nullable SwingAnimator animator,
            Duration progressAnimationDuration) {
        super(new BorderLayout());
        EdtDispatcher.requireEventDispatchThread();
        this.scanAccess = Objects.requireNonNull(scanAccess, "scanAccess");
        this.applicationService = Objects.requireNonNull(applicationService, "applicationService");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.strings = Objects.requireNonNull(strings, "strings");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        progressHost = new TaskProgressHostPanel(
                Objects.requireNonNull(taskProgressStrings, "taskProgressStrings"),
                animator,
                Objects.requireNonNull(progressAnimationDuration, "progressAnimationDuration"));
        if (progressAnimationDuration.isNegative()) {
            throw new IllegalArgumentException("progressAnimationDuration must not be negative");
        }
        tableModel = new AddonUpdateTableModel(this.strings, this::updateControls);
        resultsTable = new JTable(tableModel);
        selectionListener = this::selectionChanged;

        setName("addonUpdatesPage");
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder());
        add(createHeadingBand(), BorderLayout.NORTH);
        add(createTableSurface(), BorderLayout.CENTER);
        add(createStatusBand(), BorderLayout.SOUTH);
        configureTable();
        updateControls();
    }

    /// Returns the immutable result currently rendered by the page.
    ///
    /// @return latest result, or `null` before a successful explicit scan
    public @Nullable AddonUpdateScanResult displayedResult() {
        EdtDispatcher.requireEventDispatchThread();
        return displayedResult;
    }

    /// Returns the viewport-rendered result table for host integration and focused UI tests.
    ///
    /// @return owned installed-add-on result table
    public JTable resultsTable() {
        return resultsTable;
    }

    /// Returns the localized tab title used by an instance-management host.
    ///
    /// @return non-blank page title
    public String title() {
        return strings.title();
    }

    /// Starts one explicit background scan when no check is already running.
    ///
    /// This is the only page entry point that can contact a network add-on source.
    public void checkForUpdates() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || activeExecutor != null || !scanning.compareAndSet(false, true)) {
            return;
        }
        long requestRevision = ++scanRevision;
        statusLabel.setText(strings.checkingText());
        updateControls();
        try {
            executor.execute(() -> scanOnExecutor(requestRevision));
        } catch (RuntimeException failure) {
            scanFailed(requestRevision, failure);
        }
    }

    /// Releases selection listeners and prevents all late background results from updating Swing controls.
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        EdtDispatcher.executeAndWait(this::closeOnEventDispatchThread);
    }

    /// Creates heading text, the single explicit check command, and selected-row icon controls.
    ///
    /// @return unframed page heading
    private JComponent createHeadingBand() {
        JPanel heading = new JPanel(new MigLayout(
                "insets 12 16 8 16, fillx",
                "[grow,fill][]8[]8[]8[]8[]",
                "[40!]"));
        heading.setOpaque(false);
        JLabel title = new JLabel(strings.title());
        title.setName("addonUpdatesTitle");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 26.0F));
        heading.add(title, "growx");

        configureIconButton(
                exportButton,
                "addonUpdatesExport",
                "assets/swing/icons/output.svg",
                i18n("button.export"),
                this::exportUpdateList);
        heading.add(exportButton, "w 40!, h 40!");

        checkButton.setName("addonUpdatesCheck");
        checkButton.setText(strings.checkButtonText());
        checkButton.setIcon(new FlatSVGIcon("assets/swing/icons/refresh.svg", 18, 18));
        checkButton.setToolTipText(strings.checkButtonText());
        checkButton.addActionListener(event -> checkForUpdates());
        heading.add(checkButton, "h 40!");

        configureIconButton(
                openSourceButton,
                "addonUpdatesOpenSource",
                "assets/swing/icons/open-in-new.svg",
                strings.sourceTooltip(),
                this::openSelectedSourcePage);
        heading.add(openSourceButton, "w 40!, h 40!");

        configureIconButton(
                changelogButton,
                "addonUpdatesChangelog",
                "assets/swing/icons/script.svg",
                i18n("update.changelog"),
                this::showSelectedChangelog);
        heading.add(changelogButton, "w 40!, h 40!");

        configureIconButton(
                revealLocalButton,
                "addonUpdatesRevealLocal",
                "assets/swing/icons/folder-open.svg",
                strings.localFileTooltip(),
                this::revealSelectedLocalFile);
        heading.add(revealLocalButton, "w 40!, h 40!");
        return heading;
    }

    /// Creates a table within a Swing viewport so off-screen rows are not rendered eagerly.
    ///
    /// @return viewport-backed table surface
    private JComponent createTableSurface() {
        JPanel surface = new JPanel(new BorderLayout());
        surface.setOpaque(false);
        surface.setBorder(BorderFactory.createEmptyBorder(4, 16, 8, 16));
        JScrollPane scrollPane = new JScrollPane(resultsTable);
        scrollPane.setName("addonUpdatesScrollPane");
        surface.add(scrollPane, BorderLayout.CENTER);
        return surface;
    }

    /// Creates a compact scan state, checked-update command, and task-progress footer.
    ///
    /// @return status band
    private JComponent createStatusBand() {
        JPanel status = new JPanel(new MigLayout(
                "insets 4 16 12 16, fillx",
                "[grow,fill][]12[]",
                "[]6[]"));
        status.setOpaque(false);
        statusLabel.setName("addonUpdatesStatus");
        status.add(statusLabel, "growx");

        selectAllCheckBox.setName("addonUpdatesSelectAll");
        selectAllCheckBox.setText(strings.selectAllText());
        selectAllCheckBox.addActionListener(event -> {
            tableModel.setAllUpdatesSelected(selectAllCheckBox.isSelected());
            updateControls();
        });
        status.add(selectAllCheckBox);

        updateButton.setName("addonUpdatesApply");
        updateButton.setText(strings.updateButtonText());
        updateButton.setIcon(new FlatSVGIcon("assets/swing/icons/nav-downloads.svg", 18, 18));
        updateButton.addActionListener(event -> applySelectedUpdates());
        status.add(updateButton, "h 36!");

        status.add(progressHost, "newline, span 3, growx");
        return status;
    }

    /// Configures table selection, column sizing, and no-full-row repaint behavior.
    private void configureTable() {
        resultsTable.setName("addonUpdatesTable");
        resultsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultsTable.setAutoCreateRowSorter(false);
        resultsTable.setFillsViewportHeight(true);
        resultsTable.getSelectionModel().addListSelectionListener(selectionListener);
        configureColumn(0, 56);
        resultsTable.getColumnModel().getColumn(0).setMaxWidth(72);
        configureColumn(1, 220);
        configureColumn(2, 150);
        configureColumn(3, 150);
        configureColumn(4, 120);
        configureColumn(5, 320);
    }

    /// Applies a stable preferred width to one data column.
    ///
    /// @param modelIndex table model column index
    /// @param preferredWidth desired logical pixel width
    private void configureColumn(int modelIndex, int preferredWidth) {
        TableColumn column = resultsTable.getColumnModel().getColumn(modelIndex);
        column.setPreferredWidth(preferredWidth);
    }

    /// Configures a fixed-size familiar-symbol icon command.
    ///
    /// @param button target button
    /// @param name deterministic component name
    /// @param iconPath classpath icon path
    /// @param tooltip accessible hover text
    /// @param action command action
    private static void configureIconButton(
            JButton button,
            String name,
            String iconPath,
            String tooltip,
            Runnable action) {
        button.setName(Objects.requireNonNull(name, "name"));
        button.setIcon(new FlatSVGIcon(Objects.requireNonNull(iconPath, "iconPath"), 20, 20));
        button.setToolTipText(Objects.requireNonNull(tooltip, "tooltip"));
        button.getAccessibleContext().setAccessibleName(tooltip);
        button.getAccessibleContext().setAccessibleDescription(tooltip);
        button.setHorizontalAlignment(SwingConstants.CENTER);
        button.addActionListener(event -> Objects.requireNonNull(action, "action").run());
    }

    /// Runs local discovery and source access outside the EDT.
    ///
    /// @param requestRevision scan sequence to apply if still current
    private void scanOnExecutor(long requestRevision) {
        try {
            AddonUpdateScanResult result = scanAccess.scan();
            EdtDispatcher.execute(() -> scanCompleted(requestRevision, result));
        } catch (IOException | RuntimeException failure) {
            EdtDispatcher.execute(() -> scanFailed(requestRevision, failure));
        }
    }

    /// Applies a successful explicit scan on the EDT.
    ///
    /// @param requestRevision completed scan sequence
    /// @param result immutable completed scan result
    private void scanCompleted(long requestRevision, AddonUpdateScanResult result) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || requestRevision != scanRevision) {
            return;
        }
        displayedResult = Objects.requireNonNull(result, "result");
        tableModel.replaceRows(rowsFor(displayedResult));
        statusLabel.setText(consumePendingCompletionStatus(statusFor(displayedResult)));
        scanning.set(false);
        updateControls();
    }

    /// Applies a whole-scan failure without replacing previously completed rows.
    ///
    /// @param requestRevision completed scan sequence
    /// @param failure local discovery or infrastructure failure
    private void scanFailed(long requestRevision, Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || requestRevision != scanRevision) {
            return;
        }
        statusLabel.setText(consumePendingCompletionStatus(
                strings.failedText() + " " + describeFailure(failure)));
        scanning.set(false);
        updateControls();
    }

    /// Combines a completed update summary with its automatic rescan result exactly once.
    ///
    /// @param scanStatus current scan result or failure status
    /// @return scan status with a preceding update summary when one is pending
    private String consumePendingCompletionStatus(String scanStatus) {
        String currentScanStatus = Objects.requireNonNull(scanStatus, "scanStatus");
        @Nullable String completionStatus = pendingCompletionStatus;
        pendingCompletionStatus = null;
        return completionStatus == null ? currentScanStatus : completionStatus + " - " + currentScanStatus;
    }

    /// Converts one immutable scan outcome to compact table rows.
    ///
    /// @param result completed result
    /// @return lightweight rows for viewport rendering
    private List<AddonUpdateTableRow> rowsFor(AddonUpdateScanResult result) {
        List<AddonUpdateTableRow> rows = new ArrayList<>(
                result.updates().size() + result.failures().size());
        for (AddonUpdateItem update : result.updates()) {
            rows.add(AddonUpdateTableRow.forUpdate(update, sourceName(update.source())));
        }
        for (AddonUpdateCheckFailure failure : result.failures()) {
            rows.add(AddonUpdateTableRow.forFailure(failure, strings.failedText()));
        }
        return List.copyOf(rows);
    }

    /// Produces the compact aggregate status after a complete scan.
    ///
    /// @param result completed scan result
    /// @return localized user-visible summary
    private String statusFor(AddonUpdateScanResult result) {
        if (result.updates().isEmpty() && result.failures().isEmpty()) {
            return strings.emptyText();
        }
        String status = strings.title() + ": " + result.updates().size() + "/" + result.installedCount();
        return result.failures().isEmpty()
                ? status
                : status + " - " + strings.failedText() + " (" + result.failures().size() + ")";
    }

    /// Maps a remote source to its pre-existing localized display label.
    ///
    /// @param source remote service source
    /// @return stable source display text
    private static String sourceName(RemoteAddon.Source source) {
        return switch (Objects.requireNonNull(source, "source")) {
            case CURSEFORGE -> "CurseForge";
            case MODRINTH -> "Modrinth";
        };
    }

    /// Reacts to a final table selection change.
    ///
    /// @param event table-selection event
    private void selectionChanged(ListSelectionEvent event) {
        if (!event.getValueIsAdjusting()) {
            updateControls();
        }
    }

    /// Synchronizes scanning, checked-update, selected-row, and task commands without querying a source.
    private void updateControls() {
        EdtDispatcher.requireEventDispatchThread();
        boolean idle = !closed.get() && !scanning.get() && !exporting.get() && activeExecutor == null;
        @Nullable AddonUpdateTableRow row = selectedRow();
        checkButton.setEnabled(idle);
        exportButton.setEnabled(idle && tableModel.updateCount() > 0);
        resultsTable.setEnabled(idle);
        revealLocalButton.setEnabled(idle && row != null);
        openSourceButton.setEnabled(idle && row != null && row.sourcePage() != null);
        changelogButton.setEnabled(idle && row != null && row.update() != null);
        selectAllCheckBox.setEnabled(idle && tableModel.updateCount() > 0);
        selectAllCheckBox.setSelected(tableModel.areAllUpdatesSelected());
        updateButton.setEnabled(idle && !tableModel.selectedUpdates().isEmpty());
    }

    /// Exports every actionable result row to one explicitly chosen new CSV file.
    private void exportUpdateList() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || scanning.get() || exporting.get() || activeExecutor != null) {
            return;
        }
        @Unmodifiable List<AddonUpdateExportRow> rows = tableModel.exportRows();
        if (rows.isEmpty()) {
            updateControls();
            return;
        }
        String suggestedName = "xyml-addon-update-list-"
                + EXPORT_FILE_TIME.format(LocalDateTime.now())
                + ".csv";
        final @Nullable Path destination;
        try {
            destination = interactions.chooseExportFile(this, suggestedName);
        } catch (RuntimeException failure) {
            interactions.showFailure(this, strings.failureDialogTitle(), describeFailure(failure));
            return;
        }
        if (destination == null || !exporting.compareAndSet(false, true)) {
            return;
        }
        statusLabel.setText(i18n("button.export"));
        updateControls();
        final CompletionStage<@Nullable Void> completion;
        try {
            completion = Objects.requireNonNull(
                    interactions.exportUpdateList(destination, rows),
                    "interactions.exportUpdateList returned null");
        } catch (RuntimeException failure) {
            exporting.set(false);
            interactions.showFailure(this, strings.failureDialogTitle(), describeFailure(failure));
            updateControls();
            return;
        }
        completion.whenComplete((
                @Nullable Void ignored,
                @Nullable Throwable failure) -> EdtDispatcher.execute(() -> {
            if (closed.get()) {
                return;
            }
            exporting.set(false);
            if (failure == null) {
                statusLabel.setText(i18n("message.success") + ": " + destination);
            } else {
                interactions.showFailure(this, strings.failureDialogTitle(), describeFailure(failure));
            }
            updateControls();
        }));
    }

    /// Creates and starts one presented task for exactly the currently checked update items.
    private void applySelectedUpdates() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || scanning.get() || activeExecutor != null) {
            return;
        }
        @Unmodifiable List<AddonUpdateItem> selectedUpdates = tableModel.selectedUpdates();
        if (selectedUpdates.isEmpty()) {
            updateControls();
            return;
        }
        releaseCompletedPresentation();

        final Task<AddonUpdateApplicationResult> task;
        try {
            task = Objects.requireNonNull(
                    applicationService.applyUpdates(selectedUpdates),
                    "applicationService.applyUpdates returned null task");
        } catch (RuntimeException preparationFailure) {
            presentTaskFailure(preparationFailure);
            updateControls();
            return;
        }

        TaskExecutor taskExecutor = task.executor();
        TaskExecutorPresentationModel presentation = new TaskExecutorPresentationModel(
                taskExecutor,
                strings.title(),
                strings.updatingText());
        Subscription completionSubscription = taskExecutor.subscribeTaskListener(
                new ApplicationCompletionListener(taskExecutor, task));
        activeExecutor = taskExecutor;
        activePresentation = presentation;
        activeCompletionSubscription = completionSubscription;
        statusLabel.setText(strings.updatingText());
        updateControls();
        try {
            progressHost.bind(presentation);
            taskExecutor.start();
        } catch (RuntimeException | Error startFailure) {
            cleanupFailedTaskStart(presentation, completionSubscription);
            presentTaskFailure(startFailure);
            updateControls();
        }
    }

    /// Publishes one matching terminal update result, then automatically refreshes installed add-on state.
    ///
    /// @param taskExecutor exact executor reaching a terminal state
    /// @param task exact task yielding the aggregate application result
    /// @param succeeded whether the aggregate task completed successfully
    private void applicationCompleted(
            TaskExecutor taskExecutor,
            Task<AddonUpdateApplicationResult> task,
            boolean succeeded) {
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (closed.get() || activeExecutor != taskExecutor) {
                return;
            }
            unsubscribe(activeCompletionSubscription);
            activeCompletionSubscription = null;
            activeExecutor = null;
            invalidateDisplayedUpdates();
            if (succeeded) {
                @Nullable AddonUpdateApplicationResult result = task.getResult();
                if (result == null) {
                    presentTaskFailure(new IllegalStateException(
                            "Add-on update task completed without an application result"));
                } else {
                    handleApplicationResult(result);
                    checkForUpdates();
                    return;
                }
            } else if (taskExecutor.isCancelled()) {
                statusLabel.setText(i18n("message.cancelled"));
            } else {
                @Nullable Throwable failure = taskExecutor.getFailure();
                presentTaskFailure(failure == null
                        ? new IllegalStateException("Add-on update task failed without a cause")
                        : failure);
            }
            updateControls();
        });
    }

    /// Stores a concise terminal summary and presents exact partial failures before the automatic rescan.
    ///
    /// @param result immutable aggregate update outcome
    private void handleApplicationResult(AddonUpdateApplicationResult result) {
        AddonUpdateApplicationResult outcome = Objects.requireNonNull(result, "result");
        if (outcome.hasFailures()) {
            pendingCompletionStatus = strings.failedDownloadText()
                    + " (" + outcome.successfulUpdates().size() + "/" + outcome.attemptedCount() + ")";
            interactions.showFailure(
                    this,
                    strings.failureDialogTitle(),
                    formatApplicationFailures(outcome.failures()));
        } else {
            pendingCompletionStatus = strings.updateSucceededText()
                    + " (" + outcome.successfulUpdates().size() + ")";
        }
    }

    /// Removes exact update objects that became stale as soon as their application task completed.
    ///
    /// A failed automatic rescan must leave no actionable rows instead of re-enabling objects whose
    /// local files may already have been renamed, replaced, or deleted.
    private void invalidateDisplayedUpdates() {
        EdtDispatcher.requireEventDispatchThread();
        displayedResult = null;
        resultsTable.clearSelection();
        tableModel.replaceRows(List.of());
    }

    /// Formats partial failures as stable one-line entries for a native error dialog.
    ///
    /// @param failures immutable partial failure list
    /// @return non-blank multi-line failure detail
    private String formatApplicationFailures(
            @Unmodifiable List<AddonUpdateApplicationFailure> failures) {
        StringBuilder detail = new StringBuilder(strings.failedDownloadText());
        for (AddonUpdateApplicationFailure failure : Objects.requireNonNull(failures, "failures")) {
            detail.append('\n')
                    .append(failure.updateItem().fileName())
                    .append(": ")
                    .append(failure.detail());
        }
        return detail.toString();
    }

    /// Clears a completed presentation before another update task is constructed.
    private void releaseCompletedPresentation() {
        EdtDispatcher.requireEventDispatchThread();
        if (activeExecutor != null) {
            return;
        }
        @Nullable TaskExecutorPresentationModel presentation = activePresentation;
        activePresentation = null;
        progressHost.clear();
        if (presentation != null) {
            presentation.close();
        }
    }

    /// Cleans up task presentation ownership after executor startup fails synchronously.
    ///
    /// @param presentation presentation created for the rejected task
    /// @param completionSubscription listener registration created for the rejected task
    private void cleanupFailedTaskStart(
            TaskExecutorPresentationModel presentation,
            Subscription completionSubscription) {
        unsubscribe(completionSubscription);
        activeCompletionSubscription = null;
        activeExecutor = null;
        if (activePresentation == presentation) {
            activePresentation = null;
        }
        progressHost.clear();
        presentation.close();
    }

    /// Shows one concise task preparation or execution failure through the existing native boundary.
    ///
    /// @param failure task failure
    private void presentTaskFailure(Throwable failure) {
        String detail = describeFailure(Objects.requireNonNull(failure, "failure"));
        statusLabel.setText(strings.failedDownloadText() + " " + detail);
        interactions.showFailure(this, strings.failureDialogTitle(), detail);
    }

    /// Releases one listener registration when present.
    ///
    /// @param subscription owned listener registration, or null while none is active
    private static void unsubscribe(@Nullable Subscription subscription) {
        if (subscription != null) {
            subscription.unsubscribe();
        }
    }

    /// Opens the selected update's verified project page when one was resolved by the check.
    private void openSelectedSourcePage() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable AddonUpdateTableRow row = selectedRow();
        @Nullable URI sourcePage = row == null ? null : row.sourcePage();
        if (sourcePage == null) {
            return;
        }
        handleDesktopCompletion(interactions.openSourcePage(sourcePage));
    }

    /// Loads the selected target version's changelog off the EDT and opens the safe Swing viewer.
    private void showSelectedChangelog() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || scanning.get() || exporting.get() || activeExecutor != null
                || !changelogLoading.compareAndSet(false, true)) {
            return;
        }
        @Nullable AddonUpdateTableRow row = selectedRow();
        @Nullable AddonUpdateItem selected = row == null ? null : row.update();
        if (selected == null) {
            changelogLoading.set(false);
            updateControls();
            return;
        }
        statusLabel.setText(i18n("update.changelog"));
        updateControls();
        try {
            executor.execute(() -> loadSelectedChangelog(selected));
        } catch (RuntimeException failure) {
            changelogLoaded(selected, null, null, failure);
        }
    }

    /// Reads one exact provider changelog and version page on the caller-owned worker executor.
    ///
    /// @param selected immutable update item captured before the request starts
    private void loadSelectedChangelog(AddonUpdateItem selected) {
        try {
            RemoteAddon.Type type = selected.update().repoType();
            RemoteAddonRepository repository = selected.source().getRepoForType(type);
            if (repository == null) {
                throw new IOException("No remote repository for " + type);
            }
            RemoteAddon.Version version = selected.update().targetVersion();
            @Nullable String markdown = repository.getAddonChangelog(
                    DownloadProviders.getDownloadProvider(),
                    version.projectId(),
                    version.versionId());
            URI page = URI.create(repository.getVersionPageUrl(version));
            EdtDispatcher.execute(() -> changelogLoaded(selected, markdown, page, null));
        } catch (IOException | RuntimeException failure) {
            EdtDispatcher.execute(() -> changelogLoaded(selected, null, null, failure));
        }
    }

    /// Applies one changelog result only while the panel remains open.
    ///
    /// @param selected update item captured for the request
    /// @param markdown provider Markdown, or null when absent
    /// @param page exact provider version page
    /// @param failure request failure, or null on success
    private void changelogLoaded(
            AddonUpdateItem selected,
            @Nullable String markdown,
            @Nullable URI page,
            @Nullable Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get()) {
            return;
        }
        changelogLoading.set(false);
        if (failure != null || page == null) {
            String detail = i18n("addon.changelog.failed");
            if (failure != null) {
                detail += ": " + describeFailure(failure);
            }
            interactions.showFailure(
                    this,
                    strings.failureDialogTitle(),
                    detail);
        } else {
            RemoteAddonChangelogDialog.show(
                    this,
                    i18n("addon.changelog") + " - " + selected.targetVersion(),
                    markdown,
                    page);
        }
        statusLabel.setText(displayedResult == null ? strings.emptyText() : statusFor(displayedResult));
        updateControls();
    }

    /// Opens the selected local add-on's containing directory.
    private void revealSelectedLocalFile() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable AddonUpdateTableRow row = selectedRow();
        if (row == null) {
            return;
        }
        handleDesktopCompletion(interactions.revealLocalFile(row.localFile()));
    }

    /// Handles one asynchronous native desktop completion while preserving the current table state.
    ///
    /// @param completion native desktop completion
    private void handleDesktopCompletion(CompletionStage<@Nullable Void> completion) {
        Objects.requireNonNull(completion, "completion").whenComplete((
                @Nullable Void ignored,
                @Nullable Throwable failure) -> EdtDispatcher.execute(() -> {
            if (!closed.get() && failure != null) {
                interactions.showFailure(this, strings.failureDialogTitle(), describeFailure(failure));
            }
        }));
    }

    /// Returns the one selected model row, or `null` when no row is selected.
    ///
    /// @return selected lightweight result row, or `null`
    private @Nullable AddonUpdateTableRow selectedRow() {
        int selectedIndex = resultsTable.getSelectedRow();
        return selectedIndex < 0 ? null : tableModel.rowAt(selectedIndex);
    }

    /// Unwraps asynchronous wrapper failures into concise text.
    ///
    /// @param failure completed operation failure
    /// @return non-blank user-visible failure text
    private static String describeFailure(Throwable failure) {
        Throwable current = Objects.requireNonNull(failure, "failure");
        if (current instanceof CompletionException && current.getCause() != null) {
            current = Objects.requireNonNull(current.getCause(), "completion cause");
        }
        @Nullable String detail = current.getMessage();
        return detail == null || detail.isBlank()
                ? current.getClass().getSimpleName()
                : detail;
    }

    /// Detaches listeners and disables commands during terminal EDT cleanup.
    private void closeOnEventDispatchThread() {
        EdtDispatcher.requireEventDispatchThread();
        ++scanRevision;
        scanning.set(false);
        pendingCompletionStatus = null;
        resultsTable.getSelectionModel().removeListSelectionListener(selectionListener);
        @Nullable TaskExecutor taskExecutor = activeExecutor;
        if (taskExecutor != null) {
            taskExecutor.cancel();
        }
        unsubscribe(activeCompletionSubscription);
        activeCompletionSubscription = null;
        activeExecutor = null;
        @Nullable TaskExecutorPresentationModel presentation = activePresentation;
        activePresentation = null;
        progressHost.close();
        if (presentation != null) {
            presentation.close();
        }
        checkButton.setEnabled(false);
        exportButton.setEnabled(false);
        openSourceButton.setEnabled(false);
        revealLocalButton.setEnabled(false);
        changelogButton.setEnabled(false);
        selectAllCheckBox.setEnabled(false);
        updateButton.setEnabled(false);
        resultsTable.setEnabled(false);
    }

    /// Routes exactly one update task's terminal event back to the panel state machine.
    @NotNullByDefault
    private final class ApplicationCompletionListener extends TaskListener {
        /// Exact executor whose terminal event this listener accepts.
        private final TaskExecutor taskExecutor;

        /// Exact aggregate task represented by the executor.
        private final Task<AddonUpdateApplicationResult> task;

        /// Creates a listener tied to one update execution.
        ///
        /// @param taskExecutor exact started executor
        /// @param task exact aggregate update task
        private ApplicationCompletionListener(
                TaskExecutor taskExecutor,
                Task<AddonUpdateApplicationResult> task) {
            this.taskExecutor = Objects.requireNonNull(taskExecutor, "taskExecutor");
            this.task = Objects.requireNonNull(task, "task");
        }

        /// Publishes only the matching executor's terminal event.
        ///
        /// @param succeeded whether the aggregate task succeeded
        /// @param sourceExecutor executor publishing the event
        @Override
        public void onStop(boolean succeeded, TaskExecutor sourceExecutor) {
            if (sourceExecutor == taskExecutor) {
                applicationCompleted(taskExecutor, task, succeeded);
            }
        }
    }

    /// Lightweight, immutable data needed to paint or act on one viewport row.
    ///
    /// @param fileName local display name
    /// @param localFile exact local file or directory
    /// @param currentVersion current remote version, or empty for failure rows
    /// @param targetVersion target remote version, or empty for failure rows
    /// @param source source name or failure label
    /// @param result result detail or failure summary
    /// @param sourcePage exact source page, or `null` when unavailable
    /// @param update exact actionable update, or `null` for a scan-failure row
    private record AddonUpdateTableRow(
            String fileName,
            Path localFile,
            String currentVersion,
            String targetVersion,
            String source,
            String result,
            @Nullable URI sourcePage,
            @Nullable AddonUpdateItem update) {
        /// Converts a successful update item to one table row.
        ///
        /// @param item update item
        /// @param sourceName localized source display name
        /// @return row with an optional source-page command
        private static AddonUpdateTableRow forUpdate(AddonUpdateItem item, String sourceName) {
            AddonUpdateItem update = Objects.requireNonNull(item, "item");
            return new AddonUpdateTableRow(
                    update.fileName(),
                    update.localFile(),
                    update.currentVersion(),
                    update.targetVersion(),
                    Objects.requireNonNull(sourceName, "sourceName"),
                    "",
                    update.sourcePage(),
                    update);
        }

        /// Converts one all-source failure to a visible non-actionable source row.
        ///
        /// @param failure failure item
        /// @param failureLabel localized failure label
        /// @return row retaining its local-file action
        private static AddonUpdateTableRow forFailure(
                AddonUpdateCheckFailure failure,
                String failureLabel) {
            AddonUpdateCheckFailure item = Objects.requireNonNull(failure, "failure");
            return new AddonUpdateTableRow(
                    item.fileName(),
                    item.localFile(),
                    "",
                    "",
                    Objects.requireNonNull(failureLabel, "failureLabel"),
                    item.detail(),
                    null,
                    null);
        }
    }

    /// Small immutable-list table model that lets JTable paint only rows inside its viewport.
    @NotNullByDefault
    private static final class AddonUpdateTableModel extends AbstractTableModel {
        /// Stable column labels.
        private final AddonUpdatesStrings strings;

        /// EDT callback that reconciles select-all and update command availability.
        private final Runnable selectionChanged;

        /// Completed lightweight rows, never partially mutated by a background scan.
        private List<AddonUpdateTableRow> rows = List.of();

        /// Checked state parallel to immutable rows; failure rows are always false and non-editable.
        private boolean[] selectedRows = new boolean[0];

        /// Creates the table model.
        ///
        /// @param strings stable column labels
        /// @param selectionChanged EDT callback after a checked-row mutation
        private AddonUpdateTableModel(AddonUpdatesStrings strings, Runnable selectionChanged) {
            this.strings = Objects.requireNonNull(strings, "strings");
            this.selectionChanged = Objects.requireNonNull(selectionChanged, "selectionChanged");
        }

        /// Returns the current number of result rows.
        ///
        /// @return current model row count
        @Override
        public int getRowCount() {
            return rows.size();
        }

        /// Returns the fixed number of displayed data columns.
        ///
        /// @return fixed column count
        @Override
        public int getColumnCount() {
            return 6;
        }

        /// Returns one stable visible column label.
        ///
        /// @param column model column index
        /// @return stable column display label
        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> strings.selectionColumnText();
                case 1 -> strings.fileColumn();
                case 2 -> strings.currentVersionColumn();
                case 3 -> strings.targetVersionColumn();
                case 4 -> strings.sourceColumn();
                case 5 -> strings.resultColumn();
                default -> throw new IllegalArgumentException("Unknown update table column: " + column);
            };
        }

        /// Returns Boolean only for the checked-state column so JTable supplies a native checkbox renderer.
        ///
        /// @param columnIndex requested model column
        /// @return Boolean for checked state, Object for text columns
        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 0 ? Boolean.class : Object.class;
        }

        /// Allows only actionable update rows to change their checked state.
        ///
        /// @param rowIndex requested model row
        /// @param columnIndex requested model column
        /// @return whether the checkbox may be edited
        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 0 && rows.get(rowIndex).update() != null;
        }

        /// Returns exactly one already-computed lightweight cell value.
        ///
        /// @param rowIndex viewport-requested row index
        /// @param columnIndex requested column index
        /// @return non-null display value
        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            AddonUpdateTableRow row = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> selectedRows[rowIndex];
                case 1 -> row.fileName();
                case 2 -> row.currentVersion();
                case 3 -> row.targetVersion();
                case 4 -> row.source();
                case 5 -> row.result();
                default -> throw new IllegalArgumentException("Unknown update table column: " + columnIndex);
            };
        }

        /// Replaces one actionable row's checked state and reconciles commands immediately.
        ///
        /// @param value requested Boolean checked state
        /// @param rowIndex target model row
        /// @param columnIndex target model column
        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            if (columnIndex != 0 || rows.get(rowIndex).update() == null) {
                return;
            }
            selectedRows[rowIndex] = Boolean.TRUE.equals(value);
            fireTableCellUpdated(rowIndex, columnIndex);
            selectionChanged.run();
        }

        /// Replaces the entire completed result atomically on the EDT.
        ///
        /// @param rows immutable or mutable completed row list
        private void replaceRows(List<AddonUpdateTableRow> rows) {
            this.rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
            selectedRows = new boolean[this.rows.size()];
            for (int index = 0; index < this.rows.size(); index++) {
                @Nullable AddonUpdateItem update = this.rows.get(index).update();
                selectedRows[index] = update != null && !update.update().localAddonFile().isDisabled();
            }
            fireTableDataChanged();
        }

        /// Selects or clears every actionable row while leaving scan-failure rows unchanged.
        ///
        /// @param selected requested checked state
        private void setAllUpdatesSelected(boolean selected) {
            for (int index = 0; index < rows.size(); index++) {
                if (rows.get(index).update() != null) {
                    selectedRows[index] = selected;
                }
            }
            if (!rows.isEmpty()) {
                fireTableRowsUpdated(0, rows.size() - 1);
            }
            selectionChanged.run();
        }

        /// Returns every checked exact update in current stable table order.
        ///
        /// @return immutable selected update list
        private @Unmodifiable List<AddonUpdateItem> selectedUpdates() {
            List<AddonUpdateItem> selected = new ArrayList<>();
            for (int index = 0; index < rows.size(); index++) {
                @Nullable AddonUpdateItem update = rows.get(index).update();
                if (update != null && selectedRows[index]) {
                    selected.add(update);
                }
            }
            return List.copyOf(selected);
        }

        /// Returns every actionable result row in stable table order for CSV export.
        ///
        /// @return immutable export snapshot excluding scan-failure rows
        private @Unmodifiable List<AddonUpdateExportRow> exportRows() {
            List<AddonUpdateExportRow> exports = new ArrayList<>();
            for (AddonUpdateTableRow row : rows) {
                if (row.update() != null) {
                    exports.add(new AddonUpdateExportRow(
                            row.fileName(),
                            row.currentVersion(),
                            row.targetVersion(),
                            row.source()));
                }
            }
            return List.copyOf(exports);
        }

        /// Returns the number of actionable update rows.
        ///
        /// @return actionable update count
        private int updateCount() {
            int count = 0;
            for (AddonUpdateTableRow row : rows) {
                if (row.update() != null) {
                    count++;
                }
            }
            return count;
        }

        /// Returns whether at least one update exists and every actionable row is checked.
        ///
        /// @return complete checked state for the select-all control
        private boolean areAllUpdatesSelected() {
            int updateCount = 0;
            for (int index = 0; index < rows.size(); index++) {
                if (rows.get(index).update() != null) {
                    updateCount++;
                    if (!selectedRows[index]) {
                        return false;
                    }
                }
            }
            return updateCount > 0;
        }

        /// Returns one selected table row if its model index remains valid.
        ///
        /// @param rowIndex selected model row index
        /// @return matching row, or `null` after a concurrent table replacement
        private @Nullable AddonUpdateTableRow rowAt(int rowIndex) {
            return rowIndex < 0 || rowIndex >= rows.size() ? null : rows.get(rowIndex);
        }
    }
}
