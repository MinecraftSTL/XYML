// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.home;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.GameInstanceID;

import java.util.Objects;

/// Immutable selected account and instance identity plus presentation from a launcher state store.
///
/// @param accountId stable selected account ID, or empty for none
/// @param gameDirectoryId stable selected game-directory ID, or empty for none
/// @param instanceId stable selected instance ID within the game directory, or `null` for none
/// @param accountName selected account profile name, or empty for none
/// @param accountDetail selected account provider detail
/// @param instanceName selected instance display name, or empty when unavailable
/// @param instanceDetail selected game-directory detail
@NotNullByDefault
public record HomeSelectionState(
        String accountId,
        String gameDirectoryId,
        @Nullable GameInstanceID instanceId,
        String accountName,
        String accountDetail,
        String instanceName,
        String instanceDetail) {
    /// Validates one selection state.
    public HomeSelectionState {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(gameDirectoryId, "gameDirectoryId");
        Objects.requireNonNull(accountName, "accountName");
        Objects.requireNonNull(accountDetail, "accountDetail");
        Objects.requireNonNull(instanceName, "instanceName");
        Objects.requireNonNull(instanceDetail, "instanceDetail");
    }
}
