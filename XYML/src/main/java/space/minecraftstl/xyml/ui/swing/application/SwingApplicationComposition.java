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
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.setting.GameDirectoryManager;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.MotionPolicy;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.SwingDesignTokens;
import space.minecraftstl.xyml.ui.swing.SwingThemeManager;
import space.minecraftstl.xyml.ui.swing.SystemThemeDetector;
import space.minecraftstl.xyml.ui.swing.ThemeMode;
import space.minecraftstl.xyml.ui.swing.legacy.LegacyJavaFxDispatcher;
import space.minecraftstl.xyml.ui.swing.page.accounts.AccountsPanel;
import space.minecraftstl.xyml.ui.swing.page.accounts.LauncherAccountsModel;
import space.minecraftstl.xyml.ui.swing.page.accounts.LegacyLauncherAccountStore;
import space.minecraftstl.xyml.ui.swing.page.home.HomePanel;
import space.minecraftstl.xyml.ui.swing.page.home.LauncherHomeModel;
import space.minecraftstl.xyml.ui.swing.page.home.LegacyLauncherHomeStore;
import space.minecraftstl.xyml.ui.swing.page.instances.InstancesPanel;
import space.minecraftstl.xyml.ui.swing.page.instances.RepositoryInstancesModel;
import space.minecraftstl.xyml.ui.swing.page.settings.AppearanceSettingsPanel;
import space.minecraftstl.xyml.ui.swing.page.settings.LegacyLauncherAppearanceStore;
import space.minecraftstl.xyml.ui.swing.page.settings.PersistedAppearanceSettingsModel;
import space.minecraftstl.xyml.ui.swing.page.settings.StoredAppearanceSettings;
import space.minecraftstl.xyml.ui.swing.shell.AppShellFrame;
import space.minecraftstl.xyml.ui.swing.shell.ShellPageFactory;
import space.minecraftstl.xyml.ui.swing.shell.ShellPageId;

import javax.swing.JComponent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/// Owns the transitional Swing application window, page models, legacy stores, and animation lifecycle.
///
/// The production entry point deliberately remains separate from `Launcher` while the migration is staged.
/// Startup may call [#createAfterJavaFxInitialization] only after Accounts, game directories, and settings
/// have been initialized on the legacy JavaFX runtime. No caller-owned executor is stopped by this class.
@NotNullByDefault
public final class SwingApplicationComposition implements AutoCloseable {
    /// Native-window abstraction backed by [AppShellFrame] in production.
    private final SwingApplicationWindow window;

    /// Four page models and their ordered model/store lifecycle.
    private final SwingApplicationPageModels pageModels;

    /// Shared animator whose active timers are cancelled during cleanup.
    private final SwingAnimator animator;

    /// Prevents repeated window, timer, model, and store cleanup.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Creates the completed application lifecycle.
    ///
    /// @param window native-window adapter
    /// @param pageModels page models and owned resources
    /// @param animator shared animator
    private SwingApplicationComposition(
            SwingApplicationWindow window,
            SwingApplicationPageModels pageModels,
            SwingAnimator animator) {
        this.window = Objects.requireNonNull(window, "window");
        this.pageModels = Objects.requireNonNull(pageModels, "pageModels");
        this.animator = Objects.requireNonNull(animator, "animator");
    }

    /// Creates the production bridge after the legacy JavaFX runtime and state managers are initialized.
    ///
    /// Legacy stores and the selected repository are captured on the JavaFX application thread. Instance
    /// viewport work uses the process-wide caller-owned [Schedulers#io()] executor, which this composition
    /// never closes. All workflow commands and the download component factory remain startup-owned.
    ///
    /// @param presentation localized text and explicit transition policy
    /// @param commands startup-owned download and legacy workflow boundaries
    /// @param systemThemeDetector fast operating-system appearance detector
    /// @param animationFrameDelayMillis positive Swing animation timer delay
    /// @return closed-resource-safe Swing application composition
    public static SwingApplicationComposition createAfterJavaFxInitialization(
            SwingApplicationPresentation presentation,
            SwingApplicationCommands commands,
            SystemThemeDetector systemThemeDetector,
            int animationFrameDelayMillis) {
        Objects.requireNonNull(presentation, "presentation");
        Objects.requireNonNull(commands, "commands");
        Objects.requireNonNull(systemThemeDetector, "systemThemeDetector");
        if (animationFrameDelayMillis <= 0) {
            throw new IllegalArgumentException("animationFrameDelayMillis must be positive");
        }

        AtomicReference<@Nullable LegacyBindings> legacyResult = new AtomicReference<>();
        LegacyJavaFxDispatcher.executeAndWait(() -> legacyResult.set(LegacyBindings.create()));
        LegacyBindings legacy = Objects.requireNonNull(legacyResult.get(), "legacy bindings were not created");

        StoredAppearanceSettings rawAppearance = legacy.appearanceStore().snapshot();
        SwingThemeManager themeManager = new SwingThemeManager(
                ThemeMode.fromSettingValue(rawAppearance.themeModeValue()),
                new SwingDesignTokens(rawAppearance.cornerRadius()),
                systemThemeDetector);
        SwingAnimator animator = new SwingAnimator(
                rawAppearance.animationsDisabled() ? MotionPolicy.OFF : MotionPolicy.FULL,
                animationFrameDelayMillis);

        try {
            return createForCollaborators(
                    navigateCommand -> createProductionModels(
                            legacy,
                            commands,
                            presentation,
                            navigateCommand,
                            themeManager,
                            animator),
                    presentation,
                    commands.downloadsPageFactory(),
                    themeManager,
                    animator,
                    SwingApplicationComposition::createFrameWindow);
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(legacy, failure);
            throw failure;
        }
    }

