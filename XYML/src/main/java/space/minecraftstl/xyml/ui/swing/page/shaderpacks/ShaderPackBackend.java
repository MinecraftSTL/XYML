// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.shaderpacks;

import org.jetbrains.annotations.NotNullByDefault;

/// Identifies one Minecraft shader runtime whose configuration can select a local shader pack.
@NotNullByDefault
public enum ShaderPackBackend {
    /// Iris or Oculus shader configuration.
    IRIS_OCULUS("Iris/Oculus"),

    /// OptiFine shader configuration.
    OPTIFINE("OptiFine");

    /// Stable third-party display name used in dialogs and diagnostics.
    private final String displayName;

    /// Creates one backend definition.
    ///
    /// @param displayName stable display name
    ShaderPackBackend(String displayName) {
        this.displayName = displayName;
    }

    /// Returns the stable display name.
    ///
    /// @return backend display name
    public String displayName() {
        return displayName;
    }
}
