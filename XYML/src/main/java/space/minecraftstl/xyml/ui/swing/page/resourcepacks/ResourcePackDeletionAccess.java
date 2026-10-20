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
