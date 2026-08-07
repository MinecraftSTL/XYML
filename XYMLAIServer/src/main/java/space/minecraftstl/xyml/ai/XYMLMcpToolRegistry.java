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

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Defines the complete XYML MCP surface and adapts JSON arguments to XYML operations.
///
/// The registry is intentionally separate from the stdio transport so its schemas and risk
/// gates can be tested without starting a process or touching a real game directory.
@NotNullByDefault
public final class XYMLMcpToolRegistry {

    /// Tool names that mutate or start processes and therefore require explicit confirmation.
    private static final @Unmodifiable List<String> CONFIRMATION_TOOLS = List.of(
            "remove_mods", "install_game_version", "install_modloader", "create_instance",
            "install_local_modpack", "launch_game", "stop_game", "get_launch_status");

    /// Service receiving all launcher-specific operations.
    private final @Nullable XYMLMcpService service;

    /// Creates a registry backed by a launcher service.
    ///
    /// A null service is accepted for schema-only inspection and tests; invoking a tool in that
    /// mode returns an MCP error instead of touching the filesystem.
    ///
    /// @param service service implementation, or null for schema-only use
    public XYMLMcpToolRegistry(@Nullable XYMLMcpService service) {
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
        tools.add(tool("analyze_crash", "[L1] Read-only CrashReportAnalyzer diagnosis; provide log text or a report path.",
                schema(Map.of("instance_id", stringSchema("Instance identifier"),
                        "log_text", nullableStringSchema("Raw log text"),
                        "crash_report_path", nullableStringSchema("Crash report path")), List.of("instance_id")),
                arguments -> service().analyzeCrash(requiredString(arguments, "instance_id"),
                        optionalString(arguments, "log_text"), optionalString(arguments, "crash_report_path"))));
        tools.add(tool("list_java_runtimes", "[L1] Read-only Java runtimes known to XYML.",
                schema(Map.of()), arguments -> Map.of("runtimes", service().listJavaRuntimes())));
        tools.add(tool("list_local_mods", "[L1] Read-only local mod files and enabled states.",
                schema(Map.of("instance_id", stringSchema("Instance identifier")), List.of("instance_id")),
                arguments -> Map.of("mods", service().listLocalMods(requiredString(arguments, "instance_id")))));
        tools.add(tool("search_addons", "[L1] Read-only Modrinth or CurseForge add-on search.",
                schema(Map.of("source", stringSchema("modrinth or curseforge"),
                        "type", stringSchema("mod, resourcepack, shader, or modpack"),
                        "query", stringSchema("Search query"), "game_version", stringSchema("Game version"),
                        "category", nullableStringSchema("Optional category"), "sort", stringSchema("Sort name"),
                        "page", integerSchema("Zero-based page", 0, 10_000),
                        "page_size", integerSchema("Page size", 1, 100)),
                        List.of("source", "type", "query", "game_version")),
                arguments -> service().searchAddons(requiredString(arguments, "source"), requiredString(arguments, "type"),
                        requiredString(arguments, "query"), requiredString(arguments, "game_version"),
                        optionalString(arguments, "category"), optionalString(arguments, "sort", "POPULARITY"),
                        optionalInteger(arguments, "page", 0), optionalInteger(arguments, "page_size", 20))));
        tools.add(tool("get_addon_versions", "[L1] Read-only versions and files for a remote add-on project.",
                schema(Map.of("source", stringSchema("Remote source"), "type", nullableStringSchema("Add-on type; defaults to mod"),
                        "project_id", stringSchema("Remote project identifier")),
                        List.of("source", "project_id")),
                arguments -> Map.of("versions", service().getAddonVersions(requiredString(arguments, "source"),
                        optionalString(arguments, "type", "MOD"), requiredString(arguments, "project_id")))));
        tools.add(tool("get_addon_categories", "[L1] Read-only categories for a remote add-on source.",
                schema(Map.of("source", stringSchema("Remote source"), "type", stringSchema("Add-on type")),
                        List.of("source", "type")),
                arguments -> Map.of("categories", service().getAddonCategories(requiredString(arguments, "source"),
                        requiredString(arguments, "type")))));
        tools.add(tool("get_remote_version_by_local_file", "[L1] Read-only SHA-1 lookup for a local add-on file.",
                schema(Map.of("source", stringSchema("Remote source"), "type", nullableStringSchema("Add-on type; defaults to mod"),
                        "path", stringSchema("Local add-on path")), List.of("source", "path")),
                arguments -> service().getRemoteVersionByLocalFile(requiredString(arguments, "source"),
                        optionalString(arguments, "type", "MOD"), requiredString(arguments, "path"))));
        tools.add(tool("list_remote_game_versions", "[L1] Read-only available vanilla game versions.",
                schema(Map.of()), arguments -> Map.of("versions", service().listRemoteGameVersions())));
        tools.add(tool("list_modloader_versions", "[L1] Read-only available versions for a ModLoader and game version.",
                schema(Map.of("loader", stringSchema("Loader identifier"), "game_version", stringSchema("Game version")),
                        List.of("loader", "game_version")),
                arguments -> Map.of("versions", service().listModloaderVersions(requiredString(arguments, "loader"),
                        requiredString(arguments, "game_version")))));

        tools.add(tool("set_java_version", "[L2] Low-risk instance setting write: choose Java major version or executable path.",
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
                schema(Map.of("instance_id", stringSchema("Instance identifier"), "options", stringSchema("JVM options")),
                        List.of("instance_id", "options")),
                arguments -> service().setJvmOptions(requiredString(arguments, "instance_id"),
                        requiredString(arguments, "options"))));
        tools.add(tool("set_window_options", "[L2] Low-risk instance setting write: set dimensions and fullscreen mode.",
                schema(Map.of("instance_id", stringSchema("Instance identifier"),
                        "width", nullableIntegerSchema("Window width", 0, 32_768),
                        "height", nullableIntegerSchema("Window height", 0, 32_768),
                        "fullscreen", nullableBooleanSchema("Fullscreen flag")), List.of("instance_id")),
                arguments -> service().setWindowOptions(requiredString(arguments, "instance_id"),
                        optionalInteger(arguments, "width"), optionalInteger(arguments, "height"),
                        optionalBoolean(arguments, "fullscreen"))));

        tools.add(tool("install_addon", "[L2] Downloads a remote add-on into mods; user confirmation is recommended.",
                schema(Map.of("instance_id", stringSchema("Instance identifier"), "source", stringSchema("Remote source"),
                        "type", nullableStringSchema("Add-on type; defaults to mod"), "project_id", stringSchema("Project identifier"),
                        "version_id", stringSchema("Version identifier")),
                        List.of("instance_id", "source", "project_id", "version_id")),
                arguments -> service().installAddon(requiredString(arguments, "instance_id"),
                        requiredString(arguments, "source"), optionalString(arguments, "type", "MOD"),
                        requiredString(arguments, "project_id"), requiredString(arguments, "version_id"))));
        tools.add(tool("install_local_addon", "[L2] Copies a local mod through XYML ModManager.",
                schema(Map.of("instance_id", stringSchema("Instance identifier"), "path", stringSchema("Local mod path")),
                        List.of("instance_id", "path")),
                arguments -> service().installLocalAddon(requiredString(arguments, "instance_id"),
                        requiredString(arguments, "path"))));
        tools.add(tool("enable_mod", "[L2] Enables a mod by removing its .disabled suffix.",
                schema(Map.of("instance_id", stringSchema("Instance identifier"), "path", stringSchema("Mod path")),
                        List.of("instance_id", "path")),
                arguments -> Map.of("path", service().enableMod(requiredString(arguments, "instance_id"),
                        requiredString(arguments, "path")))));
        tools.add(tool("disable_mod", "[L2] Disables a mod by adding its .disabled suffix.",
                schema(Map.of("instance_id", stringSchema("Instance identifier"), "path", stringSchema("Mod path")),
                        List.of("instance_id", "path")),
                arguments -> Map.of("path", service().disableMod(requiredString(arguments, "instance_id"),
                        requiredString(arguments, "path")))));
        tools.add(tool("remove_mods", "[L2] High-impact deletion of selected mods; requires confirmed=true.",
                confirmedSchema(Map.of("instance_id", stringSchema("Instance identifier"),
                        "paths", Map.of("type", "array", "items", stringSchema("Mod path"))),
                        List.of("instance_id", "paths")),
                arguments -> Map.of("removed", service().removeMods(requiredString(arguments, "instance_id"),
                        requiredStrings(arguments, "paths")))));

        tools.add(tool("install_game_version", "[L3] High-impact game download; requires confirmed=true.",
                confirmedSchema(Map.of("instance_id", stringSchema("Existing instance identifier"),
                        "game_version", stringSchema("Game version")), List.of("instance_id", "game_version")),
                arguments -> service().installGameVersion(requiredString(arguments, "instance_id"),
                        requiredString(arguments, "game_version"))));
        tools.add(tool("install_modloader", "[L3] High-impact ModLoader installation; requires confirmed=true.",
                confirmedSchema(Map.of("instance_id", stringSchema("Existing instance identifier"),
                        "game_version", stringSchema("Game version"), "loader", stringSchema("Loader identifier"),
                        "loader_version", stringSchema("Loader version")),
                        List.of("instance_id", "game_version", "loader", "loader_version")),
                arguments -> service().installModloader(requiredString(arguments, "instance_id"),
                        requiredString(arguments, "game_version"), requiredString(arguments, "loader"),
                        requiredString(arguments, "loader_version"))));
        tools.add(tool("create_instance", "[L3] High-impact new instance creation and download; requires confirmed=true.",
                confirmedSchema(Map.of("instance_id", stringSchema("New instance identifier"),
                        "game_version", stringSchema("Game version"), "loader", nullableStringSchema("Loader identifier"),
                        "loader_version", nullableStringSchema("Loader version")), List.of("instance_id", "game_version")),
                arguments -> service().createInstance(requiredString(arguments, "instance_id"),
                        requiredString(arguments, "game_version"), optionalString(arguments, "loader"),
                        optionalString(arguments, "loader_version"))));
        tools.add(tool("install_local_modpack", "[L3] High-impact local modpack import; requires confirmed=true.",
                confirmedSchema(Map.of("instance_id", stringSchema("Target/new instance identifier"),
                        "path", stringSchema("Local zip or mrpack path")), List.of("instance_id", "path")),
                arguments -> service().installLocalModpack(requiredString(arguments, "instance_id"),
                        requiredString(arguments, "path"))));
        tools.add(tool("launch_game", "[L3] High-impact background game launch; requires confirmed=true.",
                confirmedSchema(Map.of("instance_id", stringSchema("Instance identifier")), List.of("instance_id")),
                arguments -> service().launchGame(requiredString(arguments, "instance_id"))));
        tools.add(tool("stop_game", "[L3] High-impact termination of the tracked game process; requires confirmed=true.",
                confirmedSchema(Map.of("instance_id", stringSchema("Instance identifier")), List.of("instance_id")),
                arguments -> service().stopGame(requiredString(arguments, "instance_id"))));
        tools.add(tool("get_launch_status", "[L3] Process status query for a launch test; requires confirmed=true.",
                confirmedSchema(Map.of("instance_id", stringSchema("Instance identifier")), List.of("instance_id")),
                arguments -> service().getLaunchStatus(requiredString(arguments, "instance_id"))));
        return List.copyOf(tools);
    }

