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
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.io.File;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Combines an editable custom Java executable field with a native file-selection command.
@NotNullByDefault
final class InstanceJavaPathControls {
    /// Editable Java executable path shared with the settings snapshot.
    private final JTextField pathField;

    /// Selects one executable path, or returns `null` after cancellation.
    private final Supplier<@Nullable Path> pathChooser;

    /// Opens the executable chooser without replacing direct text entry.
    private final JButton browseButton = new JButton(
            new FlatSVGIcon("assets/swing/icons/folder-open.svg", 16, 16));

    /// Transparent row editor containing direct text input and browsing.
    private final JPanel component = new JPanel(new BorderLayout(6, 0));

    /// Creates production controls with an editable native file chooser.
    ///
    /// @param pathField editable Java executable path
    InstanceJavaPathControls(JTextField pathField) {
        this(pathField, null);
    }

    /// Creates controls with an optional deterministic chooser for focused tests.
    ///
    /// @param pathField editable Java executable path
    /// @param pathChooser chooser supplier, or `null` for the production dialog
    InstanceJavaPathControls(
            JTextField pathField,
            @Nullable Supplier<@Nullable Path> pathChooser) {
        this.pathField = Objects.requireNonNull(pathField, "pathField");
        this.pathChooser = pathChooser == null ? this::chooseJavaExecutable : pathChooser;
        browseButton.setName("instanceGameSettingsJavaPathBrowse");
        browseButton.setToolTipText(i18n("settings.game.java_directory.choose"));
        browseButton.addActionListener(event -> applySelectedPath());
        component.setOpaque(false);
        component.add(pathField, BorderLayout.CENTER);
        component.add(browseButton, BorderLayout.EAST);
    }

    /// Returns the aligned path editor and browse command.
    ///
    /// @return transparent combined path editor
    JComponent component() {
        return component;
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
