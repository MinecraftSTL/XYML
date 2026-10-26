// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management.addonupdates;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// One selected add-on update that could not be completed or restored cleanly.
///
/// @param updateItem exact selected update item
/// @param detail non-blank failure summary suitable for a status row or dialog
@NotNullByDefault
public record AddonUpdateApplicationFailure(
        AddonUpdateItem updateItem,
        String detail) {
    /// Validates the exact update reference and user-visible failure detail.
    public AddonUpdateApplicationFailure {
        updateItem = Objects.requireNonNull(updateItem, "updateItem");
        detail = Objects.requireNonNull(detail, "detail");
        if (detail.isBlank()) {
            throw new IllegalArgumentException("detail must not be blank");
        }
    }
}
