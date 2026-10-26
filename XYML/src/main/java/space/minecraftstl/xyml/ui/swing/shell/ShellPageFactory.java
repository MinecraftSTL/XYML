// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.shell;

import org.jetbrains.annotations.NotNullByDefault;

/// Creates one destination page when the user first visits it.
///
/// @param <T> the page representation supplied by a frontend
@FunctionalInterface
@NotNullByDefault
public interface ShellPageFactory<T> {
    /// Creates the destination page.
    ///
    /// @return a newly created page
    T createPage();
}
