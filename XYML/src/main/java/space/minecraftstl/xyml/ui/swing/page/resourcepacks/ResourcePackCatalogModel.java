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
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceDataSource;

import java.nio.file.Path;

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

    /// Cancels index and viewport work, terminates subscriptions, and disables future commands.
    @Override
    void close();
}
