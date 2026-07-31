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
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.GameInstanceID;

import java.util.Objects;

/// Identifies one launch from stable account, game-directory, and instance identifiers.
///
/// Presentation names are intentionally excluded so a queued launch cannot silently follow a later UI selection.
///
/// @param accountId stable selected-account identifier
/// @param gameDirectoryId stable selected game-directory identifier
/// @param instanceId stable selected-instance identifier within the game directory
/// @param quickPlaySingleplayer single-player world folder to enter after launch, or `null` for an ordinary launch
/// @param testMode whether this request launches the instance with the launcher's isolated test-game policy
@NotNullByDefault
public record LaunchRequest(
        String accountId,
        String gameDirectoryId,
        GameInstanceID instanceId,
        @Nullable String quickPlaySingleplayer,
        boolean testMode) {
    /// Creates an ordinary launch request without a quick-play destination.
    ///
    /// @param accountId stable selected-account identifier
    /// @param gameDirectoryId stable selected game-directory identifier
    /// @param instanceId stable selected-instance identifier within the game directory
    public LaunchRequest(String accountId, String gameDirectoryId, GameInstanceID instanceId) {
        this(accountId, gameDirectoryId, instanceId, null, false);
    }

    /// Creates a single-player quick-play request without enabling test-game policy.
    ///
    /// @param accountId stable selected-account identifier
    /// @param gameDirectoryId stable selected game-directory identifier
    /// @param instanceId stable selected-instance identifier within the game directory
    /// @param quickPlaySingleplayer exact single-player world folder
    public LaunchRequest(
            String accountId,
            String gameDirectoryId,
            GameInstanceID instanceId,
            String quickPlaySingleplayer) {
        this(accountId, gameDirectoryId, instanceId, quickPlaySingleplayer, false);
    }

    /// Creates a test-game request without a quick-play destination.
    ///
    /// @param accountId stable selected-account identifier
    /// @param gameDirectoryId stable selected game-directory identifier
    /// @param instanceId stable selected-instance identifier within the game directory
    /// @return immutable test-game request
    public static LaunchRequest test(
            String accountId,
            String gameDirectoryId,
            GameInstanceID instanceId) {
        return new LaunchRequest(accountId, gameDirectoryId, instanceId, null, true);
    }

    /// Validates that every identity component is present without rewriting its stored representation.
    public LaunchRequest {
        requireIdentifier(accountId, "accountId");
        requireIdentifier(gameDirectoryId, "gameDirectoryId");
        Objects.requireNonNull(instanceId, "instanceId");
        if (quickPlaySingleplayer != null) {
            requireIdentifier(quickPlaySingleplayer, "quickPlaySingleplayer");
        }
        if (testMode && quickPlaySingleplayer != null) {
            throw new IllegalArgumentException("Test mode and single-player quick play are mutually exclusive");
        }
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
