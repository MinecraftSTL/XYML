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
import space.minecraftstl.xyml.addon.RemoteAddonRepository;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JLabel;
import javax.swing.JList;
import java.awt.Component;
import java.util.Objects;

/// Renders Core catalog sort values with the launcher's established labels.
@NotNullByDefault
final class RemoteCatalogSortRenderer extends DefaultListCellRenderer {
    /// Shared visible category and sort text.
    private final RemoteCatalogFilterStrings strings;

    /// Creates a renderer for one catalog filter bundle.
    ///
    /// @param strings filter text bundle
    RemoteCatalogSortRenderer(RemoteCatalogFilterStrings strings) {
        this.strings = Objects.requireNonNull(strings, "strings");
    }

    /// Renders one sort value while preserving standard look-and-feel selection colors.
    ///
    /// @param list owning list
    /// @param value sort value, or null while the combo box has no value
    /// @param index item index
    /// @param isSelected whether the item is selected
    /// @param cellHasFocus whether the item owns focus
    /// @return configured renderer component
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
        if (component instanceof JLabel label && value instanceof RemoteAddonRepository.SortType sortType) {
            label.setText(strings.sortTypeLabel(sortType));
        }
        return component;
    }
}
