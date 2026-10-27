// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.resourcepacks;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.ui.swing.dialog.DeletionFallbackDecision;
import space.minecraftstl.xyml.ui.swing.dialog.RetryableFailureInteraction;
import space.minecraftstl.xyml.util.io.DeletionMode;
import space.minecraftstl.xyml.util.io.FileUtils;

import java.awt.Component;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionStage;

/// Owns installed-resource-pack interactions that cross dialog, desktop, and file-system boundaries.
///
/// Dialog methods must be called on the Swing event-dispatch thread. Desktop and file-system
/// methods may be called from any thread and complete asynchronously without blocking the caller.

@NotNullByDefault
public interface ResourcePackCatalogInteractions extends RetryableFailureInteraction {
    /// Opens a multi-selection ZIP chooser on the event-dispatch thread.
    ///
    /// @param owner dialog owner
    /// @param currentDirectory installed resource-pack directory used as the chooser location
    /// @return immutable selected source paths, or an empty list after cancellation
    @Unmodifiable List<Path> chooseImportFiles(Component owner, Path currentDirectory);

    /// Confirms enabling one incompatible resource pack on the event-dispatch thread.
    ///
    /// @param owner dialog owner
    /// @param target exact incompatible pack proposed for enabling
    /// @return whether enabling was explicitly confirmed
    boolean confirmEnableIncompatible(Component owner, ResourcePackCatalogItem target);

    /// Confirms enabling a selected resource-pack batch on the event-dispatch thread.
    ///
    /// The batch warning is intentionally generic because selected off-screen rows remain shallow
    /// paths and are not parsed merely to decide whether the confirmation should be shown.
    ///
    /// @param owner dialog owner
    /// @param selectedCount positive selected path count
    /// @return whether batch enabling was explicitly confirmed
    boolean confirmEnableSelected(Component owner, int selectedCount);

    /// Confirms permanently deleting one installed resource pack on the event-dispatch thread.
    ///
    /// @param owner dialog owner
    /// @param target exact pack proposed for deletion
    /// @return whether permanent deletion was explicitly confirmed
    boolean confirmDelete(Component owner, ResourcePackCatalogItem target);

    /// Confirms permanently deleting a selected resource-pack batch on the event-dispatch thread.
    ///
    /// @param owner dialog owner
    /// @param selectedCount positive selected path count
    /// @return whether batch deletion was explicitly confirmed
    boolean confirmDeleteSelected(Component owner, int selectedCount);

    /// Chooses recycle-bin-first deletion without warning or warns before permanent deletion.
    ///
    /// @param owner dialog owner
    /// @param target exact pack proposed for deletion
    /// @return selected deletion mode, or null when deletion was cancelled
    default @Nullable DeletionMode chooseDeleteMode(Component owner, ResourcePackCatalogItem target) {
        if (FileUtils.isMoveToTrashSupported()) {
            return DeletionMode.RECYCLE_BIN_FIRST;
        }
        return confirmDelete(owner, target) ? DeletionMode.PERMANENT : null;
    }

    /// Chooses recycle-bin-first batch deletion without warning or warns before permanent deletion.
    ///
    /// @param owner dialog owner
    /// @param selectedCount positive selected path count
    /// @return selected deletion mode, or null when deletion was cancelled
    default @Nullable DeletionMode chooseDeleteModeSelected(Component owner, int selectedCount) {
        if (FileUtils.isMoveToTrashSupported()) {
            return DeletionMode.RECYCLE_BIN_FIRST;
        }
        return confirmDeleteSelected(owner, selectedCount) ? DeletionMode.PERMANENT : null;
    }

    /// Shows the original warning after one pack could not enter the recycle bin.
    ///
    /// @param owner dialog owner
    /// @param target exact pack proposed for deletion
    /// @return whether permanent deletion was approved
    default boolean confirmPermanentFallback(Component owner, ResourcePackCatalogItem target) {
        return confirmDelete(owner, target);
    }

    /// Shows the original batch warning after a selection could not enter the recycle bin.
    ///
    /// @param owner dialog owner
    /// @param selectedCount positive selected path count
    /// @return whether permanent batch deletion was approved
    default boolean confirmPermanentFallbackSelected(Component owner, int selectedCount) {
        return confirmDeleteSelected(owner, selectedCount);
    }

    /// Chooses how to recover one pack that could not enter the recycle bin.
    ///
    /// @param owner dialog owner
    /// @param target exact pack proposed for deletion
    /// @return selected fallback action, never null
    default DeletionFallbackDecision choosePermanentFallback(Component owner, ResourcePackCatalogItem target) {
        return confirmPermanentFallback(owner, target)
                ? DeletionFallbackDecision.DELETE_PERMANENTLY
                : DeletionFallbackDecision.CANCEL;
    }

    /// Chooses how to recover a failed subset of one batch that could not enter the recycle bin.
    ///
    /// @param owner dialog owner
    /// @param selectedCount positive failed path count
    /// @return selected fallback action, never null
    default DeletionFallbackDecision choosePermanentFallbackSelected(Component owner, int selectedCount) {
        return confirmPermanentFallbackSelected(owner, selectedCount)
                ? DeletionFallbackDecision.DELETE_PERMANENTLY
                : DeletionFallbackDecision.CANCEL;
    }

    /// Schedules revealing one installed resource pack through platform desktop integration.
    ///
    /// @param target exact pack to reveal
    /// @return stage completed on success or failed with the original desktop or executor error
    CompletionStage<@Nullable Void> reveal(ResourcePackCatalogItem target);

    /// Schedules creation, when needed, and opening of the installed resource-pack directory.
    ///
    /// @param resourcePackDirectory directory to ensure and open
    /// @return stage completed on success or failed with the original file, desktop, or executor error
    CompletionStage<@Nullable Void> openResourcePackDirectory(Path resourcePackDirectory);

    /// Shows one failure message on the event-dispatch thread.
    ///
    /// @param owner dialog owner
    /// @param title localized failure title
    /// @param detail failure detail
    void showFailure(Component owner, String title, String detail);
}
