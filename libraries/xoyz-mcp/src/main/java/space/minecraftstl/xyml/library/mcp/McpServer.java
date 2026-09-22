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
package space.minecraftstl.xyml.library.mcp;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/// Hosts application-provided MCP capabilities on a loopback Streamable HTTP endpoint.
///
/// The server owns protocol negotiation and session state. Providers retain ownership of their
/// application operations and may apply their own scheduling or authorization policies.
@NotNullByDefault
public final class McpServer implements AutoCloseable {
    /// HTTP path used for every MCP request.
    public static final String MCP_PATH = McpHttpTransport.MCP_PATH;

    /// Internal HTTP and JSON-RPC implementation.
    private final McpHttpTransport transport;

    /// Creates an MCP server without starting its listener.
    ///
    /// @param port loopback TCP port, or zero to select an available port
    /// @param serverInfo identity advertised during initialization
    /// @param features optional MCP feature providers
    public McpServer(int port, McpServerInfo serverInfo, McpFeatureSet features) {
        this(port, serverInfo, features, "", Duration.ofHours(1), 256, System::currentTimeMillis);
    }

    /// Creates an MCP server with an optional bearer token for transport authentication.
    ///
    /// An empty token disables authentication for compatibility with existing loopback integrations. A non-empty
    /// token is required on every request to the `/mcp` endpoint.
    ///
    /// @param port loopback TCP port, or zero to select an available port
    /// @param serverInfo identity advertised during initialization
    /// @param features optional MCP feature providers
    /// @param bearerToken bearer token, or an empty string to disable authentication
    public McpServer(int port, McpServerInfo serverInfo, McpFeatureSet features, String bearerToken) {
        this(port, serverInfo, features, bearerToken, Duration.ofHours(1), 256, System::currentTimeMillis);
    }

    /// Creates a server with explicit session settings for deterministic package tests.
    ///
    /// @param port loopback TCP port, or zero to select an available port
    /// @param serverInfo identity advertised during initialization
    /// @param features optional MCP feature providers
    /// @param sessionTtl inactivity period before a session expires
    /// @param maxSessions maximum number of retained sessions
    /// @param currentTimeMillis time source returning epoch milliseconds
    McpServer(
            int port,
            McpServerInfo serverInfo,
            McpFeatureSet features,
            Duration sessionTtl,
            int maxSessions,
            LongSupplier currentTimeMillis) {
        this(port, serverInfo, features, "", sessionTtl, maxSessions, currentTimeMillis);
    }

    /// Creates an MCP server with explicit session and authentication settings for package tests.
    ///
    /// @param port loopback TCP port, or zero to select an available port
    /// @param serverInfo identity advertised during initialization
    /// @param features optional MCP feature providers
    /// @param bearerToken bearer token, or an empty string to disable authentication
    /// @param sessionTtl inactivity period before a session expires
    /// @param maxSessions maximum number of retained sessions
    /// @param currentTimeMillis time source returning epoch milliseconds
    McpServer(
            int port,
            McpServerInfo serverInfo,
            McpFeatureSet features,
            String bearerToken,
            Duration sessionTtl,
            int maxSessions,
            LongSupplier currentTimeMillis) {
        transport = new McpHttpTransport(
                port,
                Objects.requireNonNull(serverInfo, "serverInfo"),
                Objects.requireNonNull(features, "features"),
                Objects.requireNonNull(bearerToken, "bearerToken"),
                Objects.requireNonNull(sessionTtl, "sessionTtl"),
                maxSessions,
                Objects.requireNonNull(currentTimeMillis, "currentTimeMillis"));
    }

    /// Starts the loopback HTTP listener in daemon mode.
    ///
    /// @throws IOException when the configured port cannot be bound
    public void startListener() throws IOException {
        transport.startListener();
    }

    /// Returns the active TCP port after the listener has started.
    ///
    /// @return listener port
    public int getListeningPort() {
        return transport.getListeningPort();
    }

    /// Stops the listener and discards all sessions.
    @Override
    public void close() {
        transport.close();
    }
}
