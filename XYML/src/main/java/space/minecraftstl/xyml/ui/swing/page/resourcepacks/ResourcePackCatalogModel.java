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
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionStage;

/// Supplies lazy installed-resource-pack state without exposing JavaFX or Swing types.
///
/// Implementations expose an exact count only after a lightweight path index succeeds. A viewport
/// request resolves only its measured desired path range on a background executor; it must not
/// introduce an arbitrary UI page size or eagerly parse metadata for off-screen paths.
@NotNullByDefault
public interface ResourcePackCatalogModel
        extends ViewportChoiceDataSource<ResourcePackCatalogItem>, AutoCloseable {
    /// Returns the latest immutable catalog state.
    ///
    /// @return current catalog snapshot
    ResourcePackCatalogSnapshot snapshot();

    /// Returns the current immutable shallow path index in source order.
    ///
    /// The list contains no parsed resource-pack metadata and is empty before a successful index.
    /// Callers may use it to derive filtered viewport indexes without widening metadata loads.
    ///
    /// @return immutable current indexed paths
    @Unmodifiable List<Path> indexedPaths();

    /// Registers for future catalog transitions on the publishing thread.
    ///
    /// Implementations may coalesce a committed transition superseded before delivery. A listener
    /// must therefore re-read [#snapshot()] instead of treating notifications as an event log.
    ///
    /// @param listener snapshot transition listener
    /// @return independently cancellable listener registration
    Subscription subscribe(ValueChangeListener<ResourcePackCatalogSnapshot> listener);

    /// Starts the first disk scan only if no scan has been attempted.
    void loadIfNeeded();

    /// Cancels the obsolete generation and starts one fresh disk scan.
    void refresh();

    /// Selects one pack by its normalized absolute path.
    ///
    /// @param path path belonging to the current complete catalog
    void selectResourcePack(Path path);

    /// Clears the stable selection without changing indexed content.
    void clearSelection();

    /// Imports multiple resource-pack archives or directories as one serialized catalog write.
    ///
    /// The source list is captured defensively. An empty list, duplicate target name, unsupported
    /// source shape, or existing target completes exceptionally without overwriting catalog files.
    /// Preflight and private staging avoid expected partial copies. If an external process creates
    /// a later target during publication, already published packs and the external target are kept;
    /// the Future fails after the actual partial state has been rescanned and published.
    ///
    /// @param sources source archives or directories
    /// @return asynchronous terminal snapshot after the mandatory shallow follow-up scan
    CompletionStage<ResourcePackCatalogSnapshot> importResourcePacks(List<Path> sources);

    /// Persistently enables one pack identified by its stable current-index path.
    ///
    /// @param path direct-child path belonging to the current complete catalog
    /// @return asynchronous completion after options persistence and shallow reindexing
    CompletionStage<ResourcePackCatalogSnapshot> enableResourcePack(Path path);

    /// Persistently disables one pack identified by its stable current-index path.
    ///
    /// @param path direct-child path belonging to the current complete catalog
    /// @return asynchronous completion after options persistence and shallow reindexing
    CompletionStage<ResourcePackCatalogSnapshot> disableResourcePack(Path path);

    /// Persistently disables and then deletes one current pack by stable path.
    ///
    /// @param path direct-child path belonging to the current complete catalog
    /// @return asynchronous completion after deletion and shallow reindexing
    CompletionStage<ResourcePackCatalogSnapshot> deleteResourcePack(Path path);

    /// Closes commands and subscriptions, cancels index, viewport, and pre-commit write work.
    ///
    /// A write that already crossed its irreversible commit point continues only long enough to
    /// report its actual Future outcome; it cannot publish a late catalog state after closure.
    @Override
    void close();
}
