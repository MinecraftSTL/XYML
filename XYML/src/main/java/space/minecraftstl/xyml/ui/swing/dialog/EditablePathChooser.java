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

import com.formdev.flatlaf.extras.FlatSVGIcon;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.basic.BasicFileChooserUI;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Insets;
import java.io.File;
import java.io.IOError;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// A native file chooser with an editable current-folder bar above its dialog content.
///
/// The top field changes only the directory displayed by the chooser. File paths and save names remain the
/// responsibility of the Look & Feel's native filename field, so navigating to a typed folder never approves or closes
/// the dialog and users can continue browsing from that location.
@NotNullByDefault
public final class EditablePathChooser extends JFileChooser {
    /// Serialization identifier retained for the Swing component contract.
    private static final long serialVersionUID = 1L;

    /// Icon size used by the compact directory navigation action.
    private static final int NAVIGATE_ICON_SIZE = 18;

    /// FlatLaf client property used to mark an input with validation feedback.
    private static final String OUTLINE_PROPERTY = "JComponent.outline";

    /// FlatLaf outline value used for an invalid directory path.
    private static final String ERROR_OUTLINE = "error";

    /// Top-level panel inserted above the native chooser when its dialog is created.
    private final JPanel directoryBar = new JPanel(new BorderLayout(8, 0));

    /// Editable path of the directory currently displayed by the chooser.
    private final JTextField currentDirectoryInput = new JTextField();

    /// Icon action that applies the typed directory without approving the chooser.
    private final JButton navigateDirectoryButton = new JButton();

    /// Inline selection failure shown without closing the chooser or opening a modal dialog.
    private final JLabel selectionErrorLabel = new JLabel(" ");

    /// Current directory-input validation detail, or `null` when the field is valid.
    private @Nullable String directoryValidation;

    /// Creates a chooser rooted at the platform-default directory.
    public EditablePathChooser() {
        super();
        initializeDirectoryBar();
    }

    /// Creates a chooser rooted at an explicit current directory.
    ///
    /// @param currentDirectory initial browser directory
    public EditablePathChooser(File currentDirectory) {
        super(Objects.requireNonNull(currentDirectory, "currentDirectory"));
        initializeDirectoryBar();
    }

    /// Creates the native chooser dialog and places the editable current-folder bar above it.
    ///
    /// @param parent parent component used for ownership and positioning, or `null` for no parent
    /// @return packed chooser dialog containing the directory bar
    @Override
    protected JDialog createDialog(@Nullable Component parent) {
        JDialog dialog = super.createDialog(parent);
        Container contentPane = dialog.getContentPane();
        contentPane.add(directoryBar, BorderLayout.NORTH);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        return dialog;
    }

    /// Normalizes supported Windows filename quoting and rejects selections that cannot be represented as NIO paths.
    @Override
    public void approveSelection() {
        clearSelectionValidation();
        @Nullable String invalidPath = invalidSelectionPath();
        if (invalidPath != null) {
            showSelectionValidation(i18n("swing.path_chooser.error.invalid", invalidPath));
            return;
        }
        super.approveSelection();
    }

