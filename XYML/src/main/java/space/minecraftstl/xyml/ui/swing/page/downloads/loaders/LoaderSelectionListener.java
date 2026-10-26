// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads.loaders;

import org.jetbrains.annotations.NotNullByDefault;

/// Receives an immutable loader selection after the wizard adds, removes, or clears a loader.
///
/// The callback runs on Swing's event dispatch thread. Consumers that mutate non-Swing state should
/// snapshot the value immediately and schedule their own work outside that thread.
@FunctionalInterface
@NotNullByDefault
public interface LoaderSelectionListener {
    /// Receives the newest dependency-safe selected loader snapshot.
    ///
    /// @param snapshot immutable current selection
    void selectionChanged(LoaderSelectionSnapshot snapshot);
}
