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
package space.minecraftstl.xyml.ui.swing.page.settings;

import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.setting.GameDirectoryID;
import space.minecraftstl.xyml.setting.InstanceConfigMigrationContent;
import space.minecraftstl.xyml.setting.InstanceConfigMigrationPolicy;
import space.minecraftstl.xyml.setting.InstanceConfigMigrationSourceType;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Font;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;

import static space.minecraftstl.xyml.setting.SettingsManager.settings;
import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Edits the launcher-wide automatic configuration-migration policy independently of game presets.
@NotNullByDefault
public final class InstanceConfigMigrationPolicyPanel extends JPanel {
    /// Enables automatic migration at supported isolation transitions.
    private final JCheckBox enabledBox = new JCheckBox(i18n("settings.instance_config_migration.enabled"));

    /// Selects the target game directory's shared root or one exact isolated instance.
    private final JComboBox<InstanceConfigMigrationSourceType> sourceTypeBox =
            new JComboBox<>(InstanceConfigMigrationSourceType.values());

    /// Selects one currently valid isolated instance source across game directories.
    private final JComboBox<InstanceConfigMigrationChoice> sourceInstanceBox = new JComboBox<>();

    /// Per-content selection controls.
    private final Map<InstanceConfigMigrationContent, JCheckBox> contentBoxes =
            new EnumMap<>(InstanceConfigMigrationContent.class);

    /// Persists the complete immutable policy.
    private final JButton saveButton = new JButton(i18n("button.save"));

    /// Displays save and unavailable-source feedback.
    private final JLabel statusLabel = new JLabel();

    /// Creates and populates the launcher policy editor on the EDT.
    public InstanceConfigMigrationPolicyPanel() {
        super(new MigLayout("insets 20, fillx, wrap 2", "[][grow,fill]", "[]10[]10[]10[]"));
        EdtDispatcher.requireEventDispatchThread();
        setOpaque(false);
        configureComponents();
        reload();
    }

