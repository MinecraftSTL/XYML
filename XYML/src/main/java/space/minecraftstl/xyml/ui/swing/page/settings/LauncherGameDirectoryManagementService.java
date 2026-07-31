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
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.setting.GameDirectory;
import space.minecraftstl.xyml.setting.GameDirectoryID;
import space.minecraftstl.xyml.setting.GameDirectoryManager;
import space.minecraftstl.xyml.setting.SettingsManager;
import space.minecraftstl.xyml.ui.swing.runtime.LauncherStateDispatcher;
import space.minecraftstl.xyml.util.i18n.LocalizedText;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Bridges process-wide launcher game-directory state to the immutable Swing management service contract.
///
/// This adapter intentionally delegates all storage selection, local-first merging, selected-repository refreshes, and
/// backup-and-overwrite work to [GameDirectoryManager]. It does not scan or probe filesystem directories; list
/// rendering is therefore free of directory I/O and all launcher observable access remains confined to the EDT.
@NotNullByDefault
public final class LauncherGameDirectoryManagementService implements GameDirectoryManagementService {
    /// Publishes immutable effective directory snapshots.
    private final ValueChangeSupport<GameDirectoryManagementSnapshot> changes = new ValueChangeSupport<>(this);

    /// Subscription to effective local-first list membership and entry mutations.
    private final Subscription directoriesSubscription;

    /// Subscription to the process-wide selected directory.
    private final Subscription selectionSubscription;

    /// Latest effective directory state.
    private GameDirectoryManagementSnapshot currentSnapshot;

    /// Monotonic snapshot revision.
    private long revision;

    /// Whether subscriptions were released.
    private boolean closed;

    /// Creates an adapter after game-directory state has been initialized on the EDT.
    public LauncherGameDirectoryManagementService() {
        LauncherStateDispatcher.requireEventThread();
        currentSnapshot = createSnapshot();
        directoriesSubscription = GameDirectoryManager.getGameDirectories().subscribe(change -> publishSnapshot());
        selectionSubscription = GameDirectoryManager.selectedGameDirectoryProperty().subscribe(change -> publishSnapshot());
    }

    /// Returns the current immutable effective game-directory state.
    ///
    /// @return effective local-first directory snapshot
    @Override
    public GameDirectoryManagementSnapshot snapshot() {
        LauncherStateDispatcher.requireEventThread();
        return currentSnapshot;
    }

    /// Registers one immutable directory snapshot listener.
    ///
    /// @param listener listener receiving state transitions
    /// @return independently removable listener registration
    @Override
    public Subscription subscribe(ValueChangeListener<GameDirectoryManagementSnapshot> listener) {
        LauncherStateDispatcher.requireEventThread();
        requireOpen();
        return changes.subscribe(Objects.requireNonNull(listener, "listener"));
    }

    /// Changes the selected directory using the established repository-selection workflow.
    ///
    /// @param id selected directory identifier
    @Override
    public void select(GameDirectoryID id) {
        LauncherStateDispatcher.requireEventThread();
        requireOpen();
        GameDirectoryManager.setSelectedGameDirectory(findDirectory(Objects.requireNonNull(id, "id")));
    }

    /// Adds one directory to the local or user store according to its portable path type.
    ///
    /// @param edit validated entry values
    /// @param allowReadOnlyOverwrite whether user consented to required recovery
    @Override
    public void add(GameDirectoryManagementEdit edit, boolean allowReadOnlyOverwrite) {
        LauncherStateDispatcher.requireEventThread();
        requireOpen();
        GameDirectoryManagementEdit requestedEdit = Objects.requireNonNull(edit, "edit");
        requireUniqueCustomName(requestedEdit.displayName(), null);
        GameDirectory directory = new GameDirectory(
                GameDirectoryManager.newGameDirectoryId(),
                LocalizedText.plain(requestedEdit.displayName()),
                requestedEdit.path());
        if (requestedEdit.path().isAbsolute()) {
            addUserDirectory(directory, allowReadOnlyOverwrite);
        } else {
            addLocalDirectory(directory, allowReadOnlyOverwrite);
        }
    }

    /// Updates an existing directory with the manager's cross-store and protection rules.
    ///
    /// @param id existing directory identifier
    /// @param edit validated entry values
    /// @param allowReadOnlyOverwrite whether user consented to required recovery
    @Override
    public void update(
            GameDirectoryID id,
            GameDirectoryManagementEdit edit,
            boolean allowReadOnlyOverwrite) {
        LauncherStateDispatcher.requireEventThread();
        requireOpen();
        GameDirectory directory = findDirectory(Objects.requireNonNull(id, "id"));
        GameDirectoryManagementEdit requestedEdit = Objects.requireNonNull(edit, "edit");
        requireUniqueCustomName(requestedEdit.displayName(), directory.getId());
        if (!GameDirectoryManager.canUpdateGameDirectory(directory, requestedEdit.path())) {
            if (!allowReadOnlyOverwrite) {
                throw new GameDirectoryStorageOverwriteRequiredException();
            }
            GameDirectoryManager.forceOverwriteGameDirectoryFiles(directory, requestedEdit.path());
        }
        GameDirectoryManager.updateGameDirectory(
                directory,
                LocalizedText.plain(requestedEdit.displayName()),
                requestedEdit.path());
    }

