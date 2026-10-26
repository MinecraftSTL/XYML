// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.mods;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/// Reports one Mod source whose current on-disk conflict still needs a user decision.
@NotNullByDefault
final class ModImportConflictException extends IOException {
    /// Conflicting normalized source path.
    private final Path source;

    /// Creates one immutable conflict failure.
    ///
    /// @param source conflicting import source
    ModImportConflictException(Path source) {
        super("A Mod with the same local name already exists: "
                + Objects.requireNonNull(source, "source").getFileName());
        this.source = source.toAbsolutePath().normalize();
    }

    /// Returns the source requiring a conflict decision.
    ///
    /// @return normalized source path
    Path source() {
        return source;
    }
}
