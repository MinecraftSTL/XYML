// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.application;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.SwingThemeManager;
import space.minecraftstl.xyml.ui.swing.shell.ShellPageFactory;
import space.minecraftstl.xyml.ui.swing.shell.ShellPageId;

import javax.swing.JComponent;
import java.util.Map;

/// Creates the application window around a complete set of lazy page factories.
@FunctionalInterface
@NotNullByDefault
public interface SwingApplicationWindowFactory {
    /// Creates the native-window adapter.
    ///
    /// @param themeManager initialized by the concrete window before creating Swing components
    /// @param pageFactories immutable complete lazy page table
    /// @param presentation startup-provided text and transition policy
    /// @param animator shared animator owned by the composition
    /// @param models application models used by title-bar workflow controls
    /// @return application window adapter
    SwingApplicationWindow createWindow(
            SwingThemeManager themeManager,
            @Unmodifiable Map<ShellPageId, ShellPageFactory<? extends JComponent>> pageFactories,
            SwingApplicationPresentation presentation,
            SwingAnimator animator,
            SwingApplicationPageModels models);
}
