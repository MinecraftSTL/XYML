/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.setting;

import space.minecraftstl.xyml.task.FetchTask;
import space.minecraftstl.xyml.util.StringUtils;
import space.minecraftstl.xyml.util.io.NetworkUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.net.*;
import java.util.List;
import java.util.Objects;

import static space.minecraftstl.xyml.setting.SettingsManager.settings;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Installs and updates JVM-wide proxy selection and proxy authentication from launcher settings.
@NotNullByDefault
public final class ProxyManager {

    /// Selector that always bypasses proxies.
    private static final SimpleProxySelector NO_PROXY = new SimpleProxySelector(Proxy.NO_PROXY);

    /// System selector captured before the launcher installs its delegating selector.
    private static final ProxySelector SYSTEM_DEFAULT;

    static {
        @Nullable ProxySelector systemProxySelector = ProxySelector.getDefault();
        SYSTEM_DEFAULT = systemProxySelector != null
                ? new ProxySelectorWrapper(systemProxySelector)
                : NO_PROXY;
    }

    /// Current selector read by the installed JVM-wide delegating selector.
    private static volatile ProxySelector defaultProxySelector = SYSTEM_DEFAULT;

    /// Current proxy authenticator, or `null` when custom proxy credentials are disabled.
    private static volatile @Nullable SimpleAuthenticator defaultAuthenticator = null;

    /// Builds the proxy selector represented by current launcher settings.
    private static ProxySelector getProxySelector() {
        ProxyType proxyType = Objects.requireNonNull(settings().proxyTypeProperty().get());
        return switch (proxyType) {
            case SYSTEM -> ProxyManager.SYSTEM_DEFAULT;
            case DIRECT -> NO_PROXY;
            case HTTP, SOCKS -> {
                @Nullable String host = settings().proxyHostProperty().get();
                int port = settings().proxyPortProperty().get();

                if (StringUtils.isBlank(host)) {
                    yield NO_PROXY;
                } else if (port < 0 || port > 0xFFFF) {
                    LOG.warning("Illegal proxy port: " + port);
                    yield NO_PROXY;
                } else {
                    yield new ProxySelectorWrapper(new SimpleProxySelector(new Proxy(
                            Objects.requireNonNull(proxyType.jdkType()),
                            new InetSocketAddress(host, port))));
                }
            }
        };
    }

    /// Builds proxy credentials from current launcher settings when authentication is enabled.
    ///
    /// @return authenticator, or `null` when proxy authentication is not configured
    private static @Nullable SimpleAuthenticator getAuthenticator() {
        ProxyType proxyType = Objects.requireNonNull(settings().proxyTypeProperty().get());
        if (proxyType.usesCustomAddress() && settings().hasProxyAuthProperty().get()) {
            @Nullable String username = settings().proxyUserProperty().get();
            @Nullable String password = settings().proxyPasswordProperty().get();

            if (username != null || password != null)
                return new SimpleAuthenticator(
                        Objects.requireNonNullElse(username, ""),
                        Objects.requireNonNullElse(password, "").toCharArray()
                );
            else
                return null;
        } else
            return null;
    }

    /// Installs proxy and authentication handlers backed by launcher settings.
    public static void init() {
        ProxySelector.setDefault(new DelegatingProxySelector());
        Authenticator.setDefault(new DelegatingAuthenticator());

        defaultProxySelector = getProxySelector();
        Runnable updateProxySelector = () -> defaultProxySelector = getProxySelector();
        settings().proxyTypeProperty().subscribe(change -> updateProxySelector.run());
        settings().proxyHostProperty().subscribe(change -> updateProxySelector.run());
        settings().proxyPortProperty().subscribe(change -> updateProxySelector.run());

        defaultAuthenticator = getAuthenticator();
        Runnable updateAuthenticator = () -> defaultAuthenticator = getAuthenticator();
        settings().proxyTypeProperty().subscribe(change -> updateAuthenticator.run());
        settings().hasProxyAuthProperty().subscribe(change -> updateAuthenticator.run());
        settings().proxyUserProperty().subscribe(change -> updateAuthenticator.run());
        settings().proxyPasswordProperty().subscribe(change -> updateAuthenticator.run());

        FetchTask.notifyInitialized();
    }

