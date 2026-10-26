// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.nbt;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Result of parsing and applying one type-preserving scalar edit.
@NotNullByDefault
public record NBTValueEditResult(boolean applied, @Nullable String errorMessage) {
    /// Validates success and failure invariants.
    ///
    /// @param applied whether the concrete XoyzNBT setter ran
    /// @param errorMessage technical validation detail, or `null` on success
    public NBTValueEditResult {
        if (applied && errorMessage != null) {
            throw new IllegalArgumentException("a successful edit cannot have an error message");
        }
        if (!applied) {
            Objects.requireNonNull(errorMessage, "errorMessage");
        }
    }

    /// Creates a successful edit result.
    ///
    /// @return shared-shape successful result
    static NBTValueEditResult success() {
        return new NBTValueEditResult(true, null);
    }

    /// Creates a rejected edit result.
    ///
    /// @param errorMessage non-empty technical reason
    /// @return rejected result
    static NBTValueEditResult failure(String errorMessage) {
        String message = Objects.requireNonNull(errorMessage, "errorMessage");
        if (message.isBlank()) {
            throw new IllegalArgumentException("errorMessage must not be blank");
        }
        return new NBTValueEditResult(false, message);
    }
}
