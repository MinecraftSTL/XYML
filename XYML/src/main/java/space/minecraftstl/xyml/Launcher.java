/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.XYMLCacheRepository;
import space.minecraftstl.xyml.java.JavaManager;
import space.minecraftstl.xyml.setting.*;
import space.minecraftstl.xyml.task.AsyncTaskExecutor;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;
import space.minecraftstl.xyml.ui.swing.NativeSystemThemeDetector;
import space.minecraftstl.xyml.ui.swing.SystemThemeDetector;
import space.minecraftstl.xyml.ui.swing.application.SwingApplicationPresentation;
import space.minecraftstl.xyml.ui.swing.application.SwingApplicationPresentationFactory;
import space.minecraftstl.xyml.ui.swing.runtime.LauncherStateDispatcher;
import space.minecraftstl.xyml.ui.swing.runtime.SwingApplicationRuntime;
import space.minecraftstl.xyml.ui.swing.runtime.SwingStartupPrompts;
import space.minecraftstl.xyml.ui.swing.launch.SwingLaunchInteraction;
import space.minecraftstl.xyml.ui.swing.page.accounts.AccountReauthentication;
import space.minecraftstl.xyml.ui.swing.page.accounts.AccountCreationWorkflowHandle;
import space.minecraftstl.xyml.ui.swing.page.accounts.SwingAccountCreationWorkflow;
import space.minecraftstl.xyml.ui.swing.page.accounts.SwingAccountReauthentication;
import space.minecraftstl.xyml.ui.swing.startup.StartupPromptCoordinator;
import space.minecraftstl.xyml.ui.swing.startup.SwingStartupPromptPresenter;
import space.minecraftstl.xyml.ui.swing.startup.SwingStartupSafetyDialogs;
import space.minecraftstl.xyml.ui.swing.update.SwingUpdateCheckService;
import space.minecraftstl.xyml.ui.swing.update.SwingUpdateNotificationController;
import space.minecraftstl.xyml.ui.swing.update.SwingUpdatePromptPresenter;
import space.minecraftstl.xyml.ui.swing.update.UpdateCheckRequest;
import space.minecraftstl.xyml.ui.swing.update.UpdateCheckResult;
import space.minecraftstl.xyml.upgrade.UpdateApplier;
import space.minecraftstl.xyml.upgrade.UpdateChannel;
import space.minecraftstl.xyml.upgrade.UpdateStartupResult;
import space.minecraftstl.xyml.util.*;
import space.minecraftstl.xyml.util.io.FileUtils;
import space.minecraftstl.xyml.util.io.JarUtils;
import space.minecraftstl.xyml.util.platform.*;

import java.awt.Component;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static space.minecraftstl.xyml.setting.SettingsManager.settings;
import static space.minecraftstl.xyml.util.DataSizeUnit.MEGABYTES;
import static space.minecraftstl.xyml.util.i18n.I18n.i18n;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;
import static space.minecraftstl.xyml.ui.swing.startup.SwingStartupSafetyDialogs.Severity.ERROR;
import static space.minecraftstl.xyml.ui.swing.startup.SwingStartupSafetyDialogs.Severity.INFO;
import static space.minecraftstl.xyml.ui.swing.startup.SwingStartupSafetyDialogs.Severity.WARNING;

/// Initializes launcher services and owns the native Swing application lifecycle.
@NotNullByDefault
public final class Launcher {
    /// Shared HTTP cookie manager installed before account and download services start.
    public static final CookieManager COOKIE_MANAGER = new CookieManager();

    /// Page transition duration chosen for a short, legible navigation change without delaying repeated work.
    private static final java.time.Duration SWING_PAGE_TRANSITION_DURATION =
            java.time.Duration.ofMillis(180L);

    /// Task-surface transition duration kept slightly shorter than full page navigation.
    private static final java.time.Duration SWING_TASK_TRANSITION_DURATION =
            java.time.Duration.ofMillis(140L);

