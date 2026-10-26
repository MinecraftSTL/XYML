// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.setting;

import org.jetbrains.annotations.NotNullByDefault;

/// Selects the running directory used as the automatic instance-configuration migration source.
@NotNullByDefault
public enum InstanceConfigMigrationSourceType {
    /// Use the target game repository's shared Minecraft running directory.
    GLOBAL,

    /// Use one explicitly selected isolated instance, including across game directories.
    INSTANCE
}
