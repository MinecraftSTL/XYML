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
package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.setting.AnimationSpeedSettings;
import space.minecraftstl.xyml.setting.BackgroundType;
import space.minecraftstl.xyml.theme.BuiltinBackground;
import space.minecraftstl.xyml.theme.NetworkBackgroundImageCachePolicy;
import space.minecraftstl.xyml.theme.ThemeBrightnessPreference;
import space.minecraftstl.xyml.theme.ThemeColor;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.MotionPolicy;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.SwingDesignTokens;
import space.minecraftstl.xyml.ui.swing.SwingThemeManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests persisted appearance mapping, command validation, runtime application, and cleanup.
@NotNullByDefault
public final class PersistedAppearanceSettingsModelTest {
    /// Raw system settings map to the four-state model and apply immediately to the runtime.
    @Test
    public void mapsAndAppliesInitialStoreState() {
        FakeStore store = new FakeStore(raw("system", 6, true, true));
        List<AppearanceSettingsSnapshot> applied = new ArrayList<>();
        PersistedAppearanceSettingsModel model = new PersistedAppearanceSettingsModel(store, applied::add);

        assertAll(
                () -> assertEquals(ThemeBrightnessPreference.SYSTEM,
                        model.snapshot().brightnessPreference()),
                () -> assertEquals(6, model.snapshot().cornerRadius()),
                () -> assertFalse(model.snapshot().animationsEnabled()),
                () -> assertEquals(100, model.snapshot().animationSpeed().percentage()),
                () -> assertTrue(model.snapshot().writable()),
                () -> assertEquals(List.of(model.snapshot()), applied));
        model.close();
    }

    /// Theme inheritance remains distinct from system mode while retaining the previous raw value.
    @Test
    public void persistsThemeBrightnessInheritance() {
        FakeStore store = new FakeStore(new StoredAppearanceSettings(
                "dark",
                6,
                0,
                20,
                1,
                false,
                background(),
                true,
                true));
        PersistedAppearanceSettingsModel model = new PersistedAppearanceSettingsModel(store, ignored -> { });

        model.setThemeBrightnessPreference(ThemeBrightnessPreference.THEME);

        assertAll(
                () -> assertEquals(ThemeBrightnessPreference.THEME,
                        model.snapshot().brightnessPreference()),
                () -> assertEquals("dark", store.snapshot().themeBrightnessValue()),
                () -> assertFalse(store.snapshot().themeBrightnessOverridden()));
        model.close();
    }

    /// Page commands persist canonical values and every store transition reapplies the complete runtime state.
    @Test
    public void persistsCommandsAndReappliesRuntime() {
        FakeStore store = new FakeStore(raw("system", 6, true, true));
        List<AppearanceSettingsSnapshot> applied = new ArrayList<>();
        List<AppearanceSettingsSnapshot> published = new ArrayList<>();
        PersistedAppearanceSettingsModel model = new PersistedAppearanceSettingsModel(store, applied::add);
        Subscription listener = model.subscribe(change -> published.add(change.currentValue()));

        model.setThemeBrightnessPreference(ThemeBrightnessPreference.DARK);
        model.setCornerRadius(14);
        model.setAnimationsEnabled(true);
        model.setAnimationSpeedPercentage(180);
        ThemeColorAppearanceSettings replacementThemeColor = new ThemeColorAppearanceSettings(
                Objects.requireNonNull(ThemeColor.of("#123456")),
                true);
        model.setThemeColorAppearance(replacementThemeColor);
        BackgroundAppearanceSettings replacementBackground = new BackgroundAppearanceSettings(
                BackgroundType.PAINT,
                BuiltinBackground.FALLBACK.id(),
                "",
                "",
                "#123456",
                0.7,
                NetworkBackgroundImageCachePolicy.DISABLED,
                true,
                true,
                true);
        model.setBackgroundAppearance(replacementBackground);

        assertAll(
                () -> assertEquals("dark", store.snapshot().themeBrightnessValue()),
                () -> assertEquals(14, store.snapshot().cornerRadius()),
                () -> assertFalse(store.snapshot().animationsDisabled()),
                () -> assertEquals(180, store.snapshot().animationSpeed().percentage()),
                () -> assertEquals(
                        ThemeBrightnessPreference.DARK,
                        model.snapshot().brightnessPreference()),
                () -> assertEquals(14, model.snapshot().cornerRadius()),
                () -> assertTrue(model.snapshot().animationsEnabled()),
                () -> assertEquals(180, model.snapshot().animationSpeed().percentage()),
                () -> assertEquals(replacementThemeColor, model.snapshot().themeColor()),
                () -> assertEquals(replacementBackground, model.snapshot().background()),
                () -> assertEquals(7, applied.size()),
                () -> assertEquals(6, published.size()));
        listener.unsubscribe();
        model.close();
    }

