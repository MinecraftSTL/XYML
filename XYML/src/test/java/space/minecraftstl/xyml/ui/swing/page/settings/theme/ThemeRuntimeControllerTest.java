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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.setting.BackgroundType;
import space.minecraftstl.xyml.setting.LauncherSettings;
import space.minecraftstl.xyml.theme.BackgroundLoadPolicy;
import space.minecraftstl.xyml.theme.BuiltinBackground;
import space.minecraftstl.xyml.theme.BuiltinThemePackCatalog;
import space.minecraftstl.xyml.theme.LocalThemePackRepository;
import space.minecraftstl.xyml.theme.NetworkBackgroundImageCachePolicy;
import space.minecraftstl.xyml.theme.ThemeBrightness;
import space.minecraftstl.xyml.theme.ThemeBrightnessPreference;
import space.minecraftstl.xyml.theme.ThemeColor;
import space.minecraftstl.xyml.theme.ThemeReference;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.MotionPolicy;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.SwingDesignTokens;
import space.minecraftstl.xyml.ui.swing.SwingThemeManager;
import space.minecraftstl.xyml.ui.swing.SystemThemeDetector;
import space.minecraftstl.xyml.ui.swing.page.settings.AppearanceSettingsSnapshot;
import space.minecraftstl.xyml.ui.swing.page.settings.BackgroundAppearanceSettings;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies runtime theme persistence, four-state resolution, complete updates, and stale-result isolation.
@NotNullByDefault
public final class ThemeRuntimeControllerTest {
    /// Empty local repository root used by the production repository boundary.
    @TempDir
    private Path repositoryRoot;

    /// Exact theme application preserves the resolved accent while updating radius and animation policy.
    @Test
    public void appliesExactThemeWithCompleteAppearanceState() {
        LauncherSettings settings = new LauncherSettings();
        SystemThemeDetector detector = () -> true;
        SwingThemeManager themeManager = manager(detector);
        SwingAnimator animator = new SwingAnimator(MotionPolicy.FULL, 16);
        ThemeRuntimeController controller = controller(
                settings,
                themeManager,
                animator,
                detector,
                Runnable::run,
                LauncherSettings.DEFAULT_THEME_REFERENCE);

        controller.accept(snapshot(17, false, ThemeBrightnessPreference.THEME));
        flushEdt();

        ThemeReference classic = new ThemeReference("xyml.classic", "2015-06-22");
        controller.apply(classic).toCompletableFuture().join();

        AtomicReference<@Nullable ThemeReference> persisted = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> persisted.set(settings.selectedThemeProperty().get()));
        assertAll(
                () -> assertEquals(classic, persisted.get()),
                () -> assertEquals(ThemeColor.of("#E67E22"), themeManager.effectiveAccentColor()),
                () -> assertEquals(17, themeManager.designTokens().cornerRadius()),
                () -> assertEquals(MotionPolicy.OFF, animator.motionPolicy()),
                () -> assertEquals(ThemeBrightness.LIGHT,
                        Objects.requireNonNull(themeManager.resolvedTheme()).brightness()));

