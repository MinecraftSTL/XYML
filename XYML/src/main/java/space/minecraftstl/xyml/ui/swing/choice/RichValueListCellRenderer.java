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

import javax.swing.JList;
import javax.swing.ListCellRenderer;
import java.awt.Component;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.swing.Icon;

/// Adapts the rich asynchronous-row renderer to an ordinary loaded-value list.
@NotNullByDefault
public final class RichValueListCellRenderer<T extends Object> implements ListCellRenderer<T> {
    private final RichChoiceListCellRenderer<T> delegate;

    /// Creates a renderer with the same two-line, icon, badge, and tooltip geometry as catalog rows.
    public RichValueListCellRenderer(
            Function<? super T, String> primaryTextProvider,
            Function<? super T, String> secondaryTextProvider,
            Function<? super T, String> badgeTextProvider,
            Function<? super T, Icon> iconProvider,
            Function<? super T, String> tooltipProvider) {
        this.delegate = new RichChoiceListCellRenderer<>(
                primaryTextProvider,
                secondaryTextProvider,
                badgeTextProvider,
                iconProvider,
                tooltipProvider);
    }

    /// Creates a renderer with a muted-row predicate.
    public RichValueListCellRenderer(
            Function<? super T, String> primaryTextProvider,
            Function<? super T, String> secondaryTextProvider,
            Function<? super T, String> badgeTextProvider,
            Function<? super T, Icon> iconProvider,
            Function<? super T, String> tooltipProvider,
            Predicate<? super T> disabledProvider) {
        this.delegate = new RichChoiceListCellRenderer<>(
                primaryTextProvider,
                secondaryTextProvider,
                badgeTextProvider,
                iconProvider,
                tooltipProvider,
                disabledProvider);
    }

    /// Renders one loaded value through the shared catalog row implementation.
    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Component getListCellRendererComponent(
            JList<? extends T> list,
            T value,
            int index,
            boolean isSelected,
            boolean cellHasFocus) {
        Objects.requireNonNull(value, "value");
        return delegate.getListCellRendererComponent(
                (JList) list,
                ChoiceListEntry.loaded(index, value),
                index,
                isSelected,
                cellHasFocus);
    }
}
