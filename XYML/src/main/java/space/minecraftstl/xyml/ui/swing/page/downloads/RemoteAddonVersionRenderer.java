// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.addon.RemoteAddon;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JList;
import java.awt.Component;
import java.util.Objects;

/// Renders selected remote add-on versions with recommendation and game-version context.
@NotNullByDefault
final class RemoteAddonVersionRenderer extends DefaultListCellRenderer {
    /// Version selected as the current compatibility recommendation, or null before loading.
    private @Nullable RemoteAddon.Version recommendedVersion;

    /// Game-version search context placed first in every compatible selector row.
    private String requestedGameVersion = "";

    /// Updates recommendation and game-version context without replacing the combo-box model.
    ///
    /// @param version recommended version, or null when no project is selected
    /// @param requestedGameVersion optional exact game-version search context
    void setSelectionContext(@Nullable RemoteAddon.Version version, String requestedGameVersion) {
        recommendedVersion = version;
        this.requestedGameVersion = Objects.requireNonNull(requestedGameVersion, "requestedGameVersion").trim();
    }

    /// Renders one provider version while preserving an empty selector display before selection.
    ///
    /// @param list owning selector list
    /// @param value version record, or null before selection
    /// @param index row index
    /// @param isSelected whether the row is selected
    /// @param cellHasFocus whether the row owns focus
    /// @return configured renderer component
    @Override
    public Component getListCellRendererComponent(
            JList<?> list,
            @Nullable Object value,
            int index,
            boolean isSelected,
            boolean cellHasFocus) {
        Component component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
        setIcon(null);
        setText("");
        if (value instanceof RemoteAddon.Version version) {
            setText(RemoteAddonVersionOrdering.displayText(
                    version,
                    Objects.equals(version, recommendedVersion),
                    requestedGameVersion));
            setIcon(RemoteVersionChannelPresentation.icon(version.versionType()));
        }
        return component;
    }
}