    /// Creates a composition from explicit model and window factories without requiring JavaFX.
    ///
    /// This is the integration boundary for focused lifecycle tests and future non-JavaFX startup. The
    /// composition owns the returned model bundle, animator, and window but does not infer ownership of
    /// any executor captured by those collaborators.
    ///
    /// @param modelFactory model factory receiving the shell-backed navigation command
    /// @param presentation localized text and explicit transition policy
    /// @param downloadsPageFactory lazy caller-supplied download component factory
    /// @param themeManager active Swing theme manager
    /// @param animator shared animator owned by the returned composition
    /// @param windowFactory explicit native-window factory
    /// @return composed application lifecycle
    public static SwingApplicationComposition createForCollaborators(
            SwingApplicationPageModelFactory modelFactory,
            SwingApplicationPresentation presentation,
            ShellPageFactory<? extends JComponent> downloadsPageFactory,
            SwingThemeManager themeManager,
            SwingAnimator animator,
            SwingApplicationWindowFactory windowFactory) {
        Objects.requireNonNull(modelFactory, "modelFactory");
        Objects.requireNonNull(presentation, "presentation");
        Objects.requireNonNull(downloadsPageFactory, "downloadsPageFactory");
        Objects.requireNonNull(themeManager, "themeManager");
        Objects.requireNonNull(animator, "animator");
        Objects.requireNonNull(windowFactory, "windowFactory");

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
                createPageFactories(models, presentation, downloadsPageFactory);
        final SwingApplicationWindow createdWindow;
        try {
            createdWindow = Objects.requireNonNull(
                    windowFactory.createWindow(themeManager, pageFactories, presentation, animator),
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
                animator);
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

    /// Returns whether this lifecycle has released its window, timers, models, and stores.
    ///
    /// @return `true` after the first close request begins cleanup
    public boolean isClosed() {
        return closed.get();
    }

    /// Disposes the frame, cancels animations, and closes models and stores exactly once.
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
        rethrowFailure(failure);
    }

    /// Builds lazy Swing component factories without instantiating any destination page.
    ///
    /// @param models toolkit-neutral page models
    /// @param presentation localized page text
    /// @param downloadsPageFactory caller-owned lazy download page factory
    /// @return complete immutable page factory table
    private static @Unmodifiable Map<ShellPageId, ShellPageFactory<? extends JComponent>> createPageFactories(
            SwingApplicationPageModels models,
            SwingApplicationPresentation presentation,
            ShellPageFactory<? extends JComponent> downloadsPageFactory) {
        EnumMap<ShellPageId, ShellPageFactory<? extends JComponent>> factories =
                new EnumMap<>(ShellPageId.class);
        factories.put(ShellPageId.HOME, () -> new HomePanel(models.home(), presentation.home()));
        factories.put(ShellPageId.INSTANCES, () -> new InstancesPanel(models.instances(), presentation.instances()));
        factories.put(ShellPageId.DOWNLOADS, downloadsPageFactory);
        factories.put(ShellPageId.ACCOUNTS, () -> new AccountsPanel(models.accounts(), presentation.accounts()));
        factories.put(
                ShellPageId.SETTINGS,
                () -> new AppearanceSettingsPanel(models.appearance(), presentation.appearance()));
        return Map.copyOf(factories);
    }

    /// Creates production models and records model-before-store cleanup order.
    ///
    /// @param legacy captured JavaFX store bindings and selected repository
    /// @param commands startup-owned workflow boundaries
    /// @param presentation localized model status text
    /// @param navigateCommand shell-backed navigation command
    /// @param themeManager active theme manager
    /// @param animator shared animator
    /// @return owned production page-model bundle
    private static SwingApplicationPageModels createProductionModels(
            LegacyBindings legacy,
            SwingApplicationCommands commands,
            SwingApplicationPresentation presentation,
            Consumer<ShellPageId> navigateCommand,
            SwingThemeManager themeManager,
            SwingAnimator animator) {
        List<AutoCloseable> models = new ArrayList<>(4);
        try {
            LauncherHomeModel home = new LauncherHomeModel(
                    legacy.homeStore(),
                    presentation.homeStatus(),
                    () -> navigateCommand.accept(ShellPageId.ACCOUNTS),
                    () -> navigateCommand.accept(ShellPageId.INSTANCES),
                    commands.addInstanceCommand(),
                    commands.launchCommand());
            models.add(home);

            RepositoryInstancesModel instances = new RepositoryInstancesModel(
                    legacy.repository(),
                    Schedulers.io(),
                    commands.addInstanceCommand(),
                    commands.manageInstanceCommand(),
                    presentation.instancesStatus());
            models.add(instances);

            LauncherAccountsModel accounts = new LauncherAccountsModel(
                    legacy.accountStore(),
                    commands.addAccountCommand());
            models.add(accounts);

            PersistedAppearanceSettingsModel appearance = new PersistedAppearanceSettingsModel(
                    legacy.appearanceStore(),
                    themeManager,
                    animator);
            models.add(appearance);

            List<AutoCloseable> resources = new ArrayList<>(7);
            resources.addAll(models);
            resources.add(legacy.homeStore());
            resources.add(legacy.accountStore());
            resources.add(legacy.appearanceStore());
            return new SwingApplicationPageModels(home, instances, accounts, appearance, resources);
        } catch (RuntimeException | Error failure) {
            closeAllAfterFailure(models, failure);
            closeAfterFailure(legacy, failure);
            throw failure;
        }
    }

    /// Creates the production [AppShellFrame] adapter.
    ///
    /// @param themeManager active theme manager
    /// @param pageFactories complete lazy page table
    /// @param presentation localized shell presentation
    /// @param animator shared animator
    /// @return native Swing window adapter
    private static SwingApplicationWindow createFrameWindow(
            SwingThemeManager themeManager,
            @Unmodifiable Map<ShellPageId, ShellPageFactory<? extends JComponent>> pageFactories,
            SwingApplicationPresentation presentation,
            SwingAnimator animator) {
        AppShellFrame frame = AppShellFrame.create(
                presentation.windowTitle(),
                themeManager,
                pageFactories,
                ShellPageId.HOME,
                presentation.shellPages(),
                animator,
                presentation.pageTransitionDuration());
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
            constructionFailure.addSuppressed(closingFailure);
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
        previous.addSuppressed(current);
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

    /// Captures legacy JavaFX stores and one real selected repository with idempotent cleanup.
    @NotNullByDefault
    private static final class LegacyBindings implements AutoCloseable {
        /// Selected game repository captured after game-directory initialization.
        private final XYMLGameRepository repository;

        /// Legacy account and instance projection for the home model.
        private final LegacyLauncherHomeStore homeStore;

        /// Legacy account-list projection and selection sink.
        private final LegacyLauncherAccountStore accountStore;

        /// Legacy appearance persistence adapter.
        private final LegacyLauncherAppearanceStore appearanceStore;

        /// Prevents repeated legacy-store cleanup.
        private final AtomicBoolean closed = new AtomicBoolean();

        /// Creates complete captured legacy bindings.
        ///
        /// @param repository real selected game repository
        /// @param homeStore home selection store
        /// @param accountStore account selection store
        /// @param appearanceStore appearance persistence store
        private LegacyBindings(
                XYMLGameRepository repository,
                LegacyLauncherHomeStore homeStore,
                LegacyLauncherAccountStore accountStore,
                LegacyLauncherAppearanceStore appearanceStore) {
            this.repository = Objects.requireNonNull(repository, "repository");
            this.homeStore = Objects.requireNonNull(homeStore, "homeStore");
            this.accountStore = Objects.requireNonNull(accountStore, "accountStore");
            this.appearanceStore = Objects.requireNonNull(appearanceStore, "appearanceStore");
        }

        /// Creates all adapters directly on the initialized JavaFX application thread.
        ///
        /// @return complete legacy bindings
        private static LegacyBindings create() {
            LegacyJavaFxDispatcher.requireEventThread();
            XYMLGameRepository repository = GameDirectoryManager.getSelectedRepository();
            @Nullable LegacyLauncherHomeStore homeStore = null;
            @Nullable LegacyLauncherAccountStore accountStore = null;
            @Nullable LegacyLauncherAppearanceStore appearanceStore = null;
            try {
                homeStore = new LegacyLauncherHomeStore();
                accountStore = new LegacyLauncherAccountStore();
                appearanceStore = LegacyLauncherAppearanceStore.createForCurrentSettings();
                return new LegacyBindings(repository, homeStore, accountStore, appearanceStore);
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

        /// Returns the home selection store.
        ///
        /// @return home selection store
        private LegacyLauncherHomeStore homeStore() {
            return homeStore;
        }

        /// Returns the account selection store.
        ///
        /// @return account selection store
        private LegacyLauncherAccountStore accountStore() {
            return accountStore;
        }

        /// Returns the appearance persistence store.
        ///
        /// @return appearance persistence store
        private LegacyLauncherAppearanceStore appearanceStore() {
            return appearanceStore;
        }

        /// Closes every legacy adapter at most once without closing process-wide executors.
        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }

            @Nullable Throwable failure = null;
            failure = closeCollecting(homeStore, failure);
            failure = closeCollecting(accountStore, failure);
            failure = closeCollecting(appearanceStore, failure);
            rethrowFailure(failure);
        }

        /// Closes an adapter created before a later legacy adapter failed.
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
            if (closed.get()) {
                throw new IllegalStateException("Swing application window is closed");
            }
            frame.open();
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
