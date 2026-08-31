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
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/// Serves the XYML MCP surface through a loopback Streamable HTTP JSON-RPC endpoint.
///
/// The server exposes one `/mcp` endpoint. Single responses use JSON by default; clients may
/// negotiate an SSE response when they advertise both Streamable HTTP representations. The
/// endpoint is stateful after initialization and identifies each client with an `Mcp-Session-Id`
/// header.
@NotNullByDefault
public final class XYMLMcpServer extends NanoHTTPD implements AutoCloseable {

    /// HTTP path used for every MCP request.
    public static final String MCP_PATH = "/mcp";

    /// JSON media type used for one-response exchanges.
    private static final String JSON_MEDIA_TYPE = "application/json; charset=utf-8";

    /// SSE media type used when the negotiated response requires an event stream.
    private static final String SSE_MEDIA_TYPE = "text/event-stream; charset=utf-8";

    /// Header carrying the stateful Streamable HTTP session identifier.
    private static final String SESSION_HEADER = "Mcp-Session-Id";

    /// Header carrying the negotiated MCP protocol version after initialization.
    private static final String PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version";

    /// Header used to negotiate the response representation.
    private static final String ACCEPT_HEADER = "Accept";

    /// Header required for JSON-RPC POST bodies.
    private static final String CONTENT_TYPE_HEADER = "Content-Type";

    /// Current protocol version used when a client requests an unsupported version.
    private static final String CURRENT_PROTOCOL_VERSION = "2025-11-25";

    /// JSON-RPC code used for an HTTP transport failure.
    private static final int TRANSPORT_ERROR_CODE = -32000;

    /// JSON-RPC code used when a supplied session identifier is unknown.
    private static final int SESSION_NOT_FOUND_ERROR_CODE = -32001;

    /// Streamable HTTP protocol versions whose message surface is compatible with this server.
    /// The legacy 2024-11-05 HTTP/SSE transport is intentionally not advertised.
    private static final @Unmodifiable Set<String> SUPPORTED_PROTOCOL_VERSIONS = Set.of(
            CURRENT_PROTOCOL_VERSION,
            "2025-06-18",
            "2025-03-26");

    /// Local origins permitted by the loopback listener's DNS-rebinding guard.
    private static final @Unmodifiable Set<String> LOOPBACK_ORIGINS = Set.of(
            "localhost",
            "127.0.0.1",
            "::1");

    /// Server identity advertised during MCP initialization.
    private static final @Unmodifiable Map<String, String> SERVER_INFO =
            Map.of("name", "xyml-mcp-server", "version", "1.0.0");

    /// JSON codec shared by request decoding and response serialization.
    private final Gson gson = new Gson();

    /// Registry supplying XYML tool definitions and invocations.
    private final XYMLMcpToolRegistry registry;

    /// Registry supplying launcher log and crash-report resources.
    private final XYMLMcpResourceRegistry resourceRegistry;

    /// Registry supplying launcher prompt templates.
    private final XYMLMcpPromptRegistry promptRegistry;

    /// Active Streamable HTTP sessions indexed by their opaque identifiers.
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    /// Creates a loopback MCP server without starting its listener.
    ///
    /// @param port loopback TCP port, or zero to select an available port
    /// @param service initialized launcher operation service, or `null` for schema-only tests
    public XYMLMcpServer(int port, @Nullable XYMLMcpOperations service) {
        super("127.0.0.1", validatePort(port));
        registry = new XYMLMcpToolRegistry(service);
        resourceRegistry = new XYMLMcpResourceRegistry(service);
        promptRegistry = new XYMLMcpPromptRegistry();
    }

    /// Starts the loopback HTTP listener using NanoHTTPD's daemon mode.
    ///
    /// @throws IOException when the configured port cannot be bound
    public void startListener() throws IOException {
        start(SOCKET_READ_TIMEOUT, true);
    }

