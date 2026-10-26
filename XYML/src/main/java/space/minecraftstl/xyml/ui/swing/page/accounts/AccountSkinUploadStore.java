// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;
import java.util.concurrent.CompletionStage;

/// Uploads a validated local skin through one selected online account provider.
@NotNullByDefault
public interface AccountSkinUploadStore {
    /// Reports whether one current account provider supports skin uploads.
    ///
    /// @param accountId stable launcher account identifier
    /// @return whether the account is present and upload-capable
    boolean canUpload(String accountId);

    /// Uploads one already validated PNG skin without blocking the Swing event-dispatch thread.
    ///
    /// @param accountId stable launcher account identifier
    /// @param skinFile normalized local PNG path
    /// @param slim whether the decoded texture uses slim arms
    /// @return completion after the provider accepts or rejects the upload
    CompletionStage<Void> upload(String accountId, Path skinFile, boolean slim);
}
