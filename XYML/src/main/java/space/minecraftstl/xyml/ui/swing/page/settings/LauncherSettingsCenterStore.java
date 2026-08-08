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
import space.minecraftstl.xyml.setting.DownloadSource;
import space.minecraftstl.xyml.setting.EnumCommonDirectory;
import space.minecraftstl.xyml.setting.LauncherSettings;
import space.minecraftstl.xyml.setting.ProxyType;
import space.minecraftstl.xyml.setting.SettingsManager;
import space.minecraftstl.xyml.task.FetchTask;
import space.minecraftstl.xyml.util.i18n.SupportedLocale;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static space.minecraftstl.xyml.ui.swing.runtime.LauncherStateDispatcher.execute;
import static space.minecraftstl.xyml.ui.swing.runtime.LauncherStateDispatcher.requireEventThread;

/// Adapts launcher launcher properties to immutable settings-center snapshots.
///
/// Every launcher read, listener registration, and write happens on the launcher Swing state event thread. Snapshot
/// publication itself is thread-safe so a caller can subscribe before handing it to a Swing component.
@NotNullByDefault
public final class LauncherSettingsCenterStore implements SettingsCenterStore {
    /// Serializes subscription registration with closure.
    private final Object lifecycleLock = new Object();

    /// Process-owned launcher settings exposed through this adapter.
    private final LauncherSettings settings;

    /// Dynamic persistence-permission query.
    private final BooleanSupplier writableSupplier;

    /// Snapshot change publisher owned by this adapter.
    private final ValueChangeSupport<SettingsCenterSnapshot> changes = new ValueChangeSupport<>(this);

    /// Launcher property subscriptions released at close.
    private final List<Subscription> propertySubscriptions = new ArrayList<>();

    /// Latest normalized settings state visible outside the Swing EDT.
    private volatile SettingsCenterSnapshot currentSnapshot;

    /// Whether listener cleanup and future writes are disabled.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Creates a launcher settings adapter on the launcher Swing state event thread.
    ///
    /// @param settings loaded launcher settings
    /// @param writableSupplier dynamic core-settings write permission
    public LauncherSettingsCenterStore(
            LauncherSettings settings,
            BooleanSupplier writableSupplier) {
        requireEventThread();
        this.settings = Objects.requireNonNull(settings, "settings");
        this.writableSupplier = Objects.requireNonNull(writableSupplier, "writableSupplier");
        currentSnapshot = readSnapshot();
        subscribeToSettingsProperties();
    }

    /// Creates an adapter backed by the process-wide launcher settings.
    ///
    /// @return adapter for current launcher settings
    public static LauncherSettingsCenterStore createForCurrentSettings() {
        requireEventThread();
        return new LauncherSettingsCenterStore(
                SettingsManager.settings(),
                () -> !SettingsManager.hasReadOnlyCoreSettings());
    }

    /// Returns the latest immutable settings-center snapshot.
    ///
    /// @return most recently normalized snapshot
    @Override
    public SettingsCenterSnapshot snapshot() {
        return currentSnapshot;
    }

    /// Registers a snapshot listener while the adapter remains open.
    ///
    /// @param listener snapshot listener
    /// @return independent listener registration
    @Override
    public Subscription subscribe(ValueChangeListener<SettingsCenterSnapshot> listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (lifecycleLock) {
            requireOpen();
            return changes.subscribe(listener);
        }
    }

    /// Queues a language write on the Swing EDT.
    ///
    /// @param language requested launcher language
    @Override
    public void setLanguage(SupportedLocale language) {
        write(() -> settings.languageProperty().set(Objects.requireNonNull(language, "language")));
    }

    /// Queues a preview-update preference write.
    ///
    /// @param accepted whether preview updates are eligible
    @Override
    public void setAcceptPreviewUpdates(boolean accepted) {
        write(() -> settings.acceptPreviewUpdateProperty().set(accepted));
    }

    /// Queues the automatic-update-dialog preference write.
    ///
    /// @param disabled whether automatic update dialogs are disabled
    @Override
    public void setAutomaticUpdatePromptDisabled(boolean disabled) {
        write(() -> settings.disableAutoShowUpdateDialogProperty().set(disabled));
    }

