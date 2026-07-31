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
package space.minecraftstl.xyml.ui.swing.page.downloads.loaders;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.concurrent.CompletionStage;

/// Refreshes exactly one explicitly selected loader catalog without exposing a UI toolkit.
///
/// Implementations may use Core network tasks, but callers must invoke this only from an explicit
/// refresh action after both a game version and loader kind are selected.
@FunctionalInterface
@NotNullByDefault
public interface GameLoaderCatalogSource {
    /// Starts a refresh for one exact catalog request.
    ///
    /// @param request explicit selected game-version and loader-kind request
    /// @return eventual immutable concrete loader version items
    CompletionStage<@Unmodifiable List<GameLoaderCatalogItem>> refreshAsync(
            GameLoaderCatalogRequest request);
}