    /// Swing timer delay targeting approximately sixty animation updates per second.
    private static final int SWING_ANIMATION_FRAME_DELAY_MILLIS = Math.max(
            1,
            Integer.getInteger("xyml.swing.animationFrameDelayMillis", 16));

    /// Process-wide active Swing runtime used to enforce a single native application owner.
    private static final AtomicReference<@Nullable SwingApplicationRuntime> ACTIVE_SWING_RUNTIME =
            new AtomicReference<>();

    /// Process-wide launcher owner used to enforce a single launcher lifecycle.
    private static final AtomicReference<@Nullable Launcher> ACTIVE_LAUNCHER = new AtomicReference<>();

    /// Latest successful native update-check result used when selecting the launcher crash headline.
    private static final AtomicBoolean LAUNCHER_UPDATE_AVAILABLE = new AtomicBoolean();

    /// Prevents repeated application-stop cleanup.
    private final AtomicBoolean stopped = new AtomicBoolean();

    /// Runtime owned by this launcher instance, or null before successful creation.
    private @Nullable SwingApplicationRuntime swingRuntime;

    /// Swing startup prompt sequence, or null before the production window opens.
    private @Nullable StartupPromptCoordinator startupPromptCoordinator;

    /// Active native account-creation workflow, or null before the first add-account request.
    private final AtomicReference<@Nullable AccountCreationWorkflowHandle> accountCreationWorkflow =
            new AtomicReference<>();

    /// Serializes account-workflow creation against cross-toolkit application shutdown.
    private final Object accountCreationLifecycleLock = new Object();

    /// Serializes native update-resource publication against cross-toolkit application shutdown.
    private final Object swingUpdateLifecycleLock = new Object();

    /// Native update-check service, or null until startup prompts permit background work.
    private @Nullable SwingUpdateCheckService swingUpdateCheckService;

    /// Automatic native update notification subscription, or null when disabled or not yet initialized.
    private @Nullable SwingUpdateNotificationController swingUpdateNotifications;

    /// Initializes launcher services, displays startup warnings, and opens the native Swing window.
    public void start() {
        Thread.currentThread().setUncaughtExceptionHandler(CRASH_REPORTER);
        Schedulers.installUiExecutor(SwingUiDispatcher.INSTANCE::dispatch);

        CookieHandler.setDefault(COOKIE_MANAGER);

        LOG.info("UI Toolkit: Swing/AWT");
        LOG.info("Headless Graphics Environment: " + java.awt.GraphicsEnvironment.isHeadless());

        try {
            initializeSettingsRuntime();

            if (Metadata.SKIP_OFFLINE_USERNAME_CHECK) {
                LOG.warning(Metadata.SKIP_OFFLINE_USERNAME_CHECK_ENVIRONMENT_VARIABLE
                        + " is enabled; illegal offline usernames will not be checked.");
                SwingStartupSafetyDialogs.showMessage(
                        WARNING,
                        i18n("account.methods.offline.name.check_disabled"));
            }

            // https://lapcatsoftware.com/articles/app-translocation.html
            if (OperatingSystem.CURRENT_OS == OperatingSystem.MACOS
                    && SettingsManager.isNewlyCreated()
                    && System.getProperty("user.dir").startsWith("/private/var/folders/")) {
                if (!SwingStartupSafetyDialogs.confirmWithCountdown(
                        WARNING,
                        i18n("fatal.mac_app_translocation"),
                        5)) {
                    stop();
                    return;
                }
            } else {
                checkConfigInTempDir();
            }

            if (SettingsManager.isOwnerChanged()) {
                if (!SwingStartupSafetyDialogs.confirm(
                        WARNING,
                        i18n("fatal.config_change_owner_root"))) {
                    stop();
                    return;
                }
            }

            if (SettingsManager.hasReadOnlyCoreSettings()) {
                SwingStartupSafetyDialogs.showMessage(
                        WARNING,
                        i18n("fatal.config_unsupported_version"));
            }

            if (Metadata.XYML_LOCAL_HOME.toString().indexOf('=') >= 0) {
                SwingStartupSafetyDialogs.showMessage(WARNING, i18n("fatal.illegal_char"));
            }

            Lang.thread(JavaManager::initialize, "Search Java", true);
            startSwingWindow();
        } catch (Throwable e) {
            try {
                CRASH_REPORTER.uncaughtException(Thread.currentThread(), e);
            } finally {
                try {
                    stop();
                } catch (Throwable cleanupFailure) {
                    if (cleanupFailure != e) {
                        e.addSuppressed(cleanupFailure);
                    }
                }
            }
        }
    }

