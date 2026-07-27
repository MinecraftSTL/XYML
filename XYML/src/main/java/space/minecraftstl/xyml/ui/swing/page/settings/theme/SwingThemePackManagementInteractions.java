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
package space.minecraftstl.xyml.ui.swing.page.settings.theme;

import net.miginfocom.swing.MigLayout;
import space.minecraftstl.xyml.ui.swing.dialog.EditablePathChooser;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.theme.ThemePackExporter;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingTextFields;

import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Component;
import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Production Swing interactions for local theme-pack management.
@NotNullByDefault
public final class SwingThemePackManagementInteractions implements ThemePackManagementInteractions {
    /// Localized chooser and confirmation text.
    private final ThemePackManagementStrings strings;

    /// Caller-owned worker used for potentially blocking desktop integration.
    private final Executor executor;

    /// Creates production interactions.
    ///
    /// @param strings localized interaction text
    /// @param executor caller-owned non-EDT worker executor
    public SwingThemePackManagementInteractions(ThemePackManagementStrings strings, Executor executor) {
        this.strings = Objects.requireNonNull(strings, "strings");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /// Shows a native file chooser constrained to `.xyml-theme` archives.
    ///
    /// @param owner dialog owner
    /// @return selected archive, or `null`
    @Override
    public @Nullable Path chooseImportArchive(Component owner) {
        EdtDispatcher.requireEventDispatchThread();
        JFileChooser chooser = new EditablePathChooser();
        chooser.setDialogTitle(strings.chooserTitle());
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter(strings.chooserFilter(), "xyml-theme"));
        return chooser.showOpenDialog(Objects.requireNonNull(owner, "owner")) == JFileChooser.APPROVE_OPTION
                ? chooser.getSelectedFile().toPath().toAbsolutePath().normalize()
                : null;
    }

    /// Collects name, version, and author metadata before opening the editable-path save chooser.
    ///
    /// @param owner dialog owner
    /// @param defaults generated export defaults
    /// @return complete export request, or `null` when either dialog is cancelled
    @Override
    public @Nullable ThemePackExportRequest chooseThemePackExport(
            Component owner,
            ThemePackExportDefaults defaults) {
        EdtDispatcher.requireEventDispatchThread();
        Component checkedOwner = Objects.requireNonNull(owner, "owner");
        ThemePackExportDefaults checkedDefaults = Objects.requireNonNull(defaults, "defaults");
        @Nullable ExportMetadata metadata = promptExportMetadata(checkedOwner, checkedDefaults);
        if (metadata == null) {
            return null;
        }

        JFileChooser chooser = new EditablePathChooser();
        chooser.setDialogTitle(i18n("theme_pack.export.title"));
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter(i18n("theme_pack.file"), "xyml-theme"));
        chooser.setSelectedFile(new File(
                sanitizeThemePackFileName(metadata.name()) + ThemePackExporter.FILE_EXTENSION));
        if (chooser.showSaveDialog(checkedOwner) != JFileChooser.APPROVE_OPTION) {
            return null;
        }

