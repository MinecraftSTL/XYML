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

import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Registers the small set of launcher prompt templates exposed by the MCP endpoint.
@NotNullByDefault
public final class XYMLMcpPromptRegistry {

    /// Prompt definitions in stable declaration order.
    private static final @Unmodifiable List<PromptDefinition> PROMPTS = List.of(
            new PromptDefinition("diagnose_crash", "Inspect an instance crash and its reports.",
                    List.of(new PromptArgument("instance_id", "Instance identifier", true))));

    /// Returns every prompt definition exposed by XYML.
    ///
    /// @return immutable prompt definitions
    public @Unmodifiable List<PromptDefinition> promptDefinitions() {
        return PROMPTS;
    }

    /// Expands a named prompt with its arguments.
    ///
    /// @param name prompt name
    /// @param arguments prompt arguments
    /// @return immutable MCP prompt result
    public @Unmodifiable Map<String, Object> getPrompt(String name, Map<String, Object> arguments) {
        if (!"diagnose_crash".equals(name)) {
            throw new IllegalArgumentException("Unknown prompt: " + name);
        }
        @Nullable Object rawInstanceId = arguments.get("instance_id");
        if (!(rawInstanceId instanceof String instanceId) || instanceId.isBlank()) {
            throw new IllegalArgumentException("instance_id must be a non-blank string");
        }
        String text = "Inspect the latest log and crash reports for instance '" + instanceId
                + "', then summarize the cause and applicable launcher changes.";
        return Map.of(
                "description", "Instance crash diagnosis",
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", Map.of("type", "text", "text", text))));
    }

    /// Describes a prompt exposed through `prompts/list`.
    ///
    /// @param name prompt name
    /// @param description human-readable description
    /// @param arguments immutable prompt arguments
    @NotNullByDefault
    public record PromptDefinition(
            String name, String description, @Unmodifiable List<PromptArgument> arguments) {
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
    public record PromptArgument(String name, String description, boolean required) {
        /// Validates one prompt argument.
        public PromptArgument {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(description, "description");
        }
    }
}
