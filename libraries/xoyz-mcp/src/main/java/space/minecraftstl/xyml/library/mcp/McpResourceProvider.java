// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.library.mcp;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Objects;

/// Supplies resource metadata and contents to an MCP server.
@NotNullByDefault
public interface McpResourceProvider {
    /// Returns concrete resources currently available from the server.
    ///
    /// @return immutable resource definitions
    /// @throws Exception when resource metadata cannot be produced
    @Unmodifiable List<ResourceDefinition> resourceDefinitions() throws Exception;

    /// Returns templates for parameterized resources.
    ///
    /// @return immutable resource template definitions
    @Unmodifiable List<ResourceTemplateDefinition> resourceTemplateDefinitions();

    /// Reads one resource.
    ///
    /// @param uri requested resource URI
    /// @return resource contents
    /// @throws Exception when the URI is unsupported or cannot be read
    ResourceReadResult readResource(String uri) throws Exception;

    /// Describes one concrete MCP resource.
    ///
    /// @param uri concrete resource URI
    /// @param name stable resource name
    /// @param description human-readable description
    /// @param mimeType resource MIME type
    @NotNullByDefault
    record ResourceDefinition(String uri, String name, String description, String mimeType) {
        /// Validates one resource definition.
        public ResourceDefinition {
            Objects.requireNonNull(uri, "uri");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(mimeType, "mimeType");
        }
    }

    /// Describes one parameterized MCP resource.
    ///
    /// @param uriTemplate URI template containing placeholders
    /// @param name stable template name
    /// @param description human-readable description
    /// @param mimeType resource MIME type
    @NotNullByDefault
    record ResourceTemplateDefinition(
            String uriTemplate,
            String name,
            String description,
            String mimeType) {
        /// Validates one resource template definition.
        public ResourceTemplateDefinition {
            Objects.requireNonNull(uriTemplate, "uriTemplate");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(mimeType, "mimeType");
        }
    }

    /// Contains the text returned by `resources/read`.
    ///
    /// @param uri resolved resource URI
    /// @param mimeType resource MIME type
    /// @param text text contents
    @NotNullByDefault
    record ResourceReadResult(String uri, String mimeType, String text) {
        /// Validates one resource result.
        public ResourceReadResult {
            Objects.requireNonNull(uri, "uri");
            Objects.requireNonNull(mimeType, "mimeType");
            Objects.requireNonNull(text, "text");
        }
    }
}
