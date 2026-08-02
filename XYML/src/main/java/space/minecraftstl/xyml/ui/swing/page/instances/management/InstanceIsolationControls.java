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

import com.formdev.flatlaf.extras.FlatSVGIcon;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.ui.swing.dialog.EditablePathChooser;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Font;
import java.io.File;
import java.util.Objects;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Owns the explicit instance-isolation switch and its editable working-directory chooser.
///
/// A selected isolation switch maps to a local `runningDirectory` override. An empty local value means the instance
/// root, while non-empty text selects a custom directory. Modpacks may provide a forced directory that keeps both
/// controls read-only without mutating dormant settings when unrelated fields are saved.
@NotNullByDefault
final class InstanceIsolationControls {
    /// Forced instance root, or `null` when isolation remains configurable.
    private final @Nullable String forcedRunningDirectory;

    /// Durable local-override marker shared with the complete settings editor.
    private final JCheckBox overrideBox;

    /// Editable custom working-directory text shared with the complete settings editor.
    private final JTextField directoryField;

    /// Explicit user-facing isolation switch.
    private final JCheckBox isolationControl = new JCheckBox();

    /// Opens an editable directory chooser for the custom working directory.
    private final JButton browseButton = new JButton(
            new FlatSVGIcon("assets/swing/icons/folder-open.svg", 16, 16));

    /// Recomputes dependent editor availability after isolation changes.
    private final Runnable availabilityChanged;

    /// Creates controls over one running-directory field and its durable override marker.
    ///
    /// @param forcedRunningDirectory forced modpack instance root, or `null` for configurable isolation
    /// @param overrideBox durable local-override marker
    /// @param directoryField editable custom directory text
    /// @param availabilityChanged callback that recomputes the parent editor state
    InstanceIsolationControls(
            @Nullable String forcedRunningDirectory,
            JCheckBox overrideBox,
            JTextField directoryField,
            Runnable availabilityChanged) {
        this.forcedRunningDirectory = forcedRunningDirectory;
        this.overrideBox = Objects.requireNonNull(overrideBox, "overrideBox");
        this.directoryField = Objects.requireNonNull(directoryField, "directoryField");
        this.availabilityChanged = Objects.requireNonNull(availabilityChanged, "availabilityChanged");
        configureControls();
    }

    /// Creates the explicit version-isolation title, explanation, and switch row.
    ///
    /// @return transparent isolation row
    JPanel createIsolationRow() {
        JPanel row = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill][right]", "[]2[]"));
        row.setName("instanceGameSettingsIsolationRow");
        row.setOpaque(false);