    /// Creates and opens the production Swing runtime.
    private void startSwingWindow() {
        try {
            boolean acceptPreviewUpdate = settings().acceptPreviewUpdateProperty().get();
            boolean disableAutomaticUpdatePrompt = settings().disableAutoShowUpdateDialogProperty().get();
            SwingApplicationPresentation presentation = SwingApplicationPresentationFactory.create(
                    SWING_PAGE_TRANSITION_DURATION,
                    SWING_TASK_TRANSITION_DURATION);
            AtomicReference<@Nullable SwingApplicationRuntime> dialogRuntime = new AtomicReference<>();
            Supplier<@Nullable Component> dialogOwner = () -> {
                @Nullable SwingApplicationRuntime currentRuntime = dialogRuntime.get();
                return currentRuntime == null || currentRuntime.isClosed()
                        ? null
                        : currentRuntime.dialogOwner();
            };
            SwingLaunchInteraction launchInteraction = new SwingLaunchInteraction(dialogOwner);
            AccountReauthentication accountReauthentication = SwingAccountReauthentication.create(
                    dialogOwner,
                    Schedulers.io());
            SwingApplicationRuntime runtime;
            try {
                runtime = SwingApplicationRuntime.create(
                        presentation,
                        this::openSwingAccountDialog,
                        launchInteraction,
                        accountReauthentication,
                        captureSystemThemeDetector(),
                        SWING_ANIMATION_FRAME_DELAY_MILLIS,
                        Schedulers.io(),
                        this::stop);
            } catch (RuntimeException | Error creationFailure) {
                closeAfterFailure(accountReauthentication, creationFailure);
                throw creationFailure;
            }
            dialogRuntime.set(runtime);
            swingRuntime = runtime;
            if (!ACTIVE_SWING_RUNTIME.compareAndSet(null, runtime)) {
                runtime.close();
                throw new IllegalStateException("Another Swing application runtime is already active");
            }
            try {
                runtime.setInteractionEnabled(false);
                runtime.open();
                StartupPromptCoordinator prompts = SwingStartupPrompts.create(
                        new SwingStartupPromptPresenter(runtime::dialogOwner),
                        Schedulers.io(),
                        runtime::close);
                startupPromptCoordinator = prompts;
                prompts.agreementGate().whenComplete((
                        @Nullable Boolean accepted,
                        @Nullable Throwable gateFailure) -> {
                    SwingUiDispatcher.INSTANCE.dispatch(() -> {
                        if (runtime.isClosed()) {
                            return;
                        }
                        if (gateFailure != null || !Boolean.TRUE.equals(accepted)) {
                            runtime.close();
                            return;
                        }
                        try {
                            runtime.setInteractionEnabled(true);
                        } catch (IllegalStateException failure) {
                            if (!runtime.isClosed()) {
                                throw failure;
                            }
                        }
                    });
                });
                prompts.start().whenComplete((
                        @Nullable Void ignored,
                        @Nullable Throwable promptFailure) -> {
                    if (promptFailure != null) {
                        LOG.warning("Swing startup prompt sequence failed", promptFailure);
                        return;
                    }
                    SwingUiDispatcher.INSTANCE.dispatch(() -> {
                        if (!runtime.isClosed()) {
                            startSwingUpdateCheck(
                                    runtime,
                                    acceptPreviewUpdate,
                                    disableAutomaticUpdatePrompt);
                        }
                    });
                });
            } catch (RuntimeException | Error openingFailure) {
                ACTIVE_SWING_RUNTIME.compareAndSet(runtime, null);
                @Nullable StartupPromptCoordinator prompts = startupPromptCoordinator;
                startupPromptCoordinator = null;
                if (prompts != null) {
                    closeAfterFailure(prompts, openingFailure);
                }
                closeAfterFailure(runtime, openingFailure);
                throw openingFailure;
            }
        } catch (Throwable failure) {
            CRASH_REPORTER.uncaughtException(Thread.currentThread(), failure);
            stop();
        }
    }

