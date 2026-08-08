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
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.task.Schedulers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

/// Defines the XYML-specific MCP surface without depending on an external MCP SDK.
@NotNullByDefault
public final class XYMLMcpToolRegistry {

    /// JSON serializer used for text content returned by tool calls.
    private static final Gson GSON = new Gson();

    /// Tools requiring explicit confirmation before invoking launcher operations.
    private static final @Unmodifiable List<String> CONFIRMATION_TOOLS = List.of(
            "remove_mods", "launch_game", "stop_game", "get_launch_status");

    /// Service receiving launcher-specific operations, or null for schema-only inspection.
    private final @Nullable XYMLMcpOperations service;

    /// Immutable public tool definitions in declaration order.
    private final @Unmodifiable List<ToolDefinition> tools;

    /// Operations indexed by public tool name.
    private final @Unmodifiable Map<String, Operation> operations;

    /// Creates a registry backed by a launcher service.
    ///
    /// @param service service implementation, or null for schema-only use
    public XYMLMcpToolRegistry(@Nullable XYMLMcpOperations service) {
        this.service = service;
        List<ToolDefinition> definitions = new ArrayList<>();
        Map<String, Operation> handlers = new LinkedHashMap<>();
        register(definitions, handlers, "list_instances", "[L1] Read-only list of installed XYML instances.",
                schema(Map.of()), arguments -> Map.of("instances", service().listInstances()));
        register(definitions, handlers, "get_instance_settings",
                "[L1] Read-only effective settings for one instance.",
                schema(Map.of("instance_id", stringSchema("Instance identifier")), List.of("instance_id")),
                arguments -> service().getInstanceSettings(requiredString(arguments, "instance_id")));
        register(definitions, handlers, "get_mods_directory", "[L1] Read-only absolute mods directory path.",
                schema(Map.of("instance_id", stringSchema("Instance identifier")), List.of("instance_id")),
                arguments -> Map.of("path", service().getModsDirectory(requiredString(arguments, "instance_id"))));
        register(definitions, handlers, "get_logs", "[L1] Read-only tail of the latest game log.",
                schema(Map.of("instance_id", stringSchema("Instance identifier"),
                        "lines", integerSchema("Number of trailing lines", 1, 20_000)), List.of("instance_id")),
                arguments -> service().getLogs(requiredString(arguments, "instance_id"),
                        optionalInteger(arguments, "lines", 200)));
        register(definitions, handlers, "analyze_crash",
                "[L1] Read-only CrashReportAnalyzer diagnosis using a log and optional instance crash report.",
                schema(Map.of("instance_id", stringSchema("Instance identifier"),
                        "log_text", nullableStringSchema("Raw log text"),
                        "crash_report_path", nullableStringSchema("Path inside the instance crash-reports directory")),
                        List.of("instance_id")),
                arguments -> service().analyzeCrash(requiredString(arguments, "instance_id"),
                        optionalString(arguments, "log_text"), optionalString(arguments, "crash_report_path")));
        register(definitions, handlers, "list_java_runtimes", "[L1] Read-only Java runtimes known to XYML.",
                schema(Map.of()), arguments -> Map.of("runtimes", service().listJavaRuntimes()));
        register(definitions, handlers, "list_local_mods", "[L1] Read-only local mod files and enabled states.",
                schema(Map.of("instance_id", stringSchema("Instance identifier")), List.of("instance_id")),
                arguments -> Map.of("mods", service().listLocalMods(requiredString(arguments, "instance_id"))));
        register(definitions, handlers, "set_java_version",
                "[L2] Low-risk instance setting write: choose Java major version or executable path.",
                schema(Map.of("instance_id", stringSchema("Instance identifier"),
                        "java_version", nullableStringSchema("Java major version"),
                        "java_path", nullableStringSchema("Java executable path")), List.of("instance_id")),
                arguments -> service().setJavaVersion(requiredString(arguments, "instance_id"),
                        optionalString(arguments, "java_version"), optionalString(arguments, "java_path")));
        register(definitions, handlers, "set_memory",
                "[L2] Low-risk instance setting write: set heap bounds in MiB.",
                schema(Map.of("instance_id", stringSchema("Instance identifier"),
                        "min_memory_mb", nullableIntegerSchema("Minimum heap in MiB", 0, 1_048_576),
                        "max_memory_mb", nullableIntegerSchema("Maximum heap in MiB", 1, 1_048_576)),
                        List.of("instance_id")),
                arguments -> service().setMemory(requiredString(arguments, "instance_id"),
                        optionalInteger(arguments, "min_memory_mb"), optionalInteger(arguments, "max_memory_mb")));
        register(definitions, handlers, "set_jvm_options",
                "[L2] Low-risk instance setting write: replace JVM options.",
                schema(Map.of("instance_id", stringSchema("Instance identifier"),
                        "options", stringSchema("JVM options")), List.of("instance_id", "options")),
                arguments -> service().setJvmOptions(requiredString(arguments, "instance_id"),
                        requiredString(arguments, "options")));
        register(definitions, handlers, "set_window_options",
                "[L2] Low-risk instance setting write: set dimensions and fullscreen mode.",
                schema(Map.of("instance_id", stringSchema("Instance identifier"),
                        "width", nullableIntegerSchema("Window width", 0, 32_768),
                        "height", nullableIntegerSchema("Window height", 0, 32_768),
                        "fullscreen", nullableBooleanSchema("Fullscreen flag")), List.of("instance_id")),
                arguments -> service().setWindowOptions(requiredString(arguments, "instance_id"),
                        optionalInteger(arguments, "width"), optionalInteger(arguments, "height"),
                        optionalBoolean(arguments, "fullscreen")));
        register(definitions, handlers, "enable_mod", "[L2] Enables a mod through XYML's .disabled transition.",
                schema(Map.of("instance_id", stringSchema("Instance identifier"),
                        "path", stringSchema("Mod path")), List.of("instance_id", "path")),
                arguments -> Map.of("path", service().enableMod(requiredString(arguments, "instance_id"),
                        requiredString(arguments, "path"))));
        register(definitions, handlers, "disable_mod", "[L2] Disables a mod through XYML's .disabled transition.",
                schema(Map.of("instance_id", stringSchema("Instance identifier"),
                        "path", stringSchema("Mod path")), List.of("instance_id", "path")),
                arguments -> Map.of("path", service().disableMod(requiredString(arguments, "instance_id"),
                        requiredString(arguments, "path"))));
        register(definitions, handlers, "remove_mods",
                "[L2] Deletes selected mods through XYML ModManager; requires confirmed=true.",
                confirmedSchema(Map.of("instance_id", stringSchema("Instance identifier"),
                        "paths", Map.of("type", "array", "items", stringSchema("Mod path"))),
                        List.of("instance_id", "paths")),
                arguments -> Map.of("removed", service().removeMods(requiredString(arguments, "instance_id"),
                        requiredStrings(arguments, "paths"))));
        register(definitions, handlers, "launch_game",
                "[L3] Starts a background game test; obtain user confirmation and pass confirmed=true.",
                confirmedSchema(Map.of("instance_id", stringSchema("Instance identifier")), List.of("instance_id")),
                arguments -> service().launchGame(requiredString(arguments, "instance_id")));
        register(definitions, handlers, "stop_game",
                "[L3] Terminates the tracked game process; obtain user confirmation and pass confirmed=true.",
                confirmedSchema(Map.of("instance_id", stringSchema("Instance identifier")), List.of("instance_id")),
                arguments -> service().stopGame(requiredString(arguments, "instance_id")));
        register(definitions, handlers, "get_launch_status",
                "[L3] Reads status for a confirmed launch-test workflow; requires confirmed=true.",
                confirmedSchema(Map.of("instance_id", stringSchema("Instance identifier")), List.of("instance_id")),
                arguments -> service().getLaunchStatus(requiredString(arguments, "instance_id")));
        tools = List.copyOf(definitions);
        operations = Map.copyOf(handlers);
    }

