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
package space.minecraftstl.xyml.game.launch;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Identifies one launch from stable account, game-directory, and instance identifiers.
///
/// Presentation names are intentionally excluded so a queued launch cannot silently follow a later UI selection.
///
/// @param accountId stable selected-account identifier
/// @param gameDirectoryId stable selected game-directory identifier
/// @param instanceId stable selected-instance identifier within the game directory
@NotNullByDefault
public record LaunchRequest(String accountId, String gameDirectoryId, String instanceId) {
    /// Validates that every identity component is present without rewriting its stored representation.
    public LaunchRequest {
        requireIdentifier(accountId, "accountId");
        requireIdentifier(gameDirectoryId, "gameDirectoryId");
        requireIdentifier(instanceId, "instanceId");
    }

    /// Rejects null and blank identity components while preserving non-blank identifiers exactly.
    ///
    /// @param identifier identifier to validate
    /// @param name component name used in diagnostics
    private static void requireIdentifier(String identifier, String name) {
        Objects.requireNonNull(identifier, name);
        if (identifier.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
