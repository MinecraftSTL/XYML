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

/// Describes the explicitly detected platform prompt behavior.
@NotNullByDefault
public enum StartupPlatformPrompt {
    /// The current platform requires neither a prompt nor a state write.
    NONE,

    /// The current platform is supported and should be marked without presenting a dialog.
    MARK_SUPPORTED,

    /// Windows on ARM64 requires its dedicated informational text.
    WINDOWS_ARM64,

    /// Supported Linux LoongArch or MIPS variants require their dedicated informational text.
    LOONGARCH,

    /// Another non-x86 platform requires the general compatibility warning.
    OTHER_UNSUPPORTED
}
