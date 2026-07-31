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
import space.minecraftstl.xyml.game.GameJavaVersion;

import java.util.Objects;

/// Describes one current-platform Mojang Java runtime choice and its asynchronously loaded local state.
///
/// @param version Mojang game-runtime component and major Java version
/// @param installed whether a manifest or installation directory already exists locally
@NotNullByDefault
public record MojangJavaRuntimeOption(GameJavaVersion version, boolean installed) {
    /// Rejects an absent Mojang runtime version.
    public MojangJavaRuntimeOption {
        version = Objects.requireNonNull(version, "version");
    }
}
