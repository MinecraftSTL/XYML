// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management.backups;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;
import java.util.Objects;

/// One shallowly indexed direct child of an instance's `saves` directory.
///
/// This value intentionally does not claim that its directory is a readable Minecraft world. The
/// initial index must remain inexpensive, so `World` validation is deferred until the user asks to
/// create a backup from the selected source.
///
/// @param directory normalized direct-child directory in `saves`
/// @param directoryName visible direct-child directory name
@NotNullByDefault
public record WorldBackupSource(Path directory, String directoryName) {
    /// Normalizes and validates one shallow source entry.
    ///
    /// @param directory direct-child directory in the saves folder
    /// @param directoryName visible directory name
    public WorldBackupSource {
        directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        directoryName = Objects.requireNonNull(directoryName, "directoryName");
        if (directoryName.isBlank()) {
            throw new IllegalArgumentException("directoryName must not be blank");
        }
    }

    /// Returns the concise source label used by standard Swing list renderers.
    ///
    /// @return visible directory name
    @Override
    public String toString() {
        return directoryName;
    }
}
