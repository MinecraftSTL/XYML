// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;

import java.awt.Component;
import java.nio.file.Path;
import java.util.Optional;

/// Chooses one exact local destination for a remote world archive.
@FunctionalInterface
@NotNullByDefault
interface RemoteWorldArchiveSaveChooser {
    /// Opens or simulates one save-as interaction.
    ///
    /// @param owner component owning the interaction
    /// @param suggestedFileName safe provider-derived filename suggestion
    /// @return selected destination, or empty after cancellation
    Optional<Path> choose(Component owner, String suggestedFileName);
}
