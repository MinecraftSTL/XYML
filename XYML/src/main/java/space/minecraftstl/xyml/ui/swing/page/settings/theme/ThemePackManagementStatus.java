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

/// Lifecycle status of the local theme-pack inventory.
@NotNullByDefault
public enum ThemePackManagementStatus {
    /// No inventory request has started.
    IDLE,

    /// A caller-owned worker is loading the inventory.
    LOADING,

    /// The latest inventory or mutation completed successfully.
    READY,

    /// The latest inventory request or mutation failed.
    FAILED,

    /// The model is terminal and rejects new work.
    CLOSED
}
