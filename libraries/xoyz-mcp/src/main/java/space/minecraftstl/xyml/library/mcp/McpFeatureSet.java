// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.library.mcp;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Selects the optional MCP capabilities and their providers.
///
/// A null provider omits the corresponding capability from initialization and rejects its methods.
///
/// @param tools tool provider, or `null` when tools are not exposed
/// @param resources resource provider, or `null` when resources are not exposed
/// @param prompts prompt provider, or `null` when prompts are not exposed
@NotNullByDefault
public record McpFeatureSet(
        @Nullable McpToolProvider tools,
        @Nullable McpResourceProvider resources,
        @Nullable McpPromptProvider prompts) {
}
