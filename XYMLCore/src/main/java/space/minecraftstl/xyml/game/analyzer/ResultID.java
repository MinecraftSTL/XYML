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
package space.minecraftstl.xyml.game.analyzer;

import org.jetbrains.annotations.NotNullByDefault;

/// Stable identifiers for the deliberately limited launch-log causes.
@NotNullByDefault
public enum ResultID {
    /// HotSpot terminated while compiling code with the C2 optimizing compiler.
    C2_COMPILER,

    /// A client-only mod or class was loaded by a dedicated server.
    CLIENT_MOD_ON_SERVER,

    /// Windows legacy code-page handling failed around a non-ASCII launch path.
    CODE_PAGE,

    /// A 32-bit Java runtime could not reserve the configured heap.
    JRE_32BIT,

    /// The selected Java major version conflicts with verified launch evidence.
    JRE_VERSION,

    /// The operating system could not commit enough physical or virtual memory.
    VIRTUAL_MEMORY,

    /// Forge reported a required mod dependency whose actual version is missing.
    FORGE_MISSING_DEPENDENCY,

    /// Fabric reported a hard missing mod dependency.
    FABRIC_MISSING_DEPENDENCY,

    /// A legacy Minecraft launch on Java 8 requires the Legacy Java Fixer compatibility mod.
    LEGACY_JAVA_FIXER,

    /// Fabric reported an explicit Indium, Iris, or Iris Flywheel compatibility failure.
    RENDERER_MOD_COMPATIBILITY
}
