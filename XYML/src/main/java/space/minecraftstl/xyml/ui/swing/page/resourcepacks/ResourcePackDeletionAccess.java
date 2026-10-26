// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.resourcepacks;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameRepository;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;
import space.minecraftstl.xyml.util.io.DeletionMode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/// Blocking headless boundary sharing catalog indexing, options persistence, and deletion transactions.
@NotNullByDefault
public final class ResourcePackDeletionAccess {
    /// Existing serialized catalog storage implementation.
    private final FileSystemResourcePackCatalogAccess access;

    /// Binds storage to one instance without performing I/O.
    /// @param repository owning repository
    /// @param instanceId instance identifier
    public ResourcePackDeletionAccess(GameRepository repository, GameInstanceID instanceId) {
        access = new FileSystemResourcePackCatalogAccess(repository, instanceId);
    }

    /// Lists the supported direct managed children.
    /// @return immutable pack paths
    /// @throws IOException when indexing fails
    public @Unmodifiable List<Path> paths() throws IOException {
        return access.loadIndex(new LoadCancellation()).paths();
    }

    /// Disables and deletes one current pack through the catalog transaction.
    /// @param path managed direct child
    /// @param mode requested deletion mode
    /// @throws IOException when deletion or refresh fails
    public void delete(Path path, DeletionMode mode) throws IOException {
        ResourcePackCatalogMutationAccessResult result = access.mutateAndLoadIndex(
                new ResourcePackDeleteMutation(path, mode), new LoadCancellation(), () -> { });
        if (result.mutationFailure() instanceof IOException failure) {
            throw failure;
        }
        if (result.mutationFailure() instanceof RuntimeException failure) {
            throw failure;
        }
        if (result.mutationFailure() != null) {
            throw new IOException("Resource-pack deletion failed", result.mutationFailure());
        }
    }
}
