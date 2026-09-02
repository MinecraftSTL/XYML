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
package space.minecraftstl.xyml.mcp;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.library.mcp.McpFeatureSet;
import space.minecraftstl.xyml.library.mcp.McpServer;
import space.minecraftstl.xyml.library.mcp.McpServerInfo;

import java.io.IOException;

/// Adapts the launcher MCP registries to the XoyzMCP Streamable HTTP server.
@NotNullByDefault
public final class XYMLMcpServer implements AutoCloseable {
    /// HTTP path used for launcher MCP requests.
    public static final String MCP_PATH = McpServer.MCP_PATH;

    /// Launcher server identity advertised during initialization.
    private static final McpServerInfo SERVER_INFO = new McpServerInfo("xyml-mcp-server", "1.0.0");

    /// Transport and protocol implementation owned by XoyzMCP.
    private final McpServer delegate;

    /// Creates a launcher MCP server without starting its listener.
    ///
    /// @param port loopback TCP port, or zero to select an available port
    /// @param service initialized launcher operation service, or `null` for schema-only use
    public XYMLMcpServer(int port, @Nullable XYMLMcpOperations service) {
        delegate = new McpServer(
                port,
                SERVER_INFO,
                new McpFeatureSet(
                        new XYMLMcpToolRegistry(service),
                        new XYMLMcpResourceRegistry(service),
                        new XYMLMcpPromptRegistry()));
    }

    /// Starts the loopback HTTP listener.
    ///
    /// @throws IOException when the configured port cannot be bound
    public void startListener() throws IOException {
        delegate.startListener();
    }

    /// Returns the active TCP port after the listener has started.
    ///
    /// @return listener port
    public int getListeningPort() {
        return delegate.getListeningPort();
    }

    /// Stops the listener and discards all sessions.
    @Override
    public void close() {
        delegate.close();
    }
}
