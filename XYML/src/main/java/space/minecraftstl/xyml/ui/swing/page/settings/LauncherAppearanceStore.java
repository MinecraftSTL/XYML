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
import space.minecraftstl.xyml.setting.BackgroundType;
import space.minecraftstl.xyml.setting.LauncherSettings;
import space.minecraftstl.xyml.setting.SettingsManager;
import space.minecraftstl.xyml.setting.ThemeColorType;
import space.minecraftstl.xyml.theme.BuiltinBackground;
import space.minecraftstl.xyml.theme.NetworkBackgroundImageCachePolicy;
import space.minecraftstl.xyml.theme.ThemeBrightnessPreference;
import space.minecraftstl.xyml.theme.ThemeColor;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static space.minecraftstl.xyml.ui.swing.runtime.LauncherStateDispatcher.execute;
import static space.minecraftstl.xyml.ui.swing.runtime.LauncherStateDispatcher.requireEventThread;

/// Adapter isolating persisted launcher settings behind [AppearanceSettingsStore].
///
/// Property access and listener registration occur on the Swing state event thread; the adapter exposes only
/// immutable snapshots to the rest of the Swing UI.
@NotNullByDefault
public final class LauncherAppearanceStore implements AppearanceSettingsStore, AutoCloseable {
    /// Serializes listener registration with the transition to the closed lifecycle state.
    private final Object lifecycleLock = new Object();

    /// Launcher launcher setting object.
    private final LauncherSettings settings;

    /// Dynamic check for whether core launcher settings may be persisted.
    private final BooleanSupplier writableSupplier;

    /// Raw store transition publisher.
    private final ValueChangeSupport<StoredAppearanceSettings> changes = new ValueChangeSupport<>(this);

    /// Subscriptions shared by persisted appearance properties and override membership.
    private final java.util.List<Subscription> propertySubscriptions = new java.util.ArrayList<>();

    /// Latest raw store snapshot available outside the Swing EDT.
    private volatile StoredAppearanceSettings currentSnapshot;

    /// Whether closure has been requested from any thread.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Whether one multi-property appearance write is suppressing intermediate snapshots.
    private boolean batchingAppearanceWrite;

    /// Whether a suppressed launcher property event requires one final snapshot publication.
    private boolean snapshotRefreshPending;

    /// Creates an adapter on the Swing state event thread.
    ///
    /// @param settings loaded launcher settings
    /// @param writableSupplier dynamic core-settings write check
    public LauncherAppearanceStore(
            LauncherSettings settings,
            BooleanSupplier writableSupplier) {
        requireEventThread();
        this.settings = Objects.requireNonNull(settings, "settings");
        this.writableSupplier = Objects.requireNonNull(writableSupplier, "writableSupplier");
        currentSnapshot = readSnapshot();
        propertySubscriptions.add(settings.themeBrightnessModeProperty().subscribe(change -> requestSnapshotRefresh()));
        propertySubscriptions.add(settings.cornerRadiusProperty().subscribe(change -> requestSnapshotRefresh()));
        propertySubscriptions.add(settings.animationDisabledProperty().subscribe(change -> requestSnapshotRefresh()));
        propertySubscriptions.add(settings.themeColorTypeProperty().subscribe(change -> requestSnapshotRefresh()));
        propertySubscriptions.add(settings.customThemeColorProperty().subscribe(change -> requestSnapshotRefresh()));
        propertySubscriptions.add(settings.backgroundTypeProperty().subscribe(change -> requestSnapshotRefresh()));
        propertySubscriptions.add(settings.builtinBackgroundIdProperty().subscribe(change -> requestSnapshotRefresh()));
        propertySubscriptions.add(settings.customBackgroundImagePathProperty().subscribe(change -> requestSnapshotRefresh()));
        propertySubscriptions.add(settings.networkBackgroundImageUrlProperty().subscribe(change -> requestSnapshotRefresh()));
        propertySubscriptions.add(settings.customBackgroundPaintProperty().subscribe(change -> requestSnapshotRefresh()));
        propertySubscriptions.add(settings.backgroundOpacityProperty().subscribe(change -> requestSnapshotRefresh()));
        propertySubscriptions.add(settings.networkBackgroundImageCachePolicyProperty().subscribe(change -> requestSnapshotRefresh()));
        propertySubscriptions.add(settings.windowTransparentProperty().subscribe(change -> requestSnapshotRefresh()));
        propertySubscriptions.add(settings.getThemeAppearanceOverrides().subscribe(change -> requestSnapshotRefresh()));
    }

