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
package space.minecraftstl.xyml.ai;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.jetbrains.annotations.NotNullByDefault;

/// Owns the official MCP SDK stdio transport and registered XYML capabilities.
@NotNullByDefault
public final class XYMLMcpServer implements AutoCloseable {

    /// Live synchronous MCP server wrapper.
    private final McpSyncServer server;

    /// Creates and starts a single-session stdio MCP server.
    ///
    /// The SDK moves synchronous handlers away from its non-blocking transport thread by default,
    /// so launcher I/O never runs on the JavaFX Application Thread.
    ///
    /// @param service initialized launcher operation service
    public XYMLMcpServer(XYMLMcpService service) {
        XYMLMcpToolRegistry registry = new XYMLMcpToolRegistry(service);
        StdioServerTransportProvider transport = new StdioServerTransportProvider(McpJsonDefaults.getMapper());
        server = McpServer.sync(transport)
                .serverInfo("xyml-ai-mcp-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(false)
                        .resources(false, false)
                        .build())
                .tools(registry.toolSpecifications())
                .resourceTemplates(registry.resourceTemplateSpecifications())
                .build();
    }

    /// Closes the MCP session and stdio transport gracefully.
    @Override
    public void close() {
        server.closeGracefully();
    }

    /// Returns the SDK server for protocol-level tests and diagnostics.
    ///
    /// @return live MCP server
    public McpSyncServer server() {
        return server;
    }
}