    /// Configures the current-folder field, navigation action, and directory synchronization.
    private void initializeDirectoryBar() {
        String fieldDescription = i18n("swing.path_chooser.current_directory");
        currentDirectoryInput.setName("editablePathChooser.currentDirectory");
        currentDirectoryInput.setColumns(48);
        currentDirectoryInput.putClientProperty("JTextField.placeholderText", fieldDescription);
        currentDirectoryInput.setToolTipText(fieldDescription);
        currentDirectoryInput.getAccessibleContext().setAccessibleName(fieldDescription);
        currentDirectoryInput.getDocument().addDocumentListener(
                new DirectoryInputDocumentListener(this::clearDirectoryValidation));
        currentDirectoryInput.addActionListener(event -> navigateToTypedDirectory());

        String navigateDescription = i18n("swing.path_chooser.navigate");
        navigateDirectoryButton.setName("editablePathChooser.navigateDirectory");
        navigateDirectoryButton.setIcon(new FlatSVGIcon(
                "assets/swing/icons/arrow-forward.svg",
                NAVIGATE_ICON_SIZE,
                NAVIGATE_ICON_SIZE));
        navigateDirectoryButton.setToolTipText(navigateDescription);
        navigateDirectoryButton.getAccessibleContext().setAccessibleName(navigateDescription);
        navigateDirectoryButton.setMargin(new Insets(4, 8, 4, 8));
        navigateDirectoryButton.addActionListener(event -> navigateToTypedDirectory());

        @Nullable Color selectionErrorColor = UIManager.getColor("Component.error.focusedBorderColor");
        if (selectionErrorColor != null) {
            selectionErrorLabel.setForeground(selectionErrorColor);
        }
        selectionErrorLabel.setName("editablePathChooser.selectionError");

        directoryBar.setName("editablePathChooser.directoryBar");
        directoryBar.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        directoryBar.add(currentDirectoryInput, BorderLayout.CENTER);
        directoryBar.add(navigateDirectoryButton, BorderLayout.EAST);
        directoryBar.add(selectionErrorLabel, BorderLayout.SOUTH);

        addPropertyChangeListener(DIRECTORY_CHANGED_PROPERTY, event -> {
            synchronizeDirectoryInput();
            clearSelectionValidation();
        });
        addPropertyChangeListener(SELECTED_FILE_CHANGED_PROPERTY, event -> clearSelectionValidation());
        addPropertyChangeListener(SELECTED_FILES_CHANGED_PROPERTY, event -> clearSelectionValidation());
        synchronizeDirectoryInput();
    }

    /// Resolves the typed directory and updates the browser without firing an approval event.
    private void navigateToTypedDirectory() {
        String input = removeMatchingQuotes(currentDirectoryInput.getText().strip());
        if (input.isEmpty()) {
            showDirectoryValidation(i18n("swing.path_chooser.error.directory_required"));
            return;
        }

        final Path candidate;
        try {
            candidate = resolveAgainstCurrentDirectory(Path.of(input));
        } catch (InvalidPathException | SecurityException failure) {
            showDirectoryValidation(i18n("swing.path_chooser.error.invalid", input));
            return;
        }

        try {
            if (!Files.isDirectory(candidate)) {
                showDirectoryValidation(i18n("swing.path_chooser.error.directory_unavailable", candidate));
                return;
            }

            setSelectedFile(null);
            setSelectedFiles(new File[0]);
            setCurrentDirectory(candidate.toFile());
            rescanCurrentDirectory();
            synchronizeDirectoryInput();
        } catch (SecurityException failure) {
            showDirectoryValidation(i18n("swing.path_chooser.error.directory_unavailable", candidate));
        }
    }

    /// Resolves a relative directory against the browser's current directory.
    ///
    /// @param input parsed typed path
    /// @return normalized absolute directory candidate
    private Path resolveAgainstCurrentDirectory(Path input) {
        Path path = Objects.requireNonNull(input, "input");
        if (path.isAbsolute()) {
            return path.normalize();
        }
        @Nullable File currentDirectory = getCurrentDirectory();
        @Nullable Path fileSystemDirectory = fileSystemPath(currentDirectory);
        if (currentDirectory != null && fileSystemDirectory == null) {
            throw new InvalidPathException(
                    path.toString(),
                    "The current chooser location has no file-system path");
        }
        Path base = fileSystemDirectory == null ? Path.of("").toAbsolutePath() : fileSystemDirectory;
        return base.resolve(path).normalize();
    }

    /// Mirrors the chooser's displayed directory into the top field.
    private void synchronizeDirectoryInput() {
        synchronizeDirectoryInput(getCurrentDirectory());
    }

    /// Mirrors one displayed directory into the top field when it has a real file-system path.
    ///
    /// Windows exposes locations such as "This PC" as virtual Shell folders whose `File` path cannot be converted to
    /// an NIO `Path`. Those locations remain browsable through the native chooser, while the editable path field stays
    /// empty until the user selects or enters a real directory.
    ///
    /// @param currentDirectory displayed browser directory, or `null` when no directory is available
    void synchronizeDirectoryInput(@Nullable File currentDirectory) {
        @Nullable Path fileSystemDirectory = fileSystemPath(currentDirectory);
        currentDirectoryInput.setText(fileSystemDirectory == null ? "" : fileSystemDirectory.toString());
        clearDirectoryValidation();
    }

