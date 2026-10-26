// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.util.PortablePath;

import java.util.Objects;

/// Validated display metadata and persisted path for a newly added or edited game directory.
///
/// @param displayName non-blank user-visible custom name
/// @param path local or user-level portable path selected by the user
@NotNullByDefault
public record GameDirectoryManagementEdit(String displayName, PortablePath path) {
    /// Validates the editable values before the launcher directory manager is mutated.
    public GameDirectoryManagementEdit {
        displayName = Objects.requireNonNull(displayName, "displayName").trim();
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        path = Objects.requireNonNull(path, "path");
    }
}
