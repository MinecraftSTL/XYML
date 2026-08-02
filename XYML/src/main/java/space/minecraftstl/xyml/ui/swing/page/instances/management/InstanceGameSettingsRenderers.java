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
package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.game.QuickPlayType;
import space.minecraftstl.xyml.game.Renderer;
import space.minecraftstl.xyml.setting.GameWindowType;
import space.minecraftstl.xyml.util.i18n.I18n;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.ListCellRenderer;
import java.awt.Component;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Provides localized display text and stable renderers for game-settings choices.
@NotNullByDefault
final class InstanceGameSettingsRenderers {
    /// Utility class; instances carry no state.
    private InstanceGameSettingsRenderers() {
    }

    /// Installs a text-converting combo renderer.
    ///
    /// @param comboBox target choice control
    /// @param displayName display-text conversion
    /// @param <T> choice type
    static <T> void installRenderer(JComboBox<T> comboBox, Function<T, String> displayName) {
        DefaultListCellRenderer fallback = new DefaultListCellRenderer();
        Function<T, String> converter = Objects.requireNonNull(displayName, "displayName");
        ListCellRenderer<T> renderer = (
                JList<? extends T> list,
                T value,
                int index,
                boolean selected,
                boolean focused) -> {
            Component component = fallback.getListCellRendererComponent(
                    list,
                    value,
                    index,
                    selected,
                    focused);
            if (component instanceof JLabel label && value != null) {
                label.setText(converter.apply(value));
            }
            return component;
        };
        comboBox.setRenderer(renderer);
    }

    /// Returns a localized game-window mode name.
    ///
    /// @param value window mode
    /// @return localized mode name
    static String windowTypeName(GameWindowType value) {
        return i18n("settings.game.window_type." + enumKey(value));
    }

    /// Returns a localized Quick Play mode name.
    ///
    /// @param value Quick Play mode
    /// @return localized mode name
    static String quickPlayTypeName(QuickPlayType value) {
        return i18n("settings.game.quick_play." + enumKey(value));
    }

    /// Returns a localized renderer name when available.
    ///
    /// @param renderer renderer choice
    /// @return localized renderer name or the stable enum name
    static String rendererName(Renderer renderer) {
        String key = "settings.advanced.renderer." + renderer.name().toLowerCase(Locale.ROOT);
        return I18n.hasKey(key) ? i18n(key) : renderer.name();
    }

    /// Returns the lowercase localization suffix for one enum value.
    ///
    /// @param value enum value
    /// @return lowercase locale-stable suffix
    static String enumKey(Enum<?> value) {
        return Objects.requireNonNull(value, "value").name().toLowerCase(Locale.ROOT);
    }
}
