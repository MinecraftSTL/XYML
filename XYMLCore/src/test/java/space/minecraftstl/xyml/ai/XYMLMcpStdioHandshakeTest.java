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
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/// Exercises the official SDK stdio transport through initialize and tools/list.
@NotNullByDefault
public final class XYMLMcpStdioHandshakeTest {

    /// Confirms a newline-delimited MCP client can complete initialization and list tools.
    @Test
    public void completesInitializeAndListTools() throws Exception {
        try (PipedInputStream serverInput = new PipedInputStream(8192);
             PipedOutputStream clientInput = new PipedOutputStream(serverInput)) {
            ByteArrayOutputStream serverOutput = new ByteArrayOutputStream();
            StdioServerTransportProvider transport = new StdioServerTransportProvider(
                    McpJsonDefaults.getMapper(), serverInput, serverOutput);
            XYMLMcpToolRegistry registry = new XYMLMcpToolRegistry(null);
            McpSyncServer server = McpServer.sync(transport)
                    .serverInfo("xyml-test", "1.0")
                    .capabilities(McpSchema.ServerCapabilities.builder()
                            .tools(false)
                            .resources(false, false)
                            .build())
                    .tools(registry.toolSpecifications())
                    .resourceTemplates(registry.resourceTemplateSpecifications())
                    .build();
            try {
                writeMessage(clientInput, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                        + "\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                        + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}");
                writeMessage(clientInput, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}");
                writeMessage(clientInput, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
                writeMessage(clientInput, "{\"jsonrpc\":\"2.0\",\"id\":3,"
                        + "\"method\":\"resources/templates/list\",\"params\":{}}");
                String response = waitForOutput(serverOutput);
                assertTrue(response.contains("\"list_instances\""));
                assertTrue(response.contains("\"analyze_crash\""));
                assertTrue(response.contains("\"crash_report_directory\""));
                assertTrue(response.contains("\"crash_report\""));
            } finally {
                server.closeGracefully();
            }
        }
    }

    /// Writes one stdio JSON-RPC frame and flushes it to the server.
    private static void writeMessage(PipedOutputStream output, String message) throws Exception {
        output.write((message + "\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    /// Waits briefly for the asynchronous stdio reader to produce both list responses.
    private static String waitForOutput(ByteArrayOutputStream output) throws Exception {
        for (int attempt = 0; attempt < 40; attempt++) {
            String response = output.toString(StandardCharsets.UTF_8);
            if (response.contains("\"list_instances\"")
                    && response.contains("\"crash_report_directory\"")) {
                return response;
            }
            Thread.sleep(50L);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
