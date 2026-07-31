/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
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
