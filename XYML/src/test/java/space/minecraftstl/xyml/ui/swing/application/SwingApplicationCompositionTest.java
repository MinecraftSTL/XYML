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
package space.minecraftstl.xyml.ui.swing.application;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.game.install.GameInstallRequest;
import space.minecraftstl.xyml.game.install.GameInstallService;
import space.minecraftstl.xyml.game.install.GameInstallSession;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.theme.ThemeBrightnessPreference;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.MotionPolicy;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.SwingDesignTokens;
import space.minecraftstl.xyml.ui.swing.SwingThemeManager;
import space.minecraftstl.xyml.ui.swing.SystemThemeDetector;
import space.minecraftstl.xyml.ui.swing.page.accounts.AccountsModel;
import space.minecraftstl.xyml.ui.swing.page.accounts.AccountsStrings;
import space.minecraftstl.xyml.ui.swing.page.downloads.GameInstallStrings;
import space.minecraftstl.xyml.ui.swing.page.downloads.GameVersionCatalogItem;
import space.minecraftstl.xyml.ui.swing.page.downloads.GameVersionCatalogModel;
import space.minecraftstl.xyml.ui.swing.page.downloads.GameVersionCatalogPanel;
import space.minecraftstl.xyml.ui.swing.page.downloads.GameVersionCatalogSnapshot;
import space.minecraftstl.xyml.ui.swing.page.downloads.GameVersionCatalogSource;
import space.minecraftstl.xyml.ui.swing.page.downloads.GameVersionCatalogStatus;
import space.minecraftstl.xyml.ui.swing.page.downloads.GameVersionCatalogStatusStrings;
import space.minecraftstl.xyml.ui.swing.page.downloads.GameVersionCatalogStrings;
import space.minecraftstl.xyml.ui.swing.page.downloads.GameVersionFilter;
import space.minecraftstl.xyml.ui.swing.page.home.HomeModel;
import space.minecraftstl.xyml.ui.swing.page.home.HomeStatusStrings;
import space.minecraftstl.xyml.ui.swing.page.home.HomeStrings;
import space.minecraftstl.xyml.ui.swing.page.instances.InstancesModel;
import space.minecraftstl.xyml.ui.swing.page.instances.InstancesStrings;
import space.minecraftstl.xyml.ui.swing.page.instances.RepositoryInstancesStatusStrings;
import space.minecraftstl.xyml.ui.swing.page.instances.management.InstanceManagementCoordinator;
import space.minecraftstl.xyml.ui.swing.page.instances.management.InstanceManagementHost;
import space.minecraftstl.xyml.ui.swing.page.instances.management.SchematicInstanceManagementStrings;
import space.minecraftstl.xyml.ui.swing.page.mods.ModCatalogActionStrings;
import space.minecraftstl.xyml.ui.swing.page.mods.ModCatalogStatusStrings;
import space.minecraftstl.xyml.ui.swing.page.mods.ModCatalogStrings;
import space.minecraftstl.xyml.ui.swing.page.resourcepacks.ResourcePackCatalogActionStrings;
import space.minecraftstl.xyml.ui.swing.page.resourcepacks.ResourcePackCatalogStatusStrings;
import space.minecraftstl.xyml.ui.swing.page.resourcepacks.ResourcePackCatalogStrings;
import space.minecraftstl.xyml.ui.swing.page.schematics.SchematicBrowserActionStrings;
import space.minecraftstl.xyml.ui.swing.page.schematics.SchematicBrowserStrings;
import space.minecraftstl.xyml.ui.swing.page.schematics.SchematicMetadataStrings;
import space.minecraftstl.xyml.ui.swing.page.settings.AppearanceSettingsModel;
import space.minecraftstl.xyml.ui.swing.page.settings.AppearanceSettingsStrings;
import space.minecraftstl.xyml.ui.swing.page.settings.GameDirectoryManagementService;
import space.minecraftstl.xyml.ui.swing.choice.ChoicePage;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;
import space.minecraftstl.xyml.ui.swing.shell.ShellPageFactory;
import space.minecraftstl.xyml.ui.swing.shell.ShellPageId;
import space.minecraftstl.xyml.ui.swing.shell.ShellPagePresentations;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies composition factories and lifecycle without creating a native frame.
@NotNullByDefault
class SwingApplicationCompositionTest {
    /// Confirms that pages stay factory-backed and navigation resolves through the created window.
    @Test
    void keepsPagesLazyAndRoutesNavigationThroughWindowReference() {
        AtomicReference<@Nullable Consumer<ShellPageId>> navigation = new AtomicReference<>();
        List<String> closeOrder = new ArrayList<>();
        RecordingGameInstallService gameInstaller = new RecordingGameInstallService(closeOrder);
        List<CountingCloseable> resources = createResources(closeOrder);
        RecordingGameVersionCatalogModel gameVersions = new RecordingGameVersionCatalogModel();
        RecordingWindowFactory windowFactory = new RecordingWindowFactory();

        SwingApplicationComposition composition = SwingApplicationComposition.createForCollaborators(
                navigateCommand -> {
                    navigation.set(navigateCommand);
                    return createModels(resources, gameVersions, gameInstaller);
                },
                presentation(),
                themeManager(),
                new SwingAnimator(MotionPolicy.OFF, 16),
                windowFactory);

        RecordingWindow window = windowFactory.window();
        assertEquals(EnumSet.allOf(ShellPageId.class), window.pageFactories().keySet());
        assertEquals(0, gameVersions.lazyLoadCount());

        composition.open();
        assertEquals(1, window.openCount());
        composition.hide();
        assertEquals(1, window.hideCount());
        Objects.requireNonNull(navigation.get()).accept(ShellPageId.ACCOUNTS);
        assertEquals(List.of(ShellPageId.ACCOUNTS), window.navigations());

        EdtDispatcher.executeAndWait(() -> {
            JComponent downloads = window.createPage(ShellPageId.DOWNLOADS);
            assertTrue(downloads instanceof GameVersionCatalogPanel);
            assertEquals(0, gameVersions.lazyLoadCount());

            GameVersionCatalogPanel catalogPanel = (GameVersionCatalogPanel) downloads;
            catalogPanel.addNotify();
            catalogPanel.removeNotify();
            catalogPanel.addNotify();
            assertEquals(1, gameVersions.lazyLoadCount());
            catalogPanel.close();
            catalogPanel.removeNotify();
        });
        assertEquals(0, gameInstaller.closeCount());

        composition.close();
        composition.close();
        assertTrue(composition.isClosed());
        assertEquals(1, window.closeCount());
        assertEquals(expectedCloseOrder(), closeOrder);
        for (CountingCloseable resource : resources) {
            assertEquals(1, resource.closeCount());
        }
        assertEquals(1, gameInstaller.closeCount());
        assertThrows(IllegalStateException.class, composition::open);
    }

