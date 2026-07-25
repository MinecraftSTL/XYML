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
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.ui.swing.MotionPolicy;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.SwingDesignTokens;
import space.minecraftstl.xyml.ui.swing.SwingThemeManager;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;
import space.minecraftstl.xyml.ui.swing.ThemeMode;

import java.util.Objects;
import java.util.function.Consumer;

/// Maps persisted appearance values to the Swing settings page and active UI runtime.
@NotNullByDefault
public final class PersistedAppearanceSettingsModel
        implements AppearanceSettingsModel, AutoCloseable {
    /// Raw persistence boundary.
    private final AppearanceSettingsStore store;

    /// Applies each accepted snapshot to the active Swing runtime.
    private final Consumer<AppearanceSettingsSnapshot> runtimeApplier;

    /// Page-state transition publisher.
    private final ValueChangeSupport<AppearanceSettingsSnapshot> changes = new ValueChangeSupport<>(this);

    /// Owned raw-store subscription.
    private final Subscription storeSubscription;

    /// Latest mapped page state.
    private volatile AppearanceSettingsSnapshot currentSnapshot;

    /// Whether this model has released its store subscription.
    private volatile boolean closed;

    /// Creates a production model that updates FlatLaf tokens and the shared animator.
    ///
    /// @param store raw persistence boundary
    /// @param themeManager active Swing theme manager
    /// @param animator shared Swing animator
    public PersistedAppearanceSettingsModel(
            AppearanceSettingsStore store,
            SwingThemeManager themeManager,
            SwingAnimator animator) {
        this(store, createSwingRuntimeApplier(themeManager, animator));
    }

    /// Creates a model with an explicit runtime applier for deterministic tests.
    ///
    /// @param store raw persistence boundary
    /// @param runtimeApplier callback applying mapped values to the active runtime
    PersistedAppearanceSettingsModel(
            AppearanceSettingsStore store,
            Consumer<AppearanceSettingsSnapshot> runtimeApplier) {
        this.store = Objects.requireNonNull(store, "store");
        this.runtimeApplier = Objects.requireNonNull(runtimeApplier, "runtimeApplier");
        currentSnapshot = map(store.snapshot());
        runtimeApplier.accept(currentSnapshot);
        storeSubscription = store.subscribe(this::storeChanged);
    }

    /// Returns the latest mapped page state.
    @Override
    public AppearanceSettingsSnapshot snapshot() {
        return currentSnapshot;
    }

    /// Registers a mapped page-state listener.
    @Override
    public Subscription subscribe(ValueChangeListener<AppearanceSettingsSnapshot> listener) {
        Objects.requireNonNull(listener, "listener");
        if (closed) {
            throw new IllegalStateException("Persisted appearance model is closed");
        }
        return changes.subscribe(listener);
    }

    /// Persists a canonical light, dark, or system theme value.
    @Override
    public void setThemeMode(ThemeMode themeMode) {
        requireWritable();
        store.setThemeModeValue(Objects.requireNonNull(themeMode, "themeMode").settingValue());
    }

    /// Persists a radius aligned to the current model-provided bounds and step.
    @Override
    public void setCornerRadius(int cornerRadius) {
        requireWritable();
        AppearanceSettingsSnapshot snapshot = currentSnapshot;
        if (cornerRadius < snapshot.minimumCornerRadius()
                || cornerRadius > snapshot.maximumCornerRadius()
                || (cornerRadius - snapshot.minimumCornerRadius()) % snapshot.cornerRadiusStep() != 0) {
            throw new IllegalArgumentException("Unsupported corner radius: " + cornerRadius);
        }
        store.setCornerRadius(cornerRadius);
    }

    /// Persists the inverse legacy disable flag for the page's enabled toggle.
    @Override
    public void setAnimationsEnabled(boolean enabled) {
        requireWritable();
        store.setAnimationsDisabled(!enabled);
    }

    /// Releases the raw-store subscription exactly once.
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            storeSubscription.unsubscribe();
        }
    }

    /// Maps and applies one raw store transition before notifying page listeners.
    ///
    /// @param change raw store transition
    private void storeChanged(ValueChange<StoredAppearanceSettings> change) {
        if (closed) {
            return;
        }
        StoredAppearanceSettings raw = Objects.requireNonNull(
                change.currentValue(), "appearance store emitted null");
        AppearanceSettingsSnapshot previous = currentSnapshot;
        AppearanceSettingsSnapshot replacement = map(raw);
        currentSnapshot = replacement;
        runtimeApplier.accept(replacement);
        changes.fireChange(previous, replacement);
    }

    /// Converts raw persisted values into the public page contract.
    ///
    /// @param raw raw store values
    /// @return mapped page snapshot
    private static AppearanceSettingsSnapshot map(StoredAppearanceSettings raw) {
        return new AppearanceSettingsSnapshot(
                ThemeMode.fromSettingValue(raw.themeModeValue()),
                raw.cornerRadius(),
                raw.minimumCornerRadius(),
                raw.maximumCornerRadius(),
                raw.cornerRadiusStep(),
                !raw.animationsDisabled(),
                raw.writable());
    }

    /// Creates a runtime applier that never synchronously waits across launcher state and Swing UI threads.
    ///
    /// @param themeManager active Swing theme manager
    /// @param animator shared Swing animator
    /// @return callback dispatching all runtime mutations to the Swing EDT
    private static Consumer<AppearanceSettingsSnapshot> createSwingRuntimeApplier(
            SwingThemeManager themeManager,
            SwingAnimator animator) {
        Objects.requireNonNull(themeManager, "themeManager");
        Objects.requireNonNull(animator, "animator");
        return snapshot -> SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            themeManager.update(
                    snapshot.themeMode(),
                    new SwingDesignTokens(snapshot.cornerRadius()));
            animator.setMotionPolicy(snapshot.animationsEnabled()
                    ? MotionPolicy.FULL
                    : MotionPolicy.OFF);
        });
    }

    /// Rejects writes after closure or while the backing store is read-only.
    private void requireWritable() {
        if (closed) {
            throw new IllegalStateException("Persisted appearance model is closed");
        }
        if (!currentSnapshot.writable()) {
            throw new IllegalStateException("Appearance settings are read-only");
        }
    }
}