        JLabel title = new JLabel(i18n("settings.game.isolation"));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15.0F));
        JLabel subtitle = new JLabel(i18n("settings.game.isolation.subtitle"));
        subtitle.setName("instanceGameSettingsIsolationSubtitle");

        row.add(title, "cell 0 0");
        row.add(subtitle, "cell 0 1");
        row.add(isolationControl, "cell 1 0 1 2, aligny center");
        return row;
    }

    /// Adds the working-directory field and chooser to the launch-options section.
    ///
    /// @param section target launch-options section
    /// @param showOverride whether the generic local-override checkbox remains visible
    void addRunningDirectoryRow(JPanel section, boolean showOverride) {
        JPanel checkedSection = Objects.requireNonNull(section, "section");
        JPanel row = new JPanel(new MigLayout(
                "insets 0, fillx", "[26!,center]8[280!,fill]16[grow,fill]", "[]"));
        row.setName("instanceGameSettingsRunningDirectoryRow");
        row.setOpaque(false);
        overrideBox.setVisible(showOverride);
        row.add(overrideBox, "cell 0 0, hidemode 3, aligny center");

        String labelText = i18n("settings.game.running_directory");
        JLabel label = new JLabel(labelText);
        label.setName("instanceGameSettingsRunningDirectoryLabel");
        label.setLabelFor(directoryField);
        overrideBox.getAccessibleContext().setAccessibleName(labelText);
        row.add(label, "cell 1 0, aligny center");

        JPanel editor = new JPanel(new BorderLayout(6, 0));
        editor.setName("instanceGameSettingsRunningDirectoryEditor");
        editor.setOpaque(false);
        editor.add(directoryField, BorderLayout.CENTER);
        editor.add(browseButton, BorderLayout.EAST);
        row.add(editor, "cell 2 0, growx");
        checkedSection.add(row, "span 3, growx");
    }

    /// Applies durable inheritance state without firing user actions.
    ///
    /// @param overridden whether the stored setting is local
    /// @param runningDirectory effective stored directory text
    void apply(boolean overridden, String runningDirectory) {
        overrideBox.setSelected(overridden);
        isolationControl.setSelected(forcedRunningDirectory != null || overridden);
        directoryField.setText(forcedRunningDirectory != null
                ? forcedRunningDirectory
                : Objects.requireNonNull(runningDirectory, "runningDirectory"));
    }

    /// Returns the edited override state while preserving forced-modpack dormant settings.
    ///
    /// @param storedOverridden durable state before editing
    /// @return override state to persist
    boolean editedOverridden(boolean storedOverridden) {
        return forcedRunningDirectory != null ? storedOverridden : overrideBox.isSelected();
    }

    /// Returns trimmed directory text while preserving forced-modpack dormant settings.
    ///
    /// @param storedDirectory durable value before editing
    /// @return directory value to persist
    String editedDirectory(String storedDirectory) {
        if (forcedRunningDirectory != null || !overrideBox.isSelected()) {
            return Objects.requireNonNull(storedDirectory, "storedDirectory");
        }
        return directoryField.getText().trim();
    }

    /// Applies writability after the parent editor has resolved generic override dependencies.
    ///
    /// @param writable whether the backing settings accept edits
    /// @param instancePresentation whether the explicit isolation switch is part of this presentation
    void updateAvailability(boolean writable, boolean instancePresentation) {
        isolationControl.setEnabled(writable && instancePresentation && forcedRunningDirectory == null);
        if (forcedRunningDirectory != null) {
            overrideBox.setEnabled(false);
            directoryField.setEnabled(false);
        }
        browseButton.setEnabled(directoryField.isEnabled());
    }

    /// Configures stable names, localized help, and synchronized actions.
    private void configureControls() {
        isolationControl.setName("instanceGameSettingsIsolation");
        isolationControl.setToolTipText(i18n("settings.game.isolation.subtitle"));
        isolationControl.addActionListener(event -> isolationChanged());
        overrideBox.addActionListener(event -> overrideChanged());

        browseButton.setName("instanceGameSettingsRunningDirectoryBrowse");
        browseButton.setToolTipText(i18n("settings.game.working_directory.choose"));
        browseButton.addActionListener(event -> chooseRunningDirectory());
    }

    /// Applies a user-selected isolation state to the durable override controls.
    private void isolationChanged() {
        if (forcedRunningDirectory != null) {
            isolationControl.setSelected(true);
            return;
        }
        boolean isolated = isolationControl.isSelected();
        overrideBox.setSelected(isolated);
        if (isolated) {
            directoryField.setText("");
        }
        availabilityChanged.run();
    }

    /// Mirrors generic override changes into the explicit isolation switch.
    private void overrideChanged() {
        isolationControl.setSelected(forcedRunningDirectory != null || overrideBox.isSelected());
        availabilityChanged.run();
    }

    /// Opens an editable directory chooser and writes the selected custom working directory.
    private void chooseRunningDirectory() {
        EditablePathChooser chooser = new EditablePathChooser();
        chooser.setDialogTitle(i18n("settings.game.working_directory.choose"));
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);

        String currentPath = directoryField.getText().trim();
        if (!currentPath.isEmpty()) {
            File currentFile = new File(currentPath);
            @Nullable File initialDirectory = currentFile.isDirectory() ? currentFile : currentFile.getParentFile();
            if (initialDirectory != null && initialDirectory.isDirectory()) {
                chooser.setCurrentDirectory(initialDirectory);
            }
        }

        if (chooser.showOpenDialog(directoryField) == JFileChooser.APPROVE_OPTION) {
            @Nullable File selectedDirectory = chooser.getSelectedFile();
            if (selectedDirectory != null) {
                directoryField.setText(selectedDirectory.toPath().toAbsolutePath().normalize().toString());
            }
        }
    }
}
