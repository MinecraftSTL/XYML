// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.awt.Component;
import java.nio.file.Path;
import java.util.Objects;

/// Separates native directory-selection and confirmation UI from game-directory state changes.
@NotNullByDefault
interface GameDirectoryManagementInteraction {
    /// Opens a native directory chooser on the EDT.
    ///
    /// @param owner chooser parent component
    /// @param initialDirectory suggested initial directory, or `null` when no usable suggestion is available
    /// @return chosen directory, or `null` when the chooser is cancelled
    @Nullable Path chooseDirectory(Component owner, @Nullable Path initialDirectory);

    /// Confirms use of a filesystem root as the game directory.
    ///
    /// @param owner confirmation parent component
    /// @return whether saving the root directory may continue
    boolean confirmRootDirectory(Component owner);

    /// Confirms backup and overwrite of a read-only game-directory settings file.
    ///
    /// @param owner confirmation parent component
    /// @return whether the protected mutation may continue
    boolean confirmReadOnlyOverwrite(Component owner);

    /// Confirms permanent removal of one directory configuration entry.
    ///
    /// @param owner confirmation parent component
    /// @param entry directory being removed
    /// @return whether the removal may continue
    boolean confirmRemoval(Component owner, GameDirectoryManagementEntry entry);

    /// Shows one terminal management failure.
    ///
    /// @param owner dialog parent component
    /// @param detail localized or diagnostic failure detail
    void showFailure(Component owner, String detail);

    /// Shows one retryable management failure and delegates to the plain failure boundary by default.
    ///
    /// @param owner dialog parent component
    /// @param detail localized or diagnostic failure detail
    /// @param retryAction captured operation to replay
    default void showRetryableFailure(
            Component owner,
            String detail,
            Runnable retryAction) {
        Objects.requireNonNull(retryAction, "retryAction");
        showFailure(owner, detail);
    }
}
