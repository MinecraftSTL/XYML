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
package space.minecraftstl.xyml.ui.swing.page.home;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Localizable visible and accessible text for the launcher home page.
///
/// @param pageTitle page heading
/// @param accountLabel account-selection field label
/// @param missingAccountLabel value displayed when no account exists
/// @param instanceLabel instance-selection field label
/// @param missingInstanceLabel value displayed when no instance exists
/// @param addInstanceAction new-instance command label
/// @param exportLaunchScriptAction standalone launch-script command label
/// @param launchAction normal launch command label
/// @param launchingAction in-progress launch command label
/// @param backToSelectionsAction command label for returning from task progress to launch selections
@NotNullByDefault
public record HomeStrings(
        String pageTitle,
        String accountLabel,
        String missingAccountLabel,
        String instanceLabel,
        String missingInstanceLabel,
        String addInstanceAction,
        String exportLaunchScriptAction,
        String launchAction,
        String launchingAction,
        String backToSelectionsAction) {
    /// Validates localized home-page text.
    public HomeStrings {
        Objects.requireNonNull(pageTitle, "pageTitle");
        Objects.requireNonNull(accountLabel, "accountLabel");
        Objects.requireNonNull(missingAccountLabel, "missingAccountLabel");
        Objects.requireNonNull(instanceLabel, "instanceLabel");
        Objects.requireNonNull(missingInstanceLabel, "missingInstanceLabel");
        Objects.requireNonNull(addInstanceAction, "addInstanceAction");
        Objects.requireNonNull(exportLaunchScriptAction, "exportLaunchScriptAction");
        Objects.requireNonNull(launchAction, "launchAction");
        Objects.requireNonNull(launchingAction, "launchingAction");
        Objects.requireNonNull(backToSelectionsAction, "backToSelectionsAction");
    }
}
