// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.log;

import org.jetbrains.annotations.NotNullByDefault;

import java.awt.Component;
import java.nio.file.Path;

/// Presents export outcomes without coupling the testable log panel to a concrete dialog implementation.
@NotNullByDefault
interface GameLogWindowNotifier {
    /// Presents a successful export and its destination.
    ///
    /// @param owner component owning the notification
    /// @param file exported file
    void exportSucceeded(Component owner, Path file);

    /// Presents an export failure with diagnostic context.
    ///
    /// @param owner component owning the notification
    /// @param operation localized operation name
    /// @param failure export failure
    void exportFailed(Component owner, String operation, Throwable failure);
}
