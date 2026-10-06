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
package space.minecraftstl.xyml.setting;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.GameInstanceID;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/// Immutable launcher-wide policy for automatic isolated-instance configuration migration.
///
/// @param enabled whether automatic migration is enabled
/// @param sourceType selected source strategy
/// @param sourceGameDirectory explicit source game directory, or null for the global strategy
/// @param sourceInstance explicit source instance, or null for the global strategy
/// @param contents immutable selected content kinds
@NotNullByDefault
public record InstanceConfigMigrationPolicy(
        boolean enabled,
        InstanceConfigMigrationSourceType sourceType,
        @Nullable GameDirectoryID sourceGameDirectory,
        @Nullable GameInstanceID sourceInstance,
        @Unmodifiable Set<InstanceConfigMigrationContent> contents) {
    /// Validates source identity and isolates the mutable caller-owned content set.
    public InstanceConfigMigrationPolicy {
        sourceType = Objects.requireNonNull(sourceType, "sourceType");
        contents = immutableContents(contents);
        if (sourceType == InstanceConfigMigrationSourceType.INSTANCE
                && (sourceGameDirectory == null || sourceInstance == null)) {
            throw new IllegalArgumentException("An instance migration source requires both identifiers");
        }
        if (sourceType == InstanceConfigMigrationSourceType.GLOBAL) {
            sourceGameDirectory = null;
            sourceInstance = null;
        }
    }

    /// Returns the launcher default: enabled, global source, and all supported content kinds.
    ///
    /// @return default migration policy
    public static InstanceConfigMigrationPolicy defaults() {
        return new InstanceConfigMigrationPolicy(
                true,
                InstanceConfigMigrationSourceType.GLOBAL,
                null,
                null,
                EnumSet.allOf(InstanceConfigMigrationContent.class));
    }

    /// Returns a policy whose exact instance source follows a successful rename in the identified repository.
    ///
    /// @param gameDirectory renamed instance repository
    /// @param from previous instance identifier
    /// @param to replacement instance identifier
    /// @return this policy when it does not reference the renamed source, otherwise an updated immutable policy
    public InstanceConfigMigrationPolicy renameSource(
            GameDirectoryID gameDirectory,
            GameInstanceID from,
            GameInstanceID to) {
        Objects.requireNonNull(gameDirectory, "gameDirectory");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (sourceType != InstanceConfigMigrationSourceType.INSTANCE
                || !gameDirectory.equals(sourceGameDirectory)
                || !from.equals(sourceInstance)) {
            return this;
        }
        return new InstanceConfigMigrationPolicy(enabled, sourceType, gameDirectory, to, contents);
    }

    /// Returns whether this policy performs any automatic file operation.
    ///
    /// @return true when enabled with at least one selected content kind
    public boolean active() {
        return enabled && !contents.isEmpty();
    }

    /// Creates an immutable enum-ordered content snapshot.
    private static @Unmodifiable Set<InstanceConfigMigrationContent> immutableContents(
            Set<InstanceConfigMigrationContent> contents) {
        Objects.requireNonNull(contents, "contents");
        EnumSet<InstanceConfigMigrationContent> copy = contents.isEmpty()
                ? EnumSet.noneOf(InstanceConfigMigrationContent.class)
                : EnumSet.copyOf(contents);
        return Collections.unmodifiableSet(copy);
    }
}
