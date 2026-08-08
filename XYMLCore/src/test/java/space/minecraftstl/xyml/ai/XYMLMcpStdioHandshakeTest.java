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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Exercises the lightweight stdio server through a complete MCP handshake and discovery sequence.
@NotNullByDefault
public final class XYMLMcpStdioHandshakeTest {

    /// Confirms a newline-delimited MCP client can initialize, list capabilities, and call a tool.
    @Test
    public void completesInitializeAndDiscovery() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (PipedInputStream serverInput = new PipedInputStream(8192);
                 PipedOutputStream clientWriter = new PipedOutputStream(serverInput);
                 PipedInputStream clientReaderStream = new PipedInputStream(8192);
                 PipedOutputStream serverOutput = new PipedOutputStream(clientReaderStream);
                 BufferedReader clientReader = new BufferedReader(new InputStreamReader(
                         clientReaderStream, StandardCharsets.UTF_8));
                 XYMLMcpServer server = new XYMLMcpServer(null, serverInput, serverOutput)) {
                writeMessage(clientWriter, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                        + "\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                        + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}");
                JsonObject initialization = readResponse(clientReader);
                assertEquals("2025-06-18", initialization.getAsJsonObject("result")
                        .get("protocolVersion").getAsString());
                assertEquals("xyml-ai-mcp-server", initialization.getAsJsonObject("result")
                        .getAsJsonObject("serverInfo").get("name").getAsString());

                writeMessage(clientWriter,
                        "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}");
                writeMessage(clientWriter,
                        "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
                JsonObject tools = readResponse(clientReader).getAsJsonObject("result");
                assertEquals(17, tools.getAsJsonArray("tools").size());
                assertTrue(tools.toString().contains("analyze_crash"));

                writeMessage(clientWriter, "{\"jsonrpc\":\"2.0\",\"id\":3,"
                        + "\"method\":\"resources/templates/list\",\"params\":{}}");
                JsonObject resources = readResponse(clientReader).getAsJsonObject("result");
                assertEquals(3, resources.getAsJsonArray("resourceTemplates").size());
                assertTrue(resources.toString().contains("crash_report_directory"));

                writeMessage(clientWriter, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"launch_game\",\"arguments\":{\"instance_id\":\"demo\","
                        + "\"confirmed\":false,\"unused\":null}}}");
                JsonObject callResult = readResponse(clientReader).getAsJsonObject("result");
                assertTrue(callResult.get("isError").getAsBoolean());
                assertFalse(callResult.getAsJsonArray("content").isEmpty());
                assertTrue(callResult.getAsJsonObject("structuredContent").get("error")
                        .getAsString().contains("confirmed=true"));
            }
        });
    }

    /// Returns a JSON-RPC parse error for a top-level JSON string and keeps the session usable.
    @Test
    public void rejectsTopLevelJsonStringWithoutStoppingSession() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (PipedInputStream serverInput = new PipedInputStream(8192);
                 PipedOutputStream clientWriter = new PipedOutputStream(serverInput);
                 PipedInputStream clientReaderStream = new PipedInputStream(8192);
                 PipedOutputStream serverOutput = new PipedOutputStream(clientReaderStream);
                 BufferedReader clientReader = new BufferedReader(new InputStreamReader(
                         clientReaderStream, StandardCharsets.UTF_8));
                 XYMLMcpServer server = new XYMLMcpServer(null, serverInput, serverOutput)) {
                writeMessage(clientWriter, "\"not-an-object\"");
                JsonObject error = readResponse(clientReader).getAsJsonObject("error");
                assertEquals(-32700, error.get("code").getAsInt());

                writeMessage(clientWriter, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");
                assertTrue(readResponse(clientReader).has("result"));
            }
        });
    }

    /// Writes one stdio JSON-RPC frame and flushes it to the server.
    private static void writeMessage(PipedOutputStream output, String message) throws Exception {
        output.write((message + "\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    /// Reads and parses one complete JSON-RPC response line.
    private static JsonObject readResponse(BufferedReader input) throws Exception {
        return JsonParser.parseString(input.readLine()).getAsJsonObject();
    }
}
