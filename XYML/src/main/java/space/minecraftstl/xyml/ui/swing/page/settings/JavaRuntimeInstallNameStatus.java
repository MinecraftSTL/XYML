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
package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;

/// Classifies a proposed local Java archive installation name for precise Swing validation feedback.
@NotNullByDefault
public enum JavaRuntimeInstallNameStatus {
    /// The name is syntactically valid, not reserved, and not currently installed.
    VALID,

    /// The name is empty or contains characters outside `[a-zA-Z0-9.\-_]`.
    INVALID_CHARACTERS,

    /// The name starts with the launcher-reserved Mojang runtime prefix.
    RESERVED_MOJANG_PREFIX,

    /// The name is a platform-reserved device name such as `CON`, `NUL`, `COM1`, or `LPT1.txt`.
    RESERVED_PLATFORM_NAME,

    /// The name is `.` or `..`, has an unsafe trailing character, or does not resolve to one direct child.
    UNSAFE_PATH,

    /// A managed Java runtime with this platform and name already exists locally.
    ALREADY_INSTALLED
}
