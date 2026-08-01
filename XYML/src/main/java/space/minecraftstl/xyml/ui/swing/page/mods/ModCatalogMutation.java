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
package space.minecraftstl.xyml.ui.swing.page.mods;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/// Internal serialized mutation understood by the real Mod access adapter.
@NotNullByDefault
sealed interface ModCatalogMutation {
    /// Imports one or more source archives.
    ///
    /// @param sources normalized source files
    @NotNullByDefault
    record Import(@Unmodifiable List<Path> sources) implements ModCatalogMutation {
        /// Stores normalized immutable sources and rejects an empty import.
        public Import {
            sources = sources.stream()
                    .map(source -> Objects.requireNonNull(source, "source").toAbsolutePath().normalize())
                    .toList();
            if (sources.isEmpty()) {
                throw new IllegalArgumentException("At least one Mod source is required");
            }
        }
    }

    /// Changes one current Mod's enabled state.
    ///
    /// @param localKey rename-stable target key
    /// @param enabled desired state
    @NotNullByDefault
    record Enabled(String localKey, boolean enabled) implements ModCatalogMutation {
        /// Validates the target key.
        public Enabled {
            if (Objects.requireNonNull(localKey, "localKey").isBlank()) {
                throw new IllegalArgumentException("localKey must not be blank");
            }
        }
    }

    /// Changes a non-empty batch of current Mods to one enabled state.
    ///
    /// @param localKeys immutable rename-stable target keys
    /// @param enabled desired state
    @NotNullByDefault
    record EnabledBatch(
            @Unmodifiable List<String> localKeys,
            boolean enabled) implements ModCatalogMutation {
        /// Freezes and validates unique target keys before any file mutation starts.
        public EnabledBatch {
            localKeys = validatedLocalKeys(localKeys);
        }
    }

    /// Deletes one current Mod file.
    ///
    /// @param localKey rename-stable target key
    @NotNullByDefault
    record Delete(String localKey) implements ModCatalogMutation {
        /// Validates the target key.
        public Delete {
            if (Objects.requireNonNull(localKey, "localKey").isBlank()) {
                throw new IllegalArgumentException("localKey must not be blank");
            }
        }
    }

    /// Deletes a non-empty batch of current Mod files.
    ///
    /// @param localKeys immutable rename-stable target keys
    @NotNullByDefault
    record DeleteBatch(@Unmodifiable List<String> localKeys) implements ModCatalogMutation {
        /// Freezes and validates unique target keys before any file mutation starts.
        public DeleteBatch {
            localKeys = validatedLocalKeys(localKeys);
        }
    }

    /// Freezes one non-empty list of unique non-blank rename-stable keys.
    ///
    /// @param localKeys candidate target keys
    /// @return immutable validated target keys
    private static @Unmodifiable List<String> validatedLocalKeys(
            @Unmodifiable List<String> localKeys) {
        @Unmodifiable List<String> captured = Objects.requireNonNull(localKeys, "localKeys").stream()
                .map(localKey -> Objects.requireNonNull(localKey, "localKeys contains null"))
                .toList();
        if (captured.isEmpty()) {
            throw new IllegalArgumentException("At least one Mod local key is required");
        }
        if (captured.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Mod local keys must not be blank");
        }
        if (new HashSet<>(captured).size() != captured.size()) {
            throw new IllegalArgumentException("Mod local keys must be unique");
        }
        return captured;
    }
}
