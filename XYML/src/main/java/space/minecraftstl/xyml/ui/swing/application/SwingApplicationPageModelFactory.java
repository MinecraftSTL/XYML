// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.application;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.ui.swing.page.downloads.DownloadPageNavigation;
import space.minecraftstl.xyml.ui.swing.shell.ShellPageId;

import java.util.function.Consumer;

/// Builds page models after the composition has established its shell-navigation reference.
@FunctionalInterface
@NotNullByDefault
public interface SwingApplicationPageModelFactory {
    /// Builds every page model and its ordered owned-resource lifecycle.
    ///
    /// The navigation consumer remains valid for the composition lifetime and delegates to the
    /// hosted shell once the window factory has returned.
    ///
    /// @param navigateCommand command that selects a stable shell destination
    /// @param downloadsNavigation request channel for lazily selecting a download category
    /// @return page models and ordered owned resources
    SwingApplicationPageModels createModels(
            Consumer<ShellPageId> navigateCommand,
            DownloadPageNavigation downloadsNavigation);
}
