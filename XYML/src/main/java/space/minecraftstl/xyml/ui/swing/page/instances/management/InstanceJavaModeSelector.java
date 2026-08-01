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

import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.setting.JavaVersionType;

import javax.swing.ButtonGroup;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Highlighted, mutually exclusive Java-runtime strategy selector.
///
/// The visual order deliberately follows the payload rows rendered immediately below this selector: version,
/// custom executable, then detected runtime. The automatic option has no payload row and therefore remains first.
@NotNullByDefault
final class InstanceJavaModeSelector extends JPanel {
    /// Stable visual order independent from the persistence enum declaration order.
    private static final @Unmodifiable List<JavaVersionType> DISPLAY_ORDER = List.of(
            JavaVersionType.AUTO,
            JavaVersionType.VERSION,
            JavaVersionType.CUSTOM,
            JavaVersionType.DETECTED);

    /// Exclusive native Swing selection group.
    private final ButtonGroup buttonGroup = new ButtonGroup();

    /// Highlighted button for each persisted Java strategy.
    private final EnumMap<JavaVersionType, JToggleButton> buttons = new EnumMap<>(JavaVersionType.class);

    /// Creates the transparent FlatLaf tab-style selector with automatic selection as its initial state.
    InstanceJavaModeSelector() {
        super(new MigLayout(
                "insets 0, gap 0, fillx",
                "[grow,fill][grow,fill][grow,fill][grow,fill]",
                "[]"));
        setOpaque(false);
        for (JavaVersionType mode : DISPLAY_ORDER) {
            JToggleButton button = createButton(mode);
            buttons.put(mode, button);
            buttonGroup.add(button);
            add(button);
        }
        setSelectedMode(JavaVersionType.AUTO);
    }

    /// Returns the strategies in their stable visual order.
    ///
    /// @return immutable display order
    static @Unmodifiable List<JavaVersionType> displayOrder() {
        return DISPLAY_ORDER;
    }

    /// Returns the currently selected strategy.
    ///
    /// @return selected Java strategy
    JavaVersionType selectedMode() {
        for (JavaVersionType mode : DISPLAY_ORDER) {
            if (button(mode).isSelected()) {
                return mode;
            }
        }
        throw new IllegalStateException("Java strategy selector has no selection");
    }

    /// Selects one strategy without emitting an action event.
    ///
    /// @param mode strategy to select
    void setSelectedMode(JavaVersionType mode) {
        button(Objects.requireNonNull(mode, "mode")).setSelected(true);
    }

    /// Registers a callback for user selection changes.
    ///
    /// @param listener callback invoked after a different strategy is selected
    void addSelectionListener(Runnable listener) {
        Runnable validatedListener = Objects.requireNonNull(listener, "listener");
        for (JToggleButton button : buttons.values()) {
            button.addActionListener(event -> {
                if (button.isSelected()) {
                    validatedListener.run();
                }
            });
        }
    }

    /// Applies interaction availability to the selector and every child button.
    ///
    /// @param enabled whether users may change the strategy
    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        for (JToggleButton button : buttons.values()) {
            button.setEnabled(enabled);
        }
    }

    /// Returns the button representing one strategy.
    ///
    /// @param mode represented Java strategy
    /// @return highlighted strategy button
    JToggleButton button(JavaVersionType mode) {
        return Objects.requireNonNull(buttons.get(Objects.requireNonNull(mode, "mode")), "missing mode button");
    }

    /// Creates one localized highlighted choice in the explicit display order.
    ///
    /// @param mode represented Java strategy
    /// @return configured highlighted toggle button
    private static JToggleButton createButton(JavaVersionType mode) {
        JToggleButton button = new JToggleButton(displayName(Objects.requireNonNull(mode, "mode")));
        button.setName("instanceGameSettingsJavaMode" + mode.name());
        button.putClientProperty("JButton.buttonType", "tab");
        return button;
    }

    /// Returns a localized name for one Java strategy.
    ///
    /// @param mode Java strategy
    /// @return localized strategy name
    private static String displayName(JavaVersionType mode) {
        return switch (mode) {
            case AUTO -> i18n("settings.game.java_directory.auto");
            case VERSION -> i18n("settings.game.java_directory.version");
            case CUSTOM -> i18n("settings.custom");
            case DETECTED -> i18n("settings.game.java_directory.choose");
        };
    }
}