    /// Resolves a chooser directory only when the platform reports a real file-system location.
    ///
    /// @param directory chooser directory, including a possible native virtual location
    /// @return normalized absolute path, or `null` when no real file-system path is available
    private @Nullable Path fileSystemPath(@Nullable File directory) {
        if (directory == null) {
            return null;
        }
        try {
            if (!getFileSystemView().isFileSystem(directory)) {
                return null;
            }
            return directory.toPath().toAbsolutePath().normalize();
        } catch (InvalidPathException | SecurityException ignored) {
            // Some native folders expose a legacy File facade even though no NIO path exists.
            return null;
        }
    }

    /// Shows directory validation through the field outline and tooltip without closing the dialog.
    ///
    /// @param message localized validation detail
    private void showDirectoryValidation(String message) {
        directoryValidation = Objects.requireNonNull(message, "message");
        currentDirectoryInput.putClientProperty(OUTLINE_PROPERTY, ERROR_OUTLINE);
        currentDirectoryInput.setToolTipText(message);
        currentDirectoryInput.requestFocusInWindow();
        currentDirectoryInput.selectAll();
    }

    /// Clears stale directory validation after editing or successful navigation.
    private void clearDirectoryValidation() {
        directoryValidation = null;
        currentDirectoryInput.putClientProperty(OUTLINE_PROPERTY, null);
        currentDirectoryInput.setToolTipText(i18n("swing.path_chooser.current_directory"));
    }

    /// Returns the selected path that cannot be approved, or `null` when every selected path is valid.
    ///
    /// @return offending path text, or `null` when no path prevents approval
    private @Nullable String invalidSelectionPath() {
        @Nullable String quotedPathError = normalizeQuotedWindowsSelection();
        if (quotedPathError != null) {
            return quotedPathError;
        }

        @Nullable File[] selectedFiles = selectedFilesForValidation();
        if (selectedFiles == null) {
            return null;
        }
        for (File selectedFile : selectedFiles) {
            try {
                selectedFile.toPath();
            } catch (InvalidPathException | SecurityException | IOError ignored) {
                return selectedFile.getPath();
            }
        }
        return null;
    }

    /// Removes one layer of double quotes around an absolute Windows filename when the native input uses that form.
    ///
    /// @return offending raw filename, or `null` when no supported normalization was required or it succeeded
    private @Nullable String normalizeQuotedWindowsSelection() {
        if (File.separatorChar != '\\') {
            return null;
        }

        @Nullable String filenameError = normalizeQuotedWindowsFilename();
        if (filenameError != null) {
            return filenameError;
        }
        return normalizeQuotedWindowsSelectedPath();
    }

    /// Removes one layer of double quotes from the current native filename when the UI exposes that text.
    ///
    /// @return offending raw filename, or `null` when no supported normalization was required or it succeeded
    private @Nullable String normalizeQuotedWindowsFilename() {
        if (!(getUI() instanceof BasicFileChooserUI chooserUi)) {
            return null;
        }

        @Nullable String fileName = chooserUi.getFileName();
        if (!isSingleDoubleQuotedLayer(fileName)) {
            return null;
        }
        String value = Objects.requireNonNull(fileName, "fileName");
        String candidateText = value.substring(1, value.length() - 1);
        try {
            Path candidate = Path.of(candidateText);
            if (!candidate.isAbsolute()) {
                return value;
            }
            setSelectedFile(candidate.toFile());
            return null;
        } catch (InvalidPathException | SecurityException | IOError ignored) {
            return value;
        }
    }

    /// Removes one layer of double quotes from a Windows path already resolved by the chooser.
    ///
    /// @return offending selected path, or `null` when no supported normalization was required or it succeeded
    private @Nullable String normalizeQuotedWindowsSelectedPath() {
        @Nullable File selectedFile = getSelectedFile();
        if (selectedFile == null) {
            return null;
        }

        String selectedPath = selectedFile.getPath();
        int openingQuote = selectedPath.indexOf('"');
        int closingQuote = selectedPath.lastIndexOf('"');
        if (openingQuote < 0 || closingQuote <= openingQuote + 1 || closingQuote != selectedPath.length() - 1) {
            return null;
        }

        String candidateText = selectedPath.substring(openingQuote + 1, closingQuote);
        try {
            Path candidate = Path.of(candidateText);
            if (!candidate.isAbsolute()) {
                return selectedPath;
            }
            setSelectedFile(candidate.toFile());
            return null;
        } catch (InvalidPathException | SecurityException | IOError ignored) {
            return selectedPath;
        }
    }

