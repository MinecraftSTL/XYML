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
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies NBT file filtering, modeless editor reuse, and terminal lifecycle cleanup.
@NotNullByDefault
final class SwingNBTEditorLauncherTest {
    /// Supported chooser results reuse one editor while unsupported paths remain ignored.
    @Test
    void filtersChooserResultsAndReusesCurrentWindow() {
        RecordingEditorWindowFactory windows = new RecordingEditorWindowFactory();
        AtomicReference<@Nullable Path> selected = new AtomicReference<>(Path.of("level.dat_old"));
        AtomicReference<@Nullable SwingNBTEditorLauncher> launcherReference = new AtomicReference<>();

        EdtDispatcher.executeAndWait(() -> {
            SwingNBTEditorLauncher launcher = new SwingNBTEditorLauncher(selected::get, windows);
            launcherReference.set(launcher);
            launcher.chooseAndOpen();
            selected.set(Path.of("pack.json"));
            launcher.chooseAndOpen();
            selected.set(Path.of("r.0.0.MCA"));
            launcher.chooseAndOpen();
        });

        RecordingEditorWindow window = windows.createdWindows().get(0);
        assertEquals(1, windows.createdWindows().size());
        assertEquals(List.of(
                Path.of("level.dat_old").toAbsolutePath().normalize(),
                Path.of("r.0.0.MCA").toAbsolutePath().normalize()),
                window.openedSources());
        assertEquals(2, window.showCount());

        Objects.requireNonNull(launcherReference.get()).close();
    }

    /// Terminal editor disposal permits a new session, while launcher closure disables late commands.
    @Test
    void releasesCurrentWindowAndDisablesCommandsAfterClose() {
        RecordingEditorWindowFactory windows = new RecordingEditorWindowFactory();
        AtomicReference<@Nullable SwingNBTEditorLauncher> launcherReference = new AtomicReference<>();

        EdtDispatcher.executeAndWait(() -> {
            SwingNBTEditorLauncher launcher = new SwingNBTEditorLauncher(
                    () -> Path.of("level.dat"),
                    windows);
            launcherReference.set(launcher);
            launcher.chooseAndOpen();
            windows.createdWindows().get(0).disposeFromWindowManager();
            launcher.chooseAndOpen();
        });

        assertEquals(2, windows.createdWindows().size());
        RecordingEditorWindow secondWindow = windows.createdWindows().get(1);
        Objects.requireNonNull(launcherReference.get()).close();
        assertEquals(1, secondWindow.closeCount());

        EdtDispatcher.executeAndWait(() -> Objects.requireNonNull(launcherReference.get()).chooseAndOpen());
        assertEquals(2, windows.createdWindows().size());
    }

    /// Records all temporary editor windows created by one launcher.
    @NotNullByDefault
    private static final class RecordingEditorWindowFactory
            implements SwingNBTEditorLauncher.EditorWindowFactory {
        /// Created windows in production order.
        private final List<RecordingEditorWindow> windows = new ArrayList<>();

        /// Creates and records one fake modeless editor.
        ///
        /// @param closedObserver terminal disposal observer
        /// @return newly created fake editor
        @Override
        public SwingNBTEditorLauncher.EditorWindow create(
                Consumer<SwingNBTEditorLauncher.EditorWindow> closedObserver) {
            RecordingEditorWindow window = new RecordingEditorWindow(closedObserver);
            windows.add(window);
            return window;
        }

        /// Returns an immutable snapshot of created windows.
        ///
        /// @return created-window snapshot
        private @Unmodifiable List<RecordingEditorWindow> createdWindows() {
            return List.copyOf(windows);
        }
    }

    /// Headless editor-window fake retaining opened paths and terminal lifecycle counts.
    @NotNullByDefault
    private static final class RecordingEditorWindow implements SwingNBTEditorLauncher.EditorWindow {
        /// Terminal disposal observer supplied by the launcher.
        private final Consumer<SwingNBTEditorLauncher.EditorWindow> closedObserver;

        /// Normalized sources delivered to this window.
        private final List<Path> openedSources = new ArrayList<>();

        /// Number of show-or-focus commands.
        private int showCount;

        /// Number of forced terminal closes.
        private int closeCount;

        /// Whether terminal disposal has already been reported.
        private boolean disposed;

        /// Creates one fake window.
        ///
        /// @param closedObserver terminal observer
        private RecordingEditorWindow(
                Consumer<SwingNBTEditorLauncher.EditorWindow> closedObserver) {
            this.closedObserver = Objects.requireNonNull(closedObserver, "closedObserver");
        }

        /// Records one source.
        ///
        /// @param source supported normalized source
        @Override
        public void open(Path source) {
            openedSources.add(Objects.requireNonNull(source, "source"));
        }

        /// Records one visibility or focus command.
        @Override
        public void showOrFocus() {
            ++showCount;
        }

        /// Records a user-confirmed close request.
        @Override
        public void requestClose() {
            // The launcher never requests a user-confirmed close on this headless boundary.
        }

        /// Records forced closure and reports terminal disposal once.
        @Override
        public void close() {
            ++closeCount;
            disposeFromWindowManager();
        }

        /// Simulates terminal native disposal and notifies the launcher once.
        private void disposeFromWindowManager() {
            if (!disposed) {
                disposed = true;
                closedObserver.accept(this);
            }
        }

        /// Returns an immutable snapshot of opened sources.
        ///
        /// @return opened-source snapshot
        private @Unmodifiable List<Path> openedSources() {
            return List.copyOf(openedSources);
        }

        /// Returns the show-or-focus command count.
        ///
        /// @return show count
        private int showCount() {
            return showCount;
        }

        /// Returns the forced close count.
        ///
        /// @return close count
        private int closeCount() {
            return closeCount;
        }
    }
}
