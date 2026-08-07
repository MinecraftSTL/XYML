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

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;

/// Owns the official MCP SDK stdio transport and registered XYML capabilities.
@NotNullByDefault
public final class XYMLMcpServer implements AutoCloseable {

    /// Live synchronous MCP server wrapper.
    private final McpSyncServer server;

    /// Signals that the client input stream reached EOF or the server was closed.
    private final CountDownLatch transportClosed = new CountDownLatch(1);

    /// Creates and starts a single-session stdio MCP server.
    ///
    /// The SDK moves synchronous handlers away from its non-blocking transport thread by default,
    /// so launcher I/O never runs on the JavaFX Application Thread.
    ///
    /// @param service initialized launcher operation service
    public XYMLMcpServer(XYMLMcpOperations service) {
        this(service, System.in, System.out);
    }

    /// Creates and starts a single-session MCP server on explicit standard-I/O streams.
    ///
    /// Supplying the protocol streams explicitly lets the launcher redirect its own console
    /// output without redirecting JSON-RPC frames.
    ///
    /// @param service initialized launcher operation service
    /// @param input protocol input stream
    /// @param output protocol output stream
    public XYMLMcpServer(XYMLMcpOperations service, InputStream input, OutputStream output) {
        XYMLMcpToolRegistry registry = new XYMLMcpToolRegistry(service);
        StdioServerTransportProvider transport = new StdioServerTransportProvider(
                McpJsonDefaults.getMapper(), new EndOfInputStream(input, transportClosed), output);
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

    /// Waits until the MCP client closes its input stream or this server is closed.
    ///
    /// @throws InterruptedException if the waiting thread is interrupted
    public void awaitTermination() throws InterruptedException {
        transportClosed.await();
    }

    /// Closes the MCP session and stdio transport gracefully.
    @Override
    public void close() {
        try {
            server.closeGracefully();
        } finally {
            transportClosed.countDown();
        }
    }

    /// Returns the SDK server for protocol-level tests and diagnostics.
    ///
    /// @return live MCP server
    public McpSyncServer server() {
        return server;
    }

    /// Forwards protocol input while notifying the owner when the client disconnects.
    @NotNullByDefault
    private static final class EndOfInputStream extends FilterInputStream {

        /// Latch completed when the wrapped stream ends or fails.
        private final CountDownLatch completion;

        /// Creates an EOF-aware stream wrapper.
        ///
        /// @param input underlying protocol input
        /// @param completion latch to complete on disconnect
        private EndOfInputStream(InputStream input, CountDownLatch completion) {
            super(input);
            this.completion = completion;
        }

        /// Reads one byte and records EOF or an input failure.
        ///
        /// @return byte value, or `-1` at EOF
        /// @throws IOException if the wrapped stream cannot be read
        @Override
        public int read() throws IOException {
            try {
                int value = super.read();
                if (value < 0) {
                    completion.countDown();
                }
                return value;
            } catch (IOException exception) {
                completion.countDown();
                throw exception;
            }
        }

        /// Reads a byte range and records EOF or an input failure.
        ///
        /// @param bytes destination buffer
        /// @param offset first destination index
        /// @param length maximum number of bytes to read
        /// @return number of bytes read, or `-1` at EOF
        /// @throws IOException if the wrapped stream cannot be read
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            try {
                int count = super.read(bytes, offset, length);
                if (count < 0) {
                    completion.countDown();
                }
                return count;
            } catch (IOException exception) {
                completion.countDown();
                throw exception;
            }
        }

        /// Closes the wrapped stream and records the disconnect.
        ///
        /// @throws IOException if the wrapped stream cannot be closed
        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                completion.countDown();
            }
        }
    }
}
