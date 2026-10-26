// SPDX-FileCopyrightText: Copyright (C) 2025 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml;

import org.jetbrains.annotations.NotNullByDefault;

/// Placeholder entry point replaced by the application implementation in the shadow JAR.
@NotNullByDefault
public final class EntryPoint {
    /// Prevents construction of the placeholder entry point.
    private EntryPoint() {
    }

    /// Rejects execution when the shadow JAR did not replace this placeholder.
    ///
    /// @param args ignored launcher arguments
    /// @throws AssertionError always, because this placeholder must never execute
    public static void main(String[] args) {
        throw new AssertionError("This method should not be called, please verify that XYML is complete.");
    }
}
