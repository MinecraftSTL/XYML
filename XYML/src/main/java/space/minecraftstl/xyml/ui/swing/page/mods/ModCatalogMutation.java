// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.mods;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.util.io.DeletionMode;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Internal serialized mutation understood by the real Mod access adapter.
@NotNullByDefault
sealed interface ModCatalogMutation {
    /// Imports one or more source archives.
    ///
    /// @param sources normalized source files
    /// @param conflictActions explicit conflict decisions keyed by normalized source
    @NotNullByDefault
    record Import(
            @Unmodifiable List<Path> sources,
            @Unmodifiable Map<Path, ModImportConflictAction> conflictActions)
            implements ModCatalogMutation {
        /// Stores normalized immutable sources and matching conflict decisions.
        public Import {
            sources = ModImportFileOperations.normalizeSources(sources);
            Map<Path, ModImportConflictAction> normalizedActions = new LinkedHashMap<>();
            Objects.requireNonNull(conflictActions, "conflictActions").forEach((source, action) -> {
                Path normalizedSource = Objects.requireNonNull(source, "conflict source")
                        .toAbsolutePath().normalize();
                ModImportConflictAction previous = normalizedActions.put(
                        normalizedSource,
                        Objects.requireNonNull(action, "conflict action"));
                if (previous != null && previous != action) {
                    throw new IllegalArgumentException(
                            "Conflicting decisions for Mod source " + normalizedSource);
                }
            });
            if (!sources.containsAll(normalizedActions.keySet())) {
                throw new IllegalArgumentException(
                        "Conflict decisions must belong to the import sources");
            }
            conflictActions = Map.copyOf(normalizedActions);
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
    /// @param mode selected deletion behavior
    @NotNullByDefault
    record Delete(String localKey, DeletionMode mode) implements ModCatalogMutation {
        /// Creates a permanent-delete request for compatibility callers.
        ///
        /// @param localKey rename-stable target key
        public Delete(String localKey) {
            this(localKey, DeletionMode.PERMANENT);
        }

        /// Validates the target key and mode.
        public Delete {
            if (Objects.requireNonNull(localKey, "localKey").isBlank()) {
                throw new IllegalArgumentException("localKey must not be blank");
            }
            Objects.requireNonNull(mode, "mode");
        }
    }

    /// Deletes a non-empty batch of current Mod files.
    ///
    /// @param localKeys immutable rename-stable target keys
    /// @param mode selected deletion behavior
    @NotNullByDefault
    record DeleteBatch(
            @Unmodifiable List<String> localKeys,
            DeletionMode mode) implements ModCatalogMutation {
        /// Creates a permanent-delete request for compatibility callers.
        ///
        /// @param localKeys immutable rename-stable target keys
        public DeleteBatch(@Unmodifiable List<String> localKeys) {
            this(localKeys, DeletionMode.PERMANENT);
        }

        /// Freezes and validates unique target keys before any file mutation starts.
        public DeleteBatch {
            localKeys = validatedLocalKeys(localKeys);
            Objects.requireNonNull(mode, "mode");
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
