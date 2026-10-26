// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