    /// Invalid radii and read-only stores reject writes before reaching persistence.
    @Test
    public void rejectsUnsupportedOrReadOnlyWrites() {
        FakeStore writableStore = new FakeStore(raw("light", 6, false, true));
        PersistedAppearanceSettingsModel writable =
                new PersistedAppearanceSettingsModel(writableStore, ignored -> { });
        assertThrows(IllegalArgumentException.class, () -> writable.setCornerRadius(21));
        assertThrows(IllegalArgumentException.class, () -> writable.setAnimationSpeedPercentage(205));
        assertEquals(6, writableStore.snapshot().cornerRadius());
        assertEquals(100, writableStore.snapshot().animationSpeed().percentage());
        writable.close();

        FakeStore readOnlyStore = new FakeStore(raw("light", 6, false, false));
        PersistedAppearanceSettingsModel readOnly =
                new PersistedAppearanceSettingsModel(readOnlyStore, ignored -> { });
        assertAll(
                () -> assertThrows(IllegalStateException.class,
                        () -> readOnly.setThemeBrightnessPreference(ThemeBrightnessPreference.DARK)),
                () -> assertThrows(IllegalStateException.class,
                        () -> readOnly.setCornerRadius(8)),
                () -> assertThrows(IllegalStateException.class,
                        () -> readOnly.setAnimationsEnabled(false)),
                () -> assertThrows(IllegalStateException.class,
                        () -> readOnly.setAnimationSpeedPercentage(150)),
                () -> assertThrows(IllegalStateException.class,
                        () -> readOnly.setThemeColorAppearance(ThemeColorAppearanceSettings.defaults())),
                () -> assertThrows(IllegalStateException.class,
                        () -> readOnly.setBackgroundAppearance(background())));
        readOnly.close();
    }

    /// Closing removes the raw-store subscription and rejects later model commands.
    @Test
    public void closeStopsStoreUpdates() {
        FakeStore store = new FakeStore(raw("light", 6, false, true));
        PersistedAppearanceSettingsModel model =
                new PersistedAppearanceSettingsModel(store, ignored -> { });
        AppearanceSettingsSnapshot beforeClose = model.snapshot();

        model.close();
        store.setThemeBrightnessPreference(ThemeBrightnessPreference.DARK);

        assertAll(
                () -> assertEquals(beforeClose, model.snapshot()),
                () -> assertFalse(store.hasSubscribers()),
                () -> assertThrows(IllegalStateException.class,
                        () -> model.setThemeBrightnessPreference(ThemeBrightnessPreference.SYSTEM)));
    }

    /// Closing the appearance model releases an injected application runtime exactly once.
    @Test
    public void closesOwnedRuntimeExactlyOnce() {
        FakeStore store = new FakeStore(raw("light", 6, false, true));
        AtomicInteger closeCount = new AtomicInteger();
        PersistedAppearanceSettingsModel model = new PersistedAppearanceSettingsModel(
                store,
                ignored -> { },
                closeCount::incrementAndGet);

        model.close();
        model.close();

        assertEquals(1, closeCount.get());
    }

