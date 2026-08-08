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

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import fi.iki.elonen.NanoHTTPD;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.task.Schedulers;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

/// Serves XYML MCP tools through a loopback HTTP/SSE JSON-RPC endpoint.
///
/// The server exposes only `POST /mcp` and the `initialize`, `tools/list`, and `tools/call` methods.
/// Its capability negotiation contains only the tools capability.
@NotNullByDefault
public final class XYMLMcpServer extends NanoHTTPD implements AutoCloseable {

    /// HTTP path used for every JSON-RPC request.
    public static final String MCP_PATH = "/mcp";

    /// SSE media type emitted by successful and protocol-error responses.
    private static final String SSE_MEDIA_TYPE = "text/event-stream; charset=utf-8";

    /// Server identity advertised during MCP initialization.
    private static final @Unmodifiable Map<String, String> SERVER_INFO =
            Map.of("name", "xyml-mcp-server", "version", "1.0.0");

    /// JSON codec shared by request decoding and response serialization.
    private final Gson gson = new Gson();

    /// Registry supplying XYML tool definitions and invocations.
    private final XYMLMcpToolRegistry registry;

    /// Creates a loopback MCP server without starting its listener.
    ///
    /// @param port loopback TCP port, or zero to select an available port
    /// @param service initialized launcher operation service, or `null` for schema-only tests
    public XYMLMcpServer(int port, @Nullable XYMLMcpOperations service) {
        super("127.0.0.1", validatePort(port));
        registry = new XYMLMcpToolRegistry(service);
    }

    /// Starts the loopback HTTP listener using NanoHTTPD's daemon mode.
    ///
    /// @throws IOException when the configured port cannot be bound
    public void startListener() throws IOException {
        start(SOCKET_READ_TIMEOUT, true);
    }

