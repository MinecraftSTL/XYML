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
import space.minecraftstl.xyml.ui.swing.MotionPolicy;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.shell.AppShellPanel;
import space.minecraftstl.xyml.ui.swing.shell.ShellPageFactory;
import space.minecraftstl.xyml.ui.swing.shell.ShellPageId;
import space.minecraftstl.xyml.ui.swing.shell.ShellPagePresentations;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.TransferHandler;
import java.awt.Component;
import java.awt.Container;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies headless shell entry installation, NBT filtering, window reuse, and terminal cleanup.
@NotNullByDefault
final class SwingNBTEditorLauncherTest {
    /// Header selection and supported shell drops reuse one temporary editor window.
    @Test
    void installsHeaderChooserAndFiltersShellDrops() {
        AppShellPanel shell = createShell();
        RecordingEditorWindowFactory windows = new RecordingEditorWindowFactory();
        AtomicReference<@Nullable Path> selected = new AtomicReference<>(Path.of("level.dat_old"));
        AtomicReference<@Nullable SwingNBTEditorLauncher> launcherReference = new AtomicReference<>();

        EdtDispatcher.executeAndWait(() -> {
            SwingNBTEditorLauncher launcher = SwingNBTEditorLauncher.install(
                    shell,
                    NBTEditorStrings.english(),
                    selected::get,
                    windows);
            launcherReference.set(launcher);
            JButton fileTool = findFileTool(shell);
            assertTrue(fileTool.isVisible());
            assertEquals("Open NBT file", fileTool.getText());
            fileTool.doClick();

            TransferHandler handler = Objects.requireNonNull(shell.getTransferHandler());
            TransferHandler.TransferSupport supported = transfer(List.of(new File("r.0.0.MCA")));
            assertTrue(handler.canImport(supported));
            assertTrue(handler.importData(supported));

            TransferHandler.TransferSupport unsupported = transfer(List.of(new File("pack.json")));
            assertFalse(handler.canImport(unsupported));
            assertFalse(handler.importData(unsupported));
        });

        RecordingEditorWindow window = windows.createdWindows().get(0);
        assertEquals(1, windows.createdWindows().size());
        assertEquals(List.of(
                Path.of("level.dat_old").toAbsolutePath().normalize(),
                Path.of("r.0.0.MCA").toAbsolutePath().normalize()),
                window.openedSources());
        assertEquals(2, window.showCount());

        Objects.requireNonNull(launcherReference.get()).close();
        shell.close();
    }

    /// Terminal editor disposal permits a new session, while launcher closure disables late commands.
    @Test
    void releasesCurrentWindowAndDisablesEntriesAfterClose() {
        AppShellPanel shell = createShell();
        RecordingEditorWindowFactory windows = new RecordingEditorWindowFactory();
        AtomicReference<@Nullable SwingNBTEditorLauncher> launcherReference = new AtomicReference<>();

        EdtDispatcher.executeAndWait(() -> {
            SwingNBTEditorLauncher launcher = SwingNBTEditorLauncher.install(
                    shell,
                    NBTEditorStrings.english(),
                    () -> Path.of("level.dat"),
                    windows);
            launcherReference.set(launcher);
            JButton fileTool = findFileTool(shell);
            fileTool.doClick();
            windows.createdWindows().get(0).disposeFromWindowManager();
            fileTool.doClick();
        });

        assertEquals(2, windows.createdWindows().size());
        RecordingEditorWindow secondWindow = windows.createdWindows().get(1);
        Objects.requireNonNull(launcherReference.get()).close();
        assertEquals(1, secondWindow.closeCount());
        assertNull(shell.getTransferHandler());

        EdtDispatcher.executeAndWait(() -> findFileTool(shell).doClick());
        assertEquals(2, windows.createdWindows().size());
        shell.close();
    }

    /// Creates a complete headless shell on the EDT.
    ///
    /// @return initialized shell panel
    private static AppShellPanel createShell() {
        AtomicReference<@Nullable AppShellPanel> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> result.set(new AppShellPanel(
                pageFactories(),
                ShellPageId.HOME,
                ShellPagePresentations.englishFallback(),
                new SwingAnimator(MotionPolicy.OFF, 16),
                Duration.ZERO)));
        return Objects.requireNonNull(result.get(), "shell was not created");
    }

    /// Creates one lightweight page factory for every permanent shell destination.
    ///
    /// @return complete factory map
    private static @Unmodifiable Map<ShellPageId, ShellPageFactory<? extends JComponent>>
            pageFactories() {
        EnumMap<ShellPageId, ShellPageFactory<? extends JComponent>> factories =
                new EnumMap<>(ShellPageId.class);
        for (ShellPageId page : ShellPageId.values()) {
            factories.put(page, JPanel::new);
        }
        return Map.copyOf(factories);
    }

    /// Locates the configured header command without package-private shell test access.
    ///
    /// @param root shell component tree root
    /// @return named file-tool button
    private static JButton findFileTool(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton button && "shellFileTool".equals(button.getName())) {
                return button;
            }
            if (child instanceof Container container) {
                @Nullable JButton nested = findFileToolOrNull(container);
                if (nested != null) {
                    return nested;
                }
            }
        }
        throw new AssertionError("shellFileTool button was not found");
    }

    /// Recursively searches a component subtree for the named header command.
    ///
    /// @param root component subtree
    /// @return named button, or null when absent
    private static @Nullable JButton findFileToolOrNull(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton button && "shellFileTool".equals(button.getName())) {
                return button;
            }
            if (child instanceof Container container) {
                @Nullable JButton nested = findFileToolOrNull(container);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    /// Creates a standard Swing file-list transfer for the given immutable files.
    ///
    /// @param files local file payload
    /// @return transfer support bound to a lightweight component
    private static TransferHandler.TransferSupport transfer(@Unmodifiable List<File> files) {
        return new TransferHandler.TransferSupport(new JPanel(), new FileListTransferable(files));
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
            // The launcher never forces a user-confirmed close on the headless fake.
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

    /// Immutable standard Java file-list transferable used by launcher entry tests.
    @NotNullByDefault
    private static final class FileListTransferable implements Transferable {
        /// Immutable local file payload.
        private final @Unmodifiable List<File> files;

        /// Creates one immutable payload.
        ///
        /// @param files local files to expose
        private FileListTransferable(@Unmodifiable List<File> files) {
            this.files = List.copyOf(files);
        }

        /// Returns a defensive supported-flavor array.
        ///
        /// @return one-element file-list flavor array
        @Override
        public DataFlavor @Unmodifiable [] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.javaFileListFlavor};
        }

        /// Reports whether one flavor is the Java file-list flavor.
        ///
        /// @param flavor requested flavor
        /// @return whether the flavor is supported
        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.javaFileListFlavor.equals(flavor);
        }

        /// Returns the immutable file list.
        ///
        /// @param flavor requested flavor
        /// @return immutable local files
        /// @throws UnsupportedFlavorException when the flavor is unsupported
        /// @throws IOException never thrown by this in-memory payload
        @Override
        public Object getTransferData(DataFlavor flavor)
                throws UnsupportedFlavorException, IOException {
            if (!isDataFlavorSupported(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            return files;
        }
    }
}
