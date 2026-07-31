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
import space.minecraftstl.xyml.Metadata;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.game.install.DefaultGameInstallService;
import space.minecraftstl.xyml.game.install.GameInstallService;
import space.minecraftstl.xyml.game.install.RepositoryGameInstallTaskFactory;
import space.minecraftstl.xyml.game.launch.LaunchRequest;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.setting.DownloadProviders;
import space.minecraftstl.xyml.setting.GameDirectoryManager;
import space.minecraftstl.xyml.setting.LauncherSettings;
import space.minecraftstl.xyml.setting.SettingsManager;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.theme.BuiltinThemePackCatalog;
import space.minecraftstl.xyml.theme.LocalThemePackRepository;
import space.minecraftstl.xyml.theme.ThemeReference;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.MotionPolicy;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.SwingDesignTokens;
import space.minecraftstl.xyml.ui.swing.SwingThemeManager;
import space.minecraftstl.xyml.ui.swing.SystemThemeDetector;
import space.minecraftstl.xyml.ui.swing.runtime.LauncherStateDispatcher;
import space.minecraftstl.xyml.ui.swing.page.accounts.AccountsPanel;
import space.minecraftstl.xyml.ui.swing.page.accounts.AccountsModel;
import space.minecraftstl.xyml.ui.swing.page.accounts.LauncherAccountsModel;
import space.minecraftstl.xyml.ui.swing.page.accounts.LauncherAccountStore;
import space.minecraftstl.xyml.ui.swing.page.downloads.DefaultGameVersionCatalogModel;
import space.minecraftstl.xyml.ui.swing.page.downloads.DownloadProviderGameVersionCatalogSource;
import space.minecraftstl.xyml.ui.swing.page.downloads.GameVersionCatalogModel;
import space.minecraftstl.xyml.ui.swing.page.downloads.GameVersionCatalogPanel;
import space.minecraftstl.xyml.ui.swing.page.downloads.GameVersionCatalogSource;
import space.minecraftstl.xyml.ui.swing.page.home.HomeModel;
import space.minecraftstl.xyml.ui.swing.page.home.LauncherHomeModel;
import space.minecraftstl.xyml.ui.swing.page.home.LauncherHomeStore;
import space.minecraftstl.xyml.ui.swing.page.instances.InstancesPanel;
import space.minecraftstl.xyml.ui.swing.page.instances.InstancesModel;
import space.minecraftstl.xyml.ui.swing.page.instances.SelectedRepositoryInstancesModel;
import space.minecraftstl.xyml.ui.swing.page.instances.importing.SwingInstanceJsonImportLauncher;
import space.minecraftstl.xyml.ui.swing.page.instances.management.DefaultInstanceManagementView;
import space.minecraftstl.xyml.ui.swing.page.instances.management.InstanceManagementCoordinator;
import space.minecraftstl.xyml.ui.swing.page.instances.management.maintenance.CommandInstanceMaintenanceLaunchActions;
import space.minecraftstl.xyml.ui.swing.page.instances.management.maintenance.InstanceMaintenanceLaunchActions;
import space.minecraftstl.xyml.ui.swing.page.instances.management.worlds.WorldQuickPlayActions;
import space.minecraftstl.xyml.ui.swing.page.mods.DefaultModCatalogInteractions;
import space.minecraftstl.xyml.ui.swing.page.mods.ModCatalogInteractions;
import space.minecraftstl.xyml.ui.swing.page.resourcepacks.DefaultResourcePackCatalogInteractions;
import space.minecraftstl.xyml.ui.swing.page.resourcepacks.ResourcePackCatalogInteractions;
import space.minecraftstl.xyml.ui.swing.page.schematics.DefaultSchematicBrowserInteractions;
import space.minecraftstl.xyml.ui.swing.page.schematics.SchematicBrowserInteractions;
import space.minecraftstl.xyml.ui.swing.page.settings.AppearanceSettingsModel;
import space.minecraftstl.xyml.ui.swing.page.settings.AppearanceSettingsPanel;
import space.minecraftstl.xyml.ui.swing.page.settings.GameDirectoryManagementService;
import space.minecraftstl.xyml.ui.swing.page.settings.LauncherGameDirectoryManagementService;
import space.minecraftstl.xyml.ui.swing.page.settings.LauncherAppearanceStore;
import space.minecraftstl.xyml.ui.swing.page.settings.PersistedAppearanceSettingsModel;
import space.minecraftstl.xyml.ui.swing.page.settings.SettingsCenterPanel;
import space.minecraftstl.xyml.ui.swing.page.settings.StoredAppearanceSettings;
import space.minecraftstl.xyml.ui.swing.page.settings.theme.SwingThemePackManagementInteractions;
import space.minecraftstl.xyml.ui.swing.page.settings.theme.ThemeRuntimeController;
import space.minecraftstl.xyml.ui.swing.page.settings.theme.ThemePackManagementModel;
import space.minecraftstl.xyml.ui.swing.page.settings.theme.ThemePackManagementModelFactory;
import space.minecraftstl.xyml.ui.swing.page.settings.theme.ThemePackManagementPanel;
import space.minecraftstl.xyml.ui.swing.page.settings.theme.ThemePackManagementStrings;
import space.minecraftstl.xyml.ui.swing.shell.AppShellFrame;
import space.minecraftstl.xyml.ui.swing.shell.ShellPageFactory;
import space.minecraftstl.xyml.ui.swing.shell.ShellPageId;
import space.minecraftstl.xyml.ui.swing.shell.ShellToolbarModels;
import space.minecraftstl.xyml.ui.swing.shell.ShellRecentSelections;
import space.minecraftstl.xyml.util.i18n.I18n;

import javax.swing.JComponent;
import java.awt.Component;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Owns the Swing window, page models, instance management, installer, launcher stores,
/// and animation lifecycle.
///
/// [space.minecraftstl.xyml.Launcher] creates this composition only after Accounts, game directories, and
/// settings have been initialized on the Swing-confined state thread. No caller-owned executor is stopped
/// by this class.
@NotNullByDefault
public final class SwingApplicationComposition implements AutoCloseable {
    /// Native-window abstraction backed by [AppShellFrame] in production.
    private final SwingApplicationWindow window;

