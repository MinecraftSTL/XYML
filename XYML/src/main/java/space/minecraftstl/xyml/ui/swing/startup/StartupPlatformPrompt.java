// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.startup;

import org.jetbrains.annotations.NotNullByDefault;

/// Describes the explicitly detected platform prompt behavior.
@NotNullByDefault
public enum StartupPlatformPrompt {
    /// The current platform requires neither a prompt nor a state write.
    NONE,

    /// The current platform is supported and should be marked without presenting a dialog.
    MARK_SUPPORTED,

    /// Windows on ARM64 requires its dedicated informational text.
    WINDOWS_ARM64,

    /// Supported Linux LoongArch or MIPS variants require their dedicated informational text.
    LOONGARCH,

    /// Another non-x86 platform requires the general compatibility warning.
    OTHER_UNSUPPORTED
}
