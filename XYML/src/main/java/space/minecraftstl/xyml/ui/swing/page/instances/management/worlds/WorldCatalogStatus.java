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

/// Describes the availability of the shallow world-directory index.
@NotNullByDefault
public enum WorldCatalogStatus {
    /// The page has not requested a directory scan yet.
    IDLE,

    /// A background shallow directory scan is in progress.
    LOADING,

    /// The exact directory count and index are available for viewport reads.
    READY,

    /// The latest shallow directory scan failed and can be retried.
    FAILED
}
