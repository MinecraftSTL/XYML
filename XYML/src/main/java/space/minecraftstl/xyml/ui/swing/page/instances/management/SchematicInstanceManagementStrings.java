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
package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Localizable outer-shell text for one schematic instance-management view.
///
/// @param returnAction command label returning to the instance list
/// @param returnTooltip accessible description of the return command
/// @param loadingText schematic-root resolution text
/// @param failureTitle schematic-root resolution failure heading
/// @param retryAction failed-resolution retry command label
@NotNullByDefault
public record SchematicInstanceManagementStrings(
        String returnAction,
        String returnTooltip,
        String loadingText,
        String failureTitle,
        String retryAction) {
    /// Validates every injected localized value.
    public SchematicInstanceManagementStrings {
        Objects.requireNonNull(returnAction, "returnAction");
        Objects.requireNonNull(returnTooltip, "returnTooltip");
        Objects.requireNonNull(loadingText, "loadingText");
        Objects.requireNonNull(failureTitle, "failureTitle");
        Objects.requireNonNull(retryAction, "retryAction");
    }
}
