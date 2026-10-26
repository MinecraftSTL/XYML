// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.schematics;

import org.jetbrains.annotations.NotNullByDefault;

/// Describes the lifecycle of the model's independently tracked file-system write operation.
@NotNullByDefault
public enum SchematicBrowserWriteStatus {
    /// No write is running and the most recent write did not fail.
    IDLE,

    /// Exactly one import, directory creation, or deletion is running on the injected executor.
    BUSY,

    /// The most recent write failed while the last stable directory listing was retained.
    ERROR
}
