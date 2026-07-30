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
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Insets;
import java.io.File;
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

        directoryBar.setName("editablePathChooser.directoryBar");
        directoryBar.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        directoryBar.add(currentDirectoryInput, BorderLayout.CENTER);
        directoryBar.add(navigateDirectoryButton, BorderLayout.EAST);

        addPropertyChangeListener(DIRECTORY_CHANGED_PROPERTY, event -> synchronizeDirectoryInput());
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
        Path base = currentDirectory == null
                ? Path.of("").toAbsolutePath()
                : currentDirectory.toPath().toAbsolutePath();
        return base.resolve(path).normalize();
    }

    /// Mirrors the chooser's displayed directory into the top field.
    private void synchronizeDirectoryInput() {
        @Nullable File currentDirectory = getCurrentDirectory();
        currentDirectoryInput.setText(currentDirectory == null
                ? ""
                : currentDirectory.toPath().toAbsolutePath().normalize().toString());
        clearDirectoryValidation();
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
