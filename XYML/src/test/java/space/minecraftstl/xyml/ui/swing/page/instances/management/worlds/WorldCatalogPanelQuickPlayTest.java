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
package space.minecraftstl.xyml.ui.swing.page.instances.management.worlds;

import com.formdev.flatlaf.FlatClientProperties;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.game.launch.LaunchSession;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.choice.ChoicePage;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;
import space.minecraftstl.xyml.util.platform.ManagedProcess;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies native world quick-play controls, immutable folder capture, and asynchronous completion handling.
@NotNullByDefault
final class WorldCatalogPanelQuickPlayTest {
    /// Quick launch and script generation keep preparation off the component model and restore controls on completion.
    @Test
    void delegatesQuickPlayAndScriptWithoutBlockingTheEdt() throws IOException {
        Path worldPath = Path.of("build", "test-worlds", "World Folder").toAbsolutePath().normalize();
        Path scriptPath = Path.of("build", "quick-play.bat").toAbsolutePath().normalize();
        WorldCatalogItem world = new WorldCatalogItem(
                worldPath,
                "World Folder",
                "Displayed World",
                1L,
                "1.21.1",
                false,
                null);
        ImmediateWorldCatalogModel model = new ImmediateWorldCatalogModel(world);
        RecordingInteractions interactions = new RecordingInteractions(scriptPath);
        CompletableFuture<ManagedProcess> launchCompletion = new CompletableFuture<>();
        CompletableFuture<Path> scriptCompletion = new CompletableFuture<>();
        AtomicReference<@Nullable String> launchedWorld = new AtomicReference<>();
        AtomicReference<@Nullable String> scriptedWorld = new AtomicReference<>();
        AtomicReference<@Nullable Path> scriptedDestination = new AtomicReference<>();
        WorldQuickPlayActions actions = WorldQuickPlayActions.available(
                worldFolder -> {
                    assertTrue(SwingUtilities.isEventDispatchThread());
                    launchedWorld.set(worldFolder);
                    return launchSession(launchCompletion);
                },
                (worldFolder, destination) -> {
                    assertTrue(SwingUtilities.isEventDispatchThread());
                    scriptedWorld.set(worldFolder);
                    scriptedDestination.set(destination);
                    return scriptCompletion;
                });
        AtomicReference<@Nullable WorldCatalogPanel> panelReference = new AtomicReference<>();
        AtomicReference<@Nullable BufferedImage> renderedReference = new AtomicReference<>();

        EdtDispatcher.executeAndWait(() -> {
            WorldCatalogPanel panel = new WorldCatalogPanel(
                    model,
                    WorldCatalogStrings.english(),
                    interactions,
                    actions);
            panelReference.set(panel);
            panel.setSize(960, 620);
            layoutRecursively(panel);
            panel.choiceList().setSize(new Dimension(320, 160));
            panel.choiceList().doLayout();
            panel.choiceList().getViewport().setExtentSize(new Dimension(320, 160));
            panel.choiceList().refreshLoadPlan();
            panel.choiceList().getList().setSelectedIndex(0);
            layoutRecursively(panel);

            JButton quickPlay = findNamed(panel, "worldsQuickPlay", JButton.class);
            JButton launchScript = findNamed(panel, "worldsLaunchScript", JButton.class);
            JButton chunkBase = findNamed(panel, "worldsChunkBase", JButton.class);
            JCheckBox showAll = findNamed(panel, "worldsShowAll", JCheckBox.class);
            JLabel directory = findNamed(panel, "worldsDirectory", JLabel.class);
            JLabel path = findNamed(panel, "worldsPath", JLabel.class);
            assertNotNull(quickPlay.getIcon());
            assertNotNull(launchScript.getIcon());
            assertNotNull(chunkBase.getIcon());
            assertTrue(chunkBase.isEnabled());
            assertTrue(showAll.isVisible());
            assertFalse(showAll.isSelected());
            showAll.doClick();
            assertTrue(model.showAll());
            assertEquals("World Folder", directory.getText());
            assertEquals(worldPath.toString(), path.getText());
            assertTrue(directory.getWidth() > 50, directory.getBounds().toString());
            assertTrue(path.getWidth() > 50, path.getBounds().toString());
            assertTrue(directory.getHeight() > 10, directory.getBounds().toString());
            assertTrue(path.getHeight() > 10, path.getBounds().toString());
            assertEquals(new Dimension(40, 40), quickPlay.getPreferredSize());
            assertTrue(quickPlay.isEnabled());
            assertTrue(launchScript.isEnabled());
            Rectangle quickPlayBounds = SwingUtilities.convertRectangle(
                    quickPlay.getParent(),
                    quickPlay.getBounds(),
                    panel);
            Rectangle launchScriptBounds = SwingUtilities.convertRectangle(
                    launchScript.getParent(),
                    launchScript.getBounds(),
                    panel);
            assertFalse(quickPlayBounds.intersects(launchScriptBounds));
            BufferedImage rendered = render(panel);
            assertTrue(countOpaquePixels(rendered) > 10_000L);
            renderedReference.set(rendered);

            quickPlay.doClick();

            assertEquals("World Folder", launchedWorld.get());
            assertFalse(quickPlay.isEnabled());
            assertFalse(launchScript.isEnabled());
            assertEquals(
                    WorldCatalogStrings.english().launchingText(),
                    findNamed(panel, "worldsOperationStatus", JLabel.class).getText());
        });

        writeVisualReport(Objects.requireNonNull(renderedReference.get()));

        launchCompletion.complete(null);
        EdtDispatcher.executeAndWait(() -> { });

        EdtDispatcher.executeAndWait(() -> {
            WorldCatalogPanel panel = Objects.requireNonNull(panelReference.get());
            JButton quickPlay = findNamed(panel, "worldsQuickPlay", JButton.class);
            JButton launchScript = findNamed(panel, "worldsLaunchScript", JButton.class);
            assertTrue(quickPlay.isEnabled());
            assertTrue(launchScript.isEnabled());

            launchScript.doClick();

            assertEquals("World Folder", scriptedWorld.get());
            assertEquals(scriptPath, scriptedDestination.get());
            assertFalse(quickPlay.isEnabled());
            assertFalse(launchScript.isEnabled());
            assertEquals(
                    WorldCatalogStrings.english().generatingLaunchScriptText(),
                    findNamed(panel, "worldsOperationStatus", JLabel.class).getText());
        });

        scriptCompletion.complete(scriptPath);
        EdtDispatcher.executeAndWait(() -> { });

        EdtDispatcher.executeAndWait(() -> {
            WorldCatalogPanel panel = Objects.requireNonNull(panelReference.get());
            assertEquals(scriptPath, interactions.succeededScript());
            assertNull(interactions.failure());
            assertTrue(findNamed(panel, "worldsQuickPlay", JButton.class).isEnabled());
            assertEquals("", findNamed(panel, "worldsOperationStatus", JLabel.class).getText());
            panel.close();
        });
    }

