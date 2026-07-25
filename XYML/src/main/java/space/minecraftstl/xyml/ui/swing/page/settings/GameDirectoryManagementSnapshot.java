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
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Objects;

/// Immutable effective game-directory list rendered by [GameDirectoryManagementPanel].
///
/// @param revision monotonic service revision used to publish a selection change even when entry values otherwise match
/// @param entries effective local-first game-directory entries
@NotNullByDefault
public record GameDirectoryManagementSnapshot(
        long revision,
        @Unmodifiable List<GameDirectoryManagementEntry> entries) {
    /// Validates the revision and defensively copies entries.
    public GameDirectoryManagementSnapshot {
        if (revision < 0L) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }
}
