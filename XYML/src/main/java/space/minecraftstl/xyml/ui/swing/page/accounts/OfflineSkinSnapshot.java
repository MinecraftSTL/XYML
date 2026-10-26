// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.auth.offline.Skin;

import java.util.Objects;

/// Immutable presentation-safe state for the skin attached to one offline account.
///
/// @param accountId stable launcher account identifier
/// @param profileName offline Minecraft profile name
/// @param skin configured skin, or null when the UUID-derived launcher default is active
/// @param writable whether replacing the account skin can be persisted without overwriting newer files
/// @param profileId Minecraft profile UUID text, or null for legacy presentation sources
@NotNullByDefault
public record OfflineSkinSnapshot(
        String accountId,
        String profileName,
        @Nullable Skin skin,
        boolean writable,
        @Nullable String profileId) {
    /// Validates one presentation-safe offline skin snapshot.
    public OfflineSkinSnapshot {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(profileName, "profileName");
    }

    /// Returns the effective source category, treating an absent configuration as the launcher default.
    ///
    /// @return effective configured skin source
    public Skin.Type skinType() {
        return skin == null ? Skin.Type.DEFAULT : skin.type();
    }

    /// Reports whether this account uses a user-selected local skin image.
    ///
    /// @return whether the configured source is a local file
    public boolean usesLocalSkinFile() {
        return skinType() == Skin.Type.LOCAL_FILE;
    }

    /// Returns the selected local image path only for a local-file configuration.
    ///
    /// @return persisted local skin image path, or null for another source
    public @Nullable String localSkinPath() {
        return usesLocalSkinFile() && skin != null ? skin.localSkinPath() : null;
    }

    /// Reports whether the account will use the launcher default skin at launch.
    ///
    /// @return whether no custom skin source is active
    public boolean usesDefaultSkin() {
        return skinType() == Skin.Type.DEFAULT;
    }
}