    /// Removes one directory using the established local/user recovery rules.
    ///
    /// @param id existing directory identifier
    /// @param allowReadOnlyOverwrite whether user consented to required recovery
    @Override
    public void remove(GameDirectoryID id, boolean allowReadOnlyOverwrite) {
        LauncherStateDispatcher.requireEventThread();
        requireOpen();
        GameDirectory directory = findDirectory(Objects.requireNonNull(id, "id"));
        if (!GameDirectoryManager.canRemoveGameDirectory(directory)) {
            if (!allowReadOnlyOverwrite) {
                throw new GameDirectoryStorageOverwriteRequiredException();
            }
            GameDirectoryManager.forceOverwriteGameDirectoryFiles(directory);
        }
        GameDirectoryManager.removeGameDirectory(directory);
    }

    /// Releases launcher observable subscriptions.
    @Override
    public void close() {
        LauncherStateDispatcher.requireEventThread();
        if (closed) {
            return;
        }
        closed = true;
        directoriesSubscription.unsubscribe();
        selectionSubscription.unsubscribe();
    }

    /// Adds one user-level directory after applying the user-store read-only guard.
    ///
    /// @param directory created user-level directory
    /// @param allowReadOnlyOverwrite whether overwrite was confirmed
    private static void addUserDirectory(GameDirectory directory, boolean allowReadOnlyOverwrite) {
        if (SettingsManager.isUserGameDirectoriesReadOnly()) {
            if (!allowReadOnlyOverwrite) {
                throw new GameDirectoryStorageOverwriteRequiredException();
            }
            SettingsManager.forceOverwriteUserGameDirectories();
        }
        GameDirectoryManager.addUserGameDirectory(directory);
    }

    /// Adds one workspace-local directory after applying the local-store read-only guard.
    ///
    /// @param directory created local directory
    /// @param allowReadOnlyOverwrite whether overwrite was confirmed
    private static void addLocalDirectory(GameDirectory directory, boolean allowReadOnlyOverwrite) {
        if (SettingsManager.isLocalGameDirectoriesReadOnly()) {
            if (!allowReadOnlyOverwrite) {
                throw new GameDirectoryStorageOverwriteRequiredException();
            }
            SettingsManager.forceOverwriteLocalGameDirectories();
        }
        GameDirectoryManager.addLocalGameDirectory(directory);
    }

    /// Publishes a rebuilt snapshot after one launcher list or selection transition.
    private void publishSnapshot() {
        LauncherStateDispatcher.requireEventThread();
        if (closed) {
            return;
        }
        GameDirectoryManagementSnapshot previous = currentSnapshot;
        GameDirectoryManagementSnapshot replacement = createSnapshot();
        currentSnapshot = replacement;
        changes.fireChange(previous, replacement);
    }

    /// Builds the current effective local-first directory list without filesystem access.
    ///
    /// @return immutable current state
    private GameDirectoryManagementSnapshot createSnapshot() {
        LauncherStateDispatcher.requireEventThread();
        @Nullable GameDirectory selected = GameDirectoryManager.selectedGameDirectoryProperty().get();
        List<GameDirectoryManagementEntry> entries = new ArrayList<>(GameDirectoryManager.getGameDirectories().size());
        for (GameDirectory directory : GameDirectoryManager.getGameDirectories()) {
            entries.add(new GameDirectoryManagementEntry(
                    directory.getId(),
                    GameDirectoryManager.getGameDirectoryDisplayName(directory),
                    directory.getPath(),
                    selected != null && selected.getId().equals(directory.getId())));
        }
        return new GameDirectoryManagementSnapshot(++revision, entries);
    }

    /// Finds one effective directory by stable identifier.
    ///
    /// @param id target identifier
    /// @return attached effective directory
    private static GameDirectory findDirectory(GameDirectoryID id) {
        for (GameDirectory directory : GameDirectoryManager.getGameDirectories()) {
            if (directory.getId().equals(id)) {
                return directory;
            }
        }
        throw new IllegalArgumentException("Unknown game directory: " + id);
    }

    /// Rejects duplicate custom display names while allowing an entry to retain its own current name.
    ///
    /// @param displayName requested custom display name
    /// @param excludedId existing entry being edited, or `null` when adding
    private static void requireUniqueCustomName(String displayName, @Nullable GameDirectoryID excludedId) {
        for (GameDirectory directory : GameDirectoryManager.getGameDirectories()) {
            if (excludedId != null && excludedId.equals(directory.getId())) {
                continue;
            }
            @Nullable String existingName = GameDirectoryManager.getGameDirectoryCustomName(directory);
            if (displayName.equals(existingName)) {
                throw new IllegalArgumentException("Game directory name already exists");
            }
        }
    }

    /// Rejects calls after the adapter has released its launcher state subscriptions.
    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Game directory management service is closed");
        }
    }
}