    /// Queues the April Fools preference write.
    ///
    /// @param disabled whether April Fools behavior is disabled
    @Override
    public void setAprilFoolsDisabled(boolean disabled) {
        write(() -> settings.disableAprilFoolsProperty().set(disabled));
    }

    /// Queues a common-directory mode write.
    ///
    /// @param directoryType requested directory mode
    @Override
    public void setCommonDirectoryType(EnumCommonDirectory directoryType) {
        write(() -> settings.commonDirectoryTypeProperty().set(
                Objects.requireNonNull(directoryType, "directoryType")));
    }

    /// Queues a custom common-directory write.
    ///
    /// @param directory configured directory value
    @Override
    public void setCommonDirectory(String directory) {
        write(() -> settings.commonDirectoryProperty().set(Objects.requireNonNull(directory, "directory")));
    }

    /// Queues the automatic-download-concurrency preference write.
    ///
    /// @param automatic whether automatic download concurrency is enabled
    @Override
    public void setAutomaticDownloadThreads(boolean automatic) {
        write(() -> settings.autoDownloadThreadsProperty().set(automatic));
    }

    /// Queues a validated manual download-concurrency write.
    ///
    /// @param threads positive download concurrency
    @Override
    public void setDownloadThreads(int threads) {
        if (threads <= 0) {
            throw new IllegalArgumentException("threads must be positive");
        }
        write(() -> settings.downloadThreadsProperty().set(threads));
    }

    /// Queues a game-version list source write.
    ///
    /// @param source preferred source
    @Override
    public void setVersionListSource(DownloadSource source) {
        write(() -> settings.versionListSourceProperty().set(Objects.requireNonNull(source, "source")));
    }

    /// Queues a file-download source write.
    ///
    /// @param source preferred source
    @Override
    public void setFileDownloadSource(DownloadSource source) {
        write(() -> settings.fileDownloadSourceProperty().set(Objects.requireNonNull(source, "source")));
    }

    /// Queues a default add-on source write.
    ///
    /// @param sourceId non-blank catalogue source ID
    @Override
    public void setDefaultAddonSource(String sourceId) {
        String validatedSourceId = Objects.requireNonNull(sourceId, "sourceId");
        if (validatedSourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }
        write(() -> settings.defaultAddonSourceProperty().set(validatedSourceId));
    }

    /// Queues a proxy-type write.
    ///
    /// @param proxyType requested proxy type
    @Override
    public void setProxyType(ProxyType proxyType) {
        write(() -> settings.proxyTypeProperty().set(Objects.requireNonNull(proxyType, "proxyType")));
    }

    /// Queues a proxy-host write.
    ///
    /// @param host configured host
    @Override
    public void setProxyHost(String host) {
        write(() -> settings.proxyHostProperty().set(Objects.requireNonNull(host, "host")));
    }

    /// Queues a valid proxy-port write.
    ///
    /// @param port port from `0` through `65535`
    @Override
    public void setProxyPort(int port) {
        if (port < 0 || port > 0xFFFF) {
            throw new IllegalArgumentException("port must be in range 0..65535");
        }
        write(() -> settings.proxyPortProperty().set(port));
    }

    /// Queues the proxy-authentication preference write.
    ///
    /// @param enabled whether proxy credentials are enabled
    @Override
    public void setProxyAuthenticationEnabled(boolean enabled) {
        write(() -> settings.hasProxyAuthProperty().set(enabled));
    }

    /// Queues a proxy username write.
    ///
    /// @param username configured username
    @Override
    public void setProxyUsername(String username) {
        write(() -> settings.proxyUserProperty().set(Objects.requireNonNull(username, "username")));
    }

    /// Queues a proxy password write.
    ///
    /// @param password configured password
    @Override
    public void setProxyPassword(String password) {
        write(() -> settings.proxyPasswordProperty().set(Objects.requireNonNull(password, "password")));
    }

