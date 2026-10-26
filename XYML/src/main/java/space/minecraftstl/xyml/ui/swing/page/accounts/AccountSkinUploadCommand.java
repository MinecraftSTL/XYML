// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;
import java.util.concurrent.CompletionStage;

/// Starts one explicit online-account skin upload after local validation has succeeded.
@FunctionalInterface
@NotNullByDefault
interface AccountSkinUploadCommand {
    /// Uploads the selected validated PNG through the currently selected account.
    ///
    /// @param skinFile normalized local PNG path
    /// @param slim whether the decoded skin uses slim arms
    /// @return completion after reauthentication, provider upload, and final refresh
    CompletionStage<Void> upload(Path skinFile, boolean slim);
}
