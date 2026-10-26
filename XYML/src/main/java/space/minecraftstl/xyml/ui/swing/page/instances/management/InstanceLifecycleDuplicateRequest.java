// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Captures one user-confirmed destination and world-copy choice for duplicating an instance.
///
/// The destination remains raw until the panel validates it, so a cancelled dialog is represented by
/// `null` at the interaction boundary instead of by an invalid request object.
///
/// @param destinationId requested new instance identifier
/// @param copySaves whether the source effective saves directory should be copied
@NotNullByDefault
public record InstanceLifecycleDuplicateRequest(String destinationId, boolean copySaves) {
    /// Rejects a missing destination text while preserving user whitespace for the panel to normalize.
    ///
    /// @param destinationId requested destination identifier
    /// @param copySaves whether worlds should be duplicated
    public InstanceLifecycleDuplicateRequest {
        Objects.requireNonNull(destinationId, "destinationId");
    }
}
