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
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.game.launch.LaunchSession;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.property.SimpleObjectProperty;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.MotionPolicy;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.page.accounts.AccountsModel;
import space.minecraftstl.xyml.ui.swing.page.accounts.AccountsSnapshot;
import space.minecraftstl.xyml.ui.swing.page.home.HomeModel;
import space.minecraftstl.xyml.ui.swing.page.home.HomeSnapshot;
import space.minecraftstl.xyml.ui.swing.page.home.HomeStrings;
import space.minecraftstl.xyml.ui.swing.page.instances.InstancesModel;
import space.minecraftstl.xyml.ui.swing.page.instances.InstancesSnapshot;
import space.minecraftstl.xyml.ui.swing.page.settings.GameDirectoryManagementService;
import space.minecraftstl.xyml.ui.swing.page.settings.GameDirectoryManagementSnapshot;
import space.minecraftstl.xyml.ui.swing.shell.AppShellPanel;
import space.minecraftstl.xyml.ui.swing.shell.ShellFileDropHandler;
import space.minecraftstl.xyml.ui.swing.shell.ShellFileDropHandler.RouteRegistration;
import space.minecraftstl.xyml.ui.swing.shell.ShellPageFactory;
import space.minecraftstl.xyml.ui.swing.shell.ShellPageId;
import space.minecraftstl.xyml.ui.swing.shell.ShellPagePresentations;
import space.minecraftstl.xyml.ui.swing.shell.ShellToolbarModels;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.TransferHandler;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies JSON window reuse and coexistence with earlier shell drop routes.
@NotNullByDefault
final class SwingInstanceJsonImportLauncherTest {
    /// Routes JSON to the reusable import window while preserving and independently closing NBT.
    @Test
    void coexistsWithExistingRouteAndUnregistersIndependently() {
        AppShellPanel shell = createShell();
        List<Path> nbtSources = new ArrayList<>();
        RecordingWindowFactory windows = new RecordingWindowFactory();
        AtomicReference<@Nullable RouteRegistration> nbtRegistration =
                new AtomicReference<>();
        AtomicReference<@Nullable SwingInstanceJsonImportLauncher> launcherReference =
                new AtomicReference<>();

        EdtDispatcher.executeAndWait(() -> {
            nbtRegistration.set(ShellFileDropHandler.register(
                    shell,
                    path -> path.getFileName().toString().endsWith(".dat"),
                    nbtSources::add));
            SwingInstanceJsonImportLauncher launcher = SwingInstanceJsonImportLauncher.install(
                    shell,
                    windows);
            launcherReference.set(launcher);
            TransferHandler handler = Objects.requireNonNull(shell.getTransferHandler());

            assertTrue(handler.importData(transfer(List.of(new File("level.dat")))));
            assertTrue(handler.importData(transfer(List.of(new File("first.JSON")))));
            assertTrue(handler.importData(transfer(List.of(new File("second.json")))));
            assertFalse(handler.canImport(transfer(List.of(new File("notes.txt")))));
        });

        assertEquals(List.of(Path.of("level.dat").toAbsolutePath().normalize()), nbtSources);
        assertEquals(1, windows.windows().size());
        assertEquals(List.of(
                Path.of("first.JSON").toAbsolutePath().normalize(),
                Path.of("second.json").toAbsolutePath().normalize()),
                windows.windows().get(0).sources());
        assertEquals(2, windows.windows().get(0).showCount());

        Objects.requireNonNull(launcherReference.get()).close();
        EdtDispatcher.executeAndWait(() -> {
            TransferHandler remaining = Objects.requireNonNull(shell.getTransferHandler());
            assertFalse(remaining.canImport(transfer(List.of(new File("third.json")))));
            assertTrue(remaining.canImport(transfer(List.of(new File("other.dat")))));
            Objects.requireNonNull(nbtRegistration.get()).close();
            assertNull(shell.getTransferHandler());
        });
        shell.close();
    }