    /// A store publishing on another UI thread cannot deadlock that thread against the blocked Swing EDT.
    @Test
    public void appliesCrossThreadStoreChangesWithoutEdtDeadlock() {
        CrossThreadStore store = new CrossThreadStore(raw("light", 6, false, true));
        SwingThemeManager themeManager = new SwingThemeManager(
                ThemeBrightnessPreference.LIGHT,
                new SwingDesignTokens(6),
                () -> false);
        SwingAnimator animator = new SwingAnimator(MotionPolicy.FULL, 16);
        AtomicReference<@Nullable PersistedAppearanceSettingsModel> model = new AtomicReference<>();

        EdtDispatcher.executeAndWait(() -> {
            model.set(new PersistedAppearanceSettingsModel(store, themeManager, animator));
            Objects.requireNonNull(model.get()).setCornerRadius(8);
            Objects.requireNonNull(model.get()).setAnimationSpeedPercentage(180);
        });
        EdtDispatcher.executeAndWait(() -> { });

        assertAll(
                () -> assertEquals(8, Objects.requireNonNull(model.get()).snapshot().cornerRadius()),
                () -> assertEquals(8, themeManager.designTokens().cornerRadius()),
                () -> assertEquals(MotionPolicy.FULL, animator.motionPolicy()),
                () -> assertEquals(180, animator.animationSpeedPercentage()));
        Objects.requireNonNull(model.get()).close();
    }

    /// Creates raw test settings with the production radius bounds and step.
    ///
    /// @param brightnessValue persisted brightness value
    /// @param radius current corner radius
    /// @param animationsDisabled persisted disable flag
    /// @param writable whether writes are accepted
    /// @return raw store snapshot
    private static StoredAppearanceSettings raw(
            String brightnessValue,
            int radius,
            boolean animationsDisabled,
            boolean writable) {
        return new StoredAppearanceSettings(
                brightnessValue,
                radius,
                0,
                20,
                1,
                animationsDisabled,
                background(),
                writable,
                true);
    }

    /// Creates one renderer-safe background setting shared by model tests.
    ///
    /// @return complete default background state
    private static BackgroundAppearanceSettings background() {
        return new BackgroundAppearanceSettings(
                BackgroundType.DEFAULT,
                BuiltinBackground.FALLBACK.id(),
                "",
                "",
                null,
                1.0,
                NetworkBackgroundImageCachePolicy.ENABLED,
                false,
                false,
                false);
    }

    /// Synchronous toolkit-neutral persistence fake.
    @NotNullByDefault
    private static class FakeStore implements AppearanceSettingsStore {
        /// Raw transition publisher.
        private final ValueChangeSupport<StoredAppearanceSettings> changes = new ValueChangeSupport<>(this);

        /// Latest raw store values.
        private StoredAppearanceSettings current;

        /// Creates a fake store with initial values.
        ///
        /// @param initial initial raw values
        private FakeStore(StoredAppearanceSettings initial) {
            current = initial;
        }

        /// Returns current fake values.
        @Override
        public StoredAppearanceSettings snapshot() {
            return current;
        }

        /// Registers a fake raw-state listener.
        @Override
        public Subscription subscribe(ValueChangeListener<StoredAppearanceSettings> listener) {
            return changes.subscribe(listener);
        }

        /// Replaces the four-state brightness preference while retaining an inherited raw value.
        @Override
        public void setThemeBrightnessPreference(ThemeBrightnessPreference preference) {
            @Nullable String value = preference.settingValue();
            publish(new StoredAppearanceSettings(
                    value != null ? value : current.themeBrightnessValue(),
                    current.cornerRadius(),
                    current.minimumCornerRadius(),
                    current.maximumCornerRadius(),
                    current.cornerRadiusStep(),
                    current.animationsDisabled(),
                    current.animationSpeed(),
                    current.themeColor(),
                    current.background(),
                    current.writable(),
                    value != null));
        }

