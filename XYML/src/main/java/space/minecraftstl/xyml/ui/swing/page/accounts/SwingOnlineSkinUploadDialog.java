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
package space.minecraftstl.xyml.ui.swing.page.accounts;

import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.auth.yggdrasil.TextureModel;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.util.skin.InvalidSkinException;
import space.minecraftstl.xyml.util.skin.NormalizedSkin;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Validates, previews, and explicitly uploads one online account skin using native Swing controls.
///
/// Construction and opening are network-free. PNG decoding starts only after file selection, and the
/// provider command starts only after the user presses Upload. Both operations run outside the EDT.
@NotNullByDefault
final class SwingOnlineSkinUploadDialog extends JDialog implements AutoCloseable {
    /// Caller-owned account command that performs reauthentication and provider upload.
    private final AccountSkinUploadCommand uploadCommand;

    /// Worker used for local image validation and decoding.
    private final Executor worker;

    /// Selected account display name.
    private final JLabel profileName = new JLabel();

    /// Read-only normalized local image path.
    private final JTextField selectedFileField = new JTextField();

    /// Detected wide or slim model.
    private final JLabel detectedModel = new JLabel(" ");

    /// Pixel-accurate local skin preview.
    private final OfflineSkinPreviewPanel preview = new OfflineSkinPreviewPanel();

    /// Inline validation and operation state.
    private final JLabel status = new JLabel(" ");

    /// Opens the native PNG chooser.
    private final JButton chooseButton = new JButton(i18n("account.skin.choose"));

    /// Starts the explicit provider operation.
    private final JButton uploadButton = new JButton(i18n("account.skin.upload"));

    /// Closes without starting another operation.
    private final JButton cancelButton = new JButton(i18n("button.cancel"));

    /// Latest fully validated selection, or null before validation succeeds.
    private @Nullable SkinSelection selection;

    /// Latest local validation worker, or null when idle.
    private @Nullable CompletableFuture<SkinSelection> activeValidation;

    /// Monotonic identity used to discard stale validation completions.
    private long validationRevision;

    /// Whether one upload command is active.
    private boolean uploading;

    /// Whether this dialog has released callbacks and native resources.
    private boolean closed;

    /// Creates a modal upload dialog using the shared I/O worker.
    ///
    /// @param owner component owning the dialog, or null for an unowned dialog
    /// @param accountName selected online account display name
    /// @param uploadCommand explicit provider upload command
    SwingOnlineSkinUploadDialog(
            @Nullable Component owner,
            String accountName,
            AccountSkinUploadCommand uploadCommand) {
        this(owner, accountName, uploadCommand, Schedulers.io());
    }

    /// Creates a modal upload dialog with an injected local-image worker for focused tests.
    ///
    /// @param owner component owning the dialog, or null for an unowned dialog
    /// @param accountName selected online account display name
    /// @param uploadCommand explicit provider upload command
    /// @param worker caller-owned validation worker
    SwingOnlineSkinUploadDialog(
            @Nullable Component owner,
            String accountName,
            AccountSkinUploadCommand uploadCommand,
            Executor worker) {
        super(ownerWindow(owner), i18n("account.skin.upload"), ModalityType.APPLICATION_MODAL);
        EdtDispatcher.requireEventDispatchThread();
        this.uploadCommand = Objects.requireNonNull(uploadCommand, "uploadCommand");
        this.worker = Objects.requireNonNull(worker, "worker");
        profileName.setText(Objects.requireNonNull(accountName, "accountName"));
        configureComponents();
    }