    /// Builds controls and stable automation names used by Swing tests.
    private void configureComponents() {
        JLabel heading = new JLabel(i18n("settings.instance_config_migration.title"));
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 20.0F));
        add(heading, "span 2, growx");
        add(new JLabel(i18n("settings.instance_config_migration.description")), "span 2, growx");

        enabledBox.setName("instanceConfigMigrationEnabled");
        enabledBox.addActionListener(event -> updateAvailability());
        add(enabledBox, "span 2");

        sourceTypeBox.setName("instanceConfigMigrationSourceType");
        sourceTypeBox.addActionListener(event -> updateAvailability());
        add(new JLabel(i18n("settings.instance_config_migration.source")));
        add(sourceTypeBox, "growx");

        sourceInstanceBox.setName("instanceConfigMigrationSourceInstance");
        add(new JLabel(i18n("settings.instance_config_migration.source_instance")));
        add(sourceInstanceBox, "growx");

        JPanel contents = new JPanel(new MigLayout("insets 0, fillx, wrap 2", "[grow,fill][grow,fill]", "[]"));
        contents.setOpaque(false);
        for (InstanceConfigMigrationContent content : InstanceConfigMigrationContent.values()) {
            JCheckBox box = new JCheckBox(contentName(content));
            box.setName("instanceConfigMigrationContent" + content.name());
            contentBoxes.put(content, box);
            contents.add(box);
        }
        add(new JLabel(i18n("settings.instance_config_migration.contents")), "aligny top");
        add(contents, "growx");

        saveButton.setName("instanceConfigMigrationSave");
        saveButton.addActionListener(event -> save());
        add(saveButton, "split 2");
        add(statusLabel, "growx");
        setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));
    }

    /// Reloads persisted policy and preserves an unavailable exact source as a disabled placeholder.
    private void reload() {
        sourceInstanceBox.removeAllItems();
        for (InstanceConfigMigrationChoice choice :
                InstanceConfigMigrationChoice.availableChoices(true, null, null)) {
            sourceInstanceBox.addItem(choice);
        }
        @Nullable InstanceConfigMigrationPolicy configured =
                settings().instanceConfigMigrationPolicyProperty().getValue();
        InstanceConfigMigrationPolicy policy = Objects.requireNonNullElse(
                configured, InstanceConfigMigrationPolicy.defaults());
        enabledBox.setSelected(policy.enabled());
        sourceTypeBox.setSelectedItem(policy.sourceType());
        for (Map.Entry<InstanceConfigMigrationContent, JCheckBox> entry : contentBoxes.entrySet()) {
            entry.getValue().setSelected(policy.contents().contains(entry.getKey()));
        }
        if (policy.sourceType() == InstanceConfigMigrationSourceType.INSTANCE) {
            selectPersistedSource(policy);
        }
        updateAvailability();
    }

    /// Selects the exact persisted source or appends an unavailable placeholder without falling back.
    private void selectPersistedSource(InstanceConfigMigrationPolicy policy) {
        GameDirectoryID directoryId = Objects.requireNonNull(policy.sourceGameDirectory(), "source game directory");
        GameInstanceID instanceId = Objects.requireNonNull(policy.sourceInstance(), "source instance");
        for (int index = 0; index < sourceInstanceBox.getItemCount(); index++) {
            InstanceConfigMigrationChoice choice = sourceInstanceBox.getItemAt(index);
            if (choice.gameDirectoryId().equals(directoryId) && choice.instanceId().equals(instanceId)) {
                sourceInstanceBox.setSelectedIndex(index);
                return;
            }
        }
        InstanceConfigMigrationChoice unavailable = InstanceConfigMigrationChoice.unavailable(directoryId, instanceId);
        sourceInstanceBox.addItem(unavailable);
        sourceInstanceBox.setSelectedItem(unavailable);
        statusLabel.setText(i18n("settings.instance_config_migration.source_unavailable"));
    }

    /// Persists a complete immutable policy after validating an exact instance source.
    private void save() {
        EnumSet<InstanceConfigMigrationContent> contents = EnumSet.noneOf(InstanceConfigMigrationContent.class);
        for (Map.Entry<InstanceConfigMigrationContent, JCheckBox> entry : contentBoxes.entrySet()) {
            if (entry.getValue().isSelected()) {
                contents.add(entry.getKey());
            }
        }
        InstanceConfigMigrationSourceType sourceType = Objects.requireNonNull(
                (InstanceConfigMigrationSourceType) sourceTypeBox.getSelectedItem(), "source type");
        @Nullable GameDirectoryID directoryId = null;
        @Nullable GameInstanceID instanceId = null;
        if (sourceType == InstanceConfigMigrationSourceType.INSTANCE) {
            @Nullable InstanceConfigMigrationChoice choice =
                    (InstanceConfigMigrationChoice) sourceInstanceBox.getSelectedItem();
            if (choice == null) {
                statusLabel.setText(i18n("settings.instance_config_migration.source_required"));
                return;
            }
            directoryId = choice.gameDirectoryId();
            instanceId = choice.instanceId();
        }
        settings().instanceConfigMigrationPolicyProperty().setValue(new InstanceConfigMigrationPolicy(
                enabledBox.isSelected(), sourceType, directoryId, instanceId, contents));
        statusLabel.setText(i18n("settings.instance_config_migration.saved"));
        updateAvailability();
    }

    /// Enables dependent controls while retaining visible invalid persisted sources.
    private void updateAvailability() {
        boolean enabled = enabledBox.isSelected();
        sourceTypeBox.setEnabled(enabled);
        boolean instanceSource = sourceTypeBox.getSelectedItem() == InstanceConfigMigrationSourceType.INSTANCE;
        sourceInstanceBox.setEnabled(enabled && instanceSource);
        for (JCheckBox box : contentBoxes.values()) {
            box.setEnabled(enabled);
        }
        saveButton.setEnabled(true);
        @Nullable InstanceConfigMigrationChoice choice =
                (InstanceConfigMigrationChoice) sourceInstanceBox.getSelectedItem();
        if (instanceSource && choice != null && !choice.available()) {
            statusLabel.setText(i18n("settings.instance_config_migration.source_unavailable"));
        }
    }

    /// Returns the localized label for one fixed migration content category.
    private static String contentName(InstanceConfigMigrationContent content) {
        return i18n("settings.instance_config_migration.content." + content.name().toLowerCase(java.util.Locale.ROOT));
    }
}