    /// Handles one Streamable HTTP request.
    ///
    /// POST carries JSON-RPC messages. GET is reserved for server-initiated streams and is rejected
    /// because this launcher has no server-initiated messages to publish. The session and protocol
    /// headers are validated before a request reaches the launcher operation registries.
    ///
    /// @param session incoming HTTP request
    /// @return HTTP response for the request
    @Override
    public Response serve(IHTTPSession session) {
        Objects.requireNonNull(session, "session");
        if (!MCP_PATH.equals(session.getUri())) {
            Response response = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found");
            response.closeConnection(true);
            return response;
        }
        @Unmodifiable Map<String, String> headers = Map.copyOf(session.getHeaders());
        try {
            validateOrigin(headers);
            return switch (session.getMethod()) {
                case POST -> servePost(session, headers);
                case GET -> serveGet(headers);
                case DELETE -> serveDelete(headers);
                default -> methodNotAllowed();
            };
        } catch (TransportException exception) {
            return transportError(exception, headers);
        }
    }

    /// Handles a JSON-RPC POST after transport headers have been negotiated.
    ///
    /// @param session incoming HTTP request
    /// @param headers immutable request headers
    /// @return negotiated JSON or SSE response
    private Response servePost(IHTTPSession session, @Unmodifiable Map<String, String> headers) {
        ResponseFormat format = negotiateResponseFormat(headerValue(headers, ACCEPT_HEADER));
        if (!isJsonContentType(headerValue(headers, CONTENT_TYPE_HEADER))) {
            throw new TransportException(Response.Status.UNSUPPORTED_MEDIA_TYPE,
                    "Content-Type must be application/json");
        }

        final String body;
        try {
            body = readRequestBody(session);
        } catch (IOException | ResponseException exception) {
            return transportError(new TransportException(
                    Response.Status.BAD_REQUEST, -32700, "Unable to read JSON-RPC request"), headers);
        }

        try {
            RequestResult result = Schedulers.io().submit(() -> handleRequest(body, headers)).get();
            return render(result, format);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return render(new RequestResult(
                    errorResponse(null, -32603, "Request handling was interrupted"),
                    validSessionId(headers),
                    responseProtocolVersion(headers),
                    Response.Status.INTERNAL_ERROR), format);
        } catch (ExecutionException exception) {
            @Nullable Throwable cause = exception.getCause();
            if (cause instanceof TransportException transportException) {
                throw transportException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            @Nullable String causeMessage = cause == null ? null : cause.getMessage();
            String message = causeMessage == null ? "Request handling failed" : causeMessage;
            return render(new RequestResult(
                    errorResponse(null, -32603, message),
                    validSessionId(headers),
                    responseProtocolVersion(headers),
                    Response.Status.INTERNAL_ERROR), format);
        }
    }

    /// Handles GET negotiation for optional server-initiated streams.
    ///
    /// This server has no asynchronous server-to-client messages, so Streamable HTTP clients must
    /// use POST request/response exchanges instead of opening a stream.
    ///
    /// @param headers immutable request headers
    /// @return method-not-allowed response
    private Response serveGet(@Unmodifiable Map<String, String> headers) {
        requireSseAccept(headerValue(headers, ACCEPT_HEADER));
        @Nullable String suppliedSessionId = normalizedSessionId(headerValue(headers, SESSION_HEADER));
        SessionState state = requireSession(
                suppliedSessionId,
                normalizedHeaderValue(headerValue(headers, PROTOCOL_VERSION_HEADER)));
        String sessionId = Objects.requireNonNull(suppliedSessionId, "session id");
        Response response = newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "");
        response.addHeader("Allow", "POST");
        response.closeConnection(true);
        addSessionHeaders(response, sessionId, state.protocolVersion());
        return response;
    }

