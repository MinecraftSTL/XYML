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
package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Immutable presentation data for one account row.
///
/// @param accountId stable persisted account identifier used by selection commands
/// @param displayName primary account text
/// @param detailText secondary provider and storage text, or an empty string
/// @param profileId stable game-profile identifier as text
/// @param avatarSource presentation-safe avatar texture source
@NotNullByDefault
public record AccountListItem(
        String accountId,
        String displayName,
        String detailText,
        String profileId,
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
        this(accountId, displayName, detailText, profileId, AccountAvatarSource.bundledDefault());
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
