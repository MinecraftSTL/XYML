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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.dialog.EditablePathChooser;
import space.minecraftstl.xyml.util.io.CSVTable;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Component;
import java.awt.Desktop;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Production Swing and AWT implementation for export, source-page, and local-file commands.
///
/// Calls that may spawn a browser or file explorer are always scheduled on the caller-owned
/// background executor. Native dialogs remain confined to the EDT.
@NotNullByDefault
final class DefaultAddonUpdatesInteractions implements AddonUpdatesInteractions {
    /// Required extension for exported update snapshots.
    private static final String CSV_EXTENSION = ".csv";

    /// Stable CSV header matching the established update-list format.
    private static final String FILE_NAME_HEADER = "Source File Name";

    /// Stable CSV header matching the established update-list format.
    private static final String CURRENT_VERSION_HEADER = "Current Version";

    /// Stable CSV header matching the established update-list format.
    private static final String TARGET_VERSION_HEADER = "Target Version";

    /// Stable CSV header matching the established update-list format.
    private static final String UPDATE_SOURCE_HEADER = "Update Source";

    /// Caller-owned executor used for native desktop work.
    private final Executor executor;

    /// Creates a desktop interaction boundary.
    ///
    /// @param executor caller-owned background executor
    DefaultAddonUpdatesInteractions(Executor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /// Opens an editable save chooser and refuses to replace an existing destination.
    ///
    /// @param owner dialog owner
    /// @param suggestedName collision-resistant suggested file name
    /// @return normalized CSV destination, or `null` after cancellation or collision
    @Override
    public @Nullable Path chooseExportFile(Component owner, String suggestedName) {
        EdtDispatcher.requireEventDispatchThread();
        JFileChooser chooser = new EditablePathChooser();
        chooser.setDialogTitle(i18n("button.export"));
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter(i18n("button.export") + " (*.csv)", "csv"));
        chooser.setSelectedFile(new File(Objects.requireNonNull(suggestedName, "suggestedName")));
        if (chooser.showSaveDialog(Objects.requireNonNull(owner, "owner")) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        Path destination = withCsvExtension(chooser.getSelectedFile().toPath())
                .toAbsolutePath()
                .normalize();
        if (Files.exists(destination)) {
            JOptionPane.showMessageDialog(
                    owner,
                    i18n("download.existing"),
                    i18n("message.confirm"),
                    JOptionPane.WARNING_MESSAGE);
            return null;
        }
        return destination;
    }

    /// Schedules creation of one immutable CSV snapshot outside the EDT.
    ///
    /// @param destination exact new CSV destination
    /// @param rows immutable actionable update rows
    /// @return nullable-void export completion
    @Override
    public CompletionStage<@Nullable Void> exportUpdateList(
            Path destination,
            @Unmodifiable List<AddonUpdateExportRow> rows) {
        Path target = Objects.requireNonNull(destination, "destination").toAbsolutePath().normalize();
        @Unmodifiable List<AddonUpdateExportRow> snapshot = List.copyOf(
                Objects.requireNonNull(rows, "rows"));
        CompletableFuture<@Nullable Void> result = new CompletableFuture<>();
        execute(() -> export(target, snapshot, result), result);
        return result;
    }

    /// Schedules browser navigation for an exact remote source page.
    ///
    /// @param sourcePage validated remote project page
    /// @return nullable-void desktop completion
    @Override
    public CompletionStage<@Nullable Void> openSourcePage(URI sourcePage) {
        URI destination = Objects.requireNonNull(sourcePage, "sourcePage");
        CompletableFuture<@Nullable Void> result = new CompletableFuture<>();
        execute(() -> browse(destination, result), result);
        return result;
    }

    /// Schedules opening the exact local file's containing directory.
    ///
    /// @param localFile exact local file or directory
    /// @return nullable-void desktop completion
    @Override
    public CompletionStage<@Nullable Void> revealLocalFile(Path localFile) {
        Path target = Objects.requireNonNull(localFile, "localFile").toAbsolutePath().normalize();
        CompletableFuture<@Nullable Void> result = new CompletableFuture<>();
        execute(() -> reveal(target, result), result);
        return result;
    }

    /// Shows one native failure dialog on the EDT.
    ///
    /// @param owner dialog owner
    /// @param title concise title
    /// @param detail actionable detail
    @Override
    public void showFailure(Component owner, String title, String detail) {
        EdtDispatcher.requireEventDispatchThread();
        JOptionPane.showMessageDialog(
                Objects.requireNonNull(owner, "owner"),
                Objects.requireNonNull(detail, "detail"),
                Objects.requireNonNull(title, "title"),
                JOptionPane.ERROR_MESSAGE);
    }

    /// Writes one update list through the shared structured CSV implementation.
    ///
    /// This package-visible method is deterministic for focused file-content tests.
    ///
    /// @param destination exact new destination
    /// @param rows immutable actionable update rows
    /// @throws IOException when the parent or file cannot be created
    static void writeUpdateList(
            Path destination,
            @Unmodifiable List<AddonUpdateExportRow> rows) throws IOException {
        Path target = Objects.requireNonNull(destination, "destination").toAbsolutePath().normalize();
        @Nullable Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        CSVTable table = new CSVTable();
        table.set(0, 0, FILE_NAME_HEADER);
        table.set(1, 0, CURRENT_VERSION_HEADER);
        table.set(2, 0, TARGET_VERSION_HEADER);
        table.set(3, 0, UPDATE_SOURCE_HEADER);
        List<AddonUpdateExportRow> snapshot = Objects.requireNonNull(rows, "rows");
        for (int index = 0; index < snapshot.size(); index++) {
            AddonUpdateExportRow row = snapshot.get(index);
            int csvRow = index + 1;
            table.set(0, csvRow, row.fileName());
            table.set(1, csvRow, row.currentVersion());
            table.set(2, csvRow, row.targetVersion());
            table.set(3, csvRow, row.source());
        }
        try (BufferedWriter writer = Files.newBufferedWriter(
                target,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            table.write(writer);
        }
    }

    /// Appends the required extension without changing an already-correct suffix.
    ///
    /// @param destination chooser destination
    /// @return destination ending in `.csv`
    private static Path withCsvExtension(Path destination) {
        Path target = Objects.requireNonNull(destination, "destination");
        @Nullable Path fileName = target.getFileName();
        if (fileName != null && fileName.toString().toLowerCase(Locale.ROOT).endsWith(CSV_EXTENSION)) {
            return target;
        }
        return target.resolveSibling((fileName == null ? "updates" : fileName.toString()) + CSV_EXTENSION);
    }

    /// Performs one export and completes its stage without leaking checked exceptions.
    ///
    /// @param destination exact new destination
    /// @param rows immutable actionable update rows
    /// @param result target completion result
    private static void export(
            Path destination,
            @Unmodifiable List<AddonUpdateExportRow> rows,
            CompletableFuture<@Nullable Void> result) {
        try {
            requireBackgroundThread();
            writeUpdateList(destination, rows);
            result.complete(null);
        } catch (IOException | RuntimeException failure) {
            result.completeExceptionally(failure);
        }
    }

    /// Submits one desktop action and exposes executor rejection as a failed stage.
    ///
    /// @param action background desktop action
    /// @param result target completion result
    private void execute(Runnable action, CompletableFuture<@Nullable Void> result) {
        try {
            executor.execute(Objects.requireNonNull(action, "action"));
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
        }
    }

    /// Opens one browser page outside the EDT.
    ///
    /// @param sourcePage remote source page
    /// @param result target completion result
    private static void browse(URI sourcePage, CompletableFuture<@Nullable Void> result) {
        try {
            requireBackgroundThread();
            Desktop desktop = requireDesktop(Desktop.Action.BROWSE);
            desktop.browse(sourcePage);
            result.complete(null);
        } catch (IOException | RuntimeException failure) {
            result.completeExceptionally(failure);
        }
    }

    /// Opens a local add-on's parent folder outside the EDT.
    ///
    /// @param localFile exact managed add-on path
    /// @param result target completion result
    private static void reveal(Path localFile, CompletableFuture<@Nullable Void> result) {
        try {
            requireBackgroundThread();
            @Nullable Path parent = localFile.getParent();
            Path directory = parent == null ? localFile : parent;
            if (!Files.exists(directory)) {
                throw new IOException("Local add-on path no longer exists: " + localFile);
            }
            Desktop desktop = requireDesktop(Desktop.Action.OPEN);
            desktop.open(directory.toFile());
            result.complete(null);
        } catch (IOException | RuntimeException failure) {
            result.completeExceptionally(failure);
        }
    }

    /// Obtains a supported native desktop action.
    ///
    /// @param action required desktop action
    /// @return desktop implementation supporting the action
    private static Desktop requireDesktop(Desktop.Action action) {
        if (!Desktop.isDesktopSupported()) {
            throw new UnsupportedOperationException("Desktop integration is unavailable");
        }
        Desktop desktop = Desktop.getDesktop();
        if (!desktop.isSupported(action)) {
            throw new UnsupportedOperationException("Desktop action is unavailable: " + action);
        }
        return desktop;
    }

    /// Rejects accidental execution of blocking native work on the EDT.
    private static void requireBackgroundThread() {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Addon update desktop work must not run on the EDT");
        }
    }
}
