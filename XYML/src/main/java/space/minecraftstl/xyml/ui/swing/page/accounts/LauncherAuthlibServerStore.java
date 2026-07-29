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
package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.auth.authlibinjector.AuthlibInjectorServer;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.setting.SettingsManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import static space.minecraftstl.xyml.setting.SettingsManager.getAuthlibInjectorServers;
import static space.minecraftstl.xyml.ui.swing.runtime.LauncherStateDispatcher.execute;
import static space.minecraftstl.xyml.ui.swing.runtime.LauncherStateDispatcher.executeAndWait;
import static space.minecraftstl.xyml.ui.swing.runtime.LauncherStateDispatcher.requireEventThread;
import static space.minecraftstl.xyml.util.i18n.I18n.i18n;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Bridges persisted authlib-injector servers to immutable Swing-facing values.
///
/// Construction and configuration mutations are serialized on the Swing EDT. Server
/// discovery remains deliberately outside that thread so endpoint metadata cannot freeze the launcher.
@NotNullByDefault
final class LauncherAuthlibServerStore implements AuthlibServerStore, AutoCloseable {
    /// Serializes listener registration with the terminal lifecycle transition.
    private final Object lifecycleLock = new Object();

    /// Publishes completed immutable configured-server snapshots.
    private final ValueChangeSupport<AuthlibServerSnapshot> changes = new ValueChangeSupport<>(this);

    /// Tracks structural configuration mutations in the launcher observable list.
    private final Subscription serversSubscription;

    /// Prevents work after lifecycle closure.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Latest immutable configured-server snapshot readable from every thread.
    private volatile AuthlibServerSnapshot currentSnapshot;

    /// Captures the current configured-server list and installs one structural subscription.
    LauncherAuthlibServerStore() {
        requireEventThread();
        currentSnapshot = readSnapshot();
        serversSubscription = getAuthlibInjectorServers().subscribe(change -> execute(this::refreshSnapshot));
    }

    /// Returns the latest immutable configured-server snapshot.
    @Override
    public AuthlibServerSnapshot snapshot() {
        return currentSnapshot;
    }

    /// Registers one listener for future server-list mutations.
    @Override
    public Subscription subscribe(ValueChangeListener<AuthlibServerSnapshot> listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (lifecycleLock) {
            requireOpen();
            return changes.subscribe(listener);
        }
    }

    /// Resolves endpoint metadata without reading or mutating launcher observable state.
    @Override
    public PreparedAuthlibServer prepareServer(String endpoint) throws IOException {
        requireOpen();
        String source = Objects.requireNonNull(endpoint, "endpoint").trim();
        if (source.isEmpty()) {
            throw new IllegalArgumentException("Authlib-injector server endpoint cannot be blank");
        }
        AuthlibInjectorServer server = AuthlibInjectorServer.locateServer(source);
        requireOpen();
        return new PreparedLauncherAuthlibServer(server);
    }

    /// Persists one previously resolved endpoint through the launcher event dispatcher.
    @Override
    public void addServer(PreparedAuthlibServer server, boolean allowReadOnlyOverwrite) {
        PreparedLauncherAuthlibServer prepared = requirePreparedServer(server);
        requireOpen();
        executeAndWait(() -> addServerOnEventThread(prepared, allowReadOnlyOverwrite));
    }

    /// Removes one exact configured endpoint through the launcher event dispatcher.
    @Override
    public void removeServer(String serverUrl, boolean allowReadOnlyOverwrite) {
        String url = Objects.requireNonNull(serverUrl, "serverUrl");
        requireOpen();
        executeAndWait(() -> removeServerOnEventThread(url, allowReadOnlyOverwrite));
    }

