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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import fi.iki.elonen.NanoHTTPD;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/// Implements the NanoHTTPD transport behind the public MCP server facade.
///
/// The server exposes one `/mcp` endpoint. Single responses use JSON by default; clients may
/// negotiate an SSE response when they advertise both Streamable HTTP representations. The
/// endpoint is stateful after initialization and identifies each client with an `Mcp-Session-Id`
/// header.
@NotNullByDefault
final class McpHttpTransport extends NanoHTTPD implements AutoCloseable {

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

    /// Header carrying the optional HTTP Bearer credential.
    private static final String AUTHORIZATION_HEADER = "Authorization";

    /// Challenge sent when a request lacks a valid Bearer credential.
    private static final String BEARER_CHALLENGE = "Bearer";

    /// Current protocol version used when a client requests an unsupported version.
    private static final String CURRENT_PROTOCOL_VERSION = "2025-11-25";

    /// Protocol version assumed when a backwards-compatible client omits the HTTP version header.
    private static final String MISSING_HEADER_PROTOCOL_VERSION = "2025-03-26";

    /// JSON-RPC code used for an HTTP transport failure.
    private static final int TRANSPORT_ERROR_CODE = -32000;

    /// JSON-RPC code used when a supplied session identifier is unknown.
    private static final int SESSION_NOT_FOUND_ERROR_CODE = -32001;

    /// Default period after which an inactive session is discarded.
    private static final Duration DEFAULT_SESSION_TTL = Duration.ofHours(1);

    /// Default maximum number of simultaneously retained sessions.
    private static final int DEFAULT_MAX_SESSIONS = 256;

    /// Maximum raw request-header bytes retained by the duplicate-header guard.
    private static final int RAW_HEADER_LIMIT = 16 * 1024;

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

    /// JSON codec shared by request decoding and response serialization.
    private final Gson gson = new GsonBuilder().serializeNulls().create();

    /// Server identity advertised during initialization.
    private final McpServerInfo serverInfo;

    /// Provider supplying tools, or null when that capability is disabled.
    private final @Nullable McpToolProvider toolProvider;

    /// Provider supplying resources, or null when that capability is disabled.
    private final @Nullable McpResourceProvider resourceProvider;

    /// Provider supplying prompts, or null when that capability is disabled.
    private final @Nullable McpPromptProvider promptProvider;

    /// Active Streamable HTTP sessions indexed by their opaque identifiers.
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    /// Serializes session creation, expiration, eviction, and deletion.
    private final Object sessionLock = new Object();

    /// Inactivity period after which a session is expired.
    private final long sessionTtlMillis;

    /// Maximum number of sessions retained by this server.
    private final int maxSessions;

    /// Time source used for session expiration and activity renewal.
    private final LongSupplier currentTimeMillis;

    /// SHA-256 digest of the configured bearer token, or null when transport authentication is disabled.
    private final byte @Nullable [] bearerTokenDigest;

    /// Private token copy used only to redact accidental echoes in provider results and error text.
    private final @Nullable String bearerTokenForRedaction;

    /// Raw request-header state bound to the NanoHTTPD client thread.
    private final ThreadLocal<RawRequestContext> rawRequestContext = new ThreadLocal<>();

    /// Creates a loopback MCP server without starting its listener.
    /// @param port loopback TCP port, or zero to select an available port
    /// @param serverInfo identity advertised during initialization
    /// @param features optional MCP feature providers
    McpHttpTransport(int port, McpServerInfo serverInfo, McpFeatureSet features) {
        this(port, serverInfo, features, "", DEFAULT_SESSION_TTL, DEFAULT_MAX_SESSIONS, System::currentTimeMillis);
    }

    /// Creates a loopback MCP server with an optional bearer token.
    /// An empty token disables transport authentication. A non-empty token is checked before any method-specific
    /// negotiation or session lookup.
    /// @param port loopback TCP port, or zero to select an available port
    /// @param serverInfo identity advertised during initialization
    /// @param features optional MCP feature providers
    /// @param bearerToken bearer token, or an empty string to disable authentication
    McpHttpTransport(int port, McpServerInfo serverInfo, McpFeatureSet features, String bearerToken) {
        this(
                port,
                serverInfo,
                features,
                bearerToken,
                DEFAULT_SESSION_TTL,
                DEFAULT_MAX_SESSIONS,
                System::currentTimeMillis);
    }

    /// Creates a loopback MCP server with explicit session lifecycle settings.
    ///
    /// This constructor is package-private so protocol tests can use a deterministic time source.
    ///
    /// @param port loopback TCP port, or zero to select an available port
    /// @param serverInfo identity advertised during initialization
    /// @param features optional MCP feature providers
    /// @param sessionTtl inactivity period before a session expires
    /// @param maxSessions maximum number of retained sessions
    /// @param currentTimeMillis time source returning epoch milliseconds
    McpHttpTransport(
            int port,
            McpServerInfo serverInfo,
            McpFeatureSet features,
            Duration sessionTtl,
            int maxSessions,
            LongSupplier currentTimeMillis) {
        this(port, serverInfo, features, "", sessionTtl, maxSessions, currentTimeMillis);
    }

