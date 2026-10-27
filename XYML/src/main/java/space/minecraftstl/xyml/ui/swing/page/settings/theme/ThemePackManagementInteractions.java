// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings.theme;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.ui.swing.dialog.DeletionFallbackDecision;
import space.minecraftstl.xyml.util.io.DeletionMode;
import space.minecraftstl.xyml.util.io.FileUtils;

import java.awt.Component;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/// Native chooser, confirmation, and desktop boundary injected into the theme management panel.
@NotNullByDefault
public interface ThemePackManagementInteractions {
    /// Selects one local `.xyml-theme` archive on the EDT.
    ///
    /// @param owner dialog owner
    /// @return selected archive, or `null` after cancellation
    @Nullable Path chooseImportArchive(Component owner);

    /// Confirms deletion of the complete installed package containing an item on the EDT.
    ///
    /// @param owner dialog owner
    /// @param item selected installed item
    /// @return whether deletion was confirmed
    boolean confirmDelete(Component owner, ThemePackItem item);

    /// Chooses recycle-bin-first deletion without warning or warns before permanent deletion.
    ///
    /// @param owner dialog owner
    /// @param item selected installed item
    /// @return selected deletion mode, or null when deletion was cancelled
    default @Nullable DeletionMode chooseDeleteMode(Component owner, ThemePackItem item) {
        if (FileUtils.isMoveToTrashSupported()) {
            return DeletionMode.RECYCLE_BIN_FIRST;
        }
        return confirmDelete(owner, item) ? DeletionMode.PERMANENT : null;
    }

    /// Shows the original warning after a theme package could not enter the recycle bin.
    ///
    /// @param owner dialog owner
    /// @param item selected installed item
    /// @return whether permanent deletion was approved
    default boolean confirmPermanentFallback(Component owner, ThemePackItem item) {
        return confirmDelete(owner, item);
    }

    /// Chooses how to recover one theme package that could not enter the recycle bin.
    ///
    /// @param owner dialog owner
    /// @param item selected installed item
    /// @return selected fallback action, never null
    default DeletionFallbackDecision choosePermanentFallback(Component owner, ThemePackItem item) {
        return confirmPermanentFallback(owner, item)
                ? DeletionFallbackDecision.DELETE_PERMANENTLY
                : DeletionFallbackDecision.CANCEL;
    }

    /// Opens a previously revalidated installed package directory without blocking the EDT.
    ///
    /// @param directory exact validated directory
    /// @return completion stage resolved after desktop integration returns
    CompletionStage<@Nullable Void> revealInstalledDirectory(Path directory);

    /// Collects current-theme metadata and selects an output archive through Swing dialogs on the EDT.
    ///
    /// Implementations that are used without export support may retain this cancellation default.
    ///
    /// @param owner dialog owner
    /// @param defaults generated export defaults
    /// @return confirmed export request, or `null` after cancellation
    default @Nullable ThemePackExportRequest chooseThemePackExport(
            Component owner,
            ThemePackExportDefaults defaults) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(defaults, "defaults");
        return null;
    }

    /// Reports a successfully published theme-pack archive on the EDT.
    ///
    /// @param owner dialog owner
    /// @param outputFile published archive
    default void showThemePackExportSuccess(Component owner, Path outputFile) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(outputFile, "outputFile");
    }

    /// Reports a failed current-theme export on the EDT.
    ///
    /// @param owner dialog owner
    /// @param failure root export failure
    default void showThemePackExportFailure(Component owner, Throwable failure) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(failure, "failure");
    }
}
