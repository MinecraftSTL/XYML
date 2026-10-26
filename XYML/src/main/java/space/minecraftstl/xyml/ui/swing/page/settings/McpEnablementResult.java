// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;

/// Captures the complete user decision made by the MCP enablement warning.
///
/// @param confirmed whether the requested enablement may proceed
/// @param suppressFutureWarnings whether later enablement attempts should skip the warning
@NotNullByDefault
public record McpEnablementResult(boolean confirmed, boolean suppressFutureWarnings) {
}
