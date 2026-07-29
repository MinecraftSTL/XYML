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
package space.minecraftstl.xyml.ui.swing.dialog;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies typed path resolution, native selection synchronization, and mode-specific approval validation.
@NotNullByDefault
final class EditablePathChooserTest {
    /// Temporary local file-system root used by chooser validation.
    @TempDir
    private Path temporaryDirectory;

    /// Commits one directly typed existing file and retains the native approval contract.
    ///
    /// @throws IOException when the test file cannot be created
    @Test
    void approvesDirectlyTypedExistingFile() throws IOException {
        Path selected = Files.createFile(temporaryDirectory.resolve("selected.jar"));
        EditablePathChooser chooser = valueOnEventDispatchThread(
                () -> new EditablePathChooser(temporaryDirectory.toFile()));
        AtomicBoolean approved = new AtomicBoolean();

        onEventDispatchThread(() -> {
            chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            chooser.setAcceptAllFileFilterUsed(false);
            chooser.setFileFilter(new FileNameExtensionFilter("JAR", "jar"));
            chooser.pathInput().setText(selected.toString());
            chooser.addActionListener(event -> approved.set(
                    JFileChooser.APPROVE_SELECTION.equals(event.getActionCommand())));
            chooser.applyPathButton().doClick();
        });

        assertAll(
                () -> assertTrue(approved.get()),
                () -> assertEquals(selected, chooser.getSelectedFile().toPath()),
                () -> assertTrue(chooser.validationText().isEmpty()));
    }

    /// Resolves relative directory input against the chooser's visible current directory.
    ///
    /// @throws IOException when the test directory cannot be created
    @Test
    void resolvesRelativeDirectoryInput() throws IOException {
        Path selected = Files.createDirectory(temporaryDirectory.resolve("runtime"));
        EditablePathChooser chooser = valueOnEventDispatchThread(
                () -> new EditablePathChooser(temporaryDirectory.toFile()));
        AtomicBoolean approved = new AtomicBoolean();

        onEventDispatchThread(() -> {
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.pathInput().setText("runtime");
            chooser.addActionListener(event -> approved.set(
                    JFileChooser.APPROVE_SELECTION.equals(event.getActionCommand())));
            chooser.approveSelection();
        });

        assertAll(
                () -> assertTrue(approved.get()),
                () -> assertEquals(selected, chooser.getSelectedFile().toPath()));
    }

    /// Accepts either existing type when a Java-runtime style chooser permits files and directories.
    ///
    /// @throws IOException when the fixture paths cannot be created
    @Test
    void supportsFilesAndDirectoriesMode() throws IOException {
        Path file = Files.createFile(temporaryDirectory.resolve("java.exe"));
        Path directory = Files.createDirectory(temporaryDirectory.resolve("jdk"));
        EditablePathChooser fileChooser = valueOnEventDispatchThread(
                () -> new EditablePathChooser(temporaryDirectory.toFile()));
        EditablePathChooser directoryChooser = valueOnEventDispatchThread(
                () -> new EditablePathChooser(temporaryDirectory.toFile()));
        AtomicBoolean fileApproved = new AtomicBoolean();
        AtomicBoolean directoryApproved = new AtomicBoolean();

        onEventDispatchThread(() -> {
            fileChooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
            fileChooser.pathInput().setText(file.toString());
            fileChooser.addActionListener(event -> fileApproved.set(
                    JFileChooser.APPROVE_SELECTION.equals(event.getActionCommand())));
            fileChooser.approveSelection();

            directoryChooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
            directoryChooser.pathInput().setText(directory.toString());
            directoryChooser.addActionListener(event -> directoryApproved.set(
                    JFileChooser.APPROVE_SELECTION.equals(event.getActionCommand())));
            directoryChooser.approveSelection();
        });

        assertAll(
                () -> assertTrue(fileApproved.get()),
                () -> assertEquals(file, fileChooser.getSelectedFile().toPath()),
                () -> assertTrue(directoryApproved.get()),
                () -> assertEquals(directory, directoryChooser.getSelectedFile().toPath()));
    }

    /// Accepts quoted multi-file input with one path per line and preserves input order.
    ///
    /// @throws IOException when the test files cannot be created
    @Test
    void approvesQuotedMultipleFilesInInputOrder() throws IOException {
        Path first = Files.createFile(temporaryDirectory.resolve("first.zip"));
        Path second = Files.createFile(temporaryDirectory.resolve("second.zip"));
        EditablePathChooser chooser = valueOnEventDispatchThread(
                () -> new EditablePathChooser(temporaryDirectory.toFile()));
        AtomicBoolean approved = new AtomicBoolean();

        onEventDispatchThread(() -> {
            chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            chooser.setMultiSelectionEnabled(true);
            chooser.setAcceptAllFileFilterUsed(false);
            chooser.setFileFilter(new FileNameExtensionFilter("ZIP", "zip"));
            chooser.pathInput().setText('"' + first.toString() + '"'
                    + System.lineSeparator()
                    + '\'' + second.toString() + '\'');
            chooser.addActionListener(event -> approved.set(
                    JFileChooser.APPROVE_SELECTION.equals(event.getActionCommand())));
            chooser.approveSelection();
        });

        @Unmodifiable List<Path> selected = Arrays.stream(chooser.getSelectedFiles())
                .map(File::toPath)
                .toList();
        assertAll(
                () -> assertTrue(approved.get()),
                () -> assertEquals(List.of(first, second), selected));
    }

