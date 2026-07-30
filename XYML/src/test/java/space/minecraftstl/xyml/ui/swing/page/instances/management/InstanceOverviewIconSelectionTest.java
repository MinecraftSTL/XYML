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

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies overview icon selection, background persistence, preview refresh, and interaction compatibility.
@NotNullByDefault
final class InstanceOverviewIconSelectionTest {
    /// Temporary repository and custom image root.
    @TempDir
    private @Nullable Path repositoryRoot;

    /// Replaces a custom image with a bundled type, publishes once, and refreshes the 40-pixel preview.
    @Test
    void selectsBuiltInIconAndRefreshesPreview() throws Exception {
        Path customImage = createSolidImage(repositoryRoot().resolve("custom.png"), Color.RED);
        RecordingIconStore iconStore = new RecordingIconStore(customImage);
        ChoiceInteractions interactions = new ChoiceInteractions(new InstanceIconChoice.BuiltIn(InstanceIconType.FORGE));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<@Nullable InstanceOverviewPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> panelReference.set(new InstanceOverviewPanel(
                    repository(),
                    "instance",
                    executor,
                    InstanceOverviewStrings.english(),
                    interactions,
                    iconStore)));
            InstanceOverviewPanel panel = Objects.requireNonNull(panelReference.get());
            awaitBackgroundWork(executor);

            EdtDispatcher.executeAndWait(() -> {
                JLabel preview = findNamed(panel, "instanceOverviewIconPreview", JLabel.class);
                JButton choose = findNamed(panel, "instanceOverviewChooseIcon", JButton.class);
                JButton delete = findNamed(panel, "instanceOverviewDeleteIcon", JButton.class);
                assertNotNull(preview);
                assertNotNull(choose);
                assertNotNull(delete);
                BufferedImage image = assertInstanceOf(BufferedImage.class, ((ImageIcon) preview.getIcon()).getImage());
                assertEquals(40, image.getWidth());
                assertEquals(40, image.getHeight());
                assertEquals(Color.RED.getRGB(), image.getRGB(20, 20));
                assertTrue(choose.isVisible());
                assertTrue(delete.isEnabled());
                choose.doClick();
            });

            awaitBackgroundWork(executor);
            awaitBackgroundWork(executor);

            assertEquals(InstanceIconType.FORGE, iconStore.selectedBuiltIn.get());
            assertNull(iconStore.state.get().customImage());
            assertEquals(1, iconStore.publishCount.get());
            assertFalse(iconStore.mutationOnEdt.get());
            assertTrue(iconStore.publishOnEdt.get());
            assertEquals(InstanceIconType.DEFAULT, interactions.currentType.get());
            assertTrue(interactions.sawCustomImage.get());
            assertNull(interactions.failureDetail.get());