    /// Confirms that native disposal triggers the same idempotent owned-resource lifecycle.
    @Test
    void nativeWindowClosureClosesOwnedResourcesOnce() {
        List<String> closeOrder = new ArrayList<>();
        RecordingGameInstallService gameInstaller = new RecordingGameInstallService(closeOrder);
        List<CountingCloseable> resources = createResources(closeOrder);
        RecordingWindowFactory windowFactory = new RecordingWindowFactory();
        RecordingGameVersionCatalogModel gameVersions = new RecordingGameVersionCatalogModel();
        SwingApplicationComposition composition = SwingApplicationComposition.createForCollaborators(
                navigateCommand -> createModels(resources, gameVersions, gameInstaller),
                presentation(),
                themeManager(),
                new SwingAnimator(MotionPolicy.OFF, 16),
                windowFactory);

        windowFactory.window().close();
        composition.close();

        assertTrue(composition.isClosed());
        assertEquals(1, windowFactory.window().closeCount());
        assertEquals(expectedCloseOrder(), closeOrder);
        assertFalse(resources.stream().anyMatch(resource -> resource.closeCount() != 1));
        assertEquals(1, gameInstaller.closeCount());
    }

    /// Confirms that explicit and native closure deliver the startup-owned final command once.
    @Test
    void applicationCloseCommandRunsOnceAfterOwnedResources() {
        List<String> closeOrder = new ArrayList<>();
        RecordingGameInstallService gameInstaller = new RecordingGameInstallService(closeOrder);
        List<CountingCloseable> resources = createResources(closeOrder);
        RecordingWindowFactory windowFactory = new RecordingWindowFactory();
        AtomicInteger applicationCloseCalls = new AtomicInteger();
        SwingApplicationComposition composition = SwingApplicationComposition.createForCollaborators(
                navigateCommand -> createModels(
                        resources,
                        new RecordingGameVersionCatalogModel(),
                        gameInstaller),
                presentation(),
                themeManager(),
                new SwingAnimator(MotionPolicy.OFF, 16),
                windowFactory,
                () -> {
                    applicationCloseCalls.incrementAndGet();
                    closeOrder.add("application-close");
                });

        windowFactory.window().close();
        composition.close();

        assertEquals(1, applicationCloseCalls.get());
        assertEquals(expectedCloseOrderWithApplication(), closeOrder);
    }

    /// Confirms that an earlier cleanup failure cannot skip the final application close command.
    @Test
    void cleanupFailureRetainsFinalApplicationCloseFailureAsSuppressed() {
        List<String> closeOrder = new ArrayList<>();
        IllegalStateException cleanupFailure = new IllegalStateException("installer close failed");
        IllegalArgumentException applicationCloseFailure =
                new IllegalArgumentException("application close failed");
        GameInstallService gameInstaller = failingCloseGameInstallService(
                "game-install-service",
                closeOrder,
                cleanupFailure);
        List<CountingCloseable> resources = createResources(closeOrder);
        AtomicInteger applicationCloseCalls = new AtomicInteger();
        SwingApplicationComposition composition = SwingApplicationComposition.createForCollaborators(
                navigateCommand -> createModels(
                        resources,
                        new RecordingGameVersionCatalogModel(),
                        gameInstaller),
                presentation(),
                themeManager(),
                new SwingAnimator(MotionPolicy.OFF, 16),
                new RecordingWindowFactory(),
                () -> {
                    applicationCloseCalls.incrementAndGet();
                    closeOrder.add("application-close");
                    throw applicationCloseFailure;
                });

        IllegalStateException thrown = assertThrows(IllegalStateException.class, composition::close);

        assertSame(cleanupFailure, thrown);
        assertEquals(1, applicationCloseCalls.get());
        assertEquals(expectedCloseOrderWithApplication(), closeOrder);
        assertEquals(List.of(applicationCloseFailure), List.of(thrown.getSuppressed()));
    }

    /// Confirms that repeated cleanup of the same failure instance never attempts self-suppression.
    @Test
    void repeatedCleanupFailureIdentityIsPreservedWithoutSelfSuppression() {
        List<String> closeOrder = new ArrayList<>();
        IllegalStateException repeatedFailure = new IllegalStateException("shared close failure");
        SwingApplicationComposition composition = SwingApplicationComposition.createForCollaborators(
                navigateCommand -> createModels(
                        createResources(closeOrder),
                        new RecordingGameVersionCatalogModel(),
                        failingCloseGameInstallService(
                                "game-install-service",
                                closeOrder,
                                repeatedFailure)),
                presentation(),
                themeManager(),
                new SwingAnimator(MotionPolicy.OFF, 16),
                new RecordingWindowFactory(),
                () -> {
                    throw repeatedFailure;
                });

        IllegalStateException thrown = assertThrows(IllegalStateException.class, composition::close);

        assertSame(repeatedFailure, thrown);
        assertEquals(0, thrown.getSuppressed().length);
    }

    /// Confirms that a failed window factory closes the complete collaborator-owned resource bundle.
    @Test
    void windowCreationFailureClosesCollaboratorResources() {
        List<String> closeOrder = new ArrayList<>();
        RecordingGameInstallService gameInstaller = new RecordingGameInstallService(closeOrder);
        List<CountingCloseable> resources = createResources(closeOrder);
        RecordingGameVersionCatalogModel gameVersions = new RecordingGameVersionCatalogModel();
        IllegalStateException creationFailure = new IllegalStateException("window creation failed");

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> SwingApplicationComposition.createForCollaborators(
                        navigateCommand -> createModels(resources, gameVersions, gameInstaller),
                        presentation(),
                        themeManager(),
                        new SwingAnimator(MotionPolicy.OFF, 16),
                        (ignoredTheme, ignoredPages, ignoredPresentation, ignoredAnimator, ignoredModels) -> {
                            throw creationFailure;
                        }));

