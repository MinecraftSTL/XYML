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
import space.minecraftstl.xyml.addon.RemoteAddon;
import space.minecraftstl.xyml.game.GameRepository;
import space.minecraftstl.xyml.setting.DownloadProviders;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.BorderFactory;
import javax.swing.JButton;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/// Lazy Swing page for checking installed Mods and resource packs for real compatible updates.
///
/// Construction only creates viewport-rendered table controls. Local file discovery and every
/// remote metadata request start exclusively after the user invokes `checkForUpdates`; results
/// are applied as immutable rows and never imply that an update was downloaded or installed.
@NotNullByDefault
public final class AddonUpdatesPanel extends JPanel implements AutoCloseable {
    /// Blocking access to local add-ons and remote source metadata.
    private final AddonUpdateScanAccess scanAccess;

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

    /// Opens the selected row's exact remote project page when resolved.
    private final JButton openSourceButton = new JButton();

    /// Opens the selected row's containing local directory.
    private final JButton revealLocalButton = new JButton();

    /// Displays idle, scanning, empty, and partial-failure state.
    private final JLabel statusLabel = new JLabel();

    /// Owned table selection listener detached during closure.
    private final ListSelectionListener selectionListener;

    /// Guards one in-flight network scan and prevents concurrent duplicate calls.
    private final AtomicBoolean scanning = new AtomicBoolean();

    /// Guards terminal component teardown and late background callbacks.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Monotonic scan identifier used to ignore stale background results.
    private long scanRevision;

    /// Latest fully applied result, or `null` before the first successful check.
    private @Nullable AddonUpdateScanResult displayedResult;

    /// Creates a production panel for one managed instance without starting a check.
    ///
    /// @param repository repository containing the selected instance
    /// @param instanceId stable non-blank selected instance identifier
    /// @param executor caller-owned background executor
    public AddonUpdatesPanel(GameRepository repository, String instanceId, Executor executor) {
        this(
                new RepositoryAddonUpdateScanAccess(
                        Objects.requireNonNull(repository, "repository"),
                        Objects.requireNonNull(instanceId, "instanceId"),
                        DownloadProviders.getDownloadProvider()),
                Objects.requireNonNull(executor, "executor"),
                AddonUpdatesStrings.localized(),
                new DefaultAddonUpdatesInteractions(executor));
    }

