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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the loopback HTTP/SSE JSON-RPC subset exposed by the MCP server.
@NotNullByDefault
public final class XYMLMcpServerHttpTest {

    /// Performs capability discovery through the only supported HTTP endpoint.
    @Test
    public void servesDeclaredInterfaces() throws Exception {
        try (XYMLMcpServer server = new XYMLMcpServer(0, null)) {
            server.startListener();
            URI endpoint = URI.create("http://127.0.0.1:" + server.getListeningPort() + XYMLMcpServer.MCP_PATH);
            HttpClient client = HttpClient.newHttpClient();

            JsonObject initialize = post(client, endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
            assertEquals("2.0", initialize.get("jsonrpc").getAsString());
            assertEquals(1, initialize.get("id").getAsInt());
            JsonObject capabilities = initialize.getAsJsonObject("result").getAsJsonObject("capabilities");
            assertEquals(3, capabilities.size());
            assertTrue(capabilities.has("tools"));
            assertTrue(capabilities.has("resources"));
            assertTrue(capabilities.has("prompts"));

            JsonObject list = post(client, endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
            assertEquals(16, list.getAsJsonObject("result").getAsJsonArray("tools").size());

            JsonObject call = post(client, endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                            + "\"params\":{\"name\":\"list_instances\",\"arguments\":{}}}");
            assertTrue(call.getAsJsonObject("result").get("isError").getAsBoolean());
            assertTrue(call.getAsJsonObject("result").has("structuredContent"));

            JsonObject resources = post(client, endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"resources/list\"}");
            assertEquals(0, resources.getAsJsonObject("result").getAsJsonArray("resources").size());

            JsonObject templates = post(client, endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"resources/templates/list\"}");
            assertEquals(3, templates.getAsJsonObject("result").getAsJsonArray("resourceTemplates").size());
            assertTrue(templates.getAsJsonObject("result").getAsJsonArray("resourceTemplates")
                    .get(0).getAsJsonObject().has("uriTemplate"));

            JsonObject read = post(client, endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"resources/read\","
                            + "\"params\":{\"uri\":\"xyml://instances/demo/logs/latest.log\"}}");
            assertEquals(-32603, read.getAsJsonObject("error").get("code").getAsInt());

            JsonObject prompts = post(client, endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"prompts/list\"}");
            assertEquals(1, prompts.getAsJsonObject("result").getAsJsonArray("prompts").size());
            assertTrue(prompts.getAsJsonObject("result").getAsJsonArray("prompts")
                    .get(0).getAsJsonObject().has("arguments"));

            JsonObject prompt = post(client, endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"prompts/get\","
                            + "\"params\":{\"name\":\"diagnose_crash\","
                            + "\"arguments\":{\"instance_id\":\"demo\"}}}");
            assertEquals(1, prompt.getAsJsonObject("result").getAsJsonArray("messages").size());

            JsonObject unsupported = post(client, endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"resources/subscribe\"}");
            assertEquals(-32601, unsupported.getAsJsonObject("error").get("code").getAsInt());
        }
    }

    /// Confirms notifications receive no JSON-RPC response body.
    @Test
    public void suppressesNotificationResponse() throws Exception {
        try (XYMLMcpServer server = new XYMLMcpServer(0, null)) {
            server.startListener();
            URI endpoint = URI.create("http://127.0.0.1:" + server.getListeningPort() + XYMLMcpServer.MCP_PATH);
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"params\":{}}"))
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request,
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(204, response.statusCode());
            assertTrue(response.body().isEmpty());
        }
    }

    /// Rejects every path and HTTP method outside the single endpoint contract.
    @Test
    public void exposesOnlyPostMcpEndpoint() throws Exception {
        try (XYMLMcpServer server = new XYMLMcpServer(0, null)) {
            server.startListener();
            URI root = URI.create("http://127.0.0.1:" + server.getListeningPort());
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> get = client.send(HttpRequest.newBuilder(root.resolve(XYMLMcpServer.MCP_PATH))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> wrongPath = client.send(HttpRequest.newBuilder(root.resolve("/other"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, get.statusCode());
            assertEquals(404, wrongPath.statusCode());
        }
    }

    /// Sends one JSON-RPC request and parses its SSE data event.
    private static JsonObject post(HttpClient client, URI endpoint, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(Objects.requireNonNull(response.headers().firstValue("Content-Type").orElse(null))
                .startsWith("text/event-stream"));
        String event = response.body();
        assertTrue(event.startsWith("data: "));
        assertTrue(event.endsWith("\n\n"));
        return JsonParser.parseString(event.substring("data: ".length(), event.length() - 2)).getAsJsonObject();
    }
}
