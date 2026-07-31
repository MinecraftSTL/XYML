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
package space.minecraftstl.xyml.ui.swing.startup;

import org.jetbrains.annotations.NotNullByDefault;

/// Identifies one startup prompt for presentation and failure reporting.
@NotNullByDefault
public enum StartupPromptKind {
    /// Mandatory user-agreement gate.
    AGREEMENT,

    /// Notification that an invalid custom cache directory was reset.
    INVALID_CACHE_DIRECTORY,

    /// Platform support or compatibility notice.
    PLATFORM,

    /// Warning that the launcher Java runtime is deprecated.
    DEPRECATED_JAVA,

    /// Warning that the launcher JVM is running without JIT compilation.
    INTERPRETED_JAVA,

    /// Warning that the launcher is using software rendering.
    SOFTWARE_RENDERING,

    /// Optional April Fools language-switch invitation.
    APRIL_FOOLS
}
