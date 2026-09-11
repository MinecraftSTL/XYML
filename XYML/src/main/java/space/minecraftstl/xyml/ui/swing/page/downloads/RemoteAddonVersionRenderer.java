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
