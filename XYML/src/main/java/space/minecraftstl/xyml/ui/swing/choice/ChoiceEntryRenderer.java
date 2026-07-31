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
package space.minecraftstl.xyml.ui.swing.choice;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.swing.JList;
import javax.swing.JLabel;
import javax.swing.ListCellRenderer;
import javax.swing.UIManager;
import java.awt.Component;

/// A single reusable list-style renderer for loaded, loading, and failed choice rows.
///
/// @param <T> the non-null choice value type
@NotNullByDefault
public final class ChoiceEntryRenderer<T extends Object>
        extends JLabel implements ListCellRenderer<ChoiceListEntry<T>> {
    /// The provider used to localize loaded values.
    private final ChoiceTextProvider<T> textProvider;

    /// Creates a reusable renderer.
    ///
    /// @param textProvider the provider of labels for loaded values
    public ChoiceEntryRenderer(ChoiceTextProvider<T> textProvider) {
        this.textProvider = textProvider;
        setOpaque(false);
        setBorder(UIManager.getBorder("List.cellNoFocusBorder"));
    }

    /// Configures this one renderer instance for the requested logical row.
    ///
    /// @param list the owning list
    /// @param entry the row presentation state
    /// @param index the logical row index
    /// @param isSelected whether the row is selected
    /// @param cellHasFocus whether the row has keyboard focus
    /// @return this reused label renderer
    @Override
    public Component getListCellRendererComponent(
            JList<? extends ChoiceListEntry<T>> list,
            ChoiceListEntry<T> entry,
            int index,
            boolean isSelected,
            boolean cellHasFocus) {
        setComponentOrientation(list.getComponentOrientation());
        setFont(list.getFont());
        setOpaque(isSelected);
        setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
        setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
        setBorder(UIManager.getBorder(cellHasFocus
                ? "List.focusCellHighlightBorder"
                : "List.cellNoFocusBorder"));
        setToolTipText(null);

        @Nullable T value = entry.value();
        if (entry.status() == ChoiceLoadStatus.LOADED && value != null) {
            setText(textProvider.getText(value));
            setEnabled(list.isEnabled());
        } else if (entry.status() == ChoiceLoadStatus.ERROR) {
            setText("!");
            setEnabled(false);
            @Nullable Throwable failure = entry.failure();
            setToolTipText(failure == null ? null : failure.getMessage());
        } else {
            setText("...");
            setEnabled(false);
        }
        return this;
    }
}
