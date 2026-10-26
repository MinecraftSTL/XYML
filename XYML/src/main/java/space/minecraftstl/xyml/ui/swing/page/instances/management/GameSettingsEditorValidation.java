// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