    /// Creates a loopback MCP server with explicit session and authentication settings.
    ///
    /// @param port loopback TCP port, or zero to select an available port
    /// @param serverInfo identity advertised during initialization
    /// @param features optional MCP feature providers
    /// @param bearerToken bearer token, or an empty string to disable authentication
    /// @param sessionTtl inactivity period before a session expires
    /// @param maxSessions maximum number of retained sessions
    /// @param currentTimeMillis time source returning epoch milliseconds
    McpHttpTransport(
            int port,
            McpServerInfo serverInfo,
            McpFeatureSet features,
            String bearerToken,
            Duration sessionTtl,
            int maxSessions,
            LongSupplier currentTimeMillis) {
        super("127.0.0.1", validatePort(port));
        this.serverInfo = Objects.requireNonNull(serverInfo, "serverInfo");
        McpFeatureSet configuredFeatures = Objects.requireNonNull(features, "features");
        toolProvider = configuredFeatures.tools();
        resourceProvider = configuredFeatures.resources();
        promptProvider = configuredFeatures.prompts();
        sessionTtlMillis = validateSessionTtl(sessionTtl);
        this.maxSessions = validateMaxSessions(maxSessions);
        this.currentTimeMillis = Objects.requireNonNull(currentTimeMillis, "currentTimeMillis");
        String checkedBearerToken = Objects.requireNonNull(bearerToken, "bearerToken");
        bearerTokenDigest = digestBearerToken(checkedBearerToken);
        bearerTokenForRedaction = checkedBearerToken.isEmpty() ? null : checkedBearerToken;
    }

