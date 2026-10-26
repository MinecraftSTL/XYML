// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.game.migration;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.setting.InstanceConfigMigrationContent;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/// Immutable per-content result of one completed configuration migration.
///
/// @param counts immutable counts keyed by every requested content kind
@NotNullByDefault
public record InstanceConfigMigrationResult(
        @Unmodifiable Map<InstanceConfigMigrationContent, Counts> counts) {
    /// Defensively copies the caller-owned result map.
    public InstanceConfigMigrationResult {
        Objects.requireNonNull(counts, "counts");
        EnumMap<InstanceConfigMigrationContent, Counts> copy =
                new EnumMap<>(InstanceConfigMigrationContent.class);
        copy.putAll(counts);
        counts = Collections.unmodifiableMap(copy);
    }

    /// Returns total copied and replaced files.
    ///
    /// @return total files written to the target
    public int writtenFiles() {
        return counts.values().stream().mapToInt(value -> value.copied() + value.replaced()).sum();
    }

    /// Returns total conflicts skipped by non-replacing migration.
    ///
    /// @return total skipped existing files
    public int skippedFiles() {
        return counts.values().stream().mapToInt(Counts::skipped).sum();
    }

    /// Immutable counters for one content kind.
    ///
    /// @param copied newly created regular files
    /// @param replaced replaced existing regular files
    /// @param skipped existing regular files retained without replacement
    @NotNullByDefault
    public record Counts(int copied, int replaced, int skipped) {
        /// Rejects negative counters.
        public Counts {
            if (copied < 0 || replaced < 0 || skipped < 0) {
                throw new IllegalArgumentException("Migration counters cannot be negative");
            }
        }
    }
}
