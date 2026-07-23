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
package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;

import java.util.List;
import java.util.concurrent.CompletionStage;

/// Loads a complete game-version catalog without exposing a UI toolkit.
///
/// Implementations decide their own network or cache strategy and should observe the cooperative
/// cancellation signal before expensive work and before publishing externally visible side effects.
@FunctionalInterface
@NotNullByDefault
public interface GameVersionCatalogSource {
    /// Starts one catalog load.
    ///
    /// @param cancellation cooperative cancellation signal owned by the catalog model
    /// @return eventual immutable catalog in source-defined display order
    CompletionStage<@Unmodifiable List<GameVersionCatalogItem>> load(LoadCancellation cancellation);
}
