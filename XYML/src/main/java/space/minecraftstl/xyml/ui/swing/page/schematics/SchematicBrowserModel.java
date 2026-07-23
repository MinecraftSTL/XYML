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
package space.minecraftstl.xyml.ui.swing.page.schematics;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceDataSource;

import java.nio.file.Path;
import java.util.concurrent.CompletionStage;

/// Supplies a shallow, viewport-driven schematic directory without exposing a desktop toolkit.
@NotNullByDefault
public interface SchematicBrowserModel extends ViewportChoiceDataSource<SchematicBrowserItem>, AutoCloseable {
    /// Returns the latest immutable browser state.
    ///
    /// @return current state
    SchematicBrowserSnapshot snapshot();

    /// Registers for future browser state changes on the publishing thread.
    ///
    /// @param listener state transition listener
    /// @return independently cancellable registration
    Subscription subscribe(ValueChangeListener<SchematicBrowserSnapshot> listener);

    /// Schedules the first shallow scan only while the model remains idle.
    ///
    /// @return completion of the current or newly scheduled state
    CompletionStage<SchematicBrowserSnapshot> loadIfNeeded();

    /// Schedules a replacement scan of the current directory.
    ///
    /// @return completion of the replacement scan
    CompletionStage<SchematicBrowserSnapshot> refresh();

    /// Schedules navigation to a child directory present in the current stable listing.
    ///
    /// @param directory exact known child path
    /// @return completion of the child scan
    CompletionStage<SchematicBrowserSnapshot> openDirectory(Path directory);

    /// Schedules navigation to the parent without crossing the configured root boundary.
    ///
    /// @return completion of the parent scan or the unchanged root state
    CompletionStage<SchematicBrowserSnapshot> returnToParent();

    /// Cancels scans and viewport loads and prevents every late result from publishing.
    @Override
    void close();
}
