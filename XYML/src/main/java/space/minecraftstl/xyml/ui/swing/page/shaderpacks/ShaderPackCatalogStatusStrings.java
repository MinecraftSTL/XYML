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
package space.minecraftstl.xyml.ui.swing.page.shaderpacks;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Localized lifecycle and mutation status text for the shader-pack catalog.
///
/// @param idleText text before lazy loading starts
/// @param loadingText text while a scan is active
/// @param readyText text after a successful non-empty scan
/// @param emptyText text after a successful empty scan
/// @param failureText scan failure prefix
/// @param writingText mutation-in-progress text
/// @param writeFailedText mutation failure prefix
@NotNullByDefault
public record ShaderPackCatalogStatusStrings(
        String idleText,
        String loadingText,
        String readyText,
        String emptyText,
        String failureText,
        String writingText,
        String writeFailedText) {
    /// Rejects missing or blank localized status text.
    public ShaderPackCatalogStatusStrings {
        requireText(idleText, "idleText");
        requireText(loadingText, "loadingText");
        requireText(readyText, "readyText");
        requireText(emptyText, "emptyText");
        requireText(failureText, "failureText");
        requireText(writingText, "writingText");
        requireText(writeFailedText, "writeFailedText");
    }

    /// Rejects missing or blank presentation text.
    ///
    /// @param value localized value
    /// @param name record component name
    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
