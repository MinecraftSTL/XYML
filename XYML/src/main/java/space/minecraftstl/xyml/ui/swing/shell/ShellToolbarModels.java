// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
