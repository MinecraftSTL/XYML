// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management;

import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;

import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Font;
import java.util.Objects;

/// Creates consistently styled setting sections for the shared instance and preset editor.
@NotNullByDefault
final class InstanceGameSettingsSection {
    /// Prevents instantiation of the layout helpers.
    private InstanceGameSettingsSection() {
    }

    /// Creates one unframed three-column section.
    ///
    /// @param name stable component name
    /// @param title localized section title
    /// @return configured section panel
    static JPanel sectionPanel(String name, String title) {
        JPanel section = new JPanel(new MigLayout(
                "insets 0, fillx, wrap 3", "[26!,center]8[280!,fill]16[grow,fill]", "[]10[]"));
        section.setName(Objects.requireNonNull(name, "name"));
        section.setOpaque(false);
        JLabel heading = new JLabel(Objects.requireNonNull(title, "title"));
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 15.0F));
        section.add(heading, "span 3, growx");
        return section;
    }
}
