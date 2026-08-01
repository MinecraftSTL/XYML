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
package space.minecraftstl.xyml.ui.swing.page.nbt;

import space.minecraftstl.xyml.ui.swing.dialog.EditablePathChooser;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.nbt.NBTFileType;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Component;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/// Native Swing implementation of the toolkit-neutral NBT editor interaction boundary.
@NotNullByDefault
final class SwingNBTEditorInteractions implements NBTEditorInteractions {
    /// Component used to own modal dialogs.
    private final Component owner;

    /// Stable localized dialog text.
    private final NBTEditorStrings strings;

    /// Creates native interactions owned by one page.
    ///
    /// @param owner dialog owner
    /// @param strings localized text
    SwingNBTEditorInteractions(Component owner, NBTEditorStrings strings) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.strings = Objects.requireNonNull(strings, "strings");
    }

    /// Opens a native chooser and returns only the selected lexical path.
    ///
    /// @param currentFile current source, or `null`
    /// @return selected normalized path, or `null` when cancelled
    @Override
    public @Nullable Path chooseFile(@Nullable Path currentFile) {
        EdtDispatcher.requireEventDispatchThread();
        JFileChooser chooser = new EditablePathChooser();
        chooser.setDialogTitle(strings.chooserTitle());
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter(
                strings.fileFilter(),
                "nbt",
                "dat",
                "dat_old",
                "mca",
                "mcr"));
        if (currentFile != null) {
            chooser.setSelectedFile(currentFile.toFile());
        }
        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        return chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
    }

    /// Accepts exactly one lexically supported dropped source.
    ///
    /// @param candidates normalized lexical candidate paths
    /// @return accepted path, or `null` for every other transfer shape
    @Override
    public @Nullable Path chooseDroppedFile(@Unmodifiable List<Path> candidates) {
        @Unmodifiable List<Path> paths = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        if (paths.size() != 1) {
            return null;
        }
        Path candidate = paths.get(0).toAbsolutePath().normalize();
        return NBTFileType.supports(candidate) ? candidate : null;
    }

    /// Shows a warning before replacing a dirty document.
    ///
    /// @param currentFile current dirty source
    /// @return whether replacement was confirmed
    @Override
    public boolean confirmDiscardChanges(Path currentFile) {
        EdtDispatcher.requireEventDispatchThread();
        return JOptionPane.showConfirmDialog(
                owner,
                strings.discardMessage(Objects.requireNonNull(currentFile, "currentFile")),
                strings.discardTitle(),
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }
}
