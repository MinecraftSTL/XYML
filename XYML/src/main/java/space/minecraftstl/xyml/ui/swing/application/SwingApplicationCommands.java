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
import java.util.function.Consumer;

/// Supplies launcher workflows that the transitional Swing composition does not own.
///
/// The startup layer decides how installation, account creation, instance management, and launch
/// workflows are implemented. This boundary prevents the composition from reaching into unknown
/// legacy dialogs while those workflows are being migrated.
///
/// @param addAccountCommand command that opens the supported add-account workflow
/// @param addInstanceCommand command that opens the supported add-instance workflow
/// @param manageInstanceCommand command that manages a stable selected instance identifier
/// @param launchCommand command that starts a session from captured stable selection identifiers
@NotNullByDefault
public record SwingApplicationCommands(
        Runnable addAccountCommand,
        Runnable addInstanceCommand,
        Consumer<String> manageInstanceCommand,
        HomeLaunchCommand launchCommand) {
    /// Validates every startup-owned workflow boundary.
    public SwingApplicationCommands {
        Objects.requireNonNull(addAccountCommand, "addAccountCommand");
        Objects.requireNonNull(addInstanceCommand, "addInstanceCommand");
        Objects.requireNonNull(manageInstanceCommand, "manageInstanceCommand");
        Objects.requireNonNull(launchCommand, "launchCommand");
    }
}
