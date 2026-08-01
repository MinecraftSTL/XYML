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
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.setting.LauncherSettings;
import space.minecraftstl.xyml.setting.SettingsManager;
import space.minecraftstl.xyml.setting.UserSettings;
import space.minecraftstl.xyml.ui.swing.FontAntialiasingMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static space.minecraftstl.xyml.ui.swing.runtime.LauncherStateDispatcher.execute;
import static space.minecraftstl.xyml.ui.swing.runtime.LauncherStateDispatcher.requireEventThread;

/// Adapts persisted launcher and per-user font properties to immutable Swing settings snapshots.
@NotNullByDefault
final class LauncherFontSettingsStore implements FontSettingsStore {
    /// Legacy fallback used when a malformed persisted log size cannot be displayed.
    private static final double DEFAULT_LOG_FONT_SIZE = 12.0;

    /// Serializes subscription registration with closure.
    private final Object lifecycleLock = new Object();

    /// Workspace launcher settings containing the two font families and log size.
    private final LauncherSettings launcherSettings;

    /// Per-user settings containing the text antialiasing preference.
    private final UserSettings userSettings;

    /// Dynamic core-settings write permission.
    private final BooleanSupplier launcherWritableSupplier;

    /// Dynamic per-user settings write permission.
    private final BooleanSupplier userWritableSupplier;

    /// Snapshot publisher owned by this adapter.
    private final ValueChangeSupport<FontSettingsSnapshot> changes = new ValueChangeSupport<>(this);

    /// Property subscriptions released on their required event thread.
    private final List<Subscription> propertySubscriptions = new ArrayList<>();

    /// Latest normalized font settings visible to Swing consumers.
    private volatile FontSettingsSnapshot currentSnapshot;

    /// Whether future writes and subscriptions are blocked.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Creates a font-settings adapter on the launcher state event thread.
    ///
    /// @param launcherSettings loaded workspace launcher settings
    /// @param userSettings loaded per-user settings
    /// @param launcherWritableSupplier dynamic workspace write permission
    /// @param userWritableSupplier dynamic per-user write permission
    LauncherFontSettingsStore(
            LauncherSettings launcherSettings,
            UserSettings userSettings,
            BooleanSupplier launcherWritableSupplier,
            BooleanSupplier userWritableSupplier) {
        requireEventThread();
        this.launcherSettings = Objects.requireNonNull(launcherSettings, "launcherSettings");
        this.userSettings = Objects.requireNonNull(userSettings, "userSettings");
        this.launcherWritableSupplier = Objects.requireNonNull(
                launcherWritableSupplier,
                "launcherWritableSupplier");
        this.userWritableSupplier = Objects.requireNonNull(userWritableSupplier, "userWritableSupplier");
        currentSnapshot = readSnapshot();
        subscribeToProperties();
    }

    /// Creates an adapter backed by the process-wide loaded settings.
    ///
    /// @return current launcher font settings adapter
    static LauncherFontSettingsStore createForCurrentSettings() {
        requireEventThread();
        return new LauncherFontSettingsStore(
                SettingsManager.settings(),
                SettingsManager.userSettings(),
                () -> !SettingsManager.hasReadOnlyCoreSettings(),
                () -> !SettingsManager.isUserSettingsReadOnly());
    }

    /// Returns the latest immutable font settings.
    ///
    /// @return current normalized snapshot
    @Override
    public FontSettingsSnapshot snapshot() {
        return currentSnapshot;
    }

    /// Registers a snapshot listener while the adapter remains open.
    ///
    /// @param listener snapshot listener
    /// @return independent listener registration
    @Override
    public Subscription subscribe(ValueChangeListener<FontSettingsSnapshot> listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (lifecycleLock) {
            requireOpen();
            return changes.subscribe(listener);
        }
    }

    /// Queues a launcher UI font-family write.
    ///
    /// @param family selected family, or `null` for the default
    @Override
    public void setLauncherFontFamily(@Nullable String family) {
        @Nullable String normalized = normalizeFamily(family);
        writeLauncher(() -> launcherSettings.launcherFontFamilyProperty().set(normalized));
    }

    /// Queues a game-log font-family write.
    ///
    /// @param family selected family, or `null` for monospaced
    @Override
    public void setLogFontFamily(@Nullable String family) {
        @Nullable String normalized = normalizeFamily(family);
        writeLauncher(() -> launcherSettings.logFontFamilyProperty().set(normalized));
    }