    /// Five page models, instance management, installer, and ordered owned-resource lifecycle.
    private final SwingApplicationPageModels pageModels;

    /// Shared animator whose active timers are cancelled during cleanup.
    private final SwingAnimator animator;

    /// Startup-owned command invoked after every composition resource has been offered cleanup.
    private final Runnable applicationCloseCommand;

    /// Prevents repeated window, timer, model, and store cleanup.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Creates the completed application lifecycle.
    ///
    /// @param window native-window adapter
    /// @param pageModels page models and owned resources
    /// @param animator shared animator
    /// @param applicationCloseCommand startup-owned final close command
    private SwingApplicationComposition(
            SwingApplicationWindow window,
            SwingApplicationPageModels pageModels,
            SwingAnimator animator,
            Runnable applicationCloseCommand) {
        this.window = Objects.requireNonNull(window, "window");
        this.pageModels = Objects.requireNonNull(pageModels, "pageModels");
        this.animator = Objects.requireNonNull(animator, "animator");
        this.applicationCloseCommand = Objects.requireNonNull(
                applicationCloseCommand,
                "applicationCloseCommand");
    }

    /// Creates the production bridge after launcher state managers are initialized.
    ///
    /// Launcher stores and the selected repository are captured on the Swing event dispatch thread. Instance
    /// viewport work uses the process-wide caller-owned [Schedulers#io()] executor, which this composition
    /// never closes. Launcher account creation and launch commands remain startup-owned. New-instance
    /// commands route to the Swing downloads page, while instance management itself is composed and owned here.
    ///
    /// @param presentation localized text and explicit transition policy
    /// @param commands startup-owned launcher workflow boundaries
    /// @param systemThemeDetector fast operating-system appearance detector
    /// @param animationFrameDelayMillis positive Swing animation timer delay
    /// @return closed-resource-safe Swing application composition
    public static SwingApplicationComposition createAfterStateInitialization(
            SwingApplicationPresentation presentation,
            SwingApplicationCommands commands,
            SystemThemeDetector systemThemeDetector,
            int animationFrameDelayMillis) {
        return createAfterStateInitialization(
                presentation,
                commands,
                systemThemeDetector,
                animationFrameDelayMillis,
                () -> { });
    }

    /// Creates the production bridge with a startup-owned final close command.
    ///
    /// @param presentation localized text and explicit transition policy
    /// @param commands startup-owned launcher workflow boundaries
    /// @param systemThemeDetector fast operating-system appearance detector
    /// @param animationFrameDelayMillis positive Swing animation timer delay
    /// @param applicationCloseCommand command invoked once after composition cleanup is attempted
    /// @return closed-resource-safe Swing application composition
    public static SwingApplicationComposition createAfterStateInitialization(
            SwingApplicationPresentation presentation,
            SwingApplicationCommands commands,
            SystemThemeDetector systemThemeDetector,
            int animationFrameDelayMillis,
            Runnable applicationCloseCommand) {
        Objects.requireNonNull(presentation, "presentation");
        Objects.requireNonNull(commands, "commands");
        Objects.requireNonNull(systemThemeDetector, "systemThemeDetector");
        Objects.requireNonNull(applicationCloseCommand, "applicationCloseCommand");
        if (animationFrameDelayMillis <= 0) {
            throw new IllegalArgumentException("animationFrameDelayMillis must be positive");
        }

        AtomicReference<@Nullable LauncherBindings> bindingsResult = new AtomicReference<>();
        LauncherStateDispatcher.executeAndWait(() -> bindingsResult.set(LauncherBindings.create()));
        LauncherBindings bindings = Objects.requireNonNull(bindingsResult.get(), "launcher bindings were not created");

        StoredAppearanceSettings rawAppearance = bindings.appearanceStore().snapshot();
        SwingThemeManager themeManager = new SwingThemeManager(
                rawAppearance.brightnessPreference(),
                new SwingDesignTokens(rawAppearance.cornerRadius()),
                systemThemeDetector);
        SwingAnimator animator = new SwingAnimator(
                rawAppearance.animationsDisabled() ? MotionPolicy.OFF : MotionPolicy.FULL,
                animationFrameDelayMillis);

        try {
            return createForCollaborators(
                    navigateCommand -> createProductionModels(
                            bindings,
                            commands,
                            presentation,
                            navigateCommand,
                            themeManager,
                            animator,
                            systemThemeDetector),
                    presentation,
                    themeManager,
                    animator,
                    SwingApplicationComposition::createFrameWindow,
                    applicationCloseCommand);
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(bindings, failure);
            throw failure;
        }
    }

    /// Creates a composition from explicit model and window factories without native windows.
    ///
    /// This is the integration boundary for focused lifecycle tests. The
    /// composition owns the returned model bundle, animator, and window but does not infer ownership of
    /// any executor captured by those collaborators.
    ///
    /// @param modelFactory model factory receiving the shell-backed navigation command
    /// @param presentation localized text and explicit transition policy
    /// @param themeManager active Swing theme manager
    /// @param animator shared animator owned by the returned composition
    /// @param windowFactory explicit native-window factory
    /// @return composed application lifecycle
    public static SwingApplicationComposition createForCollaborators(
            SwingApplicationPageModelFactory modelFactory,
            SwingApplicationPresentation presentation,
            SwingThemeManager themeManager,
            SwingAnimator animator,
            SwingApplicationWindowFactory windowFactory) {
        return createForCollaborators(
                modelFactory,
                presentation,
                themeManager,
                animator,
                windowFactory,
                () -> { });
    }