    /// Returns every tool definition exposed by XYML.
    ///
    /// @return immutable tool definitions
    public @Unmodifiable List<ToolDefinition> toolDefinitions() {
        return tools;
    }

    /// Returns templates for latest logs, crash-report listings, and individual reports.
    ///
    /// @return immutable resource templates
    public @Unmodifiable List<ResourceTemplate> resourceTemplateDefinitions() {
        return List.of(
                new ResourceTemplate("xyml://instances/{instance_id}/logs/latest.log", "latest_log",
                        "Latest game log for an XYML instance", "text/plain"),
                new ResourceTemplate("xyml://instances/{instance_id}/crash-reports/", "crash_report_directory",
                        "Resource URIs for crash reports in an XYML instance", "text/uri-list"),
                new ResourceTemplate("xyml://instances/{instance_id}/crash-reports/{report_name}", "crash_report",
                        "One crash report from an XYML instance", "text/plain"));
    }

    /// Invokes one registered tool after validation and confirmation checks.
    ///
    /// @param name requested tool name
    /// @param arguments decoded JSON arguments
    /// @return structured result and its MCP error flag
    public ToolCallResult call(String name, Map<String, Object> arguments) {
        @Nullable Operation operation = operations.get(name);
        if (operation == null) {
            return ToolCallResult.error(name, "Unknown tool: " + name);
        }
        try {
            if (CONFIRMATION_TOOLS.contains(name) && !optionalBoolean(arguments, "confirmed", false)) {
                throw new IllegalArgumentException("Tool " + name + " requires confirmed=true");
            }
            return ToolCallResult.success(callOnIo(() -> operation.run(arguments)));
        } catch (Exception exception) {
            return ToolCallResult.error(name, exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage());
        }
    }

