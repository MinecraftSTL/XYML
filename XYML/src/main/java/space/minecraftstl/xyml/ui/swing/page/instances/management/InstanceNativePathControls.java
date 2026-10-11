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
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.dialog.EditablePathChooser;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// A directory chooser that preserves manual input, override state and the displayed settings context.
@NotNullByDefault
final class InstanceNativePathControls implements AutoCloseable {
    /// Existing inherited text editor remains the setting's single mutable input.
    private final JTextField pathField;
    /// Captures the displayed immutable snapshot identity on the EDT.
    private final Supplier<@Nullable Object> context;
    /// Injected chooser returns null after cancellation.
    private final Supplier<@Nullable Path> chooser;
    /// Native directory-browse command.
    private final JButton button = new JButton(new FlatSVGIcon("assets/swing/icons/folder-open.svg", 16, 16));
    /// Combined editor preserves the existing three-column inherited row.
    private final JPanel component = new JPanel(new BorderLayout(6, 0));
    /// EDT-confined lifetime guard for modal completion.
    private boolean closed;

    /// Creates production controls without probing user paths on the EDT.
    InstanceNativePathControls(JTextField pathField, Supplier<@Nullable Object> context) {
        this(pathField, context, null);
    }

    /// Creates controls with a deterministic optional chooser boundary.
    InstanceNativePathControls(JTextField pathField, Supplier<@Nullable Object> context,
                               @Nullable Supplier<@Nullable Path> chooser) {
        this.pathField = Objects.requireNonNull(pathField, "pathField");
        this.context = Objects.requireNonNull(context, "context");
        this.chooser = chooser == null ? this::chooseDirectory : chooser;
        button.setName("instanceGameSettingsNativesDirectoryBrowse");
        button.setToolTipText(i18n("settings.advanced.natives_directory.choose"));
        button.addActionListener(event -> browse());
        component.setOpaque(false);
        component.add(pathField, BorderLayout.CENTER);
        component.add(button, BorderLayout.EAST);
    }

    /// Adds the existing inherited setting with its composed path editor.
    void addRow(JPanel section, String labelText, JCheckBox override) {
        section.add(override, "aligny center");
        JLabel label = new JLabel(labelText);
        label.setLabelFor(pathField);
        label.setName(pathField.getName() + "Label");
        override.getAccessibleContext().setAccessibleName(labelText);
        section.add(label, "aligny center");
        section.add(component, "growx");
    }

    /// Returns the combined editor for focused interaction tests.
    JComponent component() {
        return component;
    }

    /// Mirrors the resolved field availability, including custom-directory and override switches.
    void updateAvailability(boolean enabled) {
        button.setEnabled(enabled && !closed);
    }

    /// Ignores a modal result when its owner closed, changed context, or stopped editing.
    private void browse() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || !pathField.isEnabled()) return;
        @Nullable Object captured = context.get();
        String previous = pathField.getText();
        @Nullable Path selection = chooser.get();
        if (!closed && pathField.isEnabled() && context.get() == captured
                && previous.equals(pathField.getText()) && selection != null) {
            pathField.setText(selection.toAbsolutePath().normalize().toString());
        }
    }

    /// Opens the established editable directory chooser; cancellation preserves the text.
    private @Nullable Path chooseDirectory() {
        EditablePathChooser dialog = new EditablePathChooser();
        dialog.setDialogTitle(i18n("settings.advanced.natives_directory.choose"));
        dialog.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        String current = pathField.getText().strip();
        if (!current.isEmpty()) {
            try {
                File location = Path.of(current).toAbsolutePath().normalize().toFile();
                dialog.setSelectedFile(location);
            } catch (InvalidPathException expected) {
                // An invalid initial value must remain editable instead of preventing browsing.
            }
        }
        if (dialog.showOpenDialog(pathField) != JFileChooser.APPROVE_OPTION) return null;
        @Nullable File selected = dialog.getSelectedFile();
        return selected == null ? null : selected.toPath();
    }

    /// Prevents an outstanding modal result from changing disposed state.
    @Override
    public void close() {
        EdtDispatcher.requireEventDispatchThread();
        closed = true;
        button.setEnabled(false);
    }
}
