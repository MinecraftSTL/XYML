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
package space.minecraftstl.xyml.ui.swing.shell;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.ui.swing.page.accounts.AccountsModel;
import space.minecraftstl.xyml.ui.swing.page.home.HomeModel;
import space.minecraftstl.xyml.ui.swing.page.instances.InstancesModel;
import space.minecraftstl.xyml.ui.swing.page.settings.GameDirectoryManagementService;

import java.util.Objects;

/// Non-owning model references used by persistent title-bar workflow controls.
///
/// The application composition owns and closes these models. Shell controls own only their
/// subscriptions and viewport requests.
///
/// @param home launcher selection and launch state
/// @param instances selected-directory lazy instance source
/// @param accounts lazy account source and selection commands
/// @param gameDirectories configured instance/version-folder source and selection commands
/// @param recentSelections persistent ordering used only by compact shell selectors
@NotNullByDefault
public record ShellToolbarModels(
        HomeModel home,
        InstancesModel instances,
        AccountsModel accounts,
        GameDirectoryManagementService gameDirectories,
        ShellRecentSelections recentSelections) {
    /// Validates every required non-owning model reference.
    public ShellToolbarModels {
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(instances, "instances");
        Objects.requireNonNull(accounts, "accounts");
        Objects.requireNonNull(gameDirectories, "gameDirectories");
        Objects.requireNonNull(recentSelections, "recentSelections");
    }
}