    /// Creates the non-throwing native appearance detector used by Swing system-theme mode.
    ///
    /// @return detector that rereads the current operating-system appearance on demand
    private static SystemThemeDetector captureSystemThemeDetector() {
        return NativeSystemThemeDetector.create();
    }

    /// Opens at most one native account-creation workflow owned by the active Swing window.
    private void openSwingAccountDialog() {
        synchronized (accountCreationLifecycleLock) {
            @Nullable SwingApplicationRuntime runtime = swingRuntime;
            if (stopped.get() || runtime == null || runtime.isClosed()) {
                return;
            }

            @Nullable AccountCreationWorkflowHandle previous = accountCreationWorkflow.get();
            if (previous != null && !previous.isClosed()) {
                return;
            }

            AccountCreationWorkflowHandle created = SwingAccountCreationWorkflow.openPreferred(
                    runtime.dialogOwner(),
                    Schedulers.io());
            if (!accountCreationWorkflow.compareAndSet(previous, created)) {
                created.close();
            }
        }
    }

    /// Starts the toolkit-neutral update service after startup decisions have enabled the main window.
    ///
    /// @param runtime active native runtime used as the update-dialog owner
    /// @param acceptPreviewUpdate whether preview releases are eligible
    /// @param disableAutomaticPrompt whether successful checks must remain silent
    private void startSwingUpdateCheck(
            SwingApplicationRuntime runtime,
            boolean acceptPreviewUpdate,
            boolean disableAutomaticPrompt) {
        SwingUpdateCheckService service;
        synchronized (swingUpdateLifecycleLock) {
            if (stopped.get() || runtime.isClosed()) {
                return;
            }
            if (swingUpdateCheckService != null) {
                throw new IllegalStateException("Swing update checking was already initialized");
            }

            service = SwingUpdateCheckService.production();
            @Nullable SwingUpdateNotificationController notifications = null;
            try {
                if (!disableAutomaticPrompt) {
                    SwingUpdatePromptPresenter presenter = SwingUpdatePromptPresenter.production(
                            runtime::dialogOwner,
                            URI.create(Metadata.MANUAL_UPDATE_URL),
                            Schedulers.io());
                    notifications = new SwingUpdateNotificationController(service, presenter);
                }
            } catch (RuntimeException | Error creationFailure) {
                service.close();
                throw creationFailure;
            }
            swingUpdateCheckService = service;
            swingUpdateNotifications = notifications;
        }
        try {
            service.check(new UpdateCheckRequest(
                    UpdateChannel.getChannel(),
                    acceptPreviewUpdate)).whenComplete((
                    @Nullable UpdateCheckResult result,
                    @Nullable Throwable failure) -> {
                if (result != null) {
                    LAUNCHER_UPDATE_AVAILABLE.set(result.updateAvailable());
                }
                if (failure != null && !(failure instanceof CancellationException)) {
                    LOG.warning("Failed to check for launcher updates", failure);
                }
            });
        } catch (IllegalStateException closedRace) {
            synchronized (swingUpdateLifecycleLock) {
                if (!stopped.get() && swingUpdateCheckService == service) {
                    throw closedRace;
                }
            }
        }
    }