    /// JVM-wide selector that delegates every request to the latest launcher-managed selector.
    @NotNullByDefault
    private static final class DelegatingProxySelector extends ProxySelector {
        /// Selects proxies through the latest launcher-managed selector.
        @Override
        public List<Proxy> select(URI uri) {
            return defaultProxySelector.select(uri);
        }

        /// Reports connection failure through the latest launcher-managed selector.
        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            defaultProxySelector.connectFailed(uri, sa, ioe);
        }
    }

    /// JVM-wide authenticator that delegates to the latest launcher-managed proxy credentials.
    @NotNullByDefault
    private static final class DelegatingAuthenticator extends Authenticator {
        /// Returns the latest launcher-managed proxy credentials when configured.
        @Override
        protected @Nullable PasswordAuthentication getPasswordAuthentication() {
            @Nullable SimpleAuthenticator installedAuthenticator = ProxyManager.defaultAuthenticator;
            return installedAuthenticator != null ? installedAuthenticator.getPasswordAuthentication() : null;
        }
    }

    /// Base selector that validates connection-failure callback arguments.
    @NotNullByDefault
    private static abstract class AbstractProxySelector extends ProxySelector {
        /// Rejects an invalid connection-failure callback; concrete selectors need no additional handling.
        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            if (uri == null || sa == null || ioe == null) {
                throw new IllegalArgumentException("Arguments can't be null.");
            }
        }
    }

    /// Selector returning one immutable proxy choice for every non-null URI.
    @NotNullByDefault
    private static final class SimpleProxySelector extends AbstractProxySelector {
        /// Immutable singleton proxy result.
        private final @Unmodifiable List<Proxy> proxies;

        /// Creates a selector returning the given proxy.
        ///
        /// @param proxy sole proxy result
        private SimpleProxySelector(Proxy proxy) {
            this.proxies = List.of(proxy);
        }

        /// Returns the immutable singleton proxy result.
        @Override
        public @Unmodifiable List<Proxy> select(URI uri) {
            if (uri == null)
                throw new IllegalArgumentException("URI can't be null.");
            return proxies;
        }

        /// Returns a diagnostic representation containing the configured proxy.
        @Override
        public String toString() {
            return "SimpleProxySelector" + proxies;
        }
    }

    /// Wraps another ProxySelector to avoid using proxy for loopback addresses.
    @NotNullByDefault
    private static final class ProxySelectorWrapper extends AbstractProxySelector {
        /// Selector used for non-loopback URIs.
        private final ProxySelector source;

        /// Creates a loopback-bypassing wrapper around a selector.
        ///
        /// @param source selector used for non-loopback URIs
        private ProxySelectorWrapper(ProxySelector source) {
            this.source = source;
        }

        /// Selects a direct connection for loopback URIs and delegates all other URIs.
        @Override
        public List<Proxy> select(URI uri) {
            if (uri == null)
                throw new IllegalArgumentException("URI can't be null.");

            if (NetworkUtils.isLoopbackAddress(uri))
                return NO_PROXY.proxies;

            return source.select(uri);
        }
    }

    /// Authenticator holding one immutable proxy username/password pair.
    @NotNullByDefault
    private static final class SimpleAuthenticator extends Authenticator {
        /// Proxy authentication username.
        private final String username;

        /// Proxy authentication password, never mutated after construction.
        private final char @Unmodifiable [] password;

        /// Creates proxy credentials.
        ///
        /// @param username proxy username
        /// @param password proxy password characters owned by this authenticator
        private SimpleAuthenticator(String username, char @Unmodifiable [] password) {
            this.username = username;
            this.password = password;
        }

        /// Returns credentials only for proxy authentication requests.
        @Override
        public @Nullable PasswordAuthentication getPasswordAuthentication() {
            return getRequestorType() == RequestorType.PROXY ? new PasswordAuthentication(username, password) : null;
        }
    }

    /// Prevents instantiation.
    private ProxyManager() {
    }
}
