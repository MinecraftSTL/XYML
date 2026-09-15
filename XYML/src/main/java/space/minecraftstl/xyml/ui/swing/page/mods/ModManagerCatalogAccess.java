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
package space.minecraftstl.xyml.ui.swing.page.mods;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.addon.mod.LocalModFile;
import space.minecraftstl.xyml.addon.mod.ModManager;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameRepository;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/// Real blocking adapter around `GameRepository`, `ModManager`, and `LocalModFile`.
///
/// Every method that can touch disk is called only by `DefaultModCatalogModel` on its injected
/// background executor. The adapter performs no network access and introduces no UI dependency.
@NotNullByDefault
final class ModManagerCatalogAccess implements ModCatalogAccess {
    /// Core manager owning all index and mutation semantics.
    private final ModManager manager;

    /// Creates a real adapter for one managed game instance.
    ///
    /// @param repository real game repository
    /// @param instanceId stable managed instance identifier
    ModManagerCatalogAccess(GameRepository repository, GameInstanceID instanceId) {
        manager = new ModManager(
                Objects.requireNonNull(repository, "repository"),
                Objects.requireNonNull(instanceId, "instanceId"));
    }

    /// Returns the normalized path supplied by the real repository.
    @Override
    public Path modsDirectory() {
        return manager.getDirectory().toAbsolutePath().normalize();
    }

    /// Runs one complete Core refresh after observing pre-work cancellation.
    @Override
    public ModCatalogIndex refresh(LoadCancellation cancellation) throws IOException {
        requireNotCancelled(cancellation);
        return refreshIndex();
    }

    /// Materializes only the requested immutable entries.
    @Override
    public @Unmodifiable List<ModCatalogItem> loadItems(
            @Unmodifiable List<ModCatalogEntry> entries,
            LoadCancellation cancellation) {
        List<ModCatalogItem> items = new ArrayList<>(entries.size());
        for (ModCatalogEntry entry : entries) {
            requireNotCancelled(cancellation);
            items.add(entry.toItem());
        }
        return List.copyOf(items);
    }

    /// Applies one Core mutation and always attempts to report the actual refreshed index.
    @Override
    public ModCatalogMutationResult mutateAndRefresh(
            ModCatalogMutation mutation,
            LoadCancellation cancellation) throws IOException {
        requireNotCancelled(cancellation);
        @Nullable Throwable mutationFailure = null;
        try {
            applyMutation(mutation, cancellation);
        } catch (IOException | RuntimeException failure) {
            mutationFailure = failure;
        }
        return new ModCatalogMutationResult(refreshIndex(), mutationFailure);
    }

    /// Dispatches one validated internal mutation.
    ///
    /// @param mutation requested mutation
    /// @param cancellation cooperative pre-commit cancellation
    /// @throws IOException when a Core file operation fails
    private void applyMutation(
            ModCatalogMutation mutation,
            LoadCancellation cancellation) throws IOException {
        if (mutation instanceof ModCatalogMutation.Import importMutation) {
            importMods(importMutation, cancellation);
        } else if (mutation instanceof ModCatalogMutation.Enabled enabledMutation) {
            setEnabled(enabledMutation.localKey(), enabledMutation.enabled());
        } else if (mutation instanceof ModCatalogMutation.EnabledBatch enabledBatch) {
            setEnabled(enabledBatch.localKeys(), enabledBatch.enabled(), cancellation);
        } else if (mutation instanceof ModCatalogMutation.Delete deleteMutation) {
            manager.removeMods(findCurrent(deleteMutation.localKey()));
        } else if (mutation instanceof ModCatalogMutation.DeleteBatch deleteBatch) {
            deleteMods(deleteBatch.localKeys(), cancellation);
        } else {
            throw new IllegalArgumentException("Unsupported Mod mutation " + mutation.getClass().getName());
        }
    }

    /// Preflights and applies one ordered import plan against the current Core index.
    ///
    /// @param mutation immutable import sources and conflict decisions
    /// @param cancellation cooperative pre-commit cancellation
    /// @throws IOException when source inspection or copying fails
    private void importMods(
            ModCatalogMutation.Import mutation,
            LoadCancellation cancellation) throws IOException {
        @Unmodifiable List<Path> indexedPaths = manager.getLocalFiles().stream()
                .map(LocalModFile::getFile)
                .toList();
        ModImportFileOperations.importMods(
                manager.getDirectory(),
                indexedPaths,
                mutation.sources(),
                mutation.conflictActions(),
                cancellation);
    }

