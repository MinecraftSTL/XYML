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
package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.game.GameRepository;
import space.minecraftstl.xyml.setting.InstanceIconType;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the overview's asynchronous repository metadata and local directory controls.
@NotNullByDefault
final class InstanceOverviewPanelTest {
    /// Temporary root used by the repository proxy.
    @TempDir
    private Path repositoryRoot;

    /// Displays resolved paths, refreshes the repository, and opens the selected instance directory.
    @Test
    void displaysPathsRefreshesAndOpensInstanceDirectory() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicInteger refreshCount = new AtomicInteger();
        RecordingInteractions interactions = new RecordingInteractions();
        AtomicReference<@Nullable InstanceOverviewPanel> panelReference = new AtomicReference<>();
        try {
            GameRepository repository = repository(refreshCount);
            EdtDispatcher.executeAndWait(() -> panelReference.set(new InstanceOverviewPanel(
                    repository,
                    "instance",
                    executor,
                    InstanceOverviewStrings.english(),
                    interactions)));
            InstanceOverviewPanel panel = Objects.requireNonNull(panelReference.get());
            awaitBackgroundWork(executor);

            EdtDispatcher.executeAndWait(() -> {
                JTextField root = findNamed(panel, "instanceOverviewRootDirectory", JTextField.class);
                JTextField game = findNamed(panel, "instanceOverviewGameDirectory", JTextField.class);
                JButton refresh = findNamed(panel, "instanceOverviewRefresh", JButton.class);
                JButton openInstanceDirectory = findNamed(
                        panel,
                        "instanceOverviewOpenInstanceDirectory",
                        JButton.class);
                JButton chooseIcon = findNamed(panel, "instanceOverviewChooseIcon", JButton.class);
                JButton deleteIcon = findNamed(panel, "instanceOverviewDeleteIcon", JButton.class);
                JButton exploreDirectories = findNamed(panel, "instanceOverviewExploreDirectories", JButton.class);
                assertNotNull(root);
                assertNotNull(game);
                assertNotNull(refresh);
                assertNotNull(openInstanceDirectory);
                assertNotNull(chooseIcon);
                assertNotNull(deleteIcon);
                assertNotNull(exploreDirectories);
                Path expectedInstanceDirectory = repositoryRoot.resolve("versions")
                        .resolve("instance")
                        .toAbsolutePath()
                        .normalize();
                Path expectedGameDirectory = repositoryRoot.resolve("game").toAbsolutePath().normalize();
                assertEquals(expectedInstanceDirectory.toString(), root.getText());
                assertEquals(expectedGameDirectory.toString(), game.getText());
                assertTrue(refresh.isEnabled());
                assertTrue(openInstanceDirectory.isEnabled());
                assertTrue(exploreDirectories.isEnabled());
                assertFalse(chooseIcon.isVisible());
                assertFalse(deleteIcon.isVisible());

                openInstanceDirectory.doClick();
                assertEquals(expectedInstanceDirectory, interactions.openedDirectory.get());
                assertNull(interactions.failureDetail.get());

                JPopupMenu directoryMenu = panel.directoryMenu();
                JMenuItem mods = findNamed(directoryMenu, "instanceOverviewBrowseMods", JMenuItem.class);
                JMenuItem logs = findNamed(directoryMenu, "instanceOverviewBrowseLogs", JMenuItem.class);
                assertNotNull(mods);
                assertNotNull(logs);
                mods.doClick();
                assertEquals(expectedGameDirectory.resolve("mods"), interactions.openedDirectory.get());

                refresh.doClick();
            });
            awaitBackgroundWork(executor);
            assertEquals(1, refreshCount.get());
        } finally {
            @Nullable InstanceOverviewPanel panel = panelReference.get();
            if (panel != null) {
                panel.close();
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Creates the minimum repository contract required by a generic overview.
    ///
    /// @param refreshCount counter observing explicit refresh requests
    /// @return repository proxy rooted in the temporary directory
    private GameRepository repository(AtomicInteger refreshCount) {
        return GameRepository.class.cast(Proxy.newProxyInstance(
                GameRepository.class.getClassLoader(),
                new Class<?>[]{GameRepository.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getVersionRoot" -> repositoryRoot.resolve("versions").resolve("instance");
                    case "getRunDirectory" -> repositoryRoot.resolve("game");
                    case "refreshInstances" -> {
                        refreshCount.incrementAndGet();
                        yield null;
                    }
                    case "toString" -> "OverviewTestGameRepository";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == Objects.requireNonNull(arguments)[0];
                    default -> throw new AssertionError(
                            "Overview used an unexpected repository method: " + method.getName());
                }));
    }

    /// Waits for one FIFO executor barrier and all EDT callbacks scheduled before that barrier.
    ///
    /// @param executor executor carrying the overview's background work
    /// @throws Exception when the executor cannot complete the barrier
    private static void awaitBackgroundWork(ExecutorService executor) throws Exception {
        executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
        EdtDispatcher.executeAndWait(() -> { });
    }

    /// Finds one named descendant of the requested Swing component type.
    ///
    /// @param root component tree root
    /// @param name stable component name
    /// @param type required component type
    /// @param <T> component type
    /// @return matching component, or `null` when absent
    private static <T extends JComponent> @Nullable T findNamed(
            Container root,
            String name,
            Class<T> type) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component) && name.equals(component.getName())) {
                return type.cast(component);
            }
            if (component instanceof Container child) {
                @Nullable T nested = findNamed(child, name, type);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    /// Deterministic native-interaction substitute recording overview effects without opening a desktop app.
    @NotNullByDefault
    private static final class RecordingInteractions implements InstanceOverviewInteractions {
        /// Last directory requested through the desktop boundary, or `null` before the first request.
        private final AtomicReference<@Nullable Path> openedDirectory = new AtomicReference<>();

        /// Last failure detail shown through the dialog boundary, or `null` when no failure occurred.
        private final AtomicReference<@Nullable String> failureDetail = new AtomicReference<>();

        /// Simulates cancellation because generic repositories intentionally expose no icon commands.
        ///
        /// @param owner unused chooser owner
        /// @param currentIconType unused current icon type
        /// @param hasCustomIcon unused custom-icon state
        /// @param initialDirectory unused initial directory
        /// @return always `null`
        @Override
        public @Nullable InstanceIconChoice chooseInstanceIcon(
                Component owner,
                InstanceIconType currentIconType,
                boolean hasCustomIcon,
                Path initialDirectory) {
            return null;
        }

        /// Simulates declining a custom-icon deletion request.
        ///
        /// @param owner unused dialog owner
        /// @param instanceId unused instance identifier
        /// @return always `false`
        @Override
        public boolean confirmDeleteIcon(Component owner, String instanceId) {
            return false;
        }

        /// Records one requested directory and succeeds immediately.
        ///
        /// @param directory directory to record
        /// @return already-complete successful stage
        @Override
        public CompletionStage<@Nullable Void> openDirectory(Path directory) {
            openedDirectory.set(directory);
            return CompletableFuture.completedFuture(null);
        }

        /// Records a failure that would otherwise appear in a modal dialog.
        ///
        /// @param owner unused dialog owner
        /// @param title unused failure title
        /// @param detail failure detail
        @Override
        public void showFailure(Component owner, String title, String detail) {
            failureDetail.set(detail);
        }
    }
}
