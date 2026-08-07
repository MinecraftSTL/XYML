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

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.task.Schedulers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

/// Defines the XYML-specific MCP surface and adapts JSON arguments to launcher operations.
///
/// The registry is intentionally separate from the stdio transport so its schemas and risk
/// gates can be tested without starting a process or touching a real game directory.
@NotNullByDefault
public final class XYMLMcpToolRegistry {

    /// JSON mapper selected by the official MCP SDK.
    private static final McpJsonMapper JSON_MAPPER = McpJsonDefaults.getMapper();

    /// Tool names that delete files or control processes and require explicit confirmation.
    private static final @Unmodifiable List<String> CONFIRMATION_TOOLS = List.of(
            "remove_mods", "launch_game", "stop_game", "get_launch_status");

    /// Service receiving all launcher-specific operations.
    private final @Nullable XYMLMcpOperations service;

    /// Creates a registry backed by a launcher service.
    ///
    /// A null service is accepted for schema-only inspection and tests; invoking a tool in that
    /// mode returns an MCP error instead of touching the filesystem.
    ///
    /// @param service service implementation, or null for schema-only use
    public XYMLMcpToolRegistry(@Nullable XYMLMcpOperations service) {
        this.service = service;
    }

    /// Returns every tool specification exposed by XYML.
    ///
    /// @return immutable MCP tool specifications
    public @Unmodifiable List<McpServerFeatures.SyncToolSpecification> toolSpecifications() {
        List<McpServerFeatures.SyncToolSpecification> tools = new ArrayList<>();
        tools.add(tool("list_instances", "[L1] Read-only list of installed XYML instances.",
                schema(Map.of()), arguments -> Map.of("instances", service().listInstances())));
        tools.add(tool("get_instance_settings", "[L1] Read-only effective settings for one instance.",
                schema(Map.of("instance_id", stringSchema("Instance identifier")), List.of("instance_id")),
                arguments -> service().getInstanceSettings(requiredString(arguments, "instance_id"))));
        tools.add(tool("get_mods_directory", "[L1] Read-only absolute mods directory path.",
                schema(Map.of("instance_id", stringSchema("Instance identifier")), List.of("instance_id")),
                arguments -> Map.of("path", service().getModsDirectory(requiredString(arguments, "instance_id")))));
        tools.add(tool("get_logs", "[L1] Read-only tail of the latest game log.",
                schema(Map.of("instance_id", stringSchema("Instance identifier"),
                        "lines", integerSchema("Number of trailing lines", 1, 20_000)), List.of("instance_id")),
                arguments -> service().getLogs(requiredString(arguments, "instance_id"),
                        optionalInteger(arguments, "lines", 200))));
        tools.add(tool("analyze_crash",
                "[L1] Read-only CrashReportAnalyzer diagnosis using a log and optional instance crash report.",
                schema(Map.of("instance_id", stringSchema("Instance identifier"),
                        "log_text", nullableStringSchema("Raw log text"),
                        "crash_report_path", nullableStringSchema("Path inside the instance crash-reports directory")),
                        List.of("instance_id")),
                arguments -> service().analyzeCrash(requiredString(arguments, "instance_id"),
                        optionalString(arguments, "log_text"), optionalString(arguments, "crash_report_path"))));
        tools.add(tool("list_java_runtimes", "[L1] Read-only Java runtimes known to XYML.",
                schema(Map.of()), arguments -> Map.of("runtimes", service().listJavaRuntimes())));
        tools.add(tool("list_local_mods", "[L1] Read-only local mod files and enabled states.",
                schema(Map.of("instance_id", stringSchema("Instance identifier")), List.of("instance_id")),
                arguments -> Map.of("mods", service().listLocalMods(requiredString(arguments, "instance_id")))));

        tools.add(tool("set_java_version",
                "[L2] Low-risk instance setting write: choose Java major version or executable path.",
                schema(Map.of("instance_id", stringSchema("Instance identifier"),
                        "java_version", nullableStringSchema("Java major version"),
                        "java_path", nullableStringSchema("Java executable path")), List.of("instance_id")),
                arguments -> service().setJavaVersion(requiredString(arguments, "instance_id"),
                        optionalString(arguments, "java_version"), optionalString(arguments, "java_path"))));
        tools.add(tool("set_memory", "[L2] Low-risk instance setting write: set heap bounds in MiB.",
                schema(Map.of("instance_id", stringSchema("Instance identifier"),
                        "min_memory_mb", nullableIntegerSchema("Minimum heap in MiB", 0, 1_048_576),
                        "max_memory_mb", nullableIntegerSchema("Maximum heap in MiB", 1, 1_048_576)),
                        List.of("instance_id")),
                arguments -> service().setMemory(requiredString(arguments, "instance_id"),
                        optionalInteger(arguments, "min_memory_mb"), optionalInteger(arguments, "max_memory_mb"))));
        tools.add(tool("set_jvm_options", "[L2] Low-risk instance setting write: replace JVM options.",
                schema(Map.of("instance_id", stringSchema("Instance identifier"),
                        "options", stringSchema("JVM options")), List.of("instance_id", "options")),
                arguments -> service().setJvmOptions(requiredString(arguments, "instance_id"),
                        requiredString(arguments, "options"))));
        tools.add(tool("set_window_options",
                "[L2] Low-risk instance setting write: set dimensions and fullscreen mode.",
                schema(Map.of("instance_id", stringSchema("Instance identifier"),
                        "width", nullableIntegerSchema("Window width", 0, 32_768),
                        "height", nullableIntegerSchema("Window height", 0, 32_768),
                        "fullscreen", nullableBooleanSchema("Fullscreen flag")), List.of("instance_id")),
                arguments -> service().setWindowOptions(requiredString(arguments, "instance_id"),
                        optionalInteger(arguments, "width"), optionalInteger(arguments, "height"),
                        optionalBoolean(arguments, "fullscreen"))));
        tools.add(tool("enable_mod", "[L2] Enables a mod through XYML's .disabled transition.",
                schema(Map.of("instance_id", stringSchema("Instance identifier"),
                        "path", stringSchema("Mod path")), List.of("instance_id", "path")),
                arguments -> Map.of("path", service().enableMod(requiredString(arguments, "instance_id"),
                        requiredString(arguments, "path")))));
        tools.add(tool("disable_mod", "[L2] Disables a mod through XYML's .disabled transition.",
                schema(Map.of("instance_id", stringSchema("Instance identifier"),
                        "path", stringSchema("Mod path")), List.of("instance_id", "path")),
                arguments -> Map.of("path", service().disableMod(requiredString(arguments, "instance_id"),
                        requiredString(arguments, "path")))));
        tools.add(tool("remove_mods",
                "[L2] Deletes selected mods through XYML ModManager; requires confirmed=true.",
                confirmedSchema(Map.of("instance_id", stringSchema("Instance identifier"),
                        "paths", Map.of("type", "array", "items", stringSchema("Mod path"))),
                        List.of("instance_id", "paths")),
                arguments -> Map.of("removed", service().removeMods(requiredString(arguments, "instance_id"),
                        requiredStrings(arguments, "paths")))));

        tools.add(tool("launch_game",
                "[L3] Starts a background game test; obtain user confirmation and pass confirmed=true.",
                confirmedSchema(Map.of("instance_id", stringSchema("Instance identifier")), List.of("instance_id")),
                arguments -> service().launchGame(requiredString(arguments, "instance_id"))));
        tools.add(tool("stop_game",
                "[L3] Terminates the tracked game process; obtain user confirmation and pass confirmed=true.",
                confirmedSchema(Map.of("instance_id", stringSchema("Instance identifier")), List.of("instance_id")),
                arguments -> service().stopGame(requiredString(arguments, "instance_id"))));
        tools.add(tool("get_launch_status",
                "[L3] Reads status for a confirmed launch-test workflow; requires confirmed=true.",
                confirmedSchema(Map.of("instance_id", stringSchema("Instance identifier")), List.of("instance_id")),
                arguments -> service().getLaunchStatus(requiredString(arguments, "instance_id"))));
        return List.copyOf(tools);
    }

