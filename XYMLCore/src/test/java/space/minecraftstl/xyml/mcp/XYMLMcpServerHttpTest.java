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
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the Streamable HTTP JSON-RPC transport exposed by the MCP server.
@NotNullByDefault
public final class XYMLMcpServerHttpTest {

    /// Protocol version used by the server's current message surface.
    private static final String PROTOCOL_VERSION = "2025-11-25";

    /// Older protocol version retained for backwards-compatible initialization negotiation.
    private static final String LEGACY_PROTOCOL_VERSION = "2025-03-26";

    /// Accept value used by clients that can consume either supported response representation.
    private static final String ACCEPT_BOTH = "application/json, text/event-stream";

    /// Accept value that leaves SSE as the only usable response representation.
    private static final String ACCEPT_SSE = "application/json;q=0, text/event-stream;q=1";

    /// Accept value that prefers SSE but still permits a single JSON response.
    private static final String ACCEPT_SSE_PREFERRED = "application/json;q=0.1, text/event-stream;q=1";

    /// Performs the Streamable HTTP handshake and then uses the issued session for JSON requests.
    @Test
    public void negotiatesSessionAndJsonResponses() throws Exception {
        try (XYMLMcpServer server = new XYMLMcpServer(0, null)) {
            server.startListener();
            URI endpoint = endpoint(server);
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> initializeResponse = post(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
                    ACCEPT_BOTH,
                    null,
                    null);
            assertEquals(200, initializeResponse.statusCode());
            assertContentType(initializeResponse, "application/json");
            String sessionId = requireHeader(initializeResponse, "Mcp-Session-Id");
            assertFalse(sessionId.isBlank());
            assertEquals(PROTOCOL_VERSION, requireHeader(initializeResponse, "MCP-Protocol-Version"));

            JsonObject initialize = jsonBody(initializeResponse);
            assertEquals("2.0", initialize.get("jsonrpc").getAsString());
            assertEquals(1, initialize.get("id").getAsInt());
            JsonObject capabilities = initialize.getAsJsonObject("result").getAsJsonObject("capabilities");
            assertEquals(3, capabilities.size());
            assertTrue(capabilities.has("tools"));
            assertTrue(capabilities.has("resources"));
            assertTrue(capabilities.has("prompts"));

            HttpResponse<String> initialized = post(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                    ACCEPT_BOTH,
                    sessionId,
                    PROTOCOL_VERSION);
            assertEquals(202, initialized.statusCode());
            assertTrue(initialized.body().isEmpty());
            assertEquals(sessionId, requireHeader(initialized, "Mcp-Session-Id"));

            HttpResponse<String> listResponse = post(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}",
                    ACCEPT_BOTH,
                    sessionId,
                    PROTOCOL_VERSION);
            assertEquals(200, listResponse.statusCode());
            assertContentType(listResponse, "application/json");
            assertEquals(16, jsonBody(listResponse)
                    .getAsJsonObject("result")
                    .getAsJsonArray("tools")
                    .size());
        }
    }

    /// Keeps the complete launcher-specific tools, resources, prompts, and call response surface covered.
    @Test
    public void servesToolsResourcesPromptsAndCalls() throws Exception {
        try (XYMLMcpServer server = new XYMLMcpServer(0, null)) {
            server.startListener();
            URI endpoint = endpoint(server);
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> initializeResponse = post(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
                    ACCEPT_BOTH,
                    null,
                    null);
            String sessionId = requireHeader(initializeResponse, "Mcp-Session-Id");

            HttpResponse<String> listResponse = post(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}",
                    ACCEPT_BOTH,
                    sessionId,
                    PROTOCOL_VERSION);
            assertEquals(16, jsonBody(listResponse)
                    .getAsJsonObject("result")
                    .getAsJsonArray("tools")
                    .size());

            HttpResponse<String> callResponse = post(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                            + "\"params\":{\"name\":\"list_instances\",\"arguments\":{}}}",
                    ACCEPT_BOTH,
                    sessionId,
                    PROTOCOL_VERSION);
            JsonObject callResult = jsonBody(callResponse).getAsJsonObject("result");
            assertTrue(callResult.get("isError").getAsBoolean());
            assertTrue(callResult.has("structuredContent"));

            HttpResponse<String> resourcesResponse = post(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"resources/list\"}",
                    ACCEPT_BOTH,
                    sessionId,
                    PROTOCOL_VERSION);
            assertTrue(jsonBody(resourcesResponse)
                    .getAsJsonObject("result")
                    .getAsJsonArray("resources")
                    .isEmpty());

            HttpResponse<String> templatesResponse = post(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"resources/templates/list\"}",
                    ACCEPT_BOTH,
                    sessionId,
                    PROTOCOL_VERSION);
            assertEquals(3, jsonBody(templatesResponse)
                    .getAsJsonObject("result")
                    .getAsJsonArray("resourceTemplates")
                    .size());
            assertTrue(jsonBody(templatesResponse)
                    .getAsJsonObject("result")
                    .getAsJsonArray("resourceTemplates")
                    .get(0)
                    .getAsJsonObject()
                    .has("uriTemplate"));

            HttpResponse<String> readResponse = post(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"resources/read\","
                            + "\"params\":{\"uri\":\"xyml://instances/demo/logs/latest.log\"}}",
                    ACCEPT_BOTH,
                    sessionId,
                    PROTOCOL_VERSION);
            assertEquals(-32603, jsonBody(readResponse).getAsJsonObject("error").get("code").getAsInt());

            HttpResponse<String> promptsResponse = post(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"prompts/list\"}",
                    ACCEPT_BOTH,
                    sessionId,
                    PROTOCOL_VERSION);
            JsonObject promptsResult = jsonBody(promptsResponse).getAsJsonObject("result");
            assertEquals(1, promptsResult.getAsJsonArray("prompts").size());
            assertTrue(promptsResult.getAsJsonArray("prompts").get(0).getAsJsonObject().has("arguments"));

            HttpResponse<String> promptResponse = post(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"prompts/get\","
                            + "\"params\":{\"name\":\"diagnose_crash\","
                            + "\"arguments\":{\"instance_id\":\"demo\"}}}",
                    ACCEPT_BOTH,
                    sessionId,
                    PROTOCOL_VERSION);
            JsonObject promptResult = jsonBody(promptResponse).getAsJsonObject("result");
            assertEquals(1, promptResult.getAsJsonArray("messages").size());
            assertTrue(promptResult.getAsJsonArray("messages").get(0).toString().contains("demo"));

            HttpResponse<String> unsupportedResponse = post(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"resources/subscribe\"}",
                    ACCEPT_BOTH,
                    sessionId,
                    PROTOCOL_VERSION);
            assertEquals(-32601, jsonBody(unsupportedResponse).getAsJsonObject("error").get("code").getAsInt());
        }
    }

    /// Negotiates a supported legacy protocol and falls back to the current version for unknown requests.
    @Test
    public void negotiatesLegacyAndFallsBackForUnknownVersion() throws Exception {
        try (XYMLMcpServer server = new XYMLMcpServer(0, null)) {
            server.startListener();
            URI endpoint = endpoint(server);
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> legacyResponse = post(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"initialize\","
                            + "\"params\":{\"protocolVersion\":\"" + LEGACY_PROTOCOL_VERSION + "\"}}",
                    ACCEPT_BOTH,
                    null,
                    null);
            assertEquals(200, legacyResponse.statusCode());
            JsonObject legacyBody = jsonBody(legacyResponse);
            assertEquals(LEGACY_PROTOCOL_VERSION, legacyBody
                    .getAsJsonObject("result")
                    .get("protocolVersion")
                    .getAsString());
            String legacySessionId = requireHeader(legacyResponse, "Mcp-Session-Id");
            assertEquals(LEGACY_PROTOCOL_VERSION, requireHeader(legacyResponse, "MCP-Protocol-Version"));

            HttpResponse<String> legacyRequest = post(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"tools/list\"}",
                    ACCEPT_BOTH,
                    legacySessionId,
                    LEGACY_PROTOCOL_VERSION);
            assertEquals(200, legacyRequest.statusCode());
            assertEquals(LEGACY_PROTOCOL_VERSION, requireHeader(legacyRequest, "MCP-Protocol-Version"));

            HttpResponse<String> fallbackResponse = post(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"initialize\","
                            + "\"params\":{\"protocolVersion\":\"2024-11-05\"}}",
                    ACCEPT_BOTH,
                    null,
                    null);
            assertEquals(200, fallbackResponse.statusCode());
            JsonObject fallbackBody = jsonBody(fallbackResponse);
            assertEquals(PROTOCOL_VERSION, fallbackBody
                    .getAsJsonObject("result")
                    .get("protocolVersion")
                    .getAsString());
            assertEquals(PROTOCOL_VERSION, requireHeader(fallbackResponse, "MCP-Protocol-Version"));
        }
    }

    /// Accepts the latest protocol header that modern Streamable HTTP clients send during initialization.
    @Test
    public void acceptsLatestProtocolHeaderDuringInitialization() throws Exception {
        try (XYMLMcpServer server = new XYMLMcpServer(0, null)) {
            server.startListener();
            URI endpoint = endpoint(server);
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = post(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":13,\"method\":\"initialize\","
                            + "\"params\":{\"protocolVersion\":\"" + PROTOCOL_VERSION + "\","
                            + "\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}",
                    ACCEPT_BOTH,
                    null,
                    PROTOCOL_VERSION);
            assertEquals(200, response.statusCode());
            assertEquals(PROTOCOL_VERSION, jsonBody(response)
                    .getAsJsonObject("result")
                    .get("protocolVersion")
                    .getAsString());
            assertEquals(PROTOCOL_VERSION, requireHeader(response, "MCP-Protocol-Version"));
        }
    }

    /// Ignores an unknown protocol header during initialization and negotiates from the request body.
    @Test
    public void ignoresUnknownProtocolHeaderDuringInitialization() throws Exception {
        try (XYMLMcpServer server = new XYMLMcpServer(0, null)) {
            server.startListener();
            URI endpoint = endpoint(server);
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = post(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":14,\"method\":\"initialize\","
                            + "\"params\":{\"protocolVersion\":\"" + PROTOCOL_VERSION + "\"}}",
                    ACCEPT_BOTH,
                    null,
                    "2099-01-01");
            assertEquals(200, response.statusCode());
            assertEquals(PROTOCOL_VERSION, jsonBody(response)
                    .getAsJsonObject("result")
                    .get("protocolVersion")
                    .getAsString());
            assertEquals(PROTOCOL_VERSION, requireHeader(response, "MCP-Protocol-Version"));
        }
    }

    /// Uses the Streamable HTTP SSE representation only when JSON is not acceptable.
    @Test
    public void selectsSseOnlyWhenNegotiated() throws Exception {
        try (XYMLMcpServer server = new XYMLMcpServer(0, null)) {
            server.startListener();
            URI endpoint = endpoint(server);
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> initializeResponse = post(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
                    ACCEPT_BOTH,
                    null,
                    null);
            String sessionId = requireHeader(initializeResponse, "Mcp-Session-Id");

            HttpResponse<String> singleResponse = post(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}",
                    ACCEPT_SSE_PREFERRED,
                    sessionId,
                    PROTOCOL_VERSION);
            assertEquals(200, singleResponse.statusCode());
            assertContentType(singleResponse, "application/json");

            HttpResponse<String> listResponse = post(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}",
                    ACCEPT_SSE,
                    sessionId,
                    PROTOCOL_VERSION);
            assertEquals(200, listResponse.statusCode());
            assertContentType(listResponse, "text/event-stream");
            assertEquals(sessionId, requireHeader(listResponse, "Mcp-Session-Id"));
            assertEquals(PROTOCOL_VERSION, requireHeader(listResponse, "MCP-Protocol-Version"));
            assertEquals("no-cache, no-transform", requireHeader(listResponse, "Cache-Control"));
            String event = listResponse.body();
            assertTrue(event.startsWith("event: message\ndata: "));
            assertTrue(event.endsWith("\n\n"));
            JsonObject response = JsonParser.parseString(
                    event.substring("event: message\ndata: ".length(), event.length() - 2)).getAsJsonObject();
            assertEquals(3, response.get("id").getAsInt());
        }
    }

    /// Rejects malformed JSON-RPC bodies and accepts session headers regardless of casing or order.
    @Test
    public void rejectsMalformedBodiesAndHandlesHeaderOrder() throws Exception {
        try (XYMLMcpServer server = new XYMLMcpServer(0, null)) {
            server.startListener();
            URI endpoint = endpoint(server);
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> malformed = post(
                    client,
                    endpoint,
                    "{",
                    ACCEPT_BOTH,
                    null,
                    null);
            assertEquals(400, malformed.statusCode());
            JsonObject malformedBody = jsonBody(malformed);
            assertEquals(-32700, malformedBody.getAsJsonObject("error").get("code").getAsInt());
            assertTrue(!malformedBody.has("id") || malformedBody.get("id").isJsonNull());

            HttpResponse<String> initializeResponse = post(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":20,\"method\":\"initialize\",\"params\":{}}",
                    ACCEPT_BOTH,
                    null,
                    null);
            String sessionId = requireHeader(initializeResponse, "Mcp-Session-Id");

            HttpResponse<String> nonObject = post(
                    client,
                    endpoint,
                    "[]",
                    ACCEPT_BOTH,
                    sessionId,
                    PROTOCOL_VERSION);
            assertEquals(400, nonObject.statusCode());
            assertEquals(-32600, jsonBody(nonObject).getAsJsonObject("error").get("code").getAsInt());
            assertEquals(sessionId, requireHeader(nonObject, "Mcp-Session-Id"));
            assertEquals(PROTOCOL_VERSION, requireHeader(nonObject, "MCP-Protocol-Version"));

            HttpResponse<String> malformedSession = post(
                    client,
                    endpoint,
                    "{",
                    ACCEPT_BOTH,
                    sessionId,
                    PROTOCOL_VERSION);
            assertEquals(400, malformedSession.statusCode());
            JsonObject malformedSessionBody = jsonBody(malformedSession);
            assertEquals(-32700, malformedSessionBody.getAsJsonObject("error").get("code").getAsInt());
            assertTrue(!malformedSessionBody.has("id") || malformedSessionBody.get("id").isJsonNull());
            assertEquals(sessionId, requireHeader(malformedSession, "Mcp-Session-Id"));
            assertEquals(PROTOCOL_VERSION, requireHeader(malformedSession, "MCP-Protocol-Version"));

            HttpRequest reorderedRequest = HttpRequest.newBuilder(endpoint)
                    .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                    .header("mcp-session-id", sessionId)
                    .header("Accept", ACCEPT_BOTH)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"jsonrpc\":\"2.0\",\"id\":21,\"method\":\"tools/list\"}"))
                    .build();
            HttpResponse<String> reorderedResponse = client.send(
                    reorderedRequest, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, reorderedResponse.statusCode());
            assertEquals(sessionId, requireHeader(reorderedResponse, "Mcp-Session-Id"));
            assertEquals(PROTOCOL_VERSION, requireHeader(reorderedResponse, "MCP-Protocol-Version"));
        }
    }

    /// Rejects requests that do not carry the issued session and negotiated protocol version.
    @Test
    public void enforcesSessionAndProtocolHeaders() throws Exception {
        try (XYMLMcpServer server = new XYMLMcpServer(0, null)) {
            server.startListener();
            URI endpoint = endpoint(server);
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> missingSession = post(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}",
                    ACCEPT_BOTH,
                    null,
                    null);
            assertEquals(400, missingSession.statusCode());

            HttpResponse<String> unknownSession = post(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}",
                    ACCEPT_BOTH,
                    "missing-session",
                    PROTOCOL_VERSION);
            assertEquals(404, unknownSession.statusCode());
            assertEquals(-32001, jsonBody(unknownSession).getAsJsonObject("error").get("code").getAsInt());

            HttpResponse<String> initializeResponse = post(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"initialize\",\"params\":{}}",
                    ACCEPT_BOTH,
                    null,
                    null);
            String sessionId = requireHeader(initializeResponse, "Mcp-Session-Id");

            HttpResponse<String> missingVersion = post(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/list\"}",
                    ACCEPT_BOTH,
                    sessionId,
                    null);
            assertEquals(400, missingVersion.statusCode());

            HttpResponse<String> wrongVersion = post(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/list\"}",
                    ACCEPT_BOTH,
                    sessionId,
                    "2024-11-05");
            assertEquals(400, wrongVersion.statusCode());

            HttpResponse<String> reinitialize = post(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"initialize\",\"params\":{}}",
                    ACCEPT_BOTH,
                    sessionId,
                    null);
            assertEquals(400, reinitialize.statusCode());
        }
    }

    /// Rejects unsupported media negotiation and body content types.
    @Test
    public void validatesHttpNegotiationHeaders() throws Exception {
        try (XYMLMcpServer server = new XYMLMcpServer(0, null)) {
            server.startListener();
            URI endpoint = endpoint(server);
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> missingAccept = post(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
                    null,
                    null,
                    null);
            assertEquals(406, missingAccept.statusCode());
            assertContentType(missingAccept, "application/json");
            assertEquals(-32000, jsonBody(missingAccept).getAsJsonObject("error").get("code").getAsInt());

            HttpResponse<String> unsupportedAccept = post(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{}}",
                    "text/plain",
                    null,
                    null);
            assertEquals(406, unsupportedAccept.statusCode());

            HttpResponse<String> jsonOnlyAccept = post(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"initialize\",\"params\":{}}",
                    "application/json",
                    null,
                    null);
            assertEquals(406, jsonOnlyAccept.statusCode());

            HttpResponse<String> wrongContentType = postWithContentType(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"initialize\",\"params\":{}}",
                    ACCEPT_BOTH,
                    "text/plain",
                    null,
                    null);
            assertEquals(415, wrongContentType.statusCode());
            assertContentType(wrongContentType, "application/json");
        }
    }

    /// Rejects an optional GET stream because this launcher has no server-initiated messages.
    @Test
    public void handlesGetAndUnsupportedMethods() throws Exception {
        try (XYMLMcpServer server = new XYMLMcpServer(0, null)) {
            server.startListener();
            URI endpoint = endpoint(server);
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest missingSessionGet = HttpRequest.newBuilder(endpoint)
                    .header("Accept", "text/event-stream")
                    .GET()
                    .build();
            HttpResponse<String> missingSessionResponse = client.send(
                    missingSessionGet, HttpResponse.BodyHandlers.ofString());
            assertEquals(400, missingSessionResponse.statusCode());

            HttpRequest unknownSessionGet = HttpRequest.newBuilder(endpoint)
                    .header("Accept", "text/event-stream")
                    .header("Mcp-Session-Id", "missing-session")
                    .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                    .GET()
                    .build();
            HttpResponse<String> unknownSessionResponse = client.send(
                    unknownSessionGet, HttpResponse.BodyHandlers.ofString());
            assertEquals(404, unknownSessionResponse.statusCode());

            HttpResponse<String> initializeResponse = post(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
                    ACCEPT_BOTH,
                    null,
                    null);
            String sessionId = requireHeader(initializeResponse, "Mcp-Session-Id");

            HttpRequest getRequest = HttpRequest.newBuilder(endpoint)
                    .header("Accept", "text/event-stream")
                    .header("Mcp-Session-Id", sessionId)
                    .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                    .GET()
                    .build();
            HttpResponse<String> getResponse = client.send(getRequest, HttpResponse.BodyHandlers.ofString());
            assertEquals(405, getResponse.statusCode());
            assertEquals("POST", requireHeader(getResponse, "Allow"));

            HttpRequest putRequest = HttpRequest.newBuilder(endpoint)
                    .PUT(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
            HttpResponse<String> putResponse = client.send(putRequest, HttpResponse.BodyHandlers.ofString());
            assertEquals(405, putResponse.statusCode());
            assertEquals("GET, POST, DELETE", requireHeader(putResponse, "Allow"));

            HttpResponse<String> wrongPath = client.send(HttpRequest.newBuilder(
                            endpoint.resolve("/other"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, wrongPath.statusCode());
        }
    }

    /// Terminates a session with DELETE and rejects subsequent requests using that session.
    @Test
    public void terminatesSessionWithDelete() throws Exception {
        try (XYMLMcpServer server = new XYMLMcpServer(0, null)) {
            server.startListener();
            URI endpoint = endpoint(server);
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> initializeResponse = post(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":30,\"method\":\"initialize\",\"params\":{}}",
                    ACCEPT_BOTH,
                    null,
                    null);
            String sessionId = requireHeader(initializeResponse, "Mcp-Session-Id");

            HttpRequest deleteRequest = HttpRequest.newBuilder(endpoint)
                    .header("mcp-session-id", sessionId)
                    .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                    .DELETE()
                    .build();
            HttpResponse<String> deleteResponse = client.send(
                    deleteRequest, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, deleteResponse.statusCode());
            assertTrue(deleteResponse.body().isEmpty());

            HttpResponse<String> afterDelete = post(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":31,\"method\":\"tools/list\"}",
                    ACCEPT_BOTH,
                    sessionId,
                    PROTOCOL_VERSION);
            assertEquals(404, afterDelete.statusCode());
            assertEquals(-32001, jsonBody(afterDelete).getAsJsonObject("error").get("code").getAsInt());
        }
    }

    /// Rejects non-loopback origins before a request reaches the protocol dispatcher.
    @Test
    public void validatesOriginHeader() throws Exception {
        try (XYMLMcpServer server = new XYMLMcpServer(0, null)) {
            server.startListener();
            URI endpoint = endpoint(server);
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .header("Accept", ACCEPT_BOTH)
                    .header("Content-Type", "application/json")
                    .header("Origin", "https://example.com")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(403, response.statusCode());
        }
    }

    /// Returns the loopback endpoint for a running test server.
    ///
    /// @param server running server
    /// @return endpoint URI
    private static URI endpoint(XYMLMcpServer server) {
        return URI.create("http://127.0.0.1:" + server.getListeningPort() + XYMLMcpServer.MCP_PATH);
    }

    /// Sends one JSON POST with negotiated Streamable HTTP headers.
    ///
    /// @param client HTTP client
    /// @param endpoint MCP endpoint
    /// @param body JSON-RPC request body
    /// @param accept accepted response media types, or null to test a missing header
    /// @param sessionId session identifier, or null before initialization
    /// @param protocolVersion negotiated protocol version, or null before initialization
    /// @return HTTP response
    private static HttpResponse<String> post(
            HttpClient client,
            URI endpoint,
            String body,
            @Nullable String accept,
            @Nullable String sessionId,
            @Nullable String protocolVersion) throws Exception {
        return postWithContentType(client, endpoint, body, accept, "application/json", sessionId, protocolVersion);
    }

    /// Sends one POST while allowing the body media type to be varied.
    ///
    /// @param client HTTP client
    /// @param endpoint MCP endpoint
    /// @param body request body
    /// @param accept accepted response media types, or null
    /// @param contentType request body media type
    /// @param sessionId session identifier, or null
    /// @param protocolVersion protocol version, or null
    /// @return HTTP response
    private static HttpResponse<String> postWithContentType(
            HttpClient client,
            URI endpoint,
            String body,
            @Nullable String accept,
            String contentType,
            @Nullable String sessionId,
            @Nullable String protocolVersion) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", contentType);
        if (accept != null) {
            builder.header("Accept", accept);
        }
        if (sessionId != null) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        if (protocolVersion != null) {
            builder.header("MCP-Protocol-Version", protocolVersion);
        }
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /// Parses a JSON response body.
    ///
    /// @param response HTTP response
    /// @return parsed JSON object
    private static JsonObject jsonBody(HttpResponse<String> response) {
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    /// Asserts that a response uses the expected media type prefix.
    ///
    /// @param response HTTP response
    /// @param expected expected media type
    private static void assertContentType(HttpResponse<String> response, String expected) {
        String contentType = requireHeader(response, "Content-Type");
        assertTrue(contentType.startsWith(expected),
                () -> "Expected Content-Type " + expected + " but got " + contentType);
    }

    /// Returns a required response header.
    ///
    /// @param response HTTP response
    /// @param name header name
    /// @return header value
    private static String requireHeader(HttpResponse<String> response, String name) {
        return Objects.requireNonNull(response.headers().firstValue(name).orElse(null),
                "Missing response header: " + name);
    }
}
