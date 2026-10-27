// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.schematics;

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
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/// Owns schematic browser interactions that cross the panel, dialog, and desktop boundary.
///
/// Dialog methods must be called on the Swing event-dispatch thread. [#reveal] may be called
/// from any thread and reports desktop work asynchronously without blocking the caller.
@NotNullByDefault
public interface SchematicBrowserInteractions extends RetryableFailureInteraction {
    /// Opens a multi-selection Litematic chooser on the event-dispatch thread.
    ///
    /// @param owner dialog owner
    /// @param currentDirectory browser directory used as the chooser location
    /// @return immutable selected source paths, or an empty list after cancellation
    @Unmodifiable List<Path> chooseImportFiles(Component owner, Path currentDirectory);

    /// Prompts for one child-directory name on the event-dispatch thread.
    ///
    /// @param owner dialog owner
    /// @return entered text, or null after cancellation
    @Nullable String promptDirectoryName(Component owner);

    /// Validates the chosen name asynchronously; production implementations provide inline editable feedback.
    default CompletionStage<@Nullable String> promptDirectoryName(Component owner,
            java.util.function.Function<String, CompletionStage<@Nullable String>> validator) {
        @Nullable String name = promptDirectoryName(owner);
        if (name == null) return java.util.concurrent.CompletableFuture.completedFuture(null);
        return validator.apply(name).thenApply(error -> {
            if (error != null) throw new IllegalArgumentException(error);
            return name;
        });
    }

    /// Confirms deletion of one browser row on the event-dispatch thread.
    ///
    /// @param owner dialog owner
    /// @param target exact row proposed for deletion
    /// @return whether deletion was explicitly confirmed
    boolean confirmDelete(Component owner, SchematicBrowserItem target);

    /// Chooses recycle-bin-first deletion without warning or warns before permanent deletion.
    ///
    /// @param owner dialog owner
    /// @param target exact row proposed for deletion
    /// @return selected deletion mode, or null when deletion was cancelled
    default @Nullable DeletionMode chooseDeleteMode(Component owner, SchematicBrowserItem target) {
        if (FileUtils.isMoveToTrashSupported()) {
            return DeletionMode.RECYCLE_BIN_FIRST;
        }
        return confirmDelete(owner, target) ? DeletionMode.PERMANENT : null;
    }

    /// Shows the original warning after a schematic could not enter the recycle bin.
    ///
    /// @param owner dialog owner
    /// @param target exact row proposed for deletion
    /// @return whether permanent deletion was approved
    default boolean confirmPermanentFallback(Component owner, SchematicBrowserItem target) {
        return confirmDelete(owner, target);
    }

    /// Chooses how to recover one schematic that could not enter the recycle bin.
    ///
    /// @param owner dialog owner
    /// @param target exact row proposed for deletion
    /// @return selected fallback action, never null
    default DeletionFallbackDecision choosePermanentFallback(
            Component owner,
            SchematicBrowserItem target) {
        return confirmPermanentFallback(owner, target)
                ? DeletionFallbackDecision.DELETE_PERMANENTLY
                : DeletionFallbackDecision.CANCEL;
    }

    /// Schedules revealing one row through the platform desktop integration.
    ///
    /// @param target exact row to reveal
    /// @return stage completed on success or failed with the original desktop or executor error
    CompletionStage<@Nullable Void> reveal(SchematicBrowserItem target);

    /// Opens one exact directory through the same platform desktop boundary as reveal.
    ///
    /// @param directory exact directory to open
    /// @return stage completed on success or failed with the original desktop or executor error
    default CompletionStage<@Nullable Void> openDirectory(Path directory) {
        Path normalized = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        @Nullable Path fileName = normalized.getFileName();
        if (fileName == null) {
            throw new IllegalArgumentException("Directory must have a file name: " + normalized);
        }
        return reveal(new SchematicDirectoryItem(normalized, fileName.toString()));
    }

    /// Shows one failure message on the event-dispatch thread.
    ///
    /// @param owner dialog owner
    /// @param title localized failure title
    /// @param detail failure detail
    void showFailure(Component owner, String title, String detail);
}
