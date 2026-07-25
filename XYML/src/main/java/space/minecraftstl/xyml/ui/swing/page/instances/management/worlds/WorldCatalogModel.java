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
package space.minecraftstl.xyml.ui.swing.page.instances.management.worlds;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceDataSource;

import java.nio.file.Path;
import java.util.concurrent.CompletionStage;

/// Supplies one lazily indexed instance-world catalog to a viewport-driven Swing list.
@NotNullByDefault
public interface WorldCatalogModel extends ViewportChoiceDataSource<WorldCatalogItem>, AutoCloseable {
    /// Returns the current immutable index and mutation state without blocking.
    ///
    /// @return latest catalog snapshot
    WorldCatalogSnapshot snapshot();

    /// Registers for future state transitions on the publishing thread.
    ///
    /// @param listener state listener
    /// @return independently cancellable listener registration
    Subscription subscribe(ValueChangeListener<WorldCatalogSnapshot> listener);

    /// Resolves the managed saves directory without enumerating it.
    ///
    /// @return normalized saves directory
    Path savesDirectory();

    /// Starts the first background shallow-directory index only while the model remains idle.
    void loadIfNeeded();

    /// Cancels a stale shallow index and starts a fresh one when no mutation owns the source.
    void refresh();

    /// Validates an archive on the background executor before a name prompt is shown.
    ///
    /// @param archive selected local archive
    /// @return asynchronously validated import candidate
    CompletionStage<WorldCatalogImport> inspectImport(Path archive);

    /// Installs one validated archive and refreshes the shallow index afterward.
    ///
    /// @param world validated import source
    /// @param targetName user-confirmed non-blank destination name
    /// @return terminal catalog state
    CompletionStage<WorldCatalogSnapshot> installWorld(WorldCatalogImport world, String targetName);

    /// Deletes one current readable world through the Core World API and refreshes afterward.
    ///
    /// @param world exact current materialized row
    /// @return terminal catalog state
    CompletionStage<WorldCatalogSnapshot> deleteWorld(WorldCatalogItem world);

    /// Cancels owned work and rejects future calls.
    @Override
    void close();
}