    /// Creates a complete headless shell on the EDT.
    ///
    /// @return initialized shell panel
    private static AppShellPanel createShell() {
        AtomicReference<@Nullable AppShellPanel> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> result.set(new AppShellPanel(
                "XYML",
                pageFactories(),
                ShellPagePresentations.englishFallback(),
                new ShellToolbarModels(
                        homeModel(),
                        instancesModel(),
                        accountsModel(),
                        gameDirectories(),
                        space.minecraftstl.xyml.ui.swing.shell.ShellRecentSelections.transientSelections()),
                new HomeStrings(
                        "Home",
                        "Account",
                        "None",
                        "Instance",
                        "None",
                        "Add",
                        "Export",
                        "Launch",
                        "Launching",
                        "Back"),
                TaskProgressStrings.english(),
                new SwingAnimator(MotionPolicy.OFF, 16),
                Duration.ZERO,
                Duration.ZERO)));
        return Objects.requireNonNull(result.get(), "shell was not created");
    }

    /// Creates the launcher-state fixture required by the persistent title bar.
    ///
    /// @return empty launcher model with independently removable subscriptions
    private static HomeModel homeModel() {
        HomeSnapshot snapshot = new HomeSnapshot("", "", "", "", "", false, false, true);
        SimpleObjectProperty<Optional<LaunchSession>> launchSessions =
                new SimpleObjectProperty<>(Optional.empty());
        Object proxy = Proxy.newProxyInstance(
                HomeModel.class.getClassLoader(),
                new Class<?>[]{HomeModel.class},
                (ignoredProxy, method, ignoredArguments) -> switch (method.getName()) {
                    case "snapshot" -> snapshot;
                    case "subscribe" -> Subscription.create(() -> {
                    });
                    case "launchSessionProperty" -> launchSessions;
                    default -> throw new AssertionError("Unexpected home-model call: " + method.getName());
                });
        return HomeModel.class.cast(proxy);
    }

    /// Creates the empty lazy instance source required by the persistent title bar.
    ///
    /// @return empty instance model that never starts a range request
    private static InstancesModel instancesModel() {
        InstancesSnapshot snapshot = new InstancesSnapshot(
                OptionalInt.empty(),
                0,
                0L,
                "",
                false,
                false,
                false,
                false,
                false);
        Object proxy = Proxy.newProxyInstance(
                InstancesModel.class.getClassLoader(),
                new Class<?>[]{InstancesModel.class},
                (ignoredProxy, method, ignoredArguments) -> switch (method.getName()) {
                    case "snapshot" -> snapshot;
                    case "subscribe" -> Subscription.create(() -> {
                    });
                    case "exactItemCount" -> OptionalInt.of(0);
                    case "sourceRevision" -> OptionalLong.of(0L);
                    case "selectionContextRevision" -> 0L;
                    case "stableItemIds" -> List.of();
                    default -> throw new AssertionError("Unexpected instances-model call: " + method.getName());
                });
        return InstancesModel.class.cast(proxy);
    }

    /// Creates the empty lazy account source required by the title-bar selector.
    ///
    /// @return empty account model that never starts a range request
    private static AccountsModel accountsModel() {
        AccountsSnapshot snapshot = new AccountsSnapshot(OptionalInt.empty(), 0, 0L);
        Object proxy = Proxy.newProxyInstance(
                AccountsModel.class.getClassLoader(),
                new Class<?>[]{AccountsModel.class},
                (ignoredProxy, method, ignoredArguments) -> switch (method.getName()) {
                    case "snapshot" -> snapshot;
                    case "subscribe" -> Subscription.create(() -> {
                    });
                    case "exactItemCount" -> OptionalInt.of(0);
                    case "sourceRevision" -> OptionalLong.of(0L);
                    case "stableItemIds" -> List.of();
                    default -> throw new AssertionError("Unexpected accounts-model call: " + method.getName());
                });
        return AccountsModel.class.cast(proxy);
    }

    /// Creates the empty configured-directory source required by the title-bar selector.
    ///
    /// @return empty directory service with removable subscriptions
    private static GameDirectoryManagementService gameDirectories() {
        GameDirectoryManagementSnapshot snapshot = new GameDirectoryManagementSnapshot(0L, List.of());
        Object proxy = Proxy.newProxyInstance(
                GameDirectoryManagementService.class.getClassLoader(),
                new Class<?>[]{GameDirectoryManagementService.class},
                (ignoredProxy, method, ignoredArguments) -> switch (method.getName()) {
                    case "snapshot" -> snapshot;
                    case "subscribe" -> Subscription.create(() -> {
                    });
                    case "close" -> null;
                    default -> throw new AssertionError("Unexpected directory-service call: " + method.getName());
                });
        return GameDirectoryManagementService.class.cast(proxy);
    }

    /// Creates one lightweight page factory for every permanent shell destination.
    ///
    /// @return complete immutable factory map
    private static @Unmodifiable Map<ShellPageId, ShellPageFactory<? extends JComponent>>
            pageFactories() {
        EnumMap<ShellPageId, ShellPageFactory<? extends JComponent>> factories =
                new EnumMap<>(ShellPageId.class);
        for (ShellPageId page : ShellPageId.values()) {
            factories.put(page, JPanel::new);
        }
        return Map.copyOf(factories);
    }

    /// Creates a standard Swing file-list transfer for immutable files.
    ///
    /// @param files local file payload
    /// @return transfer support bound to a lightweight panel
    private static TransferHandler.TransferSupport transfer(@Unmodifiable List<File> files) {
        return new TransferHandler.TransferSupport(new JPanel(), new FileListTransferable(files));
    }

    /// Records modeless import windows created by one launcher.
    @NotNullByDefault
    private static final class RecordingWindowFactory
            implements SwingInstanceJsonImportLauncher.ImportWindowFactory {
        /// Created windows in production order.
        private final List<RecordingWindow> createdWindows = new ArrayList<>();

        /// Creates and records one fake window.
        ///
        /// @param closedObserver terminal disposal observer
        /// @return fake import window
        @Override
        public SwingInstanceJsonImportLauncher.ImportWindow create(
                Consumer<SwingInstanceJsonImportLauncher.ImportWindow> closedObserver) {
            RecordingWindow window = new RecordingWindow(closedObserver);
            createdWindows.add(window);
            return window;
        }

        /// Returns an immutable created-window snapshot.
        ///
        /// @return created windows
        private @Unmodifiable List<RecordingWindow> windows() {
            return List.copyOf(createdWindows);
        }
    }

    /// Headless import window retaining sources, visibility count, and terminal disposal.
    @NotNullByDefault
    private static final class RecordingWindow
            implements SwingInstanceJsonImportLauncher.ImportWindow {
        /// Terminal disposal observer supplied by the launcher.
        private final Consumer<SwingInstanceJsonImportLauncher.ImportWindow> closedObserver;

        /// Sources opened in this reusable window.
        private final List<Path> openedSources = new ArrayList<>();

        /// Number of show-or-focus commands.
        private int showCount;

        /// Whether terminal disposal was already reported.
        private boolean closed;

        /// Creates one fake import window.
        ///
        /// @param closedObserver terminal observer
        private RecordingWindow(
                Consumer<SwingInstanceJsonImportLauncher.ImportWindow> closedObserver) {
            this.closedObserver = Objects.requireNonNull(closedObserver, "closedObserver");
        }

        /// Records one normalized JSON source.
        ///
        /// @param source normalized supported source
        @Override
        public void open(Path source) {
            openedSources.add(Objects.requireNonNull(source, "source"));
        }

        /// Records one show-or-focus command.
        @Override
        public void showOrFocus() {
            ++showCount;
        }

        /// Reports terminal disposal exactly once.
        @Override
        public void close() {
            if (!closed) {
                closed = true;
                closedObserver.accept(this);
            }
        }

        /// Returns an immutable source snapshot.
        ///
        /// @return opened sources
        private @Unmodifiable List<Path> sources() {
            return List.copyOf(openedSources);
        }

        /// Returns the show-or-focus command count.
        ///
        /// @return visibility request count
        private int showCount() {
            return showCount;
        }
    }

    /// Immutable standard Java file-list transferable.
    @NotNullByDefault
    private static final class FileListTransferable implements Transferable {
        /// Immutable local-file payload.
        private final @Unmodifiable List<File> files;

        /// Creates one payload.
        ///
        /// @param files local files to expose
        private FileListTransferable(@Unmodifiable List<File> files) {
            this.files = List.copyOf(files);
        }

        /// Returns a defensive supported-flavor array.
        ///
        /// @return file-list flavor array
        @Override
        public DataFlavor @Unmodifiable [] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.javaFileListFlavor};
        }

        /// Reports whether one flavor is the Java file-list flavor.
        ///
        /// @param flavor requested flavor
        /// @return whether supported
        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.javaFileListFlavor.equals(flavor);
        }

        /// Returns the immutable file list.
        ///
        /// @param flavor requested flavor
        /// @return local files
        /// @throws UnsupportedFlavorException when unsupported
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