    /// Initializes modules and runtime services that depend on loaded settings.
    private static void initializeSettingsRuntime() {
        DownloadProviders.init();
        ProxyManager.init();
        Accounts.init();
        GameDirectoryManager.init();

        CacheRepository.setInstance(XYMLCacheRepository.REPOSITORY);
        Runnable refreshCacheDirectory = () -> {
            @Nullable String commonDirectory = settings().getResolvedCommonDirectory();
            if (commonDirectory != null && FileUtils.canCreateDirectory(commonDirectory)) {
                XYMLCacheRepository.REPOSITORY.setDirectory(commonDirectory);
            } else {
                XYMLCacheRepository.REPOSITORY.setDirectory(LauncherSettings.getDefaultCommonDirectory());
            }
        };
        refreshCacheDirectory.run();
        settings().commonDirectoryProperty().subscribe(change -> refreshCacheDirectory.run());
        settings().commonDirectoryTypeProperty().subscribe(change -> refreshCacheDirectory.run());
    }

    /// Returns whether the active config directory appears to be temporary or disposable.
    ///
    /// @return true when the config path matches a platform temporary location
    private static boolean isConfigInTempDir() {
        String configPath = SettingsManager.localConfigDirectory().toString();

        @Nullable String tmpdir = System.getProperty("java.io.tmpdir");
        if (StringUtils.isNotBlank(tmpdir) && configPath.startsWith(tmpdir))
            return true;

        String @Unmodifiable [] tempFolderNames = {"Temp", "Cache", "Caches"};
        for (String name : tempFolderNames) {
            if (configPath.contains(File.separator + name + File.separator))
                return true;
        }

        if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
            return configPath.contains("\\Temporary Internet Files\\")
                    || configPath.contains("\\INetCache\\")
                    || configPath.contains("\\$Recycle.Bin\\")
                    || configPath.contains("\\recycler\\");
        } else if (OperatingSystem.CURRENT_OS.isLinuxOrBSD()) {
            return configPath.startsWith("/tmp/")
                    || configPath.startsWith("/var/tmp/")
                    || configPath.startsWith("/var/cache/")
                    || configPath.startsWith("/dev/shm/")
                    || configPath.contains("/Trash/");
        } else if (OperatingSystem.CURRENT_OS == OperatingSystem.MACOS) {
            return configPath.startsWith("/var/folders/")
                    || configPath.startsWith("/private/var/folders/")
                    || configPath.startsWith("/tmp/")
                    || configPath.startsWith("/private/tmp/")
                    || configPath.startsWith("/var/tmp/")
                    || configPath.startsWith("/private/var/tmp/")
                    || configPath.contains("/.Trash/");
        } else {
            return false;
        }
    }

    /// Warns a new installation before it persists configuration in a temporary directory.
    private static void checkConfigInTempDir() {
        if (SettingsManager.isNewlyCreated() && isConfigInTempDir()
                && !SwingStartupSafetyDialogs.confirmWithCountdown(
                        WARNING,
                        i18n("fatal.config_in_temp_dir"),
                        5)) {
            EntryPoint.exit(0);
        }
    }

    /// Releases the Swing runtime, schedulers, pending saves, and logger once.
    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        ACTIVE_LAUNCHER.compareAndSet(this, null);

        @Nullable Throwable failure = null;
        @Nullable AccountCreationWorkflowHandle accountWorkflow;
        synchronized (accountCreationLifecycleLock) {
            accountWorkflow = accountCreationWorkflow.getAndSet(null);
        }
        if (accountWorkflow != null) {
            failure = closeCollecting(accountWorkflow, failure);
        }
        @Nullable SwingUpdateNotificationController updateNotifications;
        @Nullable SwingUpdateCheckService updateService;
        synchronized (swingUpdateLifecycleLock) {
            updateNotifications = swingUpdateNotifications;
            swingUpdateNotifications = null;
            updateService = swingUpdateCheckService;
            swingUpdateCheckService = null;
        }
        if (updateNotifications != null) {
            failure = closeCollecting(updateNotifications, failure);
        }
        if (updateService != null) {
            failure = closeCollecting(updateService, failure);
        }
        @Nullable StartupPromptCoordinator prompts = startupPromptCoordinator;
        startupPromptCoordinator = null;
        if (prompts != null) {
            failure = closeCollecting(prompts, failure);
        }
        @Nullable SwingApplicationRuntime runtime = swingRuntime;
        swingRuntime = null;
        if (runtime != null) {
            ACTIVE_SWING_RUNTIME.compareAndSet(runtime, null);
            failure = closeCollecting(runtime, failure);
        }
        failure = runCollecting(Schedulers::shutdown, failure);
        failure = runCollecting(SettingsManager::shutdown, failure);
        failure = runCollecting(LOG::shutdown, failure);
        rethrowCleanupFailure(failure);
    }

    /// Logs the runtime environment and starts the Swing application toolkit.
    ///
    /// @param args launcher and updater arguments
    public static void main(String @Unmodifiable [] args) {
        if (processUpdateArguments(args)) {
            LOG.shutdown();
            return;
        }

        Thread.setDefaultUncaughtExceptionHandler(CRASH_REPORTER);
        AsyncTaskExecutor.setUncaughtExceptionHandler(new CrashReporter(false));

        try {
            LOG.info("*** " + Metadata.TITLE + " ***");
            LOG.info("Operating System: " + (OperatingSystem.OS_RELEASE_PRETTY_NAME == null
                    ? OperatingSystem.SYSTEM_NAME + ' ' + OperatingSystem.SYSTEM_VERSION.getVersion()
                    : OperatingSystem.OS_RELEASE_PRETTY_NAME + " (" + OperatingSystem.SYSTEM_NAME + ' ' + OperatingSystem.SYSTEM_VERSION.getVersion() + ')'));
            if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
                LOG.info("Processor Identifier: " + System.getenv("PROCESSOR_IDENTIFIER"));
            }
            LOG.info("System Architecture: " + Architecture.SYSTEM_ARCH.getDisplayName());
            LOG.info("Native Encoding: " + OperatingSystem.NATIVE_CHARSET);
            LOG.info("JNU Encoding: " + System.getProperty("sun.jnu.encoding"));
            if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
                LOG.info("Code Page: " + OperatingSystem.CODE_PAGE);
            }
            LOG.info("Java Architecture: " + Architecture.CURRENT_ARCH.getDisplayName());
            LOG.info("Java Version: " + System.getProperty("java.version") + ", " + System.getProperty("java.vendor"));
            LOG.info("Java VM Version: " + System.getProperty("java.vm.name") + " (" + System.getProperty("java.vm.info") + "), " + System.getProperty("java.vm.vendor"));
            LOG.info("Java Home: " + System.getProperty("java.home"));
            LOG.info("Current Directory: " + Metadata.CURRENT_DIRECTORY);
            LOG.info("XYML User Home: " + Metadata.XYML_USER_HOME);
            LOG.info("XYML Local Home: " + Metadata.XYML_LOCAL_HOME);
            LOG.info("XYML Jar Path: " + Lang.requireNonNullElse(JarUtils.thisJarPath(), "Not Found"));
            LOG.info("XYML Log File: " + Lang.requireNonNullElse(LOG.getLogFile(), "In Memory"));
            LOG.info("JVM Max Memory: " + MEGABYTES.formatBytes(Runtime.getRuntime().maxMemory()));
            try {
                for (MemoryPoolMXBean bean : ManagementFactory.getMemoryPoolMXBeans()) {
                    if ("Metaspace".equals(bean.getName())) {
                        long bytes = bean.getUsage().getUsed();
                        LOG.info("Metaspace: " + MEGABYTES.formatBytes(bytes));
                        break;
                    }
                }
            } catch (NoClassDefFoundError ignored) {
            }
            LOG.info("Native Backend: " + (NativeUtils.USE_JNA ? "JNA" : "None"));
            if (OperatingSystem.CURRENT_OS.isLinuxOrBSD()) {
                LOG.info("XDG Session Type: " + System.getenv("XDG_SESSION_TYPE"));
                LOG.info("XDG Current Desktop: " + System.getenv("XDG_CURRENT_DESKTOP"));
            }

            LOG.info("Zlib Compatible: " + ZlibUtils.IS_ZLIB_COMPATIBLE);

            Lang.thread(SystemInfo::initialize, "Detection System Information", true);

            Launcher launcher = new Launcher();
            if (!ACTIVE_LAUNCHER.compareAndSet(null, launcher)) {
                throw new IllegalStateException("Another launcher instance is already active");
            }
            try {
                LauncherStateDispatcher.execute(launcher::start);
            } catch (RuntimeException | Error dispatchFailure) {
                launcher.stop();
                throw dispatchFailure;
            }
        } catch (Throwable e) {
            CRASH_REPORTER.uncaughtException(Thread.currentThread(), e);
        }
    }

    /// Applies toolkit-neutral startup update operations and presents only their semantic result with Swing.
    ///
    /// @param args launcher and updater arguments
    /// @return whether the current process must exit before initializing launcher state
    private static boolean processUpdateArguments(String @Unmodifiable [] args) {
        UpdateStartupResult result = UpdateApplier.processArguments(args);
        if (!result.shouldExit()) {
            return false;
        }

        @Nullable UpdateStartupResult.Notice notice = result.notice();
        if (notice == null) {
            return true;
        }

        switch (notice) {
            case UNSUPPORTED_WINDOWS_VERSION -> SwingStartupSafetyDialogs.showMessage(
                    ERROR,
                    i18n("fatal.apply_update_need_win7", Metadata.PUBLISH_URL));
            case APPLY_FAILED -> SwingStartupSafetyDialogs.showMessage(
                    ERROR,
                    i18n("fatal.apply_update_failure", Metadata.MANUAL_UPDATE_URL)
                            + "\n"
                            + StringUtils.getStackTrace(Objects.requireNonNull(
                                    result.failure(),
                                    "apply-failure result did not carry its failure")));
            case MANUAL_REBOOT_REQUIRED -> SwingStartupSafetyDialogs.showMessage(
                    INFO,
                    i18n("fatal.migration_requires_manual_reboot"));
        }
        return true;
    }

    /// Adds a cleanup failure to an existing startup or cleanup failure without replacing its identity.
    ///
    /// @param resource resource that could not be retained after startup failure
    /// @param originalFailure original startup failure
    private static void closeAfterFailure(AutoCloseable resource, Throwable originalFailure) {
        try {
            resource.close();
        } catch (Throwable cleanupFailure) {
            if (originalFailure != cleanupFailure) {
                originalFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    /// Closes one resource while retaining the first failure.
    ///
    /// @param resource resource to close
    /// @param previous first earlier failure, or null
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

    /// Runs one cleanup command while retaining the first failure.
    ///
    /// @param command cleanup command
    /// @param previous first earlier failure, or null
    /// @return first failure with any later failure suppressed
    private static @Nullable Throwable runCollecting(
            Runnable command,
            @Nullable Throwable previous) {
        try {
            command.run();
            return previous;
        } catch (Throwable current) {
            return accumulateFailure(previous, current);
        }
    }

    /// Appends a later cleanup failure to the first failure.
    ///
    /// @param previous first earlier failure, or null
    /// @param current next cleanup failure
    /// @return first failure with later failures suppressed
    private static Throwable accumulateFailure(@Nullable Throwable previous, Throwable current) {
        if (previous == null) {
            return current;
        }
        if (previous != current) {
            previous.addSuppressed(current);
        }
        return previous;
    }

    /// Rethrows accumulated cleanup without losing unchecked failure types.
    ///
    /// @param failure accumulated cleanup failure, or null when cleanup succeeded
    private static void rethrowCleanupFailure(@Nullable Throwable failure) {
        if (failure == null) {
            return;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Failed to stop launcher", failure);
    }

    /// Process-wide uncaught-exception reporter used by both UI toolkits and task executors.
    public static final CrashReporter CRASH_REPORTER =
            new CrashReporter(true, LAUNCHER_UPDATE_AVAILABLE::get);
}