    /// Queues the local AI MCP server enablement write.
    ///
    /// @param enabled whether the MCP entry point may serve requests
    @Override
    public void setMcpEnabled(boolean enabled) {
        write(() -> settings.mcpEnabledProperty().set(enabled));
    }

    /// Queues a validated local MCP port write.
    ///
    /// @param port port from 1 through 65535
    @Override
    public void setMcpPort(int port) {
        if (port < 1 || port > 0xFFFF) {
            throw new IllegalArgumentException("mcpPort must be in range 1..65535");
        }
        write(() -> settings.mcpPortProperty().set(port));
    }

    /// Releases subscriptions and blocks later writes.
    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
        }
        execute(this::removeStateListeners);
    }

    /// Registers all properties that contribute to a rendered settings snapshot.
    private void subscribeToSettingsProperties() {
        requireEventThread();
        propertySubscriptions.add(settings.languageProperty().subscribe(change -> scheduleRefreshSnapshot()));
        propertySubscriptions.add(settings.acceptPreviewUpdateProperty().subscribe(change -> scheduleRefreshSnapshot()));
        propertySubscriptions.add(settings.disableAutoShowUpdateDialogProperty().subscribe(change -> scheduleRefreshSnapshot()));
        propertySubscriptions.add(settings.disableAprilFoolsProperty().subscribe(change -> scheduleRefreshSnapshot()));
        propertySubscriptions.add(settings.commonDirectoryTypeProperty().subscribe(change -> scheduleRefreshSnapshot()));
        propertySubscriptions.add(settings.commonDirectoryProperty().subscribe(change -> scheduleRefreshSnapshot()));
        propertySubscriptions.add(settings.autoDownloadThreadsProperty().subscribe(change -> scheduleRefreshSnapshot()));
        propertySubscriptions.add(settings.downloadThreadsProperty().subscribe(change -> scheduleRefreshSnapshot()));
        propertySubscriptions.add(settings.versionListSourceProperty().subscribe(change -> scheduleRefreshSnapshot()));
        propertySubscriptions.add(settings.fileDownloadSourceProperty().subscribe(change -> scheduleRefreshSnapshot()));
        propertySubscriptions.add(settings.defaultAddonSourceProperty().subscribe(change -> scheduleRefreshSnapshot()));
        propertySubscriptions.add(settings.proxyTypeProperty().subscribe(change -> scheduleRefreshSnapshot()));
        propertySubscriptions.add(settings.proxyHostProperty().subscribe(change -> scheduleRefreshSnapshot()));
        propertySubscriptions.add(settings.proxyPortProperty().subscribe(change -> scheduleRefreshSnapshot()));
        propertySubscriptions.add(settings.hasProxyAuthProperty().subscribe(change -> scheduleRefreshSnapshot()));
        propertySubscriptions.add(settings.proxyUserProperty().subscribe(change -> scheduleRefreshSnapshot()));
        propertySubscriptions.add(settings.proxyPasswordProperty().subscribe(change -> scheduleRefreshSnapshot()));
        propertySubscriptions.add(settings.mcpEnabledProperty().subscribe(change -> scheduleRefreshSnapshot()));
        propertySubscriptions.add(settings.mcpPortProperty().subscribe(change -> scheduleRefreshSnapshot()));
    }

    /// Queues a launcher-state snapshot refresh after one property change.
    private void scheduleRefreshSnapshot() {
        execute(this::refreshSnapshot);
    }

    /// Rebuilds and publishes the current snapshot on the Swing EDT.
    private void refreshSnapshot() {
        requireEventThread();
        if (closed.get()) {
            return;
        }
        SettingsCenterSnapshot previous = currentSnapshot;
        SettingsCenterSnapshot replacement = readSnapshot();
        currentSnapshot = replacement;
        changes.fireChange(previous, replacement);
    }

    /// Reads, normalizes, and validates all values needed by the settings center.
    ///
    /// @return immutable normalized settings state
    private SettingsCenterSnapshot readSnapshot() {
        requireEventThread();
        @Nullable SupportedLocale configuredLanguage = settings.languageProperty().get();
        @Nullable EnumCommonDirectory configuredDirectoryType = settings.commonDirectoryTypeProperty().get();
        @Nullable String configuredDirectory = settings.commonDirectoryProperty().get();
        @Nullable DownloadSource configuredVersionSource = settings.versionListSourceProperty().get();
        @Nullable DownloadSource configuredFileSource = settings.fileDownloadSourceProperty().get();
        @Nullable String configuredAddonSource = settings.defaultAddonSourceProperty().get();
        @Nullable ProxyType configuredProxyType = settings.proxyTypeProperty().get();
        @Nullable String configuredProxyHost = settings.proxyHostProperty().get();
        @Nullable String configuredProxyUsername = settings.proxyUserProperty().get();
        @Nullable String configuredProxyPassword = settings.proxyPasswordProperty().get();
        @Nullable String resolvedDirectory = settings.getResolvedCommonDirectory();
        return new SettingsCenterSnapshot(
                Objects.requireNonNullElse(configuredLanguage, SupportedLocale.DEFAULT),
                settings.acceptPreviewUpdateProperty().get(),
                settings.disableAutoShowUpdateDialogProperty().get(),
                settings.disableAprilFoolsProperty().get(),
                Objects.requireNonNullElse(configuredDirectoryType, EnumCommonDirectory.DEFAULT),
                Objects.requireNonNullElse(configuredDirectory, ""),
                Objects.requireNonNullElse(resolvedDirectory, ""),
                settings.autoDownloadThreadsProperty().get(),
                normalizeDownloadThreads(settings.downloadThreadsProperty().get()),
                Objects.requireNonNullElse(configuredVersionSource, DownloadSource.DEFAULT),
                Objects.requireNonNullElse(configuredFileSource, DownloadSource.DEFAULT),
                normalizeAddonSource(configuredAddonSource),
                Objects.requireNonNullElse(configuredProxyType, ProxyType.SYSTEM),
                Objects.requireNonNullElse(configuredProxyHost, ""),
                settings.proxyPortProperty().get(),
                settings.hasProxyAuthProperty().get(),
                Objects.requireNonNullElse(configuredProxyUsername, ""),
                Objects.requireNonNullElse(configuredProxyPassword, ""),
                settings.mcpEnabledProperty().get(),
                normalizeMcpPort(settings.mcpPortProperty().get()),
                writableSupplier.getAsBoolean());
    }

    /// Normalizes persisted manual download concurrency without mutating user state during a read.
    ///
    /// @param configuredThreads persisted thread count
    /// @return positive count suitable for UI display
    private static int normalizeDownloadThreads(int configuredThreads) {
        return configuredThreads > 0 ? configuredThreads : FetchTask.DEFAULT_CONCURRENCY;
    }

    /// Normalizes a launcher add-on source value for the selectable catalogue control.
    ///
    /// @param configuredSource persisted source ID, or `null`
    /// @return non-blank source ID
    private static String normalizeAddonSource(@Nullable String configuredSource) {
        return configuredSource == null || configuredSource.isBlank() ? "modrinth" : configuredSource;
    }

    /// Normalizes an invalid persisted MCP port without mutating launcher state during a read.
    ///
    /// @param configuredPort persisted port
    /// @return valid MCP port
    private static int normalizeMcpPort(int configuredPort) {
        return configuredPort >= 1 && configuredPort <= 0xFFFF
                ? configuredPort : LauncherSettings.DEFAULT_MCP_PORT;
    }

    /// Queues a guarded persistent write on the Swing EDT.
    ///
    /// @param operation property mutation to run when the store is writable
    private void write(Runnable operation) {
        Objects.requireNonNull(operation, "operation");
        requireOpen();
        execute(() -> {
            if (!closed.get() && writableSupplier.getAsBoolean()) {
                operation.run();
            }
        });
    }

    /// Rejects accesses after the store has been closed.
    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Settings center store is closed");
        }
    }

    /// Releases launcher listener registrations from their required event thread.
    private void removeStateListeners() {
        requireEventThread();
        for (Subscription subscription : propertySubscriptions) {
            subscription.unsubscribe();
        }
        propertySubscriptions.clear();
    }
}