        assertSame(creationFailure, thrown);
        assertEquals(expectedCloseOrder(), closeOrder);
        assertFalse(resources.stream().anyMatch(resource -> resource.closeCount() != 1));
        assertEquals(1, gameInstaller.closeCount());
    }

    /// Confirms that construction cleanup may reuse the construction failure without masking it.
    @Test
    void windowCreationFailureAllowsSameCleanupFailureInstance() {
        List<String> closeOrder = new ArrayList<>();
        IllegalStateException repeatedFailure = new IllegalStateException("shared construction failure");

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> SwingApplicationComposition.createForCollaborators(
                        navigateCommand -> createModels(
                                createResources(closeOrder),
                                new RecordingGameVersionCatalogModel(),
                                failingCloseGameInstallService(
                                        "game-install-service",
                                        closeOrder,
                                        repeatedFailure)),
                        presentation(),
                        themeManager(),
                        new SwingAnimator(MotionPolicy.OFF, 16),
                        (ignoredTheme, ignoredPages, ignoredPresentation, ignoredAnimator, ignoredModels) -> {
                            throw repeatedFailure;
                        }));

        assertSame(repeatedFailure, thrown);
        assertEquals(0, thrown.getSuppressed().length);
    }

    /// Confirms production ownership and shared internal add-instance navigation for both page models.
    @Test
    void productionOwnershipSharesInternalAddInstanceNavigation() {
        List<String> closeOrder = new ArrayList<>();
        HomeModel home = closeableNoCallModel(HomeModel.class, "home-model", closeOrder);
        GameDirectoryManagementService gameDirectories = closeableNoCallModel(
                GameDirectoryManagementService.class,
                "game-directories",
                closeOrder);
        InstanceManagementCoordinator instanceManagement = recordingInstanceManagement(closeOrder);
        InstancesModel instances = closeableNoCallModel(InstancesModel.class, "instances-model", closeOrder);
        GameVersionCatalogSource source = closeableNoCallModel(
                GameVersionCatalogSource.class,
                "game-versions-source",
                closeOrder);
        GameVersionCatalogModel gameVersions = closeableNoCallModel(
                GameVersionCatalogModel.class,
                "game-versions-model",
                closeOrder);
        GameInstallService gameInstaller = closeableNoCallModel(
                GameInstallService.class,
                "game-install-service",
                closeOrder);
        AccountsModel accounts = closeableNoCallModel(AccountsModel.class, "accounts-model", closeOrder);
        AppearanceSettingsModel appearance = closeableNoCallModel(
                AppearanceSettingsModel.class,
                "appearance-model",
                closeOrder);
        CountingCloseable homeStore = new CountingCloseable("home-store", closeOrder);
        CountingCloseable accountStore = new CountingCloseable("accounts-store", closeOrder);
        CountingCloseable appearanceStore = new CountingCloseable("appearance-store", closeOrder);
        AtomicInteger stateCloseCount = new AtomicInteger();
        AtomicReference<@Nullable Runnable> homeAddInstanceCommand = new AtomicReference<>();
        AtomicReference<@Nullable Runnable> instancesAddInstanceCommand = new AtomicReference<>();
        List<ShellPageId> navigations = new ArrayList<>();
        SwingApplicationComposition.ProductionPageModelFactories factories =
                new SwingApplicationComposition.ProductionPageModelFactories(
                        addInstanceCommand -> {
                            homeAddInstanceCommand.set(addInstanceCommand);
                            return home;
                        },
                        () -> gameDirectories,
                        () -> instanceManagement,
                        (createdManagement, addInstanceCommand) -> {
                            assertSame(instanceManagement, createdManagement);
                            instancesAddInstanceCommand.set(addInstanceCommand);
                            return instances;
                        },
                        () -> source,
                        createdSource -> {
                            assertSame(source, createdSource);
                            return gameVersions;
                        },
                        () -> gameInstaller,
                        () -> accounts,
                        () -> appearance);

        SwingApplicationPageModels models = SwingApplicationComposition.createProductionModels(
                factories,
                homeStore,
                accountStore,
                appearanceStore,
                stateCloseCount::incrementAndGet,
                navigations::add);
        assertSame(gameVersions, models.gameVersions());
        assertSame(gameInstaller, models.gameInstaller());
        assertSame(gameDirectories, models.gameDirectories());
        assertSame(instanceManagement, models.instanceManagement());
        Runnable homeCommand = Objects.requireNonNull(homeAddInstanceCommand.get());
        Runnable instancesCommand = Objects.requireNonNull(instancesAddInstanceCommand.get());
        assertSame(homeCommand, instancesCommand);
        homeCommand.run();
        instancesCommand.run();
        assertEquals(List.of(ShellPageId.DOWNLOADS, ShellPageId.DOWNLOADS), navigations);
        models.close();

        assertEquals(expectedProductionCloseOrder(), closeOrder);
        assertEquals(0, stateCloseCount.get());
        assertEquals(1, homeStore.closeCount());
        assertEquals(1, accountStore.closeCount());
        assertEquals(1, appearanceStore.closeCount());
        assertTrue(instanceManagement.isClosed());
    }

    /// Confirms that a post-source construction failure closes models, then source, then all stores.
    @Test
    void productionConstructionFailureClosesCreatedSource() {
        List<String> closeOrder = new ArrayList<>();
        HomeModel home = closeableNoCallModel(HomeModel.class, "home-model", closeOrder);
        GameDirectoryManagementService gameDirectories = closeableNoCallModel(
                GameDirectoryManagementService.class,
                "game-directories",
                closeOrder);
        InstanceManagementCoordinator instanceManagement = recordingInstanceManagement(closeOrder);
        InstancesModel instances = closeableNoCallModel(InstancesModel.class, "instances-model", closeOrder);
        GameVersionCatalogSource source = closeableNoCallModel(
                GameVersionCatalogSource.class,
                "game-versions-source",
                closeOrder);
        CountingCloseable homeStore = new CountingCloseable("home-store", closeOrder);
        CountingCloseable accountStore = new CountingCloseable("accounts-store", closeOrder);
        CountingCloseable appearanceStore = new CountingCloseable("appearance-store", closeOrder);
        AutoCloseable stateStores = () -> {
            homeStore.close();
            accountStore.close();
            appearanceStore.close();
        };
        IllegalStateException creationFailure = new IllegalStateException("game model construction failed");
        SwingApplicationComposition.ProductionPageModelFactories factories =
                new SwingApplicationComposition.ProductionPageModelFactories(
                        ignoredAddInstanceCommand -> home,
                        () -> gameDirectories,
                        () -> instanceManagement,
                        (ignoredManagement, ignoredAddInstanceCommand) -> instances,
                        () -> source,
                        createdSource -> {
                            assertSame(source, createdSource);
                            throw creationFailure;
                        },
                        () -> {
                            throw new AssertionError("Installer must not be created after game-model failure");
                        },
                        () -> {
                            throw new AssertionError("Accounts model must not be created after failure");
                        },
                        () -> {
                            throw new AssertionError("Appearance model must not be created after failure");
                        });

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> SwingApplicationComposition.createProductionModels(
                        factories,
                        homeStore,
                        accountStore,
                        appearanceStore,
                        stateStores,
                        ignoredPage -> {
                        }));

        assertSame(creationFailure, thrown);
        assertEquals(List.of(
                "home-model",
                "game-directories",
                "instance-management",
                "instances-model",
                "game-versions-source",
                "home-store",
                "accounts-store",
                "appearance-store"), closeOrder);
        assertEquals(1, homeStore.closeCount());
        assertEquals(1, accountStore.closeCount());
        assertEquals(1, appearanceStore.closeCount());
        assertTrue(instanceManagement.isClosed());
    }

    /// Confirms that a service closes first and its close failure is suppressed after later construction fails.
    @Test
    void productionConstructionFailureClosesInstallerBeforeModelsAndSource() {
        List<String> closeOrder = new ArrayList<>();
        HomeModel home = closeableNoCallModel(HomeModel.class, "home-model", closeOrder);
        GameDirectoryManagementService gameDirectories = closeableNoCallModel(
                GameDirectoryManagementService.class,
                "game-directories",
                closeOrder);
        InstanceManagementCoordinator instanceManagement = recordingInstanceManagement(closeOrder);
        InstancesModel instances = closeableNoCallModel(InstancesModel.class, "instances-model", closeOrder);
        GameVersionCatalogSource source = closeableNoCallModel(
                GameVersionCatalogSource.class,
                "game-versions-source",
                closeOrder);
        GameVersionCatalogModel gameVersions = closeableNoCallModel(
                GameVersionCatalogModel.class,
                "game-versions-model",
                closeOrder);
        IllegalStateException serviceCloseFailure = new IllegalStateException("installer close failed");
        GameInstallService gameInstaller = failingCloseGameInstallService(
                "game-install-service",
                closeOrder,
                serviceCloseFailure);
        AccountsModel accounts = closeableNoCallModel(AccountsModel.class, "accounts-model", closeOrder);
        CountingCloseable homeStore = new CountingCloseable("home-store", closeOrder);
        CountingCloseable accountStore = new CountingCloseable("accounts-store", closeOrder);
        CountingCloseable appearanceStore = new CountingCloseable("appearance-store", closeOrder);
        AutoCloseable stateStores = () -> {
            homeStore.close();
            accountStore.close();
            appearanceStore.close();
        };
        IllegalStateException creationFailure = new IllegalStateException("appearance model construction failed");
        SwingApplicationComposition.ProductionPageModelFactories factories =
                new SwingApplicationComposition.ProductionPageModelFactories(
                        ignoredAddInstanceCommand -> home,
                        () -> gameDirectories,
                        () -> instanceManagement,
                        (ignoredManagement, ignoredAddInstanceCommand) -> instances,
                        () -> source,
                        createdSource -> {
                            assertSame(source, createdSource);
                            return gameVersions;
                        },
                        () -> gameInstaller,
                        () -> accounts,
                        () -> {
                            throw creationFailure;
                        });

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> SwingApplicationComposition.createProductionModels(
                        factories,
                        homeStore,
                        accountStore,
                        appearanceStore,
                        stateStores,
                        ignoredPage -> {
                        }));

        assertSame(creationFailure, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(serviceCloseFailure, thrown.getSuppressed()[0]);
        assertEquals(List.of(
                "game-install-service",
                "home-model",
                "game-directories",
                "instance-management",
                "instances-model",
                "game-versions-model",
                "accounts-model",
                "game-versions-source",
                "home-store",
                "accounts-store",
                "appearance-store"), closeOrder);
        assertTrue(instanceManagement.isClosed());
    }

    /// Creates no-call proxies that fail if composition accidentally instantiates a page.
    ///
    /// @param resources ordered lifecycle probes
    /// @param gameVersions game-version model required by the real lazy download-page factory
    /// @param gameInstaller application-owned installation service supplied to that page factory
    /// @return model bundle containing proxy contracts and explicit resources
    private static SwingApplicationPageModels createModels(
            List<? extends AutoCloseable> resources,
            GameVersionCatalogModel gameVersions,
            GameInstallService gameInstaller) {
        @Unmodifiable List<? extends AutoCloseable> resourceSnapshot = List.copyOf(resources);
        InstanceManagementCoordinator instanceManagement = noCallInstanceManagement();
        List<AutoCloseable> ownedResources = new ArrayList<>(resourceSnapshot.size() + 2);
        ownedResources.add(Objects.requireNonNull(gameInstaller, "gameInstaller"));
        ownedResources.add(instanceManagement);
        ownedResources.addAll(resourceSnapshot);
        return new SwingApplicationPageModels(
                noCallModel(HomeModel.class),
                noCallModel(GameDirectoryManagementService.class),
                noCallModel(InstancesModel.class),
                instanceManagement,
                gameVersions,
                gameInstaller,
                noCallModel(AccountsModel.class),
                noCallModel(AppearanceSettingsModel.class),
                ownedResources);
    }

    /// Creates a coordinator whose dynamic factory must remain lazy in composition-only tests.
    ///
    /// @return unopened closeable instance-management coordinator
    private static InstanceManagementCoordinator noCallInstanceManagement() {
        return new InstanceManagementCoordinator((instanceId, returnCommand) -> {
            Objects.requireNonNull(instanceId, "instanceId");
            Objects.requireNonNull(returnCommand, "returnCommand");
            throw new AssertionError("Instance management view was created eagerly");
        });
    }

    /// Creates an unopened coordinator whose host records its exact cleanup position.
    ///
    /// @param closeOrder shared close-order recorder
    /// @return hosted close-order-recording coordinator
    private static InstanceManagementCoordinator recordingInstanceManagement(List<String> closeOrder) {
        InstanceManagementCoordinator coordinator = noCallInstanceManagement();
        coordinator.attachHost(new RecordingInstanceManagementHost(closeOrder));
        return coordinator;
    }

    /// Creates an interface proxy that reports any unexpected page-model invocation.
    ///
    /// @param modelType page-model interface
    /// @param <T> page-model contract type
    /// @return proxy that fails on every method invocation
    private static <T> T noCallModel(Class<T> modelType) {
        Object proxy = Proxy.newProxyInstance(
                modelType.getClassLoader(),
                new Class<?>[]{modelType},
                (ignoredProxy, method, ignoredArguments) -> {
                    throw new AssertionError("Page model was used eagerly: " + method.getName());
                });
        return modelType.cast(proxy);
    }

    /// Creates a model or source proxy that only accepts its owned close invocation.
    ///
    /// @param contract model or source interface
    /// @param resourceName stable close-order name
    /// @param closeOrder shared close-order recorder
    /// @param <T> model or source contract type
    /// @return closeable proxy implementing the requested contract
    private static <T> T closeableNoCallModel(
            Class<T> contract,
            String resourceName,
            List<String> closeOrder) {
        Objects.requireNonNull(contract, "contract");
        CountingCloseable closeable = new CountingCloseable(resourceName, closeOrder);
        Object proxy = Proxy.newProxyInstance(
                contract.getClassLoader(),
                new Class<?>[]{contract, AutoCloseable.class},
                (ignoredProxy, method, ignoredArguments) -> {
                    if (method.getName().equals("close") && method.getParameterCount() == 0) {
                        closeable.close();
                        return null;
                    }
                    throw new AssertionError("Production construction used resource eagerly: "
                            + method.getName());
                });
        return contract.cast(proxy);
    }

    /// Creates an installation-service proxy that records cleanup before throwing one exact failure.
    ///
    /// @param resourceName stable close-order name
    /// @param closeOrder shared close-order recorder
    /// @param closeFailure exact close failure
    /// @return close-failing service proxy
    private static GameInstallService failingCloseGameInstallService(
            String resourceName,
            List<String> closeOrder,
            RuntimeException closeFailure) {
        Objects.requireNonNull(closeFailure, "closeFailure");
        CountingCloseable closeable = new CountingCloseable(resourceName, closeOrder);
        Object proxy = Proxy.newProxyInstance(
                GameInstallService.class.getClassLoader(),
                new Class<?>[]{GameInstallService.class},
                (ignoredProxy, method, ignoredArguments) -> {
                    if (method.getName().equals("close") && method.getParameterCount() == 0) {
                        closeable.close();
                        throw closeFailure;
                    }
                    throw new AssertionError("Production construction used installer eagerly: "
                            + method.getName());
                });
        return GameInstallService.class.cast(proxy);
    }

    /// Creates the explicit test-only localized presentation.
    ///
    /// @return complete presentation fixture
    private static SwingApplicationPresentation presentation() {
        return new SwingApplicationPresentation(
                "XYML test",
                ShellPagePresentations.englishFallback(),
                new HomeStrings(
                        "Home", "Account", "None", "Instance", "None", "Add", "Export", "Launch", "Launching", "Back"),
                new HomeStatusStrings("Ready", "Select account", "Select instance", "Exporting"),
                new InstancesStrings("Instances", "Refresh", "Refreshing", "Add", "Manage", "Empty"),
                new RepositoryInstancesStatusStrings("Loading", "Ready", "Refreshing", "Failed", "Unknown"),
                new SchematicInstanceManagementStrings(
                        "Instances", "Return to instances", "Resolving schematics", "Resolution failed", "Retry"),
                new SchematicBrowserStrings(
                        "Schematics",
                        "Up",
                        "Return to parent directory",
                        "Refresh",
                        "Refreshing",
                        "Refresh current directory",
                        "Open",
                        "Open selected directory",
                        "Not loaded",
                        "Loading schematics",
                        "No schematics",
                        "Unable to load schematics",
                        "Retry",
                        "Details",
                        "Select a schematic",
                        "Directory",
                        "Unreadable schematic",
                        "[Directory] ",
                        new SchematicMetadataStrings(
                                "Path",
                                "Name",
                                "Author",
                                "Description",
                                "Created",
                                "Modified",
                                "Regions",
                                "Volume",
                                "Blocks",
                                "Size",
                                "Format version",
                                "Minecraft data version",
                                "Preview",
                                "Unknown",
                                "%d x %d x %d",
                                "%d x %d pixels",
                                "%d pixels",
                                "Unavailable"),
                        new SchematicBrowserActionStrings(
                                "Import",
                                "Import schematics",
                                "Choose schematics",
                                "Litematic file",
                                "New directory",
                                "Create a directory",
                                "Directory name",
                                "Delete",
                                "Delete selected item",
                                "Delete %s?",
                                "Reveal",
                                "Reveal in file manager",
                                "Updating schematics",
                                "Update failed",
                                "Operation failed",
                                "Reveal failed")),
                modCatalogStrings(),
                modCatalogStatusStrings(),
                modCatalogActionStrings(),
                resourcePackStrings(),
                resourcePackStatusStrings(),
                resourcePackActionStrings(),
                new GameVersionCatalogStrings(
                        "Game versions",
                        "Search",
                        "Type",
                        "All",
                        "Release",
                        "Snapshot",
                        "April Fools",
                        "Old",
                        "Refresh",
                        "Refreshing"),
                new GameVersionCatalogStatusStrings("Waiting", "Loading", "Ready", "Empty", "Failed"),
                new GameInstallStrings(
                        "Instance name",
                        "Install",
                        "Back to versions",
                        "Install game",
                        "Preparing installation",
                        "Invalid name",
                        "Instance already exists",
                        "Another installation is running",
                        "Installation failed"),
                new AccountsStrings(
                        "Accounts",
                        "Add",
                        "Refresh",
                        "Copy UUID",
                        "Delete",
                        "Remove permanently?",
                        "Account error",
                        "Empty"),
                new AppearanceSettingsStrings(
                        "Appearance", "Theme", "Follow theme", "System", "Light", "Dark", "Radius", "Animations",
                        space.minecraftstl.xyml.ui.swing.page.settings.AppearanceBackgroundStrings.englishFallback()),
                Duration.ZERO,
                new TaskProgressStrings(
                        "Waiting", "Running", "Completed", "Failed", "Cancelled",
                        "Task progress", "Cancel", "Show details", "Hide details"),
                Duration.ZERO);
    }

    /// Creates installed-Mod catalog text for the composition fixture.
    ///
    /// @return complete installed-Mod catalog text
    private static ModCatalogStrings modCatalogStrings() {
        return new ModCatalogStrings(
                "Mods", "Search", "Status", "All", "Enabled", "Disabled",
                "Select a Mod", "Mod ID", "Version", "Game version", "Loader",
                "Authors", "File", "Description", "Enabled");
    }

    /// Creates installed-Mod lifecycle text for the composition fixture.
    ///
    /// @return complete installed-Mod lifecycle text
    private static ModCatalogStatusStrings modCatalogStatusStrings() {
        return new ModCatalogStatusStrings(
                "Loading", "Empty", "%d Mods", "Failed: %s", "Importing",
                "Enabling", "Disabling", "Deleting", "Write failed: %s");
    }

    /// Creates installed-Mod action text for the composition fixture.
    ///
    /// @return complete installed-Mod action text
    private static ModCatalogActionStrings modCatalogActionStrings() {
        return new ModCatalogActionStrings(
                "Refresh", "Refresh Mods", "Import", "Import Mods", "Open directory",
                "Open Mods directory", "Reveal", "Reveal Mod", "Delete", "Delete Mod",
                "Choose Mods", "Mod file", "Delete %s?", "Operation failed");
    }

    /// Creates resource-pack catalog text for the composition fixture.
    ///
    /// @return complete resource-pack catalog text
    private static ResourcePackCatalogStrings resourcePackStrings() {
        return new ResourcePackCatalogStrings(
                "Resource packs", "Refresh", "Refreshing", "Refresh resource packs",
                "Retry", "Retry loading resource packs", "Not loaded", "Loading",
                "No resource packs", "Load failed", "Unsupported", "Details",
                "Select a resource pack", "File", "Path", "Description", "Compatibility",
                "Enabled", "Enabled", "Disabled", "Compatible", "Too new", "Too old",
                "Invalid", "Missing pack metadata", "Missing game metadata");
    }

    /// Creates resource-pack lifecycle text for the composition fixture.
    ///
    /// @return complete resource-pack lifecycle text
    private static ResourcePackCatalogStatusStrings resourcePackStatusStrings() {
        return new ResourcePackCatalogStatusStrings(
                "Idle", "Loading", "Ready", "Empty", "Unsupported", "Failed", "Unknown",
                "Writing", "Write failed");
    }

    /// Creates resource-pack action text for the composition fixture.
    ///
    /// @return complete resource-pack action text
    private static ResourcePackCatalogActionStrings resourcePackActionStrings() {
        return new ResourcePackCatalogActionStrings(
                "Import", "Import resource packs", "Choose resource packs", "ZIP archive",
                "Enable", "Enable resource pack", "Disable", "Disable resource pack", "Warning",
                "Enable incompatible %s?", "Delete", "Delete resource pack", "Delete %s?",
                "Reveal", "Reveal resource pack", "Open directory", "Open resource-pack directory",
                "Operation failed", "Reveal failed", "Open directory failed");
    }

    /// Creates a non-initialized theme manager suitable for the fake window.
    ///
    /// @return explicit test theme manager
    private static SwingThemeManager themeManager() {
        return new SwingThemeManager(
                ThemeBrightnessPreference.SYSTEM,
                new SwingDesignTokens(8),
                SystemThemeDetector.lightFallback());
    }

    /// Creates lifecycle probes in production model-before-source-before-store close order.
    ///
    /// @param closeOrder shared close-order recorder
    /// @return nine distinct model, source, and store probes
    private static @Unmodifiable List<CountingCloseable> createResources(List<String> closeOrder) {
        return List.of(
                new CountingCloseable("home-model", closeOrder),
                new CountingCloseable("instances-model", closeOrder),
                new CountingCloseable("game-versions-model", closeOrder),
                new CountingCloseable("accounts-model", closeOrder),
                new CountingCloseable("appearance-model", closeOrder),
                new CountingCloseable("game-versions-source", closeOrder),
                new CountingCloseable("home-store", closeOrder),
                new CountingCloseable("accounts-store", closeOrder),
                new CountingCloseable("appearance-store", closeOrder));
    }

    /// Returns the expected dependency-safe lifecycle order.
    ///
    /// @return immutable expected close order
    private static @Unmodifiable List<String> expectedCloseOrder() {
        return List.of(
                "game-install-service",
                "home-model",
                "instances-model",
                "game-versions-model",
                "accounts-model",
                "appearance-model",
                "game-versions-source",
                "home-store",
                "accounts-store",
                "appearance-store");
    }

    /// Returns the normal close order followed by the startup-owned final command.
    ///
    /// @return immutable expected close order including application shutdown
    private static @Unmodifiable List<String> expectedCloseOrderWithApplication() {
        List<String> expected = new ArrayList<>(expectedCloseOrder());
        expected.add("application-close");
        return List.copyOf(expected);
    }

    /// Returns the production close order including dynamic instance management.
    ///
    /// @return immutable expected production close order
    private static @Unmodifiable List<String> expectedProductionCloseOrder() {
        return List.of(
                "game-install-service",
                "home-model",
                "game-directories",
                "instance-management",
                "instances-model",
                "game-versions-model",
                "accounts-model",
                "appearance-model",
                "game-versions-source",
                "home-store",
                "accounts-store",
                "appearance-store");
    }

    /// Records coordinator host restoration as its observable close-order marker.
    @NotNullByDefault
    private static final class RecordingInstanceManagementHost implements InstanceManagementHost {
        /// Shared close-order recorder.
        private final List<String> closeOrder;

        /// Creates a host backed by one close-order recorder.
        ///
        /// @param closeOrder shared close-order recorder
        private RecordingInstanceManagementHost(List<String> closeOrder) {
            this.closeOrder = Objects.requireNonNull(closeOrder, "closeOrder");
        }

        /// Rejects unexpected eager management view creation.
        ///
        /// @param component unexpected dynamic management component
        @Override
        public void showManagementView(JComponent component) {
            throw new AssertionError("Management view was shown eagerly: "
                    + Objects.requireNonNull(component, "component"));
        }

        /// Records coordinator cleanup when it restores its attached host.
        @Override
        public void showInstanceList() {
            closeOrder.add("instance-management");
        }
    }

    /// Supplies an empty catalog while recording the panel's lazy initial-load request.
    @NotNullByDefault
    private static final class RecordingGameVersionCatalogModel implements GameVersionCatalogModel {
        /// Stable empty idle snapshot used by the composition test page.
        private final GameVersionCatalogSnapshot snapshot = new GameVersionCatalogSnapshot(
                OptionalInt.empty(),
                0,
                0L,
                GameVersionCatalogStatus.IDLE,
                "Waiting",
                "",
                GameVersionFilter.ALL,
                false,
                true);

        /// Number of lazy initial-load commands delegated by created panels.
        private final AtomicInteger lazyLoadCount = new AtomicInteger();

        /// Creates an empty recording catalog.
        private RecordingGameVersionCatalogModel() {
        }

        /// Returns the stable empty catalog snapshot.
        ///
        /// @return empty idle snapshot
        @Override
        public GameVersionCatalogSnapshot snapshot() {
            return snapshot;
        }

        /// Returns an independently removable no-op listener registration.
        ///
        /// @param listener listener accepted by the page
        /// @return no-op listener registration
        @Override
        public Subscription subscribe(ValueChangeListener<GameVersionCatalogSnapshot> listener) {
            Objects.requireNonNull(listener, "listener");
            return Subscription.create(() -> {
            });
        }

        /// Returns the exact empty item count.
        ///
        /// @return exact zero count
        @Override
        public OptionalInt exactItemCount() {
            return OptionalInt.of(0);
        }

        /// Returns an empty page clamped to the exact zero count.
        ///
        /// @param desiredRange measured viewport request
        /// @param cancellation cooperative request cancellation
        /// @return completed empty page
        @Override
        public CompletionStage<ChoicePage<GameVersionCatalogItem>> load(
                IndexRange desiredRange,
                LoadCancellation cancellation) {
            Objects.requireNonNull(desiredRange, "desiredRange");
            Objects.requireNonNull(cancellation, "cancellation").throwIfCancelled();
            IndexRange emptyRange = desiredRange.clampToItemCount(0);
            return CompletableFuture.completedFuture(
                    new ChoicePage<>(emptyRange, List.of(), OptionalInt.of(0), true));
        }

        /// Records one lazy initial-load command.
        @Override
        public void loadIfNeeded() {
            lazyLoadCount.incrementAndGet();
        }

        /// Rejects an unexpected refresh command in this composition-only fake.
        @Override
        public void refresh() {
            throw new AssertionError("Catalog refresh was invoked unexpectedly");
        }

        /// Accepts a query command without changing the stable empty fixture.
        ///
        /// @param query query delegated by the panel
        @Override
        public void setQuery(String query) {
            Objects.requireNonNull(query, "query");
        }

        /// Accepts a filter command without changing the stable empty fixture.
        ///
        /// @param filter filter delegated by the panel
        @Override
        public void setFilter(GameVersionFilter filter) {
            Objects.requireNonNull(filter, "filter");
        }

        /// Rejects selection because the fixture contains no version IDs.
        ///
        /// @param versionId unexpected selected version ID
        @Override
        public void selectVersion(String versionId) {
            throw new AssertionError("Empty catalog cannot select version "
                    + Objects.requireNonNull(versionId, "versionId"));
        }

        /// Returns the recorded lazy initial-load count.
        ///
        /// @return lazy initial-load count
        private int lazyLoadCount() {
            return lazyLoadCount.get();
        }
    }

    /// Records the factories supplied by the composition without creating a native frame.
    @NotNullByDefault
    private static final class RecordingWindowFactory implements SwingApplicationWindowFactory {
        /// Window created by the factory, or null before composition.
        private @Nullable RecordingWindow window;

        /// Captures complete lazy factories without invoking them.
        ///
        /// @param themeManager unused explicit theme collaborator
        /// @param pageFactories immutable complete lazy page table
        /// @param presentation unused explicit presentation collaborator
        /// @param animator unused explicit animator collaborator
        /// @param models complete model bundle used by title-bar controls
        /// @return recording window
        @Override
        public SwingApplicationWindow createWindow(
                SwingThemeManager themeManager,
                @Unmodifiable Map<ShellPageId, ShellPageFactory<? extends JComponent>> pageFactories,
                SwingApplicationPresentation presentation,
                SwingAnimator animator,
                SwingApplicationPageModels models) {
            Objects.requireNonNull(themeManager, "themeManager");
            Objects.requireNonNull(presentation, "presentation");
            Objects.requireNonNull(animator, "animator");
            Objects.requireNonNull(models, "models");
            window = new RecordingWindow(pageFactories);
            return window;
        }

        /// Returns the window created during composition.
        ///
        /// @return recording window
        private RecordingWindow window() {
            return Objects.requireNonNull(window, "window was not created");
        }
    }

    /// Provides a deterministic headless application-window lifecycle.
    @NotNullByDefault
    private static final class RecordingWindow implements SwingApplicationWindow {
        /// Complete immutable page factory table.
        private final @Unmodifiable Map<ShellPageId, ShellPageFactory<? extends JComponent>> pageFactories;

        /// Destinations requested through the shell navigation reference.
        private final List<ShellPageId> navigations = new ArrayList<>();

        /// Stable headless component returned as the native dialog owner.
        private final JComponent dialogOwner = new JPanel();

        /// Number of successful open calls.
        private final AtomicInteger openCount = new AtomicInteger();

        /// Number of successful hide calls.
        private final AtomicInteger hideCount = new AtomicInteger();

        /// Number of first close transitions.
        private final AtomicInteger closeCount = new AtomicInteger();

        /// Idempotent closed state.
        private final AtomicBoolean closed = new AtomicBoolean();

        /// Whether the fake window currently accepts interaction.
        private boolean interactionEnabled = true;

        /// Composition cleanup callback, or null before registration.
        private @Nullable Runnable closedHandler;

        /// Creates a fake window without invoking any page factory.
        ///
        /// @param pageFactories complete lazy page table
        private RecordingWindow(
                @Unmodifiable Map<ShellPageId, ShellPageFactory<? extends JComponent>> pageFactories) {
            this.pageFactories = Map.copyOf(pageFactories);
        }

        /// Installs the composition cleanup callback.
        ///
        /// @param handler callback invoked after closure
        @Override
        public void setClosedHandler(Runnable handler) {
            if (closedHandler != null) {
                throw new IllegalStateException("closed handler already installed");
            }
            closedHandler = Objects.requireNonNull(handler, "handler");
        }

        /// Records one open request.
        @Override
        public void open() {
            if (closed.get()) {
                throw new IllegalStateException("window is closed");
            }
            openCount.incrementAndGet();
        }

        /// Records one non-destructive hide request.
        @Override
        public void hide() {
            if (closed.get()) {
                throw new IllegalStateException("window is closed");
            }
            hideCount.incrementAndGet();
        }

        /// Returns the stable headless dialog owner.
        ///
        /// @return headless owner component
        @Override
        public JComponent dialogOwner() {
            return dialogOwner;
        }

        /// Records application interaction state.
        ///
        /// @param enabled whether the fake window accepts interaction
        @Override
        public void setInteractionEnabled(boolean enabled) {
            if (closed.get()) {
                throw new IllegalStateException("window is closed");
            }
            interactionEnabled = enabled;
        }

        /// Records a shell-backed navigation request.
        ///
        /// @param page requested destination
        @Override
        public void navigateTo(ShellPageId page) {
            if (closed.get()) {
                throw new IllegalStateException("window is closed");
            }
            navigations.add(Objects.requireNonNull(page, "page"));
        }

        /// Closes once and reports the native-close event to the composition.
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                closeCount.incrementAndGet();
                @Nullable Runnable handler = closedHandler;
                if (handler != null) {
                    handler.run();
                }
            }
        }

        /// Creates one page only when the test explicitly asks for it.
        ///
        /// @param page requested page
        /// @return component created by the captured lazy factory
        private JComponent createPage(ShellPageId page) {
            return Objects.requireNonNull(pageFactories.get(page), "missing page factory").createPage();
        }

        /// Returns the immutable page factory table.
        ///
        /// @return page factory table
        private @Unmodifiable Map<ShellPageId, ShellPageFactory<? extends JComponent>> pageFactories() {
            return pageFactories;
        }

        /// Returns recorded navigation requests.
        ///
        /// @return immutable navigation snapshot
        private @Unmodifiable List<ShellPageId> navigations() {
            return List.copyOf(navigations);
        }

        /// Returns the number of successful open requests.
        ///
        /// @return open count
        private int openCount() {
            return openCount.get();
        }

        /// Returns the number of successful hide requests.
        ///
        /// @return hide count
        private int hideCount() {
            return hideCount.get();
        }

        /// Returns the number of first close transitions.
        ///
        /// @return close count
        private int closeCount() {
            return closeCount.get();
        }
    }

    /// Records application ownership while rejecting unexpected installation workflow calls.
    @NotNullByDefault
    private static final class RecordingGameInstallService implements GameInstallService {
        /// Close-order probe backing this typed service.
        private final CountingCloseable closeable;

        /// Creates one typed application-owned service probe.
        ///
        /// @param closeOrder shared close-order recorder
        private RecordingGameInstallService(List<String> closeOrder) {
            closeable = new CountingCloseable("game-install-service", closeOrder);
        }

        /// Rejects an installation command in composition-only tests.
        ///
        /// @param request unexpected installation request
        /// @return never returns
        @Override
        public GameInstallSession install(GameInstallRequest request) {
            throw new AssertionError("Unexpected installation request: "
                    + Objects.requireNonNull(request, "request"));
        }

        /// Returns the stable empty active-session state.
        ///
        /// @return empty active installation
        @Override
        public Optional<GameInstallSession> activeInstallation() {
            return Optional.empty();
        }

        /// Records application-owned service cleanup.
        @Override
        public void close() {
            closeable.close();
        }

        /// Returns the number of service close invocations.
        ///
        /// @return close count
        private int closeCount() {
            return closeable.closeCount();
        }
    }

    /// Records whether one owned model or store is closed more than once.
    @NotNullByDefault
    private static final class CountingCloseable implements AutoCloseable {
        /// Stable resource name appended to the close-order recorder.
        private final String name;

        /// Shared close-order recorder.
        private final List<String> closeOrder;

        /// Number of close invocations.
        private final AtomicInteger closeCount = new AtomicInteger();

        /// Creates one lifecycle probe.
        ///
        /// @param name stable resource name
        /// @param closeOrder shared close-order recorder
        private CountingCloseable(String name, List<String> closeOrder) {
            this.name = Objects.requireNonNull(name, "name");
            this.closeOrder = Objects.requireNonNull(closeOrder, "closeOrder");
        }

        /// Records one close invocation and its ordering.
        @Override
        public void close() {
            closeCount.incrementAndGet();
            closeOrder.add(name);
        }

        /// Returns the number of close invocations.
        ///
        /// @return close count
        private int closeCount() {
            return closeCount.get();
        }
    }
}
