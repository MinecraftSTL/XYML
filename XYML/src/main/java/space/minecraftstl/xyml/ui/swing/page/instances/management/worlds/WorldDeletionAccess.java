// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management.worlds;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameRepository;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;
import space.minecraftstl.xyml.util.io.DeletionMode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/// Headless access to the world's existing metadata and session-lock-aware deletion implementation.
@NotNullByDefault
public final class WorldDeletionAccess {
    /// Existing world storage boundary.
    private final FileSystemWorldCatalogAccess access;

    /// Binds storage to one installed instance.
    /// @param repository owning repository
    /// @param instanceId selected instance
    public WorldDeletionAccess(GameRepository repository, GameInstanceID instanceId) {
        access = new FileSystemWorldCatalogAccess(repository, instanceId);
    }

    /// Lists current direct-child world directories.
    /// @return immutable paths
    /// @throws IOException when the saves directory cannot be read
    public @Unmodifiable List<Path> paths() throws IOException {
        return access.indexWorldDirectories(access.savesDirectory(), new LoadCancellation());
    }

    /// Validates readability without mutating a world.
    /// @param path current world directory
    /// @throws IOException when the world is unreadable
    public void validate(Path path) throws IOException {
        if (!access.loadItem(path, new LoadCancellation()).readable()) {
            throw new IOException("Unreadable world: " + path);
        }
    }

    /// Deletes through the same session-lock-aware boundary as the world catalog.
    /// @param path current world directory
    /// @param mode deletion behavior
    /// @throws IOException when the world is unreadable, locked, or deletion fails
    public void delete(Path path, DeletionMode mode) throws IOException {
        LoadCancellation cancellation = new LoadCancellation();
        access.delete(access.loadItem(path, cancellation), mode, cancellation);
    }
}