    /// Returns templates for latest logs, crash-report listings, and individual reports.
    ///
    /// @return immutable resource template specifications
    public @Unmodifiable List<McpServerFeatures.SyncResourceTemplateSpecification> resourceTemplateSpecifications() {
        return List.of(
                resourceTemplate("xyml://instances/{instance_id}/logs/latest.log", "latest_log",
                        "Latest game log for an XYML instance", "text/plain"),
                resourceTemplate("xyml://instances/{instance_id}/crash-reports/", "crash_report_directory",
                        "Resource URIs for crash reports in an XYML instance", "text/uri-list"),
                resourceTemplate("xyml://instances/{instance_id}/crash-reports/{report_name}", "crash_report",
                        "One crash report from an XYML instance", "text/plain"));
    }

    /// Creates a tool specification and centralizes risk confirmation handling.
    ///
    /// @param name tool name
    /// @param description risk-labelled tool description
    /// @param inputSchema JSON input schema
    /// @param operation launcher operation
    /// @return MCP tool specification
    private McpServerFeatures.SyncToolSpecification tool(
            String name,
            String description,
            Map<String, Object> inputSchema,
            Operation operation) {
        McpSchema.Tool definition = McpSchema.Tool.builder(name, inputSchema).description(description).build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(definition)
                .callHandler((exchange, request) -> invoke(name, operation, request))
                .build();
    }

