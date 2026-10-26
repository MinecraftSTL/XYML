// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.choice;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.swing.JList;
import javax.swing.JLabel;
import javax.swing.ListCellRenderer;
import java.awt.Component;
import java.awt.Graphics;

/// A single reusable list-style renderer for loaded, loading, and failed choice rows.
///
/// @param <T> the non-null choice value type
@NotNullByDefault
public final class ChoiceEntryRenderer<T extends Object>
        extends JLabel implements ListCellRenderer<ChoiceListEntry<T>> {
    /// The provider used to localize loaded values.
    private final ChoiceTextProvider<T> textProvider;

    /// List whose UI paints the current selection background, or `null` before first configuration.
    private @Nullable JList<?> selectionOwner;

    /// Logical row represented during the next paint.
    private int selectionIndex = -1;

    /// Whether the represented row is selected.
    private boolean selected;

    /// Whether the represented row owns keyboard focus.
    private boolean focused;

    /// Creates a reusable renderer.
    ///
    /// @param textProvider the provider of labels for loaded values
    public ChoiceEntryRenderer(ChoiceTextProvider<T> textProvider) {
        this.textProvider = textProvider;
        setOpaque(false);
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
        selectionOwner = list;
        selectionIndex = index;
        selected = isSelected;
        focused = cellHasFocus;
        setOpaque(false);
        setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
        setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
        setBorder(RoundedListSelectionPainter.createCellInsetsBorder(list));
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

    /// Paints the list-owned rounded selection before the transparent label content.
    ///
    /// @param graphics destination graphics
    @Override
    protected void paintComponent(Graphics graphics) {
        @Nullable JList<?> owner = selectionOwner;
        if (selected && owner != null) {
            RoundedListSelectionPainter.paintSelectedBackground(
                    owner,
                    graphics,
                    selectionIndex,
                    getWidth(),
                    getHeight(),
                    getBackground());
        }
        if (focused && owner != null) {
            RoundedListSelectionPainter.paintFocusOutline(owner, graphics, getWidth(), getHeight());
        }
        super.paintComponent(graphics);
    }
}
