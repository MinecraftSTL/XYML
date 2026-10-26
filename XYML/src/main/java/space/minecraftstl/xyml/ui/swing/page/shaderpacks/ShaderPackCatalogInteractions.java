// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.shaderpacks;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.util.io.DeletionMode;

import java.awt.Component;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/// Native dialog, desktop, and failure boundary for shader-pack management.
@NotNullByDefault
public interface ShaderPackCatalogInteractions {
    /// Opens a multi-selection ZIP chooser on the event-dispatch thread.
    ///
    /// @param owner dialog owner
    /// @param currentDirectory installed shaderpacks directory
    /// @return immutable selected source paths, or an empty list after cancellation
    @Unmodifiable List<Path> chooseImportFiles(Component owner, Path currentDirectory);

    /// Chooses how one current pack should be deleted.
    ///
    /// @param owner dialog owner
    /// @param target exact pack proposed for deletion
    /// @return selected deletion mode, or null after cancellation
    @Nullable DeletionMode chooseDeleteMode(Component owner, ShaderPackCatalogItem target);

    /// Chooses how a selected pack batch should be deleted.
    ///
    /// @param owner dialog owner
    /// @param selectedCount positive selected pack count
    /// @return selected deletion mode, or null after cancellation
    @Nullable DeletionMode chooseDeleteModeSelected(Component owner, int selectedCount);

    /// Chooses one or more backends for an enable or disable operation.
    ///
    /// @param owner dialog owner
    /// @param availableBackends available backend set
    /// @return selected backend set, or null after cancellation or an empty selection
    @Nullable Set<ShaderPackBackend> chooseBackends(
            Component owner,
            @Unmodifiable Set<ShaderPackBackend> availableBackends);

    /// Reveals one installed pack through platform desktop integration.
    ///
    /// @param target exact pack to reveal
    /// @return stage completed on success or failed with the original desktop error
    CompletionStage<@Nullable Void> reveal(ShaderPackCatalogItem target);

    /// Ensures and opens the installed shaderpacks directory.
    ///
    /// @param directory shaderpacks directory
    /// @return stage completed on success or failed with the original desktop error
    CompletionStage<@Nullable Void> openDirectory(Path directory);

    /// Shows one failure message on the event-dispatch thread.
    ///
    /// @param owner dialog owner
    /// @param title localized failure title
    /// @param detail failure detail
    void showFailure(Component owner, String title, String detail);
}
