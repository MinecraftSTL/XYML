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

import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Supplies tool metadata and calls to an MCP server.
@NotNullByDefault
public interface McpToolProvider {
    /// Returns every exposed tool in stable declaration order.
    ///
    /// @return immutable tool definitions
    @Unmodifiable List<ToolDefinition> toolDefinitions();

    /// Invokes an exposed tool.
    ///
    /// @param name requested tool name
    /// @param arguments immutable decoded arguments
    /// @return tool invocation result
    ToolCallResult call(String name, @Unmodifiable Map<String, @Nullable Object> arguments);

    /// Describes one MCP tool.
    ///
    /// @param name tool name
    /// @param description human-readable description
    /// @param inputSchema immutable JSON Schema object
    @NotNullByDefault
    record ToolDefinition(
            String name,
            String description,
            @Unmodifiable Map<String, @Nullable Object> inputSchema) {
        /// Validates and snapshots one tool definition.
        public ToolDefinition {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(description, "description");
            inputSchema = McpJsonValues.snapshotMap(inputSchema);
        }
    }

    /// Contains structured tool output and its MCP error state.
    ///
    /// @param error whether the tool completed with an application-level error
    /// @param structuredContent immutable JSON-compatible structured content
    @NotNullByDefault
    record ToolCallResult(boolean error, @Unmodifiable Map<String, @Nullable Object> structuredContent) {
        /// Validates and snapshots one tool result.
        public ToolCallResult {
            structuredContent = McpJsonValues.snapshotMap(structuredContent);
        }

        /// Creates a successful tool result.
        ///
        /// @param content structured result content
        /// @return successful result
        public static ToolCallResult success(Map<String, @Nullable Object> content) {
            return new ToolCallResult(false, content);
        }

        /// Creates a failed tool result with a stable provider-neutral shape.
        ///
        /// @param tool requested tool name
        /// @param message failure description
        /// @return failed result
        public static ToolCallResult error(String tool, String message) {
            return new ToolCallResult(true, Map.of("tool", tool, "error", message));
        }
    }
}
