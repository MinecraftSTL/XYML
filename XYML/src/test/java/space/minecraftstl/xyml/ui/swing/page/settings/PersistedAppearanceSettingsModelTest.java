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
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.ui.swing.ThemeMode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests persisted appearance mapping, command validation, runtime application, and cleanup.
@NotNullByDefault
public final class PersistedAppearanceSettingsModelTest {
    /// Raw automatic dark settings map to system mode and apply immediately to the runtime.
    @Test
    public void mapsAndAppliesInitialStoreState() {
        FakeStore store = new FakeStore(raw("auto", 6, true, true));
        List<AppearanceSettingsSnapshot> applied = new ArrayList<>();
        PersistedAppearanceSettingsModel model = new PersistedAppearanceSettingsModel(store, applied::add);

        assertAll(
                () -> assertEquals(ThemeMode.SYSTEM, model.snapshot().themeMode()),
                () -> assertEquals(6, model.snapshot().cornerRadius()),
                () -> assertFalse(model.snapshot().animationsEnabled()),
                () -> assertTrue(model.snapshot().writable()),
                () -> assertEquals(List.of(model.snapshot()), applied));
        model.close();
    }

    /// Page commands persist canonical values and every store transition reapplies the complete runtime state.
    @Test
    public void persistsCommandsAndReappliesRuntime() {
        FakeStore store = new FakeStore(raw("auto", 6, true, true));
        List<AppearanceSettingsSnapshot> applied = new ArrayList<>();
        List<AppearanceSettingsSnapshot> published = new ArrayList<>();
        PersistedAppearanceSettingsModel model = new PersistedAppearanceSettingsModel(store, applied::add);
        Subscription listener = model.subscribe(change -> published.add(change.currentValue()));

        model.setThemeMode(ThemeMode.DARK);
        model.setCornerRadius(14);
        model.setAnimationsEnabled(true);

        assertAll(
                () -> assertEquals("dark", store.snapshot().themeModeValue()),
                () -> assertEquals(14, store.snapshot().cornerRadius()),
                () -> assertFalse(store.snapshot().animationsDisabled()),
                () -> assertEquals(ThemeMode.DARK, model.snapshot().themeMode()),
                () -> assertEquals(14, model.snapshot().cornerRadius()),
                () -> assertTrue(model.snapshot().animationsEnabled()),
                () -> assertEquals(4, applied.size()),
                () -> assertEquals(3, published.size()));
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
        assertEquals(6, writableStore.snapshot().cornerRadius());
        writable.close();

        FakeStore readOnlyStore = new FakeStore(raw("light", 6, false, false));
        PersistedAppearanceSettingsModel readOnly =
                new PersistedAppearanceSettingsModel(readOnlyStore, ignored -> { });
        assertAll(
                () -> assertThrows(IllegalStateException.class,
                        () -> readOnly.setThemeMode(ThemeMode.DARK)),
                () -> assertThrows(IllegalStateException.class,
                        () -> readOnly.setCornerRadius(8)),
                () -> assertThrows(IllegalStateException.class,
                        () -> readOnly.setAnimationsEnabled(false)));
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
        store.setThemeModeValue("dark");

        assertAll(
                () -> assertEquals(beforeClose, model.snapshot()),
                () -> assertFalse(store.hasSubscribers()),
                () -> assertThrows(IllegalStateException.class,
                        () -> model.setThemeMode(ThemeMode.SYSTEM)));
    }

    /// Creates raw test settings with the production radius bounds and step.
    ///
    /// @param mode persisted mode value
    /// @param radius current corner radius
    /// @param animationsDisabled legacy disable flag
    /// @param writable whether writes are accepted
    /// @return raw store snapshot
    private static StoredAppearanceSettings raw(
            String mode,
            int radius,
            boolean animationsDisabled,
            boolean writable) {
        return new StoredAppearanceSettings(mode, radius, 0, 20, 1, animationsDisabled, writable);
    }

    /// Synchronous toolkit-neutral persistence fake.
    @NotNullByDefault
    private static final class FakeStore implements AppearanceSettingsStore {
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

        /// Replaces the persisted theme value.
        @Override
        public void setThemeModeValue(String themeModeValue) {
            publish(new StoredAppearanceSettings(
                    themeModeValue,
                    current.cornerRadius(),
                    current.minimumCornerRadius(),
                    current.maximumCornerRadius(),
                    current.cornerRadiusStep(),
                    current.animationsDisabled(),
                    current.writable()));
        }

        /// Replaces the persisted radius.
        @Override
        public void setCornerRadius(int cornerRadius) {
            publish(new StoredAppearanceSettings(
                    current.themeModeValue(),
                    cornerRadius,
                    current.minimumCornerRadius(),
                    current.maximumCornerRadius(),
                    current.cornerRadiusStep(),
                    current.animationsDisabled(),
                    current.writable()));
        }

        /// Replaces the legacy animation-disable flag.
        @Override
        public void setAnimationsDisabled(boolean disabled) {
            publish(new StoredAppearanceSettings(
                    current.themeModeValue(),
                    current.cornerRadius(),
                    current.minimumCornerRadius(),
                    current.maximumCornerRadius(),
                    current.cornerRadiusStep(),
                    disabled,
                    current.writable()));
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
}