    /// Opens the stable modal upload surface without triggering validation or network access.
    void open() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            throw new IllegalStateException("Online skin upload dialog is closed");
        }
        pack();
        setMinimumSize(new Dimension(820, 500));
        setLocationRelativeTo(getOwner());
        setVisible(true);
    }

    /// Cancels local validation and releases the native dialog when no provider request is active.
    @Override
    public void close() {
        if (closed || uploading) {
            return;
        }
        closed = true;
        ++validationRevision;
        @Nullable CompletableFuture<SkinSelection> validation = activeValidation;
        activeValidation = null;
        if (validation != null) {
            validation.cancel(true);
        }
        EdtDispatcher.execute(this::dispose);
    }

    /// Builds profile metadata, path selection, model result, preview, status, and commands.
    private void configureComponents() {
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            /// Closes from the window manager only while no upload is active.
            ///
            /// @param event close request
            @Override
            public void windowClosing(WindowEvent event) {
                close();
            }
        });

        JPanel root = new JPanel(new MigLayout(
                "insets 18, fill, wrap 3",
                "[][grow,fill][]18[340!,fill]",
                "[]12[]12[]12[grow,fill]12[]"));
        profileName.setName("onlineSkinProfile");
        selectedFileField.setName("onlineSkinFile");
        selectedFileField.setEditable(false);
        detectedModel.setName("onlineSkinModel");
        status.setName("onlineSkinStatus");
        chooseButton.setName("onlineSkinChoose");
        uploadButton.setName("onlineSkinUpload");
        cancelButton.setName("onlineSkinCancel");

        root.add(new JLabel(i18n("account.skin.profile")));
        root.add(profileName, "span 2, growx");
        root.add(preview, "cell 3 0, span 1 4, grow");
        root.add(new JLabel(i18n("account.skin.file")));
        root.add(selectedFileField, "growx");
        root.add(chooseButton, "h 36!");
        root.add(new JLabel(i18n("account.skin.model")));
        root.add(detectedModel, "span 2, growx");
        root.add(status, "cell 0 3, span 3, grow");

        JPanel commands = new JPanel(new MigLayout("insets 0, fillx", "[grow][][]", "[]"));
        commands.setOpaque(false);
        commands.add(new JLabel(), "growx, pushx");
        commands.add(cancelButton, "h 38!");
        commands.add(uploadButton, "h 38!");
        root.add(commands, "cell 0 4, span 4, growx");
        setContentPane(root);

        chooseButton.addActionListener(event -> chooseSkinFile());
        uploadButton.addActionListener(event -> uploadSelection());
        cancelButton.addActionListener(event -> close());
        getRootPane().setDefaultButton(uploadButton);
        preview.showMessage(i18n("account.skin.upload.select_first"));
        updateControls();
    }

    /// Opens a native chooser and submits the selected PNG for local validation.
    private void chooseSkinFile() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || uploading) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(i18n("account.skin.upload"));
        chooser.setFileFilter(new FileNameExtensionFilter(i18n("account.skin.png_filter"), "png"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            validateSelection(chooser.getSelectedFile().toPath());
        }
    }

    /// Validates and decodes one explicitly selected local file without blocking the EDT.
    ///
    /// @param skinFile selected local path
    void validateSelection(Path skinFile) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || uploading) {
            return;
        }
        Path requested = Objects.requireNonNull(skinFile, "skinFile");
        long revision = ++validationRevision;
        @Nullable CompletableFuture<SkinSelection> previous = activeValidation;
        if (previous != null) {
            previous.cancel(true);
        }
        selection = null;
        selectedFileField.setText(requested.toAbsolutePath().normalize().toString());
        detectedModel.setText(" ");
        status.setText(i18n("account.skin.upload.validating"));
        preview.showMessage(i18n("account.skin.preview.loading"));
        updateControls();

        CompletableFuture<SkinSelection> operation = CompletableFuture.supplyAsync(
                () -> readSelection(requested), worker);
        activeValidation = operation;
        operation.whenComplete((result, failure) -> EdtDispatcher.execute(
                () -> validationCompleted(revision, result, failure)));
    }

    /// Publishes only the latest local validation result on the EDT.
    ///
    /// @param revision submitted validation identity
    /// @param result decoded selection, or null after failure
    /// @param failure validation failure, or null after success
    private void validationCompleted(
            long revision,
            @Nullable SkinSelection result,
            @Nullable Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || revision != validationRevision) {
            return;
        }
        activeValidation = null;
        @Nullable Throwable resolvedFailure = unwrapFailure(failure);
        if (resolvedFailure != null || result == null) {
            selection = null;
            detectedModel.setText(" ");
            status.setText(i18n("account.skin.invalid_skin"));
            preview.showMessage(failureMessage(resolvedFailure));
        } else {
            selection = result;
            selectedFileField.setText(result.file().toString());
            detectedModel.setText(i18n(result.slim()
                    ? "account.skin.model.slim"
                    : "account.skin.model.default"));
            status.setText(i18n("account.skin.upload.ready"));
            preview.showPreview(result.preview());
        }
        updateControls();
    }

    /// Starts the account-owned provider command only after validation and an explicit button press.
    private void uploadSelection() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable SkinSelection current = selection;
        if (closed || uploading || current == null) {
            return;
        }
        uploading = true;
        status.setText(i18n("account.skin.upload.uploading"));
        updateControls();

        final CompletionStage<Void> operation;
        try {
            operation = Objects.requireNonNull(
                    uploadCommand.upload(current.file(), current.slim()),
                    "uploadCommand returned null");
        } catch (Throwable failure) {
            uploadCompleted(failure);
            return;
        }
        operation.whenComplete((ignored, failure) -> EdtDispatcher.execute(() -> uploadCompleted(failure)));
    }

    /// Restores controls after failure or closes after a successful provider upload.
    ///
    /// @param failure provider failure, or null after success
    private void uploadCompleted(@Nullable Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            return;
        }
        uploading = false;
        @Nullable Throwable resolved = unwrapFailure(failure);
        if (resolved == null) {
            status.setText(i18n("account.skin.upload.success"));
            closed = true;
            dispose();
            return;
        }
        status.setText(i18n("account.skin.upload.failed") + ": " + failureMessage(resolved));
        updateControls();
    }

    /// Enables commands from exact validation and upload state.
    private void updateControls() {
        boolean validating = activeValidation != null;
        chooseButton.setEnabled(!closed && !uploading && !validating);
        uploadButton.setEnabled(!closed && !uploading && !validating && selection != null);
        cancelButton.setEnabled(!closed && !uploading);
    }

    /// Validates, decodes, normalizes, and detects the model of one selected PNG.
    ///
    /// @param skinFile selected local image
    /// @return immutable validated selection
    private static SkinSelection readSelection(Path skinFile) {
        try {
            Path normalized = OfflineSkinService.validatePng(skinFile);
            @Nullable BufferedImage image = ImageIO.read(normalized.toFile());
            if (image == null) {
                throw new IOException("Selected PNG image is not decodable: " + normalized);
            }
            NormalizedSkin normalizedSkin = new NormalizedSkin(image);
            boolean slim = normalizedSkin.isSlim();
            TextureModel model = slim ? TextureModel.SLIM : TextureModel.WIDE;
            return new SkinSelection(
                    normalized,
                    slim,
                    new OfflineSkinPreview(model, normalizedSkin.getNormalizedTexture(), null));
        } catch (IOException | InvalidSkinException | RuntimeException failure) {
            throw new CompletionException(failure);
        }
    }

    /// Converts one optional failure into concise user-visible detail.
    ///
    /// @param failure failure, or null when no detail is available
    /// @return localized or diagnostic detail
    private static String failureMessage(@Nullable Throwable failure) {
        if (failure == null) {
            return i18n("account.skin.invalid_skin");
        }
        @Nullable String localized = failure.getLocalizedMessage();
        return localized == null || localized.isBlank()
                ? failure.getClass().getSimpleName()
                : localized;
    }

    /// Removes common asynchronous wrappers from one optional failure.
    ///
    /// @param failure asynchronous failure, or null after success
    /// @return meaningful cause, or null after success
    private static @Nullable Throwable unwrapFailure(@Nullable Throwable failure) {
        @Nullable Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /// Resolves a Swing component to its nearest native owner window.
    ///
    /// @param owner dialog owner component, or null
    /// @return owning window, or null for an unowned dialog
    private static @Nullable Window ownerWindow(@Nullable Component owner) {
        if (owner == null) {
            return null;
        }
        return owner instanceof Window window ? window : SwingUtilities.getWindowAncestor(owner);
    }

    /// One validated PNG and its decoded presentation state.
    ///
    /// @param file normalized local PNG path
    /// @param slim whether the texture uses slim arms
    /// @param preview normalized preview payload
    @NotNullByDefault
    private record SkinSelection(Path file, boolean slim, OfflineSkinPreview preview) {
        /// Validates one immutable selection.
        private SkinSelection {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(preview, "preview");
        }
    }
}
