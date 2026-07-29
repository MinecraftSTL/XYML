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
package space.minecraftstl.xyml.ui.swing.page.settings.theme;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.setting.LauncherSettings;
import space.minecraftstl.xyml.theme.BuiltinThemePack;
import space.minecraftstl.xyml.theme.BuiltinThemePackCatalog;
import space.minecraftstl.xyml.theme.InstalledThemePack;
import space.minecraftstl.xyml.theme.LocalThemePackRepository;
import space.minecraftstl.xyml.theme.ResolvedTheme;
import space.minecraftstl.xyml.theme.ResolvedThemeSelection;
import space.minecraftstl.xyml.theme.ThemeBrightness;
import space.minecraftstl.xyml.theme.ThemeBrightnessPreference;
import space.minecraftstl.xyml.theme.ThemePackPackage;
import space.minecraftstl.xyml.theme.ThemeReference;
import space.minecraftstl.xyml.theme.ThemeResolutionRequest;
import space.minecraftstl.xyml.theme.ThemeResolveContext;
import space.minecraftstl.xyml.theme.ThemeSelectionResolver;
import space.minecraftstl.xyml.ui.swing.MotionPolicy;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.SwingDesignTokens;
import space.minecraftstl.xyml.ui.swing.SwingThemeManager;
import space.minecraftstl.xyml.ui.swing.SwingWindowAppearanceRequest;
import space.minecraftstl.xyml.ui.swing.SystemThemeDetector;
import space.minecraftstl.xyml.ui.swing.runtime.LauncherStateDispatcher;
import space.minecraftstl.xyml.ui.swing.page.settings.AppearanceSettingsSnapshot;
import space.minecraftstl.xyml.ui.swing.page.settings.LauncherThemeResolutionAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Coordinates launcher settings, offline theme packages, and the live Swing appearance.
///
/// Package I/O and ordinary resolution run on the caller-owned worker. Launcher settings are captured and mutated only
/// through [LauncherStateDispatcher], while the final generation check, persistence, and Swing update share one EDT
/// commit point. Passive startup failures are logged; an explicit theme application reports its failure to its caller.
@NotNullByDefault
public final class ThemeRuntimeController
        implements ThemePackApplication, Consumer<AppearanceSettingsSnapshot>, AutoCloseable {
    /// Lock guarding lifecycle, generations, inventory, and latest appearance state.
    private final Object stateLock = new Object();

    /// Thread-confined launcher launcher settings.
    private final LauncherSettings settings;

    /// Embedded offline theme catalog.
    private final BuiltinThemePackCatalog builtinCatalog;

    /// Strict local theme repository.
    private final LocalThemePackRepository localRepository;

    /// Active FlatLaf runtime adapter.
    private final SwingThemeManager themeManager;

    /// Shared Swing motion runtime.
    private final SwingAnimator animator;

    /// Fast operating-system brightness detector.
    private final SystemThemeDetector systemThemeDetector;

    /// Caller-owned non-EDT worker executor.
    private final Executor executor;

    /// Validated package inventory in built-in then installed order.
    private @Unmodifiable List<ThemePackPackage> packages = List.of();

    /// Latest accepted appearance snapshot, or `null` before model composition.
    private @Nullable AppearanceSettingsSnapshot appearance;

    /// Theme reference currently represented by the runtime or its initial persisted state.
    private ThemeReference activeReference;

    /// Explicit application currently waiting for inventory or resolution, or `null`.
    private @Nullable ThemeReference applyingReference;

    /// Completion owned by the current explicit application, or `null`.
    private @Nullable CompletableFuture<@Nullable Void> applyingCompletion;

    /// Monotonic inventory generation rejecting superseded package loads.
    private long inventoryGeneration;

    /// Monotonic passive-resolution generation rejecting obsolete appearance results.
    private long resolutionGeneration;

    /// Monotonic explicit-application generation rejecting superseded selections.
    private long applicationGeneration;

    /// Whether a validated inventory is currently available.
    private boolean inventoryLoaded;

    /// Terminal lifecycle flag.
    private boolean closed;

    /// Creates a controller and starts its first fully offline package load.
    ///
    /// @param settings loaded launcher settings confined through [LauncherStateDispatcher]
    /// @param builtinCatalog packaged built-in theme catalog
    /// @param localRepository local installed-theme repository
    /// @param themeManager active Swing theme manager
    /// @param animator shared Swing animator
    /// @param systemThemeDetector current operating-system brightness detector
    /// @param executor caller-owned non-EDT worker executor
    /// @param initialTheme initially persisted exact theme reference
    public ThemeRuntimeController(
            LauncherSettings settings,
            BuiltinThemePackCatalog builtinCatalog,
            LocalThemePackRepository localRepository,
            SwingThemeManager themeManager,
            SwingAnimator animator,
            SystemThemeDetector systemThemeDetector,
            Executor executor,
            ThemeReference initialTheme) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.builtinCatalog = Objects.requireNonNull(builtinCatalog, "builtinCatalog");
        this.localRepository = Objects.requireNonNull(localRepository, "localRepository");
        this.themeManager = Objects.requireNonNull(themeManager, "themeManager");
        this.animator = Objects.requireNonNull(animator, "animator");
        this.systemThemeDetector = Objects.requireNonNull(systemThemeDetector, "systemThemeDetector");
        this.executor = Objects.requireNonNull(executor, "executor");
        activeReference = Objects.requireNonNull(initialTheme, "initialTheme");
        themeManager.setSystemThemeRefreshHandler(this::refreshForSystemAppearance);
        startInitialLoad();
    }

    /// Creates a management model backed by the same offline catalogs and exact application callback.
    ///
    /// The returned model owns only its subscriptions and must be closed by the page-model composition.
    ///
    /// @return independent theme-pack management model
    public ThemePackManagementModel createManagementModel() {
        ThemeReference applied;
        synchronized (stateLock) {
            requireOpen();
            applied = activeReference;
        }
        return new ThemePackManagementModel(
                new LocalThemePackManagementBackend(builtinCatalog, localRepository),
                this,
                executor,
                applied);
    }

    /// Applies the latest corner radius and animation policy, then asynchronously re-resolves theme values.
    ///
    /// @param snapshot complete persisted appearance state
    @Override
    public void accept(AppearanceSettingsSnapshot snapshot) {
        AppearanceSettingsSnapshot checked = Objects.requireNonNull(snapshot, "snapshot");
        boolean canResolve;
        synchronized (stateLock) {
            requireOpen();
            appearance = checked;
            canResolve = inventoryLoaded && applyingReference == null;
        }

        applyImmediateAppearance(checked);
        if (canResolve) {
            schedulePassiveResolution();
        }
    }

    /// Persists and activates one exact available package theme.
    ///
    /// The package inventory is reloaded before validation so a newly imported local pack is immediately selectable.
    /// A missing exact reference is rejected rather than silently persisting the resolver fallback.
    ///
    /// @param reference exact selected reference
    /// @return completion stage resolved after EDT persistence and runtime application
    @Override
    public CompletionStage<@Nullable Void> apply(ThemeReference reference) {
        ThemeReference selected = Objects.requireNonNull(reference, "reference");
        CompletableFuture<@Nullable Void> completion = new CompletableFuture<>();
        @Nullable CompletableFuture<@Nullable Void> superseded;
        long inventoryToken;
        long applicationToken;
        synchronized (stateLock) {
            requireOpen();
            if (appearance == null) {
                throw new IllegalStateException(
                        "Appearance settings must be accepted before applying a theme");
            }
            superseded = applyingCompletion;
            applyingReference = selected;
            applyingCompletion = completion;
            applicationToken = ++applicationGeneration;
            inventoryToken = ++inventoryGeneration;
            resolutionGeneration++;
        }
        if (superseded != null) {
            superseded.completeExceptionally(new CancellationException("Theme application was superseded"));
        }

        loadPackages().whenComplete((loaded, failure) -> completeExplicitLoad(
                inventoryToken,
                applicationToken,
                selected,
                loaded,
                failure,
                completion));
        return completion.minimalCompletionStage();
    }

    /// Stops future resolution, rejects late completions, and detaches system-theme refresh handling.
    @Override
    public void close() {
        @Nullable CompletableFuture<@Nullable Void> pending;
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            closed = true;
            inventoryGeneration++;
            resolutionGeneration++;
            applicationGeneration++;
            packages = List.of();
            inventoryLoaded = false;
            applyingReference = null;
            pending = applyingCompletion;
            applyingCompletion = null;
        }
        themeManager.setSystemThemeRefreshHandler(null);
        if (pending != null) {
            pending.completeExceptionally(new CancellationException("Theme runtime controller is closed"));
        }
    }

    /// Starts the best-effort package load used by startup and the first appearance snapshot.
    private void startInitialLoad() {
        long generation;
        synchronized (stateLock) {
            generation = ++inventoryGeneration;
        }
        loadPackages().whenComplete((loaded, failure) -> completeInitialLoad(generation, loaded, failure));
    }

    /// Loads built-in and installed packages concurrently and preserves their trust order.
    ///
    /// @return eventual immutable package inventory
    private CompletionStage<@Unmodifiable List<ThemePackPackage>> loadPackages() {
        CompletionStage<@Unmodifiable List<BuiltinThemePack>> builtins = builtinCatalog.loadAll(executor);
        CompletionStage<@Unmodifiable List<InstalledThemePack>> installed = localRepository.listInstalled(executor);
        return builtins.thenCombine(installed, (builtinPacks, installedPacks) -> {
            List<ThemePackPackage> combined = new ArrayList<>(builtinPacks.size() + installedPacks.size());
            combined.addAll(builtinPacks);
            combined.addAll(installedPacks);
            return List.copyOf(combined);
        });
    }

    /// Commits a current startup inventory or logs its current failure.
    private void completeInitialLoad(
            long generation,
            @Nullable @Unmodifiable List<ThemePackPackage> loaded,
            @Nullable Throwable failure) {
        @Nullable Throwable cause = unwrap(failure);
        boolean resolve;
        synchronized (stateLock) {
            if (closed || generation != inventoryGeneration) {
                return;
            }
            if (cause == null) {
                packages = List.copyOf(Objects.requireNonNull(loaded, "loaded packages"));
                inventoryLoaded = true;
                resolve = appearance != null;
            } else {
                inventoryLoaded = false;
                resolve = false;
            }
        }
        if (cause != null) {
            LOG.warning("Failed to load the initial offline theme inventory", cause);
        } else if (resolve) {
            schedulePassiveResolution();
        }
    }

    /// Commits a current explicit inventory and starts exact background resolution.
    private void completeExplicitLoad(
            long inventoryToken,
            long applicationToken,
            ThemeReference reference,
            @Nullable @Unmodifiable List<ThemePackPackage> loaded,
            @Nullable Throwable failure,
            CompletableFuture<@Nullable Void> completion) {
        @Nullable Throwable cause = unwrap(failure);
        @Nullable @Unmodifiable List<ThemePackPackage> currentPackages = null;
        synchronized (stateLock) {
            if (!isCurrentApplication(inventoryToken, applicationToken, reference, completion)) {
                return;
            }
            if (cause == null) {
                currentPackages = List.copyOf(Objects.requireNonNull(loaded, "loaded packages"));
                packages = currentPackages;
                inventoryLoaded = true;
            }
        }
        if (cause != null) {
            finishApplicationFailure(applicationToken, completion, cause);
        } else {
            scheduleExplicitResolution(
                    applicationToken,
                    reference,
                    Objects.requireNonNull(currentPackages, "currentPackages"),
                    completion);
        }
    }

    /// Schedules a passive settings-to-runtime resolution against the current validated inventory.
    private void schedulePassiveResolution() {
        long generation;
        @Unmodifiable List<ThemePackPackage> currentPackages;
        AppearanceSettingsSnapshot currentAppearance;
        synchronized (stateLock) {
            if (closed || !inventoryLoaded || applyingReference != null || appearance == null) {
                return;
            }
            generation = ++resolutionGeneration;
            currentPackages = packages;
            currentAppearance = appearance;
        }

        try {
            executor.execute(() -> resolvePassive(generation, currentPackages, currentAppearance));
        } catch (RuntimeException failure) {
            logPassiveFailure(generation, failure);
        }
    }

    /// Resolves a passive settings snapshot away from the EDT.
    private void resolvePassive(
            long generation,
            @Unmodifiable List<ThemePackPackage> currentPackages,
            AppearanceSettingsSnapshot currentAppearance) {
        try {
            ThemeResolveContext context = contextFor(currentAppearance.brightnessPreference());
            ThemeResolutionRequest request = snapshotRequest(context, null);
            ResolvedThemeSelection selection = new ThemeSelectionResolver(currentPackages).resolve(request);
            LauncherStateDispatcher.execute(() -> commitPassiveResolution(
                    generation,
                    currentAppearance,
                    selection));
        } catch (Throwable failure) {
            logPassiveFailure(generation, failure);
        }
    }

    /// Applies a passive result on the EDT only while its generation and appearance remain current.
    private void commitPassiveResolution(
            long generation,
            AppearanceSettingsSnapshot resolvedAppearance,
            ResolvedThemeSelection selection) {
        try {
            synchronized (stateLock) {
                if (closed
                        || generation != resolutionGeneration
                        || applyingReference != null
                        || !resolvedAppearance.equals(appearance)) {
                    return;
                }
                applyResolvedAppearance(selection, resolvedAppearance);
                activeReference = selection.effectiveReference();
            }
        } catch (Throwable failure) {
            logPassiveFailure(generation, failure);
        }
    }

    /// Logs one current passive resolution failure and ignores stale failures.
    private void logPassiveFailure(long generation, Throwable failure) {
        synchronized (stateLock) {
            if (closed || generation != resolutionGeneration || applyingReference != null) {
                return;
            }
        }
        LOG.warning("Failed to resolve the active offline theme", unwrap(failure));
    }

    /// Schedules exact validation and resolution for one explicit selection.
    private void scheduleExplicitResolution(
            long applicationToken,
            ThemeReference reference,
            @Unmodifiable List<ThemePackPackage> currentPackages,
            CompletableFuture<@Nullable Void> completion) {
        AppearanceSettingsSnapshot currentAppearance;
        synchronized (stateLock) {
            if (!isCurrentApplication(applicationToken, reference, completion)) {
                return;
            }
            currentAppearance = Objects.requireNonNull(appearance, "appearance");
        }
        try {
            executor.execute(() -> resolveExplicit(
                    applicationToken,
                    reference,
                    currentPackages,
                    currentAppearance,
                    completion));
        } catch (RuntimeException failure) {
            finishApplicationFailure(applicationToken, completion, failure);
        }
    }

    /// Validates one exact reference away from the EDT before scheduling its commit.
    private void resolveExplicit(
            long applicationToken,
            ThemeReference reference,
            @Unmodifiable List<ThemePackPackage> currentPackages,
            AppearanceSettingsSnapshot currentAppearance,
            CompletableFuture<@Nullable Void> completion) {
        try {
            ThemeResolveContext context = contextFor(currentAppearance.brightnessPreference());
            ThemeSelectionResolver resolver = new ThemeSelectionResolver(currentPackages);
            ResolvedThemeSelection preliminary = resolver.resolve(snapshotRequest(context, reference));
            if (preliminary.fallbackUsed() || !reference.equals(preliminary.effectiveReference())) {
                throw new IllegalArgumentException("Theme reference is unavailable: " + reference);
            }
            LauncherStateDispatcher.execute(() -> commitExplicitResolution(
                    applicationToken,
                    reference,
                    currentAppearance,
                    context,
                    resolver,
                    completion));
        } catch (Throwable failure) {
            finishApplicationFailure(applicationToken, completion, failure);
        }
    }

    /// Persists, re-snapshots, resolves, and applies an exact selection at one checked EDT commit point.
    private void commitExplicitResolution(
            long applicationToken,
            ThemeReference reference,
            AppearanceSettingsSnapshot resolvedAppearance,
            ThemeResolveContext context,
            ThemeSelectionResolver resolver,
            CompletableFuture<@Nullable Void> completion) {
        @Nullable @Unmodifiable List<ThemePackPackage> retryPackages = null;
        @Nullable Throwable commitFailure = null;
        boolean completed = false;
        synchronized (stateLock) {
            if (!isCurrentApplication(applicationToken, reference, completion)) {
                return;
            }
            if (!resolvedAppearance.equals(appearance)) {
                retryPackages = packages;
            } else {
                @Nullable ThemeReference previousReference = settings.selectedThemeProperty().get();
                try {
                    settings.selectedThemeProperty().set(reference);
                    ThemeResolutionRequest persistedRequest =
                            LauncherThemeResolutionAdapter.snapshot(settings, context);
                    ResolvedThemeSelection selection = resolver.resolve(persistedRequest);
                    if (selection.fallbackUsed() || !reference.equals(selection.effectiveReference())) {
                        throw new IllegalStateException(
                                "Persisted theme no longer resolves exactly: " + reference);
                    }
                    applyResolvedAppearance(selection, resolvedAppearance);
                    activeReference = reference;
                    applyingReference = null;
                    applyingCompletion = null;
                    completed = true;
                } catch (Throwable failure) {
                    settings.selectedThemeProperty().set(previousReference);
                    commitFailure = failure;
                }
            }
        }
        if (retryPackages != null) {
            scheduleExplicitResolution(applicationToken, reference, retryPackages, completion);
        } else if (commitFailure != null) {
            finishApplicationFailure(applicationToken, completion, commitFailure);
        } else if (completed) {
            completion.complete(null);
        }
    }

    /// Captures launcher settings for one context and optionally substitutes an exact selected reference.
    private ThemeResolutionRequest snapshotRequest(
            ThemeResolveContext context,
            @Nullable ThemeReference selectedReference) {
        CompletableFuture<ThemeResolutionRequest> captured = new CompletableFuture<>();
        LauncherStateDispatcher.executeAndWait(() -> {
            ThemeResolutionRequest persisted = LauncherThemeResolutionAdapter.snapshot(settings, context);
            captured.complete(selectedReference == null
                    ? persisted
                    : new ThemeResolutionRequest(selectedReference, context, persisted.userOverrides()));
        });
        return captured.join();
    }

    /// Builds a condition context with the exact four-state system-brightness semantics.
    private ThemeResolveContext contextFor(ThemeBrightnessPreference preference) {
        ThemeBrightness brightness = switch (Objects.requireNonNull(preference, "preference")) {
            case THEME, SYSTEM -> systemThemeDetector.isDarkTheme()
                    ? ThemeBrightness.DARK
                    : ThemeBrightness.LIGHT;
            case LIGHT -> ThemeBrightness.LIGHT;
            case DARK -> ThemeBrightness.DARK;
        };
        return ThemeResolveContext.current(brightness);
    }

    /// Immediately updates motion and, when available, tokens without discarding the resolved accent.
    private void applyImmediateAppearance(AppearanceSettingsSnapshot snapshot) {
        animator.setMotionPolicy(snapshot.animationsEnabled() ? MotionPolicy.FULL : MotionPolicy.OFF);
        @Nullable ResolvedTheme currentTheme = themeManager.resolvedTheme();
        if (currentTheme != null) {
            themeManager.update(
                    currentTheme,
                    new SwingDesignTokens(snapshot.cornerRadius()),
                    snapshot.brightnessPreference());
        }
    }

    /// Applies one resolved theme and its package-owned background with the same persisted snapshot.
    ///
    /// @param selection exact resolved theme, appearance, and owning package
    /// @param snapshot same-generation appearance controls
    private void applyResolvedAppearance(
            ResolvedThemeSelection selection,
            AppearanceSettingsSnapshot snapshot) {
        themeManager.update(
                Objects.requireNonNull(selection, "selection").theme(),
                new SwingDesignTokens(snapshot.cornerRadius()),
                snapshot.brightnessPreference());
        themeManager.updateWindowAppearance(SwingWindowAppearanceRequest.resolve(selection, snapshot));
        animator.setMotionPolicy(snapshot.animationsEnabled() ? MotionPolicy.FULL : MotionPolicy.OFF);
    }

    /// Requests passive re-resolution after a native system-appearance signal.
    private void refreshForSystemAppearance() {
        schedulePassiveResolution();
    }

    /// Records one current explicit failure and completes its public stage exceptionally.
    private void finishApplicationFailure(
            long applicationToken,
            CompletableFuture<@Nullable Void> completion,
            Throwable failure) {
        @Nullable Throwable cause = unwrap(failure);
        boolean refreshPassive;
        synchronized (stateLock) {
            if (closed
                    || applicationToken != applicationGeneration
                    || applyingCompletion != completion) {
                return;
            }
            applyingReference = null;
            applyingCompletion = null;
            refreshPassive = inventoryLoaded && appearance != null;
        }
        completion.completeExceptionally(Objects.requireNonNull(cause, "failure"));
        if (refreshPassive) {
            schedulePassiveResolution();
        }
    }

    /// Tests whether inventory and application tokens still identify the current explicit request.
    private boolean isCurrentApplication(
            long inventoryToken,
            long applicationToken,
            ThemeReference reference,
            CompletableFuture<@Nullable Void> completion) {
        return inventoryToken == inventoryGeneration
                && isCurrentApplication(applicationToken, reference, completion);
    }

    /// Tests whether one explicit request remains current and open.
    private boolean isCurrentApplication(
            long applicationToken,
            ThemeReference reference,
            CompletableFuture<@Nullable Void> completion) {
        return !closed
                && applicationToken == applicationGeneration
                && reference.equals(applyingReference)
                && applyingCompletion == completion;
    }

    /// Removes asynchronous wrapper exceptions while retaining the original cause.
    private static @Nullable Throwable unwrap(@Nullable Throwable failure) {
        @Nullable Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /// Rejects public use after terminal closure.
    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Theme runtime controller is closed");
        }
    }
}
