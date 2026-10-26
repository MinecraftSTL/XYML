// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.game.GameInstanceID;

import java.util.Objects;

/// Cheap searchable identity for one installed instance without icon or version-detail resolution.
///
/// @param stableId stable repository identifier used for loading and commands
/// @param displayName user-visible name matched by the instance-page search
@NotNullByDefault
public record InstanceSearchEntry(GameInstanceID stableId, String displayName) {
    /// Validates one immutable search entry.
    public InstanceSearchEntry {
        Objects.requireNonNull(stableId, "stableId");
        Objects.requireNonNull(displayName, "displayName");
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("Instance display name cannot be blank");
        }
    }
}
