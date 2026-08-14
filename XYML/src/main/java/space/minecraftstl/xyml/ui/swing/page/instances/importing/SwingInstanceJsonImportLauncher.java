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
package space.minecraftstl.xyml.ui.swing.page.instances.importing;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.shell.AppShellFrame;
import space.minecraftstl.xyml.ui.swing.shell.AppShellPanel;
import space.minecraftstl.xyml.ui.swing.shell.ShellFileDropHandler;
import space.minecraftstl.xyml.ui.swing.shell.ShellPageId;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;
import space.minecraftstl.xyml.util.io.FileUtils;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/// Installs the composable instance-workspace route for single Minecraft version JSON imports.
///
/// The launcher performs lexical extension matching only. It captures the currently selected
/// repository when creating a modeless import window, then delegates parsing and all task creation
/// to [RepositoryInstanceJsonImportService]. One window is reused until native disposal.
@NotNullByDefault
public final class SwingInstanceJsonImportLauncher implements AutoCloseable {
    /// Shell whose shared ordered drop handler owns this workflow route.
    private final AppShellPanel shellPanel;

    /// Injected modeless window factory invoked on the EDT.
    private final ImportWindowFactory windowFactory;

    /// Independently removable JSON drop route.
    private final ShellFileDropHandler.RouteRegistration dropRegistration;

    /// Current modeless window, or null before opening and after disposal.
    private @Nullable ImportWindow importWindow;

    /// Whether owner or composition closure has permanently disabled this launcher.
    private boolean closed;

