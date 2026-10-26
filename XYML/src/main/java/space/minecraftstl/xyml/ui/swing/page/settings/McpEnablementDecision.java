// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;

/// Supplies the user decision required before a disabled MCP server is enabled.
///
/// Implementations report both confirmation and a permanent warning opt-out. The settings panel applies that result
/// before it changes the enablement switch. The production dialog also persists its opt-out as soon as the user
/// changes the checkbox, so closing or cancelling the dialog cannot roll that preference back.
@FunctionalInterface
@NotNullByDefault
public interface McpEnablementDecision {
    /// Returns the complete confirmation and warning-preference decision.
    ///
    /// @return immutable user decision
    McpEnablementResult confirm();
}
