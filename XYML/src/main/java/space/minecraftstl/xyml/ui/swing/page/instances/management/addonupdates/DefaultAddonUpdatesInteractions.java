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
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/// Production AWT desktop implementation for source-page and local-file commands.
///
/// Calls that may spawn a browser or file explorer are always scheduled on the caller-owned
/// background executor. Native dialogs remain confined to the EDT.
@NotNullByDefault
final class DefaultAddonUpdatesInteractions implements AddonUpdatesInteractions {
    /// Caller-owned executor used for native desktop work.
    private final Executor executor;

    /// Creates a desktop interaction boundary.
    ///
    /// @param executor caller-owned background executor
    DefaultAddonUpdatesInteractions(Executor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
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
