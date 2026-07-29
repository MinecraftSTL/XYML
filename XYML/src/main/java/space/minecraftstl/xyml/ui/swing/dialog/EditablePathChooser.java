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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import javax.swing.filechooser.FileFilter;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// A file chooser that keeps native directory browsing while exposing an always-editable path input.
///
/// Single-selection and save modes accept one path. Multi-selection open mode accepts one path per line. Typed relative
/// paths are resolved against the chooser's current directory. Validation runs only when the user approves the dialog,
/// avoiding file-system access for every document edit while still rejecting missing or mode-incompatible open targets
/// and invalid save parents.
@NotNullByDefault
public final class EditablePathChooser extends JFileChooser {
    /// Serialization identifier retained for the Swing component contract.
    private static final long serialVersionUID = 1L;

    /// Preferred width of the editable accessory without constraining the native browser area.
    private static final int ACCESSORY_WIDTH = 320;

    /// Preferred height that keeps wrapped guidance, input, and inline validation from overlapping.
    private static final int ACCESSORY_HEIGHT = 210;

    /// Editable single- or multi-line path source presented beside the browser.
    private final JTextArea pathInput = new JTextArea(3, 32);

    /// Contextual input guidance updated with the chooser's selection mode.
    private final JTextArea inputHint = new JTextArea(2, 28);

    /// Inline validation feedback retained until the next edit or successful approval.
    private final JTextArea validationMessage = new JTextArea(2, 28);

    /// Explicit accessory action that commits typed input independently of Look & Feel internals.
    private final JButton applyPathButton = new JButton();

    /// Prevents a programmatic browser-selection update from recursively overwriting the input.
    private boolean synchronizingSelection;

    /// Creates a chooser rooted at the platform-default directory.
    public EditablePathChooser() {
        super();
        initializeEditableAccessory();
    }

    /// Creates a chooser rooted at an explicit current directory.
    ///
    /// @param currentDirectory initial browser directory
    public EditablePathChooser(File currentDirectory) {
        super(Objects.requireNonNull(currentDirectory, "currentDirectory"));
        initializeEditableAccessory();
    }

    /// Validates and commits the editable input before allowing the native chooser to approve.
    @Override
    public void approveSelection() {
        SelectionResolution resolution = resolveSelection();
        @Nullable String error = resolution.error();
        if (error != null) {
            validationMessage.setText(error);
            validationMessage.setToolTipText(error);
            pathInput.requestFocusInWindow();
            return;
        }

        @Unmodifiable List<Path> paths = resolution.paths();
        synchronizingSelection = true;
        try {
            if (isMultiSelectionEnabled() && getDialogType() != SAVE_DIALOG) {
                setSelectedFiles(paths.stream().map(Path::toFile).toArray(File[]::new));
            } else {
                setSelectedFile(paths.get(0).toFile());
            }
        } finally {
            synchronizingSelection = false;
        }
        validationMessage.setText(" ");
        validationMessage.setToolTipText(null);
        super.approveSelection();
    }

