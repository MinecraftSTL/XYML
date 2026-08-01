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
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.setting.GameSettingsPresetID;

import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import java.awt.Component;
import java.awt.Font;
import java.util.Objects;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Presents and reads the parent global game-settings preset used by one instance.
@NotNullByDefault
final class InstanceParentPresetControls {
    /// Preset selector containing the default fallback and all current presets.
    private final JComboBox<InstanceGameSettingsParentPreset> parentPresetBox = new JComboBox<>();

    /// Creates a configured parent-preset selector.
    InstanceParentPresetControls() {
        parentPresetBox.setName("instanceGameSettingsParentPreset");
        parentPresetBox.setRenderer(parentPresetRenderer());
    }

    /// Creates the transparent parent-preset row retained from the legacy instance editor.
    ///
    /// @return title, subtitle, and preset selector row
    JPanel createRow() {
        JPanel row = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill][360!,fill]", "[]2[]"));
        row.setName("instanceGameSettingsParentPresetRow");
        row.setOpaque(false);

        JLabel title = new JLabel(i18n("settings.type.global.preset"));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15.0F));
        JLabel subtitle = new JLabel(i18n("settings.type.global.preset.subtitle"));
        subtitle.setName("instanceGameSettingsParentPresetSubtitle");

        row.add(title, "cell 0 0");
        row.add(subtitle, "cell 0 1");
        row.add(parentPresetBox, "cell 1 0 1 2, aligny center, growx");
        return row;
    }

    /// Applies the available choices and current selection.
    ///
    /// @param values durable parent-preset state
    void apply(InstanceGameSettingsSnapshot.ParentPresetSettings values) {
        InstanceGameSettingsSnapshot.ParentPresetSettings checkedValues = Objects.requireNonNull(values, "values");
        DefaultComboBoxModel<InstanceGameSettingsParentPreset> model = new DefaultComboBoxModel<>();
        for (InstanceGameSettingsParentPreset choice : checkedValues.choices()) {
            model.addElement(choice);
        }
        parentPresetBox.setModel(model);
        selectPreset(checkedValues.selectedId());
    }

    /// Reads the selected parent preset ID.
    ///
    /// @param current current durable parent-preset state
    /// @return edited parent-preset values
    InstanceGameSettingsSnapshot.ParentPresetSettings edited(
            InstanceGameSettingsSnapshot.ParentPresetSettings current) {
        @Nullable Object selected = parentPresetBox.getSelectedItem();
        @Nullable GameSettingsPresetID selectedId = selected instanceof InstanceGameSettingsParentPreset preset
                ? preset.id()
                : null;
        return new InstanceGameSettingsSnapshot.ParentPresetSettings(
                selectedId,
                Objects.requireNonNull(current, "current").choices());
    }

    /// Enables or disables the selector according to page writability.
    ///
    /// @param writable whether the backing settings can be saved
    /// @param instancePresentation whether this row is visible in the current presentation
    void updateAvailability(boolean writable, boolean instancePresentation) {
        parentPresetBox.setEnabled(writable && instancePresentation);
    }

    /// Registers a command invoked whenever the selected parent preset changes.
    ///
    /// @param selectionChanged preview command
    void addSelectionListener(Runnable selectionChanged) {
        Runnable checkedSelectionChanged = Objects.requireNonNull(selectionChanged, "selectionChanged");
        parentPresetBox.addActionListener(event -> checkedSelectionChanged.run());
    }

    /// Selects one option by preset ID, falling back to the default option when missing.
    ///
    /// @param selectedId desired preset ID, or `null` for default fallback
    private void selectPreset(@Nullable GameSettingsPresetID selectedId) {
        for (int index = 0; index < parentPresetBox.getItemCount(); index++) {
            InstanceGameSettingsParentPreset choice = parentPresetBox.getItemAt(index);
            if (Objects.equals(choice.id(), selectedId)) {
                parentPresetBox.setSelectedIndex(index);
                return;
            }
        }
        if (parentPresetBox.getItemCount() > 0) {
            parentPresetBox.setSelectedIndex(0);
        }
    }

    /// Creates a renderer that shows the localized preset display name.
    ///
    /// @return parent-preset renderer
    private static ListCellRenderer<InstanceGameSettingsParentPreset> parentPresetRenderer() {
        DefaultListCellRenderer fallback = new DefaultListCellRenderer();
        return (JList<? extends InstanceGameSettingsParentPreset> list,
                @Nullable InstanceGameSettingsParentPreset value,
                int index,
                boolean selected,
                boolean focused) -> {
            Component component = fallback.getListCellRendererComponent(
                    list,
                    value == null ? "" : value.displayName(),
                    index,
                    selected,
                    focused);
            return component;
        };
    }
}
