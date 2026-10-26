// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Immutable account presentation captured by an account store without exposing authentication objects.
///
/// @param id stable persisted account identifier
/// @param title primary user-visible account text
/// @param detail secondary user-visible provider and storage text, or an empty string
/// @param profileId stable game-profile identifier as text
/// @param portable whether the account is stored in the portable account file
/// @param avatarSource presentation-safe avatar texture source
@NotNullByDefault
public record AccountDescriptor(
        String id,
        String title,
        String detail,
        String profileId,
        boolean portable,
        AccountAvatarSource avatarSource) {
    /// Creates a descriptor whose avatar uses the UUID-derived launcher-bundled fallback.
    ///
    /// @param id stable persisted account identifier
    /// @param title primary user-visible account text
    /// @param detail secondary user-visible provider and storage text, or an empty string
    /// @param profileId stable game-profile identifier as text
    public AccountDescriptor(String id, String title, String detail, String profileId) {
        this(id, title, detail, profileId, false, AccountAvatarSource.bundledDefault());
    }

    /// Creates a descriptor with an explicit avatar source in global storage.
    ///
    /// @param id stable persisted account identifier
    /// @param title primary user-visible account text
    /// @param detail secondary user-visible provider and storage text, or an empty string
    /// @param profileId stable game-profile identifier as text
    /// @param avatarSource presentation-safe avatar texture source
    public AccountDescriptor(
            String id,
            String title,
            String detail,
            String profileId,
            AccountAvatarSource avatarSource) {
        this(id, title, detail, profileId, false, avatarSource);
    }

    /// Validates one immutable account descriptor.
    public AccountDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(avatarSource, "avatarSource");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Account id cannot be blank");
        }
        if (title.isBlank()) {
            throw new IllegalArgumentException("Account title cannot be blank");
        }
        if (profileId.isBlank()) {
            throw new IllegalArgumentException("Profile id cannot be blank");
        }
    }
}
