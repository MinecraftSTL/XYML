// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JLabel;
import javax.swing.JList;
import java.awt.Component;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/// Renders flattened provider categories using the currently selected provider's localization namespace.
@NotNullByDefault
final class RemoteCatalogCategoryRenderer extends DefaultListCellRenderer {
    /// Resolves whether the owning selector currently targets Modrinth.
    private final BooleanSupplier modrinthSource;

    /// Shared visible text including the all-categories option.
    private final RemoteCatalogFilterStrings strings;

    /// Creates a renderer bound to the owning panel's live source selection.
    ///
    /// @param modrinthSource live provider-kind supplier
    /// @param strings filter text bundle
    RemoteCatalogCategoryRenderer(BooleanSupplier modrinthSource, RemoteCatalogFilterStrings strings) {
        this.modrinthSource = Objects.requireNonNull(modrinthSource, "modrinthSource");
        this.strings = Objects.requireNonNull(strings, "strings");
    }

    /// Renders one category option while preserving standard look-and-feel selection colors.
    ///
    /// @param list owning list
    /// @param value option value, or null while the combo box has no value
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
        if (component instanceof JLabel label && value instanceof RemoteCatalogCategoryOption option) {
            label.setText(option.displayText(modrinthSource.getAsBoolean(), strings.allCategoriesLabel()));
        }
        return component;
    }
}
