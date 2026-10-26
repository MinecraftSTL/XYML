// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;

/// Selects whether the complete game-settings editor manages instance overrides or direct preset values.
@NotNullByDefault
public enum GameSettingsEditorPresentation {
    /// Shows instance inheritance controls, the page heading, and local save/reload actions.
    INSTANCE,

    /// Hides inheritance controls and outer chrome while retaining the complete settings surface.
    GLOBAL_PRESET
}
