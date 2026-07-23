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
import space.minecraftstl.xyml.ui.swing.page.accounts.AccountsStrings;
import space.minecraftstl.xyml.ui.swing.page.downloads.GameVersionCatalogStatusStrings;
import space.minecraftstl.xyml.ui.swing.page.downloads.GameVersionCatalogStrings;
import space.minecraftstl.xyml.ui.swing.page.home.HomeStatusStrings;
import space.minecraftstl.xyml.ui.swing.page.home.HomeStrings;
import space.minecraftstl.xyml.ui.swing.page.instances.InstancesStrings;
import space.minecraftstl.xyml.ui.swing.page.instances.RepositoryInstancesStatusStrings;
import space.minecraftstl.xyml.ui.swing.page.settings.AppearanceSettingsStrings;
import space.minecraftstl.xyml.ui.swing.shell.ShellPagePresentations;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;

import java.time.Duration;
import java.util.Objects;

/// Groups startup-selected text and timing without inventing composition-level defaults.
///
/// @param windowTitle operating-system window title
/// @param shellPages localized top-level navigation presentations
/// @param home localized home-page controls
/// @param homeStatus localized home readiness states
/// @param instances localized installed-instance controls
/// @param instancesStatus localized repository states and fallbacks
/// @param gameVersions localized game-version catalog controls
/// @param gameVersionsStatus localized game-version catalog lifecycle states
/// @param accounts localized account-page controls
/// @param appearance localized appearance-settings controls
/// @param pageTransitionDuration non-negative page transition duration selected by startup policy
/// @param taskProgress localized task-progress controls and lifecycle states
/// @param taskProgressAnimationDuration non-negative progress animation duration selected by startup policy
@NotNullByDefault
public record SwingApplicationPresentation(
        String windowTitle,
        ShellPagePresentations shellPages,
        HomeStrings home,
        HomeStatusStrings homeStatus,
        InstancesStrings instances,
        RepositoryInstancesStatusStrings instancesStatus,
        GameVersionCatalogStrings gameVersions,
        GameVersionCatalogStatusStrings gameVersionsStatus,
        AccountsStrings accounts,
        AppearanceSettingsStrings appearance,
        Duration pageTransitionDuration,
        TaskProgressStrings taskProgress,
        Duration taskProgressAnimationDuration) {
    /// Validates all localized text groups and the explicit timing policy.
    public SwingApplicationPresentation {
        Objects.requireNonNull(windowTitle, "windowTitle");
        Objects.requireNonNull(shellPages, "shellPages");
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(homeStatus, "homeStatus");
        Objects.requireNonNull(instances, "instances");
        Objects.requireNonNull(instancesStatus, "instancesStatus");
        Objects.requireNonNull(gameVersions, "gameVersions");
        Objects.requireNonNull(gameVersionsStatus, "gameVersionsStatus");
        Objects.requireNonNull(accounts, "accounts");
        Objects.requireNonNull(appearance, "appearance");
        Objects.requireNonNull(pageTransitionDuration, "pageTransitionDuration");
        Objects.requireNonNull(taskProgress, "taskProgress");
        Objects.requireNonNull(taskProgressAnimationDuration, "taskProgressAnimationDuration");
        if (windowTitle.isBlank()) {
            throw new IllegalArgumentException("windowTitle must not be blank");
        }
        if (pageTransitionDuration.isNegative()) {
            throw new IllegalArgumentException("pageTransitionDuration must not be negative");
        }
        if (taskProgressAnimationDuration.isNegative()) {
            throw new IllegalArgumentException("taskProgressAnimationDuration must not be negative");
        }
    }
}