    /// Keeps every world detail and action reachable when the management host has little vertical space.
    @Test
    void constrainedHeightScrollsTheCompleteWorldDetailsSurface() {
        Path worldPath = Path.of("build", "test-worlds", "compact-world").toAbsolutePath().normalize();
        WorldCatalogItem world = new WorldCatalogItem(
                worldPath,
                "compact-world",
                "Compact World",
                1L,
                "1.21.1",
                false,
                null);
        AtomicReference<@Nullable WorldCatalogPanel> panelReference = new AtomicReference<>();

        EdtDispatcher.executeAndWait(() -> {
            WorldCatalogPanel panel = new WorldCatalogPanel(
                    new ImmediateWorldCatalogModel(world),
                    WorldCatalogStrings.english(),
                    new RecordingInteractions(Path.of("build", "compact-world.bat")),
                    WorldQuickPlayActions.unavailable());
            panelReference.set(panel);
            panel.setSize(720, 980);
            layoutRecursively(panel);
            JScrollPane scroll = findNamed(panel, "worldsDetailsScroll", JScrollPane.class);
            int largeMaximum = scroll.getVerticalScrollBar().getMaximum();
            int largeVisibleAmount = scroll.getVerticalScrollBar().getVisibleAmount();

            panel.setSize(720, 280);
            panel.invalidate();
            layoutRecursively(panel);

            JPanel details = findNamed(panel, "worldsDetails", JPanel.class);
            JButton delete = findNamed(panel, "worldsDelete", JButton.class);
            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, scroll.getHorizontalScrollBarPolicy());
            assertFalse(scroll.isOpaque());
            assertFalse(scroll.getViewport().isOpaque());
            int compactVisibleAmount = scroll.getVerticalScrollBar().getVisibleAmount();
            assertTrue(scroll.getVerticalScrollBar().getMaximum() >= largeMaximum);
            assertTrue(compactVisibleAmount < largeVisibleAmount);
            assertTrue(
                    scroll.getVerticalScrollBar().getMaximum()
                            > compactVisibleAmount);

            int bottom = scroll.getVerticalScrollBar().getMaximum()
                    - scroll.getVerticalScrollBar().getVisibleAmount();
            scroll.getVerticalScrollBar().setValue(bottom);
            Rectangle deleteBounds = SwingUtilities.convertRectangle(
                    delete.getParent(),
                    delete.getBounds(),
                    details);
            assertTrue(scroll.getViewport().getViewRect().intersects(deleteBounds));
            assertTrue(panel.choiceList().getViewport().getExtentSize().height > 0);

            panel.setSize(720, 980);
            panel.invalidate();
            layoutRecursively(panel);
            assertTrue(scroll.getVerticalScrollBar().getVisibleAmount() > compactVisibleAmount);
            panel.close();
        });
        assertNotNull(panelReference.get());
    }

    /// Restored detail controls submit one complete update and target the exact world level data.
    @Test
    void editsWorldDetailsIconAndExactLevelDataPath() throws IOException {
        Path worldPath = Path.of("build", "test-worlds", "editable-world").toAbsolutePath().normalize();
        WorldCatalogDetails details = new WorldCatalogDetails(
                worldPath.resolve("level.dat"),
                encodedIcon(),
                123456L,
                "(10, 70, -5)",
                24_000L,
                new WorldCatalogDetails.WorldSettings(
                        false,
                        true,
                        WorldCatalogDetails.Difficulty.NORMAL,
                        false),
                new WorldCatalogDetails.PlayerSummary(
                        "(1.00, 65.00, 2.00)",
                        "(3, 40, 4)",
                        "(5, 70, 6)",
                        WorldCatalogDetails.GameMode.SURVIVAL,
                        20.0F,
                        18,
                        4.0F,
                        7));
        WorldCatalogItem world = new WorldCatalogItem(
                worldPath,
                "editable-world",
                "Editable World",
                1L,
                "1.21.1",
                false,
                null,
                details);
        ImmediateWorldCatalogModel model = new ImmediateWorldCatalogModel(world);
        RecordingInteractions interactions = new RecordingInteractions(Path.of("build", "unused.bat"));

        EdtDispatcher.executeAndWait(() -> {
            WorldCatalogPanel panel = new WorldCatalogPanel(
                    model,
                    WorldCatalogStrings.english(),
                    interactions,
                    WorldQuickPlayActions.unavailable());
            panel.setSize(900, 980);
            layoutRecursively(panel);
            panel.choiceList().setSize(new Dimension(320, 180));
            panel.choiceList().doLayout();
            panel.choiceList().getViewport().setExtentSize(new Dimension(320, 180));
            panel.choiceList().refreshLoadPlan();
            panel.choiceList().getList().setSelectedIndex(0);

            JPasswordField seed = findNamed(panel, "worldsSeed", JPasswordField.class);
            assertEquals("123456", new String(seed.getPassword()));
            assertTrue(seed.getEchoChar() != 0);
            assertEquals("showRevealButton: true", seed.getClientProperty(FlatClientProperties.STYLE));
            assertEquals("(10, 70, -5)", findNamed(panel, "worldsSpawn", JLabel.class).getText());
            assertNotNull(findNamed(panel, "worldsIcon", JLabel.class).getIcon());
            JTextField name = findNamed(panel, "worldsWorldName", JTextField.class);
            JCheckBox cheats = findNamed(panel, "worldsAllowCheats", JCheckBox.class);
            JCheckBox structures = findNamed(panel, "worldsGenerateStructures", JCheckBox.class);
            JComboBox<?> difficulty = findNamed(panel, "worldsDifficulty", JComboBox.class);
            JCheckBox difficultyLocked = findNamed(panel, "worldsDifficultyLocked", JCheckBox.class);
            JComboBox<?> gameMode = findNamed(panel, "worldsPlayerGameMode", JComboBox.class);
            JTextField health = findNamed(panel, "worldsPlayerHealth", JTextField.class);
            JTextField food = findNamed(panel, "worldsPlayerFoodLevel", JTextField.class);
            JTextField saturation = findNamed(panel, "worldsPlayerFoodSaturation", JTextField.class);
            JTextField xp = findNamed(panel, "worldsPlayerXpLevel", JTextField.class);
            assertTrue(name.isEnabled());
            assertTrue(health.isEnabled());
            seed.dispatchEvent(new MouseEvent(
                    seed,
                    MouseEvent.MOUSE_CLICKED,
                    1L,
                    0,
                    1,
                    1,
                    2,
                    false,
                    MouseEvent.BUTTON1));
            assertEquals("123456", interactions.copiedText());

            name.setText("Renamed World");
            cheats.setSelected(true);
            structures.setSelected(false);
            difficulty.setSelectedItem(WorldCatalogDetails.Difficulty.HARD);
            difficultyLocked.setSelected(true);
            gameMode.setSelectedItem(WorldCatalogDetails.GameMode.CREATIVE);
            health.setText("16.5");
            food.setText("12");
            saturation.setText("2.5");
            xp.setText("19");
            findNamed(panel, "worldsSaveDetails", JButton.class).doClick();

            WorldDetailsUpdate update = Objects.requireNonNull(model.detailsUpdate());
            assertEquals(world, model.updatedWorld());
            assertEquals("Renamed World", update.worldName());
            assertEquals(Boolean.TRUE, update.settings().allowCheats());
            assertEquals(Boolean.FALSE, update.settings().generateStructures());
            assertEquals(WorldCatalogDetails.Difficulty.HARD, update.settings().difficulty());
            assertEquals(Boolean.TRUE, update.settings().difficultyLocked());
            WorldDetailsUpdate.PlayerUpdate player = Objects.requireNonNull(update.player());
            assertEquals(WorldCatalogDetails.GameMode.CREATIVE, player.gameMode());
            assertEquals(16.5F, player.health());
            assertEquals(12, player.foodLevel());
            assertEquals(2.5F, player.foodSaturation());
            assertEquals(19, player.xpLevel());

            findNamed(panel, "worldsChangeIcon", JButton.class).doClick();
            assertEquals(world, model.iconWorld());
            assertEquals(interactions.iconSource(), model.iconSource());
            findNamed(panel, "worldsResetIcon", JButton.class).doClick();
            assertEquals(world, model.resetIconWorld());
            findNamed(panel, "worldsEditLevelData", JButton.class).doClick();
            assertEquals(details.levelDataPath(), interactions.openedLevelData());
            panel.close();
        });

        assertTrue(interactions.closed());
    }

    /// Encodes one deterministic 64-by-64 icon for the immutable UI fixture.
    ///
    /// @return Base64 PNG text
    private static String encodedIcon() throws IOException {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(2, 3, 0xFF2468AC);
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "PNG", output));
        return java.util.Base64.getEncoder().encodeToString(output.toByteArray());
    }

    /// Creates the minimal launch session proxy used by the panel's completion observer.
    ///
    /// @param completion controllable process-preparation completion
    /// @return launch session exposing only the completion method used by this surface
    private static LaunchSession launchSession(CompletionStage<ManagedProcess> completion) {
        return LaunchSession.class.cast(Proxy.newProxyInstance(
                LaunchSession.class.getClassLoader(),
                new Class<?>[]{LaunchSession.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "completion" -> completion;
                    case "toString" -> "WorldQuickPlayLaunchSession";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == Objects.requireNonNull(arguments)[0];
                    default -> throw new AssertionError("Unexpected launch-session call: " + method.getName());
                }));
    }

    /// Recursively lays out one offscreen component tree before pixel verification.
    ///
    /// @param container root or nested container
    private static void layoutRecursively(Container container) {
        container.doLayout();
        for (Component component : container.getComponents()) {
            if (component instanceof Container child) {
                layoutRecursively(child);
            }
        }
    }

    /// Paints one fully laid-out world page into a stable offscreen image.
    ///
    /// @param panel world catalog panel
    /// @return rendered ARGB pixels
    private static BufferedImage render(WorldCatalogPanel panel) {
        BufferedImage image = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            panel.paint(graphics);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    /// Optionally writes visual evidence outside the checkout when the test environment requests it.
    ///
    /// @param image rendered page image
    /// @throws IOException when the requested report cannot be written
    private static void writeVisualReport(BufferedImage image) throws IOException {
        @Nullable String requestedPath = System.getenv("XYML_WORLD_QUICK_PLAY_SCREENSHOT");
        if (requestedPath == null || requestedPath.isBlank()) {
            return;
        }
        Path report = Path.of(requestedPath).toAbsolutePath().normalize();
        @Nullable Path parent = report.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        assertTrue(ImageIO.write(Objects.requireNonNull(image, "image"), "PNG", report.toFile()));
    }

    /// Counts non-transparent rendered pixels for a nonblank-surface assertion.
    ///
    /// @param image rendered page
    /// @return opaque or translucent pixel count
    private static long countOpaquePixels(BufferedImage image) {
        long count = 0L;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    count++;
                }
            }
        }
        return count;
    }

    /// Finds one deterministically named Swing descendant.
    ///
    /// @param root component tree root
    /// @param name stable component name
    /// @param type requested component type
    /// @param <T> component type
    /// @return matching component
    private static <T extends JComponent> T findNamed(Container root, String name, Class<T> type) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component) && name.equals(component.getName())) {
                return type.cast(component);
            }
            if (component instanceof Container child) {
                @Nullable T nested = findNamedOrNull(child, name, type);
                if (nested != null) {
                    return nested;
                }
            }
        }
        throw new AssertionError("Missing component: " + name);
    }

    /// Finds one optionally named descendant during recursive traversal.
    ///
    /// @param root component tree root
    /// @param name stable component name
    /// @param type requested component type
    /// @param <T> component type
    /// @return matching component, or null when absent
    private static <T extends JComponent> @Nullable T findNamedOrNull(
            Container root,
            String name,
            Class<T> type) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component) && name.equals(component.getName())) {
                return type.cast(component);
            }
            if (component instanceof Container child) {
                @Nullable T nested = findNamedOrNull(child, name, type);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    /// Immediate one-row model for deterministic viewport loading.
    @NotNullByDefault
    private static final class ImmediateWorldCatalogModel implements WorldCatalogModel {
        /// Exact loaded world row.
        private final WorldCatalogItem world;

        /// Stable ready snapshot.
        private final WorldCatalogSnapshot snapshot = new WorldCatalogSnapshot(
                OptionalInt.of(1),
                0L,
                WorldCatalogStatus.READY,
                "1 world",
                "",
                true,
                true,
                false);

        /// Mutable Show All state used to verify panel-to-model wiring.
        private boolean showAll;

        /// World most recently submitted for detail editing.
        private @Nullable WorldCatalogItem updatedWorld;

        /// Detail values most recently submitted.
        private @Nullable WorldDetailsUpdate detailsUpdate;

        /// World most recently submitted for icon replacement.
        private @Nullable WorldCatalogItem iconWorld;

        /// Icon source most recently submitted.
        private @Nullable Path iconSource;

        /// World most recently submitted for icon reset.
        private @Nullable WorldCatalogItem resetIconWorld;

        /// Creates one immediate model.
        ///
        /// @param world exact world row
        private ImmediateWorldCatalogModel(WorldCatalogItem world) {
            this.world = Objects.requireNonNull(world, "world");
        }

        /// Exposes version filtering so the production checkbox is visible in the fixture.
        ///
        /// @return true
        @Override
        public boolean supportsVersionFiltering() {
            return true;
        }

        /// Returns the state last selected through the panel checkbox.
        ///
        /// @return current Show All state
        @Override
        public boolean showAll() {
            return showAll;
        }

        /// Records the state selected through the panel checkbox.
        ///
        /// @param replacement whether every world should be visible
        @Override
        public void setShowAll(boolean replacement) {
            showAll = replacement;
        }

        /// Returns the stable ready snapshot.
        @Override
        public WorldCatalogSnapshot snapshot() {
            return snapshot;
        }

        /// Returns a removable no-op subscription because this fixture never changes.
        @Override
        public Subscription subscribe(ValueChangeListener<WorldCatalogSnapshot> listener) {
            Objects.requireNonNull(listener, "listener");
            return Subscription.create(() -> { });
        }

        /// Returns the test saves directory.
        @Override
        public Path savesDirectory() {
            return Objects.requireNonNull(world.path().getParent());
        }

        /// Performs no loading because the fixture starts ready.
        @Override
        public void loadIfNeeded() {
        }

        /// Performs no refresh because the fixture is immutable.
        @Override
        public void refresh() {
        }

        /// Rejects unused import inspection.
        @Override
        public CompletionStage<WorldCatalogImport> inspectImport(Path archive) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        /// Rejects unused world installation.
        @Override
        public CompletionStage<WorldCatalogSnapshot> installWorld(WorldCatalogImport candidate, String targetName) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        /// Rejects unused world deletion.
        @Override
        public CompletionStage<WorldCatalogSnapshot> deleteWorld(WorldCatalogItem selectedWorld) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        /// Records one immediate detail update.
        @Override
        public CompletionStage<WorldCatalogSnapshot> updateWorldDetails(
                WorldCatalogItem selectedWorld,
                WorldDetailsUpdate update) {
            updatedWorld = Objects.requireNonNull(selectedWorld, "selectedWorld");
            detailsUpdate = Objects.requireNonNull(update, "update");
            return CompletableFuture.completedFuture(snapshot);
        }

        /// Records one immediate icon replacement.
        @Override
        public CompletionStage<WorldCatalogSnapshot> replaceWorldIcon(
                WorldCatalogItem selectedWorld,
                Path source) {
            iconWorld = Objects.requireNonNull(selectedWorld, "selectedWorld");
            iconSource = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
            return CompletableFuture.completedFuture(snapshot);
        }

        /// Records one immediate icon reset.
        @Override
        public CompletionStage<WorldCatalogSnapshot> resetWorldIcon(WorldCatalogItem selectedWorld) {
            resetIconWorld = Objects.requireNonNull(selectedWorld, "selectedWorld");
            return CompletableFuture.completedFuture(snapshot);
        }

        /// Returns the exact one-row count.
        @Override
        public OptionalInt exactItemCount() {
            return OptionalInt.of(1);
        }

        /// Immediately supplies the exact requested row range.
        @Override
        public CompletionStage<ChoicePage<WorldCatalogItem>> load(
                IndexRange desiredRange,
                LoadCancellation cancellation) {
            cancellation.throwIfCancelled();
            @Unmodifiable List<WorldCatalogItem> items = desiredRange.isEmpty()
                    ? List.of()
                    : List.of(world);
            return CompletableFuture.completedFuture(new ChoicePage<>(
                    desiredRange,
                    items,
                    OptionalInt.of(1),
                    desiredRange.endExclusive() == 1));
        }

        /// Releases no resources because this fixture owns none.
        @Override
        public void close() {
        }

        /// Returns the most recently detail-edited world.
        ///
        /// @return edited world, or null before editing
        private @Nullable WorldCatalogItem updatedWorld() {
            return updatedWorld;
        }

        /// Returns the most recently submitted detail values.
        ///
        /// @return detail update, or null before editing
        private @Nullable WorldDetailsUpdate detailsUpdate() {
            return detailsUpdate;
        }

        /// Returns the most recently icon-edited world.
        ///
        /// @return icon world, or null before replacement
        private @Nullable WorldCatalogItem iconWorld() {
            return iconWorld;
        }

        /// Returns the most recently submitted icon source.
        ///
        /// @return icon source, or null before replacement
        private @Nullable Path iconSource() {
            return iconSource;
        }

        /// Returns the most recently icon-reset world.
        ///
        /// @return reset world, or null before reset
        private @Nullable WorldCatalogItem resetIconWorld() {
            return resetIconWorld;
        }
    }

    /// Records script chooser feedback while avoiding native dialogs and desktop calls.
    @NotNullByDefault
    private static final class RecordingInteractions implements WorldCatalogInteractions {
        /// Script destination returned by the chooser.
        private final Path destination;

        /// Successfully reported script path, or null before success.
        private @Nullable Path succeededScript;

        /// Reported failure detail, or null without failure.
        private @Nullable String failure;

        /// Deterministic world-icon source returned by the chooser.
        private final Path iconSource = Path.of("build", "selected-world-icon.png")
                .toAbsolutePath()
                .normalize();

        /// Exact level-data path most recently opened.
        private @Nullable Path openedLevelData;

        /// Exact world-detail text most recently copied.
        private @Nullable String copiedText;

        /// Whether panel closure released this interaction boundary.
        private boolean closed;

        /// Creates one recording interaction boundary.
        ///
        /// @param destination selected script destination
        private RecordingInteractions(Path destination) {
            this.destination = Objects.requireNonNull(destination, "destination");
        }

        /// Returns no archive because import is outside this test.
        @Override
        public @Nullable Path chooseWorldArchive(Component owner, Path currentDirectory) {
            return null;
        }

        /// Returns no world name because import is outside this test.
        @Override
        public @Nullable String chooseWorldName(Component owner, WorldCatalogImport world) {
            return null;
        }

        /// Rejects deletion because mutation is outside this test.
        @Override
        public boolean confirmDelete(Component owner, WorldCatalogItem world) {
            return false;
        }

        /// Returns no copy name because copying is outside this test.
        @Override
        public @Nullable String chooseCopyName(Component owner, WorldCatalogItem world) {
            return null;
        }

        /// Returns no export path because archive export is outside this test.
        @Override
        public @Nullable Path chooseExportArchive(Component owner, WorldCatalogItem world) {
            return null;
        }

        /// Returns the deterministic selected icon path.
        @Override
        public @Nullable Path chooseWorldIcon(Component owner, WorldCatalogItem world) {
            return iconSource;
        }

        /// Records the exact direct world level-data path.
        @Override
        public void openLevelData(Component owner, Path levelDataPath) {
            openedLevelData = Objects.requireNonNull(levelDataPath, "levelDataPath")
                    .toAbsolutePath()
                    .normalize();
        }

        /// Records exact copied world-detail text.
        @Override
        public void copyText(Component owner, String text) {
            copiedText = Objects.requireNonNull(text, "text");
        }

        /// Returns the configured local script destination.
        @Override
        public @Nullable Path chooseLaunchScriptDestination(Component owner, WorldCatalogItem world) {
            return destination;
        }

        /// Records successful script feedback.
        @Override
        public void launchScriptSucceeded(Component owner, Path scriptFile) {
            succeededScript = Objects.requireNonNull(scriptFile, "scriptFile");
        }

        /// Completes desktop requests immediately because they are outside this test.
        @Override
        public CompletionStage<@Nullable Void> openDirectory(Path directory) {
            return CompletableFuture.completedFuture(null);
        }

        /// Completes an unused Chunk Base request immediately.
        ///
        /// @param world selected world
        /// @param tool selected destination
        /// @return completed nullable-void stage
        @Override
        public CompletionStage<@Nullable Void> openChunkBase(WorldCatalogItem world, ChunkBaseTool tool) {
            return CompletableFuture.completedFuture(null);
        }

        /// Records unexpected visible failures.
        @Override
        public void showFailure(Component owner, String title, String detail) {
            failure = Objects.requireNonNull(detail, "detail");
        }

        /// Records release of the panel-owned interaction boundary.
        @Override
        public void close() {
            closed = true;
        }

        /// Returns the successfully reported script path.
        ///
        /// @return reported path, or null before success
        private @Nullable Path succeededScript() {
            return succeededScript;
        }

        /// Returns the unexpected visible failure detail.
        ///
        /// @return failure detail, or null without failure
        private @Nullable String failure() {
            return failure;
        }

        /// Returns the deterministic icon source.
        ///
        /// @return normalized icon source
        private Path iconSource() {
            return iconSource;
        }

        /// Returns the exact opened level-data path.
        ///
        /// @return opened path, or null before editing
        private @Nullable Path openedLevelData() {
            return openedLevelData;
        }

        /// Returns the exact copied world-detail text.
        ///
        /// @return copied text, or null before copying
        private @Nullable String copiedText() {
            return copiedText;
        }

        /// Returns whether panel closure released the boundary.
        ///
        /// @return closure state
        private boolean closed() {
            return closed;
        }
    }
}
