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

import java.util.Objects;

/// Localized text for the optional interface refresh after a live corner-radius change.
///
/// @param promptText explanation shown while the current component trees match the selected radius
/// @param requiredText explanation shown when remaining components may need a refresh
/// @param actionText refresh button label
@NotNullByDefault
public record CornerRadiusRefreshStrings(
        String promptText,
        String requiredText,
        String actionText) {
    /// Validates every localized refresh string.
    public CornerRadiusRefreshStrings {
        Objects.requireNonNull(promptText, "promptText");
        Objects.requireNonNull(requiredText, "requiredText");
        Objects.requireNonNull(actionText, "actionText");
    }
}