    /// Creates an adapter for the process-wide loaded launcher settings.
    ///
    /// @return launcher store bound to [SettingsManager#settings()]
    public static LauncherAppearanceStore createForCurrentSettings() {
        requireEventThread();
        return new LauncherAppearanceStore(
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
                throw new IllegalStateException("Launcher appearance store is closed");
            }
            return changes.subscribe(listener);
        }
    }

    /// Queues a four-state brightness preference for atomic persistence on the Swing state event thread.
    ///
    /// Theme inheritance removes only the override key and deliberately retains the previous raw value. The other
    /// preferences add the key and write their canonical launcher values.
    ///
    /// @param preference requested brightness preference
    @Override
    public void setThemeBrightnessPreference(ThemeBrightnessPreference preference) {
        ThemeBrightnessPreference checkedPreference = Objects.requireNonNull(preference, "preference");
        requireOpen();
        execute(() -> {
            if (!closed.get()) {
                writeBrightnessPreference(checkedPreference);
            }
        });
    }

    /// Queues one validated corner radius for persistence on the Swing state event thread.
    @Override
    public void setCornerRadius(int cornerRadius) {
        requireOpen();
        execute(() -> {
            if (!closed.get()) {
                settings.cornerRadiusProperty().set(cornerRadius);
            }
        });
    }

    /// Queues the animation-disable flag for persistence on the Swing state event thread.
    @Override
    public void setAnimationsDisabled(boolean disabled) {
        requireOpen();
        execute(() -> {
            if (!closed.get()) {
                settings.animationDisabledProperty().set(disabled);
            }
        });
    }

    /// Queues one complete theme-color write on the launcher state event thread.
    ///
    /// @param themeColor complete replacement theme-color settings
    @Override
    public void setThemeColorAppearance(ThemeColorAppearanceSettings themeColor) {
        ThemeColorAppearanceSettings replacement = Objects.requireNonNull(themeColor, "themeColor");
        requireOpen();
        execute(() -> {
            if (!closed.get()) {
                writeThemeColorAppearance(replacement);
            }
        });
    }

    /// Queues one complete background setting write on the launcher state event thread.
    ///
    /// @param background complete replacement background settings
    @Override
    public void setBackgroundAppearance(BackgroundAppearanceSettings background) {
        BackgroundAppearanceSettings replacement = Objects.requireNonNull(background, "background");
        requireOpen();
        execute(() -> {
            if (!closed.get()) {
                writeBackgroundAppearance(replacement);
            }
        });
    }

    /// Requests idempotent listener removal without blocking a Swing EDT caller.
    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
        }
        execute(this::removeStateListeners);
    }

    /// Rebuilds and publishes the raw snapshot after one launcher property changes.
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

    /// Defers snapshot rebuilding while one preference mutates both its value and override membership.
    private void requestSnapshotRefresh() {
        requireEventThread();
        if (batchingAppearanceWrite) {
            snapshotRefreshPending = true;
        } else {
            refreshSnapshot();
        }
    }

    /// Writes one preference without publishing an impossible intermediate combination.
    ///
    /// @param preference requested preference
    private void writeBrightnessPreference(ThemeBrightnessPreference preference) {
        requireEventThread();
        batchingAppearanceWrite = true;
        snapshotRefreshPending = false;
        try {
            @Nullable String value = preference.settingValue();
            if (value == null) {
                settings.getThemeAppearanceOverrides().remove(LauncherSettings.THEME_APPEARANCE_BRIGHTNESS_MODE);
            } else {
                settings.themeBrightnessModeProperty().set(value);
                settings.getThemeAppearanceOverrides().add(LauncherSettings.THEME_APPEARANCE_BRIGHTNESS_MODE);
            }
        } finally {
            batchingAppearanceWrite = false;
        }
        if (snapshotRefreshPending) {
            snapshotRefreshPending = false;
            refreshSnapshot();
        }
    }

    /// Writes the custom theme color and matching override key before publishing one replacement snapshot.
    ///
    /// @param themeColor complete validated theme-color settings
    private void writeThemeColorAppearance(ThemeColorAppearanceSettings themeColor) {
        requireEventThread();
        batchingAppearanceWrite = true;
        snapshotRefreshPending = false;
        try {
            settings.customThemeColorProperty().set(themeColor.customColor());
            if (themeColor.overridden()) {
                settings.themeColorTypeProperty().set(ThemeColorType.CUSTOM);
            }
            setOverrideMembership(
                    LauncherSettings.THEME_APPEARANCE_COLOR,
                    themeColor.overridden());
        } finally {
            batchingAppearanceWrite = false;
        }
        if (snapshotRefreshPending) {
            snapshotRefreshPending = false;
            refreshSnapshot();
        }
    }

    /// Writes every background property and matching override key before publishing one replacement snapshot.
    ///
    /// @param background complete validated background settings
    private void writeBackgroundAppearance(BackgroundAppearanceSettings background) {
        requireEventThread();
        batchingAppearanceWrite = true;
        snapshotRefreshPending = false;
        try {
            settings.backgroundTypeProperty().set(background.type());
            settings.builtinBackgroundIdProperty().set(background.builtinBackgroundId());
            settings.customBackgroundImagePathProperty().set(background.customImagePath());
            settings.networkBackgroundImageUrlProperty().set(background.networkImageUrl());
            settings.customBackgroundPaintProperty().set(background.customPaint());
            settings.backgroundOpacityProperty().set(background.opacity());
            settings.networkBackgroundImageCachePolicyProperty().set(background.networkCachePolicy());
            settings.windowTransparentProperty().set(background.windowTransparent());
            setOverrideMembership(
                    LauncherSettings.THEME_APPEARANCE_BACKGROUND,
                    background.sourceOverridden());
            setOverrideMembership(
                    LauncherSettings.THEME_APPEARANCE_BACKGROUND_OPACITY,
                    background.opacityOverridden());
        } finally {
            batchingAppearanceWrite = false;
        }
        if (snapshotRefreshPending) {
            snapshotRefreshPending = false;
            refreshSnapshot();
        }
    }

    /// Adds or removes one selected-theme appearance override key.
    ///
    /// @param key stable launcher override key
    /// @param overridden whether the launcher value overrides the selected theme
    private void setOverrideMembership(String key, boolean overridden) {
        if (overridden) {
            settings.getThemeAppearanceOverrides().add(key);
        } else {
            settings.getThemeAppearanceOverrides().remove(key);
        }
    }

    /// Reads and normalizes the persisted appearance settings on the Swing state event thread.
    ///
    /// @return raw immutable store snapshot
    private StoredAppearanceSettings readSnapshot() {
        requireEventThread();
        @Nullable String configuredMode = settings.themeBrightnessModeProperty().get();
        int radius = alignRadius(settings.cornerRadiusProperty().get());
        BackgroundType backgroundType = Objects.requireNonNullElse(
                settings.backgroundTypeProperty().get(),
                BackgroundType.DEFAULT);
        double opacity = Math.max(0.0, Math.min(1.0, settings.backgroundOpacityProperty().get()));
        boolean customThemeColorOverridden = settings.getThemeAppearanceOverrides().contains(
                LauncherSettings.THEME_APPEARANCE_COLOR)
                && settings.themeColorTypeProperty().get() == ThemeColorType.CUSTOM;
        ThemeColorAppearanceSettings themeColor = new ThemeColorAppearanceSettings(
                Objects.requireNonNullElse(settings.customThemeColorProperty().get(), ThemeColor.DEFAULT),
                customThemeColorOverridden);
        BackgroundAppearanceSettings background = new BackgroundAppearanceSettings(
                backgroundType,
                Objects.requireNonNullElse(
                        settings.builtinBackgroundIdProperty().get(),
                        BuiltinBackground.FALLBACK.id()),
                Objects.requireNonNullElse(settings.customBackgroundImagePathProperty().get(), ""),
                Objects.requireNonNullElse(settings.networkBackgroundImageUrlProperty().get(), ""),
                settings.customBackgroundPaintProperty().get(),
                opacity,
                Objects.requireNonNullElse(
                        settings.networkBackgroundImageCachePolicyProperty().get(),
                        NetworkBackgroundImageCachePolicy.ENABLED),
                settings.windowTransparentProperty().get(),
                settings.getThemeAppearanceOverrides().contains(
                        LauncherSettings.THEME_APPEARANCE_BACKGROUND),
                settings.getThemeAppearanceOverrides().contains(
                        LauncherSettings.THEME_APPEARANCE_BACKGROUND_OPACITY));
        return new StoredAppearanceSettings(
                Objects.requireNonNullElse(configuredMode, "system"),
                radius,
                LauncherSettings.MINIMUM_CORNER_RADIUS,
                LauncherSettings.MAXIMUM_CORNER_RADIUS,
                LauncherSettings.CORNER_RADIUS_STEP,
                settings.animationDisabledProperty().get(),
                themeColor,
                background,
                writableSupplier.getAsBoolean(),
                settings.getThemeAppearanceOverrides().contains(
                        LauncherSettings.THEME_APPEARANCE_BRIGHTNESS_MODE));
    }

    /// Constrains a launcher or externally edited radius to the supported stepped range.
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
            throw new IllegalStateException("Launcher appearance store is closed");
        }
    }

    /// Removes every owned settings listener on the Swing state event thread.
    private void removeStateListeners() {
        requireEventThread();
        for (Subscription subscription : propertySubscriptions) {
            subscription.unsubscribe();
        }
        propertySubscriptions.clear();
    }
}
