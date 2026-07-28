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

import java.awt.Component;
import java.awt.Frame;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/// Owns the file-selection and modeless-window lifecycle for the settings NBT tool.
///
/// The launcher has no shell navigation or drag-and-drop responsibilities. Its component owner
/// supplies native file-dialog placement, while the owner resolver is evaluated only when a new
/// editor window is required. One editor window is reused until that window closes.
@NotNullByDefault
public final class SwingNBTEditorLauncher implements AutoCloseable {
    /// Native or injected file chooser invoked only from the EDT.
    private final FileChooser fileChooser;

    /// Native or injected editor-window factory invoked only from the EDT.
    private final EditorWindowFactory editorWindowFactory;

    /// Current modeless editor window, or null before opening and after disposal.
    private @Nullable EditorWindow editorWindow;

    /// Whether the owning settings tool has permanently disabled this launcher.
    private boolean closed;

    /// Creates the production launcher for one settings component.
    ///
    /// @param chooserOwner component owning the native file chooser
    /// @param ownerResolver dynamic resolver for the containing launcher frame
    /// @param ioExecutor caller-owned executor used for NBT and bundled-icon I/O
    /// @return configured launcher lifecycle
    static SwingNBTEditorLauncher create(
            Component chooserOwner,
            OwnerResolver ownerResolver,
            Executor ioExecutor) {
        EdtDispatcher.requireEventDispatchThread();
        Component component = Objects.requireNonNull(chooserOwner, "chooserOwner");
        OwnerResolver resolver = Objects.requireNonNull(ownerResolver, "ownerResolver");
        Executor executor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        NBTEditorStrings strings = NBTEditorStrings.localized();
        SwingNBTEditorInteractions interactions = new SwingNBTEditorInteractions(component, strings);
        return new SwingNBTEditorLauncher(
                () -> interactions.chooseFile(null),
                closedObserver -> {
                    @Nullable Frame owner = resolver.resolve();
                    return owner == null
                            ? null
                            : new SwingNBTEditorDialog(owner, executor, strings, closedObserver);
                });
    }

    /// Creates a deterministic launcher boundary for headless tests.
    ///
    /// @param fileChooser injected file chooser
    /// @param editorWindowFactory injected modeless-window factory
    SwingNBTEditorLauncher(
            FileChooser fileChooser,
            EditorWindowFactory editorWindowFactory) {
        EdtDispatcher.requireEventDispatchThread();
        this.fileChooser = Objects.requireNonNull(fileChooser, "fileChooser");
        this.editorWindowFactory = Objects.requireNonNull(editorWindowFactory, "editorWindowFactory");
    }

    /// Selects one local file and opens it when the path has a supported NBT extension.
    public void chooseAndOpen() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            return;
        }
        @Nullable Path selected = fileChooser.chooseFile();
        if (selected != null) {
            open(selected);
        }
    }

    /// Forces any current editor window closed from any caller thread.
    @Override
    public void close() {
        EdtDispatcher.executeAndWait(() -> {
            if (closed) {
                return;
            }
            closed = true;
            @Nullable EditorWindow currentWindow = editorWindow;
            editorWindow = null;
            if (currentWindow != null) {
                currentWindow.close();
            }
        });
    }

    /// Opens a supported path in the current or a newly created modeless editor.
    ///
    /// @param source normalized or relative source supplied by the settings chooser
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
            currentWindow = editorWindowFactory.create(this::editorWindowClosed);
            if (currentWindow == null) {
                return;
            }
            editorWindow = currentWindow;
        }
        currentWindow.open(normalizedSource);
        currentWindow.showOrFocus();
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

    /// Resolves the current launcher frame only when a new editor window is required.
    @NotNullByDefault
    @FunctionalInterface
    interface OwnerResolver {
        /// Returns the current containing frame, or null while the settings panel is detached.
        ///
        /// @return containing launcher frame, or null
        @Nullable Frame resolve();
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
        /// Returning null leaves the selected document unopened, which can occur when the settings
        /// page is detached between file selection and window creation.
        ///
        /// @param closedObserver terminal disposal observer
        /// @return newly created editor window, or null when no owner is available
        @Nullable EditorWindow create(Consumer<EditorWindow> closedObserver);
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