            EdtDispatcher.executeAndWait(() -> {
                JLabel preview = findNamed(panel, "instanceOverviewIconPreview", JLabel.class);
                JButton delete = findNamed(panel, "instanceOverviewDeleteIcon", JButton.class);
                assertNotNull(preview);
                assertNotNull(delete);
                BufferedImage image = assertInstanceOf(BufferedImage.class, ((ImageIcon) preview.getIcon()).getImage());
                assertNotEquals(Color.RED.getRGB(), image.getRGB(20, 20));
                assertFalse(delete.isEnabled());
            });
        } finally {
            @Nullable InstanceOverviewPanel panel = panelReference.get();
            if (panel != null) {
                panel.close();
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Creates one solid PNG for deterministic custom preview checks.
    ///
    /// @param path target image path
    /// @param color fill color
    /// @return target path
    /// @throws IOException when the image cannot be saved
    private static Path createSolidImage(Path path, Color color) throws IOException {
        Path normalizedPath = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        Files.createDirectories(normalizedPath.getParent());
        BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Objects.requireNonNull(color, "color"));
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        } finally {
            graphics.dispose();
        }
        if (!ImageIO.write(image, "PNG", normalizedPath.toFile())) {
            throw new IOException("PNG writer is unavailable");
        }
        return normalizedPath;
    }

    /// Creates the minimum generic repository contract used by the overview paths.
    ///
    /// @return proxy repository rooted in the temporary directory
    private GameRepository repository() {
        return GameRepository.class.cast(Proxy.newProxyInstance(
                GameRepository.class.getClassLoader(),
                new Class<?>[]{GameRepository.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getVersionRoot" -> repositoryRoot().resolve("versions").resolve("instance");
                    case "getRunDirectory" -> repositoryRoot().resolve("game");
                    case "getGameVersion" -> Optional.empty();
                    case "refreshInstances" -> null;
                    case "toString" -> "IconSelectionGameRepository";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == Objects.requireNonNull(arguments)[0];
                    default -> throw new AssertionError(
                            "Overview used an unexpected repository method: " + method.getName());
                }));
    }

    /// Returns the JUnit-injected temporary repository root.
    ///
    /// @return non-null temporary repository root
    private Path repositoryRoot() {
        return Objects.requireNonNull(repositoryRoot, "repositoryRoot");
    }

    /// Waits for one FIFO executor barrier and all EDT callbacks scheduled before that barrier.
    ///
    /// @param executor executor carrying overview background work
    /// @throws Exception when the barrier cannot complete
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

    /// Deterministic icon store recording overview mutations and publication thread affinity.
    @NotNullByDefault
    private static final class RecordingIconStore implements InstanceIconStore {
        /// Current icon state returned by each snapshot load.
        private final AtomicReference<Snapshot> state;

        /// Last bundled type selected through the overview, or `null` before selection.
        private final AtomicReference<@Nullable InstanceIconType> selectedBuiltIn = new AtomicReference<>();

        /// Number of successful change publications.
        private final AtomicInteger publishCount = new AtomicInteger();

        /// Whether the repository mutation accidentally ran on the EDT.
        private final AtomicBoolean mutationOnEdt = new AtomicBoolean();

        /// Whether successful change publication ran on the EDT.
        private final AtomicBoolean publishOnEdt = new AtomicBoolean();

        /// Creates a store initially overridden by one custom image.
        ///
        /// @param customImage initial custom image
        private RecordingIconStore(Path customImage) {
            state = new AtomicReference<>(new Snapshot(InstanceIconType.DEFAULT, customImage));
        }

        /// Returns the latest recorded icon state.
        ///
        /// @return current immutable state
        @Override
        public Snapshot load() {
            return state.get();
        }

        /// Records bundled selection and removes the custom override atomically.
        ///
        /// @param iconType selected bundled type
        @Override
        public void selectBuiltIn(InstanceIconType iconType) {
            mutationOnEdt.set(javax.swing.SwingUtilities.isEventDispatchThread());
            selectedBuiltIn.set(iconType);
            state.set(new Snapshot(iconType, null));
        }

        /// Records custom image selection for completeness.
        ///
        /// @param sourceImage selected custom source
        @Override
        public void selectCustom(Path sourceImage) {
            mutationOnEdt.set(javax.swing.SwingUtilities.isEventDispatchThread());
            state.set(new Snapshot(InstanceIconType.DEFAULT, sourceImage));
        }

        /// Removes the current custom override.
        @Override
        public void deleteCustom() {
            mutationOnEdt.set(javax.swing.SwingUtilities.isEventDispatchThread());
            Snapshot current = state.get();
            state.set(new Snapshot(current.builtInType(), null));
        }

        /// Records one successful publication and its thread affinity.
        ///
        /// @param source transition source
        @Override
        public void publishChanged(Object source) {
            Objects.requireNonNull(source, "source");
            publishCount.incrementAndGet();
            publishOnEdt.set(javax.swing.SwingUtilities.isEventDispatchThread());
        }
    }

    /// Deterministic complete icon chooser result with no native desktop effects.
    @NotNullByDefault
    private static final class ChoiceInteractions implements InstanceOverviewInteractions {
        /// Choice returned by the complete icon selector.
        private final InstanceIconChoice choice;

        /// Current built-in type observed by the selector.
        private final AtomicReference<@Nullable InstanceIconType> currentType = new AtomicReference<>();

        /// Whether the selector observed a custom image override.
        private final AtomicBoolean sawCustomImage = new AtomicBoolean();

        /// Last failure detail, or `null` when no failure was reported.
        private final AtomicReference<@Nullable String> failureDetail = new AtomicReference<>();

        /// Creates deterministic interactions returning one choice.
        ///
        /// @param choice completed icon choice
        private ChoiceInteractions(InstanceIconChoice choice) {
            this.choice = Objects.requireNonNull(choice, "choice");
        }

        /// Records current state and returns the configured complete choice.
        ///
        /// @param owner unused owner
        /// @param currentIconType current bundled type
        /// @param hasCustomIcon custom override state
        /// @param initialDirectory unused initial directory
        /// @return configured choice
        @Override
        public InstanceIconChoice chooseInstanceIcon(
                Component owner,
                InstanceIconType currentIconType,
                boolean hasCustomIcon,
                Path initialDirectory) {
            currentType.set(currentIconType);
            sawCustomImage.set(hasCustomIcon);
            return choice;
        }

        /// Declines custom deletion.
        ///
        /// @param owner unused owner
        /// @param instanceId unused instance identifier
        /// @return always `false`
        @Override
        public boolean confirmDeleteIcon(Component owner, String instanceId) {
            return false;
        }

        /// Completes local directory opening immediately.
        ///
        /// @param directory unused directory
        /// @return completed successful stage
        @Override
        public CompletionStage<@Nullable Void> openDirectory(Path directory) {
            return CompletableFuture.completedFuture(null);
        }

        /// Records a surfaced operational failure.
        ///
        /// @param owner unused owner
        /// @param title unused title
        /// @param detail failure detail
        @Override
        public void showFailure(Component owner, String title, String detail) {
            failureDetail.set(detail);
        }
    }

}
