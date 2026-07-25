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
