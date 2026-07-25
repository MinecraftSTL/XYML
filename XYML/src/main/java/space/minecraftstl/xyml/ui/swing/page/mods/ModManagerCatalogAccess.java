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
import space.minecraftstl.xyml.addon.LocalAddonManager;
import space.minecraftstl.xyml.addon.mod.LocalModFile;
import space.minecraftstl.xyml.addon.mod.ModManager;
import space.minecraftstl.xyml.game.GameRepository;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;
import space.minecraftstl.xyml.util.io.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
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
    ModManagerCatalogAccess(GameRepository repository, String instanceId) {
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
            importMods(importMutation.sources(), cancellation);
        } else if (mutation instanceof ModCatalogMutation.Enabled enabledMutation) {
            setEnabled(enabledMutation.localKey(), enabledMutation.enabled());
        } else if (mutation instanceof ModCatalogMutation.Delete deleteMutation) {
            manager.removeMods(findCurrent(deleteMutation.localKey()));
        } else {
            throw new IllegalArgumentException("Unsupported Mod mutation " + mutation.getClass().getName());
        }
    }

    /// Validates every import source before copying the first file, then delegates to Core.
    ///
    /// @param sources immutable normalized source paths
    /// @param cancellation cooperative pre-commit cancellation
    /// @throws IOException when source inspection or copying fails
    private void importMods(
            @Unmodifiable List<Path> sources,
            LoadCancellation cancellation) throws IOException {
        Set<String> existingKeys = new HashSet<>();
        for (LocalModFile file : manager.getLocalFiles()) {
            existingKeys.add(file.getFileName().toLowerCase(Locale.ROOT));
        }

        Set<String> importKeys = new HashSet<>();
        for (Path source : sources) {
            requireNotCancelled(cancellation);
            if (!Files.isRegularFile(source)) {
                throw new IOException("Mod source is not a regular file: " + source);
            }
            if (!ModManager.isFileNameMod(source)) {
                throw new IllegalArgumentException("Unsupported Mod file: " + source);
            }
            String localKey = localKey(source).toLowerCase(Locale.ROOT);
            if (!importKeys.add(localKey)) {
                throw new IllegalArgumentException("Duplicate Mod import target: " + source.getFileName());
            }
            if (existingKeys.contains(localKey)
                    || manager.hasSimpleMod(Objects.requireNonNull(source.getFileName()).toString())) {
                throw new IOException("A Mod with the same local name already exists: " + source.getFileName());
            }
        }

        // Crossing this loop starts irreversible copies; completion always performs a full refresh.
        for (Path source : sources) {
            manager.addMod(source);
        }
    }

    /// Enables or disables the current Core object through exception-preserving manager methods.
    ///
    /// @param localKey rename-stable target key
    /// @param enabled desired suffix state
    /// @throws IOException when the rename fails
    private void setEnabled(String localKey, boolean enabled) throws IOException {
        LocalModFile target = findCurrent(localKey);
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

    /// Derives the same rename-stable key used by `LocalModFile`.
    ///
    /// @param source candidate import source
    /// @return stable local key
    private static String localKey(Path source) {
        String addOnName = LocalAddonManager.getLocalAddonName(source);
        return FileUtils.getNameWithoutExtension(Path.of(addOnName));
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
