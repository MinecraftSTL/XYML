// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.setting.GameDirectoryID;

/// Exposes game-directory state and mutations without coupling a Swing page directly to launcher observables.
///
/// Implementations keep all selection and persistence behavior behind the established game-directory manager. Calls
/// that alter state are made on the Swing event dispatch thread by [GameDirectoryManagementPanel].
@NotNullByDefault
public interface GameDirectoryManagementService extends AutoCloseable {
    /// Returns the latest rendered game-directory snapshot.
    ///
    /// @return immutable effective game-directory state
    GameDirectoryManagementSnapshot snapshot();

    /// Registers for game-directory snapshot changes.
    ///
    /// @param listener listener receiving immutable state transitions
    /// @return independently removable listener registration
    Subscription subscribe(ValueChangeListener<GameDirectoryManagementSnapshot> listener);

    /// Sets the process-wide selected game directory.
    ///
    /// @param id stable directory identifier from the current snapshot
    void select(GameDirectoryID id);

    /// Adds a new directory to the store selected by its local or absolute path.
    ///
    /// @param edit validated display name and portable path
    /// @param allowReadOnlyOverwrite whether the user has approved backup and overwrite of read-only settings files
    /// @throws GameDirectoryStorageOverwriteRequiredException when confirmation is required before the mutation
    void add(GameDirectoryManagementEdit edit, boolean allowReadOnlyOverwrite);

    /// Updates one existing directory, including moving it between local and user-level stores when its path changes.
    ///
    /// @param id stable directory identifier from the current snapshot
    /// @param edit validated display name and portable path
    /// @param allowReadOnlyOverwrite whether the user has approved backup and overwrite of read-only settings files
    /// @throws GameDirectoryStorageOverwriteRequiredException when confirmation is required before the mutation
    void update(GameDirectoryID id, GameDirectoryManagementEdit edit, boolean allowReadOnlyOverwrite);

    /// Removes one existing directory.
    ///
    /// @param id stable directory identifier from the current snapshot
    /// @param allowReadOnlyOverwrite whether the user has approved backup and overwrite of read-only settings files
    /// @throws GameDirectoryStorageOverwriteRequiredException when confirmation is required before the mutation
    void remove(GameDirectoryID id, boolean allowReadOnlyOverwrite);

    /// Releases service subscriptions.
    @Override
    void close();
}
