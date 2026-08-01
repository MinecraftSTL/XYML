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
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.setting.JavaVersionType;

import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Mutually exclusive Java-runtime strategy rows for instance and global-preset editors.
///
/// Instance editors begin with an explicit inheritance choice. Every local strategy is represented by a native radio
/// button on the same row as its payload editor, so the selected launch behavior is unambiguous.
@NotNullByDefault
final class InstanceJavaModeSelector {
    /// Stable visual order matching the payload editors rendered on the corresponding rows.
    private static final @Unmodifiable List<JavaVersionType> DISPLAY_ORDER = List.of(
            JavaVersionType.AUTO,
            JavaVersionType.VERSION,
            JavaVersionType.CUSTOM,
            JavaVersionType.DETECTED);

    /// Whether the owning editor represents one instance that may inherit its preset.
    private final boolean inheritanceAvailable;

    /// Exclusive native Swing selection group.
    private final ButtonGroup buttonGroup = new ButtonGroup();

    /// First instance-only choice that leaves all Java values inherited.
    private final JRadioButton inheritanceButton = createInheritanceButton();

    /// Radio button for each persisted local Java strategy.
    private final EnumMap<JavaVersionType, JRadioButton> buttons = new EnumMap<>(JavaVersionType.class);

    /// Creates a selector for one instance or a direct global preset.
    ///
    /// @param inheritanceAvailable whether to expose the leading inheritance row
    InstanceJavaModeSelector(boolean inheritanceAvailable) {
        this.inheritanceAvailable = inheritanceAvailable;
        if (inheritanceAvailable) {
            buttonGroup.add(inheritanceButton);
        }
        for (JavaVersionType mode : DISPLAY_ORDER) {
            JRadioButton button = createModeButton(mode);
            buttons.put(mode, button);
            buttonGroup.add(button);
        }
        (inheritanceAvailable ? inheritanceButton : button(JavaVersionType.AUTO)).setSelected(true);
    }

    /// Adds the ordered radio rows and their corresponding payload editors to a settings section.
    ///
    /// @param section target three-column settings section
    /// @param versionEditor Java-major editor
    /// @param customEditor custom executable editor and browse command
    /// @param detectedEditor detected-runtime editor
    void addRows(
            JPanel section,
            JComponent versionEditor,
            JComponent customEditor,
            JComponent detectedEditor) {
        JPanel validatedSection = Objects.requireNonNull(section, "section");
        if (inheritanceAvailable) {
            addRow(validatedSection, inheritanceButton, emptyEditor());
        }
        addRow(validatedSection, button(JavaVersionType.AUTO), emptyEditor());
        addRow(validatedSection, button(JavaVersionType.VERSION), versionEditor);
        addRow(validatedSection, button(JavaVersionType.CUSTOM), customEditor);
        addRow(validatedSection, button(JavaVersionType.DETECTED), detectedEditor);
    }

    /// Returns the strategies in their stable visual order.
    ///
    /// @return immutable display order
    static @Unmodifiable List<JavaVersionType> displayOrder() {
        return DISPLAY_ORDER;
    }

    /// Returns whether the instance-specific inheritance choice is selected.
    ///
    /// @return true when Java settings are inherited
    boolean isInherited() {
        return inheritanceAvailable && inheritanceButton.isSelected();
    }

    /// Returns the currently selected local strategy.
    ///
    /// @return selected local Java strategy
    /// @throws IllegalStateException when the inheritance row is selected
    JavaVersionType selectedMode() {
        for (JavaVersionType mode : DISPLAY_ORDER) {
            if (button(mode).isSelected()) {
                return mode;
            }
        }
        throw new IllegalStateException("Java strategy is inherited instead of locally selected");
    }

    /// Applies one durable strategy and its instance-override state without emitting an action event.
    ///
    /// @param overridden whether the instance uses a local strategy
    /// @param mode effective Java strategy
    void apply(boolean overridden, JavaVersionType mode) {
        if (inheritanceAvailable && !overridden) {
            inheritanceButton.setSelected(true);
        } else {
            button(Objects.requireNonNull(mode, "mode")).setSelected(true);
        }
    }

    /// Registers a callback for user selection changes.
    ///
    /// @param listener callback invoked after a radio choice is selected
    void addSelectionListener(Runnable listener) {
        Runnable validatedListener = Objects.requireNonNull(listener, "listener");
        if (inheritanceAvailable) {
            addSelectionListener(inheritanceButton, validatedListener);
        }
        for (JRadioButton button : buttons.values()) {
            addSelectionListener(button, validatedListener);
        }
    }

    /// Applies interaction availability to every visible radio choice.
    ///
    /// @param enabled whether users may change the Java strategy
    void setEnabled(boolean enabled) {
        if (inheritanceAvailable) {
            inheritanceButton.setEnabled(enabled);
        }
        for (JRadioButton button : buttons.values()) {
            button.setEnabled(enabled);
        }
    }

    /// Returns the instance-only inheritance radio button.
    ///
    /// @return inheritance choice
    JRadioButton inheritanceButton() {
        return inheritanceButton;
    }

    /// Returns the radio button representing one local strategy.
    ///
    /// @param mode represented Java strategy
    /// @return native strategy radio button
    JRadioButton button(JavaVersionType mode) {
        return Objects.requireNonNull(buttons.get(Objects.requireNonNull(mode, "mode")), "missing mode button");
    }

    /// Adds one radio choice and its aligned payload editor.
    ///
    /// @param section target settings section
    /// @param button row choice
    /// @param editor aligned payload editor or transparent placeholder
    private static void addRow(JPanel section, JRadioButton button, JComponent editor) {
        section.add(Objects.requireNonNull(button, "button"), "span 2, growx");
        section.add(Objects.requireNonNull(editor, "editor"), "growx");
    }

    /// Creates a transparent placeholder for choices without additional input.
    ///
    /// @return transparent row placeholder
    private static JPanel emptyEditor() {
        JPanel placeholder = new JPanel();
        placeholder.setOpaque(false);
        return placeholder;
    }

    /// Creates the localized leading inheritance choice.
    ///
    /// @return configured inheritance radio button
    private static JRadioButton createInheritanceButton() {
        JRadioButton button = new JRadioButton(i18n("settings.game.java_directory.inherit"));
        button.setName("instanceGameSettingsJavaModeInherit");
        button.setOpaque(false);
        return button;
    }

    /// Creates one localized local-strategy choice.
    ///
    /// @param mode represented Java strategy
    /// @return configured strategy radio button
    private static JRadioButton createModeButton(JavaVersionType mode) {
        JRadioButton button = new JRadioButton(displayName(Objects.requireNonNull(mode, "mode")));
        button.setName("instanceGameSettingsJavaMode" + mode.name());
        button.setOpaque(false);
        return button;
    }

    /// Registers one selected-only callback on a radio button.
    ///
    /// @param button source radio button
    /// @param listener validated selection callback
    private static void addSelectionListener(JRadioButton button, Runnable listener) {
        button.addActionListener(event -> {
            if (button.isSelected()) {
                listener.run();
            }
        });
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
