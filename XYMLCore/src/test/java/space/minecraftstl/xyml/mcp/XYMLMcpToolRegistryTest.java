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
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.library.mcp.McpPromptProvider.PromptDefinition;
import space.minecraftstl.xyml.library.mcp.McpToolProvider.ToolDefinition;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the SDK-independent MCP contract without a configured XYML workspace.
@NotNullByDefault
public final class XYMLMcpToolRegistryTest {

    /// Ensures every approved tool is present exactly once.
    @Test
    public void registersCompleteToolSurface() {
        XYMLMcpToolRegistry registry = new XYMLMcpToolRegistry(null);
        List<ToolDefinition> definitions = registry.toolDefinitions();
        Set<String> names = definitions.stream().map(ToolDefinition::name)
                .collect(Collectors.toSet());
        assertEquals(19, definitions.size());
        assertEquals(19, names.size());
        assertEquals(Set.of("list_instances", "get_instance_settings", "rename_instance", "duplicate_instance",
                "delete_instance", "get_mods_directory", "analyze_crash", "list_java_runtimes", "list_local_mods",
                "set_java_version", "set_memory", "set_jvm_options", "set_window_options", "enable_mod",
                "disable_mod", "remove_mods", "launch_game", "stop_game", "get_launch_status"), names);
        assertFalse(names.contains("get_logs"));
        assertFalse(names.contains("search_addons"));
        assertFalse(names.contains("create_instance"));
    }

