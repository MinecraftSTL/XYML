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

import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import java.util.Objects;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Mutually exclusive automatic and manual memory-allocation rows.
///
/// Instance editors expose inheritance as the leading choice, while global presets directly choose between automatic
/// and manual allocation.
@NotNullByDefault
final class InstanceMemoryModeSelector {
    /// Whether the owning editor represents an instance that can inherit its preset.
    private final boolean inheritanceAvailable;

    /// Exclusive memory-mode selection group.
    private final ButtonGroup buttonGroup = new ButtonGroup();

    /// Instance-only choice that leaves memory settings inherited.
    private final JRadioButton inheritanceButton = createButton(
            "instanceGameSettingsMemoryModeInherit",
            i18n("settings.game.java_directory.inherit"));

    /// Automatic memory-allocation choice.
    private final JRadioButton automaticButton = createButton(
            "instanceGameSettingsMemoryModeAutomatic",
            i18n("settings.memory.auto_allocate"));

    /// Manual memory-allocation choice.
    private final JRadioButton manualButton = createButton(
            "instanceGameSettingsMemoryModeManual",
            i18n("settings.memory.manual_allocate"));

    /// Creates an instance or global-preset memory selector.
    ///
    /// @param inheritanceAvailable whether to expose the leading inheritance choice
    InstanceMemoryModeSelector(boolean inheritanceAvailable) {
        this.inheritanceAvailable = inheritanceAvailable;
        if (inheritanceAvailable) {
            buttonGroup.add(inheritanceButton);
        }
        buttonGroup.add(automaticButton);
        buttonGroup.add(manualButton);
        (inheritanceAvailable ? inheritanceButton : automaticButton).setSelected(true);
    }

    /// Adds the ordered choices and manual value editor to a three-column settings section.
    ///
    /// @param section target section
    /// @param maximumMemoryEditor manual maximum-memory editor
    void addRows(JPanel section, JComponent maximumMemoryEditor) {
        JPanel validatedSection = Objects.requireNonNull(section, "section");
        if (inheritanceAvailable) {
            addRow(validatedSection, inheritanceButton, emptyEditor());
        }
        addRow(validatedSection, automaticButton, emptyEditor());
        addRow(validatedSection, manualButton, maximumMemoryEditor);
    }

    /// Returns whether the instance inherits its memory settings.
    ///
    /// @return true when the inheritance choice is selected
    boolean isInherited() {
        return inheritanceAvailable && inheritanceButton.isSelected();
    }

    /// Returns whether the selected local or global mode allocates memory automatically.
    ///
    /// @return true for automatic allocation
    /// @throws IllegalStateException when the instance inheritance choice is selected
    boolean isAutomatic() {
        if (isInherited()) {
            throw new IllegalStateException("Memory allocation mode is inherited instead of locally selected");
        }
        return automaticButton.isSelected();
    }

    /// Applies durable inheritance and effective-mode state without emitting an action event.
    ///
    /// @param overridden whether either memory setting is local to the instance
    /// @param automatic effective automatic-allocation state
    void apply(boolean overridden, boolean automatic) {
        if (inheritanceAvailable && !overridden) {
            inheritanceButton.setSelected(true);
        } else {
            (automatic ? automaticButton : manualButton).setSelected(true);
        }
    }

    /// Registers one callback for user selection changes.
    ///
    /// @param listener callback invoked after a selected choice changes
    void addSelectionListener(Runnable listener) {
        Runnable validatedListener = Objects.requireNonNull(listener, "listener");
        if (inheritanceAvailable) {
            addSelectionListener(inheritanceButton, validatedListener);
        }
        addSelectionListener(automaticButton, validatedListener);
        addSelectionListener(manualButton, validatedListener);
    }

    /// Applies interaction availability to every visible choice.
    ///
    /// @param enabled whether users may change the memory mode
    void setEnabled(boolean enabled) {
        if (inheritanceAvailable) {
            inheritanceButton.setEnabled(enabled);
        }
        automaticButton.setEnabled(enabled);
        manualButton.setEnabled(enabled);
    }

    /// Returns the instance-only inheritance choice for focused tests.
    ///
    /// @return inheritance radio button
    JRadioButton inheritanceButton() {
        return inheritanceButton;
    }

    /// Returns the automatic-allocation choice used by the memory summary.
    ///
    /// @return automatic radio button
    JRadioButton automaticButton() {
        return automaticButton;
    }

    /// Returns the manual-allocation choice for focused tests.
    ///
    /// @return manual radio button
    JRadioButton manualButton() {
        return manualButton;
    }

    /// Adds one choice and its aligned payload editor.
    ///
    /// @param section target settings section
    /// @param button row choice
    /// @param editor aligned editor or transparent placeholder
    private static void addRow(JPanel section, JRadioButton button, JComponent editor) {
        section.add(Objects.requireNonNull(button, "button"), "span 2, growx");
        section.add(Objects.requireNonNull(editor, "editor"), "growx");
    }

    /// Creates a transparent placeholder for choices without a payload.
    ///
    /// @return transparent placeholder
    private static JPanel emptyEditor() {
        JPanel placeholder = new JPanel();
        placeholder.setOpaque(false);
        return placeholder;
    }

    /// Creates one transparent localized radio button.
    ///
    /// @param name stable component name
    /// @param text localized visible text
    /// @return configured radio button
    private static JRadioButton createButton(String name, String text) {
        JRadioButton button = new JRadioButton(Objects.requireNonNull(text, "text"));
        button.setName(Objects.requireNonNull(name, "name"));
        button.setOpaque(false);
        return button;
    }

    /// Registers one selected-only callback.
    ///
    /// @param button source button
    /// @param listener validated callback
    private static void addSelectionListener(JRadioButton button, Runnable listener) {
        button.addActionListener(event -> {
            if (button.isSelected()) {
                listener.run();
            }
        });
    }
}
