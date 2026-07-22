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

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.setting.LauncherSettings;
import space.minecraftstl.xyml.setting.SettingsManager;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.BooleanSupplier;

/// Transitional adapter isolating legacy JavaFX launcher properties behind [AppearanceSettingsStore].
///
/// All JavaFX property access and listener registration occurs on the JavaFX application thread. This
/// class is the only appearance-settings layer that must be replaced when launcher settings become toolkit-neutral.
@NotNullByDefault
public final class LegacyLauncherAppearanceStore implements AppearanceSettingsStore, AutoCloseable {
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

    /// Whether JavaFX listeners have been removed.
    private volatile boolean closed;

    /// Creates an adapter on the JavaFX application thread.
    ///
    /// @param settings loaded launcher settings
    /// @param writableSupplier dynamic core-settings write check
    public LegacyLauncherAppearanceStore(
            LauncherSettings settings,
            BooleanSupplier writableSupplier) {
        requireJavaFxThread();
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
        requireJavaFxThread();
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
        if (closed) {
            throw new IllegalStateException("Legacy appearance store is closed");
        }
        return changes.subscribe(listener);
    }

    /// Persists one canonical brightness identifier on the JavaFX thread.
    @Override
    public void setThemeModeValue(String themeModeValue) {
        String validatedValue = Objects.requireNonNull(themeModeValue, "themeModeValue");
        runOnJavaFxThreadAndWait(() -> {
            requireOpen();
            settings.themeBrightnessModeProperty().set(validatedValue);
        });
    }

    /// Persists one validated corner radius on the JavaFX thread.
    @Override
    public void setCornerRadius(int cornerRadius) {
        runOnJavaFxThreadAndWait(() -> {
            requireOpen();
            settings.cornerRadiusProperty().set(cornerRadius);
        });
    }

    /// Persists the legacy animation-disable flag on the JavaFX thread.
    @Override
    public void setAnimationsDisabled(boolean disabled) {
        runOnJavaFxThreadAndWait(() -> {
            requireOpen();
            settings.animationDisabledProperty().set(disabled);
        });
    }

    /// Removes every JavaFX property listener synchronously and exactly once.
    @Override
    public void close() {
        runOnJavaFxThreadAndWait(() -> {
            if (!closed) {
                closed = true;
                settings.themeBrightnessModeProperty().removeListener(propertyListener);
                settings.cornerRadiusProperty().removeListener(propertyListener);
                settings.animationDisabledProperty().removeListener(propertyListener);
            }
        });
    }

    /// Rebuilds and publishes the raw snapshot after one legacy property changes.
    private void refreshSnapshot() {
        requireJavaFxThread();
        if (closed) {
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
        requireJavaFxThread();
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

    /// Runs one property operation synchronously on the JavaFX application thread.
    ///
    /// @param operation property operation
    private static void runOnJavaFxThreadAndWait(Runnable operation) {
        Objects.requireNonNull(operation, "operation");
        if (Platform.isFxApplicationThread()) {
            operation.run();
            return;
        }

        FutureTask<Void> task = new FutureTask<>(operation, null);
        Platform.runLater(task);
        try {
            task.get();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for JavaFX settings update", failure);
        } catch (ExecutionException failure) {
            @Nullable Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("JavaFX settings update failed", cause);
        }
    }

    /// Requires construction and direct property callbacks to run on the JavaFX thread.
    private static void requireJavaFxThread() {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("Legacy appearance store must access properties on the JavaFX thread");
        }
    }

    /// Rejects property writes after listener cleanup.
    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Legacy appearance store is closed");
        }
    }
}
