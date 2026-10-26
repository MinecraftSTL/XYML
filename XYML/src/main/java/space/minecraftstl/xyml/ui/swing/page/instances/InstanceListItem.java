// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.image.InstanceIconData;

import java.util.Objects;

/// Immutable presentation data for one installed game instance.
///
/// @param id stable repository instance identifier used by commands
/// @param name user-visible instance name
/// @param detail concise game-version or loader detail
/// @param icon normalized non-null instance icon pixels
@NotNullByDefault
public record InstanceListItem(
        GameInstanceID id,
        String name,
        String detail,
        InstanceIconData icon) {
    /// Validates one instance row.
    public InstanceListItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(icon, "icon");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Instance name cannot be blank");
        }
    }

    /// Returns the compact text rendered by the reusable viewport-list cell.
    ///
    /// @return instance name followed by detail when available
    public String displayText() {
        return detail.isBlank() ? name : name + " - " + detail;
    }

}