    /// Rejects missing, wrong-type, and filter-incompatible open targets without firing approval.
    ///
    /// @throws IOException when the fixture paths cannot be created
    @Test
    void rejectsInvalidOpenTargets() throws IOException {
        Path directory = Files.createDirectory(temporaryDirectory.resolve("directory"));
        Path textFile = Files.createFile(temporaryDirectory.resolve("notes.txt"));
        EditablePathChooser chooser = valueOnEventDispatchThread(
                () -> new EditablePathChooser(temporaryDirectory.toFile()));
        AtomicBoolean approved = new AtomicBoolean();

        onEventDispatchThread(() -> {
            chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            chooser.setAcceptAllFileFilterUsed(false);
            chooser.setFileFilter(new FileNameExtensionFilter("ZIP", "zip"));
            chooser.addActionListener(event -> approved.set(
                    JFileChooser.APPROVE_SELECTION.equals(event.getActionCommand())));

            chooser.pathInput().setText(temporaryDirectory.resolve("missing.zip").toString());
            chooser.approveSelection();
            assertFalse(chooser.validationText().isEmpty());

            chooser.pathInput().setText(directory.toString());
            chooser.approveSelection();
            assertFalse(chooser.validationText().isEmpty());

            chooser.pathInput().setText(textFile.toString());
            chooser.approveSelection();
            assertFalse(chooser.validationText().isEmpty());
        });

        assertFalse(approved.get());
    }

    /// Adds a required save extension and rejects an absent or non-writable parent.
    @Test
    void validatesSaveDestinationAndAddsExtension() {
        EditablePathChooser chooser = valueOnEventDispatchThread(
                () -> new EditablePathChooser(temporaryDirectory.toFile()));
        AtomicBoolean approved = new AtomicBoolean();

        onEventDispatchThread(() -> {
            chooser.setDialogType(JFileChooser.SAVE_DIALOG);
            chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            chooser.setAcceptAllFileFilterUsed(false);
            chooser.setFileFilter(new FileNameExtensionFilter("ZIP", "zip"));
            chooser.addActionListener(event -> approved.set(
                    JFileChooser.APPROVE_SELECTION.equals(event.getActionCommand())));
            chooser.pathInput().setText(temporaryDirectory.resolve("export").toString());
            chooser.approveSelection();
        });

        assertAll(
                () -> assertTrue(approved.get()),
                () -> assertEquals(
                        temporaryDirectory.resolve("export.zip"),
                        chooser.getSelectedFile().toPath()));

        EditablePathChooser invalidChooser = valueOnEventDispatchThread(
                () -> new EditablePathChooser(temporaryDirectory.toFile()));
        AtomicBoolean invalidApproved = new AtomicBoolean();
        onEventDispatchThread(() -> {
            invalidChooser.setDialogType(JFileChooser.SAVE_DIALOG);
            invalidChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            invalidChooser.setAcceptAllFileFilterUsed(false);
            invalidChooser.setFileFilter(new FileNameExtensionFilter("ZIP", "zip"));
            invalidChooser.addActionListener(event -> invalidApproved.set(
                    JFileChooser.APPROVE_SELECTION.equals(event.getActionCommand())));
            invalidChooser.pathInput().setText(temporaryDirectory.resolve("export.txt").toString());
            invalidChooser.approveSelection();
            assertFalse(invalidChooser.validationText().isEmpty());

            invalidChooser.pathInput().setText(
                    temporaryDirectory.resolve("missing-parent").resolve("export.zip").toString());
            invalidChooser.approveSelection();
        });

        assertAll(
                () -> assertFalse(invalidApproved.get()),
                () -> assertFalse(invalidChooser.validationText().isEmpty()));
    }

    /// Handles compound extensions even though the standard extension filter examines only the final suffix.
    ///
    /// @throws IOException when the compound-extension fixture cannot be created
    @Test
    void acceptsCompoundExtension() throws IOException {
        Path archive = Files.createFile(temporaryDirectory.resolve("runtime.tar.gz"));
        EditablePathChooser chooser = valueOnEventDispatchThread(
                () -> new EditablePathChooser(temporaryDirectory.toFile()));
        AtomicBoolean approved = new AtomicBoolean();

        onEventDispatchThread(() -> {
            chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            chooser.setAcceptAllFileFilterUsed(false);
            chooser.setFileFilter(new FileNameExtensionFilter("Java archive", "zip", "tar.gz"));
            chooser.pathInput().setText(archive.toString());
            chooser.addActionListener(event -> approved.set(
                    JFileChooser.APPROVE_SELECTION.equals(event.getActionCommand())));
            chooser.approveSelection();
        });

        assertTrue(approved.get());
    }

    /// Mirrors browser-selected files into the editable field without requiring approval.
    ///
    /// @throws IOException when the browser fixture cannot be created
    @Test
    void synchronizesBrowserSelectionIntoEditableInput() throws IOException {
        Path selected = Files.createFile(temporaryDirectory.resolve("selected.nbt"));
        EditablePathChooser chooser = valueOnEventDispatchThread(
                () -> new EditablePathChooser(temporaryDirectory.toFile()));

        onEventDispatchThread(() -> chooser.setSelectedFile(selected.toFile()));

        assertEquals(selected.toString(), chooser.pathInput().getText());
    }

    /// Runs a state mutation synchronously on the Swing event-dispatch thread.
    ///
    /// @param action mutation to run
    private static void onEventDispatchThread(Runnable action) {
        EdtDispatcher.executeAndWait(action);
    }

    /// Creates a value synchronously on the Swing event-dispatch thread.
    ///
    /// @param supplier value supplier
    /// @param <T> value type
    /// @return supplied value
    private static <T extends Object> T valueOnEventDispatchThread(Supplier<T> supplier) {
        AtomicReference<T> reference = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> reference.set(supplier.get()));
        return reference.get();
    }
}