        /// Replaces the persisted radius.
        @Override
        public void setCornerRadius(int cornerRadius) {
            publish(new StoredAppearanceSettings(
                    current.themeBrightnessValue(),
                    cornerRadius,
                    current.minimumCornerRadius(),
                    current.maximumCornerRadius(),
                    current.cornerRadiusStep(),
                    current.animationsDisabled(),
                    current.animationSpeed(),
                    current.themeColor(),
                    current.background(),
                    current.writable(),
                    current.themeBrightnessOverridden()));
        }

        /// Replaces the persisted animation-disable flag.
        @Override
        public void setAnimationsDisabled(boolean disabled) {
            publish(new StoredAppearanceSettings(
                    current.themeBrightnessValue(),
                    current.cornerRadius(),
                    current.minimumCornerRadius(),
                    current.maximumCornerRadius(),
                    current.cornerRadiusStep(),
                    disabled,
                    current.animationSpeed(),
                    current.themeColor(),
                    current.background(),
                    current.writable(),
                    current.themeBrightnessOverridden()));
        }

        /// Replaces the persisted animation speed.
        @Override
        public void setAnimationSpeedPercentage(int percentage) {
            publish(new StoredAppearanceSettings(
                    current.themeBrightnessValue(),
                    current.cornerRadius(),
                    current.minimumCornerRadius(),
                    current.maximumCornerRadius(),
                    current.cornerRadiusStep(),
                    current.animationsDisabled(),
                    new AnimationSpeedSettings(percentage),
                    current.themeColor(),
                    current.background(),
                    current.writable(),
                    current.themeBrightnessOverridden()));
        }

        /// Replaces the complete persisted theme-color state.
        @Override
        public void setThemeColorAppearance(ThemeColorAppearanceSettings themeColor) {
            publish(new StoredAppearanceSettings(
                    current.themeBrightnessValue(),
                    current.cornerRadius(),
                    current.minimumCornerRadius(),
                    current.maximumCornerRadius(),
                    current.cornerRadiusStep(),
                    current.animationsDisabled(),
                    current.animationSpeed(),
                    Objects.requireNonNull(themeColor, "themeColor"),
                    current.background(),
                    current.writable(),
                    current.themeBrightnessOverridden()));
        }

        /// Replaces the complete persisted background state.
        @Override
        public void setBackgroundAppearance(BackgroundAppearanceSettings background) {
            publish(new StoredAppearanceSettings(
                    current.themeBrightnessValue(),
                    current.cornerRadius(),
                    current.minimumCornerRadius(),
                    current.maximumCornerRadius(),
                    current.cornerRadiusStep(),
                    current.animationsDisabled(),
                    current.animationSpeed(),
                    current.themeColor(),
                    Objects.requireNonNull(background, "background"),
                    current.writable(),
                    current.themeBrightnessOverridden()));
        }

        /// Publishes one raw replacement synchronously.
        ///
        /// @param replacement new raw values
        private void publish(StoredAppearanceSettings replacement) {
            StoredAppearanceSettings previous = current;
            current = replacement;
            changes.fireChange(previous, replacement);
        }

        /// Returns whether the model still owns a store listener.
        ///
        /// @return whether any subscriber remains
        private boolean hasSubscribers() {
            return changes.hasSubscribers();
        }
    }

    /// Simulates a store that synchronously waits while another toolkit thread publishes its change.
    @NotNullByDefault
    private static final class CrossThreadStore extends FakeStore {
        /// Creates a cross-thread fake with initial values.
        ///
        /// @param initial initial raw values
        private CrossThreadStore(StoredAppearanceSettings initial) {
            super(initial);
        }

        /// Publishes the radius from a simulated foreign UI thread while the calling EDT waits for completion.
        @Override
        public void setCornerRadius(int cornerRadius) {
            FutureTask<Void> change = new FutureTask<>(() -> {
                super.setCornerRadius(cornerRadius);
                return null;
            });
            Thread publisher = new Thread(change, "appearance-cross-toolkit-publisher");
            publisher.start();
            try {
                change.get(2L, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while simulating cross-toolkit persistence", failure);
            } catch (ExecutionException | TimeoutException failure) {
                throw new IllegalStateException("Cross-toolkit appearance update did not complete", failure);
            }
        }
    }
}
