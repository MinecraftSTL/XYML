// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.mcp;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.game.GameInstanceID;

import java.util.Objects;

/// Decides whether one MCP-triggered deletion may proceed.
@NotNullByDefault
@FunctionalInterface
public interface McpDeletionConfirmation {
    /// Requests approval for one destructive operation.
    ///
    /// @param request immutable deletion description
    /// @return whether the deletion may proceed
    boolean confirm(DeletionRequest request);

    /// Requests permission to permanently delete targets that could not enter the recycle bin.
    /// This authorization is independent of ordinary confirmation and defaults to denial.
    ///
    /// @param request remaining deletion category and item count
    /// @return whether permanent deletion was explicitly approved
    default boolean confirmPermanent(DeletionRequest request) {
        Objects.requireNonNull(request, "request");
        return false;
    }

    /// Describes the destructive target presented to the launcher user.
    ///
    /// @param kind deletion category
    /// @param instanceId instance containing the target
    /// @param itemCount number of items to delete
    @NotNullByDefault
    record DeletionRequest(DeletionKind kind, GameInstanceID instanceId, int itemCount) {
        /// Validates the request before it reaches a presentation implementation.
        public DeletionRequest {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(instanceId, "instanceId");
            if (itemCount < 1) {
                throw new IllegalArgumentException("itemCount must be positive");
            }
        }

        /// Creates an instance-deletion request.
        ///
        /// @param instanceId instance to delete
        /// @return immutable request
        public static DeletionRequest instance(GameInstanceID instanceId) {
            return new DeletionRequest(DeletionKind.INSTANCE, instanceId, 1);
        }

        /// Creates a local-mod deletion request.
        ///
        /// @param instanceId instance containing the mods
        /// @param itemCount number of selected mods
        /// @return immutable request
        public static DeletionRequest mods(GameInstanceID instanceId, int itemCount) {
            return new DeletionRequest(DeletionKind.MODS, instanceId, itemCount);
        }
    }

    /// Categories with distinct user-facing confirmation text.
    @NotNullByDefault
    enum DeletionKind {
        /// A complete installed game instance.
        INSTANCE,

        /// One or more local mod files.
        MODS,

        /// Local resource packs.
        RESOURCE_PACKS,

        /// Local worlds.
        WORLDS,

        /// Local saved servers.
        SERVERS,

        /// Local shader packs.
        SHADER_PACKS,

        /// Local schematic files and directories.
        SCHEMATICS
    }
}
