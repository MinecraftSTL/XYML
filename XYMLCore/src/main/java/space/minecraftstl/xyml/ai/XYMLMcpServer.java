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

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.task.Schedulers;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/// Implements the MCP server subset needed by XYML over newline-delimited standard I/O.
///
/// The implementation deliberately supports only stdio. It handles initialization, tool listing
/// and calls, resource-template listing and reads, and ping without opening network listeners.
@NotNullByDefault
public final class XYMLMcpServer implements AutoCloseable {

    /// Server identity advertised during MCP initialization.
    private static final @Unmodifiable Map<String, String> SERVER_INFO =
            Map.of("name", "xyml-ai-mcp-server", "version", "1.0.0");

    /// JSON codec shared by the reader and response writer.
    private final Gson gson = new Gson();

    /// Registry supplying XYML tools and resources.
    private final XYMLMcpToolRegistry registry;

    /// UTF-8 protocol input.
    private final BufferedReader input;

    /// UTF-8 protocol output.
    private final BufferedWriter output;

    /// Signals EOF, protocol failure, or explicit closure.
    private final CountDownLatch terminated = new CountDownLatch(1);

    /// Ensures close and reader termination run once.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Background protocol reader scheduled on the shared I/O executor.
    private final Future<?> readerTask;

    /// Creates and starts a single-session stdio MCP server.
    ///
    /// @param service initialized launcher operation service
    public XYMLMcpServer(XYMLMcpOperations service) {
        this(service, System.in, System.out);
    }

