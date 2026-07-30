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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.plaf.basic.BasicFileChooserUI;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies directory-bar navigation and preservation of the native filename approval contract.
@NotNullByDefault
final class EditablePathChooserTest {
    /// Temporary local file-system root used by chooser navigation tests.
    @TempDir
    private Path temporaryDirectory;

    /// Shows the initial browser directory in the top field without installing the rejected side accessory.
    @Test
    void initializesCurrentDirectoryBarWithoutAccessory() {
        EditablePathChooser chooser = valueOnEventDispatchThread(
                () -> new EditablePathChooser(temporaryDirectory.toFile()));

        assertAll(
                () -> assertEquals(normalize(temporaryDirectory), chooser.currentDirectoryInput().getText()),
                () -> assertNull(chooser.getAccessory()),
                () -> assertTrue(chooser.validationText().isEmpty()));
    }

    /// Places the directory bar above the native chooser content in an actual undisplayed dialog.
    @Test
    void placesDirectoryBarAboveNativeChooserContent() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "A graphical environment is required for dialog construction");
        EditablePathChooser chooser = valueOnEventDispatchThread(
                () -> new EditablePathChooser(temporaryDirectory.toFile()));

        String northComponentName = valueOnEventDispatchThread(() -> {
            JDialog dialog = chooser.createDialog(null);
            try {
                BorderLayout layout = assertInstanceOf(BorderLayout.class, dialog.getContentPane().getLayout());
                Component north = Objects.requireNonNull(
                        layout.getLayoutComponent(BorderLayout.NORTH),
                        "dialog north component");
                return Objects.requireNonNull(north.getName(), "dialog north component name");
            } finally {
                dialog.dispose();
            }
        });

        assertEquals("editablePathChooser.directoryBar", northComponentName);
    }

    /// Switches to an absolute folder without approving and clears a stale file selection.
    ///
    /// @throws IOException when the fixture directory or file cannot be created
    @Test
    void navigatesToAbsoluteDirectoryWithoutApproving() throws IOException {
        Path staleFile = Files.createFile(temporaryDirectory.resolve("stale.txt"));
        Path targetDirectory = Files.createDirectory(temporaryDirectory.resolve("target"));
        EditablePathChooser chooser = valueOnEventDispatchThread(
                () -> new EditablePathChooser(temporaryDirectory.toFile()));
        AtomicBoolean approved = new AtomicBoolean();

        onEventDispatchThread(() -> {
            chooser.setSelectedFile(staleFile.toFile());
            chooser.addActionListener(event -> approved.set(
                    JFileChooser.APPROVE_SELECTION.equals(event.getActionCommand())));
            chooser.currentDirectoryInput().setText(targetDirectory.toString());
            chooser.navigateDirectoryButton().doClick();
        });

        assertAll(
                () -> assertFalse(approved.get()),
                () -> assertEquals(normalize(targetDirectory), normalize(chooser.getCurrentDirectory().toPath())),
                () -> assertEquals(normalize(targetDirectory), chooser.currentDirectoryInput().getText()),
                () -> assertNull(chooser.getSelectedFile()),
                () -> assertTrue(chooser.validationText().isEmpty()));
    }

    /// Resolves a relative folder and still permits a later browser-style file selection and approval.
    ///
    /// @throws IOException when the fixture directory or file cannot be created
    @Test
    void continuesSelectionAfterRelativeDirectoryNavigation() throws IOException {
        Path targetDirectory = Files.createDirectory(temporaryDirectory.resolve("runtime"));
        Path selectedFile = Files.createFile(targetDirectory.resolve("java.exe"));
        EditablePathChooser chooser = valueOnEventDispatchThread(
                () -> new EditablePathChooser(temporaryDirectory.toFile()));
        AtomicBoolean approved = new AtomicBoolean();

        onEventDispatchThread(() -> {
            chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            chooser.addActionListener(event -> approved.set(
                    JFileChooser.APPROVE_SELECTION.equals(event.getActionCommand())));
            chooser.currentDirectoryInput().setText("runtime");
            chooser.currentDirectoryInput().postActionEvent();
            assertFalse(approved.get());
            chooser.setSelectedFile(selectedFile.toFile());
            chooser.approveSelection();
        });

        assertAll(
                () -> assertTrue(approved.get()),
                () -> assertEquals(normalize(targetDirectory), normalize(chooser.getCurrentDirectory().toPath())),
                () -> assertEquals(normalize(selectedFile), normalize(chooser.getSelectedFile().toPath())));
    }

    /// Synchronizes native browser directory changes into the top field.
    ///
    /// @throws IOException when the fixture directory cannot be created
    @Test
    void synchronizesBrowserDirectoryChanges() throws IOException {
        Path targetDirectory = Files.createDirectory(temporaryDirectory.resolve("libraries"));
        EditablePathChooser chooser = valueOnEventDispatchThread(
                () -> new EditablePathChooser(temporaryDirectory.toFile()));

        onEventDispatchThread(() -> chooser.setCurrentDirectory(targetDirectory.toFile()));

        assertEquals(normalize(targetDirectory), chooser.currentDirectoryInput().getText());
    }

    /// Keeps the top field on the current folder when a file is selected in the native browser.
    ///
    /// @throws IOException when the fixture file cannot be created
    @Test
    void doesNotMirrorSelectedFileIntoDirectoryBar() throws IOException {
        Path selectedFile = Files.createFile(temporaryDirectory.resolve("selected.nbt"));
        EditablePathChooser chooser = valueOnEventDispatchThread(
                () -> new EditablePathChooser(temporaryDirectory.toFile()));

        onEventDispatchThread(() -> chooser.setSelectedFile(selectedFile.toFile()));

        assertEquals(normalize(temporaryDirectory), chooser.currentDirectoryInput().getText());
    }

    /// Rejects file, missing-folder, and syntactically invalid values without navigation or approval.
    ///
    /// @throws IOException when the fixture file cannot be created
    @Test
    void rejectsInvalidDirectoryBarValues() throws IOException {
        Path file = Files.createFile(temporaryDirectory.resolve("not-a-folder.zip"));
        EditablePathChooser chooser = valueOnEventDispatchThread(
                () -> new EditablePathChooser(temporaryDirectory.toFile()));
        AtomicBoolean approved = new AtomicBoolean();

        onEventDispatchThread(() -> {
            chooser.addActionListener(event -> approved.set(
                    JFileChooser.APPROVE_SELECTION.equals(event.getActionCommand())));

            chooser.currentDirectoryInput().setText(file.toString());
            chooser.navigateDirectoryButton().doClick();
            assertFalse(chooser.validationText().isEmpty());

            chooser.currentDirectoryInput().setText(temporaryDirectory.resolve("missing").toString());
            chooser.navigateDirectoryButton().doClick();
            assertFalse(chooser.validationText().isEmpty());

            chooser.currentDirectoryInput().setText("\0");
            chooser.navigateDirectoryButton().doClick();
            assertFalse(chooser.validationText().isEmpty());
        });

        assertAll(
                () -> assertFalse(approved.get()),
                () -> assertEquals(normalize(temporaryDirectory), normalize(chooser.getCurrentDirectory().toPath())));
    }

    /// Lets the native filename field approve a directly entered absolute file path.
    ///
    /// @throws IOException when the fixture file cannot be created
    @Test
    void approvesDirectFilePathThroughNativeFilenameField() throws IOException {
        Path selectedFile = Files.createFile(temporaryDirectory.resolve("selected.jar"));
        EditablePathChooser chooser = valueOnEventDispatchThread(
                () -> new EditablePathChooser(temporaryDirectory.toFile()));
        AtomicBoolean approved = new AtomicBoolean();

        onEventDispatchThread(() -> {
            chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            chooser.addActionListener(event -> approved.set(
                    JFileChooser.APPROVE_SELECTION.equals(event.getActionCommand())));
            BasicFileChooserUI chooserUi = assertInstanceOf(BasicFileChooserUI.class, chooser.getUI());
            chooserUi.setFileName(selectedFile.toString());
            chooserUi.getApproveSelectionAction().actionPerformed(
                    new ActionEvent(chooser, ActionEvent.ACTION_PERFORMED, "approveSelection"));
        });

        assertAll(
                () -> assertTrue(approved.get()),
                () -> assertEquals(normalize(selectedFile), normalize(chooser.getSelectedFile().toPath())),
                () -> assertEquals(normalize(temporaryDirectory), chooser.currentDirectoryInput().getText()));
    }

    /// Clears stale multi-selection when the directory bar changes the visible folder.
    ///
    /// @throws IOException when the fixture paths cannot be created
    @Test
    void clearsMultiSelectionDuringDirectoryNavigation() throws IOException {
        Path first = Files.createFile(temporaryDirectory.resolve("first.zip"));
        Path second = Files.createFile(temporaryDirectory.resolve("second.zip"));
        Path targetDirectory = Files.createDirectory(temporaryDirectory.resolve("target"));
        EditablePathChooser chooser = valueOnEventDispatchThread(
                () -> new EditablePathChooser(temporaryDirectory.toFile()));

        onEventDispatchThread(() -> {
            chooser.setMultiSelectionEnabled(true);
            chooser.setSelectedFiles(new java.io.File[]{first.toFile(), second.toFile()});
            chooser.currentDirectoryInput().setText(targetDirectory.toString());
            chooser.navigateDirectoryButton().doClick();
        });

        assertAll(
                () -> assertEquals(0, chooser.getSelectedFiles().length),
                () -> assertNull(chooser.getSelectedFile()),
                () -> assertEquals(normalize(targetDirectory), normalize(chooser.getCurrentDirectory().toPath())));
    }

    /// Normalizes one path using the same absolute representation shown by the chooser.
    ///
    /// @param path source path
    /// @return normalized absolute path text
    private static String normalize(Path path) {
        return path.toAbsolutePath().normalize().toString();
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
