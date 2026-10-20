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
package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JLabel;
import javax.swing.JList;
import java.awt.Component;
import java.util.Objects;

/// Renders the shared remote-version ordering selector with localized labels.
@NotNullByDefault
final class RemoteAddonVersionSortRenderer extends DefaultListCellRenderer {
    /// Shared localized labels for the version-order choices.
    private final RemoteCatalogFilterStrings strings;

    /// Creates a renderer using one immutable filter-text bundle.
    ///
    /// @param strings localized version-order labels
    RemoteAddonVersionSortRenderer(RemoteCatalogFilterStrings strings) {
        this.strings = Objects.requireNonNull(strings, "strings");
    }

    /// Replaces the enum's technical name with its localized visible label.
    @Override
    public Component getListCellRendererComponent(
            JList<?> list,
            @Nullable Object value,
            int index,
            boolean isSelected,
            boolean cellHasFocus) {
        Component component = super.getListCellRendererComponent(
                list,
                value,
                index,
                isSelected,
                cellHasFocus);
        if (component instanceof JLabel label && value instanceof RemoteAddonVersionSortMode mode) {
            label.setText(strings.versionSortModeLabel(mode));
        }
        return component;
    }
}
