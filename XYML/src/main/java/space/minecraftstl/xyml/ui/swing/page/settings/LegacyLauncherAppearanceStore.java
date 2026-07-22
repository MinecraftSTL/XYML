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

import javafx.beans.value.ChangeListener;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.setting.LauncherSettings;
import space.minecraftstl.xyml.setting.SettingsManager;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static space.minecraftstl.xyml.ui.swing.legacy.LegacyJavaFxDispatcher.execute;
import static space.minecraftstl.xyml.ui.swing.legacy.LegacyJavaFxDispatcher.requireEventThread;

/// Transitional adapter isolating legacy JavaFX launcher properties behind [AppearanceSettingsStore].
///
/// All JavaFX property access and listener registration occurs on the JavaFX application thread. This
/// class is the only appearance-settings layer that must be replaced when launcher settings become toolkit-neutral.
@NotNullByDefault
public final class LegacyLauncherAppearanceStore implements AppearanceSettingsStore, AutoCloseable {
    /// Serializes listener registration with the transition to the closed lifecycle state.
    private final Object lifecycleLock = new Object();

    /// Legacy launcher setting object.
    private final LauncherSettings settings;

    /// Dynamic check for whether core launcher settings may be persisted.
    private final BooleanSupplier writableSupplier;

    /// Raw store transition publisher.
    private final ValueChangeSupport<StoredAppearanceSettings> changes = new ValueChangeSupport<>(this);

    /// One listener shared by the three legacy JavaFX properties.
    private final ChangeListener<Object> propertyListener = (observable, previous, current) -> refreshSnapshot();

    /// Latest raw store snapshot available to non-JavaFX threads.
    private volatile StoredAppearanceSettings currentSnapshot;

    /// Whether closure has been requested from any thread.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Creates an adapter on the JavaFX application thread.
    ///
    /// @param settings loaded launcher settings
    /// @param writableSupplier dynamic core-settings write check
    public LegacyLauncherAppearanceStore(
            LauncherSettings settings,
            BooleanSupplier writableSupplier) {
        requireEventThread();
        this.settings = Objects.requireNonNull(settings, "settings");
        this.writableSupplier = Objects.requireNonNull(writableSupplier, "writableSupplier");
        currentSnapshot = readSnapshot();
        settings.themeBrightnessModeProperty().addListener(propertyListener);
        settings.cornerRadiusProperty().addListener(propertyListener);
        settings.animationDisabledProperty().addListener(propertyListener);
    }

    /// Creates an adapter for the process-wide loaded launcher settings.
    ///
    /// @return legacy store bound to [SettingsManager#settings()]
    public static LegacyLauncherAppearanceStore createForCurrentSettings() {
        requireEventThread();
        return new LegacyLauncherAppearanceStore(
                SettingsManager.settings(),
                () -> !SettingsManager.hasReadOnlyCoreSettings());
    }

    /// Returns the latest cross-thread-safe raw settings snapshot.
    @Override
    public StoredAppearanceSettings snapshot() {
        return currentSnapshot;
    }

    /// Registers a raw settings listener.
    @Override
    public Subscription subscribe(ValueChangeListener<StoredAppearanceSettings> listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (lifecycleLock) {
            if (closed.get()) {
                throw new IllegalStateException("Legacy appearance store is closed");
            }
            return changes.subscribe(listener);
        }
    }

    /// Queues one canonical brightness identifier for persistence on the JavaFX thread.
    @Override
    public void setThemeModeValue(String themeModeValue) {
        String validatedValue = Objects.requireNonNull(themeModeValue, "themeModeValue");
        requireOpen();
        execute(() -> {
            if (!closed.get()) {
                settings.themeBrightnessModeProperty().set(validatedValue);
            }
        });
    }

    /// Queues one validated corner radius for persistence on the JavaFX thread.
    @Override
    public void setCornerRadius(int cornerRadius) {
        requireOpen();
        execute(() -> {
            if (!closed.get()) {
                settings.cornerRadiusProperty().set(cornerRadius);
            }
        });
    }

    /// Queues the legacy animation-disable flag for persistence on the JavaFX thread.
    @Override
    public void setAnimationsDisabled(boolean disabled) {
        requireOpen();
        execute(() -> {
            if (!closed.get()) {
                settings.animationDisabledProperty().set(disabled);
            }
        });
    }

    /// Requests idempotent JavaFX listener removal without blocking a Swing EDT caller.
    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
        }
        execute(this::removeLegacyListeners);
    }

    /// Rebuilds and publishes the raw snapshot after one legacy property changes.
    private void refreshSnapshot() {
        requireEventThread();
        if (closed.get()) {
            return;
        }
        StoredAppearanceSettings previous = currentSnapshot;
        StoredAppearanceSettings replacement = readSnapshot();
        currentSnapshot = replacement;
        changes.fireChange(previous, replacement);
    }

    /// Reads and normalizes the three legacy settings on the JavaFX thread.
    ///
    /// @return raw immutable store snapshot
    private StoredAppearanceSettings readSnapshot() {
        requireEventThread();
        @Nullable String configuredMode = settings.themeBrightnessModeProperty().get();
        int radius = alignRadius(settings.cornerRadiusProperty().get());
        return new StoredAppearanceSettings(
                Objects.requireNonNullElse(configuredMode, "auto"),
                radius,
                LauncherSettings.MINIMUM_CORNER_RADIUS,
                LauncherSettings.MAXIMUM_CORNER_RADIUS,
                LauncherSettings.CORNER_RADIUS_STEP,
                settings.animationDisabledProperty().get(),
                writableSupplier.getAsBoolean());
    }

    /// Constrains a legacy or externally edited radius to the supported stepped range.
    ///
    /// @param radius raw persisted radius
    /// @return supported radius aligned downward to the nearest step
    private static int alignRadius(int radius) {
        int constrained = Math.max(
                LauncherSettings.MINIMUM_CORNER_RADIUS,
                Math.min(LauncherSettings.MAXIMUM_CORNER_RADIUS, radius));
        int offset = constrained - LauncherSettings.MINIMUM_CORNER_RADIUS;
        return LauncherSettings.MINIMUM_CORNER_RADIUS
                + offset / LauncherSettings.CORNER_RADIUS_STEP * LauncherSettings.CORNER_RADIUS_STEP;
    }

    /// Rejects property writes after listener cleanup.
    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Legacy appearance store is closed");
        }
    }

    /// Removes every owned settings listener on the JavaFX application thread.
    private void removeLegacyListeners() {
        requireEventThread();
        settings.themeBrightnessModeProperty().removeListener(propertyListener);
        settings.cornerRadiusProperty().removeListener(propertyListener);
        settings.animationDisabledProperty().removeListener(propertyListener);
    }
}
