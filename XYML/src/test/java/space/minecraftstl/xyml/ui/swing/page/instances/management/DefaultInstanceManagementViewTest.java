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
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.application.SwingApplicationPresentation;
import space.minecraftstl.xyml.ui.swing.application.SwingApplicationPresentationFactory;
import space.minecraftstl.xyml.ui.swing.page.home.HomeModel;
import space.minecraftstl.xyml.ui.swing.page.home.HomeSnapshot;
import space.minecraftstl.xyml.ui.swing.page.instances.management.maintenance.InstanceMaintenanceLaunchActions;
import space.minecraftstl.xyml.ui.swing.page.instances.management.worlds.WorldQuickPlayActions;
import space.minecraftstl.xyml.ui.swing.page.mods.DefaultModCatalogInteractions;
import space.minecraftstl.xyml.ui.swing.page.resourcepacks.DefaultResourcePackCatalogInteractions;
import space.minecraftstl.xyml.ui.swing.page.schematics.DefaultSchematicBrowserInteractions;
import space.minecraftstl.xyml.util.PortablePath;
import space.minecraftstl.xyml.util.i18n.LocalizedText;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that production instance management exposes every recovered local-content tool.
@NotNullByDefault
final class DefaultInstanceManagementViewTest {
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
                    "instance",
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
            assertEquals("instance", view.instanceId());
        } finally {
            @Nullable DefaultInstanceManagementView view = viewReference.get();
            if (view != null) {
                view.close();
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// A real XYML repository exposes all destinations without eagerly constructing maintenance.
    @Test
    void exposesProductionMaintenanceTabWithoutEagerLoading()
            throws InterruptedException, ReflectiveOperationException {
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
            SwingApplicationPresentation presentation = SwingApplicationPresentationFactory.create(
                    "XYML test",
                    Duration.ZERO,
                    Duration.ZERO);
            XYMLGameRepository repository = new XYMLGameRepository(new GameDirectory(
                    GameDirectoryID.generate(),
                    LocalizedText.plain("Maintenance test"),
                    PortablePath.of(repositoryRoot.toString())));
            EdtDispatcher.executeAndWait(() -> viewReference.set(new DefaultInstanceManagementView(
                    homeModel(),
                    repository,
                    ignored -> repositoryRoot.resolve("schematics"),
                    "instance",
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

            EdtDispatcher.executeAndWait(() -> {
                InstanceManagementNavigationPanel navigation = Objects.requireNonNull(findNamed(
                        view,
                        "instanceManagementNavigation",
                        InstanceManagementNavigationPanel.class));
                InstanceManagementPageDeck deck = Objects.requireNonNull(findNamed(
                        view,
                        "instanceManagementPageDeck",
                        InstanceManagementPageDeck.class));
                assertEquals(InstanceManagementPageId.orderedValues(), navigation.availablePages());
                assertFalse(deck.isLoaded(InstanceManagementPageId.MAINTENANCE_TOOLS));
                assertNull(findNamed(view, "instanceMaintenancePage", JComponent.class));
                assertEquals(1, deck.loadedPageCount());
            });
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
