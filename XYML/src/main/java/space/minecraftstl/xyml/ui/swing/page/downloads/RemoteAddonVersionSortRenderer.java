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
