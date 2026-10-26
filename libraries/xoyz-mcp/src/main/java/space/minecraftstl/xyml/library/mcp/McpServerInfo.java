// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.library.mcp;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Map;
import java.util.Objects;

/// Identifies an MCP server during initialization.
///
/// @param name stable server name
/// @param version server implementation version
@NotNullByDefault
public record McpServerInfo(String name, String version) {
    /// Validates one server identity.
    public McpServerInfo {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
    }

    /// Returns this identity in the MCP initialization shape.
    ///
    /// @return immutable server information
    public @Unmodifiable Map<String, String> toMap() {
        return Map.of("name", name, "version", version);
    }
}