    /// Creates and starts a single-session MCP server on explicit standard-I/O streams.
    ///
    /// @param service initialized launcher operation service, or null for schema-only tests
    /// @param protocolInput protocol input stream
    /// @param protocolOutput protocol output stream
    public XYMLMcpServer(@Nullable XYMLMcpOperations service, InputStream protocolInput, OutputStream protocolOutput) {
        registry = new XYMLMcpToolRegistry(service);
        input = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(protocolInput, "protocolInput"), StandardCharsets.UTF_8));
        output = new BufferedWriter(new OutputStreamWriter(
                Objects.requireNonNull(protocolOutput, "protocolOutput"), StandardCharsets.UTF_8));
        readerTask = Schedulers.io().submit(this::readMessages);
    }

    /// Waits until the MCP client closes its input stream or this server is closed.
    ///
    /// @throws InterruptedException if the waiting thread is interrupted
    public void awaitTermination() throws InterruptedException {
        terminated.await();
    }

    /// Stops the stdio reader and releases any blocked termination waiter.
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        readerTask.cancel(true);
        try {
            input.close();
        } catch (IOException ignored) {
            // The peer may already have closed its pipe.
        } finally {
            terminated.countDown();
        }
    }

    /// Reads complete newline-delimited JSON-RPC messages until EOF.
    private void readMessages() {
        try {
            @Nullable String line;
            while (!closed.get() && (line = input.readLine()) != null) {
                if (!line.isBlank()) {
                    handleLine(line);
                }
            }
        } catch (IOException ignored) {
            // Closing the client pipe or this server is a normal stdio lifecycle event.
        } finally {
            closed.set(true);
            terminated.countDown();
        }
    }

    /// Parses and dispatches one JSON-RPC line.
    ///
    /// @param line complete UTF-8 JSON line
    private void handleLine(String line) {
        @Nullable JsonElement id = null;
        try {
            JsonObject request = gson.fromJson(line, JsonObject.class);
            if (request == null) {
                throw new JsonParseException("Request must be a JSON object");
            }
            id = request.get("id");
            @Nullable String method = stringMember(request, "method");
            if (method == null || method.isBlank()) {
                throw new ProtocolException(-32600, "Request method is missing");
            }
            if (id == null || id.isJsonNull()) {
                handleNotification(method);
                return;
            }
            writeResult(id, dispatch(method, objectMember(request, "params")));
        } catch (ProtocolException exception) {
            writeError(id, exception.code(), exception.getMessage());
        } catch (JsonParseException | IllegalStateException exception) {
            writeError(id, -32700, "Invalid JSON-RPC message: " + exception.getMessage());
        } catch (Exception exception) {
            writeError(id, -32603, exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage());
        }
    }

    /// Handles supported client notifications.
    ///
    /// @param method notification method
    private static void handleNotification(String method) {
        // JSON-RPC notifications never receive a response. Unknown notifications are ignored for forward compatibility.
    }

    /// Dispatches one request to the minimal MCP method surface.
    ///
    /// @param method JSON-RPC method
    /// @param params decoded parameter object
    /// @return JSON-compatible result
    private Object dispatch(String method, JsonObject params) {
        return switch (method) {
            case "initialize" -> initialize(params);
            case "ping" -> Map.of();
            case "tools/list" -> Map.of("tools", registry.toolDefinitions());
            case "tools/call" -> callTool(params);
            case "resources/templates/list" -> Map.of("resourceTemplates",
                    registry.resourceTemplateDefinitions());
            case "resources/read" -> readResource(params);
            default -> throw new ProtocolException(-32601, "Unsupported method: " + method);
        };
    }

    /// Negotiates the protocol version and advertises XYML capabilities.
    ///
    /// @param params initialization parameters
    /// @return initialization result
    private static @Unmodifiable Map<String, Object> initialize(JsonObject params) {
        @Nullable String requestedVersion = stringMember(params, "protocolVersion");
        Map<String, Object> capabilities = Map.of(
                "tools", Map.of("listChanged", false),
                "resources", Map.of("subscribe", false, "listChanged", false));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", requestedVersion == null ? "2025-06-18" : requestedVersion);
        result.put("capabilities", capabilities);
        result.put("serverInfo", SERVER_INFO);
        result.put("instructions", "Use XYML tools to diagnose and test local Minecraft instances.");
        return Map.copyOf(result);
    }

    /// Invokes one tool and formats its MCP content envelope.
    ///
    /// @param params tool-call parameters
    /// @return MCP tool-call result
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

    /// Reads one resource and formats its MCP content envelope.
    ///
    /// @param params resource-read parameters
    /// @return MCP resource-read result
    private @Unmodifiable Map<String, Object> readResource(JsonObject params) {
        @Nullable String uri = stringMember(params, "uri");
        if (uri == null || uri.isBlank()) {
            throw new ProtocolException(-32602, "Resource URI is missing");
        }
        try {
            Map<String, String> resource = registry.readResource(uri);
            return Map.of("contents", List.of(Map.of(
                    "uri", resource.getOrDefault("uri", uri),
                    "mimeType", resource.getOrDefault("mime_type", "text/plain"),
                    "text", resource.getOrDefault("text", ""))));
        } catch (Exception exception) {
            throw new ProtocolException(-32603, exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage());
        }
    }

    /// Writes one successful JSON-RPC response.
    private void writeResult(JsonElement id, Object result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id.deepCopy());
        response.add("result", gson.toJsonTree(result));
        writeMessage(response);
    }

    /// Writes one JSON-RPC error response.
    private void writeError(@Nullable JsonElement id, int code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id == null ? null : id.deepCopy());
        response.add("error", error);
        writeMessage(response);
    }

    /// Serializes and flushes one newline-delimited JSON-RPC response.
    private void writeMessage(JsonObject response) {
        synchronized (output) {
            try {
                output.write(gson.toJson(response));
                output.newLine();
                output.flush();
            } catch (IOException ignored) {
                closed.set(true);
                terminated.countDown();
            }
        }
    }

    /// Returns a string object member, or null when absent or JSON null.
    private static @Nullable String stringMember(JsonObject object, String name) {
        @Nullable JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    /// Returns an object member or an empty object when absent.
    private static JsonObject objectMember(JsonObject object, String name) {
        @Nullable JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) {
            return new JsonObject();
        }
        if (!value.isJsonObject()) {
            throw new ProtocolException(-32602, name + " must be a JSON object");
        }
        return value.getAsJsonObject();
    }

    /// Converts an object member to a JSON-compatible immutable map.
    @SuppressWarnings("unchecked")
    private Map<String, Object> mapMember(JsonObject object, String name) {
        JsonObject value = objectMember(object, name);
        Map<String, Object> decoded = gson.fromJson(value, Map.class);
        return decoded == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(decoded));
    }

    /// Internal JSON-RPC error with an explicit protocol code.
    @NotNullByDefault
    private static final class ProtocolException extends RuntimeException {
        /// JSON-RPC error code.
        private final int code;

        /// Creates one protocol error.
        private ProtocolException(int code, String message) {
            super(message);
            this.code = code;
        }

        /// Returns the JSON-RPC error code.
        private int code() {
            return code;
        }
    }
}
