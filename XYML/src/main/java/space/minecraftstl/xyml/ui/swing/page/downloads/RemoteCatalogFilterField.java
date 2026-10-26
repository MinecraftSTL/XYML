// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads;

import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Dimension;
import java.util.Objects;

/// Builds compact fixed-height label and control pairs for remote catalog filter rows.
@NotNullByDefault
final class RemoteCatalogFilterField {
    /// Prevents utility-class instantiation.
    private RemoteCatalogFilterField() {
    }

    /// Creates one fixed-height filter field with a leading label and growable control.
    ///
    /// @param label field label
    /// @param component field control
    /// @return transparent single-row field container
    static JPanel create(JLabel label, JComponent component) {
        JLabel resolvedLabel = Objects.requireNonNull(label, "label");
        JComponent resolvedComponent = Objects.requireNonNull(component, "component");
        JPanel field = new JPanel(new MigLayout("insets 0, fillx", "[][grow,fill]", "[40!]"));
        field.setOpaque(false);
        field.setMinimumSize(new Dimension(0, 0));
        field.add(resolvedLabel);
        resolvedComponent.setMinimumSize(new Dimension(0, 0));
        field.add(resolvedComponent, "growx, wmin 0, h 40!");
        return field;
    }
}
