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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the launcher registries are connected to the reusable XoyzMCP transport.
@NotNullByDefault
final class XYMLMcpServerIntegrationTest {
    /// Streamable HTTP protocol version used by the integration request.
    private static final String PROTOCOL_VERSION = "2025-11-25";

    /// Media types required by Streamable HTTP POST negotiation.
    private static final String ACCEPT = "application/json, text/event-stream";

    /// Starts the launcher facade and verifies every launcher-specific capability family.
    @Test
    void exposesLauncherRegistriesThroughXoyzMcp() throws Exception {
        XYMLMcpOperations operations = (XYMLMcpOperations) Proxy.newProxyInstance(
                XYMLMcpOperations.class.getClassLoader(),
                new Class<?>[]{XYMLMcpOperations.class},
                (proxy, method, arguments) -> {
                    if ("getCrashRepairStatus".equals(method.getName())) {
                        return Map.of("operation_id", arguments[0], "state", "RUNNING");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        try (XYMLMcpServer server = new XYMLMcpServer(0, operations)) {
            server.startListener();
            URI endpoint = URI.create("http://127.0.0.1:" + server.getListeningPort() + XYMLMcpServer.MCP_PATH);
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> initialize = post(client, endpoint, """
                    {
                      "jsonrpc": "2.0",
                      "id": 1,
                      "method": "initialize",
                      "params": {
                        "protocolVersion": "2025-11-25",
                        "capabilities": {},
                        "clientInfo": {"name": "xyml-test", "version": "1.0.0"}
                      }
                    }
                    """, null);
            assertEquals(200, initialize.statusCode());
            String sessionId = initialize.headers().firstValue("Mcp-Session-Id").orElseThrow();
            JsonObject initializeResult = result(initialize);
            assertEquals("xyml-mcp-server", initializeResult.getAsJsonObject("serverInfo").get("name").getAsString());
            JsonObject capabilities = initializeResult.getAsJsonObject("capabilities");
            assertTrue(capabilities.has("tools"));
            assertTrue(capabilities.has("resources"));
            assertTrue(capabilities.has("prompts"));

            JsonArray tools = result(post(client, endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", sessionId))
                    .getAsJsonArray("tools");
            assertEquals(24, tools.size());
            assertTrue(tools.asList().stream()
                    .anyMatch(tool -> "analyze_crash".equals(tool.getAsJsonObject().get("name").getAsString())));
            assertTrue(tools.asList().stream()
                    .anyMatch(tool -> "execute_crash_solution".equals(
                            tool.getAsJsonObject().get("name").getAsString())));

            JsonObject callResult = result(post(client, endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":21,\"method\":\"tools/call\","
                            + "\"params\":{\"name\":\"get_crash_repair_status\","
                            + "\"arguments\":{\"operation_id\":\"repair-1\"}}}", sessionId));
            assertFalse(callResult.get("isError").getAsBoolean());
            JsonObject structuredContent = callResult.getAsJsonObject("structuredContent");
            assertEquals("repair-1", structuredContent.get("operation_id").getAsString());
            assertEquals("RUNNING", structuredContent.get("state").getAsString());

            JsonArray templates = result(post(client, endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"resources/templates/list\"}", sessionId))
                    .getAsJsonArray("resourceTemplates");
            assertEquals(3, templates.size());

            JsonArray prompts = result(post(client, endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"prompts/list\"}", sessionId))
                    .getAsJsonArray("prompts");
            assertEquals("diagnose_crash", prompts.get(0).getAsJsonObject().get("name").getAsString());
        }
    }

    /// Confirms server shutdown releases an application operation service that owns retained tasks.
    @Test
    void closesApplicationOperationService() {
        AtomicBoolean closed = new AtomicBoolean();
        XYMLMcpOperations operations = (XYMLMcpOperations) Proxy.newProxyInstance(
                XYMLMcpOperations.class.getClassLoader(),
                new Class<?>[]{XYMLMcpOperations.class, AutoCloseable.class},
                (proxy, method, arguments) -> {
                    if ("close".equals(method.getName())) {
                        closed.set(true);
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        XYMLMcpServer server = new XYMLMcpServer(0, operations);

        server.close();

        assertTrue(closed.get());
    }

    /// Sends one JSON-RPC POST request with optional initialized-session headers.
    ///
    /// @param client HTTP client
    /// @param endpoint MCP endpoint
    /// @param body JSON-RPC request body
    /// @param sessionId initialized session identifier, or null for initialization
    /// @return HTTP response
    /// @throws Exception when the request cannot be sent
    private static HttpResponse<String> post(
            HttpClient client,
            URI endpoint,
            String body,
            @Nullable String sessionId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .header("Accept", ACCEPT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (sessionId != null) {
            request.header("Mcp-Session-Id", sessionId);
            request.header("MCP-Protocol-Version", PROTOCOL_VERSION);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /// Parses the JSON-RPC result object from a successful response.
    ///
    /// @param response HTTP response
    /// @return JSON-RPC result object
    private static JsonObject result(HttpResponse<String> response) {
        assertEquals(200, response.statusCode());
        return JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("result");
    }
}
