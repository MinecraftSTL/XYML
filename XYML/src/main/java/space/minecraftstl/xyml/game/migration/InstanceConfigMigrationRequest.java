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
package space.minecraftstl.xyml.game.migration;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.setting.InstanceConfigMigrationContent;

import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/// Immutable filesystem request for one configuration migration.
///
/// @param sourceDirectory captured source Minecraft running directory
/// @param targetDirectory captured target Minecraft running directory
/// @param contents immutable selected content kinds
/// @param replaceExisting whether conflicting regular files are replaced
@NotNullByDefault
public record InstanceConfigMigrationRequest(
        Path sourceDirectory,
        Path targetDirectory,
        @Unmodifiable Set<InstanceConfigMigrationContent> contents,
        boolean replaceExisting) {
    /// Normalizes roots and isolates the mutable caller-owned content set.
    public InstanceConfigMigrationRequest {
        sourceDirectory = normalize(sourceDirectory, "sourceDirectory");
        targetDirectory = normalize(targetDirectory, "targetDirectory");
        if (sourceDirectory.equals(targetDirectory)) {
            throw new IllegalArgumentException("Migration source and target directories must differ");
        }
        Objects.requireNonNull(contents, "contents");
        EnumSet<InstanceConfigMigrationContent> copy = contents.isEmpty()
                ? EnumSet.noneOf(InstanceConfigMigrationContent.class)
                : EnumSet.copyOf(contents);
        contents = Collections.unmodifiableSet(copy);
    }

    /// Normalizes one required absolute request root.
    private static Path normalize(Path path, String name) {
        return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
    }
}