    /// Initializes the accessory components and browser-to-input synchronization without file-system access.
    private void initializeEditableAccessory() {
        pathInput.setName("editablePathChooser.pathInput");
        pathInput.setLineWrap(false);
        pathInput.getAccessibleContext().setAccessibleName(i18n("swing.path_chooser.path"));
        pathInput.getDocument().addDocumentListener(new PathInputDocumentListener(validationMessage));

        inputHint.setName("editablePathChooser.pathHint");
        configureReadOnlyText(inputHint);
        validationMessage.setName("editablePathChooser.validationMessage");
        configureReadOnlyText(validationMessage);
        @Nullable Color errorColor = UIManager.getColor("Component.error.focusedBorderColor");
        validationMessage.setForeground(errorColor == null ? Color.RED.darker() : errorColor);

        applyPathButton.setName("editablePathChooser.applyPath");
        applyPathButton.setText(i18n("swing.path_chooser.apply"));
        applyPathButton.addActionListener(event -> approveSelection());

        JScrollPane inputScrollPane = new JScrollPane(pathInput);
        inputScrollPane.setBorder(BorderFactory.createEmptyBorder());

        JPanel accessory = new JPanel(new BorderLayout(0, 8));
        accessory.setName("editablePathChooser.accessory");
        accessory.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(i18n("swing.path_chooser.path")),
                BorderFactory.createEmptyBorder(4, 8, 8, 8)));
        accessory.setPreferredSize(new Dimension(ACCESSORY_WIDTH, ACCESSORY_HEIGHT));
        accessory.add(inputHint, BorderLayout.NORTH);
        accessory.add(inputScrollPane, BorderLayout.CENTER);
        JPanel feedback = new JPanel(new BorderLayout(8, 0));
        feedback.add(validationMessage, BorderLayout.CENTER);
        feedback.add(applyPathButton, BorderLayout.EAST);
        accessory.add(feedback, BorderLayout.SOUTH);
        setAccessory(accessory);

        addPropertyChangeListener(SELECTED_FILE_CHANGED_PROPERTY, event -> synchronizeFromBrowser());
        addPropertyChangeListener(SELECTED_FILES_CHANGED_PROPERTY, event -> synchronizeFromBrowser());
        addPropertyChangeListener(MULTI_SELECTION_ENABLED_CHANGED_PROPERTY, event -> updateInputPresentation());
        addPropertyChangeListener(DIALOG_TYPE_CHANGED_PROPERTY, event -> updateInputPresentation());
        updateInputPresentation();
        synchronizeFromBrowser();
    }

    /// Configures one label-like text area to wrap without accepting focus or edits.
    ///
    /// @param textArea label-like text area
    private static void configureReadOnlyText(JTextArea textArea) {
        JTextArea target = Objects.requireNonNull(textArea, "textArea");
        target.setEditable(false);
        target.setFocusable(false);
        target.setOpaque(false);
        target.setLineWrap(true);
        target.setWrapStyleWord(true);
        target.setBorder(null);
        target.setFont(UIManager.getFont("Label.font"));
        target.setForeground(UIManager.getColor("Label.foreground"));
    }

    /// Updates path guidance and the visible row count for the current selection cardinality.
    private void updateInputPresentation() {
        boolean multiple = isMultiSelectionEnabled() && getDialogType() != SAVE_DIALOG;
        pathInput.setRows(multiple ? 5 : 3);
        inputHint.setText(i18n(multiple
                ? "swing.path_chooser.hint.multiple"
                : "swing.path_chooser.hint.single"));
        pathInput.getAccessibleContext().setAccessibleDescription(inputHint.getText());
    }

    /// Copies a native browser selection into the editable input without touching the file system.
    private void synchronizeFromBrowser() {
        if (synchronizingSelection || pathInput.hasFocus()) {
            return;
        }
        File @Unmodifiable [] selectedFiles = getSelectedFiles();
        if (isMultiSelectionEnabled() && getDialogType() != SAVE_DIALOG && selectedFiles.length > 0) {
            setPathInputText(List.of(selectedFiles).stream()
                    .map(File::toPath)
                    .map(Path::toString)
                    .toList());
            return;
        }
        @Nullable File selectedFile = getSelectedFile();
        if (selectedFile != null) {
            setPathInputText(List.of(selectedFile.toPath().toString()));
        }
    }

    /// Writes normalized browser paths to the input while suppressing edit-side validation noise.
    ///
    /// @param paths browser-selected path strings
    private void setPathInputText(@Unmodifiable List<String> paths) {
        synchronizingSelection = true;
        try {
            pathInput.setText(String.join(System.lineSeparator(), List.copyOf(paths)));
        } finally {
            synchronizingSelection = false;
        }
        validationMessage.setText(" ");
        validationMessage.setToolTipText(null);
    }

    /// Resolves, normalizes, and validates the current editable input.
    ///
    /// @return immutable approved paths or one localized validation error
    private SelectionResolution resolveSelection() {
        @Unmodifiable List<String> entries = pathInput.getText().lines()
                .map(String::strip)
                .map(EditablePathChooser::removeMatchingQuotes)
                .filter(entry -> !entry.isEmpty())
                .toList();
        if (entries.isEmpty()) {
            return SelectionResolution.failure(i18n("swing.path_chooser.error.required"));
        }
        if ((!isMultiSelectionEnabled() || getDialogType() == SAVE_DIALOG) && entries.size() != 1) {
            return SelectionResolution.failure(i18n("swing.path_chooser.error.single"));
        }

        List<Path> paths = new ArrayList<>(entries.size());
        for (String entry : entries) {
            final Path normalized;
            try {
                normalized = resolveAgainstCurrentDirectory(Path.of(entry));
            } catch (InvalidPathException | SecurityException failure) {
                return SelectionResolution.failure(i18n("swing.path_chooser.error.invalid", entry));
            }
            SelectionResolution validation = validatePath(normalizeSaveExtension(normalized));
            @Nullable String error = validation.error();
            if (error != null) {
                return validation;
            }
            paths.add(validation.paths().get(0));
        }
        return SelectionResolution.success(paths);
    }

    /// Resolves a relative input against the visible browser directory and returns a normalized absolute path.
    ///
    /// @param input parsed typed input
    /// @return normalized absolute path
    private Path resolveAgainstCurrentDirectory(Path input) {
        Path path = Objects.requireNonNull(input, "input");
        if (path.isAbsolute()) {
            return path.normalize();
        }
        @Nullable File currentDirectory = getCurrentDirectory();
        Path base = currentDirectory == null
                ? Path.of("").toAbsolutePath()
                : currentDirectory.toPath().toAbsolutePath();
        return base.resolve(path).normalize();
    }

    /// Appends the active filter's first extension to an extensionless save filename.
    ///
    /// @param input normalized candidate
    /// @return candidate with a default extension when the active save filter requires one
    private Path normalizeSaveExtension(Path input) {
        if (getDialogType() != SAVE_DIALOG || getFileSelectionMode() == DIRECTORIES_ONLY) {
            return input;
        }
        @Nullable FileFilter filter = getFileFilter();
        if (!(filter instanceof FileNameExtensionFilter extensionFilter)
                || acceptsExtension(input, extensionFilter)) {
            return input;
        }
        @Nullable Path fileName = input.getFileName();
        if (fileName == null || hasFileNameExtension(fileName.toString())) {
            return input;
        }
        String @Unmodifiable [] extensions = extensionFilter.getExtensions();
        if (extensions.length == 0) {
            return input;
        }
        String separator = fileName.toString().endsWith(".") ? "" : ".";
        return input.resolveSibling(fileName + separator + extensions[0]);
    }

    /// Validates one resolved path against open/save semantics, selection type, and active filter.
    ///
    /// @param candidate normalized absolute candidate
    /// @return one-path success or localized validation failure
    private SelectionResolution validatePath(Path candidate) {
        try {
            if (getDialogType() == SAVE_DIALOG) {
                return validateSavePath(candidate);
            }
            if (!Files.exists(candidate)) {
                return SelectionResolution.failure(i18n("swing.path_chooser.error.missing", candidate));
            }
            @Nullable String typeError = validateExistingType(candidate);
            if (typeError != null) {
                return SelectionResolution.failure(typeError);
            }
            if (Files.isRegularFile(candidate) && !activeFilterAccepts(candidate)) {
                return SelectionResolution.failure(i18n("swing.path_chooser.error.filter", candidate));
            }
            return SelectionResolution.success(List.of(candidate));
        } catch (SecurityException failure) {
            return SelectionResolution.failure(i18n("swing.path_chooser.error.invalid", candidate));
        }
    }

    /// Validates an existing or new save target and its writable containing directory.
    ///
    /// @param candidate normalized save candidate
    /// @return one-path success or localized validation failure
    private SelectionResolution validateSavePath(Path candidate) {
        if (Files.exists(candidate)) {
            @Nullable String typeError = validateExistingType(candidate);
            if (typeError != null) {
                return SelectionResolution.failure(typeError);
            }
        }
        if (getFileSelectionMode() != DIRECTORIES_ONLY && !activeFilterAccepts(candidate)) {
            return SelectionResolution.failure(i18n("swing.path_chooser.error.filter", candidate));
        }
        @Nullable Path parent = candidate.getParent();
        if (parent == null || !Files.isDirectory(parent) || !Files.isWritable(parent)) {
            return SelectionResolution.failure(i18n("swing.path_chooser.error.save_parent", candidate));
        }
        return SelectionResolution.success(List.of(candidate));
    }

    /// Checks an existing candidate against the configured file-selection mode.
    ///
    /// @param candidate existing normalized candidate
    /// @return localized mismatch text, or null when the type is accepted
    private @Nullable String validateExistingType(Path candidate) {
        return switch (getFileSelectionMode()) {
            case FILES_ONLY -> Files.isRegularFile(candidate)
                    ? null
                    : i18n("swing.path_chooser.error.expected_file", candidate);
            case DIRECTORIES_ONLY -> Files.isDirectory(candidate)
                    ? null
                    : i18n("swing.path_chooser.error.expected_directory", candidate);
            case FILES_AND_DIRECTORIES -> Files.isRegularFile(candidate) || Files.isDirectory(candidate)
                    ? null
                    : i18n("swing.path_chooser.error.unsupported_type", candidate);
            default -> i18n("swing.path_chooser.error.unsupported_type", candidate);
        };
    }

    /// Applies the active filter, including compound extensions such as `tar.gz`.
    ///
    /// @param candidate normalized candidate
    /// @return whether the active filter accepts the candidate
    private boolean activeFilterAccepts(Path candidate) {
        @Nullable FileFilter filter = getFileFilter();
        if (filter == null) {
            return true;
        }
        if (filter instanceof FileNameExtensionFilter extensionFilter) {
            return acceptsExtension(candidate, extensionFilter);
        }
        return filter.accept(candidate.toFile());
    }

    /// Checks a filename against every case-insensitive suffix in one extension filter.
    ///
    /// @param candidate normalized candidate
    /// @param filter active extension filter
    /// @return whether one configured extension matches
    private static boolean acceptsExtension(Path candidate, FileNameExtensionFilter filter) {
        @Nullable Path fileName = candidate.getFileName();
        if (fileName == null) {
            return false;
        }
        String lowerName = fileName.toString().toLowerCase(Locale.ROOT);
        for (String extension : filter.getExtensions()) {
            if (lowerName.endsWith("." + extension.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /// Reports whether the final filename segment already contains a non-leading extension separator.
    ///
    /// @param fileName final filename segment
    /// @return whether an explicit extension is present
    private static boolean hasFileNameExtension(String fileName) {
        int separator = Objects.requireNonNull(fileName, "fileName").lastIndexOf('.');
        return separator > 0 && separator < fileName.length() - 1;
    }

    /// Removes matching single or double quotes commonly produced by platform copy-as-path actions.
    ///
    /// @param input stripped input line
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

    /// Returns the editable path component for package-local focused tests.
    ///
    /// @return editable path component
    JTextComponent pathInput() {
        return pathInput;
    }

    /// Returns the typed-path approval button for package-local focused tests.
    ///
    /// @return typed-path approval button
    JButton applyPathButton() {
        return applyPathButton;
    }

    /// Returns the current inline validation text for package-local focused tests.
    ///
    /// @return validation text, blank when no error is shown
    String validationText() {
        return validationMessage.getText().strip();
    }

    /// Immutable typed-input resolution with either approved paths or one error.
    ///
    /// @param paths immutable approved paths, empty after failure
    /// @param error localized validation error, or null after success
    @NotNullByDefault
    private record SelectionResolution(
            @Unmodifiable List<Path> paths,
            @Nullable String error) {
        /// Copies the successful paths and verifies the success invariant.
        private SelectionResolution {
            paths = List.copyOf(paths);
            if ((error == null) == paths.isEmpty()) {
                throw new IllegalArgumentException("Exactly one of paths or error must be present");
            }
        }

        /// Creates an immutable successful resolution.
        ///
        /// @param paths one or more validated paths
        /// @return successful resolution
        private static SelectionResolution success(List<Path> paths) {
            return new SelectionResolution(paths, null);
        }

        /// Creates a failed resolution without approved paths.
        ///
        /// @param error localized failure detail
        /// @return failed resolution
        private static SelectionResolution failure(String error) {
            return new SelectionResolution(List.of(), Objects.requireNonNull(error, "error"));
        }
    }

    /// Clears stale validation whenever the user edits the path text.
    @NotNullByDefault
    private static final class PathInputDocumentListener implements javax.swing.event.DocumentListener {
        /// Validation label cleared after every document mutation.
        private final JTextArea validationMessage;

        /// Creates a listener for one chooser validation label.
        ///
        /// @param validationMessage validation label to clear
        private PathInputDocumentListener(JTextArea validationMessage) {
            this.validationMessage = Objects.requireNonNull(validationMessage, "validationMessage");
        }

        /// Clears feedback after inserted text.
        ///
        /// @param event document mutation event
        @Override
        public void insertUpdate(javax.swing.event.DocumentEvent event) {
            Objects.requireNonNull(event, "event");
            clearValidation();
        }

        /// Clears feedback after removed text.
        ///
        /// @param event document mutation event
        @Override
        public void removeUpdate(javax.swing.event.DocumentEvent event) {
            Objects.requireNonNull(event, "event");
            clearValidation();
        }

        /// Clears feedback after an attribute-only mutation.
        ///
        /// @param event document mutation event
        @Override
        public void changedUpdate(javax.swing.event.DocumentEvent event) {
            Objects.requireNonNull(event, "event");
            clearValidation();
        }

        /// Restores the validation row's stable blank height.
        private void clearValidation() {
            validationMessage.setText(" ");
            validationMessage.setToolTipText(null);
        }
    }
}
