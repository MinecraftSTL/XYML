// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;
import java.util.UUID;

/// Plain role offered when one authlib-injector login owns multiple game profiles.
///
/// @param profileId stable profile identifier
/// @param profileName profile display name
@NotNullByDefault
public record AccountRoleOption(UUID profileId, String profileName) {
    /// Validates one immutable role option.
    public AccountRoleOption {
        profileId = Objects.requireNonNull(profileId, "profileId");
        profileName = Objects.requireNonNull(profileName, "profileName");
    }
}
