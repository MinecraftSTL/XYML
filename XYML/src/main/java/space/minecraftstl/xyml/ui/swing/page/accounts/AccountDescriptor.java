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

/// Immutable account presentation captured by an account store without exposing authentication objects.
///
/// @param id stable persisted account identifier
/// @param title primary user-visible account text
/// @param detail secondary user-visible provider and storage text, or an empty string
/// @param profileId stable game-profile identifier as text
/// @param avatarSource presentation-safe avatar texture source
@NotNullByDefault
public record AccountDescriptor(
        String id,
        String title,
        String detail,
        String profileId,
        AccountAvatarSource avatarSource) {
    /// Creates a descriptor whose avatar uses the UUID-derived launcher-bundled fallback.
    ///
    /// @param id stable persisted account identifier
    /// @param title primary user-visible account text
    /// @param detail secondary user-visible provider and storage text, or an empty string
    /// @param profileId stable game-profile identifier as text
    public AccountDescriptor(String id, String title, String detail, String profileId) {
        this(id, title, detail, profileId, AccountAvatarSource.bundledDefault());
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
