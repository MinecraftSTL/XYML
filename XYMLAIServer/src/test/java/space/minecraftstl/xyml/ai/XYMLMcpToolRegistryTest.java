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

import io.modelcontextprotocol.spec.McpSchema;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the MCP contract independently from a configured XYML workspace.
@NotNullByDefault
public final class XYMLMcpToolRegistryTest {

    /// Ensures every v2 tool is present exactly once and resource templates are advertised.
    @Test
    public void registersCompleteToolSurface() {
        XYMLMcpToolRegistry registry = new XYMLMcpToolRegistry(null);
        List<io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification> specifications =
                registry.toolSpecifications();
        Set<String> names = specifications.stream().map(specification -> specification.tool().name())
                .collect(Collectors.toSet());
        assertEquals(29, specifications.size());
        assertEquals(29, names.size());
        assertTrue(names.containsAll(Set.of("list_instances", "analyze_crash", "search_addons",
                "install_local_addon", "create_instance", "launch_game", "get_launch_status")));
        assertEquals(2, registry.resourceTemplateSpecifications().size());
        assertEquals("xyml://instances/{instance_id}/logs/latest.log",
                registry.resourceTemplateSpecifications().get(0).resourceTemplate().uriTemplate());
    }

    /// Ensures high-impact tools require a boolean confirmation in their JSON schema.
    @Test
    public void marksHighImpactToolsAsConfirmed() {
        XYMLMcpToolRegistry registry = new XYMLMcpToolRegistry(null);
        List<McpSchema.Tool> tools = registry.toolSpecifications().stream()
                .filter(specification -> specification.tool().name().equals("launch_game")
                        || specification.tool().name().equals("remove_mods")
                        || specification.tool().name().equals("get_launch_status"))
                .map(specification -> specification.tool())
                .toList();
        for (McpSchema.Tool tool : tools) {
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

    /// Ensures the confirmation gate returns an MCP error without invoking a launcher service.
    @Test
    public void rejectsUnconfirmedLaunch() {
        XYMLMcpToolRegistry registry = new XYMLMcpToolRegistry(null);
        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder("launch_game")
                .arguments(Map.of("instance_id", "demo", "confirmed", false)).build();
        McpSchema.CallToolResult result = registry.toolSpecifications().stream()
                .filter(specification -> specification.tool().name().equals("launch_game"))
                .findFirst().orElseThrow().callHandler().apply(null, request);
        assertTrue(Boolean.TRUE.equals(result.isError()));
        assertFalse(result.content().isEmpty());
    }
}