    /// Handles a single HTTP request.
    ///
    /// Only JSON-RPC requests sent with `POST /mcp` are accepted. Responses contain one SSE
    /// `data` event whose value is a JSON-RPC object. Notifications have no `id` and return HTTP
    /// 204 without a response body.
    ///
    /// @param session incoming HTTP request
    /// @return HTTP response for the request
    @Override
    public Response serve(IHTTPSession session) {
        Objects.requireNonNull(session, "session");
        if (session.getMethod() != Method.POST || !MCP_PATH.equals(session.getUri())) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found");
        }
        try {
            String body = readRequestBody(session);
            @Nullable JsonObject response = Schedulers.io().submit(() -> handleRequest(body)).get();
            return response == null
                    ? newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "")
                    : sseResponse(response);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return sseResponse(errorResponse(null, -32603, "Request handling was interrupted"));
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            return sseResponse(errorResponse(null, -32603, cause == null || cause.getMessage() == null
                    ? "Request handling failed" : cause.getMessage()));
        } catch (IOException exception) {
            return sseResponse(errorResponse(null, -32700, "Unable to read JSON-RPC request"));
        } catch (ResponseException exception) {
            return sseResponse(errorResponse(null, -32700,
                    Objects.requireNonNullElse(exception.getMessage(), "Unable to read JSON-RPC request")));
        }
    }

    /// Reads the request body through NanoHTTPD's body parser.
    ///
    /// @param session incoming HTTP request
    /// @return UTF-8 request body, or an empty string when no body was supplied
    /// @throws IOException when the request body cannot be read
    /// @throws ResponseException when NanoHTTPD rejects the request body
    private static String readRequestBody(IHTTPSession session) throws IOException, ResponseException {
        Map<String, String> files = new HashMap<>();
        session.parseBody(files);
        @Nullable String body = files.get("postData");
        return body == null ? "" : body;
    }

    /// Stops the HTTP listener.
    @Override
    public void close() {
        stop();
    }

    /// Parses, validates, and dispatches one JSON-RPC request body.
    ///
    /// @param body complete UTF-8 request body
    /// @return response object, or `null` for a notification
    private @Nullable JsonObject handleRequest(String body) {
        final JsonElement parsed;
        try {
            parsed = JsonParser.parseString(Objects.requireNonNull(body, "body"));
        } catch (JsonParseException exception) {
            return errorResponse(null, -32700, "Invalid JSON-RPC message");
        }
        if (!parsed.isJsonObject()) {
            return errorResponse(null, -32600, "JSON-RPC request must be an object");
        }

        JsonObject request = parsed.getAsJsonObject();
        boolean notification = !request.has("id");
        @Nullable JsonElement id = request.get("id");
        if (!isValidId(id, notification)) {
            return errorResponse(null, -32600, "JSON-RPC id must be a string or number");
        }
        if (!isJsonRpcVersion(request)) {
            return notification ? null : errorResponse(id, -32600, "jsonrpc must be 2.0");
        }

        @Nullable String method = stringMember(request, "method");
        if (method == null || method.isBlank()) {
            return notification ? null : errorResponse(id, -32600, "Request method is missing");
        }

        try {
            Object result = dispatch(method, objectMember(request, "params"));
            return notification ? null : resultResponse(Objects.requireNonNull(id, "request id"), result);
        } catch (ProtocolException exception) {
            return notification ? null : errorResponse(id, exception.code(), exception.getMessage());
        } catch (Exception exception) {
            String message = exception.getMessage();
            return notification ? null : errorResponse(id, -32603,
                    message == null ? exception.getClass().getSimpleName() : message);
        }
    }

    /// Dispatches one request to the supported MCP method surface.
    ///
    /// @param method JSON-RPC method name
    /// @param params decoded parameter object
    /// @return JSON-compatible response result
    private Object dispatch(String method, JsonObject params) {
        return switch (method) {
            case "initialize" -> initialize(params);
            case "tools/list" -> Map.of("tools", registry.toolDefinitions());
            case "tools/call" -> callTool(params);
            default -> throw new ProtocolException(-32601, "Unsupported method: " + method);
        };
    }

    /// Negotiates the protocol version and advertises only tools.
    ///
    /// @param params initialization parameters
    /// @return immutable initialization result
    private static @Unmodifiable Map<String, Object> initialize(JsonObject params) {
        @Nullable String requestedVersion = stringMember(params, "protocolVersion");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", requestedVersion == null ? "2025-06-18" : requestedVersion);
        result.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
        result.put("serverInfo", SERVER_INFO);
        return Map.copyOf(result);
    }

    /// Invokes one tool and formats its MCP content envelope.
    ///
    /// @param params tool-call parameters
    /// @return immutable tool-call result
    private @Unmodifiable Map<String, Object> callTool(JsonObject params) {
        @Nullable String name = stringMember(params, "name");
        if (name == null || name.isBlank()) {
            throw new ProtocolException(-32602, "Tool name is missing");
        }
        Map<String, Object> arguments = mapMember(params, "arguments");
        XYMLMcpToolRegistry.ToolCallResult result = registry.call(name, arguments);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("content", List.of(Map.of("type", "text", "text",
                XYMLMcpToolRegistry.toJsonText(result.structuredContent()))));
        response.put("structuredContent", result.structuredContent());
        response.put("isError", result.error());
        return Map.copyOf(response);
    }

    /// Creates a successful JSON-RPC response.
    ///
    /// @param id request identifier
    /// @param result JSON-compatible method result
    /// @return JSON-RPC response object
    private JsonObject resultResponse(JsonElement id, Object result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id.deepCopy());
        response.add("result", gson.toJsonTree(result));
        return response;
    }

    /// Creates a JSON-RPC error response.
    ///
    /// @param id request identifier, or `null` when unavailable
    /// @param code JSON-RPC error code
    /// @param message stable error description
    /// @return JSON-RPC error object
    private static JsonObject errorResponse(@Nullable JsonElement id, int code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", Objects.requireNonNull(message, "message"));
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id == null ? null : id.deepCopy());
        response.add("error", error);
        return response;
    }

    /// Serializes a JSON-RPC response as one server-sent event.
    ///
    /// @param response response to serialize
    /// @return HTTP JSON response
    private Response sseResponse(JsonObject response) {
        String event = "data: " + gson.toJson(response) + "\n\n";
        Response result = newFixedLengthResponse(Response.Status.OK, SSE_MEDIA_TYPE, event);
        result.addHeader("Cache-Control", "no-cache");
        return result;
    }

    /// Returns whether a request has the exact JSON-RPC version marker.
    ///
    /// @param request decoded request object
    /// @return whether the marker is `2.0`
    private static boolean isJsonRpcVersion(JsonObject request) {
        @Nullable JsonElement version = request.get("jsonrpc");
        return version != null && version.isJsonPrimitive()
                && version.getAsJsonPrimitive().isString() && "2.0".equals(version.getAsString());
    }

    /// Returns whether an identifier is valid for the request kind.
    ///
    /// @param id identifier element, or `null` when omitted
    /// @param notification whether the request omitted its identifier
    /// @return whether the identifier matches the JSON-RPC subset
    private static boolean isValidId(@Nullable JsonElement id, boolean notification) {
        if (notification) {
            return true;
        }
        return id != null && id.isJsonPrimitive()
                && (id.getAsJsonPrimitive().isString() || id.getAsJsonPrimitive().isNumber());
    }

    /// Returns a string member when it is present as a JSON string.
    ///
    /// @param object JSON object to inspect
    /// @param name member name
    /// @return string value, or `null` when absent or not a string
    private static @Nullable String stringMember(JsonObject object, String name) {
        @Nullable JsonElement value = object.get(Objects.requireNonNull(name, "name"));
        return value == null || value.isJsonNull() || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString() ? null : value.getAsString();
    }

    /// Returns an object member or an empty object when absent.
    ///
    /// @param object JSON object to inspect
    /// @param name member name
    /// @return supplied object value or a new empty object
    /// @throws ProtocolException when a present value is not an object
    private static JsonObject objectMember(JsonObject object, String name) {
        @Nullable JsonElement value = object.get(Objects.requireNonNull(name, "name"));
        if (value == null || value.isJsonNull()) {
            return new JsonObject();
        }
        if (!value.isJsonObject()) {
            throw new ProtocolException(-32602, name + " must be a JSON object");
        }
        return value.getAsJsonObject();
    }

    /// Converts an object member to an immutable JSON-compatible map.
    ///
    /// @param object JSON object to inspect
    /// @param name member name
    /// @return immutable decoded map
    @SuppressWarnings("unchecked")
    private Map<String, Object> mapMember(JsonObject object, String name) {
        JsonObject value = objectMember(object, name);
        @Nullable Map<String, Object> decoded = gson.fromJson(value, Map.class);
        return decoded == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(decoded));
    }

    /// Validates a configured TCP port.
    ///
    /// @param port TCP port, or zero for an automatic port
    /// @return validated port
    private static int validatePort(int port) {
        if (port < 0 || port > 0xFFFF) {
            throw new IllegalArgumentException("port must be in range 0..65535");
        }
        return port;
    }

    /// Internal JSON-RPC error with an explicit protocol code.
    @NotNullByDefault
    private static final class ProtocolException extends RuntimeException {
        /// JSON-RPC error code.
        private final int code;

        /// Creates one protocol error.
        ///
        /// @param code JSON-RPC error code
        /// @param message stable error description
        private ProtocolException(int code, String message) {
            super(Objects.requireNonNull(message, "message"));
            this.code = code;
        }

        /// Returns the JSON-RPC error code.
        ///
        /// @return error code
        private int code() {
            return code;
        }
    }
}
