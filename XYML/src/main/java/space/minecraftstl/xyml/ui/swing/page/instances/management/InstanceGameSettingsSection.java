/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
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