    /// Queues a validated game-log font-size write.
    ///
    /// @param size positive finite font size
    @Override
    public void setLogFontSize(double size) {
        if (!Double.isFinite(size) || size <= 0) {
            throw new IllegalArgumentException("size must be positive and finite");
        }
        writeLauncher(() -> launcherSettings.logFontSizeProperty().set(size));
    }

    /// Queues a per-user text antialiasing write.
    ///
    /// @param mode selected mode
    @Override
    public void setAntialiasingMode(FontAntialiasingMode mode) {
        FontAntialiasingMode validatedMode = Objects.requireNonNull(mode, "mode");
        writeUser(() -> userSettings.fontAntiAliasingProperty().set(validatedMode.persistedValue()));
    }

    /// Releases property subscriptions and blocks later writes.
    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
        }
        execute(this::removeStateListeners);
    }

    /// Registers every persisted property contributing to one font snapshot.
    private void subscribeToProperties() {
        requireEventThread();
        propertySubscriptions.add(launcherSettings.launcherFontFamilyProperty()
                .subscribe(change -> scheduleRefreshSnapshot()));
        propertySubscriptions.add(launcherSettings.logFontFamilyProperty()
                .subscribe(change -> scheduleRefreshSnapshot()));
        propertySubscriptions.add(launcherSettings.logFontSizeProperty()
                .subscribe(change -> scheduleRefreshSnapshot()));
        propertySubscriptions.add(userSettings.fontAntiAliasingProperty()
                .subscribe(change -> scheduleRefreshSnapshot()));
    }

    /// Queues one snapshot refresh after a contributing property changes.
    private void scheduleRefreshSnapshot() {
        execute(this::refreshSnapshot);
    }

    /// Rebuilds and publishes the current snapshot on the launcher state event thread.
    private void refreshSnapshot() {
        requireEventThread();
        if (closed.get()) {
            return;
        }
        FontSettingsSnapshot previous = currentSnapshot;
        FontSettingsSnapshot replacement = readSnapshot();
        currentSnapshot = replacement;
        changes.fireChange(previous, replacement);
    }

    /// Reads and normalizes both persisted settings owners without mutating them.
    ///
    /// @return immutable normalized font settings
    private FontSettingsSnapshot readSnapshot() {
        requireEventThread();
        @Nullable String launcherFamily = launcherSettings.launcherFontFamilyProperty().get();
        @Nullable String logFamily = launcherSettings.logFontFamilyProperty().get();
        @Nullable String antialiasing = userSettings.fontAntiAliasingProperty().get();
        double configuredLogSize = launcherSettings.logFontSizeProperty().get();
        return new FontSettingsSnapshot(
                normalizeFamily(launcherFamily),
                normalizeFamily(logFamily),
                normalizeLogFontSize(configuredLogSize),
                FontAntialiasingMode.fromPersistedValue(antialiasing),
                launcherWritableSupplier.getAsBoolean(),
                userWritableSupplier.getAsBoolean());
    }

    /// Queues a guarded workspace-settings mutation.
    ///
    /// @param operation property mutation
    private void writeLauncher(Runnable operation) {
        write(operation, launcherWritableSupplier);
    }

    /// Queues a guarded per-user settings mutation.
    ///
    /// @param operation property mutation
    private void writeUser(Runnable operation) {
        write(operation, userWritableSupplier);
    }

    /// Queues a guarded mutation on the launcher state event thread.
    ///
    /// @param operation property mutation
    /// @param writableSupplier matching dynamic write permission
    private void write(Runnable operation, BooleanSupplier writableSupplier) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(writableSupplier, "writableSupplier");
        requireOpen();
        execute(() -> {
            if (!closed.get() && writableSupplier.getAsBoolean()) {
                operation.run();
            }
        });
    }

    /// Rejects accesses after closure.
    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Font settings store is closed");
        }
    }

    /// Removes launcher property listeners on their required event thread.
    private void removeStateListeners() {
        requireEventThread();
        for (Subscription subscription : propertySubscriptions) {
            subscription.unsubscribe();
        }
        propertySubscriptions.clear();
    }

    /// Normalizes blank family values to the persisted default representation.
    ///
    /// @param family configured family, or `null`
    /// @return trimmed family, or `null` for the default
    private static @Nullable String normalizeFamily(@Nullable String family) {
        if (family == null) {
            return null;
        }
        String normalized = family.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    /// Normalizes malformed legacy log sizes for display without rewriting the persisted file.
    ///
    /// @param size configured log font size
    /// @return positive finite display size
    private static double normalizeLogFontSize(double size) {
        return Double.isFinite(size) && size > 0 ? size : DEFAULT_LOG_FONT_SIZE;
    }
}
