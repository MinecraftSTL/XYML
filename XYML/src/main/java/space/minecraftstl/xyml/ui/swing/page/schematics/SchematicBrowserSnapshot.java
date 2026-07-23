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
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.OptionalInt;

/// Immutable presentation-neutral state for one schematic browser directory.
///
/// @param rootDirectory immutable navigation boundary
/// @param currentDirectory directory represented by the last successful scan
/// @param itemCount exact indexed row count, or empty before the first successful scan
/// @param contentRevision revision incremented by each successfully committed scan
/// @param status current directory lifecycle
/// @param failureMessage latest scan failure text, or null outside the error state
/// @param canReturnToParent whether parent navigation remains inside the root boundary
@NotNullByDefault
public record SchematicBrowserSnapshot(
        Path rootDirectory,
        Path currentDirectory,
        OptionalInt itemCount,
        long contentRevision,
        SchematicBrowserStatus status,
        @Nullable String failureMessage,
        boolean canReturnToParent) {
}