    /// Creates a composition with an explicit command delivered after final cleanup.
    ///
    /// @param modelFactory model factory receiving the shell-backed navigation command
    /// @param presentation localized text and explicit transition policy
    /// @param themeManager active Swing theme manager
    /// @param animator shared animator owned by the returned composition
    /// @param windowFactory explicit native-window factory
    /// @param applicationCloseCommand command invoked once after all owned cleanup is attempted
    /// @return composed application lifecycle
    public static SwingApplicationComposition createForCollaborators(
            SwingApplicationPageModelFactory modelFactory,
            SwingApplicationPresentation presentation,
            SwingThemeManager themeManager,
            SwingAnimator animator,
            SwingApplicationWindowFactory windowFactory,
            Runnable applicationCloseCommand) {
        Objects.requireNonNull(modelFactory, "modelFactory");
        Objects.requireNonNull(presentation, "presentation");
        Objects.requireNonNull(themeManager, "themeManager");
        Objects.requireNonNull(animator, "animator");
        Objects.requireNonNull(windowFactory, "windowFactory");
        Objects.requireNonNull(applicationCloseCommand, "applicationCloseCommand");

        AtomicReference<@Nullable SwingApplicationWindow> windowReference = new AtomicReference<>();
        Consumer<ShellPageId> navigateCommand = page -> {
            Objects.requireNonNull(page, "page");
            @Nullable SwingApplicationWindow currentWindow = windowReference.get();
            if (currentWindow == null) {
                throw new IllegalStateException("Swing application window is not ready for navigation");
            }
            currentWindow.navigateTo(page);
        };

        final SwingApplicationPageModels models;
        try {
            models = Objects.requireNonNull(
                    modelFactory.createModels(navigateCommand),
                    "modelFactory returned null");
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(animator::cancelAll, failure);
            throw failure;
        }

        @Unmodifiable Map<ShellPageId, ShellPageFactory<? extends JComponent>> pageFactories =
                createPageFactories(models, presentation, animator);
        final SwingApplicationWindow createdWindow;
        try {
            createdWindow = Objects.requireNonNull(
                    windowFactory.createWindow(themeManager, pageFactories, presentation, animator, models),
                    "windowFactory returned null");
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(models, failure);
            closeAfterFailure(animator::cancelAll, failure);
            throw failure;
        }

        windowReference.set(createdWindow);
        SwingApplicationComposition composition = new SwingApplicationComposition(
                createdWindow,
                models,
                animator,
                applicationCloseCommand);
        try {
            createdWindow.setClosedHandler(composition::close);
            return composition;
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(composition, failure);
            throw failure;
        }
    }

    /// Opens the native Swing window unless this lifecycle has already closed.
    public void open() {
        if (closed.get()) {
            throw new IllegalStateException("Swing application composition is closed");
        }
        window.open();
    }

    /// Hides the native Swing window without releasing page state or owned services.
    public void hide() {
        if (closed.get()) {
            throw new IllegalStateException("Swing application composition is closed");
        }
        window.hide();
    }

    /// Returns the stable native owner for application-modal Swing dialogs.
    ///
    /// @return native application window component
    public Component dialogOwner() {
        if (closed.get()) {
            throw new IllegalStateException("Swing application composition is closed");
        }
        return window.dialogOwner();
    }

    /// Enables or disables application interaction while retaining native visibility.
    ///
    /// @param enabled whether hosted pages accept user input
    public void setInteractionEnabled(boolean enabled) {
        if (closed.get()) {
            throw new IllegalStateException("Swing application composition is closed");
        }
        window.setInteractionEnabled(enabled);
    }

    /// Returns whether this lifecycle has released its window, timers, models, and stores.
    ///
    /// @return `true` after the first close request begins cleanup
    public boolean isClosed() {
        return closed.get();
    }

