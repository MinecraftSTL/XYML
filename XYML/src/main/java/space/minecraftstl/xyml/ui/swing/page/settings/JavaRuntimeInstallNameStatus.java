// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;

/// Classifies a proposed local Java archive installation name for precise Swing validation feedback.
@NotNullByDefault
public enum JavaRuntimeInstallNameStatus {
    /// The name is syntactically valid, not reserved, and not currently installed.
    VALID,

    /// The name is empty or contains characters outside `[a-zA-Z0-9.\-_]`.
    INVALID_CHARACTERS,

    /// The name starts with the launcher-reserved Mojang runtime prefix.
    RESERVED_MOJANG_PREFIX,

    /// The name is a platform-reserved device name such as `CON`, `NUL`, `COM1`, or `LPT1.txt`.
    RESERVED_PLATFORM_NAME,

    /// The name is `.` or `..`, has an unsafe trailing character, or does not resolve to one direct child.
    UNSAFE_PATH,

    /// A managed Java runtime with this platform and name already exists locally.
    ALREADY_INSTALLED
}