    /// Wraps each NanoHTTPD client stream so duplicate or ambiguously padded Authorization headers remain visible
    /// to the transport guard before NanoHTTPD normalizes them into a map.
    @Override
    protected ClientHandler createClientHandler(Socket socket, InputStream inputStream) {
        RawRequestContext context = new RawRequestContext();
        return new RawCheckingClientHandler(socket, inputStream, context);
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
    /// because this server has no server-initiated messages to publish. The session and protocol
    /// headers are validated before a request reaches the configured capability providers.
    ///
    /// @param session incoming HTTP request
    /// @return HTTP response for the request
    @Override
    public Response serve(IHTTPSession session) {
        Objects.requireNonNull(session, "session");
        @Nullable RawRequestContext rawContext = rawRequestContext.get();
        try {
            if (!MCP_PATH.equals(session.getUri())) {
                Response response = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found");
                response.closeConnection(true);
                return response;
            }
            final @Unmodifiable Map<String, String> headers;
            try {
                headers = Map.copyOf(session.getHeaders());
            } catch (RuntimeException malformedHeaders) {
                Response response = transportError(
                        new TransportException(
                                Response.Status.BAD_REQUEST,
                                TRANSPORT_ERROR_CODE,
                                "Malformed request headers",
                                false),
                        Map.of());
                response.closeConnection(true);
                return response;
            }
            try {
                if (rawContext != null && rawContext.originMalformed()) {
                    throw forbiddenOrigin("Duplicate or malformed Origin header");
                }
                validateOrigin(headers);
                authenticate(headers, rawContext);
                Response response = switch (session.getMethod()) {
                    case POST -> servePost(session, headers);
                    case GET -> serveGet(headers);
                    case DELETE -> serveDelete(headers);
                    default -> methodNotAllowed();
                };
                // NanoHTTPD may prefetch bytes from a pipelined connection. Closing every
                // response keeps the raw-header duplicate check scoped to exactly one request.
                response.closeConnection(true);
                return response;
            } catch (TransportException exception) {
                Response response = transportError(exception, headers);
                response.closeConnection(true);
                return response;
            }
        } finally {
            if (rawContext != null) {
                rawContext.resetForNextRequest();
            }
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

        return render(handleRequest(body, headers), format);
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
        response.addHeader("Allow", "POST, DELETE");
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
        synchronized (sessionLock) {
            SessionState state = requireSession(
                    suppliedSessionId,
                    normalizedHeaderValue(headerValue(headers, PROTOCOL_VERSION_HEADER)));
            String sessionId = Objects.requireNonNull(suppliedSessionId, "session id");
            if (!sessions.remove(sessionId, state)) {
                throw new TransportException(Response.Status.NOT_FOUND,
                        SESSION_NOT_FOUND_ERROR_CODE, "Mcp-Session-Id was not found");
            }
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
        response.addHeader("Allow", "POST, DELETE");
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
        synchronized (sessionLock) {
            sessions.clear();
        }
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
        String requestedVersion;
        try {
            params = requiredObjectMember(request, "params");
            requestedVersion = requiredProtocolVersionMember(params);
            requiredObjectMember(params, "capabilities");
            JsonObject clientInfo = requiredObjectMember(params, "clientInfo");
            requiredNonBlankStringMember(clientInfo, "name");
            requiredNonBlankStringMember(clientInfo, "version");
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
                resultResponse(id, initialize(negotiatedVersion)),
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
            case "tools/list" -> Map.of("tools", requireToolProvider(method).toolDefinitions());
            case "tools/call" -> callTool(params);
            case "resources/list" -> listResources();
            case "resources/templates/list" -> Map.of(
                    "resourceTemplates", requireResourceProvider(method).resourceTemplateDefinitions());
            case "resources/read" -> readResource(params);
            case "prompts/list" -> Map.of("prompts", requirePromptProvider(method).promptDefinitions());
            case "prompts/get" -> getPrompt(params);
            case "notifications/initialized" -> Map.of();
            default -> throw new ProtocolException(-32601, "Unsupported method: " + method);
        };
    }

    /// Negotiates the protocol version and advertises the implemented MCP capabilities.
    ///
    /// @param protocolVersion negotiated protocol version
    /// @return immutable initialization result
    private @Unmodifiable Map<String, Object> initialize(String protocolVersion) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", Objects.requireNonNull(protocolVersion, "protocolVersion"));
        Map<String, Object> capabilities = new LinkedHashMap<>();
        if (toolProvider != null) {
            capabilities.put("tools", Map.of("listChanged", false));
        }
        if (resourceProvider != null) {
            capabilities.put("resources", Map.of("subscribe", false, "listChanged", false));
        }
        if (promptProvider != null) {
            capabilities.put("prompts", Map.of("listChanged", false));
        }
        result.put("capabilities", Map.copyOf(capabilities));
        result.put("serverInfo", serverInfo.toMap());
        return Map.copyOf(result);
    }

    /// Lists concrete resources supplied by the configured provider.
    ///
    /// @return immutable resource list result
    private @Unmodifiable Map<String, Object> listResources() {
        McpResourceProvider provider = requireResourceProvider("resources/list");
        try {
            return Map.of("resources", provider.resourceDefinitions());
        } catch (Exception exception) {
            throw new ProtocolException(-32603, exceptionMessage(exception, "Unable to list resources"));
        }
    }

    /// Reads one provider resource and formats the MCP contents envelope.
    ///
    /// @param params resource-read parameters
    /// @return immutable resource contents result
    private @Unmodifiable Map<String, Object> readResource(JsonObject params) {
        @Nullable String uri = stringMember(params, "uri");
        if (uri == null || uri.isBlank()) {
            throw new ProtocolException(-32602, "Resource URI is missing");
        }
        McpResourceProvider provider = requireResourceProvider("resources/read");
        try {
            McpResourceProvider.ResourceReadResult result = provider.readResource(uri);
            return Map.of("contents", List.of(Map.of(
                    "uri", result.uri(), "mimeType", result.mimeType(), "text", result.text())));
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException(-32602, exceptionMessage(exception, "Invalid resource URI"));
        } catch (Exception exception) {
            throw new ProtocolException(-32603, exceptionMessage(exception, "Unable to read resource"));
        }
    }

    /// Expands one prompt template supplied by the configured provider.
    ///
    /// @param params prompt-get parameters
    /// @return immutable prompt result
    private @Unmodifiable Map<String, @Nullable Object> getPrompt(JsonObject params) {
        @Nullable String name = stringMember(params, "name");
        if (name == null || name.isBlank()) {
            throw new ProtocolException(-32602, "Prompt name is missing");
        }
        try {
            return McpJsonValues.snapshotMap(
                    requirePromptProvider("prompts/get").getPrompt(name, mapMember(params, "arguments")));
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
        @Unmodifiable Map<String, @Nullable Object> arguments = mapMember(params, "arguments");
        McpToolProvider.ToolCallResult result = requireToolProvider("tools/call").call(name, arguments);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("content", List.of(Map.of("type", "text", "text",
                gson.toJson(result.structuredContent()))));
        response.put("structuredContent", result.structuredContent());
        response.put("isError", result.error());
        return Map.copyOf(response);
    }

    /// Returns the configured tool provider or reports an unsupported method.
    ///
    /// @param method requested method
    /// @return configured tool provider
    /// @throws ProtocolException when the tools capability is disabled
    private McpToolProvider requireToolProvider(String method) {
        @Nullable McpToolProvider provider = toolProvider;
        if (provider == null) {
            throw new ProtocolException(-32601, "Unsupported method: " + method);
        }
        return provider;
    }

    /// Returns the configured resource provider or reports an unsupported method.
    ///
    /// @param method requested method
    /// @return configured resource provider
    /// @throws ProtocolException when the resources capability is disabled
    private McpResourceProvider requireResourceProvider(String method) {
        @Nullable McpResourceProvider provider = resourceProvider;
        if (provider == null) {
            throw new ProtocolException(-32601, "Unsupported method: " + method);
        }
        return provider;
    }

    /// Returns the configured prompt provider or reports an unsupported method.
    ///
    /// @param method requested method
    /// @return configured prompt provider
    /// @throws ProtocolException when the prompts capability is disabled
    private McpPromptProvider requirePromptProvider(String method) {
        @Nullable McpPromptProvider provider = promptProvider;
        if (provider == null) {
            throw new ProtocolException(-32601, "Unsupported method: " + method);
        }
        return provider;
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
                status,
                JSON_MEDIA_TYPE,
                gson.toJson(JsonCredentialRedactor.redact(
                        Objects.requireNonNull(response, "response"), bearerTokenForRedaction)));
    }

    /// Serializes one JSON-RPC response as an SSE event when the client requires that representation.
    ///
    /// @param status HTTP status to return
    /// @param response JSON-RPC response object
    /// @return SSE HTTP response
    private Response sseResponse(Response.Status status, JsonObject response) {
        String event = "event: message\ndata: "
                + gson.toJson(JsonCredentialRedactor.redact(
                        Objects.requireNonNull(response, "response"), bearerTokenForRedaction)) + "\n\n";
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
        if (exception.exposeSessionContext()) {
            @Nullable String suppliedSessionId = normalizedSessionId(headerValue(headers, SESSION_HEADER));
            if (suppliedSessionId != null) {
                @Nullable SessionState state = sessions.get(suppliedSessionId);
                if (state != null) {
                    addSessionHeaders(response, suppliedSessionId, state.protocolVersion());
                }
            }
        }
        if (exception.status() == Response.Status.UNAUTHORIZED) {
            response.addHeader("WWW-Authenticate", BEARER_CHALLENGE);
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

    /// Authenticates a request before any protocol or provider operation is evaluated.
    ///
    /// The configured digest is absent when authentication is intentionally disabled by an empty token. When a token
    /// is configured, the Authorization value is parsed strictly and duplicate names are rejected, so callers cannot
    /// rely on ambiguous proxy merging or malformed credentials.
    ///
    /// @param headers immutable request headers
    private void authenticate(
            @Unmodifiable Map<String, String> headers,
            @Nullable RawRequestContext rawContext) {
        @Nullable byte[] expectedDigest = bearerTokenDigest;
        if (rawContext != null && (rawContext.authorizationMalformed() || rawContext.headersMalformed())) {
            throw unauthorized();
        }
        @Nullable String rawAuthorization = singleHeaderValue(headers, AUTHORIZATION_HEADER);
        if (rawContext != null && rawContext.headerParsed() && rawContext.rawAuthorization() != null) {
            rawAuthorization = rawContext.rawAuthorization();
        }
        if (expectedDigest == null) {
            // An empty configured token disables credential comparison, but an explicitly supplied Authorization
            // field still has to obey the transport grammar so malformed proxy input cannot pass silently.
            if (rawAuthorization != null) {
                parseBearerCredential(rawAuthorization);
            }
            return;
        }
        @Nullable String suppliedToken = rawAuthorization == null ? null : parseBearerCredential(rawAuthorization);
        byte[] suppliedDigest = sha256(suppliedToken == null ? "" : suppliedToken);
        try {
            if (!MessageDigest.isEqual(expectedDigest, suppliedDigest)) {
                throw unauthorized();
            }
        } finally {
            Arrays.fill(suppliedDigest, (byte) 0);
        }
    }

    /// Parses one Authorization header without exposing credential contents in errors.
    ///
    /// The parser accepts exactly one ASCII space between the case-insensitive scheme and credential, but does not
    /// trim the field value. Consequently, leading/trailing spaces, tabs, and control characters are rejected instead
    /// of being silently normalized into a different credential.
    ///
    /// @param rawAuthorization raw Authorization value
    /// @return supplied bearer token
    private static String parseBearerCredential(String rawAuthorization) {
        String raw = Objects.requireNonNull(rawAuthorization, "rawAuthorization");
        int separator = raw.indexOf(' ');
        if (separator <= 0
                || !BEARER_CHALLENGE.equalsIgnoreCase(raw.substring(0, separator))) {
            throw unauthorized();
        }
        if (separator + 1 >= raw.length() || raw.indexOf(' ', separator + 1) >= 0) {
            throw unauthorized();
        }
        String token = raw.substring(separator + 1);
        if (!isValidBearerToken(token)) {
            throw unauthorized();
        }
        return token;
    }

    /// Returns one case-insensitive header value and rejects duplicate names.
    ///
    /// @param headers immutable request headers
    /// @param name header name
    /// @return header value, or null when absent
    private static @Nullable String singleHeaderValue(
            @Unmodifiable Map<String, String> headers,
            String name) {
        @Nullable String value = null;
        int matches = 0;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                matches++;
                value = entry.getValue();
            }
        }
        if (matches > 1) {
            throw unauthorized();
        }
        if (matches == 1 && AUTHORIZATION_HEADER.equalsIgnoreCase(name)
                && value != null && value.indexOf(',') >= 0) {
            throw unauthorized();
        }
        return value;
    }

    /// Creates a generic unauthorized transport failure.
    ///
    /// @return unauthorized exception without session or credential context
    private static TransportException unauthorized() {
        return new TransportException(Response.Status.UNAUTHORIZED, TRANSPORT_ERROR_CODE,
                "Unauthorized", false);
    }

    /// Hashes one credential with a fixed algorithm for constant-time digest comparison.
    ///
    /// @param token token text
    /// @return SHA-256 digest
    private static byte[] sha256(String token) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(Objects.requireNonNull(token, "token").getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /// Validates and digests the configured bearer token.
    ///
    /// @param token configured token, or an empty value to disable authentication
    /// @return token digest, or null when authentication is disabled
    private static byte @Nullable [] digestBearerToken(String token) {
        String checkedToken = Objects.requireNonNull(token, "token");
        if (checkedToken.isEmpty()) {
            return null;
        }
        // Configuration is persisted verbatim. Wire-level syntax is validated only for the
        // incoming Authorization field; an unsupported configured value simply cannot match it.
        return sha256(checkedToken);
    }

    /// Checks the opaque token subset that can be carried unambiguously in an Authorization header.
    ///
    /// @param token token text
    /// @return whether the token contains only printable non-whitespace, non-comma ASCII characters
    private static boolean isValidBearerToken(String token) {
        for (int index = 0; index < token.length(); index++) {
            char character = token.charAt(index);
            if (character < 0x21 || character > 0x7E || character == ',') {
                return false;
            }
        }
        return !token.isEmpty();
    }

    /// Validates an optional Origin header against the loopback listener.
    ///
    /// @param headers immutable request headers
    private static void validateOrigin(@Unmodifiable Map<String, String> headers) {
        @Nullable String origin = normalizedHeaderValue(singleNonAuthorizationHeaderValue(headers, "Origin"));
        if (origin == null) {
            return;
        }
        final URI parsed;
        try {
            parsed = URI.create(origin);
        } catch (IllegalArgumentException exception) {
            throw forbiddenOrigin("Invalid Origin header");
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
            throw forbiddenOrigin("Origin is not allowed");
        }
    }

    /// Creates an Origin rejection that never echoes caller-supplied session metadata.
    ///
    /// @param message stable Origin rejection message
    /// @return non-contextual forbidden transport failure
    private static TransportException forbiddenOrigin(String message) {
        return new TransportException(Response.Status.FORBIDDEN, TRANSPORT_ERROR_CODE,
                Objects.requireNonNull(message, "message"), false);
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

    /// Returns one case-insensitive non-Authorization header value and rejects ambiguous duplicates.
    ///
    /// @param headers immutable request headers
    /// @param name header name
    /// @return header value, or null when absent
    private static @Nullable String singleNonAuthorizationHeaderValue(
            @Unmodifiable Map<String, String> headers,
            String name) {
        @Nullable String value = null;
        int matches = 0;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                matches++;
                value = entry.getValue();
            }
        }
        if (matches > 1) {
            throw forbiddenOrigin("Duplicate Origin header");
        }
        return value;
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
        synchronized (sessionLock) {
            @Nullable SessionState state = sessions.get(sessionId);
            if (state == null || state.isExpired(currentTimeMillis.getAsLong(), sessionTtlMillis)) {
                if (state != null) {
                    sessions.remove(sessionId, state);
                }
                throw new TransportException(
                        Response.Status.NOT_FOUND, SESSION_NOT_FOUND_ERROR_CODE, "Mcp-Session-Id was not found");
            }
            String effectiveProtocolVersion = protocolVersion == null
                    ? MISSING_HEADER_PROTOCOL_VERSION : protocolVersion;
            if (!state.protocolVersion().equals(effectiveProtocolVersion)) {
                throw new TransportException(Response.Status.BAD_REQUEST,
                        "MCP-Protocol-Version does not match the session");
            }
            state.touch(currentTimeMillis.getAsLong());
            return state;
        }
    }

    /// Allocates a collision-resistant opaque session identifier.
    ///
    /// @param protocolVersion negotiated protocol version
    /// @return newly allocated session identifier
    private String createSession(String protocolVersion) {
        String validatedVersion = Objects.requireNonNull(protocolVersion, "protocolVersion");
        synchronized (sessionLock) {
            long now = currentTimeMillis.getAsLong();
            cleanupExpiredSessions(now);
            while (sessions.size() >= maxSessions) {
                evictOldestSession();
            }
            String sessionId;
            do {
                sessionId = UUID.randomUUID().toString();
            } while (sessions.putIfAbsent(sessionId, new SessionState(validatedVersion, now)) != null);
            return sessionId;
        }
    }

    /// Removes sessions that have exceeded the inactivity period.
    ///
    /// @param now current epoch milliseconds
    private void cleanupExpiredSessions(long now) {
        for (Map.Entry<String, SessionState> entry : sessions.entrySet()) {
            SessionState state = entry.getValue();
            if (state.isExpired(now, sessionTtlMillis)) {
                sessions.remove(entry.getKey(), state);
            }
        }
    }

    /// Evicts the least recently active session to keep the configured capacity bound.
    private void evictOldestSession() {
        @Nullable Map.Entry<String, SessionState> oldest = null;
        for (Map.Entry<String, SessionState> entry : sessions.entrySet()) {
            if (oldest == null
                    || entry.getValue().lastAccessMillis() < oldest.getValue().lastAccessMillis()) {
                oldest = entry;
            }
        }
        if (oldest != null) {
            sessions.remove(oldest.getKey(), oldest.getValue());
        }
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

    /// Reads the required non-empty protocol version from initialization parameters.
    ///
    /// @param params initialization parameters
    /// @return requested protocol version
    /// @throws ProtocolException when the member is absent, blank, or not a string
    private static String requiredProtocolVersionMember(JsonObject params) {
        @Nullable String version = protocolVersionMember(params);
        if (version == null) {
            throw new ProtocolException(-32602, "protocolVersion must be a non-empty string");
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

    /// Reads a required JSON object member.
    ///
    /// @param object object to inspect
    /// @param name required member name
    /// @return required object value
    /// @throws ProtocolException when the member is absent or not an object
    private static JsonObject requiredObjectMember(JsonObject object, String name) {
        @Nullable JsonElement value = object.get(Objects.requireNonNull(name, "name"));
        if (value == null || value.isJsonNull() || !value.isJsonObject()) {
            throw new ProtocolException(-32602, name + " must be a JSON object");
        }
        return value.getAsJsonObject();
    }

    /// Validates a required non-empty string member.
    ///
    /// @param object object to inspect
    /// @param name required member name
    /// @throws ProtocolException when the member is absent, blank, or not a string
    private static void requiredNonBlankStringMember(JsonObject object, String name) {
        @Nullable String value = stringMember(object, name);
        if (value == null || value.isBlank()) {
            throw new ProtocolException(-32602, name + " must be a non-empty string");
        }
    }

    /// Converts an object member to an immutable JSON-compatible map.
    ///
    /// @param object JSON object to inspect
    /// @param name member name
    /// @return immutable decoded map
    @SuppressWarnings("unchecked")
    private @Unmodifiable Map<String, @Nullable Object> mapMember(JsonObject object, String name) {
        JsonObject value = objectMember(object, name);
        @Nullable Map<String, @Nullable Object> decoded = gson.fromJson(value, Map.class);
        return decoded == null ? Map.of() : McpJsonValues.snapshotMap(decoded);
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

    /// Validates a positive session inactivity period and returns milliseconds.
    ///
    /// @param sessionTtl inactivity period
    /// @return inactivity period in milliseconds
    private static long validateSessionTtl(Duration sessionTtl) {
        long millis = Objects.requireNonNull(sessionTtl, "sessionTtl").toMillis();
        if (millis <= 0) {
            throw new IllegalArgumentException("sessionTtl must be positive");
        }
        return millis;
    }

    /// Validates the maximum number of retained sessions.
    ///
    /// @param maxSessions session capacity
    /// @return validated session capacity
    private static int validateMaxSessions(int maxSessions) {
        if (maxSessions <= 0) {
            throw new IllegalArgumentException("maxSessions must be positive");
        }
        return maxSessions;
    }

    /// Client handler that keeps raw header occurrences visible to the transport authentication boundary.
    @NotNullByDefault
    private final class RawCheckingClientHandler extends ClientHandler {
        /// Header state shared with the stream wrapper and the request dispatcher.
        private final RawRequestContext context;

        /// Creates a handler around one accepted socket and its raw input stream.
        ///
        /// @param socket accepted client socket
        /// @param inputStream raw socket input
        /// @param context per-connection header state
        private RawCheckingClientHandler(Socket socket, InputStream inputStream, RawRequestContext context) {
            super(new RawHeaderInputStream(inputStream, context), socket);
            this.context = Objects.requireNonNull(context, "context");
        }

        /// Binds the raw-header state to the NanoHTTPD request thread for the whole keep-alive connection.
        @Override
        public void run() {
            rawRequestContext.set(context);
            try {
                super.run();
            } finally {
                rawRequestContext.remove();
            }
        }
    }

    /// Input wrapper that parses only the first header block of each keep-alive request.
    @NotNullByDefault
    private static final class RawHeaderInputStream extends InputStream {
        /// Underlying NanoHTTPD socket stream.
        private final InputStream delegate;

        /// Per-connection raw-header state.
        private final RawRequestContext context;

        /// Header bytes retained until the first empty line.
        private final ByteArrayOutputStream header = new ByteArrayOutputStream();

        /// Context generation represented by the local parser state.
        private long observedGeneration = -1L;

        /// Whether this request's header block has already been parsed.
        private boolean complete;

        /// Last four bytes used to detect a header terminator without copying the whole buffer.
        private final int[] tail = new int[4];

        /// Number of valid bytes currently held in [#tail].
        private int tailLength;

        /// Creates a raw-header stream wrapper.
        ///
        /// @param delegate underlying socket stream
        /// @param context per-connection parser state
        private RawHeaderInputStream(InputStream delegate, RawRequestContext context) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.context = Objects.requireNonNull(context, "context");
        }

        /// Reads and observes one byte.
        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                observe(value);
            }
            return value;
        }

        /// Reads and observes a byte range without retaining request bodies.
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return 0;
            }
            resetForNewGeneration();
            if (complete) {
                return delegate.read(bytes, offset, length);
            }

            // Stop the underlying read at the first header terminator. Otherwise BufferedInputStream could consume
            // the body or the next keep-alive request before this guard gets a chance to inspect its headers.
            int first = read();
            if (first < 0) {
                return -1;
            }
            bytes[offset] = (byte) first;
            int count = 1;
            while (count < length && !complete) {
                int value = delegate.read();
                if (value < 0) {
                    break;
                }
                bytes[offset + count] = (byte) value;
                observe(value);
                count++;
            }
            return count;
        }

        /// Closes the wrapped socket stream.
        @Override
        public void close() throws IOException {
            delegate.close();
        }

        /// Observes one header byte and parses the block once its terminator arrives.
        private void observe(int value) {
            resetForNewGeneration();
            if (complete) {
                return;
            }
            if (header.size() >= RAW_HEADER_LIMIT) {
                context.markMalformed();
                complete = true;
                return;
            }
            header.write(value);
            if (tailLength < tail.length) {
                tail[tailLength++] = value;
            } else {
                tail[0] = tail[1];
                tail[1] = tail[2];
                tail[2] = tail[3];
                tail[3] = value;
            }
            if ((tailLength >= 4
                    && tail[tailLength - 4] == '\r'
                    && tail[tailLength - 3] == '\n'
                    && tail[tailLength - 2] == '\r'
                    && tail[tailLength - 1] == '\n')
                    || (tailLength >= 2
                    && tail[tailLength - 2] == '\n'
                    && tail[tailLength - 1] == '\n')) {
                complete = true;
                context.parse(header.toByteArray());
            }
        }

        /// Resets local parsing state when the dispatcher starts the next keep-alive request.
        private void resetForNewGeneration() {
            long generation = context.generation();
            if (observedGeneration == generation) {
                return;
            }
            observedGeneration = generation;
            header.reset();
            complete = false;
            tailLength = 0;
        }
    }

    /// Per-connection raw Authorization result consumed by the request dispatcher.
    @NotNullByDefault
    private static final class RawRequestContext {
        /// Whether the current header block has been parsed.
        private boolean headerParsed;

        /// Whether the current block has duplicate or malformed Authorization syntax.
        private boolean authorizationMalformed;

        /// Whether the current block contains duplicate or ambiguously padded Origin syntax.
        private boolean originMalformed;

        /// Whether the current block contains any malformed field line that NanoHTTPD could silently ignore.
        private boolean headersMalformed;

        /// Exact Authorization field value after one legal header delimiter space.
        private @Nullable String rawAuthorization;

        /// Generation incremented after each dispatched request on a keep-alive connection.
        private long generation;

        /// Parses one raw ISO-8859-1 header block without retaining credentials after dispatch.
        ///
        /// @param bytes complete raw header block
        private void parse(byte[] bytes) {
            headerParsed = true;
            authorizationMalformed = false;
            originMalformed = false;
            headersMalformed = false;
            rawAuthorization = null;
            try {
                String text = new String(bytes, StandardCharsets.ISO_8859_1);
                String[] lines = text.split("\\r\\n|\\n", -1);
                int authorizationCount = 0;
                int originCount = 0;
                boolean previousAuthorization = false;
                boolean previousOrigin = false;
                for (int index = 1; index < lines.length; index++) {
                    String line = lines[index];
                    if (line.isEmpty()) {
                        break;
                    }
                    if (line.startsWith(" ") || line.startsWith("\t")) {
                        headersMalformed = true;
                        if (previousAuthorization) {
                            authorizationMalformed = true;
                        }
                        if (previousOrigin) {
                            originMalformed = true;
                        }
                        continue;
                    }
                    int separator = line.indexOf(':');
                    if (separator <= 0) {
                        headersMalformed = true;
                        previousAuthorization = false;
                        previousOrigin = false;
                        continue;
                    }
                    String name = line.substring(0, separator);
                    String normalizedName = name.strip();
                    if (!isValidHeaderName(name) || !name.equals(normalizedName)
                            || containsControl(line)) {
                        headersMalformed = true;
                    }
                    previousAuthorization = AUTHORIZATION_HEADER.equalsIgnoreCase(normalizedName);
                    previousOrigin = "Origin".equalsIgnoreCase(normalizedName);
                    if (previousAuthorization && !AUTHORIZATION_HEADER.equalsIgnoreCase(name)) {
                        // Some HTTP parsers trim field names before exposing them to the application. Reject a
                        // padded Authorization name here so it cannot evade the raw duplicate-header guard.
                        authorizationMalformed = true;
                    }
                    if (previousOrigin && !"Origin".equalsIgnoreCase(name)) {
                        // Reject padded Origin names because NanoHTTPD trims names before exposing them in its map.
                        originMalformed = true;
                    }
                    if (!previousAuthorization) {
                        if (!previousOrigin) {
                            continue;
                        }
                    }
                    String value = line.substring(separator + 1);
                    if (previousAuthorization) {
                        authorizationCount++;
                        if (authorizationCount > 1) {
                            authorizationMalformed = true;
                        }
                        if (value.startsWith(" ")) {
                            value = value.substring(1);
                        } else if (value.startsWith("\t")) {
                            authorizationMalformed = true;
                        }
                        if (value.startsWith(" ") || value.startsWith("\t")
                                || value.endsWith(" ") || value.endsWith("\t")
                                || containsControl(value) || value.indexOf(',') >= 0) {
                            authorizationMalformed = true;
                        }
                        rawAuthorization = value;
                    }
                    if (previousOrigin) {
                        originCount++;
                        if (originCount > 1 || containsControl(value)) {
                            originMalformed = true;
                        }
                    }
                }
            } catch (RuntimeException failure) {
                authorizationMalformed = true;
                headersMalformed = true;
                rawAuthorization = null;
            }
        }

        /// Marks the current request malformed when the raw header block exceeds the bounded parser size.
        private void markMalformed() {
            headerParsed = true;
            authorizationMalformed = true;
            originMalformed = true;
            headersMalformed = true;
            rawAuthorization = null;
        }

        /// Returns whether the current header block has been parsed.
        private boolean headerParsed() {
            return headerParsed;
        }

        /// Returns whether Authorization syntax was ambiguous or malformed.
        private boolean authorizationMalformed() {
            return authorizationMalformed;
        }

        /// Returns whether the current header block has duplicate or malformed Origin syntax.
        private boolean originMalformed() {
            return originMalformed;
        }

        /// Returns whether any header line is malformed, including syntax NanoHTTPD would otherwise ignore.
        private boolean headersMalformed() {
            return headersMalformed;
        }

        /// Returns the exact parsed Authorization value, or null when absent.
        private @Nullable String rawAuthorization() {
            return rawAuthorization;
        }

        /// Returns the current parser generation.
        private long generation() {
            return generation;
        }

        /// Clears credential state before the next request on a keep-alive connection.
        private void resetForNextRequest() {
            headerParsed = false;
            authorizationMalformed = false;
            originMalformed = false;
            headersMalformed = false;
            rawAuthorization = null;
            generation++;
        }

        /// Validates an HTTP field name using the RFC token character set.
        ///
        /// @param name raw field name before parser normalization
        /// @return whether the field name is syntactically valid
        private static boolean isValidHeaderName(String name) {
            if (name.isEmpty()) {
                return false;
            }
            for (int index = 0; index < name.length(); index++) {
                char character = name.charAt(index);
                if (!(character >= 'a' && character <= 'z')
                        && !(character >= 'A' && character <= 'Z')
                        && !(character >= '0' && character <= '9')
                        && "!#$%&'*+-.^_`|~".indexOf(character) < 0) {
                    return false;
                }
            }
            return true;
        }

        /// Tests for header control characters that must never enter credential parsing.
        private static boolean containsControl(String value) {
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if (character < 0x20 || character == 0x7F) {
                    return true;
                }
            }
            return false;
        }
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
    private static final class SessionState {
        /// Protocol version negotiated for this session.
        private final String protocolVersion;

        /// Epoch time of the most recent accepted request.
        private volatile long lastAccessMillis;

        /// Validates the negotiated protocol version stored for this session.
        private SessionState(String protocolVersion, long lastAccessMillis) {
            Objects.requireNonNull(protocolVersion, "protocolVersion");
            this.protocolVersion = protocolVersion;
            this.lastAccessMillis = lastAccessMillis;
        }

        /// Returns the protocol version negotiated for this session.
        ///
        /// @return negotiated protocol version
        private String protocolVersion() {
            return protocolVersion;
        }

        /// Returns the last activity time used for capacity eviction.
        ///
        /// @return last accepted request time in epoch milliseconds
        private long lastAccessMillis() {
            return lastAccessMillis;
        }

        /// Returns whether this session has exceeded the inactivity period.
        ///
        /// @param now current epoch milliseconds
        /// @param ttlMillis inactivity period in milliseconds
        /// @return whether the session is expired
        private boolean isExpired(long now, long ttlMillis) {
            return now >= lastAccessMillis && now - lastAccessMillis >= ttlMillis;
        }

        /// Renews this session after an accepted request.
        ///
        /// @param now current epoch milliseconds
        private void touch(long now) {
            lastAccessMillis = Math.max(lastAccessMillis, now);
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

        /// Whether a supplied session identifier may be echoed in the error response.
        private final boolean exposeSessionContext;

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
            this(status, code, message, true);
        }

        /// Creates a transport failure with explicit response-context visibility.
        ///
        /// @param status HTTP status to return
        /// @param code JSON-RPC error code
        /// @param message stable transport error description
        /// @param exposeSessionContext whether a supplied session may be echoed
        private TransportException(
                Response.Status status,
                int code,
                String message,
                boolean exposeSessionContext) {
            super(Objects.requireNonNull(message, "message"));
            this.code = code;
            this.status = Objects.requireNonNull(status, "status");
            this.exposeSessionContext = exposeSessionContext;
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

        /// Returns whether response metadata may include a supplied session context.
        ///
        /// @return whether session metadata is safe to echo
        private boolean exposeSessionContext() {
            return exposeSessionContext;
        }
    }

}