    /// Disposes the frame, cancels animations, and closes services, models, and stores exactly once.
    ///
    /// Cleanup attempts every owned resource even when an earlier resource fails. The process-wide
    /// [Schedulers#io()] executor is caller-owned and is intentionally absent from this lifecycle.
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        @Nullable Throwable failure = null;
        failure = closeCollecting(window, failure);
        failure = runCollecting(animator::cancelAll, failure);
        failure = closeCollecting(pageModels, failure);
        failure = runCollecting(applicationCloseCommand, failure);
        rethrowFailure(failure);
    }

    /// Builds lazy Swing component factories without instantiating any destination page.
    ///
    /// @param models toolkit-neutral page models
    /// @param presentation localized page text
    /// @param animator shared application animator
    /// @return complete immutable page factory table
    private static @Unmodifiable Map<ShellPageId, ShellPageFactory<? extends JComponent>> createPageFactories(
            SwingApplicationPageModels models,
            SwingApplicationPresentation presentation,
            SwingAnimator animator) {
        EnumMap<ShellPageId, ShellPageFactory<? extends JComponent>> factories =
                new EnumMap<>(ShellPageId.class);
        factories.put(ShellPageId.INSTANCES, () -> new InstancesPanel(
                models.instances(),
                presentation.instances(),
                models.instanceManagement()));
        factories.put(
                ShellPageId.DOWNLOADS,
                () -> new GameVersionCatalogPanel(
                        models.gameVersions(),
                        models.gameInstaller(),
                        presentation.gameVersions(),
                        presentation.gameInstall(),
                        presentation.taskProgress(),
                        animator,
                        presentation.taskProgressAnimationDuration()));
        factories.put(ShellPageId.ACCOUNTS, () -> new AccountsPanel(models.accounts(), presentation.accounts()));
        factories.put(
                ShellPageId.SETTINGS,
                () -> createSettingsPage(models, presentation));
        return Map.copyOf(factories);
    }

    /// Creates the settings center and attaches local theme-pack management when production supplied it.
    ///
    /// @param models complete application models
    /// @param presentation localized settings text
    /// @return fully owned settings center
    private static SettingsCenterPanel createSettingsPage(
            SwingApplicationPageModels models,
            SwingApplicationPresentation presentation) {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable ThemePackManagementModel themePackModel = models.createThemePackManagementModel();
        @Nullable ThemePackManagementPanel themePackPanel = null;
        if (themePackModel != null) {
            ThemePackManagementStrings strings = I18n.isUseChinese()
                    ? ThemePackManagementStrings.simplifiedChinese()
                    : ThemePackManagementStrings.english();
            try {
                themePackPanel = new ThemePackManagementPanel(
                        themePackModel,
                        strings,
                        new SwingThemePackManagementInteractions(strings, Schedulers.io()),
                        Schedulers.io());
            } catch (RuntimeException | Error failure) {
                themePackModel.close();
                throw failure;
            }
        }

        AppearanceSettingsPanel appearancePanel;
        try {
            appearancePanel = new AppearanceSettingsPanel(
                    models.appearance(),
                    presentation.appearance(),
                    themePackPanel);
        } catch (RuntimeException | Error failure) {
            if (themePackPanel != null) {
                themePackPanel.close();
            }
            throw failure;
        }
        try {
            return SettingsCenterPanel.createForCurrentSettings(appearancePanel);
        } catch (RuntimeException | Error failure) {
            appearancePanel.close();
            throw failure;
        }
    }

    /// Creates production collaborators and records service-before-model-before-store cleanup order.
    ///
    /// @param bindings captured state-store bindings and selected repository
    /// @param commands startup-owned workflow boundaries
    /// @param presentation localized model status text
    /// @param navigateCommand shell-backed navigation command
    /// @param themeManager active theme manager
    /// @param animator shared animator
    /// @param systemThemeDetector fast operating-system appearance detector
    /// @return owned production page-model bundle
    private static SwingApplicationPageModels createProductionModels(
            LauncherBindings bindings,
            SwingApplicationCommands commands,
            SwingApplicationPresentation presentation,
            Consumer<ShellPageId> navigateCommand,
            SwingThemeManager themeManager,
            SwingAnimator animator,
            SystemThemeDetector systemThemeDetector) {
        ThemeRuntimeController themeRuntime = new ThemeRuntimeController(
                bindings.settings(),
                new BuiltinThemePackCatalog(),
                new LocalThemePackRepository(Metadata.XYML_LOCAL_HOME.resolve("themes")),
                themeManager,
                animator,
                systemThemeDetector,
                Schedulers.io(),
                bindings.initialTheme());
        SchematicBrowserInteractions schematicInteractions = new DefaultSchematicBrowserInteractions(
                presentation.schematics().actions(),
                Schedulers.io());
        ResourcePackCatalogInteractions resourcePackInteractions =
                new DefaultResourcePackCatalogInteractions(
                        presentation.resourcePacksActions(),
                        Schedulers.io());
        ModCatalogInteractions modInteractions = new DefaultModCatalogInteractions(
                presentation.modsActions(),
                Schedulers.io());
        ProductionPageModelFactories factories = new ProductionPageModelFactories(
                addInstanceCommand -> new LauncherHomeModel(
                        bindings.homeStore(),
                        presentation.homeStatus(),
                        () -> navigateCommand.accept(ShellPageId.ACCOUNTS),
                        () -> navigateCommand.accept(ShellPageId.INSTANCES),
                        addInstanceCommand,
                        commands.launchCommand(),
                        commands.launchScriptExportCommand()),
                LauncherGameDirectoryManagementService::new,
                homeModel -> new InstanceManagementCoordinator((instanceId, ignoredReturnCommand) -> {
                    WorldQuickPlayActions worldQuickPlayActions = WorldQuickPlayActions.available(
                            worldFolder -> commands.launchCommand().launch(new LaunchRequest(
                                    bindings.homeStore().snapshot().accountId(),
                                    bindings.repository().getGameDirectory().getId().toString(),
                                    instanceId,
                                    worldFolder)),
                            (worldFolder, destination) -> commands.launchScriptExportCommand().export(
                                    new LaunchRequest(
                                            bindings.homeStore().snapshot().accountId(),
                                            bindings.repository().getGameDirectory().getId().toString(),
                                            instanceId,
                                            worldFolder),
                                    destination));
                    InstanceMaintenanceLaunchActions maintenanceLaunchActions =
                            new CommandInstanceMaintenanceLaunchActions(
                                    bindings.repository(),
                                    instanceId,
                                    commands.launchCommand(),
                                    commands.launchScriptExportCommand());
                    return new DefaultInstanceManagementView(
                                homeModel,
                                bindings.repository(),
                                bindings.repository()::getSchematicsDirectory,
                                instanceId,
                                Schedulers.io(),
                                presentation.schematicManagement(),
                                presentation.schematics(),
                                schematicInteractions,
                                presentation.mods(),
                                presentation.modsStatus(),
                                presentation.modsActions(),
                                modInteractions,
                                presentation.resourcePacks(),
                                presentation.resourcePacksStatus(),
                                presentation.resourcePacksActions(),
                                resourcePackInteractions,
                                () -> navigateCommand.accept(ShellPageId.INSTANCES),
                                presentation.taskProgress(),
                                animator,
                                presentation.taskProgressAnimationDuration(),
                                worldQuickPlayActions,
                                maintenanceLaunchActions);
                }),
                (management, addInstanceCommand) -> new SelectedRepositoryInstancesModel(
                        Schedulers.io(),
                        addInstanceCommand,
                        instanceId -> openInstanceManagement(management, instanceId),
                        presentation.instancesStatus()),
                () -> new DownloadProviderGameVersionCatalogSource(DownloadProviders.getDownloadProvider()),
                source -> new DefaultGameVersionCatalogModel(source, presentation.gameVersionsStatus()),
                () -> new DefaultGameInstallService(
                        request -> new RepositoryGameInstallTaskFactory(
                                        bindings.repository(),
                                        DownloadProviders.getDownloadProvider(),
                                        Schedulers.io(),
                                        LauncherStateDispatcher::execute)
                                .create(request),
                        Schedulers.io(),
                        presentation.gameInstall().taskTitle(),
                        presentation.gameInstall().preparingPhase()),
                () -> new LauncherAccountsModel(
                        bindings.accountStore(),
                        commands.addAccountCommand(),
                        commands.refreshAccountCommand()),
                () -> new PersistedAppearanceSettingsModel(
                        bindings.appearanceStore(),
                        themeRuntime,
                        themeRuntime));
        try {
            return createProductionModels(
                    factories,
                    bindings.homeStore(),
                    bindings.accountStore(),
                    bindings.appearanceStore(),
                    bindings,
                    navigateCommand,
                    themeRuntime::createManagementModel);
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(themeRuntime, failure);
            throw failure;
        }
    }

    /// Opens one Swing-owned instance-management view and observes its asynchronous result.
    ///
    /// Coordinator cancellation is an expected close race. Other failures have already restored
    /// the instances list and are logged here so a command failure is never silently discarded.
    ///
    /// @param management application-owned instance-management coordinator
    /// @param instanceId stable selected repository identifier
    private static void openInstanceManagement(
            InstanceManagementCoordinator management,
            String instanceId) {
        Objects.requireNonNull(management, "management");
        Objects.requireNonNull(instanceId, "instanceId");
        management.open(instanceId).whenComplete(
                (@Nullable Void ignored, @Nullable Throwable failure) -> {
                    @Nullable Throwable cause = unwrapCompletionFailure(failure);
                    if (cause != null && !(cause instanceof CancellationException)) {
                        LOG.warning("Failed to open Swing instance management for " + instanceId, cause);
                    }
                });
    }

    /// Removes asynchronous completion wrappers without changing the underlying failure identity.
    ///
    /// @param failure asynchronous failure, or null after success
    /// @return underlying failure, or null after success
    private static @Nullable Throwable unwrapCompletionFailure(@Nullable Throwable failure) {
        @Nullable Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /// Executes the production page-model construction sequence with explicit testable factories.
    ///
    /// Every closeable result is registered before the next factory runs, so a later constructor
    /// failure cannot leak an earlier model or source. The returned bundle owns stores individually;
    /// the aggregate launcher resource is used only to clean up partial construction.
    ///
    /// @param factories ordered page-model and source factories
    /// @param homeStore launcher home projection
    /// @param accountStore launcher account projection
    /// @param appearanceStore launcher appearance persistence adapter
    /// @param stateResources aggregate partial-construction cleanup resource
    /// @param navigateCommand shell navigation command used to create the shared add-instance action
    /// @return fully owned production page models
    static SwingApplicationPageModels createProductionModels(
            ProductionPageModelFactories factories,
            AutoCloseable homeStore,
            AutoCloseable accountStore,
            AutoCloseable appearanceStore,
            AutoCloseable stateResources,
            Consumer<ShellPageId> navigateCommand) {
        return createProductionModels(
                factories,
                homeStore,
                accountStore,
                appearanceStore,
                stateResources,
                navigateCommand,
                null);
    }

    /// Executes the production model transaction with optional local theme-pack page support.
    ///
    /// @param factories ordered page-model and source factories
    /// @param homeStore launcher home projection
    /// @param accountStore launcher account projection
    /// @param appearanceStore launcher appearance persistence adapter
    /// @param stateResources aggregate partial-construction cleanup resource
    /// @param navigateCommand shell navigation command used to create the shared add-instance action
    /// @param themePackManagementModelFactory optional fresh theme-pack model factory
    /// @return fully owned production page models
    static SwingApplicationPageModels createProductionModels(
            ProductionPageModelFactories factories,
            AutoCloseable homeStore,
            AutoCloseable accountStore,
            AutoCloseable appearanceStore,
            AutoCloseable stateResources,
            Consumer<ShellPageId> navigateCommand,
            @Nullable ThemePackManagementModelFactory themePackManagementModelFactory) {
        Objects.requireNonNull(factories, "factories");
        Objects.requireNonNull(homeStore, "homeStore");
        Objects.requireNonNull(accountStore, "accountStore");
        Objects.requireNonNull(appearanceStore, "appearanceStore");
        Objects.requireNonNull(stateResources, "stateResources");
        Objects.requireNonNull(navigateCommand, "navigateCommand");
        Runnable addInstanceCommand = () -> navigateCommand.accept(ShellPageId.DOWNLOADS);
        List<AutoCloseable> services = new ArrayList<>(1);
        List<AutoCloseable> models = new ArrayList<>(7);
        List<AutoCloseable> sources = new ArrayList<>(1);
        try {
            HomeModel home = ownCloseable(
                    factories.home().apply(addInstanceCommand),
                    models,
                    "home model");
            GameDirectoryManagementService gameDirectories = ownCloseable(
                    factories.gameDirectories().get(),
                    models,
                    "game-directory selection service");
            InstanceManagementCoordinator instanceManagement = ownCloseable(
                    factories.instanceManagement().apply(home),
                    models,
                    "instance-management coordinator");
            InstancesModel instances = ownCloseable(
                    factories.instances().apply(instanceManagement, addInstanceCommand),
                    models,
                    "instances model");
            GameVersionCatalogSource gameVersionSource = ownCloseable(
                    factories.gameVersionSource().get(),
                    sources,
                    "game-version source");
            GameVersionCatalogModel gameVersions = ownCloseable(
                    factories.gameVersions().apply(gameVersionSource),
                    models,
                    "game-version model");
            GameInstallService gameInstaller = ownCloseable(
                    factories.gameInstaller().get(),
                    services,
                    "game-install service");
            AccountsModel accounts = ownCloseable(factories.accounts().get(), models, "accounts model");
            AppearanceSettingsModel appearance = ownCloseable(
                    factories.appearance().get(),
                    models,
                    "appearance model");

            @Unmodifiable List<AutoCloseable> resources = productionOwnedResources(
                    services,
                    models,
                    sources,
                    homeStore,
                    accountStore,
                    appearanceStore);
            return new SwingApplicationPageModels(
                    home,
                    gameDirectories,
                    instances,
                    instanceManagement,
                    gameVersions,
                    gameInstaller,
                    accounts,
                    appearance,
                    resources,
                    themePackManagementModelFactory);
        } catch (RuntimeException | Error failure) {
            closeProductionModelConstructionAfterFailure(
                    services,
                    models,
                    sources,
                    stateResources,
                    failure);
            throw failure;
        }
    }

    /// Registers a non-null closeable factory result before construction advances.
    ///
    /// @param value factory result implementing its page or source contract
    /// @param resources matching ownership layer receiving the closeable resource
    /// @param description failure description for a non-closeable implementation
    /// @param <T> page-model or source contract
    /// @return the same validated result
    private static <T> T ownCloseable(
            T value,
            List<AutoCloseable> resources,
            String description) {
        T validatedValue = Objects.requireNonNull(value, description);
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(description, "description");
        if (!(validatedValue instanceof AutoCloseable closeable)) {
            throw new IllegalStateException(description + " must implement AutoCloseable");
        }
        resources.add(closeable);
        return validatedValue;
    }

    /// Builds the immutable production service-before-model-before-source-before-store ownership order.
    ///
    /// This package-visible policy keeps successful ownership directly testable without initializing
    /// process-wide state or download provider.
    ///
    /// @param services active-work services closed before their dependencies
    /// @param models created page models and instance-management coordinator in dependency-safe close order
    /// @param sources lower-level sources owned by those models
    /// @param homeStore launcher home projection
    /// @param accountStore launcher account projection
    /// @param appearanceStore launcher appearance persistence adapter
    /// @return immutable complete ownership order
    static @Unmodifiable List<AutoCloseable> productionOwnedResources(
            List<? extends AutoCloseable> services,
            List<? extends AutoCloseable> models,
            List<? extends AutoCloseable> sources,
            AutoCloseable homeStore,
            AutoCloseable accountStore,
            AutoCloseable appearanceStore) {
        Objects.requireNonNull(services, "services");
        Objects.requireNonNull(models, "models");
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(homeStore, "homeStore");
        Objects.requireNonNull(accountStore, "accountStore");
        Objects.requireNonNull(appearanceStore, "appearanceStore");

        List<AutoCloseable> resources = new ArrayList<>(
                services.size() + models.size() + sources.size() + 3);
        resources.addAll(List.copyOf(services));
        resources.addAll(List.copyOf(models));
        resources.addAll(List.copyOf(sources));
        resources.add(homeStore);
        resources.add(accountStore);
        resources.add(appearanceStore);
        return List.copyOf(resources);
    }

    /// Closes every partially constructed production layer after a later constructor fails.
    ///
    /// Cleanup follows the same dependency direction as successful ownership and suppresses every
    /// cleanup failure onto the original construction failure.
    ///
    /// @param services active-work services created before the failure
    /// @param models page models and instance-management coordinator created before the failure
    /// @param sources lower-level sources created before the failure
    /// @param stateResources launcher stores captured before model construction
    /// @param constructionFailure original constructor failure
    static void closeProductionModelConstructionAfterFailure(
            List<? extends AutoCloseable> services,
            List<? extends AutoCloseable> models,
            List<? extends AutoCloseable> sources,
            AutoCloseable stateResources,
            Throwable constructionFailure) {
        Objects.requireNonNull(services, "services");
        Objects.requireNonNull(models, "models");
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(stateResources, "stateResources");
        Objects.requireNonNull(constructionFailure, "constructionFailure");
        closeAllAfterFailure(List.copyOf(services), constructionFailure);
        closeAllAfterFailure(List.copyOf(models), constructionFailure);
        closeAllAfterFailure(List.copyOf(sources), constructionFailure);
        closeAfterFailure(stateResources, constructionFailure);
    }

    /// Creates the production [AppShellFrame] adapter.
    ///
    /// @param themeManager active theme manager
    /// @param pageFactories complete lazy page table
    /// @param presentation localized shell presentation
    /// @param animator shared animator
    /// @param models application models used by title-bar workflow controls
    /// @return native Swing window adapter
    private static SwingApplicationWindow createFrameWindow(
            SwingThemeManager themeManager,
            @Unmodifiable Map<ShellPageId, ShellPageFactory<? extends JComponent>> pageFactories,
            SwingApplicationPresentation presentation,
            SwingAnimator animator,
            SwingApplicationPageModels models) {
        AppShellFrame frame = AppShellFrame.create(
                presentation.windowTitle(),
                themeManager,
                pageFactories,
                presentation.shellPages(),
                new ShellToolbarModels(
                        models.home(),
                        models.instances(),
                        models.accounts(),
                        models.gameDirectories(),
                        new ShellRecentSelections(SettingsManager.settings())),
                presentation.home(),
                presentation.taskProgress(),
                animator,
                presentation.pageTransitionDuration(),
                presentation.taskProgressAnimationDuration());
        SwingInstanceJsonImportLauncher.install(
                frame,
                GameDirectoryManager::getSelectedRepository,
                Schedulers.io(),
                presentation.taskProgress(),
                animator,
                presentation.taskProgressAnimationDuration());
        return new AppShellApplicationWindow(frame);
    }

    /// Closes all resources after partial construction and suppresses cleanup failures.
    ///
    /// @param resources already-created resources in dependency-safe close order
    /// @param constructionFailure original construction failure
    private static void closeAllAfterFailure(
            List<? extends AutoCloseable> resources,
            Throwable constructionFailure) {
        for (AutoCloseable resource : resources) {
            closeAfterFailure(resource, constructionFailure);
        }
    }

    /// Closes one partially constructed resource and suppresses cleanup failures.
    ///
    /// @param resource resource to close
    /// @param constructionFailure original construction failure
    private static void closeAfterFailure(AutoCloseable resource, Throwable constructionFailure) {
        try {
            resource.close();
        } catch (Throwable closingFailure) {
            if (constructionFailure != closingFailure) {
                constructionFailure.addSuppressed(closingFailure);
            }
        }
    }

    /// Closes one lifecycle resource while preserving the first cleanup failure.
    ///
    /// @param resource resource to close
    /// @param previous first prior failure, or null
    /// @return first failure with any later failure suppressed
    private static @Nullable Throwable closeCollecting(
            AutoCloseable resource,
            @Nullable Throwable previous) {
        try {
            resource.close();
            return previous;
        } catch (Throwable current) {
            return accumulateFailure(previous, current);
        }
    }

    /// Runs one cleanup action while preserving the first cleanup failure.
    ///
    /// @param action cleanup action
    /// @param previous first prior failure, or null
    /// @return first failure with any later failure suppressed
    private static @Nullable Throwable runCollecting(Runnable action, @Nullable Throwable previous) {
        try {
            action.run();
            return previous;
        } catch (Throwable current) {
            return accumulateFailure(previous, current);
        }
    }

    /// Accumulates one cleanup failure without skipping later resources.
    ///
    /// @param previous first failure, or null
    /// @param current next failure
    /// @return first failure with later failures suppressed
    private static Throwable accumulateFailure(@Nullable Throwable previous, Throwable current) {
        Objects.requireNonNull(current, "current");
        if (previous == null) {
            return current;
        }
        if (previous != current) {
            previous.addSuppressed(current);
        }
        return previous;
    }

    /// Rethrows an accumulated cleanup result without losing unchecked failure types.
    ///
    /// @param failure accumulated failure, or null when cleanup succeeded
    private static void rethrowFailure(@Nullable Throwable failure) {
        if (failure == null) {
            return;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Failed to close Swing application composition", failure);
    }

    /// Ordered factories used by the production page-model construction transaction.
    ///
    /// @param home launcher-home model factory receiving the shared add-instance command
    /// @param gameDirectories configured game-directory selection service factory
    /// @param instanceManagement dynamic instance-management coordinator factory borrowing the created home model
    /// @param instances installed-instance model factory receiving the registered coordinator and shared
    ///                  add-instance command
    /// @param gameVersionSource game-version catalog source factory
    /// @param gameVersions game-version model factory receiving the registered source
    /// @param gameInstaller single-flight vanilla installation service factory
    /// @param accounts account-selection model factory
    /// @param appearance appearance-settings model factory
    @NotNullByDefault
    record ProductionPageModelFactories(
            Function<Runnable, ? extends HomeModel> home,
            Supplier<? extends GameDirectoryManagementService> gameDirectories,
            Function<HomeModel, ? extends InstanceManagementCoordinator> instanceManagement,
            BiFunction<InstanceManagementCoordinator, Runnable, ? extends InstancesModel> instances,
            Supplier<? extends GameVersionCatalogSource> gameVersionSource,
            Function<GameVersionCatalogSource, ? extends GameVersionCatalogModel> gameVersions,
            Supplier<? extends GameInstallService> gameInstaller,
            Supplier<? extends AccountsModel> accounts,
            Supplier<? extends AppearanceSettingsModel> appearance) {
        /// Validates every factory before production construction starts.
        ProductionPageModelFactories {
            Objects.requireNonNull(home, "home");
            Objects.requireNonNull(gameDirectories, "gameDirectories");
            Objects.requireNonNull(instanceManagement, "instanceManagement");
            Objects.requireNonNull(instances, "instances");
            Objects.requireNonNull(gameVersionSource, "gameVersionSource");
            Objects.requireNonNull(gameVersions, "gameVersions");
            Objects.requireNonNull(gameInstaller, "gameInstaller");
            Objects.requireNonNull(accounts, "accounts");
            Objects.requireNonNull(appearance, "appearance");
        }
    }

    /// Captures launcher stores and follows the selected repository with idempotent cleanup.
    @NotNullByDefault
    private static final class LauncherBindings implements AutoCloseable {
        /// Loaded launcher settings retained only for EDT-confined theme resolution and persistence.
        private final LauncherSettings settings;

        /// Exact selected theme captured on the Swing EDT.
        private final ThemeReference initialTheme;

        /// Current selected game repository, updated on the Swing EDT.
        private volatile XYMLGameRepository repository;

        /// Subscription keeping the cross-thread repository snapshot current.
        private final Subscription repositorySubscription;

        /// Launcher account and instance projection for the home model.
        private final LauncherHomeStore homeStore;

        /// Launcher account-list projection and selection sink.
        private final LauncherAccountStore accountStore;

        /// Launcher appearance persistence adapter.
        private final LauncherAppearanceStore appearanceStore;

        /// Prevents repeated launcher-store cleanup.
        private final AtomicBoolean closed = new AtomicBoolean();

        /// Creates complete captured launcher bindings.
        ///
        /// @param repository real selected game repository
        /// @param settings loaded launcher settings
        /// @param initialTheme exact selected theme captured on the Swing EDT
        /// @param homeStore home selection store
        /// @param accountStore account selection store
        /// @param appearanceStore appearance persistence store
        private LauncherBindings(
                XYMLGameRepository repository,
                LauncherSettings settings,
                ThemeReference initialTheme,
                LauncherHomeStore homeStore,
                LauncherAccountStore accountStore,
                LauncherAppearanceStore appearanceStore) {
            this.repository = Objects.requireNonNull(repository, "repository");
            this.settings = Objects.requireNonNull(settings, "settings");
            this.initialTheme = Objects.requireNonNull(initialTheme, "initialTheme");
            this.homeStore = Objects.requireNonNull(homeStore, "homeStore");
            this.accountStore = Objects.requireNonNull(accountStore, "accountStore");
            this.appearanceStore = Objects.requireNonNull(appearanceStore, "appearanceStore");
            repositorySubscription = GameDirectoryManager.selectedRepositoryProperty().subscribe(change -> {
                @Nullable XYMLGameRepository selectedRepository = change.currentValue();
                if (selectedRepository != null) {
                    this.repository = selectedRepository;
                }
            });
        }

        /// Creates all adapters directly on the initialized Swing event dispatch thread.
        ///
        /// @return complete launcher bindings
        private static LauncherBindings create() {
            LauncherStateDispatcher.requireEventThread();
            XYMLGameRepository repository = GameDirectoryManager.getSelectedRepository();
            LauncherSettings settings = SettingsManager.settings();
            ThemeReference initialTheme = settings.getSelectedThemeOrDefault();
            @Nullable LauncherHomeStore homeStore = null;
            @Nullable LauncherAccountStore accountStore = null;
            @Nullable LauncherAppearanceStore appearanceStore = null;
            try {
                homeStore = new LauncherHomeStore();
                accountStore = new LauncherAccountStore();
                appearanceStore = LauncherAppearanceStore.createForCurrentSettings();
                return new LauncherBindings(
                        repository,
                        settings,
                        initialTheme,
                        homeStore,
                        accountStore,
                        appearanceStore);
            } catch (RuntimeException | Error failure) {
                closeNullableAfterFailure(homeStore, failure);
                closeNullableAfterFailure(accountStore, failure);
                closeNullableAfterFailure(appearanceStore, failure);
                throw failure;
            }
        }

        /// Returns the captured selected repository.
        ///
        /// @return selected game repository
        private XYMLGameRepository repository() {
            return repository;
        }

        /// Returns the loaded settings for EDT-confined theme operations.
        ///
        /// @return loaded launcher settings
        private LauncherSettings settings() {
            return settings;
        }

        /// Returns the exact theme reference captured during startup composition.
        ///
        /// @return initially selected theme reference
        private ThemeReference initialTheme() {
            return initialTheme;
        }

        /// Returns the home selection store.
        ///
        /// @return home selection store
        private LauncherHomeStore homeStore() {
            return homeStore;
        }

        /// Returns the account selection store.
        ///
        /// @return account selection store
        private LauncherAccountStore accountStore() {
            return accountStore;
        }

        /// Returns the appearance persistence store.
        ///
        /// @return appearance persistence store
        private LauncherAppearanceStore appearanceStore() {
            return appearanceStore;
        }

        /// Closes every launcher adapter at most once without closing process-wide executors.
        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }

            @Nullable Throwable failure = null;
            failure = closeCollecting(repositorySubscription, failure);
            failure = closeCollecting(homeStore, failure);
            failure = closeCollecting(accountStore, failure);
            failure = closeCollecting(appearanceStore, failure);
            rethrowFailure(failure);
        }

        /// Closes an adapter created before a later launcher adapter failed.
        ///
        /// @param resource partially created adapter, or null
        /// @param constructionFailure original construction failure
        private static void closeNullableAfterFailure(
                @Nullable AutoCloseable resource,
                Throwable constructionFailure) {
            if (resource != null) {
                closeAfterFailure(resource, constructionFailure);
            }
        }
    }

    /// Adapts [AppShellFrame] to a thread-safe idempotent composition window contract.
    @NotNullByDefault
    private static final class AppShellApplicationWindow implements SwingApplicationWindow {
        /// Real production Swing frame.
        private final AppShellFrame frame;

        /// Cleanup callback installed after the composition is fully constructed.
        private final AtomicReference<@Nullable Runnable> closedHandler = new AtomicReference<>();

        /// Prevents repeated native-frame disposal.
        private final AtomicBoolean closed = new AtomicBoolean();

        /// Prevents repeated delivery of the native-close callback.
        private final AtomicBoolean closeNotificationDelivered = new AtomicBoolean();

        /// Creates the frame adapter and observes native disposal.
        ///
        /// @param frame real application shell frame
        private AppShellApplicationWindow(AppShellFrame frame) {
            this.frame = Objects.requireNonNull(frame, "frame");
            EdtDispatcher.executeAndWait(() -> frame.addWindowListener(new WindowCloseListener(this)));
        }

        /// Installs one composition cleanup callback.
        ///
        /// @param handler callback delivered after native disposal
        @Override
        public void setClosedHandler(Runnable handler) {
            Objects.requireNonNull(handler, "handler");
            if (!closedHandler.compareAndSet(null, handler)) {
                throw new IllegalStateException("Window close handler is already installed");
            }
            notifyClosedHandler();
        }

        /// Opens the real frame.
        @Override
        public void open() {
            EdtDispatcher.executeAndWait(() -> {
                if (closed.get()) {
                    throw new IllegalStateException("Swing application window is closed");
                }
                frame.open();
            });
        }

        /// Hides the real frame on the EDT unless native disposal already started.
        @Override
        public void hide() {
            EdtDispatcher.executeAndWait(() -> {
                if (!closed.get()) {
                    frame.hideWindow();
                }
            });
        }

        /// Returns the stable production frame used to own native dialogs.
        ///
        /// @return production application frame
        @Override
        public Component dialogOwner() {
            return frame;
        }

        /// Enables or disables production-frame interaction on the EDT.
        ///
        /// @param enabled whether hosted pages accept user input
        @Override
        public void setInteractionEnabled(boolean enabled) {
            EdtDispatcher.executeAndWait(() -> {
                if (closed.get()) {
                    throw new IllegalStateException("Swing application window is closed");
                }
                frame.setInteractionEnabled(enabled);
            });
        }

        /// Routes page commands through the hosted application shell.
        ///
        /// @param page destination selected by a page command
        @Override
        public void navigateTo(ShellPageId page) {
            Objects.requireNonNull(page, "page");
            if (closed.get()) {
                throw new IllegalStateException("Swing application window is closed");
            }
            EdtDispatcher.execute(() -> {
                if (!closed.get()) {
                    frame.shellPanel().navigateTo(page);
                }
            });
        }

        /// Disposes the frame on the EDT at most once.
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                EdtDispatcher.executeAndWait(frame::dispose);
            }
            notifyClosedHandler();
        }

        /// Records native disposal and delivers the registered cleanup callback.
        private void nativeWindowClosed() {
            closed.set(true);
            notifyClosedHandler();
        }

        /// Delivers a registered callback once after the frame is known to be closed.
        private void notifyClosedHandler() {
            @Nullable Runnable handler = closedHandler.get();
            if (closed.get() && handler != null && closeNotificationDelivered.compareAndSet(false, true)) {
                handler.run();
            }
        }
    }

    /// Forwards one native frame-disposal event to its application-window adapter.
    @NotNullByDefault
    private static final class WindowCloseListener extends WindowAdapter {
        /// Window adapter receiving native close notification.
        private final AppShellApplicationWindow window;

        /// Creates the native close listener.
        ///
        /// @param window window adapter receiving the event
        private WindowCloseListener(AppShellApplicationWindow window) {
            this.window = Objects.requireNonNull(window, "window");
        }

        /// Marks the adapter closed after native frame disposal.
        ///
        /// @param event native window event
        @Override
        public void windowClosed(WindowEvent event) {
            Objects.requireNonNull(event, "event");
            window.nativeWindowClosed();
        }
    }
}