    /// Installs the production JSON import route and ties its lifetime to the launcher frame.
    ///
    /// @param frame production launcher frame
    /// @param repositorySupplier currently selected repository supplier
    /// @param ioExecutor executor used for JSON parsing and task preparation
    /// @param taskProgressStrings localized task lifecycle text
    /// @param animator optional shared progress animator
    /// @param progressAnimationDuration non-negative progress animation duration
    /// @return installed launcher lifecycle
    public static SwingInstanceJsonImportLauncher install(
            AppShellFrame frame,
            Supplier<XYMLGameRepository> repositorySupplier,
            Executor ioExecutor,
            TaskProgressStrings taskProgressStrings,
            @Nullable SwingAnimator animator,
            Duration progressAnimationDuration) {
        AppShellFrame owner = Objects.requireNonNull(frame, "frame");
        Supplier<XYMLGameRepository> repositories = Objects.requireNonNull(
                repositorySupplier,
                "repositorySupplier");
        Executor executor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        TaskProgressStrings progressStrings = Objects.requireNonNull(
                taskProgressStrings,
                "taskProgressStrings");
        Duration duration = Objects.requireNonNull(
                progressAnimationDuration,
                "progressAnimationDuration");
        AtomicReference<@Nullable SwingInstanceJsonImportLauncher> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> {
            InstanceJsonImportStrings strings = InstanceJsonImportStrings.localized();
            SwingInstanceJsonImportLauncher launcher = install(
                    owner.shellPanel(),
                    closedObserver -> new SwingInstanceJsonImportDialog(
                            owner,
                            new RepositoryInstanceJsonImportService(
                                    Objects.requireNonNull(
                                            repositories.get(),
                                            "repositorySupplier returned null"),
                                    executor),
                            strings,
                            progressStrings,
                            animator,
                            duration,
                            closedObserver));
            owner.addWindowListener(new WindowAdapter() {
                /// Releases the import workflow after owner disposal.
                ///
                /// @param event native owner-window event
                @Override
                public void windowClosed(WindowEvent event) {
                    Objects.requireNonNull(event, "event");
                    launcher.close();
                }
            });
            result.set(launcher);
        });
        return Objects.requireNonNull(result.get(), "instance JSON launcher was not installed");
    }

    /// Installs an injected launcher boundary for headless tests.
    ///
    /// @param shellPanel shell receiving the composable route
    /// @param windowFactory injected modeless-window factory
    /// @return installed launcher lifecycle
    static SwingInstanceJsonImportLauncher install(
            AppShellPanel shellPanel,
            ImportWindowFactory windowFactory) {
        EdtDispatcher.requireEventDispatchThread();
        return new SwingInstanceJsonImportLauncher(shellPanel, windowFactory);
    }

    /// Creates and registers one launcher on the EDT.
    ///
    /// @param shellPanel shell receiving the route
    /// @param windowFactory injected window factory
    private SwingInstanceJsonImportLauncher(
            AppShellPanel shellPanel,
            ImportWindowFactory windowFactory) {
        EdtDispatcher.requireEventDispatchThread();
        this.shellPanel = Objects.requireNonNull(shellPanel, "shellPanel");
        this.windowFactory = Objects.requireNonNull(windowFactory, "windowFactory");
        dropRegistration = ShellFileDropHandler.register(
                this.shellPanel,
                this::supportsOnInstanceWorkspace,
                this::open);
    }

    /// Returns whether one lexical path has the JSON extension used by Minecraft version metadata.
    ///
    /// @param path local path candidate
    /// @return whether the extension equals JSON ignoring case
    public static boolean supports(Path path) {
        return "json".equals(FileUtils.getExtension(
                Objects.requireNonNull(path, "path")).toLowerCase(Locale.ROOT));
    }

    /// Returns whether one JSON path is accepted on the default workspace or explicit instance-list page.
    ///
    /// @param path normalized dropped path
    /// @return whether this route accepts the path on the current shell page
    private boolean supportsOnInstanceWorkspace(Path path) {
        @Nullable ShellPageId selectedPage = shellPanel.selectedPage();
        return !closed
                && (selectedPage == null || selectedPage == ShellPageId.INSTANCES)
                && supports(Objects.requireNonNull(path, "path"));
    }

    /// Closes any current import window and removes only this launcher's drop route.
    @Override
    public void close() {
        EdtDispatcher.executeAndWait(() -> {
            if (closed) {
                return;
            }
            closed = true;
            dropRegistration.close();
            @Nullable ImportWindow currentWindow = importWindow;
            importWindow = null;
            if (currentWindow != null) {
                currentWindow.close();
            }
        });
    }

    /// Opens or reuses the modeless import window for one normalized JSON path.
    ///
    /// @param source dropped JSON path
    private void open(Path source) {
        EdtDispatcher.requireEventDispatchThread();
        Path normalizedSource = Objects.requireNonNull(source, "source")
                .toAbsolutePath()
                .normalize();
        if (!supportsOnInstanceWorkspace(normalizedSource)) {
            return;
        }
        @Nullable ImportWindow currentWindow = importWindow;
        if (currentWindow == null) {
            currentWindow = Objects.requireNonNull(
                    windowFactory.create(this::windowClosed),
                    "windowFactory returned null");
            importWindow = currentWindow;
        }
        currentWindow.open(normalizedSource);
        currentWindow.showOrFocus();
    }

    /// Clears current window identity after terminal native disposal.
    ///
    /// @param disposedWindow disposed window
    private void windowClosed(ImportWindow disposedWindow) {
        EdtDispatcher.requireEventDispatchThread();
        if (importWindow == Objects.requireNonNull(disposedWindow, "disposedWindow")) {
            importWindow = null;
        }
    }

    /// Creates one modeless import-window boundary.
    @NotNullByDefault
    @FunctionalInterface
    interface ImportWindowFactory {
        /// Creates a window whose terminal disposal reports its identity.
        ///
        /// @param closedObserver terminal disposal observer
        /// @return newly created modeless import window
        ImportWindow create(Consumer<ImportWindow> closedObserver);
    }

    /// Minimal modeless import-window operations required by the launcher.
    @NotNullByDefault
    interface ImportWindow extends AutoCloseable {
        /// Opens or replaces the selected JSON source while idle.
        ///
        /// @param source normalized supported source
        void open(Path source);

        /// Reveals the new window or focuses the existing window.
        void showOrFocus();

        /// Forces terminal cancellation and disposal.
        @Override
        void close();
    }
}
