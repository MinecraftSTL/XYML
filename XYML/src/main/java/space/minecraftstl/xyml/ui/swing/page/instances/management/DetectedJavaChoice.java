// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.setting.GameSettings;

import java.util.Objects;

/// Display value for one detected Java reference.
///
/// @param value persisted Java runtime reference
/// @param label visible runtime description
@NotNullByDefault
record DetectedJavaChoice(GameSettings.DetectedJava value, String label) {
    /// Rejects missing detected Java choice values.
    DetectedJavaChoice {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(label, "label");
    }

    /// Returns the visible combo-box label.
    @Override
    public String toString() {
        return label;
    }
}
