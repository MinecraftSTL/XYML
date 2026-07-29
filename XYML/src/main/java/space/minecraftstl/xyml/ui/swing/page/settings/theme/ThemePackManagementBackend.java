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
package space.minecraftstl.xyml.ui.swing.page.settings.theme;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/// Background boundary for the embedded and locally installed theme-pack inventory.
///
/// Every method must defer repository, file, and image work to the supplied non-EDT executor.
@NotNullByDefault
public interface ThemePackManagementBackend {
    /// Loads every selectable embedded and installed theme.
    ///
    /// @param executor caller-owned non-EDT worker executor
    /// @return immutable theme index
    CompletionStage<@Unmodifiable List<ThemePackItem>> loadAll(Executor executor);

    /// Imports one local archive without replacing an existing package.
    ///
    /// @param archive selected `.xyml-theme` archive
    /// @param executor caller-owned non-EDT worker executor
    /// @return immutable imported themes from the newly installed package
    CompletionStage<@Unmodifiable List<ThemePackItem>> importArchive(Path archive, Executor executor);

    /// Revalidates and deletes the exact installed package represented by an item.
    ///
    /// @param item installed theme item authorizing its containing package
    /// @param executor caller-owned non-EDT worker executor
    /// @return completion stage resolved after deletion
    CompletionStage<@Nullable Void> deleteInstalled(ThemePackItem item, Executor executor);

    /// Revalidates the exact installation directory before desktop integration opens it.
    ///
    /// @param item installed theme item authorizing its containing package
    /// @param executor caller-owned non-EDT worker executor
    /// @return exact current installation directory
    CompletionStage<Path> locateInstalled(ThemePackItem item, Executor executor);
}