    /// Enables or disables the current Core object through exception-preserving manager methods.
    ///
    /// @param localKey rename-stable target key
    /// @param enabled desired suffix state
    /// @throws IOException when the rename fails
    private void setEnabled(String localKey, boolean enabled) throws IOException {
        LocalModFile target = findCurrent(localKey);
        setEnabled(target, enabled);
    }

    /// Preflights every stable key before applying one enabled state to the batch.
    ///
    /// @param localKeys immutable rename-stable target keys
    /// @param enabled desired suffix state
    /// @param cancellation cooperative cancellation before each irreversible rename
    /// @throws IOException when current index access or a rename fails
    private void setEnabled(
            @Unmodifiable List<String> localKeys,
            boolean enabled,
            LoadCancellation cancellation) throws IOException {
        @Unmodifiable List<LocalModFile> targets = findCurrent(localKeys);
        for (LocalModFile target : targets) {
            requireNotCancelled(cancellation);
            setEnabled(target, enabled);
        }
    }

    /// Enables or disables one already resolved current Core object.
    ///
    /// @param target exact current Core file
    /// @param enabled desired suffix state
    /// @throws IOException when the rename fails
    private void setEnabled(LocalModFile target, boolean enabled) throws IOException {
        Path path = target.getFile();
        boolean currentlyEnabled = !manager.isDisabled(path);
        if (enabled == currentlyEnabled) {
            return;
        }
        if (enabled) {
            manager.enableMod(path);
        } else {
            manager.disableMod(path);
        }
    }

    /// Preflights and deletes one stable-key batch through Core's existing batch operation.
    ///
    /// @param localKeys immutable rename-stable target keys
    /// @param cancellation cooperative cancellation before irreversible deletion
    /// @throws IOException when current index access or deletion fails
    private void deleteMods(
            @Unmodifiable List<String> localKeys,
            LoadCancellation cancellation) throws IOException {
        @Unmodifiable List<LocalModFile> targets = findCurrent(localKeys);
        requireNotCancelled(cancellation);
        manager.removeMods(targets.toArray(LocalModFile[]::new));
    }

    /// Resolves a mutation target from the manager's current exact index.
    ///
    /// @param localKey rename-stable key
    /// @return exact current Core file
    /// @throws IOException when current index access fails
    private LocalModFile findCurrent(String localKey) throws IOException {
        for (LocalModFile file : manager.getLocalFiles()) {
            if (file.getFileName().equals(localKey)) {
                return file;
            }
        }
        throw new IllegalArgumentException("Unknown current Mod: " + localKey);
    }

    /// Resolves every stable key before the first batch mutation starts.
    ///
    /// @param localKeys immutable rename-stable target keys
    /// @return immutable exact current Core files in requested order
    /// @throws IOException when current index access fails
    private @Unmodifiable List<LocalModFile> findCurrent(
            @Unmodifiable List<String> localKeys) throws IOException {
        List<LocalModFile> targets = new ArrayList<>(localKeys.size());
        for (String localKey : localKeys) {
            targets.add(findCurrent(localKey));
        }
        return List.copyOf(targets);
    }

    /// Rebuilds the complete immutable index from the real Core manager.
    ///
    /// @return sorted immutable index
    /// @throws IOException when Core refresh fails
    private ModCatalogIndex refreshIndex() throws IOException {
        manager.refresh();
        @Unmodifiable List<ModCatalogEntry> entries = manager.getLocalFiles().stream()
                .map(file -> ModCatalogEntry.from(file, manager))
                .toList();
        return new ModCatalogIndex(entries);
    }

    /// Throws when cooperative cancellation was requested before irreversible work.
    ///
    /// @param cancellation cancellation signal
    private static void requireNotCancelled(LoadCancellation cancellation) {
        if (cancellation.isCancelled()) {
            throw new CancellationException("Mod catalog operation was cancelled");
        }
    }
}
