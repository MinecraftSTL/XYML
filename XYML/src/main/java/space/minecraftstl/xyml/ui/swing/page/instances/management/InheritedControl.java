// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import java.util.Objects;

/// One value editor paired with its independent instance-override checkbox.
///
/// @param overrideBox local-override checkbox
/// @param editor effective value editor
/// @param <T> editor component type
@NotNullByDefault
record InheritedControl<T extends JComponent>(JCheckBox overrideBox, T editor) {
    /// Rejects missing components.
    InheritedControl {
        Objects.requireNonNull(overrideBox, "overrideBox");
        Objects.requireNonNull(editor, "editor");
    }
}