    /// Converts a service result or exception into an MCP structured result.
    ///
    /// @param name tool name
    /// @param operation launcher operation
    /// @param request MCP tool request
    /// @return MCP tool result
    private McpSchema.CallToolResult invoke(
            String name,
            Operation operation,
            McpSchema.CallToolRequest request) {
        Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
        try {
            if (CONFIRMATION_TOOLS.contains(name) && !optionalBoolean(arguments, "confirmed", false)) {
                throw new IllegalArgumentException("Tool " + name + " requires confirmed=true");
            }
            Map<String, Object> result = callOnIo(() -> operation.run(arguments));
            return McpSchema.CallToolResult.builder()
                    .addTextContent(toJsonText(result))
                    .structuredContent(result)
                    .isError(false)
                    .build();
        } catch (Exception exception) {
            Map<String, Object> error = Map.of("tool", name, "error", exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage());
            return McpSchema.CallToolResult.builder()
                    .addTextContent(toJsonText(error))
                    .structuredContent(error)
                    .isError(true)
                    .build();
        }
    }

    /// Serializes structured content as valid JSON for clients that only inspect text content.
    ///
    /// @param result structured result
    /// @return JSON text
    private static String toJsonText(Map<String, Object> result) {
        try {
            return JSON_MAPPER.writeValueAsString(result);
        } catch (IOException exception) {
            return "{\"error\":\"Unable to serialize MCP result\"}";
        }
    }

    /// Creates one parameterized resource template specification.
    ///
    /// @param uriTemplate resource URI template
    /// @param name resource name
    /// @param description resource description
    /// @param mimeType expected MIME type
    /// @return MCP resource template specification
    private McpServerFeatures.SyncResourceTemplateSpecification resourceTemplate(
            String uriTemplate, String name, String description, String mimeType) {
        McpSchema.ResourceTemplate resource = McpSchema.ResourceTemplate.builder(uriTemplate, name)
                .description(description).mimeType(mimeType).build();
        return new McpServerFeatures.SyncResourceTemplateSpecification(resource,
                (exchange, request) -> {
                    try {
                        Map<String, String> result = callOnIo(() -> service().readResource(request.uri()));
                        return McpSchema.ReadResourceResult.builder(List.of(
                                McpSchema.TextResourceContents.builder(result.get("uri"), result.get("text"))
                                        .mimeType(result.get("mime_type")).build())).build();
                    } catch (Exception exception) {
                        return McpSchema.ReadResourceResult.builder(List.of(
                                McpSchema.TextResourceContents.builder(request.uri(), exception.getMessage() == null
                                        ? exception.getClass().getSimpleName() : exception.getMessage())
                                        .mimeType("text/plain").build())).build();
                    }
                });
    }

