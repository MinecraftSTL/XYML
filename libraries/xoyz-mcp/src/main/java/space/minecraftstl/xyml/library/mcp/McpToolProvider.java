// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