    /// Ensures no tool schema exposes the removed MCP-level confirmation parameter.
    @Test
    public void omitsConfirmationFromAllToolSchemas() {
        XYMLMcpToolRegistry registry = new XYMLMcpToolRegistry(null);
        for (ToolDefinition tool : registry.toolDefinitions()) {
            Map<String, Object> schema = tool.inputSchema();
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
            assertFalse(properties.containsKey("confirmed"), tool.name());
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) schema.getOrDefault("required", List.of());
            assertFalse(required.contains("confirmed"), tool.name());
        }
    }

    /// Ensures instance lifecycle and launch tools dispatch without a confirmation argument.
    @Test
    public void dispatchesInstanceLifecycleAndLaunchTools() {
        Map<String, List<Object>> calls = new HashMap<>();
        XYMLMcpToolRegistry registry = new XYMLMcpToolRegistry(recordingService(calls));

        assertFalse(registry.call("rename_instance", Map.of(
                "source_instance_id", "old", "destination_instance_id", "new")).error());
        assertEquals(List.of("old", "new"), calls.get("renameInstance"));

        assertFalse(registry.call("duplicate_instance", Map.of(
                "source_instance_id", "old", "destination_instance_id", "copy", "copy_saves", true)).error());
        assertEquals(List.of("old", "copy", true), calls.get("duplicateInstance"));

        assertFalse(registry.call("delete_instance", Map.of("instance_id", "old")).error());
        assertEquals(List.of("old"), calls.get("deleteInstance"));

        assertFalse(registry.call("remove_mods", Map.of(
                "instance_id", "old", "paths", List.of("example.jar"))).error());
        assertEquals(List.of("old", List.of("example.jar")), calls.get("removeMods"));

        assertFalse(registry.call("launch_game", Map.of("instance_id", "old")).error());
        assertEquals(List.of("old"), calls.get("launchGame"));
        assertFalse(registry.call("stop_game", Map.of("instance_id", "old")).error());
        assertEquals(List.of("old"), calls.get("stopGame"));
        assertFalse(registry.call("get_launch_status", Map.of("instance_id", "old")).error());
        assertEquals(List.of("old"), calls.get("getLaunchStatus"));
    }

    /// Ensures each instance setting tool passes its inherit flag to the launcher service.
    @Test
    public void passesInheritSettingArguments() {
        Map<String, List<Object>> calls = new HashMap<>();
        XYMLMcpToolRegistry registry = new XYMLMcpToolRegistry(recordingService(calls));

        assertFalse(registry.call("set_java_version", Map.of(
                "instance_id", "demo", "java_version", "21", "java_path", "java.exe", "inherit", true)).error());
        assertEquals(List.of("demo", "21", "java.exe", true), calls.get("setJavaVersion"));

        assertFalse(registry.call("set_memory", Map.of(
                "instance_id", "demo", "min_memory_mb", 512, "max_memory_mb", 4096, "inherit", true)).error());
        assertEquals(List.of("demo", 512, 4096, true), calls.get("setMemory"));

        assertFalse(registry.call("set_jvm_options", Map.of(
                "instance_id", "demo", "options", "-Xmx4G", "inherit", true)).error());
        assertEquals(List.of("demo", "-Xmx4G", true), calls.get("setJvmOptions"));

        assertFalse(registry.call("set_window_options", Map.of(
                "instance_id", "demo", "width", 1280, "height", 720, "fullscreen", false, "inherit", true)).error());
        assertEquals(List.of("demo", 1280, 720, false, true), calls.get("setWindowOptions"));
    }

    /// Ensures numeric JSON arguments are not silently truncated before reaching launcher settings.
    @Test
    public void rejectsFractionalIntegerArguments() {
        Map<String, List<Object>> calls = new HashMap<>();
        XYMLMcpToolRegistry registry = new XYMLMcpToolRegistry(recordingService(calls));

        assertTrue(registry.call("set_memory", Map.of(
                "instance_id", "demo", "max_memory_mb", 1024.5)).error());
        assertFalse(calls.containsKey("setMemory"));
    }

    /// Creates a proxy service that records operation arguments and returns typed placeholder results.
    ///
    /// @param calls operation argument records
    /// @return recording service proxy
    private static XYMLMcpOperations recordingService(Map<String, List<Object>> calls) {
        return (XYMLMcpOperations) Proxy.newProxyInstance(
                XYMLMcpOperations.class.getClassLoader(), new Class<?>[]{XYMLMcpOperations.class},
                (proxy, method, arguments) -> {
                    calls.put(method.getName(), arguments == null
                            ? List.of() : new ArrayList<>(Arrays.asList(arguments)));
                    return switch (method.getName()) {
                        case "renameInstance", "duplicateInstance", "deleteInstance", "removeMods", "setJavaVersion",
                                "setMemory", "setJvmOptions", "setWindowOptions", "stopGame", "getLaunchStatus" -> Map.of(
                                "operation", method.getName());
                        case "launchGame" -> Map.of("operation", method.getName());
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }

    /// Ensures the resource and prompt registries expose protocol-neutral definitions without a repository.
    @Test
    public void registersResourceAndPromptInterfaces() throws Exception {
        XYMLMcpOperations operations = (XYMLMcpOperations) Proxy.newProxyInstance(
                XYMLMcpOperations.class.getClassLoader(), new Class<?>[]{XYMLMcpOperations.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "listInstances" -> List.of(Map.of("id", "demo"));
                    case "readResource" -> Map.of("uri", "xyml://demo", "mime_type", "text/plain", "text", "ok");
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        XYMLMcpResourceRegistry resources = new XYMLMcpResourceRegistry(operations);
        assertEquals(3, resources.resourceTemplateDefinitions().size());
        assertEquals(2, resources.resourceDefinitions().size());
        assertEquals("ok", resources.readResource("xyml://demo").text());

        XYMLMcpPromptRegistry prompts = new XYMLMcpPromptRegistry();
        assertEquals(1, prompts.promptDefinitions().size());
        PromptDefinition definition = prompts.promptDefinitions().get(0);
        assertEquals("diagnose_crash", definition.name());
        assertEquals("检查实例崩溃及其报告。", definition.description());
        assertEquals("instance_id", definition.arguments().get(0).name());
        assertEquals("实例标识符", definition.arguments().get(0).description());
        Map<String, Object> result = prompts.getPrompt("diagnose_crash", Map.of("instance_id", "demo"));
        assertTrue(result.containsKey("messages"));
        assertEquals("实例崩溃诊断", result.get("description"));
        String messages = String.valueOf(result.get("messages"));
        assertTrue(messages.contains("实例“demo”"));
        assertTrue(messages.contains("崩溃报告目录资源"));
        assertTrue(messages.contains("crash_report_path"));
    }
}