    /// Tests whether one native filename contains exactly one enclosing double-quote layer.
    ///
    /// @param value native filename input, or `null` when unavailable
    /// @return whether one pair of enclosing double quotes can be removed safely
    private static boolean isSingleDoubleQuotedLayer(@Nullable String value) {
        if (value == null || value.length() < 3) {
            return false;
        }
        if (value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') {
            return false;
        }
        String inner = value.substring(1, value.length() - 1);
        return !inner.startsWith("\"") && !inner.endsWith("\"");
    }

    /// Returns the current selection as files requiring NIO path validation.
    ///
    /// @return selected files, or `null` when no file is selected
    private @Nullable File[] selectedFilesForValidation() {
        if (isMultiSelectionEnabled()) {
            File[] selectedFiles = getSelectedFiles();
            return selectedFiles.length == 0 ? null : selectedFiles;
        }

        @Nullable File selectedFile = getSelectedFile();
        return selectedFile == null ? null : new File[]{selectedFile};
    }

    /// Shows one selection failure in the directory bar without closing the chooser.
    ///
    /// @param message localized selection failure
    private void showSelectionValidation(String message) {
        selectionErrorLabel.setText(Objects.requireNonNull(message, "message"));
        selectionErrorLabel.setToolTipText(message);
    }

    /// Clears stale selection failure feedback after a new selection or before another approval attempt.
    private void clearSelectionValidation() {
        selectionErrorLabel.setText(" ");
        selectionErrorLabel.setToolTipText(null);
    }

    /// Removes matching single or double quotes commonly produced by platform copy-as-path actions.
    ///
    /// @param input stripped input text
    /// @return unquoted path text when both delimiters match
    private static String removeMatchingQuotes(String input) {
        String value = Objects.requireNonNull(input, "input");
        if (value.length() < 2) {
            return value;
        }
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        return first == last && (first == '\'' || first == '"')
                ? value.substring(1, value.length() - 1).strip()
                : value;
    }

    /// Returns the editable current-directory field for package-local focused tests.
    ///
    /// @return current-directory field
    JTextField currentDirectoryInput() {
        return currentDirectoryInput;
    }

    /// Returns the directory navigation action for package-local focused tests.
    ///
    /// @return navigation button
    JButton navigateDirectoryButton() {
        return navigateDirectoryButton;
    }

    /// Returns the current directory-input validation detail for package-local focused tests.
    ///
    /// @return validation detail, or an empty string when no error is shown
    String validationText() {
        return directoryValidation == null ? "" : directoryValidation;
    }

    /// Returns the inline selection-validation detail for package-local focused tests.
    ///
    /// @return selection detail, or an empty string when no error is shown
    String selectionValidationText() {
        @Nullable String text = selectionErrorLabel.getText();
        return text == null ? "" : text.strip();
    }

    /// Clears directory validation whenever the user edits the top field.
    @NotNullByDefault
    private static final class DirectoryInputDocumentListener implements DocumentListener {
        /// Callback that clears validation after a document mutation.
        private final Runnable clearValidation;

        /// Creates a listener for one directory field.
        ///
        /// @param clearValidation callback that clears its validation state
        private DirectoryInputDocumentListener(Runnable clearValidation) {
            this.clearValidation = Objects.requireNonNull(clearValidation, "clearValidation");
        }

        /// Clears validation after inserted text.
        ///
        /// @param event document mutation event
        @Override
        public void insertUpdate(DocumentEvent event) {
            Objects.requireNonNull(event, "event");
            clearValidation.run();
        }

        /// Clears validation after removed text.
        ///
        /// @param event document mutation event
        @Override
        public void removeUpdate(DocumentEvent event) {
            Objects.requireNonNull(event, "event");
            clearValidation.run();
        }

        /// Clears validation after an attribute-only mutation.
        ///
        /// @param event document mutation event
        @Override
        public void changedUpdate(DocumentEvent event) {
            Objects.requireNonNull(event, "event");
            clearValidation.run();
        }
    }
}
