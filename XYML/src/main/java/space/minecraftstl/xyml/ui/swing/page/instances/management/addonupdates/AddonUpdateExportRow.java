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
package space.minecraftstl.xyml.ui.swing.page.instances.management.addonupdates;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Immutable values written for one actionable add-on update in an exported CSV list.
///
/// @param fileName local add-on file name
/// @param currentVersion currently installed version
/// @param targetVersion compatible target version
/// @param source remote catalog display name
@NotNullByDefault
record AddonUpdateExportRow(
        String fileName,
        String currentVersion,
        String targetVersion,
        String source) {
    /// Validates every exported cell before background file I/O begins.
    AddonUpdateExportRow {
        fileName = Objects.requireNonNull(fileName, "fileName");
        currentVersion = Objects.requireNonNull(currentVersion, "currentVersion");
        targetVersion = Objects.requireNonNull(targetVersion, "targetVersion");
        source = Objects.requireNonNull(source, "source");
    }
}
