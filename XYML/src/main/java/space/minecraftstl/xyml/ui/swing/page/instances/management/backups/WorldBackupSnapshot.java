// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management.backups;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Objects;

/// Immutable result of one shallow world-source and backup-directory scan.
///
/// Source directories have not had their NBT decoded and archive entries have not been decompressed;
/// the snapshot is therefore suitable for lazy page activation and inexpensive refreshes.
///
/// @param sources immutable direct-child source directories from `saves`
/// @param archives immutable ZIP archive entries from `backups`
@NotNullByDefault
public record WorldBackupSnapshot(
        @Unmodifiable List<WorldBackupSource> sources,
        @Unmodifiable List<WorldBackupArchive> archives) {
    /// Copies both result lists so asynchronous scans cannot expose mutable collection state.
    ///
    /// @param sources direct-child source directories
    /// @param archives local backup ZIP archives
    public WorldBackupSnapshot {
        sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        archives = List.copyOf(Objects.requireNonNull(archives, "archives"));
    }

    /// Creates the initial pre-activation snapshot without touching the filesystem.
    ///
    /// @return immutable empty snapshot
    public static WorldBackupSnapshot empty() {
        return new WorldBackupSnapshot(List.of(), List.of());
    }
}
