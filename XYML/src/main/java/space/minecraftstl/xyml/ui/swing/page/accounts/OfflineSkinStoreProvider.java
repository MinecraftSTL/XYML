// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;

/// Marks an account source that owns persistent offline-account skin configuration.
@NotNullByDefault
public interface OfflineSkinStoreProvider {
    /// Returns the skin bridge owned by this account source.
    ///
    /// @return persistent offline-skin bridge
    OfflineSkinStore offlineSkinStore();
}
