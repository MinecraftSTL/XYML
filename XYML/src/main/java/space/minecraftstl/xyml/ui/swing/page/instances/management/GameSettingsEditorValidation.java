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
import space.minecraftstl.xyml.game.QuickPlayType;
import space.minecraftstl.xyml.util.ServerAddress;
import space.minecraftstl.xyml.util.io.FileUtils;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;

/// Validates user-controlled game-setting values only when their dependent mode makes them active.
@NotNullByDefault
final class GameSettingsEditorValidation {
    /// Prevents utility instantiation.
    private GameSettingsEditorValidation() {
    }

    /// Validates the multiplayer or singleplayer target selected by the current Quick Play mode.
    ///
    /// @param type selected Quick Play destination
    /// @param multiplayerActive whether the multiplayer value is a direct setting
    /// @param multiplayer multiplayer server address text
    /// @param singleplayerActive whether the singleplayer value is a direct setting
    /// @param singleplayer singleplayer world directory name
    static void validateQuickPlayTargets(
            QuickPlayType type,
            boolean multiplayerActive,
            String multiplayer,
            boolean singleplayerActive,
            String singleplayer) {
        QuickPlayType selectedType = Objects.requireNonNull(type, "type");
        String server = Objects.requireNonNull(multiplayer, "multiplayer").trim();
        if (selectedType == QuickPlayType.MULTIPLAYER && multiplayerActive && !server.isEmpty()) {
            try {
                ServerAddress.parse(server);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Invalid multiplayer server address", exception);
            }
        }
        String world = Objects.requireNonNull(singleplayer, "singleplayer").trim();
        if (selectedType == QuickPlayType.SINGLEPLAYER
                && singleplayerActive
                && !world.isEmpty()
                && !FileUtils.isNameValid(world)) {
            throw new IllegalArgumentException("Invalid singleplayer world name");
        }
    }

    /// Validates one active local path without requiring that the future target already exists.
    ///
    /// @param active whether the path can affect launch behavior
    /// @param path raw path text
    /// @param fieldName user-facing field name for validation errors
    static void validatePath(boolean active, String path, String fieldName) {
        String rawPath = Objects.requireNonNull(path, "path").trim();
        if (!active || rawPath.isEmpty()) {
            return;
        }
        try {
            Path.of(rawPath);
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException(
                    "Invalid " + Objects.requireNonNull(fieldName, "fieldName"),
                    exception);
        }
    }
}