    /// Executes launcher work on the shared XYML I/O scheduler.
    ///
    /// @param operation launcher operation
    /// @param <T> result type
    /// @return operation result
    /// @throws Exception if the operation fails or is interrupted
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
    ///
    /// @return configured launcher operation service
    private XYMLMcpOperations service() {
        return Objects.requireNonNull(service, "This registry has no launcher service");
    }

    /// Creates an object JSON schema with optional required fields.
    ///
    /// @param properties property schemas
    /// @return object schema
    private static Map<String, Object> schema(Map<String, Object> properties) {
        return schema(properties, List.of());
    }

    /// Creates an object JSON schema with required fields.
    ///
    /// @param properties property schemas
    /// @param required required property names
    /// @return object schema
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

    /// Adds the confirmation property and marks it required for a high-impact tool.
    ///
    /// @param properties property schemas
    /// @param required required property names
    /// @return object schema with required confirmation
    private static Map<String, Object> confirmedSchema(
            Map<String, Object> properties, List<String> required) {
        Map<String, Object> expanded = new LinkedHashMap<>(properties);
        expanded.put("confirmed", Map.of("type", "boolean", "description",
                "Must be true after the user explicitly confirms this high-impact operation"));
        List<String> expandedRequired = new ArrayList<>(required);
        expandedRequired.add("confirmed");
        return schema(expanded, expandedRequired);
    }

    /// Creates a string property schema.
    ///
    /// @param description property description
    /// @return string schema
    private static Map<String, Object> stringSchema(String description) {
        return Map.of("type", "string", "description", description);
    }

    /// Creates a nullable string property schema.
    ///
    /// @param description property description
    /// @return nullable string schema
    private static Map<String, Object> nullableStringSchema(String description) {
        return Map.of("type", List.of("string", "null"), "description", description);
    }

    /// Creates an integer property schema with bounds.
    ///
    /// @param description property description
    /// @param minimum minimum value
    /// @param maximum maximum value
    /// @return integer schema
    private static Map<String, Object> integerSchema(String description, int minimum, int maximum) {
        return Map.of("type", "integer", "minimum", minimum, "maximum", maximum,
                "description", description);
    }

    /// Creates a nullable integer property schema with bounds.
    ///
    /// @param description property description
    /// @param minimum minimum value
    /// @param maximum maximum value
    /// @return nullable integer schema
    private static Map<String, Object> nullableIntegerSchema(String description, int minimum, int maximum) {
        return Map.of("type", List.of("integer", "null"), "minimum", minimum, "maximum", maximum,
                "description", description);
    }

    /// Creates a nullable boolean property schema.
    ///
    /// @param description property description
    /// @return nullable boolean schema
    private static Map<String, Object> nullableBooleanSchema(String description) {
        return Map.of("type", List.of("boolean", "null"), "description", description);
    }

    /// Reads a required non-blank string argument.
    ///
    /// @param arguments decoded arguments
    /// @param name argument name
    /// @return argument value
    private static String requiredString(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-blank string");
        }
        return string;
    }

    /// Reads an optional string argument, preserving null when omitted.
    ///
    /// @param arguments decoded arguments
    /// @param name argument name
    /// @return string value, or null
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
    ///
    /// @param arguments decoded arguments
    /// @param name argument name
    /// @return integer value, or null
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
    ///
    /// @param arguments decoded arguments
    /// @param name argument name
    /// @param fallback fallback value
    /// @return integer value
    private static int optionalInteger(Map<String, Object> arguments, String name, int fallback) {
        @Nullable Integer value = optionalInteger(arguments, name);
        return value == null ? fallback : value;
    }

    /// Reads an optional boolean argument.
    ///
    /// @param arguments decoded arguments
    /// @param name argument name
    /// @return boolean value, or null
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
    ///
    /// @param arguments decoded arguments
    /// @param name argument name
    /// @param fallback fallback value
    /// @return boolean value
    private static boolean optionalBoolean(Map<String, Object> arguments, String name, boolean fallback) {
        @Nullable Boolean value = optionalBoolean(arguments, name);
        return value == null ? fallback : value;
    }

    /// Reads a required array of string arguments.
    ///
    /// @param arguments decoded arguments
    /// @param name argument name
    /// @return immutable string list
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

    /// Function that invokes one launcher operation with decoded MCP arguments.
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