    /// Reads an XYML resource on the shared I/O scheduler.
    ///
    /// @param uri resource URI
    /// @return immutable URI, MIME type, and text map
    /// @throws Exception if the launcher service cannot read the resource
    public @Unmodifiable Map<String, String> readResource(String uri) throws Exception {
        return callOnIo(() -> service().readResource(uri));
    }

    /// Serializes one structured tool result for MCP text content.
    ///
    /// @param result structured result
    /// @return JSON text
    public static String toJsonText(Map<String, Object> result) {
        return GSON.toJson(result);
    }

    /// Public protocol-neutral tool definition.
    ///
    /// @param name tool name
    /// @param description risk-labelled description
    /// @param inputSchema immutable JSON schema
    @NotNullByDefault
    public record ToolDefinition(
            String name, String description, @Unmodifiable Map<String, Object> inputSchema) {
        /// Validates and snapshots one definition.
        public ToolDefinition {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(description, "description");
            inputSchema = Map.copyOf(inputSchema);
        }
    }

    /// Public protocol-neutral resource template.
    ///
    /// @param uriTemplate resource URI template
    /// @param name resource name
    /// @param description resource description
    /// @param mimeType expected MIME type
    @NotNullByDefault
    public record ResourceTemplate(String uriTemplate, String name, String description, String mimeType) {
        /// Validates one resource template.
        public ResourceTemplate {
            Objects.requireNonNull(uriTemplate, "uriTemplate");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(mimeType, "mimeType");
        }
    }

    /// Immutable tool invocation result.
    ///
    /// @param error whether the operation failed
    /// @param structuredContent structured JSON-compatible content
    @NotNullByDefault
    public record ToolCallResult(boolean error, @Unmodifiable Map<String, Object> structuredContent) {
        /// Validates and snapshots one result.
        public ToolCallResult {
            structuredContent = Collections.unmodifiableMap(new LinkedHashMap<>(structuredContent));
        }

        /// Creates a successful result.
        public static ToolCallResult success(Map<String, Object> content) {
            return new ToolCallResult(false, content);
        }

        /// Creates a failed result with a stable tool-and-error shape.
        public static ToolCallResult error(String tool, String message) {
            return new ToolCallResult(true, Map.of("tool", tool, "error", message));
        }
    }

    /// Registers one public definition and its private operation.
    private static void register(List<ToolDefinition> definitions, Map<String, Operation> handlers,
                                 String name, String description, Map<String, Object> inputSchema,
                                 Operation operation) {
        definitions.add(new ToolDefinition(name, description, inputSchema));
        handlers.put(name, operation);
    }