    /// Creates a deterministic page with explicit local, remote, and native interaction boundaries.
    ///
    /// This constructor is package-visible for focused headless tests. The panel owns no executor
    /// and cancels no caller work at close; it only suppresses late UI application.
    ///
    /// @param scanAccess blocking scan boundary
    /// @param executor caller-owned background executor
    /// @param strings stable visible labels
    /// @param interactions native desktop boundary
    AddonUpdatesPanel(
            AddonUpdateScanAccess scanAccess,
            Executor executor,
            AddonUpdatesStrings strings,
            AddonUpdatesInteractions interactions) {
        super(new BorderLayout());
        EdtDispatcher.requireEventDispatchThread();
        this.scanAccess = Objects.requireNonNull(scanAccess, "scanAccess");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.strings = Objects.requireNonNull(strings, "strings");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        tableModel = new AddonUpdateTableModel(this.strings);
        resultsTable = new JTable(tableModel);
        selectionListener = this::selectionChanged;

        setName("addonUpdatesPage");
        setBorder(BorderFactory.createEmptyBorder());
        add(createHeadingBand(), BorderLayout.NORTH);
        add(createTableSurface(), BorderLayout.CENTER);
        add(createStatusBand(), BorderLayout.SOUTH);
        configureTable();
        applySelectionActions();
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
        if (closed.get() || !scanning.compareAndSet(false, true)) {
            return;
        }
        long requestRevision = ++scanRevision;
        checkButton.setEnabled(false);
        statusLabel.setText(strings.checkingText());
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
                "[grow,fill][]8[]8[]",
                "[40!]"));
        heading.setOpaque(false);
        JLabel title = new JLabel(strings.title());
        title.setName("addonUpdatesTitle");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 26.0F));
        heading.add(title, "growx");

        checkButton.setName("addonUpdatesCheck");
        checkButton.setText(strings.checkButtonText());
        checkButton.setIcon(new FlatSVGIcon("assets/swing/icons/refresh.svg", 18, 18));
        checkButton.setToolTipText(strings.checkButtonText());
        checkButton.addActionListener(event -> checkForUpdates());
        heading.add(checkButton, "h 40!");

        configureIconButton(
                openSourceButton,
                "addonUpdatesOpenSource",
                "assets/swing/icons/arrow-forward.svg",
                strings.sourceTooltip(),
                this::openSelectedSourcePage);
        heading.add(openSourceButton, "w 40!, h 40!");

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
        surface.setBorder(BorderFactory.createEmptyBorder(4, 16, 8, 16));
        JScrollPane scrollPane = new JScrollPane(resultsTable);
        scrollPane.setName("addonUpdatesScrollPane");
        surface.add(scrollPane, BorderLayout.CENTER);
        return surface;
    }

    /// Creates a compact scan-state footer.
    ///
    /// @return status band
    private JComponent createStatusBand() {
        JPanel status = new JPanel(new MigLayout(
                "insets 4 16 12 16, fillx",
                "[grow,fill]",
                "[]"));
        status.setOpaque(false);
        statusLabel.setName("addonUpdatesStatus");
        status.add(statusLabel, "growx");
        return status;
    }

    /// Configures table selection, column sizing, and no-full-row repaint behavior.
    private void configureTable() {
        resultsTable.setName("addonUpdatesTable");
        resultsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultsTable.setAutoCreateRowSorter(false);
        resultsTable.setFillsViewportHeight(true);
        resultsTable.getSelectionModel().addListSelectionListener(selectionListener);
        configureColumn(0, 220);
        configureColumn(1, 150);
        configureColumn(2, 150);
        configureColumn(3, 120);
        configureColumn(4, 320);
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
        statusLabel.setText(statusFor(displayedResult));
        scanning.set(false);
        checkButton.setEnabled(true);
        applySelectionActions();
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
        statusLabel.setText(strings.failedText() + " " + describeFailure(failure));
        scanning.set(false);
        checkButton.setEnabled(true);
        applySelectionActions();
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
            applySelectionActions();
        }
    }

    /// Synchronizes selected-row actions without querying any source.
    private void applySelectionActions() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable AddonUpdateTableRow row = selectedRow();
        revealLocalButton.setEnabled(!closed.get() && row != null);
        openSourceButton.setEnabled(!closed.get() && row != null && row.sourcePage() != null);
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
        Objects.requireNonNull(completion, "completion").whenComplete((@Nullable Void ignored, @Nullable Throwable failure) ->
                EdtDispatcher.execute(() -> {
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
        resultsTable.getSelectionModel().removeListSelectionListener(selectionListener);
        checkButton.setEnabled(false);
        openSourceButton.setEnabled(false);
        revealLocalButton.setEnabled(false);
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
    private record AddonUpdateTableRow(
            String fileName,
            Path localFile,
            String currentVersion,
            String targetVersion,
            String source,
            String result,
            @Nullable URI sourcePage) {
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
                    update.sourcePage());
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
                    null);
        }
    }

    /// Small immutable-list table model that lets JTable paint only rows inside its viewport.
    @NotNullByDefault
    private static final class AddonUpdateTableModel extends AbstractTableModel {
        /// Stable column labels.
        private final AddonUpdatesStrings strings;

        /// Completed lightweight rows, never partially mutated by a background scan.
        private List<AddonUpdateTableRow> rows = List.of();

        /// Creates the table model.
        ///
        /// @param strings stable column labels
        private AddonUpdateTableModel(AddonUpdatesStrings strings) {
            this.strings = Objects.requireNonNull(strings, "strings");
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
            return 5;
        }

        /// Returns one stable visible column label.
        ///
        /// @param column model column index
        /// @return stable column display label
        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> strings.fileColumn();
                case 1 -> strings.currentVersionColumn();
                case 2 -> strings.targetVersionColumn();
                case 3 -> strings.sourceColumn();
                case 4 -> strings.resultColumn();
                default -> throw new IllegalArgumentException("Unknown update table column: " + column);
            };
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
                case 0 -> row.fileName();
                case 1 -> row.currentVersion();
                case 2 -> row.targetVersion();
                case 3 -> row.source();
                case 4 -> row.result();
                default -> throw new IllegalArgumentException("Unknown update table column: " + columnIndex);
            };
        }

        /// Replaces the entire completed result atomically on the EDT.
        ///
        /// @param rows immutable or mutable completed row list
        private void replaceRows(List<AddonUpdateTableRow> rows) {
            this.rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
            fireTableDataChanged();
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
