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

/// Lifecycle of one lazily loaded local resource-pack catalog.
@NotNullByDefault
public enum ResourcePackCatalogStatus {
    /// No disk scan has been requested.
    IDLE,

    /// The latest disk-scan generation is active.
    LOADING,

    /// The latest disk scan completed and its catalog is current.
    READY,

    /// The managed Minecraft version predates resource-pack support.
    UNSUPPORTED,

    /// The latest disk scan failed and may be retried.
    FAILED
}
