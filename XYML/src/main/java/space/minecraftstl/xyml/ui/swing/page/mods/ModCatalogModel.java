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
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/// Supplies one searchable, filtered, viewport-driven installed-Mod catalog.
@NotNullByDefault
public interface ModCatalogModel extends ViewportChoiceDataSource<ModCatalogItem>, AutoCloseable {
    /// Returns the latest immutable catalog state.
    ///
    /// @return current snapshot
    ModCatalogSnapshot snapshot();

    /// Registers for future catalog transitions on their publishing thread.
    ///
    /// @param listener transition listener
    /// @return independently cancellable registration
    Subscription subscribe(ValueChangeListener<ModCatalogSnapshot> listener);

    /// Returns the normalized installed-Mod directory.
    ///
    /// @return Mod directory
    Path modsDirectory();

    /// Returns the current filtered row identities without materializing viewport metadata rows.
    ///
    /// The order exactly matches this data source's logical indexes. A local key remains stable
    /// when enabling or disabling a Mod renames its physical file with the disabled suffix.
    ///
    /// @return immutable filtered local keys in logical list order
    @Unmodifiable List<String> filteredLocalKeys();

    /// Starts the first background `ModManager` refresh when still idle.
    void loadIfNeeded();

    /// Cancels an obsolete index generation and starts a fresh background refresh.
    void refresh();

    /// Applies a case-insensitive in-memory query without disk access.
    ///
    /// @param query user query, normalized by the model
    void setSearchQuery(String query);

    /// Applies an enabled-state filter without disk access.
    ///
    /// @param filter new filter
    void setFilter(ModCatalogFilter filter);

    /// Selects one current filtered row by its rename-stable local key.
    ///
    /// @param localKey key belonging to the current filtered index
    void selectMod(String localKey);

    /// Clears the current stable selection.
    void clearSelection();

    /// Classifies source paths whose local keys conflict with installed or earlier selected Mods.
    ///
    /// This method reads only the current immutable index and performs no disk access.
    ///
    /// @param sources candidate import sources
    /// @return immutable normalized conflicting sources in candidate order
    @Unmodifiable List<Path> findImportConflicts(@Unmodifiable List<Path> sources);

    /// Enables or disables one indexed Mod and refreshes the exact catalog afterward.
    ///
    /// @param localKey rename-stable target key
    /// @param enabled desired on-disk state
    /// @return asynchronous terminal snapshot
    CompletionStage<ModCatalogSnapshot> setModEnabled(String localKey, boolean enabled);

    /// Enables or disables one non-empty batch of indexed Mods with one follow-up refresh.
    ///
    /// @param localKeys immutable rename-stable target keys
    /// @param enabled desired on-disk state
    /// @return asynchronous terminal snapshot
    CompletionStage<ModCatalogSnapshot> setModsEnabled(
            @Unmodifiable List<String> localKeys,
            boolean enabled);

    /// Imports valid local Mod archives and refreshes the exact catalog afterward.
    ///
    /// @param sources source files captured defensively
    /// @param conflictActions explicit decisions for sources previously reported as conflicting
    /// @return asynchronous terminal snapshot
    CompletionStage<ModCatalogSnapshot> importMods(
            @Unmodifiable List<Path> sources,
            @Unmodifiable Map<Path, ModImportConflictAction> conflictActions);

    /// Permanently deletes one indexed current Mod and refreshes afterward.
    ///
    /// @param localKey rename-stable target key
    /// @return asynchronous terminal snapshot
    CompletionStage<ModCatalogSnapshot> deleteMod(String localKey);

    /// Permanently deletes one non-empty batch of indexed current Mods with one follow-up refresh.
    ///
    /// @param localKeys immutable rename-stable target keys
    /// @return asynchronous terminal snapshot
    CompletionStage<ModCatalogSnapshot> deleteMods(@Unmodifiable List<String> localKeys);

    /// Cancels outstanding pre-commit work and rejects later commands and loads.
    @Override
    void close();
}
