// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
