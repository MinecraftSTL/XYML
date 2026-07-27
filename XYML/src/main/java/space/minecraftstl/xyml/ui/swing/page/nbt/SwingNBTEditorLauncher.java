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
package space.minecraftstl.xyml.ui.swing.page.nbt;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.nbt.NBTFileType;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.shell.AppShellFrame;
import space.minecraftstl.xyml.ui.swing.shell.AppShellPanel;
import space.minecraftstl.xyml.ui.swing.shell.ShellFileDropHandler;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/// Installs the temporary NBT editor entry points without adding a permanent shell destination.
///
/// The stable header command owns native file selection while the shell transfer handler accepts
/// exactly one supported NBT file. A single modeless editor window is reused until it closes.
@NotNullByDefault
public final class SwingNBTEditorLauncher implements AutoCloseable {
    /// Shell whose header and drop surface expose this temporary workflow.
    private final AppShellPanel shellPanel;

    /// Native or injected file chooser invoked only from the EDT.
    private final FileChooser fileChooser;

    /// Native or injected editor-window factory invoked only from the EDT.
    private final EditorWindowFactory editorWindowFactory;

    /// Independently removable shell-drop route owned by this launcher.
    private final ShellFileDropHandler.RouteRegistration dropRegistration;

    /// Current modeless editor window, or null before opening and after disposal.
    private @Nullable EditorWindow editorWindow;

    /// Whether owner or composition closure has permanently disabled this launcher.
    private boolean closed;

    /// Installs the production NBT entry points and ties their lifetime to the frame.
    ///
    /// @param frame production launcher frame
    /// @param ioExecutor caller-owned executor used for all NBT and bundled-icon I/O
    /// @return installed launcher lifecycle
    public static SwingNBTEditorLauncher install(
            AppShellFrame frame,
            Executor ioExecutor) {
        AppShellFrame owner = Objects.requireNonNull(frame, "frame");
        Executor executor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        AtomicReference<@Nullable SwingNBTEditorLauncher> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> {
            NBTEditorStrings strings = NBTEditorStrings.localized();
            SwingNBTEditorInteractions interactions = new SwingNBTEditorInteractions(owner, strings);
            SwingNBTEditorLauncher launcher = install(
                    owner.shellPanel(),
                    strings,
                    () -> interactions.chooseFile(null),
                    closedObserver -> new SwingNBTEditorDialog(
                            owner,
                            executor,
                            strings,
                            closedObserver));
            owner.addWindowListener(new WindowAdapter() {
                /// Releases the editor after owner disposal regardless of dirty document state.
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
        return Objects.requireNonNull(result.get(), "NBT editor launcher was not installed");
    }

    /// Installs a deterministic launcher boundary for headless tests.
    ///
    /// @param shellPanel shell receiving the header and drop entries
    /// @param strings stable text bundle
    /// @param fileChooser injected file chooser
    /// @param editorWindowFactory injected modeless-window factory
    /// @return installed launcher lifecycle
    static SwingNBTEditorLauncher install(
            AppShellPanel shellPanel,
            NBTEditorStrings strings,
            FileChooser fileChooser,
            EditorWindowFactory editorWindowFactory) {
        EdtDispatcher.requireEventDispatchThread();
        SwingNBTEditorLauncher launcher = new SwingNBTEditorLauncher(
                shellPanel,
                fileChooser,
                editorWindowFactory);
        launcher.shellPanel.configureFileTool(
                Objects.requireNonNull(strings, "strings").openTooltip(),
                launcher::chooseAndOpen);
        return launcher;
    }

    /// Creates one uninstalled launcher on the EDT.
    ///
    /// @param shellPanel shell later receiving entry points
    /// @param fileChooser file-selection boundary
    /// @param editorWindowFactory editor-window factory
    private SwingNBTEditorLauncher(
            AppShellPanel shellPanel,
            FileChooser fileChooser,
            EditorWindowFactory editorWindowFactory) {
        EdtDispatcher.requireEventDispatchThread();
        this.shellPanel = Objects.requireNonNull(shellPanel, "shellPanel");
        this.fileChooser = Objects.requireNonNull(fileChooser, "fileChooser");
        this.editorWindowFactory = Objects.requireNonNull(
                editorWindowFactory,
                "editorWindowFactory");
        dropRegistration = ShellFileDropHandler.register(
                this.shellPanel,
                NBTFileType::supports,
                this::open);
    }

    /// Forces any current editor window closed and removes shell drag-and-drop from any caller thread.
    @Override
    public void close() {
        EdtDispatcher.executeAndWait(() -> {
            if (closed) {
                return;
            }
            closed = true;
            dropRegistration.close();
            @Nullable EditorWindow currentWindow = editorWindow;
            editorWindow = null;
            if (currentWindow != null) {
                currentWindow.close();
            }
        });
    }

    /// Opens a supported path in the current or a newly created modeless editor.
    ///
    /// @param source normalized or relative source supplied by a chooser or shell drop
    private void open(Path source) {
        EdtDispatcher.requireEventDispatchThread();
        Path normalizedSource = Objects.requireNonNull(source, "source")
                .toAbsolutePath()
                .normalize();
        if (closed || !NBTFileType.supports(normalizedSource)) {
            return;
        }

        @Nullable EditorWindow currentWindow = editorWindow;
        if (currentWindow == null) {
            currentWindow = Objects.requireNonNull(
                    editorWindowFactory.create(this::editorWindowClosed),
                    "editorWindowFactory returned null");
            editorWindow = currentWindow;
        }
        currentWindow.open(normalizedSource);
        currentWindow.showOrFocus();
    }

    /// Chooses one file and forwards only a supported result to the editor.
    private void chooseAndOpen() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            return;
        }
        @Nullable Path selected = fileChooser.chooseFile();
        if (selected != null) {
            open(selected);
        }
    }

    /// Clears the current-window identity after native disposal.
    ///
    /// @param disposedWindow disposed editor window
    private void editorWindowClosed(EditorWindow disposedWindow) {
        EdtDispatcher.requireEventDispatchThread();
        if (editorWindow == Objects.requireNonNull(disposedWindow, "disposedWindow")) {
            editorWindow = null;
        }
    }

    /// Selects a lexical file path without performing NBT I/O.
    @NotNullByDefault
    @FunctionalInterface
    interface FileChooser {
        /// Returns the selected path or null when the user cancels.
        ///
        /// @return selected lexical path, or null
        @Nullable Path chooseFile();
    }

    /// Creates one modeless editor-window boundary.
    @NotNullByDefault
    @FunctionalInterface
    interface EditorWindowFactory {
        /// Creates an editor whose terminal disposal reports its own identity.
        ///
        /// @param closedObserver terminal disposal observer
        /// @return newly created editor window
        EditorWindow create(Consumer<EditorWindow> closedObserver);
    }

    /// Minimal modeless editor-window operations required by the launcher.
    @NotNullByDefault
    interface EditorWindow extends AutoCloseable {
        /// Opens or replaces the represented source.
        ///
        /// @param source supported normalized source
        void open(Path source);

        /// Reveals the new window or focuses the existing window.
        void showOrFocus();

        /// Requests closure through unsaved-change confirmation.
        void requestClose();

        /// Forces terminal resource release.
        @Override
        void close();
    }
}