    /// Schedules idempotent launcher listener release without blocking an arbitrary caller.
    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
        }
        execute(serversSubscription::unsubscribe);
    }

    /// Publishes a fresh immutable snapshot after a structural server-list mutation.
    private void refreshSnapshot() {
        requireEventThread();
        if (closed.get()) {
            return;
        }
        AuthlibServerSnapshot previous = currentSnapshot;
        AuthlibServerSnapshot replacement = readSnapshot();
        if (previous.equals(replacement)) {
            return;
        }
        currentSnapshot = replacement;
        try {
            changes.fireChange(previous, replacement);
        } catch (RuntimeException failure) {
            LOG.warning("Failed to publish authlib-injector server state", failure);
        }
    }

    /// Applies a prepared endpoint after validating storage compatibility on the Swing EDT.
    ///
    /// @param prepared prepared endpoint created by this store
    /// @param allowReadOnlyOverwrite whether confirmed recovery may overwrite incompatible storage
    private void addServerOnEventThread(
            PreparedLauncherAuthlibServer prepared,
            boolean allowReadOnlyOverwrite) {
        requireEventThread();
        requireOpen();
        makeStorageWritableIfAllowed(prepared.option().url(), allowReadOnlyOverwrite);
        AuthlibInjectorServer server = prepared.server();
        if (getAuthlibInjectorServers().stream()
                .noneMatch(configured -> configured.getUrl().equals(server.getUrl()))) {
            getAuthlibInjectorServers().add(server);
        }
    }

    /// Applies one exact endpoint removal after validating storage compatibility on the Swing EDT.
    ///
    /// @param serverUrl configured endpoint URL
    /// @param allowReadOnlyOverwrite whether confirmed recovery may overwrite incompatible storage
    private void removeServerOnEventThread(
            String serverUrl,
            boolean allowReadOnlyOverwrite) {
        requireEventThread();
        requireOpen();
        AuthlibInjectorServer server = getAuthlibInjectorServers().stream()
                .filter(configured -> configured.getUrl().equals(serverUrl))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown authlib-injector server: " + serverUrl));
        makeStorageWritableIfAllowed(serverUrl, allowReadOnlyOverwrite);
        getAuthlibInjectorServers().remove(server);
    }

    /// Forces a backup-and-overwrite only after the caller explicitly consented to recovery.
    ///
    /// @param serverUrl configured endpoint URL relevant to the mutation
    /// @param allowReadOnlyOverwrite whether user consented to backup-and-overwrite
    private static void makeStorageWritableIfAllowed(
            String serverUrl,
            boolean allowReadOnlyOverwrite) {
        if (!SettingsManager.isAuthlibInjectorServersReadOnly()) {
            return;
        }
        if (!allowReadOnlyOverwrite) {
            throw new AuthlibServerStorageOverwriteRequiredException(
                    serverUrl,
                    i18n("account.injector.server.storage.read_only"));
        }
        SettingsManager.forceOverwriteAuthlibInjectorServers();
    }

    /// Captures configured endpoint metadata as immutable presentation-safe values.
    ///
    /// @return exact current configured-server snapshot
    private static AuthlibServerSnapshot readSnapshot() {
        requireEventThread();
        List<AuthlibServerOption> servers = new ArrayList<>(getAuthlibInjectorServers().size());
        for (AuthlibInjectorServer server : getAuthlibInjectorServers()) {
            servers.add(new AuthlibServerOption(
                    server.getUrl(),
                    server.getName(),
                    !server.isNonEmailLogin()));
        }
        return new AuthlibServerSnapshot(List.copyOf(servers));
    }

    /// Rejects a prepared endpoint originating from another server-store implementation.
    ///
    /// @param server opaque resolved endpoint
    /// @return validated launcher prepared endpoint
    private static PreparedLauncherAuthlibServer requirePreparedServer(PreparedAuthlibServer server) {
        Objects.requireNonNull(server, "server");
        if (server instanceof PreparedLauncherAuthlibServer prepared) {
            return prepared;
        }
        throw new IllegalArgumentException("Prepared server was not created by this store");
    }

    /// Rejects calls after closure before they can schedule stale state work.
    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Authlib-injector server store is closed");
        }
    }

    /// Opaque launcher server plus its immutable confirmation-safe presentation metadata.
    @NotNullByDefault
    private record PreparedLauncherAuthlibServer(
            AuthlibInjectorServer server,
            AuthlibServerOption option) implements PreparedAuthlibServer {
        /// Creates one opaque prepared endpoint and extracts only safe confirmation metadata.
        private PreparedLauncherAuthlibServer(AuthlibInjectorServer server) {
            this(
                    Objects.requireNonNull(server, "server"),
                    new AuthlibServerOption(
                            server.getUrl(),
                            server.getName(),
                            !server.isNonEmailLogin()));
        }
    }
}
