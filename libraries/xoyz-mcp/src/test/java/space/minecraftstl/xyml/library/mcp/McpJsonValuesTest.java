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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies ownership of JSON-compatible values accepted by the public provider API.
@NotNullByDefault
public final class McpJsonValuesTest {
    /// Verifies tool definitions retain immutable deep snapshots of schemas.
    @Test
    void toolDefinitionSnapshotsNestedSchema() {
        List<@Nullable Object> sourceTypes = new ArrayList<>(List.of("string"));
        Map<String, @Nullable Object> sourceProperties = new LinkedHashMap<>();
        sourceProperties.put("type", sourceTypes);
        Map<String, @Nullable Object> sourceSchema = new LinkedHashMap<>();
        sourceSchema.put("properties", sourceProperties);

        McpToolProvider.ToolDefinition definition =
                new McpToolProvider.ToolDefinition("example", "Example tool", sourceSchema);
        sourceTypes.add("null");
        sourceProperties.put("late", true);

        assertEquals(Map.of("properties", Map.of("type", List.of("string"))), definition.inputSchema());
        @Unmodifiable Map<@Nullable ?, @Nullable ?> properties =
                requireMap(definition.inputSchema().get("properties"));
        @Unmodifiable List<@Nullable ?> types = requireList(properties.get("type"));
        assertThrows(UnsupportedOperationException.class, properties::clear);
        assertThrows(UnsupportedOperationException.class, types::clear);
    }

    /// Verifies tool results retain immutable deep snapshots of structured content.
    @Test
    void toolResultSnapshotsNestedContent() {
        List<@Nullable Object> sourceItems = new ArrayList<>(List.of("first"));
        Map<String, @Nullable Object> sourceContent = new LinkedHashMap<>();
        sourceContent.put("items", sourceItems);

        McpToolProvider.ToolCallResult result = McpToolProvider.ToolCallResult.success(sourceContent);
        sourceItems.add("second");

        assertEquals(Map.of("items", List.of("first")), result.structuredContent());
        @Unmodifiable List<@Nullable ?> items = requireList(result.structuredContent().get("items"));
        assertThrows(UnsupportedOperationException.class, items::clear);
    }

    /// Verifies unsupported mutable values and non-finite numbers are rejected as non-JSON scalars.
    @Test
    void rejectsUnsupportedOrNonJsonScalars() {
        assertThrows(IllegalArgumentException.class,
                () -> McpToolProvider.ToolCallResult.success(Map.of("mutable", new StringBuilder("value"))));
        assertThrows(IllegalArgumentException.class,
                () -> McpToolProvider.ToolCallResult.success(Map.of("mutable_number", new AtomicInteger(1))));
        assertThrows(IllegalArgumentException.class,
                () -> McpToolProvider.ToolCallResult.success(Map.of("not_a_number", Double.NaN)));
        assertThrows(IllegalArgumentException.class,
                () -> McpToolProvider.ToolCallResult.success(Map.of("infinite", Float.POSITIVE_INFINITY)));
    }

    /// Requires a nested JSON object.
    ///
    /// @param value candidate value
    /// @return nested object
    private static @Unmodifiable Map<@Nullable ?, @Nullable ?> requireMap(@Nullable Object value) {
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        throw new AssertionError("Expected a nested map, got " + value);
    }

    /// Requires a nested JSON array.
    ///
    /// @param value candidate value
    /// @return nested array
    private static @Unmodifiable List<@Nullable ?> requireList(@Nullable Object value) {
        if (value instanceof List<?> list) {
            return list;
        }
        throw new AssertionError("Expected a nested list, got " + value);
    }
}
