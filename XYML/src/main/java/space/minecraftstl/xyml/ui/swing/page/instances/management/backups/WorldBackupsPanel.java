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
package space.minecraftstl.xyml.ui.swing.page.instances.management.backups;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.GameRepository;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingTransparency;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Lazy Swing page for creating, listing, restoring, and deleting one instance's local world backups.
///
/// Construction is pure Swing setup. The host must call `activate` after selecting this page; only
/// then does the page ask its catalog to scan direct child paths in `saves` and `backups`. All world
/// parsing, compression, decompression, deletion, and desktop operations remain asynchronous.
@NotNullByDefault
public final class WorldBackupsPanel extends JPanel implements AutoCloseable {
    /// Catalog performing all background filesystem and Core world operations.
    private final WorldBackupCatalog catalog;

    /// Native dialog and platform file-manager boundary.
    private final WorldBackupInteractions interactions;

    /// Models selectable shallow save directories without forcing NBT parsing.
    private final DefaultComboBoxModel<WorldBackupSource> sourceModel = new DefaultComboBoxModel<>();

    /// Models indexed local ZIP backup archives.
    private final DefaultListModel<WorldBackupArchive> archiveModel = new DefaultListModel<>();

    /// Lets the user choose one source world to archive.
    private final JComboBox<WorldBackupSource> sourceBox = new JComboBox<>(sourceModel);

    /// Displays existing backup archives and drives archive-specific commands.
    private final JList<WorldBackupArchive> archiveList = new JList<>(archiveModel);

    /// Starts a fresh shallow directory index.
    private final JButton refreshButton = new JButton();

    /// Starts real locked-world ZIP export for the selected source directory.
    private final JButton createButton = new JButton(i18n("world.backup.create.new_one"));

    /// Opens the managed local backup directory through the native desktop boundary.
    private final JButton openDirectoryButton = new JButton();

    /// Restores the selected archive to a user-confirmed new save directory.
    private final JButton restoreButton = new JButton(i18n("swing.world_backup.restore"));

    /// Permanently deletes the selected backup archive after confirmation.
    private final JButton deleteButton = new JButton();

    /// Presents selected archive timestamp and size without opening archive contents.
    private final JLabel archiveDetailLabel = new JLabel();

    /// Presents scan and operation progress or terminal result text.
    private final JLabel statusLabel = new JLabel(i18n("swing.world_backup.idle"));

    /// Guards one-time initial activation and terminal component cleanup.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Most recently rendered shallow index.
    private WorldBackupSnapshot displayedSnapshot = WorldBackupSnapshot.empty();

    /// Monotonic request number used to discard a late result from an older operation.
    private long requestSequence;

    /// Whether the page has started at least one asynchronous shallow index.
    private boolean activated;

    /// Whether an index or mutation currently controls page availability.
    private boolean operationPending;

    /// Creates a production page for one repository instance.
    ///
    /// @param repository managed game repository
    /// @param instanceId stable managed instance identifier
    /// @param executor caller-owned background executor
    public WorldBackupsPanel(GameRepository repository, String instanceId, Executor executor) {
        this(
                new FileSystemWorldBackupCatalog(
                        Objects.requireNonNull(repository, "repository"),
                        Objects.requireNonNull(instanceId, "instanceId"),
                        Objects.requireNonNull(executor, "executor")),
                new DefaultWorldBackupInteractions(executor));
    }

    /// Creates a page with injected catalog and interaction boundaries for focused Swing tests.
    ///
    /// @param catalog asynchronous local-world backup catalog
    /// @param interactions native dialog and desktop boundary
    WorldBackupsPanel(WorldBackupCatalog catalog, WorldBackupInteractions interactions) {
        super(new BorderLayout());
        EdtDispatcher.requireEventDispatchThread();
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        configureComponents();
        updateControls();
    }

    /// Returns the tab title used by an instance-management host.
    ///
    /// @return localized world backup title
    public String title() {
        return i18n("world.backup");
    }

    /// Returns the last shallow index rendered on the Swing EDT.
    ///
    /// @return immutable rendered source and backup metadata
    public WorldBackupSnapshot displayedSnapshot() {
        EdtDispatcher.requireEventDispatchThread();
        return displayedSnapshot;
    }

