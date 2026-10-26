// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Immutable presentation data for one account row.
///
/// @param accountId stable persisted account identifier used by selection commands
/// @param displayName primary account text
/// @param detailText secondary provider and storage text, or an empty string
/// @param profileId stable game-profile identifier as text
/// @param portable whether the account belongs to the portable account group
/// @param avatarSource presentation-safe avatar texture source
@NotNullByDefault
public record AccountListItem(
        String accountId,
        String displayName,
        String detailText,
        String profileId,
        boolean portable,
        AccountAvatarSource avatarSource) {
    /// Creates a row whose avatar uses the UUID-derived launcher-bundled fallback.
    ///
    /// @param accountId stable persisted account identifier used by selection commands
    /// @param displayName primary account text
    /// @param detailText secondary provider and storage text, or an empty string
    /// @param profileId stable game-profile identifier as text
    public AccountListItem(
            String accountId,
            String displayName,
            String detailText,
            String profileId) {
        this(accountId, displayName, detailText, profileId, false, AccountAvatarSource.bundledDefault());
    }

    /// Creates a global account row with an explicit avatar source.
    ///
    /// @param accountId stable persisted account identifier used by selection commands
    /// @param displayName primary account text
    /// @param detailText secondary provider and storage text, or an empty string
    /// @param profileId stable game-profile identifier as text
    /// @param avatarSource presentation-safe avatar texture source
    public AccountListItem(
            String accountId,
            String displayName,
            String detailText,
            String profileId,
            AccountAvatarSource avatarSource) {
        this(accountId, displayName, detailText, profileId, false, avatarSource);
    }

    /// Validates one account row.
    public AccountListItem {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(detailText, "detailText");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(avatarSource, "avatarSource");
        if (accountId.isBlank()) {
            throw new IllegalArgumentException("Account id cannot be blank");
        }
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("Account title cannot be blank");
        }
        if (profileId.isBlank()) {
            throw new IllegalArgumentException("Profile id cannot be blank");
        }
    }

    /// Returns the compact text rendered by the reusable viewport-list cell.
    ///
    /// @return title followed by detail when available
    public String displayText() {
        return detailText.isBlank() ? displayName : displayName + " - " + detailText;
    }
}
