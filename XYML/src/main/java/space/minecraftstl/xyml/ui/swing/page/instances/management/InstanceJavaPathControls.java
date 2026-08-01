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
import java.io.File;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Adds an editable custom Java executable field and native file-selection command to one inherited row.
@NotNullByDefault
final class InstanceJavaPathControls {
    /// Durable local-override marker shared with the settings snapshot.
    private final JCheckBox overrideBox;

    /// Editable Java executable path shared with the settings snapshot.
    private final JTextField pathField;

    /// Selects one executable path, or returns `null` after cancellation.
    private final Supplier<@Nullable Path> pathChooser;

    /// Opens the executable chooser without replacing direct text entry.
    private final JButton browseButton = new JButton(
            new FlatSVGIcon("assets/swing/icons/folder-open.svg", 16, 16));

    /// Creates production controls with an editable native file chooser.
    ///
    /// @param overrideBox durable local-override marker
    /// @param pathField editable Java executable path
    InstanceJavaPathControls(JCheckBox overrideBox, JTextField pathField) {
        this(overrideBox, pathField, null);
    }

    /// Creates controls with an optional deterministic chooser for focused tests.
    ///
    /// @param overrideBox durable local-override marker
    /// @param pathField editable Java executable path
    /// @param pathChooser chooser supplier, or `null` for the production dialog
    InstanceJavaPathControls(
            JCheckBox overrideBox,
            JTextField pathField,
            @Nullable Supplier<@Nullable Path> pathChooser) {
        this.overrideBox = Objects.requireNonNull(overrideBox, "overrideBox");
        this.pathField = Objects.requireNonNull(pathField, "pathField");
        this.pathChooser = pathChooser == null ? this::chooseJavaExecutable : pathChooser;
        browseButton.setName("instanceGameSettingsJavaPathBrowse");
        browseButton.setToolTipText(i18n("settings.game.java_directory.choose"));
        browseButton.addActionListener(event -> applySelectedPath());
    }

    /// Adds the inherited Java path and chooser to one three-column settings section.
    ///
    /// @param section target settings section
    /// @param labelText localized path label
    void addRow(JPanel section, String labelText) {
        JPanel checkedSection = Objects.requireNonNull(section, "section");
        checkedSection.add(overrideBox, "aligny center");
        checkedSection.add(new JLabel(Objects.requireNonNull(labelText, "labelText")), "aligny center");
        JPanel editor = new JPanel(new BorderLayout(6, 0));
        editor.setOpaque(false);
        editor.add(pathField, BorderLayout.CENTER);
        editor.add(browseButton, BorderLayout.EAST);
        checkedSection.add(editor, "growx");
    }

    /// Mirrors the path field's resolved editor availability to the chooser button.
    ///
    /// @param pathEditingEnabled whether direct path editing is currently enabled
    void updateAvailability(boolean pathEditingEnabled) {
        browseButton.setEnabled(pathEditingEnabled);
    }

    /// Writes a confirmed absolute executable path without changing the override state.
    private void applySelectedPath() {
        @Nullable Path selectedPath = pathChooser.get();
        if (selectedPath != null) {
            pathField.setText(selectedPath.toAbsolutePath().normalize().toString());
        }
    }

    /// Opens an editable file chooser initialized from the current path text.
    ///
    /// @return selected executable path, or `null` after cancellation
    private @Nullable Path chooseJavaExecutable() {
        EditablePathChooser chooser = new EditablePathChooser();
        chooser.setDialogTitle(i18n("settings.game.java_directory.choose"));
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        String currentPath = pathField.getText().trim();
        if (!currentPath.isEmpty()) {
            File currentFile = new File(currentPath);
            @Nullable File initialDirectory = currentFile.isDirectory() ? currentFile : currentFile.getParentFile();
            if (initialDirectory != null && initialDirectory.isDirectory()) {
                chooser.setCurrentDirectory(initialDirectory);
            }
            if (currentFile.isFile()) {
                chooser.setSelectedFile(currentFile);
            }
        }
        if (chooser.showOpenDialog(pathField) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        @Nullable File selectedFile = chooser.getSelectedFile();
        return selectedFile == null ? null : selectedFile.toPath();
    }
}