    /// Starts the first shallow scan after the host selects this tab.
    ///
    /// Repeated calls are harmless and do not restart an already activated page; the refresh command
    /// remains available for explicit user-requested re-indexing.
    public void activate() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || activated) {
            return;
        }
        activated = true;
        refreshIndex();
    }

    /// Releases UI references and prevents later asynchronous completions from touching controls.
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        EdtDispatcher.executeAndWait(this::closeOnEventDispatchThread);
    }

    /// Builds the unframed compact page structure and wires user commands.
    private void configureComponents() {
        setName("worldBackupsPage");
        setOpaque(false);
        add(createHeading(), BorderLayout.NORTH);
        add(createContent(), BorderLayout.CENTER);
        add(createStatusBand(), BorderLayout.SOUTH);

        sourceBox.setName("worldBackupsSource");
        sourceBox.addActionListener(event -> updateControls());
        archiveList.setName("worldBackupsList");
        archiveList.setOpaque(false);
        archiveList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        DefaultListCellRenderer archiveRenderer = new DefaultListCellRenderer();
        archiveList.setCellRenderer((list, value, index, selected, focus) -> {
            JLabel label = (JLabel) archiveRenderer.getListCellRendererComponent(
                    list,
                    value,
                    index,
                    selected,
                    focus);
            label.setOpaque(selected);
            return label;
        });
        archiveList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                updateArchiveDetail();
                updateControls();
            }
        });
        createButton.setName("worldBackupsCreate");
        createButton.addActionListener(event -> createSelectedBackup());
        restoreButton.setName("worldBackupsRestore");
        restoreButton.addActionListener(event -> restoreSelectedBackup());
        configureIconButton(
                refreshButton,
                "worldBackupsRefresh",
                "assets/swing/icons/refresh.svg",
                i18n("button.refresh"),
                this::refreshIndex);
        configureIconButton(
                openDirectoryButton,
                "worldBackupsOpenDirectory",
                "assets/swing/icons/folder-open.svg",
                i18n("swing.world_backup.open_directory"),
                this::openBackupDirectory);
        configureIconButton(
                deleteButton,
                "worldBackupsDelete",
                "assets/swing/icons/delete.svg",
                i18n("world.backup.delete"),
                this::deleteSelectedBackup);
    }

    /// Creates the title and fixed-size global icon commands.
    ///
    /// @return configured heading band
    private JComponent createHeading() {
        JPanel heading = new JPanel(new MigLayout(
                "insets 12 16 8 16, fillx",
                "[grow,fill][]8[]",
                "[40!]"));
        heading.setOpaque(false);
        JLabel title = new JLabel(title());
        title.setName("worldBackupsTitle");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 26.0F));
        heading.add(title, "growx");
        heading.add(openDirectoryButton, "w 40!, h 40!");
        heading.add(refreshButton, "w 40!, h 40!");
        return heading;
    }

    /// Creates the source selector, archive list, and selected-backup commands.
    ///
    /// @return configured main content surface
    private JComponent createContent() {
        JPanel content = new JPanel(new MigLayout(
                "insets 8 16 12 16, fill, wrap 1",
                "[grow,fill]",
                "[][]10[grow,fill]8[]"));
        content.setOpaque(false);

        JPanel sourceRow = new JPanel(new MigLayout("insets 0, fillx", "[][grow,fill]8[]", "[]"));
        sourceRow.setOpaque(false);
        sourceRow.add(new JLabel(i18n("swing.world_backup.source")));
        sourceRow.add(sourceBox, "growx");
        sourceRow.add(createButton);
        content.add(sourceRow, "growx");

        JScrollPane archiveScroll = new JScrollPane(archiveList);
        archiveScroll.setName("worldBackupsScroll");
        archiveScroll.setBorder(BorderFactory.createEmptyBorder());
        SwingTransparency.revealBackgroundThroughScrollPane(archiveScroll);
        content.add(archiveScroll, "grow");

        JPanel archiveFooter = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill][]8[]", "[]"));
        archiveFooter.setOpaque(false);
        archiveDetailLabel.setName("worldBackupsDetail");
        archiveFooter.add(archiveDetailLabel, "growx");
        archiveFooter.add(restoreButton);
        archiveFooter.add(deleteButton, "w 40!, h 40!");
        content.add(archiveFooter, "growx");
        return content;
    }

    /// Creates the compact operation status footer.
    ///
    /// @return configured status band
    private JComponent createStatusBand() {
        JPanel status = new JPanel(new MigLayout("insets 4 16 12 16, fillx", "[grow,fill]", "[]"));
        status.setOpaque(false);
        statusLabel.setName("worldBackupsStatus");
        status.add(statusLabel, "growx");
        return status;
    }

    /// Starts one fresh shallow directory index from the refresh command or initial activation.
    private void refreshIndex() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || operationPending) {
            return;
        }
        long request = beginOperation(i18n("swing.world_backup.loading"));
        try {
            observeSnapshot(catalog.load(), request, i18n("swing.world_backup.loaded"));
        } catch (RuntimeException exception) {
            completeFailure(request, exception);
        }
    }

    /// Starts real archive export for the selected shallow source world.
    private void createSelectedBackup() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable WorldBackupSource source = selectedSource();
        if (source == null || closed.get() || operationPending) {
            return;
        }
        long request = beginOperation(i18n("world.backup.processing"));
        try {
            observeSnapshot(catalog.createBackup(source), request, i18n("swing.world_backup.created"));
        } catch (RuntimeException exception) {
            completeFailure(request, exception);
        }
    }

    /// Opens the backup directory through the background desktop boundary.
    private void openBackupDirectory() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get()) {
            return;
        }
        try {
            interactions.openDirectory(catalog.backupsDirectory()).whenComplete((@Nullable Void ignored, @Nullable Throwable failure) -> {
                if (failure != null) {
                    EdtDispatcher.execute(() -> showFailureIfOpen(failure));
                }
            });
        } catch (RuntimeException exception) {
            showFailureIfOpen(exception);
        }
    }

    /// Confirms and schedules permanent deletion for the selected local ZIP archive.
    private void deleteSelectedBackup() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable WorldBackupArchive archive = selectedArchive();
        if (archive == null || closed.get() || operationPending || !interactions.confirmDelete(this, archive)) {
            return;
        }
        long request = beginOperation(i18n("swing.world_backup.deleting"));
        try {
            observeSnapshot(catalog.deleteBackup(archive), request, i18n("swing.world_backup.deleted"));
        } catch (RuntimeException exception) {
            completeFailure(request, exception);
        }
    }

    /// Prompts for a new save name, confirms it, and schedules actual archive restoration.
    private void restoreSelectedBackup() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable WorldBackupArchive archive = selectedArchive();
        if (archive == null || closed.get() || operationPending) {
            return;
        }
        @Nullable String destinationName = interactions.requestRestoreDestination(this, archive);
        if (destinationName == null || !interactions.confirmRestore(this, archive, destinationName)) {
            return;
        }
        long request = beginOperation(i18n("swing.world_backup.restoring"));
        try {
            observeSnapshot(
                    catalog.restoreBackup(archive, destinationName),
                    request,
                    i18n("swing.world_backup.restored"));
        } catch (RuntimeException exception) {
            completeFailure(request, exception);
        }
    }

    /// Marks the page busy and returns a unique request token for one asynchronous action.
    ///
    /// @param status visible pending-operation text
    /// @return unique active request sequence value
    private long beginOperation(String status) {
        EdtDispatcher.requireEventDispatchThread();
        operationPending = true;
        requestSequence++;
        statusLabel.setText(Objects.requireNonNull(status, "status"));
        updateControls();
        return requestSequence;
    }

    /// Applies a terminal snapshot or reports its failure on the EDT if this request remains current.
    ///
    /// @param stage terminal background operation
    /// @param request active request token
    /// @param successStatus visible success text
    private void observeSnapshot(CompletionStage<WorldBackupSnapshot> stage, long request, String successStatus) {
        Objects.requireNonNull(stage, "stage").whenComplete((@Nullable WorldBackupSnapshot snapshot, @Nullable Throwable failure) ->
                EdtDispatcher.execute(() -> {
                    if (closed.get() || request != requestSequence) {
                        return;
                    }
                    if (failure != null) {
                        completeFailure(request, failure);
                    } else if (snapshot == null) {
                        completeFailure(request, new IllegalStateException(i18n("swing.world_backup.missing_index")));
                    } else {
                        operationPending = false;
                        applySnapshot(snapshot);
                        statusLabel.setText(Objects.requireNonNull(successStatus, "successStatus"));
                        updateControls();
                    }
                }));
    }

    /// Clears pending state and presents one concise asynchronous operation failure.
    ///
    /// @param request request token whose failure completed
    /// @param failure original synchronous or asynchronous failure
    private void completeFailure(long request, Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || request != requestSequence) {
            return;
        }
        operationPending = false;
        statusLabel.setText(i18n("message.failed"));
        updateControls();
        showFailureIfOpen(failure);
    }

    /// Applies a new shallow index while preserving selection by filesystem path when possible.
    ///
    /// @param snapshot terminal immutable source and archive index
    private void applySnapshot(WorldBackupSnapshot snapshot) {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable WorldBackupSource previousSource = selectedSource();
        @Nullable WorldBackupArchive previousArchive = selectedArchive();
        displayedSnapshot = Objects.requireNonNull(snapshot, "snapshot");
        sourceModel.removeAllElements();
        for (WorldBackupSource source : snapshot.sources()) {
            sourceModel.addElement(source);
        }
        archiveModel.clear();
        for (WorldBackupArchive archive : snapshot.archives()) {
            archiveModel.addElement(archive);
        }
        restoreSourceSelection(previousSource);
        restoreArchiveSelection(previousArchive);
        updateArchiveDetail();
    }

    /// Restores a source selection by directory path, falling back to the first source.
    ///
    /// @param previousSource selected source before model replacement, or null
    private void restoreSourceSelection(@Nullable WorldBackupSource previousSource) {
        if (sourceModel.getSize() == 0) {
            return;
        }
        if (previousSource != null) {
            for (int index = 0; index < sourceModel.getSize(); index++) {
                @Nullable WorldBackupSource candidate = sourceModel.getElementAt(index);
                if (candidate != null && candidate.directory().equals(previousSource.directory())) {
                    sourceBox.setSelectedIndex(index);
                    return;
                }
            }
        }
        sourceBox.setSelectedIndex(0);
    }

    /// Restores an archive selection by its stable archive path when it remains indexed.
    ///
    /// @param previousArchive selected archive before model replacement, or null
    private void restoreArchiveSelection(@Nullable WorldBackupArchive previousArchive) {
        if (previousArchive != null) {
            for (int index = 0; index < archiveModel.getSize(); index++) {
                WorldBackupArchive candidate = archiveModel.getElementAt(index);
                if (candidate.archive().equals(previousArchive.archive())) {
                    archiveList.setSelectedIndex(index);
                    return;
                }
            }
        }
        archiveList.clearSelection();
    }

    /// Refreshes detail text for the currently selected archive without opening its compressed contents.
    private void updateArchiveDetail() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable WorldBackupArchive archive = selectedArchive();
        archiveDetailLabel.setText(archive == null
                ? i18n("swing.world_backup.select_archive")
                : formatArchiveDetail(archive));
    }

    /// Enables controls according to page lifecycle, pending operation state, and user selections.
    private void updateControls() {
        EdtDispatcher.requireEventDispatchThread();
        boolean interactive = !closed.get() && !operationPending;
        refreshButton.setEnabled(interactive);
        openDirectoryButton.setEnabled(!closed.get());
        createButton.setEnabled(interactive && selectedSource() != null);
        boolean archiveSelected = selectedArchive() != null;
        restoreButton.setEnabled(interactive && archiveSelected);
        deleteButton.setEnabled(interactive && archiveSelected);
    }

    /// Returns the currently selected shallow source directory, if any.
    ///
    /// @return selected source or null
    private @Nullable WorldBackupSource selectedSource() {
        return (WorldBackupSource) sourceBox.getSelectedItem();
    }

    /// Returns the currently selected local backup archive, if any.
    ///
    /// @return selected archive or null
    private @Nullable WorldBackupArchive selectedArchive() {
        return archiveList.getSelectedValue();
    }

    /// Formats lightweight archive metadata for the selected-item footer.
    ///
    /// @param archive selected archive metadata
    /// @return archive size and localized timestamp text
    private static String formatArchiveDetail(WorldBackupArchive archive) {
        WorldBackupArchive selectedArchive = Objects.requireNonNull(archive, "archive");
        String size = selectedArchive.sizeBytes() < 1024L
                ? selectedArchive.sizeBytes() + " B"
                : String.format(java.util.Locale.ROOT, "%.1f KiB", selectedArchive.sizeBytes() / 1024.0D);
        try {
            String timestamp = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                    .withZone(ZoneId.systemDefault())
                    .format(selectedArchive.lastModified());
            return size + " - " + timestamp;
        } catch (DateTimeException exception) {
            return size;
        }
    }

    /// Configures an icon-only fixed-footprint tool command with hover and assistive text.
    ///
    /// @param button target button
    /// @param name deterministic component name
    /// @param iconPath bundled SVG path
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

    /// Shows one failure dialog only while this page remains open on the EDT.
    ///
    /// @param failure original operation failure
    private void showFailureIfOpen(Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        if (!closed.get()) {
            interactions.showFailure(this, i18n("world.backup"), failureDetail(failure));
        }
    }

    /// Removes standard asynchronous wrappers and returns concise user-visible failure detail.
    ///
    /// @param failure original terminal failure
    /// @return exception message or simple type name
    private static String failureDetail(Throwable failure) {
        Throwable current = Objects.requireNonNull(failure, "failure");
        if (current instanceof CompletionException && current.getCause() != null) {
            current = Objects.requireNonNull(current.getCause(), "completion failure cause");
        }
        @Nullable String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    /// Disables commands and detaches displayed model data during terminal page cleanup.
    private void closeOnEventDispatchThread() {
        EdtDispatcher.requireEventDispatchThread();
        operationPending = false;
        sourceModel.removeAllElements();
        archiveModel.clear();
        archiveDetailLabel.setText("");
        statusLabel.setText("");
        updateControls();
        removeAll();
    }
}