    /// Executes launcher work on the shared XYML I/O scheduler.
    private static <T> T callOnIo(Callable<T> operation) throws Exception {
        try {
            return Schedulers.io().submit(operation).get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception operationException) {
                throw operationException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("MCP operation failed", cause);
        }
    }

    /// Returns the configured service or a schema-only mode error.
    private XYMLMcpOperations service() {
        return Objects.requireNonNull(service, "This registry has no launcher service");
    }

    /// Creates an object JSON schema with optional required fields.
    private static Map<String, Object> schema(Map<String, Object> properties) {
        return schema(properties, List.of());
    }

    /// Creates an object JSON schema with required fields.
    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "object");
        result.put("properties", Map.copyOf(properties));
        if (!required.isEmpty()) {
            result.put("required", List.copyOf(required));
        }
        result.put("additionalProperties", false);
        return Map.copyOf(result);
    }

    /// Adds the confirmation property and marks it required.
    private static Map<String, Object> confirmedSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> expanded = new LinkedHashMap<>(properties);
        expanded.put("confirmed", Map.of("type", "boolean",
                "description", "Must be true after the user explicitly confirms this operation"));
        List<String> expandedRequired = new ArrayList<>(required);
        expandedRequired.add("confirmed");
        return schema(expanded, expandedRequired);
    }

    /// Creates a string property schema.
    private static Map<String, Object> stringSchema(String description) {
        return Map.of("type", "string", "description", description);
    }

    /// Creates a nullable string property schema.
    private static Map<String, Object> nullableStringSchema(String description) {
        return Map.of("type", List.of("string", "null"), "description", description);
    }

    /// Creates an integer property schema with bounds.
    private static Map<String, Object> integerSchema(String description, int minimum, int maximum) {
        return Map.of("type", "integer", "minimum", minimum, "maximum", maximum, "description", description);
    }

    /// Creates a nullable integer property schema with bounds.
    private static Map<String, Object> nullableIntegerSchema(String description, int minimum, int maximum) {
        return Map.of("type", List.of("integer", "null"), "minimum", minimum, "maximum", maximum,
                "description", description);
    }

    /// Creates a nullable boolean property schema.
    private static Map<String, Object> nullableBooleanSchema(String description) {
        return Map.of("type", List.of("boolean", "null"), "description", description);
    }

    /// Reads a required non-blank string argument.
    private static String requiredString(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-blank string");
        }
        return string;
    }

    /// Reads an optional string argument.
    private static @Nullable String optionalString(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String string)) {
            throw new IllegalArgumentException(name + " must be a string or null");
        }
        return string;
    }

    /// Reads an optional integer argument.
    private static @Nullable Integer optionalInteger(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(name + " must be an integer or null");
        }
        return number.intValue();
    }

    /// Reads an optional integer argument with a fallback.
    private static int optionalInteger(Map<String, Object> arguments, String name, int fallback) {
        @Nullable Integer value = optionalInteger(arguments, name);
        return value == null ? fallback : value;
    }

    /// Reads an optional boolean argument.
    private static @Nullable Boolean optionalBoolean(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Boolean bool)) {
            throw new IllegalArgumentException(name + " must be a boolean or null");
        }
        return bool;
    }

    /// Reads an optional boolean argument with a fallback.
    private static boolean optionalBoolean(Map<String, Object> arguments, String name, boolean fallback) {
        @Nullable Boolean value = optionalBoolean(arguments, name);
        return value == null ? fallback : value;
    }

    /// Reads a required array of string arguments.
    private static @Unmodifiable List<String> requiredStrings(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (!(value instanceof List<?> values)) {
            throw new IllegalArgumentException(name + " must be an array");
        }
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof String string) || string.isBlank()) {
                throw new IllegalArgumentException(name + " must contain non-blank strings");
            }
            result.add(string);
        }
        return List.copyOf(result);
    }

    /// Function invoking one launcher operation with decoded arguments.
    @FunctionalInterface
    @NotNullByDefault
    private interface Operation {
        /// Executes an operation.
        ///
        /// @param arguments decoded JSON arguments
        /// @return structured result
        /// @throws Exception when the underlying XYML operation fails
        Map<String, Object> run(Map<String, Object> arguments) throws Exception;
    }
}
