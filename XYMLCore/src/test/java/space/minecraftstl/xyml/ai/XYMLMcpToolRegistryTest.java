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

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

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

    /// Ensures every approved tool is present exactly once and resource templates are advertised.
    @Test
    public void registersCompleteToolSurface() {
        XYMLMcpToolRegistry registry = new XYMLMcpToolRegistry(null);
        List<XYMLMcpToolRegistry.ToolDefinition> definitions = registry.toolDefinitions();
        Set<String> names = definitions.stream().map(XYMLMcpToolRegistry.ToolDefinition::name)
                .collect(Collectors.toSet());
        assertEquals(17, definitions.size());
        assertEquals(17, names.size());
        assertTrue(names.containsAll(Set.of("list_instances", "analyze_crash", "set_java_version",
                "enable_mod", "remove_mods", "launch_game", "get_launch_status")));
        assertFalse(names.contains("search_addons"));
        assertFalse(names.contains("create_instance"));
        assertEquals(3, registry.resourceTemplateDefinitions().size());
        assertEquals("xyml://instances/{instance_id}/logs/latest.log",
                registry.resourceTemplateDefinitions().get(0).uriTemplate());
        assertEquals("xyml://instances/{instance_id}/crash-reports/{report_name}",
                registry.resourceTemplateDefinitions().get(2).uriTemplate());
    }

    /// Ensures high-impact tools require a boolean confirmation in their JSON schema.
    @Test
    public void marksHighImpactToolsAsConfirmed() {
        XYMLMcpToolRegistry registry = new XYMLMcpToolRegistry(null);
        List<XYMLMcpToolRegistry.ToolDefinition> tools = registry.toolDefinitions().stream()
                .filter(definition -> definition.name().equals("launch_game")
                        || definition.name().equals("remove_mods")
                        || definition.name().equals("get_launch_status"))
                .toList();
        for (XYMLMcpToolRegistry.ToolDefinition tool : tools) {
            Map<String, Object> schema = tool.inputSchema();
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) schema.get("required");
            assertTrue(required.contains("confirmed"));
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
            @SuppressWarnings("unchecked")
            Map<String, Object> confirmation = (Map<String, Object>) properties.get("confirmed");
            assertEquals("boolean", confirmation.get("type"));
        }
    }

    /// Ensures confirmation rejection never invokes an absent launcher service.
    @Test
    public void rejectsUnconfirmedLaunch() {
        XYMLMcpToolRegistry registry = new XYMLMcpToolRegistry(null);
        XYMLMcpToolRegistry.ToolCallResult result = registry.call(
                "launch_game", Map.of("instance_id", "demo", "confirmed", false));
        assertTrue(result.error());
        assertEquals("launch_game", result.structuredContent().get("tool"));
        assertTrue(String.valueOf(result.structuredContent().get("error")).contains("confirmed=true"));
    }
}
