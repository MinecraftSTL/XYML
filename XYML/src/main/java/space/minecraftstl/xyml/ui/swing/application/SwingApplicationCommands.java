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
package space.minecraftstl.xyml.ui.swing.application;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.ui.swing.page.home.HomeLaunchCommand;

import java.util.Objects;

/// Supplies launcher workflows that the transitional Swing composition does not own.
///
/// The startup layer decides how account creation and launch workflows are implemented. Instance
/// creation and management are owned by the Swing composition and are intentionally absent from
/// this legacy workflow boundary.
///
/// @param addAccountCommand command that opens the supported add-account workflow
/// @param launchCommand command that starts a session from captured stable selection identifiers
@NotNullByDefault
public record SwingApplicationCommands(
        Runnable addAccountCommand,
        HomeLaunchCommand launchCommand) {
    /// Validates every startup-owned workflow boundary.
    public SwingApplicationCommands {
        Objects.requireNonNull(addAccountCommand, "addAccountCommand");
        Objects.requireNonNull(launchCommand, "launchCommand");
    }
}
