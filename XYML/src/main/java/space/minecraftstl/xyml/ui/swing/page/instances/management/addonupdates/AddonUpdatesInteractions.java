// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management.addonupdates;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.ui.swing.dialog.RetryableFailureInteraction;

import java.awt.Component;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionStage;

/// Owns native desktop effects outside installed add-on update scan logic.
@NotNullByDefault
interface AddonUpdatesInteractions extends RetryableFailureInteraction {
    /// Lets the user choose an exact new CSV destination with an editable directory path.
    ///
    /// @param owner dialog owner
    /// @param suggestedName collision-resistant suggested file name
    /// @return normalized destination, or `null` when cancelled or already occupied
    @Nullable Path chooseExportFile(Component owner, String suggestedName);

    /// Writes one immutable update snapshot outside the EDT without replacing an existing file.
    ///
    /// @param destination exact new CSV destination
    /// @param rows immutable actionable update rows
    /// @return nullable-void export completion
    CompletionStage<@Nullable Void> exportUpdateList(
            Path destination,
            @Unmodifiable List<AddonUpdateExportRow> rows);

    /// Opens the exact remote project page outside the EDT.
    ///
    /// @param sourcePage validated project page URI
    /// @return nullable-void desktop completion
    CompletionStage<@Nullable Void> openSourcePage(URI sourcePage);

    /// Opens the containing directory of one exact local add-on outside the EDT.
    ///
    /// @param localFile local installed file or directory
    /// @return nullable-void desktop completion
    CompletionStage<@Nullable Void> revealLocalFile(Path localFile);

    /// Displays one user-visible native action failure on the EDT.
    ///
    /// @param owner dialog owner
    /// @param title concise dialog title
    /// @param detail actionable failure detail
    void showFailure(Component owner, String title, String detail);
}