        Path output = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        String fileName = output.getFileName().toString();
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(ThemePackExporter.FILE_EXTENSION)) {
            output = output.resolveSibling(fileName + ThemePackExporter.FILE_EXTENSION);
        }
        return new ThemePackExportRequest(
                checkedDefaults.packId(),
                metadata.name(),
                metadata.version(),
                metadata.author(),
                output);
    }

    /// Shows a localized success dialog containing the exact published output path.
    ///
    /// @param owner dialog owner
    /// @param outputFile published archive
    @Override
    public void showThemePackExportSuccess(Component owner, Path outputFile) {
        EdtDispatcher.requireEventDispatchThread();
        JOptionPane.showMessageDialog(
                Objects.requireNonNull(owner, "owner"),
                i18n("theme_pack.export.success", Objects.requireNonNull(outputFile, "outputFile")),
                i18n("message.success"),
                JOptionPane.INFORMATION_MESSAGE);
    }

    /// Shows a localized export failure dialog with the most specific available diagnostic detail.
    ///
    /// @param owner dialog owner
    /// @param failure export failure
    @Override
    public void showThemePackExportFailure(Component owner, Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        Throwable cause = rootCause(Objects.requireNonNull(failure, "failure"));
        @Nullable String message = cause.getMessage();
        String detail = message == null || message.isBlank()
                ? cause.getClass().getSimpleName()
                : message;
        JOptionPane.showMessageDialog(
                Objects.requireNonNull(owner, "owner"),
                i18n("theme_pack.export.failed") + "\n\n" + detail,
                i18n("message.error"),
                JOptionPane.ERROR_MESSAGE);
    }

    /// Shows one destructive confirmation for the complete containing package.
    ///
    /// @param owner dialog owner
    /// @param item selected installed item
    /// @return whether deletion was confirmed
    @Override
    public boolean confirmDelete(Component owner, ThemePackItem item) {
        EdtDispatcher.requireEventDispatchThread();
        ThemePackItem checked = Objects.requireNonNull(item, "item");
        return JOptionPane.showConfirmDialog(
                Objects.requireNonNull(owner, "owner"),
                strings.confirmDeleteFormat().formatted(checked.packageName()),
                strings.confirmDeleteTitle(),
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
    }

    /// Opens one revalidated directory on the caller-owned worker.
    ///
    /// @param directory exact validated directory
    /// @return completion stage resolved after the native desktop call
    @Override
    public CompletionStage<@Nullable Void> revealInstalledDirectory(Path directory) {
        Path target = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        CompletableFuture<@Nullable Void> completion = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    if (javax.swing.SwingUtilities.isEventDispatchThread()) {
                        throw new IllegalStateException("Desktop integration must not run on the EDT");
                    }
                    if (!Desktop.isDesktopSupported()) {
                        throw new UnsupportedOperationException("Desktop integration is unavailable");
                    }
                    Desktop desktop = Desktop.getDesktop();
                    if (!desktop.isSupported(Desktop.Action.OPEN)) {
                        throw new UnsupportedOperationException("Desktop cannot open directories");
                    }
                    desktop.open(target.toFile());
                    completion.complete(null);
                } catch (IOException | RuntimeException failure) {
                    completion.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException failure) {
            completion.completeExceptionally(failure);
        }
        return completion.minimalCompletionStage();
    }

    /// Opens the localized metadata form and applies defaults to any field the user leaves blank.
    ///
    /// @param owner dialog owner
    /// @param defaults generated metadata defaults
    /// @return confirmed metadata or `null`
    private static @Nullable ExportMetadata promptExportMetadata(
            Component owner,
            ThemePackExportDefaults defaults) {
        JTextField nameField = metadataField("themePackExportName", defaults.name());
        JTextField versionField = metadataField("themePackExportVersion", defaults.version());
        JTextField authorField = metadataField("themePackExportAuthor", defaults.author());

        JPanel form = new JPanel(new MigLayout(
                "insets 0, fillx, wrap 2",
                "[][grow,fill]",
                "[]12[]8[]8[]"));
        form.setOpaque(false);
        JLabel subtitle = new JLabel(i18n("theme_pack.export.subtitle"));
        subtitle.setName("themePackExportSubtitle");
        form.add(subtitle, "span 2, growx");
        form.add(new JLabel(i18n("theme_pack.export.name")));
        form.add(nameField, "growx");
        form.add(new JLabel(i18n("theme_pack.export.version")));
        form.add(versionField, "growx");
        form.add(new JLabel(i18n("theme_pack.export.author")));
        form.add(authorField, "growx");

        int result = JOptionPane.showConfirmDialog(
                Objects.requireNonNull(owner, "owner"),
                form,
                i18n("theme_pack.export.title"),
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return null;
        }
        return new ExportMetadata(
                valueOrDefault(nameField, defaults.name()),
                valueOrDefault(versionField, defaults.version()),
                valueOrDefault(authorField, defaults.author()));
    }

    /// Creates one prefilled metadata field with the standard clear affordance.
    ///
    /// @param name stable component name
    /// @param value initial value
    /// @return configured text field
    private static JTextField metadataField(String name, String value) {
        JTextField field = new JTextField(28);
        field.setName(Objects.requireNonNull(name, "name"));
        field.setText(Objects.requireNonNull(value, "value"));
        SwingTextFields.showClearButton(field);
        return field;
    }

    /// Returns trimmed field text or its generated default when blank.
    ///
    /// @param field metadata input
    /// @param defaultValue generated fallback
    /// @return non-empty value
    private static String valueOrDefault(JTextField field, String defaultValue) {
        String value = Objects.requireNonNull(field, "field").getText().trim();
        return value.isEmpty() ? Objects.requireNonNull(defaultValue, "defaultValue") : value;
    }

    /// Sanitizes a package display name for a portable suggested file name.
    ///
    /// @param value package display name
    /// @return non-empty portable file-name stem
    static String sanitizeThemePackFileName(String value) {
        String checked = Objects.requireNonNull(value, "value");
        StringBuilder builder = new StringBuilder(checked.length());
        checked.trim().codePoints().forEach(codePoint -> {
            if (isUnsafeThemePackFileNameCodePoint(codePoint)) {
                builder.append('_');
            } else {
                builder.appendCodePoint(codePoint);
            }
        });
        String sanitized = builder.toString().replaceAll("[.\\s]+$", "");
        return sanitized.isBlank() ? "theme-pack" : sanitized;
    }

    /// Tests whether a code point is unsafe in a cross-platform file name.
    ///
    /// @param codePoint candidate Unicode code point
    /// @return whether it must be replaced
    private static boolean isUnsafeThemePackFileNameCodePoint(int codePoint) {
        return !Character.isValidCodePoint(codePoint)
                || Character.isISOControl(codePoint)
                || codePoint == '/'
                || codePoint == '\\'
                || codePoint == ':'
                || codePoint == '<'
                || codePoint == '>'
                || codePoint == '"'
                || codePoint == '|'
                || codePoint == '?'
                || codePoint == '*'
                || codePoint == 0xfffd
                || codePoint == 0xfffe
                || codePoint == 0xffff;
    }

    /// Removes asynchronous wrapper exceptions before presenting a diagnostic.
    ///
    /// @param failure completion failure
    /// @return most specific available cause
    private static Throwable rootCause(Throwable failure) {
        Throwable current = Objects.requireNonNull(failure, "failure");
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /// Trimmed metadata confirmed by the first export dialog.
    ///
    /// @param name package display name
    /// @param version package version
    /// @param author package author
    @NotNullByDefault
    private record ExportMetadata(String name, String version, String author) {
        /// Rejects incomplete confirmed metadata.
        private ExportMetadata {
            name = requireNonBlank(name, "name");
            version = requireNonBlank(version, "version");
            author = requireNonBlank(author, "author");
        }

        /// Trims one required metadata value.
        ///
        /// @param value candidate value
        /// @param field diagnostic field name
        /// @return non-empty value
        private static String requireNonBlank(String value, String field) {
            String checked = Objects.requireNonNull(value, field).trim();
            if (checked.isEmpty()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
            return checked;
        }
    }
}