    /// Returns templates for latest logs and individual crash reports.
    ///
    /// @return immutable resource template specifications
    public @Unmodifiable List<McpServerFeatures.SyncResourceTemplateSpecification> resourceTemplateSpecifications() {
        return List.of(
                resourceTemplate("xyml://instances/{instance_id}/logs/latest.log", "latest_log",
                        "Latest game log for an XYML instance", "text/plain"),
                resourceTemplate("xyml://instances/{instance_id}/crash-reports/", "latest_crash_report",
                        "Latest crash report for an XYML instance", "text/plain"));
    }

    /// Creates a schema-only tool specification and centralizes risk confirmation handling.
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
    private McpSchema.CallToolResult invoke(
            String name,
            Operation operation,
            McpSchema.CallToolRequest request) {
        Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
        try {
            if (CONFIRMATION_TOOLS.contains(name) && !optionalBoolean(arguments, "confirmed", false)) {
                throw new IllegalArgumentException("Tool " + name + " requires confirmed=true");
            }
            Map<String, Object> result = operation.run(arguments);
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

    /// Returns a compact text representation for MCP clients that only inspect content.
    private static String toJsonText(Map<String, Object> result) {
        return result.toString();
    }

    /// Creates one parameterized resource template specification.
    private McpServerFeatures.SyncResourceTemplateSpecification resourceTemplate(
            String uriTemplate, String name, String description, String mimeType) {
        McpSchema.ResourceTemplate resource = McpSchema.ResourceTemplate.builder(uriTemplate, name)
                .description(description).mimeType(mimeType).build();
        return new McpServerFeatures.SyncResourceTemplateSpecification(resource,
                (exchange, request) -> {
                    try {
                        Map<String, String> result = service().readResource(request.uri());
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

    /// Returns the configured service or a schema-only mode error.
    private XYMLMcpService service() {
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

    /// Adds the confirmation property and marks it required for a high-impact tool.
    private static Map<String, Object> confirmedSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> expanded = new LinkedHashMap<>(properties);
        expanded.put("confirmed", Map.of("type", "boolean", "description",
                "Must be true after the user explicitly confirms this high-impact operation"));
        List<String> expandedRequired = new ArrayList<>(required);
        expandedRequired.add("confirmed");
        return schema(expanded, expandedRequired);
    }

    /// Creates a string property schema.
    private static Map<String, Object> stringSchema(String description) {
        return Map.of("type", "string", "description", description);
    }

    /// Creates a nullable string property schema for omitted optional arguments.
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

    /// Reads an optional string argument, preserving null when omitted.
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

    /// Reads an optional string argument with a fallback.
    private static String optionalString(Map<String, Object> arguments, String name, String fallback) {
        @Nullable String value = optionalString(arguments, name);
        return value == null || value.isBlank() ? fallback : value;
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
    private static List<String> requiredStrings(Map<String, Object> arguments, String name) {
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
    private interface Operation {

        /// Executes an operation.
        ///
        /// @param arguments decoded JSON arguments
        /// @return structured result
        /// @throws Exception when the underlying XYML operation fails
        Map<String, Object> run(Map<String, Object> arguments) throws Exception;
    }
}
