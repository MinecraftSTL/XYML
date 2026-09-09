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
// Added by MinecraftSTL in 2026 for explicit tolerant-read diagnostics.
package space.minecraftstl.xyml.library.nbt.io;

import space.minecraftstl.xyml.library.nbt.NBTElement;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// A detached root together with the diagnostics produced while reading it.
///
/// @param <E> root element type
@NotNullByDefault
public final class NBTReadResult<E extends NBTElement> {
    private final E root;
    private final NBTReadReport report;

    /// Creates a read result.
    ///
    /// @param root detached root
    /// @param report immutable read report
    public NBTReadResult(E root, NBTReadReport report) {
        this.root = Objects.requireNonNull(root, "root");
        this.report = Objects.requireNonNull(report, "report");
    }

    /// Returns the detached root.
    public E root() {
        return root;
    }

    /// Returns the read report.
    public NBTReadReport report() {
        return report;
    }

    /// Bean-style alias for [#root()].
    public E getRoot() {
        return root;
    }

    /// Bean-style alias for [#report()].
    public NBTReadReport getReport() {
        return report;
    }
}
