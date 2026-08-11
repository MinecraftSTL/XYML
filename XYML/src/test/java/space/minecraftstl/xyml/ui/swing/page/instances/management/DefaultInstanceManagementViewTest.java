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
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameRepository;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.game.launch.LaunchSession;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.property.ReadOnlyProperty;
import space.minecraftstl.xyml.observable.property.SimpleObjectProperty;
import space.minecraftstl.xyml.setting.GameDirectory;
import space.minecraftstl.xyml.setting.GameDirectoryID;
import space.minecraftstl.xyml.setting.GameSettings;
import space.minecraftstl.xyml.setting.GameSettingsPresetID;
import space.minecraftstl.xyml.setting.GameSettingsPresets;
import space.minecraftstl.xyml.setting.LauncherSettings;
import space.minecraftstl.xyml.setting.SettingsManager;
import space.minecraftstl.xyml.setting.UserSettings;
import space.minecraftstl.xyml.theme.ThemeBrightnessPreference;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingDesignTokens;
import space.minecraftstl.xyml.ui.swing.SwingThemeManager;
import space.minecraftstl.xyml.ui.swing.SystemThemeDetector;
import space.minecraftstl.xyml.ui.swing.application.SwingApplicationPresentation;
import space.minecraftstl.xyml.ui.swing.application.SwingApplicationPresentationFactory;
import space.minecraftstl.xyml.ui.swing.page.home.HomeModel;
import space.minecraftstl.xyml.ui.swing.page.home.HomeSnapshot;
import space.minecraftstl.xyml.ui.swing.page.instances.management.maintenance.InstanceMaintenanceLaunchActions;
import space.minecraftstl.xyml.ui.swing.page.instances.management.worlds.WorldQuickPlayActions;
import space.minecraftstl.xyml.ui.swing.page.mods.DefaultModCatalogInteractions;
import space.minecraftstl.xyml.ui.swing.page.mods.ModCatalogPanel;
import space.minecraftstl.xyml.ui.swing.page.mods.ModCatalogStatus;
import space.minecraftstl.xyml.ui.swing.page.resourcepacks.DefaultResourcePackCatalogInteractions;
import space.minecraftstl.xyml.ui.swing.page.schematics.DefaultSchematicBrowserInteractions;
import space.minecraftstl.xyml.util.PortablePath;
import space.minecraftstl.xyml.util.i18n.LocalizedText;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that production instance management exposes every recovered local-content tool.
@NotNullByDefault
final class DefaultInstanceManagementViewTest {
    /// Width available inside the launcher's preferred-size shell after its rail and horizontal gaps.
    private static final int RENDER_WIDTH = 1090;

    /// Height available inside the launcher's preferred-size shell after its toolbar and vertical gaps.
    private static final int RENDER_HEIGHT = 632;

    /// Width available to the workspace inside the launcher's minimum-size shell.
    private static final int COMPACT_WIDTH = 950;

    /// Height available below the minimum-size shell toolbar.
    private static final int COMPACT_HEIGHT = 472;

    /// Temporary repository root used by lazy filesystem adapters.
    @TempDir
    private Path repositoryRoot;

