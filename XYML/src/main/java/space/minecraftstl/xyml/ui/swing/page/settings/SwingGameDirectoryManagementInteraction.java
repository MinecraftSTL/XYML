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

import space.minecraftstl.xyml.ui.swing.dialog.EditablePathChooser;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import java.awt.Component;
import java.io.File;
import java.nio.file.Path;
import java.util.Objects;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Production native interactions for [GameDirectoryManagementPanel].
///
/// Native chooser and confirmation calls are intentionally kept on the EDT. The panel performs path conversion on
/// its background executor after these user-driven UI calls return.
@NotNullByDefault
final class SwingGameDirectoryManagementInteraction implements GameDirectoryManagementInteraction {
    /// Opens the platform directory chooser.
    ///
    /// @param owner chooser parent component
    /// @param initialDirectory suggested initial location, or `null`
    /// @return chosen path, or `null` after cancellation
    @Override
    public @Nullable Path chooseDirectory(Component owner, @Nullable Path initialDirectory) {
        EdtDispatcher.requireEventDispatchThread();
        JFileChooser chooser = initialDirectory == null
                ? new EditablePathChooser()
                : new EditablePathChooser(initialDirectory.toFile());
        chooser.setDialogTitle(i18n("game_directory.instance_directory.choose"));
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setMultiSelectionEnabled(false);
        if (chooser.showOpenDialog(Objects.requireNonNull(owner, "owner")) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        @Nullable File selected = chooser.getSelectedFile();
        return selected == null ? null : selected.toPath();
    }

    /// Confirms recovery of a newer read-only directory configuration file.
    ///
    /// @param owner confirmation parent component
    /// @return whether backup and overwrite may proceed
    @Override
    public boolean confirmReadOnlyOverwrite(Component owner) {
        EdtDispatcher.requireEventDispatchThread();
        return JOptionPane.showConfirmDialog(
                Objects.requireNonNull(owner, "owner"),
                i18n("settings.game_directories.read_only"),
                i18n("game_directory"),
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    /// Confirms permanent removal of one stored directory entry.
    ///
    /// @param owner confirmation parent component
    /// @param entry directory being removed
    /// @return whether removal may proceed
    @Override
    public boolean confirmRemoval(Component owner, GameDirectoryManagementEntry entry) {
        EdtDispatcher.requireEventDispatchThread();
        GameDirectoryManagementEntry target = Objects.requireNonNull(entry, "entry");
        return JOptionPane.showConfirmDialog(
                Objects.requireNonNull(owner, "owner"),
                target.displayName() + "\n" + target.path().getPath(),
                i18n("button.remove"),
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    /// Shows one terminal failure dialog.
    ///
    /// @param owner dialog parent component
    /// @param detail localized or diagnostic error detail
    @Override
    public void showFailure(Component owner, String detail) {
        EdtDispatcher.requireEventDispatchThread();
        JOptionPane.showMessageDialog(
                Objects.requireNonNull(owner, "owner"),
                Objects.requireNonNull(detail, "detail"),
                i18n("message.failed"),
                JOptionPane.ERROR_MESSAGE);
    }
}
