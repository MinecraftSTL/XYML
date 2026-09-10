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
import space.minecraftstl.xyml.library.nbt.io.NBTFileEncoding;
import space.minecraftstl.xyml.library.nbt.io.NBTReadReport;
import space.minecraftstl.xyml.library.nbt.io.StorageProfile;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.nbt.NBTFileType;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
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

    /// Opens a save-style chooser for one new NBT target without touching the filesystem.
    ///
    /// A filename without a supported suffix receives `.nbt`, whose backend default is RAW.
    ///
    /// @param currentFile current source used to select the initial directory, or `null`
    /// @return normalized target path, or `null` when cancelled
    @Override
    public @Nullable Path chooseNewFile(@Nullable Path currentFile) {
        EdtDispatcher.requireEventDispatchThread();
        JFileChooser chooser = createFileChooser(strings.newChooserTitle());
        if (currentFile != null) {
            @Nullable Path parent = currentFile.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                chooser.setCurrentDirectory(parent.toFile());
            }
        }
        if (chooser.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        Path selected = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        if (NBTFileType.supports(selected)) {
            return selected;
        }
        @Nullable Path fileName = selected.getFileName();
        return fileName == null ? null : selected.resolveSibling(fileName + ".nbt");
    }

    /// Opens a native chooser and returns only the selected lexical path.
    ///
    /// @param currentFile current source, or `null`
    /// @return selected normalized path, or `null` when cancelled
    @Override
    public @Nullable Path chooseFile(@Nullable Path currentFile) {
        EdtDispatcher.requireEventDispatchThread();
        JFileChooser chooser = createFileChooser(strings.chooserTitle());
        if (currentFile != null) {
            chooser.setSelectedFile(currentFile.toFile());
        }
        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        return chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
    }

    /// Creates the shared constrained chooser used for opening and creating NBT files.
    ///
    /// @param title localized dialog title
    /// @return configured chooser
    private JFileChooser createFileChooser(String title) {
        JFileChooser chooser = new EditablePathChooser();
        chooser.setDialogTitle(Objects.requireNonNull(title, "title"));
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter(
                strings.fileFilter(),
                "nbt",
                "dat",
                "dat_old",
                "xyml_old",
                "mca",
                "mcr"));
        return chooser;
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

    /// Shows a repair-save confirmation with a collapsed plain-text diagnostic view.
    ///
    /// @param currentFile source that will be rewritten
    /// @param report immutable tolerant-read diagnostics
    /// @return whether the user explicitly approved strict repair publication
    @Override
    public boolean confirmRepairSave(Path currentFile, NBTReadReport report) {
        NBTReadReport diagnostics = Objects.requireNonNull(report, "report");
        StorageProfile profile = diagnostics.encoding() == NBTFileEncoding.REGION
                ? StorageProfile.region(new byte[StorageProfile.REGION_SLOT_COUNT],
                        new boolean[StorageProfile.REGION_SLOT_COUNT], new boolean[StorageProfile.REGION_SLOT_COUNT])
                : new StorageProfile(diagnostics.encoding());
        return confirmRepairSave(currentFile, diagnostics, profile);
    }

    /// Shows a repair-save confirmation with region marker metadata when available.
    ///
    /// @param currentFile source that will be rewritten
    /// @param report immutable tolerant-read diagnostics
    /// @param storageProfile immutable storage profile
    /// @return whether the user explicitly approved strict repair publication
    @Override
    public boolean confirmRepairSave(Path currentFile, NBTReadReport report, StorageProfile storageProfile) {
        EdtDispatcher.requireEventDispatchThread();
        Path source = Objects.requireNonNull(currentFile, "currentFile");
        NBTReadReport diagnostics = Objects.requireNonNull(report, "report");
        StorageProfile profile = Objects.requireNonNull(storageProfile, "storageProfile");
        String summary = diagnostics.hasPartialDataLoss()
                ? strings.partialRepairSaveMessage(source)
                : strings.repairSaveMessage(source);
        JTextArea summaryArea = new JTextArea(summary);
        summaryArea.setEditable(false);
        summaryArea.setLineWrap(true);
        summaryArea.setWrapStyleWord(true);
        summaryArea.setOpaque(false);

        JTextArea detailsArea = new JTextArea(NBTReadWarningView.formatReadReport(
                diagnostics, profile, strings));
        detailsArea.setEditable(false);
        detailsArea.setLineWrap(true);
        detailsArea.setWrapStyleWord(true);
        detailsArea.setCaretPosition(0);
        JScrollPane detailsScroll = new JScrollPane(detailsArea);
        detailsScroll.setPreferredSize(new Dimension(620, 180));
        detailsScroll.setVisible(false);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setPreferredSize(new Dimension(660, 230));
        JToggleButton detailsToggle = new JToggleButton(strings.showReadDetailsText());
        detailsToggle.addActionListener(event -> {
            boolean expanded = detailsToggle.isSelected();
            detailsScroll.setVisible(expanded);
            detailsToggle.setText(expanded
                    ? strings.hideReadDetailsText()
                    : strings.showReadDetailsText());
            content.revalidate();
            content.repaint();
        });

        content.add(summaryArea, BorderLayout.NORTH);
        content.add(detailsScroll, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout());
        footer.add(detailsToggle, BorderLayout.WEST);
        content.add(footer, BorderLayout.SOUTH);
        int option = JOptionPane.showConfirmDialog(
                owner,
                content,
                strings.repairSaveTitle(),
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        return option == JOptionPane.YES_OPTION;
    }

    /// Shows a second explicit warning before clearing one fixed Region chunk root.
    ///
    /// @param currentFile current Region source
    /// @param localIndex fixed chunk slot
    /// @return whether clearing was confirmed
    @Override
    public boolean confirmClearChunk(Path currentFile, int localIndex) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(currentFile, "currentFile");
        if (localIndex < 0 || localIndex >= 1024) {
            throw new IndexOutOfBoundsException("localIndex: " + localIndex);
        }
        return JOptionPane.showConfirmDialog(
                owner,
                strings.clearChunkMessage(localIndex),
                strings.clearChunkTitle(),
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }
}
