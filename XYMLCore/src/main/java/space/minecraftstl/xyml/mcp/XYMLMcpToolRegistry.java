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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.library.mcp.McpToolProvider;
import space.minecraftstl.xyml.library.mcp.McpToolProvider.ToolCallResult;
import space.minecraftstl.xyml.library.mcp.McpToolProvider.ToolDefinition;
import space.minecraftstl.xyml.task.Schedulers;

import java.awt.EventQueue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/// Defines the XYML-specific MCP surface without depending on an external MCP SDK.
@NotNullByDefault
public final class XYMLMcpToolRegistry implements McpToolProvider {

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
                schema(Map.of()),
                arguments -> Map.of("instances", McpTaskExecution.execute(service().listInstances())));
        register(definitions, handlers, "get_instance_settings",
                "[L1] Read-only effective settings for one instance.",
                schema(Map.of("instance_id", stringSchema("Instance identifier")), List.of("instance_id")),
                arguments -> McpTaskExecution.execute(
                        service().getInstanceSettings(requiredString(arguments, "instance_id"))));
        register(definitions, handlers, "rename_instance",
                "[L2] Renames an installed instance through XYML's repository lifecycle.",
                schema(Map.of(
                        "source_instance_id", stringSchema("Existing instance identifier"),
                        "destination_instance_id", stringSchema("New instance identifier")),
                        List.of("source_instance_id", "destination_instance_id")),
                arguments -> McpTaskExecution.execute(service().renameInstance(
                        requiredString(arguments, "source_instance_id"),
                        requiredString(arguments, "destination_instance_id"))));
        register(definitions, handlers, "duplicate_instance",
                "[L2] Duplicates an installed instance; saved worlds are excluded unless requested.",
                schema(Map.of(
                        "source_instance_id", stringSchema("Existing instance identifier"),
                        "destination_instance_id", stringSchema("New instance identifier"),
                        "copy_saves", booleanSchema("Whether saved worlds should be copied")),
                        List.of("source_instance_id", "destination_instance_id")),
                arguments -> McpTaskExecution.execute(service().duplicateInstance(
                        requiredString(arguments, "source_instance_id"),
                        requiredString(arguments, "destination_instance_id"),
                        optionalBoolean(arguments, "copy_saves", false))));
        register(definitions, handlers, "delete_instance",
                "[L2] Deletes an instance after any launcher-configured manual confirmation.",
                schema(Map.of("instance_id", stringSchema("Existing instance identifier")),
                        List.of("instance_id")),
                arguments -> McpTaskExecution.execute(
                        service().deleteInstance(requiredString(arguments, "instance_id"))));
        register(definitions, handlers, "get_mods_directory", "[L1] Read-only absolute mods directory path.",
                schema(Map.of("instance_id", stringSchema("Instance identifier")), List.of("instance_id")),
                arguments -> Map.of("path", McpTaskExecution.execute(
                        service().getModsDirectory(requiredString(arguments, "instance_id")))));
        register(definitions, handlers, "analyze_crash",
                "[L1] Read-only CrashReportAnalyzer diagnosis that merges log and instance crash-report rules.",
                schema(Map.of("instance_id", stringSchema("Instance identifier"),
                        "log_text", nullableStringSchema(
                                "Raw log text; filesystem references in supplied text are not followed"),
                        "crash_report_path", nullableStringSchema(
                                "Direct file name inside the instance crash-reports directory")),
                        List.of("instance_id")),
                arguments -> McpTaskExecution.execute(service().analyzeCrash(requiredString(arguments, "instance_id"),
                        optionalString(arguments, "log_text"), optionalString(arguments, "crash_report_path"))));
        register(definitions, handlers, "list_java_runtimes", "[L1] Read-only Java runtimes known to XYML.",
                schema(Map.of()), arguments -> Map.of("runtimes", service().listJavaRuntimes()));
        register(definitions, handlers, "list_local_mods", "[L1] Read-only local mod files and enabled states.",
                schema(Map.of("instance_id", stringSchema("Instance identifier")), List.of("instance_id")),
                arguments -> Map.of("mods", McpTaskExecution.execute(
                        service().listLocalMods(requiredString(arguments, "instance_id")))));
        register(definitions, handlers, "set_java_version",
                "[L2] Low-risk instance setting write: choose Java major version or executable path.",
                schema(Map.of("instance_id", stringSchema("Instance identifier"),
                        "java_version", nullableStringSchema("Java major version"),
                        "java_path", nullableStringSchema("Java executable path"),
                        "inherit", booleanSchema("Restore inherited Java settings")), List.of("instance_id")),
                arguments -> McpTaskExecution.execute(service().setJavaVersion(requiredString(arguments, "instance_id"),
                        optionalString(arguments, "java_version"), optionalString(arguments, "java_path"),
                        optionalBoolean(arguments, "inherit", false))));
        register(definitions, handlers, "set_memory",
                "[L2] Low-risk instance setting write: set heap bounds in MiB.",
                schema(Map.of("instance_id", stringSchema("Instance identifier"),
                        "min_memory_mb", nullableIntegerSchema("Minimum heap in MiB", 0, 1_048_576),
                        "max_memory_mb", nullableIntegerSchema("Maximum heap in MiB", 1, 1_048_576),
                        "inherit", booleanSchema("Restore inherited heap settings")),
                        List.of("instance_id")),
                arguments -> McpTaskExecution.execute(service().setMemory(requiredString(arguments, "instance_id"),
                        optionalInteger(arguments, "min_memory_mb"), optionalInteger(arguments, "max_memory_mb"),
                        optionalBoolean(arguments, "inherit", false))));
        register(definitions, handlers, "set_jvm_options",
                "[L2] Low-risk instance setting write: replace JVM options.",
                schema(Map.of("instance_id", stringSchema("Instance identifier"),
                        "options", nullableStringSchema("JVM options"),
                        "inherit", booleanSchema("Restore inherited JVM options")), List.of("instance_id")),
                arguments -> McpTaskExecution.execute(service().setJvmOptions(requiredString(arguments, "instance_id"),
                        optionalString(arguments, "options"), optionalBoolean(arguments, "inherit", false))));
        register(definitions, handlers, "set_window_options",
                "[L2] Low-risk instance setting write: set dimensions and fullscreen mode.",
                schema(Map.of("instance_id", stringSchema("Instance identifier"),
                        "width", nullableIntegerSchema("Window width", 0, 32_768),
                        "height", nullableIntegerSchema("Window height", 0, 32_768),
                        "fullscreen", nullableBooleanSchema("Fullscreen flag"),
                        "inherit", booleanSchema("Restore inherited window settings")), List.of("instance_id")),
                arguments -> McpTaskExecution.execute(service().setWindowOptions(requiredString(arguments, "instance_id"),
                        optionalInteger(arguments, "width"), optionalInteger(arguments, "height"),
                        optionalBoolean(arguments, "fullscreen"), optionalBoolean(arguments, "inherit", false))));
        register(definitions, handlers, "enable_mod", "[L2] Enables a mod through XYML's .disabled transition.",
                schema(Map.of("instance_id", stringSchema("Instance identifier"),
                        "path", stringSchema("Mod path")), List.of("instance_id", "path")),
                arguments -> Map.of("path", McpTaskExecution.execute(
                        service().enableMod(requiredString(arguments, "instance_id"),
                                requiredString(arguments, "path")))));
        register(definitions, handlers, "disable_mod", "[L2] Disables a mod through XYML's .disabled transition.",
                schema(Map.of("instance_id", stringSchema("Instance identifier"),
                        "path", stringSchema("Mod path")), List.of("instance_id", "path")),
                arguments -> Map.of("path", McpTaskExecution.execute(
                        service().disableMod(requiredString(arguments, "instance_id"),
                                requiredString(arguments, "path")))));
        register(definitions, handlers, "remove_mods",
                "[L2] Deletes selected mods after any launcher-configured manual confirmation.",
                schema(Map.of("instance_id", stringSchema("Instance identifier"),
                        "paths", Map.of("type", "array", "items", stringSchema("Mod path"))),
                        List.of("instance_id", "paths")),
                arguments -> McpTaskExecution.execute(service().removeMods(requiredString(arguments, "instance_id"),
                        requiredStrings(arguments, "paths"))));
        register(definitions, handlers, "launch_game",
                "[L3] High-impact background game launch; user confirmation is recommended before calling.",
                schema(Map.of("instance_id", stringSchema("Instance identifier")), List.of("instance_id")),
                arguments -> McpTaskExecution.execute(
                        service().launchGame(requiredString(arguments, "instance_id"))));
        register(definitions, handlers, "stop_game",
                "[L3] High-impact process termination; user confirmation is recommended before calling.",
                schema(Map.of("instance_id", stringSchema("Instance identifier")), List.of("instance_id")),
                arguments -> McpTaskExecution.execute(
                        service().stopGame(requiredString(arguments, "instance_id"))));
        register(definitions, handlers, "get_launch_status",
                "[L1] Reads status for a launch-test workflow.",
                schema(Map.of("instance_id", stringSchema("Instance identifier")), List.of("instance_id")),
                arguments -> service().getLaunchStatus(requiredString(arguments, "instance_id")));
        tools = List.copyOf(definitions);
        operations = Map.copyOf(handlers);
    }

    /// Returns every tool definition exposed by XYML.
    ///
    /// @return immutable tool definitions
    @Override
    public @Unmodifiable List<ToolDefinition> toolDefinitions() {
        return tools;
    }

    /// Invokes one registered tool after validating its arguments.
    ///
    /// @param name requested tool name
    /// @param arguments decoded JSON arguments
    /// @return structured result and its MCP error flag
    @Override
    public ToolCallResult call(String name, @Unmodifiable Map<String, @Nullable Object> arguments) {
        @Nullable Operation operation = operations.get(name);
        if (operation == null) {
            return ToolCallResult.error(name, "Unknown tool: " + name);
        }
        try {
            return ToolCallResult.success(callOnIo(() -> operation.run(arguments)));
        } catch (Exception exception) {
            return ToolCallResult.error(name, exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage());
        }
    }

    /// Registers one public definition and its private operation.
    private static void register(List<ToolDefinition> definitions, Map<String, Operation> handlers,
                                 String name, String description, Map<String, @Nullable Object> inputSchema,
                                 Operation operation) {
        definitions.add(new ToolDefinition(name, description, inputSchema));
        handlers.put(name, operation);
    }

    /// Executes launcher work on the shared XYML I/O scheduler and propagates caller cancellation to that work.
    private static <T> T callOnIo(Callable<T> operation) throws Exception {
        if (EventQueue.isDispatchThread()) {
            throw new IllegalStateException("MCP tool invocation cannot block the AWT event dispatch thread");
        }
        Future<T> future = Schedulers.io().submit(operation);
        try {
            return future.get();
        } catch (InterruptedException exception) {
            future.cancel(true);
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

    /// Creates a boolean property schema.
    private static Map<String, Object> booleanSchema(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    /// Creates a nullable boolean property schema.
    private static Map<String, Object> nullableBooleanSchema(String description) {
        return Map.of("type", List.of("boolean", "null"), "description", description);
    }

    /// Reads a required non-blank string argument.
    private static String requiredString(Map<String, @Nullable Object> arguments, String name) {
        @Nullable Object value = arguments.get(name);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-blank string");
        }
        return string;
    }

    /// Reads an optional string argument.
    private static @Nullable String optionalString(Map<String, @Nullable Object> arguments, String name) {
        @Nullable Object value = arguments.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String string)) {
            throw new IllegalArgumentException(name + " must be a string or null");
        }
        return string;
    }

    /// Reads an optional integer argument.
    private static @Nullable Integer optionalInteger(Map<String, @Nullable Object> arguments, String name) {
        @Nullable Object value = arguments.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(name + " must be an integer or null");
        }
        double numericValue = number.doubleValue();
        if (!Double.isFinite(numericValue)
                || numericValue != Math.rint(numericValue)
                || numericValue < Integer.MIN_VALUE
                || numericValue > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " must be a 32-bit integer or null");
        }
        return (int) numericValue;
    }

    /// Reads an optional integer argument with a fallback.
    private static int optionalInteger(Map<String, @Nullable Object> arguments, String name, int fallback) {
        @Nullable Integer value = optionalInteger(arguments, name);
        return value == null ? fallback : value;
    }

    /// Reads an optional boolean argument.
    private static @Nullable Boolean optionalBoolean(Map<String, @Nullable Object> arguments, String name) {
        @Nullable Object value = arguments.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Boolean bool)) {
            throw new IllegalArgumentException(name + " must be a boolean or null");
        }
        return bool;
    }

    /// Reads an optional boolean argument with a fallback.
    private static boolean optionalBoolean(Map<String, @Nullable Object> arguments, String name, boolean fallback) {
        @Nullable Boolean value = optionalBoolean(arguments, name);
        return value == null ? fallback : value;
    }

    /// Reads a required array of string arguments.
    private static @Unmodifiable List<String> requiredStrings(
            Map<String, @Nullable Object> arguments,
            String name) {
        @Nullable Object value = arguments.get(name);
        if (!(value instanceof List<@Nullable ?> values)) {
            throw new IllegalArgumentException(name + " must be an array");
        }
        List<String> result = new ArrayList<>();
        for (@Nullable Object item : values) {
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
        Map<String, @Nullable Object> run(Map<String, @Nullable Object> arguments) throws Exception;
    }
}
