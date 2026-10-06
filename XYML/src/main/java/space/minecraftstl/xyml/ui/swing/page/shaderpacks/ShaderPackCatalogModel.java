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
package space.minecraftstl.xyml.ui.swing.page.shaderpacks;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.util.io.DeletionMode;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/// Supplies lazy installed shader-pack state without exposing Swing or JavaFX types.
@NotNullByDefault
public interface ShaderPackCatalogModel extends AutoCloseable {
    /// Returns the latest immutable catalog state.
    ///
    /// @return current catalog snapshot
    ShaderPackCatalogSnapshot snapshot();

    /// Registers for future catalog transitions.
    ///
    /// @param listener snapshot transition listener
    /// @return independently cancellable listener registration
    Subscription subscribe(ValueChangeListener<ShaderPackCatalogSnapshot> listener);

    /// Starts the first disk scan only if no scan has been attempted.
    void loadIfNeeded();

    /// Starts one fresh disk scan.
    void refresh();

    /// Selects one loaded row by stable path.
    ///
    /// @param path normalized absolute path belonging to the current catalog
    void selectShaderPack(Path path);

    /// Clears the stable list selection without changing disk content.
    void clearSelection();

    /// Imports every source as one serialized catalog mutation.
    ///
    /// @param sources source ZIP archives or directories
    /// @return asynchronous terminal snapshot after the mandatory follow-up scan
    CompletionStage<ShaderPackCatalogSnapshot> importShaderPacks(List<Path> sources);

    /// Persistently updates one pack in the supplied backends.
    ///
    /// @param path normalized absolute current-catalog path
    /// @param backends non-empty backend set to update
    /// @param enabled desired persistent state
    /// @return asynchronous terminal snapshot after the mandatory follow-up scan
    CompletionStage<ShaderPackCatalogSnapshot> setShaderPackEnabled(
            Path path,
            @Unmodifiable Set<ShaderPackBackend> backends,
            boolean enabled);

    /// Deletes one current pack using the requested deletion mode.
    ///
    /// @param path normalized absolute current-catalog path
    /// @param mode exact recycle-bin or permanent deletion mode
    /// @return asynchronous terminal snapshot after the mandatory follow-up scan
    CompletionStage<ShaderPackCatalogSnapshot> deleteShaderPack(Path path, DeletionMode mode);

    /// Deletes several current packs serially and continues after individual failures.
    ///
    /// @param paths normalized absolute current-catalog paths
    /// @param mode exact recycle-bin or permanent deletion mode
    /// @return asynchronous terminal snapshot after the mandatory follow-up scan
    CompletionStage<ShaderPackCatalogSnapshot> deleteShaderPacks(
            @Unmodifiable List<Path> paths,
            DeletionMode mode);

    /// Releases listeners and prevents new mutations.
    @Override
    void close();
}