    /// Every supported destination is directly reachable while only the default overview is constructed initially.
    @Test
    void exposesEveryRecoveredManagementTabAsPersistentMainPage() throws InterruptedException {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicBoolean returned = new AtomicBoolean();
        AtomicReference<@Nullable DefaultInstanceManagementView> viewReference = new AtomicReference<>();
        try {
            SwingApplicationPresentation presentation = SwingApplicationPresentationFactory.create(
                    "XYML test",
                    Duration.ZERO,
                    Duration.ZERO);
            EdtDispatcher.executeAndWait(() -> viewReference.set(new DefaultInstanceManagementView(
                    homeModel(),
                    repository(),
                    ignored -> repositoryRoot.resolve("schematics"),
                    new GameInstanceID("instance"),
                    executor,
                    presentation.schematicManagement(),
                    presentation.schematics(),
                    new DefaultSchematicBrowserInteractions(
                            presentation.schematics().actions(),
                            executor),
                    presentation.mods(),
                    presentation.modsStatus(),
                    presentation.modsActions(),
                    new DefaultModCatalogInteractions(
                            presentation.modsActions(),
                            executor),
                    presentation.resourcePacks(),
                    presentation.resourcePacksStatus(),
                    presentation.resourcePacksActions(),
                    new DefaultResourcePackCatalogInteractions(
                            presentation.resourcePacksActions(),
                            executor),
                    () -> returned.set(true),
                    presentation.taskProgress(),
                    null,
                    Duration.ZERO,
                    unusedWorldQuickPlayActions(),
                    null)));
            DefaultInstanceManagementView view = Objects.requireNonNull(viewReference.get());

            EdtDispatcher.executeAndWait(() -> {
                InstanceManagementNavigationPanel navigation = findNamed(
                        view,
                        "instanceManagementNavigation",
                        InstanceManagementNavigationPanel.class);
                InstanceManagementPageDeck deck = findNamed(
                        view,
                        "instanceManagementPageDeck",
                        InstanceManagementPageDeck.class);
                JPanel summary = findNamed(view, "instanceWorkspaceSummary", JPanel.class);
                assertNotNull(navigation);
                assertNotNull(deck);
                assertNotNull(summary);
                assertFalse(view.isOpaque());
                assertFalse(summary.isOpaque());
                assertFalse(deck.isOpaque());
                assertEquals(new Dimension(0, 0), deck.getMinimumSize());
                assertEquals(List.of(
                        InstanceManagementPageId.OVERVIEW,
                        InstanceManagementPageId.MODS,
                        InstanceManagementPageId.RESOURCE_PACKS,
                        InstanceManagementPageId.WORLDS,
                        InstanceManagementPageId.DATA_PACKS,
                        InstanceManagementPageId.SCHEMATICS,
                        InstanceManagementPageId.BACKUPS,
                        InstanceManagementPageId.FILE_UPDATE_CHECK), navigation.availablePages());
                assertEquals(InstanceManagementPageId.OVERVIEW, navigation.selectedPage());
                assertEquals(InstanceManagementPageId.OVERVIEW, deck.selectedPage());
                assertEquals(1, deck.loadedPageCount());
                assertTrue(deck.isLoaded(InstanceManagementPageId.OVERVIEW));
                assertFalse(deck.isLoaded(InstanceManagementPageId.MODS));
                assertFalse(returned.get());
                assertNull(findNamed(view, "instanceManagementReturn", JComponent.class));
                assertNull(findNamed(view, "instanceManagementTitle", JLabel.class));
                JLabel name = findNamed(view, "instanceWorkspaceName", JLabel.class);
                JLabel status = findNamed(view, "instanceWorkspaceStatus", JLabel.class);
                assertNotNull(name);
                assertNotNull(status);
                assertEquals("Test instance", name.getText());
                assertEquals("Ready", status.getText());
                assertFalse(returned.get());
            });

            view.close();
            view.close();
            assertEquals(new GameInstanceID("instance"), view.instanceId());
        } finally {
            @Nullable DefaultInstanceManagementView view = viewReference.get();
            if (view != null) {
                view.close();
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// A real XYML repository exposes all destinations, renders both states, and scrolls at minimum shell height.
    @Test
    void rendersProductionWorkspaceWithoutEagerMaintenanceOrCompactOverflow()
            throws IOException, InterruptedException, ReflectiveOperationException {
        Field launcherSettingsField = SettingsManager.class.getDeclaredField("launcherSettings");
        Field gameSettingsPresetsField = SettingsManager.class.getDeclaredField("gameSettingsPresets");
        Field userSettingsField = SettingsManager.class.getDeclaredField("userSettingsInstance");
        launcherSettingsField.setAccessible(true);
        gameSettingsPresetsField.setAccessible(true);
        userSettingsField.setAccessible(true);
        @Nullable Object previousLauncherSettings = launcherSettingsField.get(null);
        @Nullable Object previousGameSettingsPresets = gameSettingsPresetsField.get(null);
        @Nullable Object previousUserSettings = userSettingsField.get(null);
        LauncherSettings temporarySettings = new LauncherSettings();
        GameSettingsPresetID presetId = GameSettingsPresetID.generate();
        GameSettingsPresets temporaryPresets = new GameSettingsPresets();
        temporaryPresets.getPresets().add(new GameSettings.Preset(presetId));
        temporarySettings.defaultGameSettingsPresetProperty().set(presetId);
        launcherSettingsField.set(null, temporarySettings);
        gameSettingsPresetsField.set(null, temporaryPresets);
        userSettingsField.set(null, new UserSettings());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<@Nullable DefaultInstanceManagementView> viewReference = new AtomicReference<>();
        try {
            initializeTestTheme();
            SwingApplicationPresentation presentation = SwingApplicationPresentationFactory.create(
                    "XYML test",
                    Duration.ZERO,
                    Duration.ZERO);
            createInstalledInstanceFixture();
            XYMLGameRepository repository = new XYMLGameRepository(new GameDirectory(
                    GameDirectoryID.generate(),
                    LocalizedText.plain("Maintenance test"),
                    PortablePath.of(repositoryRoot.toString())));
            GameInstanceID instanceId = new GameInstanceID("instance");
            repository.refresh();
            assertTrue(repository.hasInstance(instanceId));
            EdtDispatcher.executeAndWait(() -> viewReference.set(new DefaultInstanceManagementView(
                    homeModel(),
                    repository,
                    ignored -> repositoryRoot.resolve("schematics"),
                    instanceId,
                    executor,
                    presentation.schematicManagement(),
                    presentation.schematics(),
                    new DefaultSchematicBrowserInteractions(
                            presentation.schematics().actions(),
                            executor),
                    presentation.mods(),
                    presentation.modsStatus(),
                    presentation.modsActions(),
                    new DefaultModCatalogInteractions(
                            presentation.modsActions(),
                            executor),
                    presentation.resourcePacks(),
                    presentation.resourcePacksStatus(),
                    presentation.resourcePacksActions(),
                    new DefaultResourcePackCatalogInteractions(
                            presentation.resourcePacksActions(),
                            executor),
                    () -> { },
                    presentation.taskProgress(),
                    null,
                    Duration.ZERO,
                    unusedWorldQuickPlayActions(),
                    new UnusedMaintenanceLaunchActions())));
            DefaultInstanceManagementView view = Objects.requireNonNull(viewReference.get());
            awaitExecutor(executor);
            EdtDispatcher.executeAndWait(() -> { });

            BufferedImage background = loadDefaultBackground();
            AtomicReference<@Nullable BufferedImage> overviewImage = new AtomicReference<>();
            AtomicReference<@Nullable BufferedImage> modsImage = new AtomicReference<>();
            AtomicReference<@Nullable BufferedImage> compactImage = new AtomicReference<>();

            EdtDispatcher.executeAndWait(() -> {
                InstanceManagementNavigationPanel navigation = Objects.requireNonNull(findNamed(
                        view,
                        "instanceManagementNavigation",
                        InstanceManagementNavigationPanel.class));
                InstanceManagementPageDeck deck = Objects.requireNonNull(findNamed(
                        view,
                        "instanceManagementPageDeck",
                        InstanceManagementPageDeck.class));
                JPanel summary = Objects.requireNonNull(findNamed(
                        view,
                        "instanceWorkspaceSummary",
                        JPanel.class));
                JPanel workspaceBody = Objects.requireNonNull(findNamed(
                        view,
                        "instanceManagementWorkspaceBody",
                        JPanel.class));
                JScrollPane navigationScroll = Objects.requireNonNull(findNamed(
                        view,
                        "instanceManagementNavigationScroll",
                        JScrollPane.class));
                assertEquals(InstanceManagementPageId.orderedValues(), navigation.availablePages());
                assertFalse(deck.isLoaded(InstanceManagementPageId.MAINTENANCE_TOOLS));
                assertNull(findNamed(view, "instanceMaintenancePage", JComponent.class));
                assertEquals(1, deck.loadedPageCount());
                assertFalse(view.isOpaque());
                assertFalse(summary.isOpaque());
                assertFalse(workspaceBody.isOpaque());
                assertFalse(navigation.isOpaque());
                assertFalse(navigationScroll.isOpaque());
                assertFalse(navigationScroll.getViewport().isOpaque());
                assertFalse(deck.isOpaque());
                InstanceOverviewPanel overview = Objects.requireNonNull(findNamed(
                        view,
                        "instanceOverview",
                        InstanceOverviewPanel.class));
                assertFalse(overview.isOpaque());

                sizeAndLayout(view, RENDER_WIDTH, RENDER_HEIGHT);
                assertWorkspaceGeometry(view, summary, workspaceBody, navigation, deck);
                assertFalse(navigationScroll.getVerticalScrollBar().isVisible());
                BufferedImage renderedOverview = renderOverBackground(view, background);
                assertBackgroundVisibleAtNavigationGap(
                        view,
                        background,
                        renderedOverview,
                        workspaceBody,
                        navigation);
                overviewImage.set(renderedOverview);

                navigation.button(InstanceManagementPageId.MODS).doClick();
                assertEquals(InstanceManagementPageId.MODS, deck.selectedPage());
                assertTrue(deck.isLoaded(InstanceManagementPageId.MODS));
                assertEquals(2, deck.loadedPageCount());
            });

            awaitExecutor(executor);
            EdtDispatcher.executeAndWait(() -> { });
            EdtDispatcher.executeAndWait(() -> {
                InstanceManagementNavigationPanel navigation = Objects.requireNonNull(findNamed(
                        view,
                        "instanceManagementNavigation",
                        InstanceManagementNavigationPanel.class));
                InstanceManagementPageDeck deck = Objects.requireNonNull(findNamed(
                        view,
                        "instanceManagementPageDeck",
                        InstanceManagementPageDeck.class));
                JPanel summary = Objects.requireNonNull(findNamed(
                        view,
                        "instanceWorkspaceSummary",
                        JPanel.class));
                JPanel workspaceBody = Objects.requireNonNull(findNamed(
                        view,
                        "instanceManagementWorkspaceBody",
                        JPanel.class));
                JScrollPane navigationScroll = Objects.requireNonNull(findNamed(
                        view,
                        "instanceManagementNavigationScroll",
                        JScrollPane.class));
                ModCatalogPanel mods = Objects.requireNonNull(findNamed(
                        view,
                        "modsCatalogPage",
                        ModCatalogPanel.class));
                assertFalse(mods.isOpaque());
                assertEquals(ModCatalogStatus.READY, mods.displayedSnapshot().status());

                sizeAndLayout(view, RENDER_WIDTH, RENDER_HEIGHT);
                assertWorkspaceGeometry(view, summary, workspaceBody, navigation, deck);
                assertFalse(navigationScroll.getVerticalScrollBar().isVisible());
                BufferedImage renderedMods = renderOverBackground(view, background);
                assertBackgroundVisibleAtNavigationGap(
                        view,
                        background,
                        renderedMods,
                        workspaceBody,
                        navigation);
                modsImage.set(renderedMods);

                navigation.button(InstanceManagementPageId.OVERVIEW).doClick();
                sizeAndLayout(view, COMPACT_WIDTH, COMPACT_HEIGHT);
                assertWorkspaceGeometry(view, summary, workspaceBody, navigation, deck);
                assertTrue(navigationScroll.getViewport().getView().getPreferredSize().height
                        > navigationScroll.getViewport().getExtentSize().height);
                assertTrue(navigationScroll.getVerticalScrollBar().isVisible());
                compactImage.set(renderOverBackground(view, background));
            });

            writeReport("overview.png", Objects.requireNonNull(overviewImage.get()));
            writeReport("mods.png", Objects.requireNonNull(modsImage.get()));
            writeReport("compact.png", Objects.requireNonNull(compactImage.get()));
        } finally {
            try {
                @Nullable DefaultInstanceManagementView view = viewReference.get();
                if (view != null) {
                    view.close();
                }
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            } finally {
                launcherSettingsField.set(null, previousLauncherSettings);
                gameSettingsPresetsField.set(null, previousGameSettingsPresets);
                userSettingsField.set(null, previousUserSettings);
            }
        }
    }

    /// Installs the launcher's real light palette and eight-pixel production corner-radius token.
    private static void initializeTestTheme() {
        SwingThemeManager themeManager = new SwingThemeManager(
                ThemeBrightnessPreference.LIGHT,
                new SwingDesignTokens(8),
                SystemThemeDetector.lightFallback());
        themeManager.initialize();
    }

    /// Creates one parseable instance manifest and a minimal game JAR exposing a real Minecraft version.
    ///
    /// @throws IOException when the temporary fixture cannot be written
    private void createInstalledInstanceFixture() throws IOException {
        Path versionRoot = repositoryRoot.resolve("versions").resolve("instance");
        Files.createDirectories(versionRoot);
        Files.writeString(
                versionRoot.resolve("instance.json"),
                "{\"id\":\"instance\",\"type\":\"release\","
                        + "\"mainClass\":\"net.minecraft.client.main.Main\",\"libraries\":[]}",
                StandardCharsets.UTF_8);
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(
                versionRoot.resolve("instance.jar")))) {
            jar.putNextEntry(new JarEntry("version.json"));
            jar.write("{\"id\":\"1.21.1\"}".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }

    /// Waits until every background action submitted before the barrier has completed.
    ///
    /// @param executor single-threaded workspace executor
    /// @throws InterruptedException when the test thread is interrupted while awaiting the barrier
    private static void awaitExecutor(ExecutorService executor) throws InterruptedException {
        CountDownLatch barrier = new CountDownLatch(1);
        Objects.requireNonNull(executor, "executor").execute(barrier::countDown);
        assertTrue(barrier.await(5, TimeUnit.SECONDS));
    }

    /// Loads the bundled light XYML wallpaper used by the production theme.
    ///
    /// @return decoded immutable test source image
    /// @throws IOException when the bundled image cannot be decoded
    private static BufferedImage loadDefaultBackground() throws IOException {
        String resource = "/assets/themes/xyml.default/assets/background-light.png";
        try (InputStream input = Objects.requireNonNull(
                DefaultInstanceManagementViewTest.class.getResourceAsStream(resource),
                "missing default XYML background")) {
            return Objects.requireNonNull(ImageIO.read(input), "invalid default XYML background");
        }
    }

    /// Applies one deterministic size and recursively lays out the non-displayable Swing tree.
    ///
    /// @param view workspace root
    /// @param width target viewport width
    /// @param height target viewport height
    private static void sizeAndLayout(DefaultInstanceManagementView view, int width, int height) {
        view.setSize(new Dimension(width, height));
        layoutTree(view);
    }

    /// Verifies summary, navigation, and page content remain ordered and bounded after layout.
    ///
    /// @param view workspace root
    /// @param summary persistent instance summary
    /// @param workspaceBody navigation-and-content row
    /// @param navigation grouped destination rail
    /// @param deck lazy page deck
    private static void assertWorkspaceGeometry(
            DefaultInstanceManagementView view,
            JPanel summary,
            JPanel workspaceBody,
            InstanceManagementNavigationPanel navigation,
            InstanceManagementPageDeck deck) {
        assertTrue(summary.getWidth() > 0);
        assertTrue(summary.getHeight() > 0);
        assertTrue(workspaceBody.getWidth() > 0);
        assertTrue(workspaceBody.getHeight() > 0);
        assertTrue(summary.getY() + summary.getHeight() <= workspaceBody.getY());
        assertTrue(navigation.getWidth() > 0);
        assertTrue(navigation.getHeight() > 0);
        assertTrue(deck.getWidth() >= 500);
        assertTrue(deck.getHeight() > 0);
        assertTrue(navigation.getX() + navigation.getWidth() <= deck.getX());
        assertTrue(deck.getX() + deck.getWidth() <= workspaceBody.getWidth());
        assertTrue(workspaceBody.getY() + workspaceBody.getHeight() <= view.getHeight());
    }

    /// Paints the transparent workspace over the bundled production wallpaper using centered cover scaling.
    ///
    /// @param view fully laid-out workspace
    /// @param background bundled wallpaper
    /// @return complete opaque comparison render
    private static BufferedImage renderOverBackground(
            DefaultInstanceManagementView view,
            BufferedImage background) {
        BufferedImage image = renderBackground(view.getWidth(), view.getHeight(), background);
        Graphics2D graphics = image.createGraphics();
        try {
            view.printAll(graphics);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    /// Cover-scales the production wallpaper into one standalone image without Swing controls.
    ///
    /// @param width target image width
    /// @param height target image height
    /// @param background bundled source wallpaper
    /// @return opaque wallpaper render
    private static BufferedImage renderBackground(int width, int height, BufferedImage background) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            double scale = Math.max(
                    (double) width / background.getWidth(),
                    (double) height / background.getHeight());
            int backgroundWidth = (int) Math.ceil(background.getWidth() * scale);
            int backgroundHeight = (int) Math.ceil(background.getHeight() * scale);
            int backgroundX = (width - backgroundWidth) / 2;
            int backgroundY = (height - backgroundHeight) / 2;
            graphics.drawImage(
                    background,
                    backgroundX,
                    backgroundY,
                    backgroundWidth,
                    backgroundHeight,
                    null);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    /// Confirms the intentional split gap remains pixel-identical to the wallpaper beneath the workspace.
    ///
    /// @param view sized workspace root
    /// @param background bundled source wallpaper
    /// @param rendered composite image
    /// @param workspaceBody navigation-and-content row
    /// @param navigation left grouped navigation rail
    private static void assertBackgroundVisibleAtNavigationGap(
            DefaultInstanceManagementView view,
            BufferedImage background,
            BufferedImage rendered,
            JPanel workspaceBody,
            InstanceManagementNavigationPanel navigation) {
        BufferedImage wallpaper = renderBackground(view.getWidth(), view.getHeight(), background);
        int sampleX = workspaceBody.getX() + navigation.getX() + navigation.getWidth() + 6;
        int sampleY = workspaceBody.getY() + 12;
        assertTrue(sampleX >= 0 && sampleX < rendered.getWidth());
        assertTrue(sampleY >= 0 && sampleY < rendered.getHeight());
        assertEquals(wallpaper.getRGB(sampleX, sampleY), rendered.getRGB(sampleX, sampleY));
    }

    /// Writes one focused workspace render to the stable Gradle report directory.
    ///
    /// @param fileName report filename
    /// @param image rendered workspace image
    /// @throws IOException when the report directory or PNG cannot be written
    private static void writeReport(String fileName, BufferedImage image) throws IOException {
        Path report = Path.of("build", "reports", "instance-workspace", fileName).toAbsolutePath();
        Files.createDirectories(report.getParent());
        assertTrue(ImageIO.write(image, "png", report.toFile()));
    }

    /// Recursively lays out one non-displayable Swing tree.
    ///
    /// @param container tree root
    private static void layoutTree(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container childContainer) {
                layoutTree(childContainer);
            }
        }
    }

    /// Creates the minimum repository contract required before lazy management tabs become displayable.
    ///
    /// @return repository proxy rooted in the temporary directory
    private GameRepository repository() {
        return GameRepository.class.cast(Proxy.newProxyInstance(
                GameRepository.class.getClassLoader(),
                new Class<?>[]{GameRepository.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getResourcePackDirectory" -> repositoryRoot.resolve("resourcepacks");
                    case "getModsDirectory" -> repositoryRoot.resolve("mods");
                    case "getVersionRoot" -> repositoryRoot.resolve("versions").resolve("instance");
                    case "getRunDirectory" -> repositoryRoot;
                    case "getGameVersion" -> Optional.of("1.21.1");
                    case "getResolvedPreservingPatchesVersion" -> throw new IllegalStateException(
                            "The test repository intentionally has no Mod metadata");
                    case "toString" -> "TestGameRepository";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == Objects.requireNonNull(arguments)[0];
                    default -> throw new AssertionError(
                            "Instance-management construction used repository eagerly: " + method.getName());
                }));
    }

    /// Creates a stable launch-ready home model for the persistent instance summary.
    ///
    /// @return borrowed test home model
    private static HomeModel homeModel() {
        return new TestHomeModel();
    }

    /// Creates quick-play callbacks that fail if view construction invokes them eagerly.
    ///
    /// @return available quick-play boundary for construction-only tests
    private static WorldQuickPlayActions unusedWorldQuickPlayActions() {
        return WorldQuickPlayActions.available(
                worldFolder -> {
                    throw new AssertionError("Construction started world quick play eagerly: " + worldFolder);
                },
                (worldFolder, destination) -> {
                    throw new AssertionError(
                            "Construction exported a world launch script eagerly: "
                                    + worldFolder
                                    + " -> "
                                    + destination);
                });
    }

    /// Stable launch-ready model used only by the instance workspace summary.
    @NotNullByDefault
    private static final class TestHomeModel implements HomeModel {
        /// Stable empty launch-session property required by the model contract.
        private final SimpleObjectProperty<Optional<LaunchSession>> launchSession =
                new SimpleObjectProperty<>(this, "launchSession", Optional.empty());

        /// Returns one selected instance and ready launch state.
        ///
        /// @return stable home snapshot
        @Override
        public HomeSnapshot snapshot() {
            return new HomeSnapshot(
                    "Player",
                    "Offline",
                    "Test instance",
                    "Test directory",
                    "Ready",
                    true,
                    false,
                    true);
        }

        /// Registers a no-op invalidation listener.
        ///
        /// @param listener required listener
        /// @return independently cancellable no-op subscription
        @Override
        public Subscription subscribe(ValueChangeListener<HomeSnapshot> listener) {
            Objects.requireNonNull(listener, "listener");
            return Subscription.create(() -> { });
        }

        /// Returns the stable empty launch-session property.
        ///
        /// @return empty launch session
        @Override
        public ReadOnlyProperty<Optional<LaunchSession>> launchSessionProperty() {
            return launchSession;
        }

        /// Ignores unused account selection.
        @Override
        public void selectAccount() {
        }

        /// Ignores unused instance selection.
        @Override
        public void selectInstance() {
        }

        /// Ignores unused instance creation.
        @Override
        public void addInstance() {
        }

        /// Ignores unused launch commands.
        @Override
        public void launch() {
        }
    }

    /// Launch boundary that fails immediately if lazy construction invokes an operation.
    @NotNullByDefault
    private static final class UnusedMaintenanceLaunchActions implements InstanceMaintenanceLaunchActions {
        /// Rejects an unexpected eager test launch.
        ///
        /// @return never returns
        @Override
        public LaunchSession testLaunch() {
            throw new AssertionError("Maintenance construction started a test launch eagerly");
        }

        /// Rejects an unexpected eager script export.
        ///
        /// @param scriptFile unexpected destination
        /// @return never returns
        @Override
        public CompletionStage<Path> exportLaunchScript(Path scriptFile) {
            throw new AssertionError("Maintenance construction exported a launch script eagerly: " + scriptFile);
        }
    }

    /// Finds one named descendant of the requested Swing type.
    ///
    /// @param root component tree root
    /// @param name stable component name
    /// @param type required component type
    /// @param <T> component type
    /// @return matching component, or null when absent
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
}
