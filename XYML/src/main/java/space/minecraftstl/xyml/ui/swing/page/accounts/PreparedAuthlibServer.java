// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;

/// Opaque resolved authlib-injector endpoint awaiting explicit persistence.
///
/// Implementations may retain protocol objects and cached metadata behind this boundary. A prepared
/// value is only valid with the [AuthlibServerStore] that created it.
@NotNullByDefault
public interface PreparedAuthlibServer {
    /// Returns plain metadata safe to display in a confirmation form.
    ///
    /// @return resolved server option
    AuthlibServerOption option();
}
