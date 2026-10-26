// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;

/// Marks an account source that owns portable/global storage movement.
@NotNullByDefault
public interface AccountPortabilityStoreProvider {
    /// Returns the account portability store owned by this source.
    ///
    /// @return persistent account portability store
    AccountPortabilityStore accountPortabilityStore();
}