        ThemePackManagementModel managementModel = controller.createManagementModel();
        assertEquals(classic, managementModel.snapshot().appliedTheme());
        managementModel.close();
        controller.close();
    }

    /// Theme and system preferences use detected brightness while explicit light bypasses a dark detector.
    @Test
    public void resolvesSystemAndExplicitBrightnessContexts() {
        LauncherSettings settings = new LauncherSettings();
        SystemThemeDetector detector = () -> true;
        SwingThemeManager themeManager = manager(detector);
        SwingAnimator animator = new SwingAnimator(MotionPolicy.FULL, 16);
        ThemeRuntimeController controller = controller(
                settings,
                themeManager,
                animator,
                detector,
                Runnable::run,
                LauncherSettings.DEFAULT_THEME_REFERENCE);

        controller.accept(snapshot(8, true, ThemeBrightnessPreference.THEME));
        flushEdt();
        assertEquals(ThemeBrightness.DARK, Objects.requireNonNull(themeManager.resolvedTheme()).brightness());

        EdtDispatcher.executeAndWait(() -> {
            settings.themeBrightnessModeProperty().set("light");
            settings.getThemeAppearanceOverrides().add(LauncherSettings.THEME_APPEARANCE_BRIGHTNESS_MODE);
        });
        controller.accept(snapshot(8, true, ThemeBrightnessPreference.LIGHT));
        flushEdt();

        assertAll(
                () -> assertEquals(ThemeBrightness.LIGHT,
                        Objects.requireNonNull(themeManager.resolvedTheme()).brightness()),
                () -> assertEquals(
                        ThemeBrightnessPreference.LIGHT,
                        themeManager.brightnessPreference()));
        controller.close();
    }

    /// An unavailable exact reference fails without replacing the persisted selection.
    @Test
    public void propagatesExactApplicationFailureWithoutPersistence() {
        LauncherSettings settings = new LauncherSettings();
        SystemThemeDetector detector = () -> false;
        SwingThemeManager themeManager = manager(detector);
        ThemeRuntimeController controller = controller(
                settings,
                themeManager,
                new SwingAnimator(MotionPolicy.FULL, 16),
                detector,
                Runnable::run,
                LauncherSettings.DEFAULT_THEME_REFERENCE);
        controller.accept(snapshot(8, true, ThemeBrightnessPreference.THEME));
        flushEdt();

        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> controller.apply(new ThemeReference("missing.pack", "missing"))
                        .toCompletableFuture()
                        .join());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());

        AtomicReference<@Nullable ThemeReference> persisted = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> persisted.set(settings.selectedThemeProperty().get()));
        assertEquals(LauncherSettings.DEFAULT_THEME_REFERENCE, persisted.get());
        controller.close();
    }

    /// Closing before queued package work completes prevents the late startup result from reaching Swing.
    @Test
    public void ignoresInitialCompletionAfterClose() {
        LauncherSettings settings = new LauncherSettings();
        SystemThemeDetector detector = () -> true;
        SwingThemeManager themeManager = manager(detector);
        ManualExecutor executor = new ManualExecutor();
        ThemeRuntimeController controller = controller(
                settings,
                themeManager,
                new SwingAnimator(MotionPolicy.FULL, 16),
                detector,
                executor,
                LauncherSettings.DEFAULT_THEME_REFERENCE);
        controller.accept(snapshot(19, true, ThemeBrightnessPreference.THEME));

        controller.close();
        executor.runAll();
        flushEdt();

        assertAll(
                () -> assertNull(themeManager.resolvedTheme()),
                () -> assertEquals(5, themeManager.designTokens().cornerRadius()),
                () -> assertThrows(
                        IllegalStateException.class,
                        () -> controller.accept(snapshot(8, true, ThemeBrightnessPreference.THEME))));
    }

    /// A newer explicit selection cancels an older load and is the only request allowed to persist.
    @Test
    public void rejectsSupersededApplicationGeneration() {
        LauncherSettings settings = new LauncherSettings();
        SystemThemeDetector detector = () -> false;
        SwingThemeManager themeManager = manager(detector);
        ManualExecutor executor = new ManualExecutor();
        ThemeRuntimeController controller = controller(
                settings,
                themeManager,
                new SwingAnimator(MotionPolicy.FULL, 16),
                detector,
                executor,
                LauncherSettings.DEFAULT_THEME_REFERENCE);
        controller.accept(snapshot(11, true, ThemeBrightnessPreference.THEME));
        executor.runAll();
        flushEdt();

        CompletionStage<@Nullable Void> superseded = controller.apply(
                new ThemeReference("xyml.classic", "2021-08-26"));
        ThemeReference latest = new ThemeReference("xyml.classic", "2015-06-22");
        CompletionStage<@Nullable Void> current = controller.apply(latest);
        executor.runAll();
        flushEdt();
        current.toCompletableFuture().join();

        CompletionException supersededFailure = assertThrows(
                CompletionException.class,
                () -> superseded.toCompletableFuture().join());
        AtomicReference<@Nullable ThemeReference> persisted = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> persisted.set(settings.selectedThemeProperty().get()));
        assertAll(
                () -> assertInstanceOf(CancellationException.class, supersededFailure.getCause()),
                () -> assertEquals(latest, persisted.get()),
                () -> assertEquals(ThemeColor.of("#E67E22"), themeManager.effectiveAccentColor()));
        controller.close();
    }

    /// Creates one initialized manager with a deterministic system detector.
    private static SwingThemeManager manager(SystemThemeDetector detector) {
        SwingThemeManager manager = new SwingThemeManager(
                ThemeBrightnessPreference.LIGHT,
                new SwingDesignTokens(5),
                detector);
        manager.initialize();
        return manager;
    }

    /// Creates a controller around packaged resources and an empty local repository.
    private ThemeRuntimeController controller(
            LauncherSettings settings,
            SwingThemeManager themeManager,
            SwingAnimator animator,
            SystemThemeDetector detector,
            Executor executor,
            ThemeReference initialTheme) {
        return new ThemeRuntimeController(
                settings,
                new BuiltinThemePackCatalog(),
                new LocalThemePackRepository(repositoryRoot),
                themeManager,
                animator,
                detector,
                executor,
                initialTheme);
    }

    /// Creates one valid appearance snapshot with fixed launcher bounds.
    private static AppearanceSettingsSnapshot snapshot(
            int radius,
            boolean animations,
            ThemeBrightnessPreference preference) {
        return new AppearanceSettingsSnapshot(
                preference,
                radius,
                0,
                24,
                1,
                animations,
                background(),
                true);
    }

    /// Creates one theme-inheriting background state for runtime tests.
    ///
    /// @return complete launcher-background controls
    private static BackgroundAppearanceSettings background() {
        return new BackgroundAppearanceSettings(
                BackgroundType.DEFAULT,
                BuiltinBackground.FALLBACK.id(),
                "",
                "",
                null,
                1.0,
                NetworkBackgroundImageCachePolicy.ENABLED,
                BackgroundType.BUILTIN,
                "#FFFFFF",
                BackgroundLoadPolicy.WAIT_FOR_BACKGROUND,
                false,
                false,
                false,
                false);
    }

    /// Waits until all previously enqueued EDT work has completed.
    private static void flushEdt() {
        EdtDispatcher.executeAndWait(() -> { });
    }

    /// Deterministic queued executor used to deliver package work only after closure.
    @NotNullByDefault
    private static final class ManualExecutor implements Executor {
        /// FIFO task queue.
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        /// Queues one task without running it.
        ///
        /// @param command task to queue
        @Override
        public void execute(Runnable command) {
            tasks.add(Objects.requireNonNull(command, "command"));
        }

        /// Runs every currently or transitively queued task in FIFO order.
        private void runAll() {
            while (!tasks.isEmpty()) {
                Objects.requireNonNull(tasks.remove()).run();
            }
        }
    }
}
