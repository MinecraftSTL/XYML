// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.setting.GameDirectoryID;
import space.minecraftstl.xyml.util.PortablePath;

import java.util.Objects;

/// Immutable rendered metadata for one effective game-directory entry.
///
/// A relative path belongs to the workspace-local game-directory store and an absolute path belongs to the
/// user-level store. The entry deliberately keeps the persisted [PortablePath] rather than a resolved filesystem path,
/// so rendering the list performs no filesystem I/O.
///
/// @param id stable persisted game-directory identifier
/// @param displayName localized display name resolved for the current launcher locale
/// @param path persisted local or user-level game-directory path
/// @param selected whether this is the process-wide selected game directory
@NotNullByDefault
public record GameDirectoryManagementEntry(
        GameDirectoryID id,
        String displayName,
        PortablePath path,
        boolean selected) {
    /// Validates immutable entry values.
    public GameDirectoryManagementEntry {
        id = Objects.requireNonNull(id, "id");
        displayName = requireNonBlank(displayName, "displayName");
        path = Objects.requireNonNull(path, "path");
    }

    /// Returns whether this entry is persisted in the user-level game-directory store.
    ///
    /// @return `true` for absolute paths and user-level storage
    public boolean isUserDirectory() {
        return path.isAbsolute();
    }

    /// Validates one required visible text value.
    ///
    /// @param value source text
    /// @param name parameter name
    /// @return validated text
    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