    /// Terminates one initialized Streamable HTTP session.
    ///
    /// @param headers immutable request headers
    /// @return empty successful response after the session is removed
    private Response serveDelete(@Unmodifiable Map<String, String> headers) {
        @Nullable String suppliedSessionId = normalizedSessionId(headerValue(headers, SESSION_HEADER));
        SessionState state = requireSession(
                suppliedSessionId,
                normalizedHeaderValue(headerValue(headers, PROTOCOL_VERSION_HEADER)));
        String sessionId = Objects.requireNonNull(suppliedSessionId, "session id");
        if (!sessions.remove(sessionId, state)) {
            throw new TransportException(Response.Status.NOT_FOUND,
                    SESSION_NOT_FOUND_ERROR_CODE, "Mcp-Session-Id was not found");
        }
        Response response = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "");
        response.closeConnection(true);
        return response;
    }

    /// Returns the response used for a method outside the Streamable HTTP endpoint contract.
    ///
    /// @return method-not-allowed response with the supported methods
    private static Response methodNotAllowed() {
        Response response = newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "");
        response.addHeader("Allow", "GET, POST, DELETE");
        response.closeConnection(true);
        return response;
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
        sessions.clear();
        stop();
    }

    /// Parses, validates, and dispatches one JSON-RPC request body inside a session.
    ///
    /// @param body complete UTF-8 request body
    /// @param headers immutable transport headers
    /// @return response metadata, including the session to echo
    private RequestResult handleRequest(String body, @Unmodifiable Map<String, String> headers) {
        @Nullable String suppliedSessionId = normalizedSessionId(headerValue(headers, SESSION_HEADER));
        @Nullable String suppliedProtocolVersion = normalizedHeaderValue(
                headerValue(headers, PROTOCOL_VERSION_HEADER));
        @Nullable SessionState suppliedState = suppliedSessionId == null
                ? null : requireSession(suppliedSessionId, suppliedProtocolVersion);
        final JsonElement parsed;
        try {
            parsed = JsonParser.parseString(Objects.requireNonNull(body, "body"));
        } catch (JsonParseException | IllegalStateException exception) {
            throw new TransportException(Response.Status.BAD_REQUEST, -32700,
                    "Invalid JSON-RPC message");
        }
        if (!parsed.isJsonObject()) {
            return new RequestResult(
                    errorResponse(null, -32600, "JSON-RPC request must be an object"),
                    validSessionId(suppliedSessionId),
                    responseProtocolVersion(suppliedProtocolVersion),
                    Response.Status.BAD_REQUEST);
        }

        JsonObject request = parsed.getAsJsonObject();
        boolean notification = !request.has("id");
        @Nullable JsonElement id = request.get("id");
        if (!isValidId(id, notification)) {
            return new RequestResult(
                    errorResponse(null, -32600, "JSON-RPC id must be a string or number"),
                    validSessionId(suppliedSessionId),
                    responseProtocolVersion(suppliedProtocolVersion),
                    Response.Status.BAD_REQUEST);
        }
        if (!isJsonRpcVersion(request)) {
            return requestError(
                    id,
                    notification,
                    -32600,
                    "jsonrpc must be 2.0",
                    validSessionId(suppliedSessionId),
                    responseProtocolVersion(suppliedProtocolVersion),
                    Response.Status.BAD_REQUEST);
        }

        @Nullable String method = stringMember(request, "method");
        if (method == null || method.isBlank()) {
            return requestError(
                    id,
                    notification,
                    -32600,
                    "Request method is missing",
                    validSessionId(suppliedSessionId),
                    responseProtocolVersion(suppliedProtocolVersion),
                    Response.Status.BAD_REQUEST);
        }

        if ("initialize".equals(method)) {
            return handleInitialize(request, id, notification, suppliedSessionId, suppliedProtocolVersion);
        }

        SessionState state = suppliedState == null
                ? requireSession(suppliedSessionId, suppliedProtocolVersion) : suppliedState;
        String protocolVersion = state.protocolVersion();
        JsonObject params;
        try {
            params = objectMember(request, "params");
        } catch (ProtocolException exception) {
            return requestError(
                    id,
                    notification,
                    exception.code(),
                    Objects.requireNonNull(exception.getMessage(), "protocol error message"),
                    suppliedSessionId,
                    protocolVersion,
                    Response.Status.OK);
        }

        try {
            Object result = dispatch(method, params);
            return notification
                    ? new RequestResult(null, suppliedSessionId, protocolVersion, Response.Status.ACCEPTED)
                    : new RequestResult(
                            resultResponse(Objects.requireNonNull(id, "request id"), result),
                            suppliedSessionId,
                            protocolVersion,
                            Response.Status.OK);
        } catch (ProtocolException exception) {
            return requestError(
                    id,
                    notification,
                    exception.code(),
                    Objects.requireNonNull(exception.getMessage(), "protocol error message"),
                    suppliedSessionId,
                    protocolVersion,
                    Response.Status.OK);
        } catch (Exception exception) {
            @Nullable String message = exception.getMessage();
            return requestError(
                    id,
                    notification,
                    -32603,
                    message == null ? exception.getClass().getSimpleName() : message,
                    suppliedSessionId,
                    protocolVersion,
                    Response.Status.OK);
        }
    }

    /// Completes the initial Streamable HTTP handshake and allocates a session identifier.
    ///
    /// @param request decoded initialize request
    /// @param id request identifier
    /// @param notification whether the request omitted an identifier
    /// @param suppliedSessionId session header supplied by the client, or null
    /// @param suppliedProtocolVersion protocol header supplied by the client, or null; ignored during initialization
    /// @return initialized response metadata
    private RequestResult handleInitialize(
            JsonObject request,
            @Nullable JsonElement id,
            boolean notification,
            @Nullable String suppliedSessionId,
            @Nullable String suppliedProtocolVersion) {
        if (notification || id == null) {
            throw new TransportException(Response.Status.BAD_REQUEST,
                    "initialize must be a JSON-RPC request with an id");
        }
        if (suppliedSessionId != null) {
            throw new TransportException(Response.Status.BAD_REQUEST,
                    "initialize must not include Mcp-Session-Id");
        }

        JsonObject params;
        try {
            params = objectMember(request, "params");
        } catch (ProtocolException exception) {
            return new RequestResult(
                    errorResponse(id, exception.code(),
                            Objects.requireNonNull(exception.getMessage(), "protocol error message")),
                    null,
                    CURRENT_PROTOCOL_VERSION,
                    Response.Status.BAD_REQUEST);
        }
        final @Nullable String requestedVersion;
        try {
            requestedVersion = protocolVersionMember(params);
        } catch (ProtocolException exception) {
            return new RequestResult(
                    errorResponse(id, exception.code(),
                            Objects.requireNonNull(exception.getMessage(), "protocol error message")),
                    null,
                    CURRENT_PROTOCOL_VERSION,
                    Response.Status.BAD_REQUEST);
        }
        String negotiatedVersion = negotiateProtocolVersion(requestedVersion);
        String sessionId = createSession(negotiatedVersion);
        return new RequestResult(
                resultResponse(id, initialize(params, negotiatedVersion)),
                sessionId,
                negotiatedVersion,
                Response.Status.OK);
    }

    /// Dispatches one request to the supported MCP method surface.
    ///
    /// @param method JSON-RPC method name
    /// @param params decoded parameter object
    /// @return JSON-compatible response result
    private Object dispatch(String method, JsonObject params) {
        return switch (method) {
            case "tools/list" -> Map.of("tools", registry.toolDefinitions());
            case "tools/call" -> callTool(params);
            case "resources/list" -> listResources();
            case "resources/templates/list" -> Map.of(
                    "resourceTemplates", resourceRegistry.resourceTemplateDefinitions());
            case "resources/read" -> readResource(params);
            case "prompts/list" -> Map.of("prompts", promptRegistry.promptDefinitions());
            case "prompts/get" -> getPrompt(params);
            case "notifications/initialized" -> Map.of();
            default -> throw new ProtocolException(-32601, "Unsupported method: " + method);
        };
    }

    /// Negotiates the protocol version and advertises the implemented MCP capabilities.
    ///
    /// @param params initialization parameters
    /// @return immutable initialization result
    private static @Unmodifiable Map<String, Object> initialize(JsonObject params, String protocolVersion) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", Objects.requireNonNull(protocolVersion, "protocolVersion"));
        result.put("capabilities", Map.of(
                "tools", Map.of("listChanged", false),
                "resources", Map.of("subscribe", false, "listChanged", false),
                "prompts", Map.of("listChanged", false)));
        result.put("serverInfo", SERVER_INFO);
        return Map.copyOf(result);
    }

    /// Lists concrete launcher resources.
    ///
    /// @return immutable resource list result
    private @Unmodifiable Map<String, Object> listResources() {
        try {
            return Map.of("resources", resourceRegistry.resourceDefinitions());
        } catch (Exception exception) {
            throw new ProtocolException(-32603, exceptionMessage(exception, "Unable to list resources"));
        }
    }

    /// Reads one launcher resource and formats the MCP contents envelope.
    ///
    /// @param params resource-read parameters
    /// @return immutable resource contents result
    private @Unmodifiable Map<String, Object> readResource(JsonObject params) {
        @Nullable String uri = stringMember(params, "uri");
        if (uri == null || uri.isBlank()) {
            throw new ProtocolException(-32602, "Resource URI is missing");
        }
        try {
            XYMLMcpResourceRegistry.ResourceReadResult result = resourceRegistry.readResource(uri);
            return Map.of("contents", List.of(Map.of(
                    "uri", result.uri(), "mimeType", result.mimeType(), "text", result.text())));
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException(-32602, exceptionMessage(exception, "Invalid resource URI"));
        } catch (Exception exception) {
            throw new ProtocolException(-32603, exceptionMessage(exception, "Unable to read resource"));
        }
    }

    /// Expands one launcher prompt template.
    ///
    /// @param params prompt-get parameters
    /// @return immutable prompt result
    private @Unmodifiable Map<String, Object> getPrompt(JsonObject params) {
        @Nullable String name = stringMember(params, "name");
        if (name == null || name.isBlank()) {
            throw new ProtocolException(-32602, "Prompt name is missing");
        }
        try {
            return promptRegistry.getPrompt(name, mapMember(params, "arguments"));
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException(-32602, exceptionMessage(exception, "Invalid prompt arguments"));
        }
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

    /// Returns an exception message suitable for a protocol response.
    ///
    /// @param exception operation exception
    /// @param fallback message used when the exception has no message
    /// @return stable response message
    private static String exceptionMessage(Exception exception, String fallback) {
        @Nullable String message = exception.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }

    /// Renders one request result in the representation selected by the client's Accept header.
    ///
    /// @param result protocol result and session metadata
    /// @param format negotiated response representation
    /// @return HTTP response
    private Response render(RequestResult result, ResponseFormat format) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(format, "format");
        @Nullable JsonObject body = result.response();
        Response response = body == null
                ? newFixedLengthResponse(result.status(), MIME_PLAINTEXT, "")
                : format == ResponseFormat.SSE
                ? sseResponse(result.status(), body)
                : jsonResponse(result.status(), body);
        @Nullable String sessionId = result.sessionId();
        if (sessionId != null) {
            addSessionHeaders(response, sessionId, result.protocolVersion());
        }
        return response;
    }

    /// Serializes one JSON-RPC response as an ordinary JSON document.
    ///
    /// @param status HTTP status to return
    /// @param response JSON-RPC response object
    /// @return JSON HTTP response
    private Response jsonResponse(Response.Status status, JsonObject response) {
        return newFixedLengthResponse(
                status, JSON_MEDIA_TYPE, gson.toJson(Objects.requireNonNull(response, "response")));
    }

    /// Serializes one JSON-RPC response as an SSE event when the client requires that representation.
    ///
    /// @param status HTTP status to return
    /// @param response JSON-RPC response object
    /// @return SSE HTTP response
    private Response sseResponse(Response.Status status, JsonObject response) {
        String event = "event: message\ndata: "
                + gson.toJson(Objects.requireNonNull(response, "response")) + "\n\n";
        Response result = newFixedLengthResponse(status, SSE_MEDIA_TYPE, event);
        result.addHeader("Cache-Control", "no-cache, no-transform");
        return result;
    }

    /// Converts a transport failure into an HTTP JSON error while preserving a valid session context.
    ///
    /// @param exception transport failure
    /// @param headers immutable request headers
    /// @return HTTP transport error response
    private Response transportError(
            TransportException exception,
            @Unmodifiable Map<String, String> headers) {
        Objects.requireNonNull(exception, "exception");
        JsonObject body = errorResponse(null, exception.code(),
                Objects.requireNonNull(exception.getMessage(), "transport error message"));
        Response response = jsonResponse(exception.status(), body);
        response.closeConnection(true);
        @Nullable String suppliedSessionId = normalizedSessionId(headerValue(headers, SESSION_HEADER));
        if (suppliedSessionId != null) {
            @Nullable SessionState state = sessions.get(suppliedSessionId);
            if (state != null) {
                addSessionHeaders(response, suppliedSessionId, state.protocolVersion());
            }
        }
        return response;
    }

    /// Creates a JSON-RPC error result, suppressing the body for notifications.
    ///
    /// @param id request identifier, or null for a notification
    /// @param notification whether the request omitted an identifier
    /// @param code JSON-RPC error code
    /// @param message stable error description
    /// @param sessionId validated session identifier, or null
    /// @param protocolVersion negotiated protocol version
    /// @param status HTTP status for a non-notification response
    /// @return request result metadata
    private static RequestResult requestError(
            @Nullable JsonElement id,
            boolean notification,
            int code,
            String message,
            @Nullable String sessionId,
            String protocolVersion,
            Response.Status status) {
        return notification
                ? new RequestResult(null, sessionId, protocolVersion, Response.Status.ACCEPTED)
                : new RequestResult(errorResponse(id, code, message), sessionId, protocolVersion, status);
    }

    /// Adds the headers that bind a response to its Streamable HTTP session.
    ///
    /// @param response response being sent
    /// @param sessionId opaque session identifier
    /// @param protocolVersion negotiated protocol version
    private static void addSessionHeaders(Response response, String sessionId, String protocolVersion) {
        Objects.requireNonNull(response, "response").addHeader(
                SESSION_HEADER,
                Objects.requireNonNull(sessionId, "sessionId"));
        response.addHeader(PROTOCOL_VERSION_HEADER, Objects.requireNonNull(protocolVersion, "protocolVersion"));
    }

    /// Negotiates the representation for one JSON-RPC response.
    ///
    /// A single response uses JSON whenever the client accepts it. SSE remains available when
    /// JSON is explicitly refused, and is also the representation reserved for future streamed
    /// responses. Both media types must be explicitly listed by a Streamable HTTP client.
    ///
    /// @param acceptHeader raw Accept header
    /// @return negotiated response representation
    private static ResponseFormat negotiateResponseFormat(@Nullable String acceptHeader) {
        if (!containsMediaType(acceptHeader, "application/json")
                || !containsMediaType(acceptHeader, "text/event-stream")) {
            throw new TransportException(Response.Status.NOT_ACCEPTABLE,
                    "Accept must include both application/json and text/event-stream");
        }
        double jsonQuality = mediaTypeQuality(acceptHeader, "application/json");
        double sseQuality = mediaTypeQuality(acceptHeader, "text/event-stream");
        if (jsonQuality > 0.0) {
            return ResponseFormat.JSON;
        }
        if (sseQuality > 0.0) {
            return ResponseFormat.SSE;
        }
        throw new TransportException(Response.Status.NOT_ACCEPTABLE,
                "Accept does not allow application/json or text/event-stream");
    }

    /// Returns whether an Accept header explicitly lists a media type.
    ///
    /// @param acceptHeader raw Accept header
    /// @param target media type to find
    /// @return whether the target is explicitly listed
    private static boolean containsMediaType(@Nullable String acceptHeader, String target) {
        if (acceptHeader == null || acceptHeader.isBlank()) {
            return false;
        }
        String expected = target.toLowerCase(Locale.ROOT);
        for (String item : acceptHeader.split(",")) {
            String mediaType = item.trim().split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (expected.equals(mediaType)) {
                return true;
            }
        }
        return false;
    }

    /// Requires an SSE-capable Accept header for an optional server-to-client stream.
    ///
    /// @param acceptHeader raw Accept header
    private static void requireSseAccept(@Nullable String acceptHeader) {
        if (mediaTypeQuality(acceptHeader, "text/event-stream") <= 0.0) {
            throw new TransportException(Response.Status.NOT_ACCEPTABLE,
                    "GET requires text/event-stream in Accept");
        }
    }

    /// Computes the best quality value for one explicitly listed media type.
    ///
    /// @param acceptHeader raw Accept header
    /// @param target media type to find
    /// @return quality from 0 through 1
    private static double mediaTypeQuality(@Nullable String acceptHeader, String target) {
        if (acceptHeader == null || acceptHeader.isBlank()) {
            return 0.0;
        }
        double best = 0.0;
        for (String item : acceptHeader.split(",")) {
            String[] parameters = item.trim().split(";");
            if (parameters.length == 0) {
                continue;
            }
            String mediaType = parameters[0].trim().toLowerCase(Locale.ROOT);
            if (mediaType.isEmpty()) {
                continue;
            }
            double quality = 1.0;
            boolean validQuality = true;
            for (int index = 1; index < parameters.length; index++) {
                String parameter = parameters[index].trim();
                int separator = parameter.indexOf('=');
                if (separator < 0 || !"q".equalsIgnoreCase(parameter.substring(0, separator).trim())) {
                    continue;
                }
                String rawQuality = parameter.substring(separator + 1).trim();
                if (rawQuality.length() > 1 && rawQuality.startsWith("\"") && rawQuality.endsWith("\"")) {
                    rawQuality = rawQuality.substring(1, rawQuality.length() - 1).trim();
                }
                try {
                    quality = Double.parseDouble(rawQuality);
                } catch (NumberFormatException exception) {
                    validQuality = false;
                }
                break;
            }
            if (!validQuality || !Double.isFinite(quality) || quality < 0.0 || quality > 1.0) {
                continue;
            }
            if (mediaType.equals(target)) {
                best = Math.max(best, quality);
            }
        }
        return best;
    }

    /// Checks the request body media type required by Streamable HTTP POST.
    ///
    /// @param contentType raw Content-Type header
    /// @return whether the media type is application/json
    private static boolean isJsonContentType(@Nullable String contentType) {
        if (contentType == null) {
            return false;
        }
        int separator = contentType.indexOf(';');
        String mediaType = (separator < 0 ? contentType : contentType.substring(0, separator))
                .trim()
                .toLowerCase(Locale.ROOT);
        return "application/json".equals(mediaType);
    }

    /// Validates an optional Origin header against the loopback listener.
    ///
    /// @param headers immutable request headers
    private static void validateOrigin(@Unmodifiable Map<String, String> headers) {
        @Nullable String origin = normalizedHeaderValue(headerValue(headers, "Origin"));
        if (origin == null) {
            return;
        }
        final URI parsed;
        try {
            parsed = URI.create(origin);
        } catch (IllegalArgumentException exception) {
            throw new TransportException(Response.Status.FORBIDDEN, "Invalid Origin header");
        }
        @Nullable String host = parsed.getHost();
        if (host != null && host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        @Nullable String path = parsed.getPath();
        if (!("http".equalsIgnoreCase(parsed.getScheme()) || "https".equalsIgnoreCase(parsed.getScheme()))
                || host == null
                || !LOOPBACK_ORIGINS.contains(host.toLowerCase(Locale.ROOT))
                || parsed.getUserInfo() != null
                || path == null
                || !path.isEmpty()
                || parsed.getQuery() != null
                || parsed.getFragment() != null) {
            throw new TransportException(Response.Status.FORBIDDEN, "Origin is not allowed");
        }
    }

    /// Looks up a request header without depending on NanoHTTPD's key casing.
    ///
    /// @param headers immutable request headers
    /// @param name header name
    /// @return header value, or null when absent
    private static @Nullable String headerValue(
            @Unmodifiable Map<String, String> headers,
            String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /// Normalizes a header value while treating blank values as absent.
    ///
    /// @param value raw header value
    /// @return trimmed value, or null when blank
    private static @Nullable String normalizedHeaderValue(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    /// Returns a supplied session identifier only when it belongs to this server.
    ///
    /// @param headers immutable request headers
    /// @return valid session identifier, or null
    private @Nullable String validSessionId(@Unmodifiable Map<String, String> headers) {
        return validSessionId(normalizedSessionId(headerValue(headers, SESSION_HEADER)));
    }

    /// Returns a supplied session identifier only when it belongs to this server.
    ///
    /// @param sessionId candidate session identifier
    /// @return valid session identifier, or null
    private @Nullable String validSessionId(@Nullable String sessionId) {
        return sessionId != null && sessions.containsKey(sessionId) ? sessionId : null;
    }

    /// Normalizes an opaque session identifier without interpreting its contents.
    ///
    /// @param value raw session header value
    /// @return trimmed session identifier, or null when absent
    private static @Nullable String normalizedSessionId(@Nullable String value) {
        return normalizedHeaderValue(value);
    }

    /// Selects the protocol version used in an error response before a session is available.
    ///
    /// @param headers immutable request headers
    /// @return supported response protocol version
    private static String responseProtocolVersion(@Unmodifiable Map<String, String> headers) {
        return responseProtocolVersion(normalizedHeaderValue(headerValue(headers, PROTOCOL_VERSION_HEADER)));
    }

    /// Selects the protocol version used in an error response before a session is available.
    ///
    /// @param suppliedVersion protocol version supplied by the client
    /// @return supported response protocol version
    private static String responseProtocolVersion(@Nullable String suppliedVersion) {
        return suppliedVersion != null && SUPPORTED_PROTOCOL_VERSIONS.contains(suppliedVersion)
                ? suppliedVersion : CURRENT_PROTOCOL_VERSION;
    }

    /// Validates a session and its protocol-version header for a post-initialization request.
    ///
    /// @param sessionId session identifier, or null
    /// @param protocolVersion protocol header, or null
    /// @return active session state
    private SessionState requireSession(
            @Nullable String sessionId,
            @Nullable String protocolVersion) {
        if (sessionId == null) {
            throw new TransportException(Response.Status.BAD_REQUEST,
                    "Mcp-Session-Id header is required");
        }
        @Nullable SessionState state = sessions.get(sessionId);
        if (state == null) {
            throw new TransportException(
                    Response.Status.NOT_FOUND, SESSION_NOT_FOUND_ERROR_CODE, "Mcp-Session-Id was not found");
        }
        if (protocolVersion == null) {
            throw new TransportException(Response.Status.BAD_REQUEST,
                    "MCP-Protocol-Version header is required");
        }
        if (!state.protocolVersion().equals(protocolVersion)) {
            throw new TransportException(Response.Status.BAD_REQUEST,
                    "MCP-Protocol-Version does not match the session");
        }
        return state;
    }

    /// Allocates a collision-resistant opaque session identifier.
    ///
    /// @param protocolVersion negotiated protocol version
    /// @return newly allocated session identifier
    private String createSession(String protocolVersion) {
        String validatedVersion = Objects.requireNonNull(protocolVersion, "protocolVersion");
        String sessionId;
        do {
            sessionId = UUID.randomUUID().toString();
        } while (sessions.putIfAbsent(sessionId, new SessionState(validatedVersion)) != null);
        return sessionId;
    }

    /// Negotiates a supported protocol version rather than echoing arbitrary client input.
    ///
    /// @param requestedVersion client protocol version, or null when omitted
    /// @return selected server-supported version
    private static String negotiateProtocolVersion(@Nullable String requestedVersion) {
        return requestedVersion != null && SUPPORTED_PROTOCOL_VERSIONS.contains(requestedVersion)
                ? requestedVersion : CURRENT_PROTOCOL_VERSION;
    }

    /// Reads and validates the protocolVersion initialize parameter.
    ///
    /// @param params initialize parameters
    /// @return requested protocol version, or null when omitted
    private static @Nullable String protocolVersionMember(JsonObject params) {
        @Nullable JsonElement value = params.get("protocolVersion");
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new ProtocolException(-32602, "protocolVersion must be a string");
        }
        String version = value.getAsString().trim();
        if (version.isEmpty()) {
            throw new ProtocolException(-32602, "protocolVersion must not be blank");
        }
        return version;
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

    /// Response representations supported by the Streamable HTTP negotiation.
    @NotNullByDefault
    private enum ResponseFormat {
        /// One JSON-RPC response in an application/json document.
        JSON,

        /// One JSON-RPC response in an SSE event stream.
        SSE
    }

    /// State associated with one initialized Streamable HTTP client session.
    @NotNullByDefault
    private record SessionState(String protocolVersion) {
        /// Validates the negotiated protocol version stored for this session.
        private SessionState {
            Objects.requireNonNull(protocolVersion, "protocolVersion");
        }
    }

    /// Carries a JSON-RPC response together with transport metadata.
    @NotNullByDefault
    private record RequestResult(
            @Nullable JsonObject response,
            @Nullable String sessionId,
            String protocolVersion,
            Response.Status status) {
        /// Validates response metadata produced by request dispatch.
        private RequestResult {
            Objects.requireNonNull(protocolVersion, "protocolVersion");
            Objects.requireNonNull(status, "status");
        }
    }

    /// Describes an HTTP-level transport failure that must not reach JSON-RPC dispatch.
    @NotNullByDefault
    private static final class TransportException extends RuntimeException {
        /// JSON-RPC code associated with the transport failure.
        private final int code;

        /// HTTP status associated with the transport failure.
        private final Response.Status status;

        /// Creates one transport failure.
        ///
        /// @param status HTTP status to return
        /// @param message stable transport error description
        private TransportException(Response.Status status, String message) {
            this(status, TRANSPORT_ERROR_CODE, message);
        }

        /// Creates one transport failure with an explicit JSON-RPC code.
        ///
        /// @param status HTTP status to return
        /// @param code JSON-RPC error code
        /// @param message stable transport error description
        private TransportException(Response.Status status, int code, String message) {
            super(Objects.requireNonNull(message, "message"));
            this.code = code;
            this.status = Objects.requireNonNull(status, "status");
        }

        /// Returns the JSON-RPC code associated with this failure.
        ///
        /// @return JSON-RPC error code
        private int code() {
            return code;
        }

        /// Returns the HTTP status associated with this failure.
        ///
        /// @return HTTP status
        private Response.Status status() {
            return status;
        }
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
