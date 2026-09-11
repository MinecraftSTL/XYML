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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the Streamable HTTP JSON-RPC transport exposed by the MCP server.
@NotNullByDefault
public final class McpServerHttpTest {

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

    /// Server identity used by protocol tests.
    private static final McpServerInfo SERVER_INFO = new McpServerInfo("xoyz-mcp-test", "1.0");

    /// Complete feature set used by protocol tests.
    private static final McpFeatureSet FEATURES = new McpFeatureSet(
            new TestToolProvider(), new TestResourceProvider(), new TestPromptProvider());

    /// Credential used by authentication coverage.
    private static final String AUTH_TOKEN = "test-bearer-token";

    /// Performs the Streamable HTTP handshake and then uses the issued session for JSON requests.
    @Test
    public void negotiatesSessionAndJsonResponses() throws Exception {
        try (McpServer server = createServer()) {
            server.startListener();
            URI endpoint = endpoint(server);
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> initializeResponse = post(
                    client,
                    endpoint,
                    initializeBody(1),
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
            assertEquals(1, jsonBody(listResponse)
                    .getAsJsonObject("result")
                    .getAsJsonArray("tools")
                    .size());
        }
    }

    /// Advertises only configured capability families and rejects omitted methods.
    @Test
    public void advertisesOnlyConfiguredCapabilities() throws Exception {
        McpFeatureSet toolsOnly = new McpFeatureSet(new TestToolProvider(), null, null);
        try (McpServer server = new McpServer(0, SERVER_INFO, toolsOnly)) {
            server.startListener();
            URI endpoint = endpoint(server);
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> initializeResponse = post(
                    client, endpoint, initializeBody(35), ACCEPT_BOTH, null, null);
            String sessionId = requireHeader(initializeResponse, "Mcp-Session-Id");

            JsonObject capabilities = jsonBody(initializeResponse)
                    .getAsJsonObject("result")
                    .getAsJsonObject("capabilities");
            assertEquals(1, capabilities.size());
            assertTrue(capabilities.has("tools"));

            HttpResponse<String> unavailable = post(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":36,\"method\":\"resources/list\"}",
                    ACCEPT_BOTH,
                    sessionId,
                    PROTOCOL_VERSION);
            assertEquals(-32601, jsonBody(unavailable).getAsJsonObject("error").get("code").getAsInt());
        }
    }

    /// Rejects initialize requests that do not contain the required handshake fields.
    @Test
    public void validatesInitializeParameters() throws Exception {
        try (McpServer server = createServer()) {
            server.startListener();
            URI endpoint = endpoint(server);
            HttpClient client = HttpClient.newHttpClient();

            assertInitializeRejected(client, endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":40,\"method\":\"initialize\"}");
            assertInitializeRejected(client, endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":41,\"method\":\"initialize\","
                            + "\"params\":{\"protocolVersion\":\"\",\"capabilities\":{},"
                            + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}");
            assertInitializeRejected(client, endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"initialize\","
                            + "\"params\":{\"protocolVersion\":\"" + PROTOCOL_VERSION + "\","
                            + "\"capabilities\":[],\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}");
            assertInitializeRejected(client, endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":43,\"method\":\"initialize\","
                            + "\"params\":{\"protocolVersion\":\"" + PROTOCOL_VERSION + "\","
                            + "\"capabilities\":{},\"clientInfo\":{\"name\":\"test\"}}}");
        }
    }

    /// Renews active sessions and expires them after the configured inactivity period.
    @Test
    public void renewsAndExpiresSessions() throws Exception {
        AtomicLong now = new AtomicLong(1_000L);
        try (McpServer server = new McpServer(
                0, SERVER_INFO, FEATURES, Duration.ofMillis(100), 4, now::get)) {
            server.startListener();
            URI endpoint = endpoint(server);
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> initializeResponse = post(
                    client, endpoint, initializeBody(50), ACCEPT_BOTH, null, null);
            String sessionId = requireHeader(initializeResponse, "Mcp-Session-Id");

            now.set(1_090L);
            HttpResponse<String> renewed = post(
                    client, endpoint, "{\"jsonrpc\":\"2.0\",\"id\":51,\"method\":\"tools/list\"}",
                    ACCEPT_BOTH, sessionId, PROTOCOL_VERSION);
            assertEquals(200, renewed.statusCode());

            now.set(1_150L);
            HttpResponse<String> stillActive = post(
                    client, endpoint, "{\"jsonrpc\":\"2.0\",\"id\":52,\"method\":\"tools/list\"}",
                    ACCEPT_BOTH, sessionId, PROTOCOL_VERSION);
            assertEquals(200, stillActive.statusCode());

            now.set(1_251L);
            HttpResponse<String> expired = post(
                    client, endpoint, "{\"jsonrpc\":\"2.0\",\"id\":53,\"method\":\"tools/list\"}",
                    ACCEPT_BOTH, sessionId, PROTOCOL_VERSION);
            assertEquals(404, expired.statusCode());
            assertEquals(-32001, jsonBody(expired).getAsJsonObject("error").get("code").getAsInt());
        }
    }

    /// Keeps the session map within its capacity by evicting the least recently active session.
    @Test
    public void evictsOldestSessionAtCapacity() throws Exception {
        AtomicLong now = new AtomicLong(2_000L);
        try (McpServer server = new McpServer(
                0, SERVER_INFO, FEATURES, Duration.ofHours(1), 2, now::get)) {
            server.startListener();
            URI endpoint = endpoint(server);
            HttpClient client = HttpClient.newHttpClient();
            String firstSession = requireHeader(post(
                    client, endpoint, initializeBody(60), ACCEPT_BOTH, null, null), "Mcp-Session-Id");
            now.set(2_010L);
            String secondSession = requireHeader(post(
                    client, endpoint, initializeBody(61), ACCEPT_BOTH, null, null), "Mcp-Session-Id");
            now.set(2_020L);
            assertEquals(200, post(
                    client, endpoint, "{\"jsonrpc\":\"2.0\",\"id\":62,\"method\":\"tools/list\"}",
                    ACCEPT_BOTH, firstSession, PROTOCOL_VERSION).statusCode());
            now.set(2_030L);
            String newestSession = requireHeader(post(
                    client, endpoint, initializeBody(63), ACCEPT_BOTH, null, null), "Mcp-Session-Id");

            assertEquals(404, post(
                    client, endpoint, "{\"jsonrpc\":\"2.0\",\"id\":64,\"method\":\"tools/list\"}",
                    ACCEPT_BOTH, secondSession, PROTOCOL_VERSION).statusCode());
            assertEquals(200, post(
                    client, endpoint, "{\"jsonrpc\":\"2.0\",\"id\":65,\"method\":\"tools/list\"}",
                    ACCEPT_BOTH, firstSession, PROTOCOL_VERSION).statusCode());
            assertEquals(200, post(
                    client, endpoint, "{\"jsonrpc\":\"2.0\",\"id\":66,\"method\":\"tools/list\"}",
                    ACCEPT_BOTH, newestSession, PROTOCOL_VERSION).statusCode());
        }
    }

    /// Keeps the complete tools, resources, prompts, and call response surface covered.
    @Test
    public void servesToolsResourcesPromptsAndCalls() throws Exception {
        try (McpServer server = createServer()) {
            server.startListener();
            URI endpoint = endpoint(server);
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> initializeResponse = post(
                    client,
                    endpoint,
                    initializeBody(1),
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
            assertEquals(1, jsonBody(listResponse)
                    .getAsJsonObject("result")
                    .getAsJsonArray("tools")
                    .size());

            HttpResponse<String> callResponse = post(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                            + "\"params\":{\"name\":\"echo\",\"arguments\":{}}}",
                    ACCEPT_BOTH,
                    sessionId,
                    PROTOCOL_VERSION);
            JsonObject callResult = jsonBody(callResponse).getAsJsonObject("result");
            assertFalse(callResult.get("isError").getAsBoolean());
            assertTrue(callResult.has("structuredContent"));

            HttpResponse<String> nullArgumentResponse = post(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":31,\"method\":\"tools/call\","
                            + "\"params\":{\"name\":\"echo\",\"arguments\":{\"value\":null}}}",
                    ACCEPT_BOTH,
                    sessionId,
                    PROTOCOL_VERSION);
            assertTrue(jsonBody(nullArgumentResponse)
                    .getAsJsonObject("result")
                    .getAsJsonObject("structuredContent")
                    .getAsJsonObject("arguments")
                    .get("value")
                    .isJsonNull());

            HttpResponse<String> resourcesResponse = post(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"resources/list\"}",
                    ACCEPT_BOTH,
                    sessionId,
                    PROTOCOL_VERSION);
            assertEquals(1, jsonBody(resourcesResponse)
                    .getAsJsonObject("result")
                    .getAsJsonArray("resources")
                    .size());

            HttpResponse<String> templatesResponse = post(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"resources/templates/list\"}",
                    ACCEPT_BOTH,
                    sessionId,
                    PROTOCOL_VERSION);
            assertEquals(1, jsonBody(templatesResponse)
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
                            + "\"params\":{\"uri\":\"mcp-test://status\"}}",
                    ACCEPT_BOTH,
                    sessionId,
                    PROTOCOL_VERSION);
            JsonObject resourceContent = jsonBody(readResponse)
                    .getAsJsonObject("result")
                    .getAsJsonArray("contents")
                    .get(0)
                    .getAsJsonObject();
            assertEquals("mcp-test://status", resourceContent.get("uri").getAsString());
            assertEquals("text/plain", resourceContent.get("mimeType").getAsString());
            assertEquals("ready", resourceContent.get("text").getAsString());

            HttpResponse<String> missingReadResponse = post(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":61,\"method\":\"resources/read\","
                            + "\"params\":{\"uri\":\"mcp-test://missing\"}}",
                    ACCEPT_BOTH,
                    sessionId,
                    PROTOCOL_VERSION);
            assertEquals(-32603,
                    jsonBody(missingReadResponse).getAsJsonObject("error").get("code").getAsInt());

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
                            + "\"params\":{\"name\":\"inspect\","
                            + "\"arguments\":{\"subject\":\"demo\"}}}",
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
        try (McpServer server = createServer()) {
            server.startListener();
            URI endpoint = endpoint(server);
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> legacyResponse = post(
                    client,
                    endpoint,
                    initializeBody(10, LEGACY_PROTOCOL_VERSION),
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

            HttpResponse<String> missingLegacyVersionRequest = post(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":111,\"method\":\"tools/list\"}",
                    ACCEPT_BOTH,
                    legacySessionId,
                    null);
            assertEquals(200, missingLegacyVersionRequest.statusCode());
            assertEquals(LEGACY_PROTOCOL_VERSION,
                    requireHeader(missingLegacyVersionRequest, "MCP-Protocol-Version"));

            HttpResponse<String> fallbackResponse = post(
                    client,
                    endpoint,
                    initializeBody(12, "2024-11-05"),
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
        try (McpServer server = createServer()) {
            server.startListener();
            URI endpoint = endpoint(server);
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = post(
                    client,
                    endpoint,
                    initializeBody(13),
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
        try (McpServer server = createServer()) {
            server.startListener();
            URI endpoint = endpoint(server);
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = post(
                    client,
                    endpoint,
                    initializeBody(14),
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
        try (McpServer server = createServer()) {
            server.startListener();
            URI endpoint = endpoint(server);
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> initializeResponse = post(
                    client,
                    endpoint,
                    initializeBody(1),
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
        try (McpServer server = createServer()) {
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
                    initializeBody(20),
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
        try (McpServer server = createServer()) {
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
                    initializeBody(3),
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
                    initializeBody(6),
                    ACCEPT_BOTH,
                    sessionId,
                    null);
            assertEquals(400, reinitialize.statusCode());
        }
    }

    /// Rejects unsupported media negotiation and body content types.
    @Test
    public void validatesHttpNegotiationHeaders() throws Exception {
        try (McpServer server = createServer()) {
            server.startListener();
            URI endpoint = endpoint(server);
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> missingAccept = post(
                    client,
                    endpoint,
                    initializeBody(1),
                    null,
                    null,
                    null);
            assertEquals(406, missingAccept.statusCode());
            assertContentType(missingAccept, "application/json");
            assertEquals(-32000, jsonBody(missingAccept).getAsJsonObject("error").get("code").getAsInt());

            HttpResponse<String> unsupportedAccept = post(
                    client,
                    endpoint,
                    initializeBody(2),
                    "text/plain",
                    null,
                    null);
            assertEquals(406, unsupportedAccept.statusCode());

            HttpResponse<String> jsonOnlyAccept = post(
                    client,
                    endpoint,
                    initializeBody(3),
                    "application/json",
                    null,
                    null);
            assertEquals(406, jsonOnlyAccept.statusCode());

            HttpResponse<String> wrongContentType = postWithContentType(
                    client,
                    endpoint,
                    initializeBody(4),
                    ACCEPT_BOTH,
                    "text/plain",
                    null,
                    null);
            assertEquals(415, wrongContentType.statusCode());
            assertContentType(wrongContentType, "application/json");
        }
    }

    /// Rejects an optional GET stream because this server has no server-initiated messages.
    @Test
    public void handlesGetAndUnsupportedMethods() throws Exception {
        try (McpServer server = createServer()) {
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
                    initializeBody(1),
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
            assertEquals("POST, DELETE", requireHeader(getResponse, "Allow"));

            HttpRequest putRequest = HttpRequest.newBuilder(endpoint)
                    .PUT(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
            HttpResponse<String> putResponse = client.send(putRequest, HttpResponse.BodyHandlers.ofString());
            assertEquals(405, putResponse.statusCode());
            assertEquals("POST, DELETE", requireHeader(putResponse, "Allow"));

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
        try (McpServer server = createServer()) {
            server.startListener();
            URI endpoint = endpoint(server);
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> initializeResponse = post(
                    client,
                    endpoint,
                    initializeBody(30),
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
        try (McpServer server = createServer()) {
            server.startListener();
            URI endpoint = endpoint(server);
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .header("Accept", ACCEPT_BOTH)
                    .header("Content-Type", "application/json")
                    .header("Origin", "https://example.com")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            initializeBody(1)))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(403, response.statusCode());
        }
    }

    /// Applies the Origin policy before Bearer authentication when both headers are supplied.
    @Test
    public void rejectsInvalidOriginBeforeBearerAuthentication() throws Exception {
        try (McpServer server = new McpServer(0, SERVER_INFO, FEATURES, AUTH_TOKEN)) {
            server.startListener();
            URI endpoint = endpoint(server);
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .header("Accept", ACCEPT_BOTH)
                    .header("Content-Type", "application/json")
                    .header("Origin", "https://example.com")
                    .header("Authorization", "Bearer " + AUTH_TOKEN)
                    .POST(HttpRequest.BodyPublishers.ofString(initializeBody(1)))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(403, response.statusCode());
            assertTrue(response.headers().firstValue("WWW-Authenticate").isEmpty());
        }
    }

    /// Rejects duplicate Origin fields before NanoHTTPD collapses them into one map entry.
    @Test
    public void rejectsDuplicateOriginHeaders() throws Exception {
        try (McpServer server = createServer()) {
            server.startListener();
            try (Socket socket = new Socket("127.0.0.1", server.getListeningPort())) {
                String body = initializeBody(2);
                String request = "POST /mcp HTTP/1.1\r\n"
                        + "Host: 127.0.0.1\r\n"
                        + "Origin: http://localhost\r\n"
                        + "Origin: https://localhost\r\n"
                        + "Accept: " + ACCEPT_BOTH + "\r\n"
                        + "Content-Type: application/json\r\n"
                        + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n"
                        + "Connection: close\r\n\r\n"
                        + body;
                socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
                String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(response.startsWith("HTTP/1.1 403"), response);
            }
        }
    }

    /// Requires the configured Bearer credential before initialization and every later HTTP method.
    @Test
    public void enforcesBearerAuthenticationAcrossMethods() throws Exception {
        try (McpServer server = new McpServer(0, SERVER_INFO, FEATURES, AUTH_TOKEN)) {
            server.startListener();
            URI endpoint = endpoint(server);
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> missing = post(
                    client,
                    endpoint,
                    initializeBody(1),
                    ACCEPT_BOTH,
                    null,
                    null);
            assertEquals(401, missing.statusCode());
            assertEquals("Bearer", requireHeader(missing, "WWW-Authenticate"));
            assertFalse(missing.body().contains(AUTH_TOKEN));

            HttpResponse<String> wrong = postWithAuthorization(
                    client, endpoint, initializeBody(2), "Bearer wrong-token", null, null);
            assertEquals(401, wrong.statusCode());
            assertTrue(wrong.headers().firstValue("Mcp-Session-Id").isEmpty());

            HttpResponse<String> initialized = postWithAuthorization(
                    client, endpoint, initializeBody(3), "bearer " + AUTH_TOKEN, null, null);
            assertEquals(200, initialized.statusCode());
            String sessionId = requireHeader(initialized, "Mcp-Session-Id");

            HttpResponse<String> missingPost = post(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/list\"}",
                    ACCEPT_BOTH,
                    sessionId,
                    PROTOCOL_VERSION);
            assertEquals(401, missingPost.statusCode());
            assertTrue(missingPost.headers().firstValue("Mcp-Session-Id").isEmpty());
            assertFalse(missingPost.body().contains(sessionId));

            HttpRequest missingGetRequest = HttpRequest.newBuilder(endpoint)
                    .header("Accept", "text/event-stream")
                    .header("Mcp-Session-Id", sessionId)
                    .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                    .GET()
                    .build();
            HttpResponse<String> missingGet = client.send(missingGetRequest, HttpResponse.BodyHandlers.ofString());
            assertEquals(401, missingGet.statusCode());
            assertTrue(missingGet.headers().firstValue("Mcp-Session-Id").isEmpty());

            HttpRequest missingDeleteRequest = HttpRequest.newBuilder(endpoint)
                    .header("Mcp-Session-Id", sessionId)
                    .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                    .DELETE()
                    .build();
            HttpResponse<String> missingDelete = client.send(
                    missingDeleteRequest, HttpResponse.BodyHandlers.ofString());
            assertEquals(401, missingDelete.statusCode());
            assertTrue(missingDelete.headers().firstValue("Mcp-Session-Id").isEmpty());

            HttpResponse<String> validPost = postWithAuthorization(
                    client,
                    endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/list\"}",
                    "Bearer " + AUTH_TOKEN,
                    sessionId,
                    PROTOCOL_VERSION);
            assertEquals(200, validPost.statusCode());

            HttpRequest validDeleteRequest = HttpRequest.newBuilder(endpoint)
                    .header("Authorization", "Bearer " + AUTH_TOKEN)
                    .header("Mcp-Session-Id", sessionId)
                    .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                    .DELETE()
                    .build();
            assertEquals(200, client.send(validDeleteRequest, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    /// Applies the Bearer gate before dispatching every supported NanoHTTPD method, including 405 responses.
    @Test
    public void authenticatesUnsupportedMethodsBeforeReturning405() throws Exception {
        try (McpServer server = new McpServer(0, SERVER_INFO, FEATURES, AUTH_TOKEN)) {
            server.startListener();
            URI endpoint = endpoint(server);
            HttpClient client = HttpClient.newHttpClient();
            for (String method : List.of("PUT", "HEAD", "OPTIONS", "TRACE", "PATCH")) {
                HttpRequest missingAuthorization = HttpRequest.newBuilder(endpoint)
                        .header("Accept", ACCEPT_BOTH)
                        .method(method, HttpRequest.BodyPublishers.noBody())
                        .build();
                HttpResponse<String> missing = client.send(
                        missingAuthorization, HttpResponse.BodyHandlers.ofString());
                assertEquals(401, missing.statusCode(), method);
                assertEquals("Bearer", requireHeader(missing, "WWW-Authenticate"), method);

                HttpRequest validAuthorization = HttpRequest.newBuilder(endpoint)
                        .header("Authorization", "Bearer " + AUTH_TOKEN)
                        .header("Accept", ACCEPT_BOTH)
                        .method(method, HttpRequest.BodyPublishers.noBody())
                        .build();
                HttpResponse<String> valid = client.send(
                        validAuthorization, HttpResponse.BodyHandlers.ofString());
                assertEquals(405, valid.statusCode(), method);
                assertEquals("POST, DELETE", requireHeader(valid, "Allow"), method);
            }
        }
    }

    /// Rejects every missing, incorrect, duplicate, or malformed credential before each MCP request path.
    ///
    /// A valid request follows every rejection. This proves an unauthorized initialize did not allocate conflicting
    /// state and an unauthorized session request did not consume or delete the existing session.
    ///
    /// @param requestKind initialize, session POST, GET, DELETE, or method-not-allowed request
    /// @param credentialKind missing, incorrect, duplicate, or malformed Authorization fields
    @ParameterizedTest(name = "{0} rejects {1} Authorization")
    @MethodSource("authenticationFailureMatrix")
    public void authenticatesEveryMcpRequestPathBeforeDispatch(
            String requestKind,
            String credentialKind) throws Exception {
        try (McpServer server = new McpServer(0, SERVER_INFO, FEATURES, AUTH_TOKEN)) {
            server.startListener();
            URI endpoint = endpoint(server);
            HttpClient client = HttpClient.newHttpClient();
            @Nullable String sessionId = requiresInitializedSession(requestKind)
                    ? initializeAuthenticatedSession(client, endpoint)
                    : null;

            HttpResponse<String> rejected = client.send(
                    authenticationRequest(
                            endpoint,
                            requestKind,
                            sessionId,
                            authorizationValues(credentialKind)),
                    HttpResponse.BodyHandlers.ofString());

            assertUnauthorizedResponse(rejected, sessionId);

            HttpResponse<String> accepted = client.send(
                    authenticationRequest(
                            endpoint,
                            requestKind,
                            sessionId,
                            List.of("Bearer " + AUTH_TOKEN)),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(expectedAuthenticatedStatus(requestKind), accepted.statusCode());
        }
    }

    /// Rejects printable credentials outside the Bearer token68 grammar even when they exactly match configuration.
    ///
    /// @param invalidToken configured and supplied non-token68 credential
    @ParameterizedTest(name = "rejects non-token68 credential {0}")
    @ValueSource(strings = {
            "invalid@token",
            "invalid=padding",
            "quoted\"token",
            "invalid%token",
            "invalid\\token"
    })
    public void rejectsCredentialsOutsideToken68Grammar(String invalidToken) throws Exception {
        try (McpServer server = new McpServer(0, SERVER_INFO, FEATURES, invalidToken)) {
            server.startListener();
            HttpResponse<String> response = postWithAuthorization(
                    HttpClient.newHttpClient(),
                    endpoint(server),
                    initializeBody(61),
                    "Bearer " + invalidToken,
                    null,
                    null);

            assertEquals(401, response.statusCode());
            assertEquals("Bearer", requireHeader(response, "WWW-Authenticate"));
            assertFalse(response.body().contains(invalidToken));
        }
    }

    /// Accepts every token68 data-character family and trailing equals padding.
    @Test
    public void acceptsCompleteToken68Grammar() throws Exception {
        String token = "AZaz09-._~+/==";
        try (McpServer server = new McpServer(0, SERVER_INFO, FEATURES, token)) {
            server.startListener();
            HttpResponse<String> response = postWithAuthorization(
                    HttpClient.newHttpClient(),
                    endpoint(server),
                    initializeBody(62),
                    "bEaReR " + token,
                    null,
                    null);

            assertEquals(200, response.statusCode());
        }
    }

    /// Ensures provider-returned values cannot accidentally expose the transport credential.
    @Test
    public void redactsBearerTokenFromStructuredProviderResponses() throws Exception {
        try (McpServer server = new McpServer(0, SERVER_INFO, FEATURES, AUTH_TOKEN)) {
            server.startListener();
            URI endpoint = endpoint(server);
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> initialized = postWithAuthorization(
                    client, endpoint, initializeBody(50), "Bearer " + AUTH_TOKEN, null, null);
            String sessionId = requireHeader(initialized, "Mcp-Session-Id");
            String body = "{\"jsonrpc\":\"2.0\",\"id\":51,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"echo\",\"arguments\":{\"secret\":\""
                    + AUTH_TOKEN + "\"}}}";
            HttpResponse<String> response = postWithAuthorization(
                    client, endpoint, body, "Bearer " + AUTH_TOKEN, sessionId, PROTOCOL_VERSION);
            assertEquals(200, response.statusCode());
            assertFalse(response.body().contains(AUTH_TOKEN));
            assertTrue(response.body().contains("[REDACTED]"));
        }
    }

    /// Ensures a provider cannot expose the credential through a JSON object property name.
    @Test
    public void redactsBearerTokenFromStructuredPropertyNames() {
        JsonObject source = new JsonObject();
        source.add(AUTH_TOKEN, new JsonObject());
        source.add("prefix-" + AUTH_TOKEN + "-suffix", new JsonObject());
        source.add("[REDACTED]", new JsonObject());

        String redacted = JsonCredentialRedactor.redact(source, AUTH_TOKEN).toString();

        assertFalse(redacted.contains(AUTH_TOKEN));
        assertTrue(redacted.contains("[REDACTED]"));
        assertTrue(redacted.contains("[REDACTED]#1"));
    }

    /// Leaves structured output untouched when transport authentication is disabled with an empty token.
    @Test
    public void doesNotRedactWithEmptyBearerToken() {
        JsonObject source = new JsonObject();
        source.addProperty("message", "unchanged");

        assertEquals(source, JsonCredentialRedactor.redact(source, ""));
    }

    /// Redacts a one-character credential from values without corrupting JSON-RPC property names.
    @Test
    public void shortBearerTokenDoesNotCorruptStructuredResponses() throws Exception {
        String shortToken = "i";
        try (McpServer server = new McpServer(0, SERVER_INFO, FEATURES, shortToken)) {
            server.startListener();
            URI endpoint = endpoint(server);
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> initialized = postWithAuthorization(
                    client, endpoint, initializeBody(52), "Bearer " + shortToken, null, null);
            assertEquals(200, initialized.statusCode());
            assertEquals(52, jsonBody(initialized).get("id").getAsInt());
            String sessionId = requireHeader(initialized, "Mcp-Session-Id");

            String body = "{\"jsonrpc\":\"2.0\",\"id\":53,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"echo\",\"arguments\":{\"secret\":\"i\"}}}";
            HttpResponse<String> response = postWithAuthorization(
                    client, endpoint, body, "Bearer " + shortToken, sessionId, PROTOCOL_VERSION);
            assertEquals(200, response.statusCode());
            JsonObject parsed = jsonBody(response);
            assertEquals("2.0", parsed.get("jsonrpc").getAsString());
            assertEquals(53, parsed.get("id").getAsInt());
            assertTrue(response.body().contains("[REDACTED]"));
        }
    }

    /// Rejects two physical Authorization fields before NanoHTTPD can collapse them into one map entry.
    @Test
    public void rejectsDuplicateAuthorizationHeaders() throws Exception {
        try (McpServer server = new McpServer(0, SERVER_INFO, FEATURES, AUTH_TOKEN)) {
            server.startListener();
            String body = initializeBody(1);
            String request = "POST /mcp HTTP/1.1\r\n"
                    + "Host: 127.0.0.1\r\n"
                    + "Authorization: Bearer " + AUTH_TOKEN + "\r\n"
                    + "authorization: Bearer " + AUTH_TOKEN + "\r\n"
                    + "Accept: " + ACCEPT_BOTH + "\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n"
                    + "Connection: close\r\n\r\n"
                    + body;
            try (Socket socket = new Socket("127.0.0.1", server.getListeningPort())) {
                socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
                String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertRawUnauthorizedResponse(response);
            }
        }
    }

    /// Rejects an Authorization value containing a control character before credential comparison.
    @Test
    public void rejectsControlCharacterInAuthorizationHeader() throws Exception {
        try (McpServer server = new McpServer(0, SERVER_INFO, FEATURES, AUTH_TOKEN)) {
            server.startListener();
            String body = initializeBody(2);
            String request = "POST /mcp HTTP/1.1\r\n"
                    + "Host: 127.0.0.1\r\n"
                    + "Authorization: Bearer " + AUTH_TOKEN + '\u0001' + "\r\n"
                    + "Accept: " + ACCEPT_BOTH + "\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n"
                    + "Connection: close\r\n\r\n"
                    + body;
            try (Socket socket = new Socket("127.0.0.1", server.getListeningPort())) {
                socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
                String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertRawUnauthorizedResponse(response);
            }
        }
    }

    /// Rejects malformed non-credential header lines when the bearer gate is enabled.
    @Test
    public void rejectsMalformedHeaderLinesWithBearerAuthentication() throws Exception {
        try (McpServer server = new McpServer(0, SERVER_INFO, FEATURES, AUTH_TOKEN)) {
            server.startListener();
            for (String malformedHeader : List.of(
                    "Broken-Header\r\n",
                    " X-Folded: value\r\n",
                    "Bad Header: value\r\n",
                    ": empty-name\r\n")) {
                String body = initializeBody(30);
                String request = "POST /mcp HTTP/1.1\r\n"
                        + "Host: 127.0.0.1\r\n"
                        + "Authorization: Bearer " + AUTH_TOKEN + "\r\n"
                        + "Accept: " + ACCEPT_BOTH + "\r\n"
                        + malformedHeader
                        + "Content-Type: application/json\r\n"
                        + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n"
                        + "Connection: close\r\n\r\n"
                        + body;
                try (Socket socket = new Socket("127.0.0.1", server.getListeningPort())) {
                    socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                    String response = new String(
                            socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    assertTrue(response.startsWith("HTTP/1.1 401"), response);
                    assertFalse(response.contains(AUTH_TOKEN), response);
                }
            }
        }
    }

    /// Persists any non-empty configured token verbatim; malformed wire credentials still fail closed.
    @Test
    public void acceptsOpaqueConfiguredBearerTokens() {
        assertDoesNotThrow(() -> new McpServer(0, SERVER_INFO, FEATURES, "token with spaces"));
        assertDoesNotThrow(() -> new McpServer(0, SERVER_INFO, FEATURES, "token\nwith-control"));
    }

    /// Rejects malformed request credentials without reflecting their contents.
    @Test
    public void rejectsMalformedBearerCredentials() throws Exception {
        try (McpServer server = new McpServer(0, SERVER_INFO, FEATURES, AUTH_TOKEN)) {
            server.startListener();
            URI endpoint = endpoint(server);
            HttpClient client = HttpClient.newHttpClient();
            for (String authorization : List.of(
                    "Basic " + AUTH_TOKEN,
                    "Bearer",
                    "Bearer " + AUTH_TOKEN + " extra",
                    "Bearer " + AUTH_TOKEN + ",other")) {
                HttpResponse<String> response = postWithAuthorization(
                        client, endpoint, initializeBody(40), authorization, null, null);
                assertEquals(401, response.statusCode());
                assertFalse(response.body().contains(AUTH_TOKEN));
            }
        }
    }

    /// Creates a server exposing deterministic test providers.
    ///
    /// @return unstarted test server
    private static McpServer createServer() {
        return new McpServer(0, SERVER_INFO, FEATURES);
    }

    /// Returns the loopback endpoint for a running test server.
    ///
    /// @param server running server
    /// @return endpoint URI
    private static URI endpoint(McpServer server) {
        return URI.create("http://127.0.0.1:" + server.getListeningPort() + McpServer.MCP_PATH);
    }

    /// Creates a valid initialize request using the current protocol version.
    ///
    /// @param id JSON-RPC request identifier
    /// @return initialize request body
    private static String initializeBody(int id) {
        return initializeBody(id, PROTOCOL_VERSION);
    }

    /// Creates a valid initialize request for a selected protocol version.
    ///
    /// @param id JSON-RPC request identifier
    /// @param protocolVersion requested protocol version
    /// @return initialize request body
    private static String initializeBody(int id, String protocolVersion) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id
                + ",\"method\":\"initialize\",\"params\":{\"protocolVersion\":\""
                + protocolVersion + "\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test-client\","
                + "\"version\":\"1.0\"}}}";
    }

    /// Asserts that an initialize request returns a parameter error without allocating a session.
    ///
    /// @param client HTTP client
    /// @param endpoint MCP endpoint
    /// @param body initialize request body
    private static void assertInitializeRejected(HttpClient client, URI endpoint, String body) throws Exception {
        HttpResponse<String> response = post(client, endpoint, body, ACCEPT_BOTH, null, null);
        assertEquals(400, response.statusCode());
        assertEquals(-32602, jsonBody(response).getAsJsonObject("error").get("code").getAsInt());
        assertTrue(response.headers().firstValue("Mcp-Session-Id").isEmpty());
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

    /// Sends one JSON POST with an explicit Authorization header.
    ///
    /// @param client HTTP client
    /// @param endpoint MCP endpoint
    /// @param body request body
    /// @param authorization Authorization header value
    /// @param sessionId session identifier, or null before initialization
    /// @param protocolVersion protocol version, or null before initialization
    /// @return HTTP response
    private static HttpResponse<String> postWithAuthorization(
            HttpClient client,
            URI endpoint,
            String body,
            String authorization,
            @Nullable String sessionId,
            @Nullable String protocolVersion) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .header("Accept", ACCEPT_BOTH);
        if (sessionId != null) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        if (protocolVersion != null) {
            builder.header("MCP-Protocol-Version", protocolVersion);
        }
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /// Supplies the cross-product of MCP request paths and rejected credential shapes.
    ///
    /// @return parameterized authentication cases
    private static Stream<Arguments> authenticationFailureMatrix() {
        return Stream.of("INITIALIZE", "POST", "GET", "DELETE", "METHOD_NOT_ALLOWED")
                .flatMap(requestKind -> Stream.of("MISSING", "WRONG", "DUPLICATE", "ILLEGAL")
                        .map(credentialKind -> Arguments.of(requestKind, credentialKind)));
    }

    /// Returns whether a request path requires a previously initialized session.
    ///
    /// @param requestKind request-path identifier
    /// @return whether the request includes session and protocol headers
    private static boolean requiresInitializedSession(String requestKind) {
        return switch (requestKind) {
            case "POST", "GET", "DELETE" -> true;
            case "INITIALIZE", "METHOD_NOT_ALLOWED" -> false;
            default -> throw new IllegalArgumentException("Unknown request kind: " + requestKind);
        };
    }

    /// Initializes one authenticated session for a later request-path test.
    ///
    /// @param client HTTP client
    /// @param endpoint MCP endpoint
    /// @return issued session identifier
    private static String initializeAuthenticatedSession(HttpClient client, URI endpoint) throws Exception {
        HttpResponse<String> response = postWithAuthorization(
                client,
                endpoint,
                initializeBody(70),
                "Bearer " + AUTH_TOKEN,
                null,
                null);
        assertEquals(200, response.statusCode());
        return requireHeader(response, "Mcp-Session-Id");
    }

    /// Builds one MCP request with exact Authorization field occurrences.
    ///
    /// @param endpoint MCP endpoint
    /// @param requestKind request-path identifier
    /// @param sessionId initialized session identifier, or null before initialization and for 405 requests
    /// @param authorizationValues exact Authorization field values in wire order
    /// @return immutable HTTP request
    private static HttpRequest authenticationRequest(
            URI endpoint,
            String requestKind,
            @Nullable String sessionId,
            @Unmodifiable List<String> authorizationValues) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint);
        for (String value : authorizationValues) {
            builder.header("Authorization", value);
        }
        if (sessionId != null) {
            builder.header("Mcp-Session-Id", sessionId)
                    .header("MCP-Protocol-Version", PROTOCOL_VERSION);
        }
        switch (requestKind) {
            case "INITIALIZE" -> builder
                    .header("Accept", ACCEPT_BOTH)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(initializeBody(71)));
            case "POST" -> builder
                    .header("Accept", ACCEPT_BOTH)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"jsonrpc\":\"2.0\",\"id\":72,\"method\":\"tools/list\"}"));
            case "GET" -> builder
                    .header("Accept", "text/event-stream")
                    .GET();
            case "DELETE" -> builder.DELETE();
            case "METHOD_NOT_ALLOWED" -> builder.PUT(HttpRequest.BodyPublishers.noBody());
            default -> throw new IllegalArgumentException("Unknown request kind: " + requestKind);
        }
        return builder.build();
    }

    /// Returns exact Authorization fields for one rejected credential shape.
    ///
    /// @param credentialKind credential-shape identifier
    /// @return immutable Authorization field values
    private static @Unmodifiable List<String> authorizationValues(String credentialKind) {
        return switch (credentialKind) {
            case "MISSING" -> List.of();
            case "WRONG" -> List.of("Bearer wrong-token");
            case "DUPLICATE" -> List.of("Bearer " + AUTH_TOKEN, "Bearer " + AUTH_TOKEN);
            case "ILLEGAL" -> List.of("Bearer " + AUTH_TOKEN + " extra");
            default -> throw new IllegalArgumentException("Unknown credential kind: " + credentialKind);
        };
    }

    /// Returns the response expected after a valid credential reaches one request path.
    ///
    /// @param requestKind request-path identifier
    /// @return expected HTTP status code
    private static int expectedAuthenticatedStatus(String requestKind) {
        return switch (requestKind) {
            case "INITIALIZE", "POST", "DELETE" -> 200;
            case "GET", "METHOD_NOT_ALLOWED" -> 405;
            default -> throw new IllegalArgumentException("Unknown request kind: " + requestKind);
        };
    }

    /// Verifies one structured unauthorized response does not disclose credential or session context.
    ///
    /// @param response unauthorized HTTP response
    /// @param sessionId supplied session identifier, or null before initialization
    private static void assertUnauthorizedResponse(
            HttpResponse<String> response,
            @Nullable String sessionId) {
        assertEquals(401, response.statusCode());
        assertEquals("Bearer", requireHeader(response, "WWW-Authenticate"));
        assertTrue(response.headers().firstValue("Mcp-Session-Id").isEmpty());
        assertFalse(response.body().contains(AUTH_TOKEN));
        if (sessionId != null) {
            assertFalse(response.body().contains(sessionId));
        }
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

    /// Verifies the security properties shared by raw unauthorized responses.
    ///
    /// @param response complete raw HTTP response
    private static void assertRawUnauthorizedResponse(String response) {
        assertTrue(response.startsWith("HTTP/1.1 401"), response);
        assertTrue(response.lines().anyMatch(line -> "WWW-Authenticate: Bearer".equalsIgnoreCase(line)), response);
        assertTrue(response.lines().noneMatch(line -> line.regionMatches(
                true, 0, "Mcp-Session-Id:", 0, "Mcp-Session-Id:".length())), response);
        assertFalse(response.contains(AUTH_TOKEN), response);
    }

    /// Supplies one deterministic tool for transport tests.
    @NotNullByDefault
    private static final class TestToolProvider implements McpToolProvider {
        /// {@inheritDoc}
        @Override
        public @Unmodifiable List<ToolDefinition> toolDefinitions() {
            return List.of(new ToolDefinition(
                    "echo",
                    "Returns the supplied arguments.",
                    Map.of("type", "object", "properties", Map.of())));
        }

        /// {@inheritDoc}
        @Override
        public ToolCallResult call(
                String name,
                @Unmodifiable Map<String, @Nullable Object> arguments) {
            if (!"echo".equals(name)) {
                return ToolCallResult.error(name, "Unknown tool: " + name);
            }
            return ToolCallResult.success(Map.of("arguments", arguments));
        }
    }

    /// Supplies deterministic resource metadata, contents, and failures for transport tests.
    @NotNullByDefault
    private static final class TestResourceProvider implements McpResourceProvider {
        /// {@inheritDoc}
        @Override
        public @Unmodifiable List<ResourceDefinition> resourceDefinitions() {
            return List.of(new ResourceDefinition(
                    "mcp-test://status", "test_status", "Test status", "text/plain"));
        }

        /// {@inheritDoc}
        @Override
        public @Unmodifiable List<ResourceTemplateDefinition> resourceTemplateDefinitions() {
            return List.of(new ResourceTemplateDefinition(
                    "mcp-test://items/{name}", "test_item", "Test item", "text/plain"));
        }

        /// {@inheritDoc}
        @Override
        public ResourceReadResult readResource(String uri) throws IOException {
            if ("mcp-test://status".equals(uri)) {
                return new ResourceReadResult(uri, "text/plain", "ready");
            }
            throw new IOException("Test resource is unavailable: " + uri);
        }
    }

    /// Supplies one deterministic prompt for transport tests.
    @NotNullByDefault
    private static final class TestPromptProvider implements McpPromptProvider {
        /// {@inheritDoc}
        @Override
        public @Unmodifiable List<PromptDefinition> promptDefinitions() {
            return List.of(new PromptDefinition(
                    "inspect",
                    "Inspects one subject.",
                    List.of(new PromptArgument("subject", "Subject to inspect", true))));
        }

        /// {@inheritDoc}
        @Override
        public @Unmodifiable Map<String, @Nullable Object> getPrompt(
                String name,
                @Unmodifiable Map<String, @Nullable Object> arguments) {
            if (!"inspect".equals(name)) {
                throw new IllegalArgumentException("Unknown prompt: " + name);
            }
            Object subject = arguments.get("subject");
            return Map.of("messages", List.of(Map.of(
                    "role", "user",
                    "content", Map.of("type", "text", "text", "Inspect " + subject))));
        }
    }
}
