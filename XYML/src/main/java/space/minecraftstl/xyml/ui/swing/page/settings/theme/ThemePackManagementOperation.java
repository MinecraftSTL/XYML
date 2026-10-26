// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings.theme;

import org.jetbrains.annotations.NotNullByDefault;

/// Exclusive theme-pack mutation currently represented by the management UI.
@NotNullByDefault
public enum ThemePackManagementOperation {
    /// No mutation is active.
    NONE,

    /// A local archive is being validated and imported.
    IMPORTING,

    /// A selected theme is being persisted and applied.
    APPLYING,

    /// An installed package is being revalidated and deleted.
    DELETING,

    /// An installed package directory is being revalidated and opened.
    LOCATING
}
