// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;

/// Marks an account source that can upload online-account skins.
@NotNullByDefault
public interface AccountSkinUploadStoreProvider {
    /// Returns the online skin upload store owned by this source.
    ///
    /// @return account skin upload store
    AccountSkinUploadStore accountSkinUploadStore();
}
