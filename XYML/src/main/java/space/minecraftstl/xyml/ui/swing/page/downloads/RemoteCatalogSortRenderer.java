// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
