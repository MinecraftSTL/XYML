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
