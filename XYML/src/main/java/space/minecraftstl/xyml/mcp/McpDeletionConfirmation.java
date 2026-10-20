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
        MODS
    }
}
