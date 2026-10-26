// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.startup;

import org.jetbrains.annotations.NotNullByDefault;

/// Identifies one startup prompt for presentation and failure reporting.
@NotNullByDefault
public enum StartupPromptKind {
    /// Mandatory user-agreement gate.
    AGREEMENT,

    /// Notification that an invalid custom cache directory was reset.
    INVALID_CACHE_DIRECTORY,

    /// Platform support or compatibility notice.
    PLATFORM,

    /// Warning that the launcher Java runtime is deprecated.
    DEPRECATED_JAVA,

    /// Warning that the launcher JVM is running without JIT compilation.
    INTERPRETED_JAVA,

    /// Warning that the launcher is using software rendering.
    SOFTWARE_RENDERING,

    /// Optional April Fools language-switch invitation.
    APRIL_FOOLS
}
