// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.library.mcp;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Supplies prompt metadata and expanded messages to an MCP server.
@NotNullByDefault
public interface McpPromptProvider {
    /// Returns every exposed prompt in stable declaration order.
    ///
    /// @return immutable prompt definitions
    @Unmodifiable List<PromptDefinition> promptDefinitions();

    /// Expands one prompt with decoded arguments.
    ///
    /// @param name prompt name
    /// @param arguments immutable decoded arguments
    /// @return immutable MCP prompt result
    @Unmodifiable Map<String, @Nullable Object> getPrompt(
            String name,
            @Unmodifiable Map<String, @Nullable Object> arguments);

    /// Describes one MCP prompt.
    ///
    /// @param name prompt name
    /// @param description human-readable description
    /// @param arguments immutable prompt arguments
    @NotNullByDefault
    record PromptDefinition(
            String name,
            String description,
            @Unmodifiable List<PromptArgument> arguments) {
        /// Validates and snapshots one prompt definition.
        public PromptDefinition {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(description, "description");
            arguments = List.copyOf(arguments);
        }
    }

    /// Describes one prompt argument.
    ///
    /// @param name argument name
    /// @param description human-readable argument description
    /// @param required whether the argument is required
    @NotNullByDefault
    record PromptArgument(String name, String description, boolean required) {
        /// Validates one prompt argument.
        public PromptArgument {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(description, "description");
        }
    }
}
