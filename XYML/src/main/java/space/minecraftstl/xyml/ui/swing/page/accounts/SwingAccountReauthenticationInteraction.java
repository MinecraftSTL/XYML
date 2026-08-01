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
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.log.SwingLogFontPreferences;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Native Swing prompts and OAuth progress for credential-expiry recovery.
///
/// Blocking methods marshal worker calls to the EDT. The owner supplier is evaluated only on the EDT,
/// allowing the surrounding window to change safely across application lifecycle transitions.
@NotNullByDefault
final class SwingAccountReauthenticationInteraction implements AccountReauthenticationInteraction {
    /// Supplies the current dialog owner on the EDT.
    private final Supplier<@Nullable Component> ownerSupplier;

    /// Cancels the active completion from a native cancel button or owner-window closure.
    private final Runnable cancellationCommand;

    /// Current modal prompt, or null while none is visible.
    private final AtomicReference<@Nullable JDialog> pendingPrompt = new AtomicReference<>();

    /// Modeless OAuth progress dialog, or null outside OAuth authentication.
    private final AtomicReference<@Nullable JDialog> oauthDialog = new AtomicReference<>();

    /// Status label owned by the current OAuth dialog.
    private final AtomicReference<@Nullable JLabel> oauthStatus = new AtomicReference<>();

    /// Copyable device-code action owned by the current OAuth dialog.
    private final AtomicReference<@Nullable MicrosoftDeviceCodeButton> oauthDeviceCode = new AtomicReference<>();

    /// Button reopening the latest OAuth location.
    private final AtomicReference<@Nullable JButton> oauthOpenButton = new AtomicReference<>();

    /// Button copying the latest OAuth device code or browser location.
    private final AtomicReference<@Nullable JButton> oauthCopyButton = new AtomicReference<>();

    /// Latest trusted OAuth location.
    private final AtomicReference<@Nullable String> oauthLocation = new AtomicReference<>();

    /// Latest device code, or null for browser authorization.
    private final AtomicReference<@Nullable String> oauthCode = new AtomicReference<>();

    /// Creates native prompts with a lifecycle-aware owner and cancellation command.
    ///
    /// @param ownerSupplier current owner supplier evaluated on the EDT
    /// @param cancellationCommand active-operation cancellation command
    SwingAccountReauthenticationInteraction(
            Supplier<@Nullable Component> ownerSupplier,
            Runnable cancellationCommand) {
        this.ownerSupplier = Objects.requireNonNull(ownerSupplier, "ownerSupplier");
        this.cancellationCommand = Objects.requireNonNull(cancellationCommand, "cancellationCommand");
    }

    /// Confirms backup-and-overwrite with the same localized warning as settings recovery.
    @Override
    public boolean confirmReadOnlyStorage(AccountReauthenticationTarget target) {
        Objects.requireNonNull(target, "target");
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        EdtDispatcher.executeAndWait(() -> {
            String message = i18n("account.storage.read_only")
                    + "\n\n"
                    + i18n("settings.file.force_write.confirm");
            @Nullable Object value = showPrompt(
                    message,
                    i18n("message.warning"),
                    JOptionPane.WARNING_MESSAGE,
                    new Object[]{i18n("settings.file.force_write"), i18n("button.cancel")},
                    i18n("button.cancel"));
            result.set(Integer.valueOf(0).equals(value));
        });
        return Boolean.TRUE.equals(result.get());
    }

    /// Requests a non-empty password and includes the previous localized error on retries.
    @Override
    public char[] requestPassword(
            AccountReauthenticationTarget target,
            @Nullable String localizedError) throws AccountReauthenticationCancelledException {
        Objects.requireNonNull(target, "target");
        AtomicReference<char @Nullable []> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> result.set(showPasswordPrompt(target, localizedError)));
        char @Nullable [] password = result.get();
        if (password == null) {
            throw new AccountReauthenticationCancelledException();
        }
        return password;
    }

    /// Shows a localized OAuth retry-or-cancel prompt.
    @Override
    public boolean confirmOAuthRetry(
            AccountReauthenticationTarget target,
            String localizedError) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(localizedError, "localizedError");
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        EdtDispatcher.executeAndWait(() -> {
            @Nullable Object value = showPrompt(
                    localizedError,
                    i18n("account.failed"),
                    JOptionPane.ERROR_MESSAGE,
                    new Object[]{i18n("account.login.retry"), i18n("button.cancel")},
                    i18n("button.cancel"));
            result.set(Integer.valueOf(0).equals(value));
        });
        return Boolean.TRUE.equals(result.get());
    }

    /// Applies OAuth progress and local browser/clipboard integration on the EDT.
    @Override
    public void onProgress(
            AccountReauthenticationTarget target,
            AccountReauthenticationNotice notice) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(notice, "notice");
        if (target.kind() != AccountReauthenticationKind.OAUTH_DEVICE_CODE) {
            return;
        }
        JLabel status = ensureOAuthDialog(target);
        MicrosoftDeviceCodeButton deviceCode = Objects.requireNonNull(
                oauthDeviceCode.get(),
                "OAuth device-code action");
        deviceCode.clearCode();
        switch (notice.kind()) {
            case AUTHENTICATING -> status.setText(i18n("account.methods.microsoft.logging_in"));
            case BROWSER_AUTHORIZATION -> {
                String location = Objects.requireNonNull(notice.location(), "authorization location");
                updateOAuthActions(location, null);
                status.setText("<html>" + i18n("account.methods.microsoft.methods.browser")
                        + "<br>" + location + "</html>");
                openExternalLocation(location);
            }
            case DEVICE_AUTHORIZATION -> {
                String location = Objects.requireNonNull(notice.location(), "verification location");
                String code = Objects.requireNonNull(notice.code(), "device code");
                updateOAuthActions(location, code);
                status.setText("<html>" + i18n("account.methods.microsoft.methods.device")
                        + "<br>" + location + "</html>");
                deviceCode.showCode(code);
                copyToClipboard(code);
                openExternalLocation(location);
            }
            case AUTHORIZATION_COMPLETED -> status.setText(
                    "<html>" + i18n("account.methods.microsoft.methods.device.hint.completed") + "</html>");
            case PERSISTING -> status.setText(i18n("settings.file.force_write"));
        }
    }

    /// Shows an acknowledgement-only terminal localized failure.
    @Override
    public void showFailure(
            AccountReauthenticationTarget target,
            String localizedError) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(localizedError, "localizedError");
        JOptionPane.showMessageDialog(
                currentOwner(),
                localizedError,
                i18n("account.failed"),
                JOptionPane.ERROR_MESSAGE);
    }

    /// Disposes every current prompt without blocking non-EDT callers.
    @Override
    public void closeCurrentInteraction() {
        EdtDispatcher.execute(() -> {
            @Nullable JDialog prompt = pendingPrompt.getAndSet(null);
            if (prompt != null) {
                prompt.dispose();
            }
            @Nullable JDialog progress = oauthDialog.getAndSet(null);
            oauthStatus.set(null);
            oauthDeviceCode.set(null);
            oauthOpenButton.set(null);
            oauthCopyButton.set(null);
            oauthLocation.set(null);
            oauthCode.set(null);
            if (progress != null) {
                progress.dispose();
            }
        });
    }

    /// Shows one modal password prompt and returns a fresh mutable password array.
    ///
    /// @param target classic account target
    /// @param localizedError previous localized error, or null
    /// @return password characters, or null after cancellation
    private char @Nullable [] showPasswordPrompt(
            AccountReauthenticationTarget target,
            @Nullable String localizedError) {
        EdtDispatcher.requireEventDispatchThread();
        JPasswordField password = new JPasswordField();
        JPanel content = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill]", "[][]10[]"));
        content.add(new JLabel(target.displayName()), "wrap");
        if (localizedError != null && !localizedError.isBlank()) {
            content.add(new JLabel("<html>" + localizedError.replace("\n", "<br>") + "</html>"), "wrap");
        }
        content.add(password, "growx");
        while (true) {
            @Nullable Object value = showPrompt(
                    content,
                    i18n("login.enter_password"),
                    localizedError == null ? JOptionPane.QUESTION_MESSAGE : JOptionPane.ERROR_MESSAGE,
                    new Object[]{i18n("button.ok"), i18n("button.cancel")},
                    i18n("button.cancel"));
            if (!Integer.valueOf(0).equals(value)) {
                return null;
            }
            char[] characters = password.getPassword();
            if (characters.length > 0) {
                password.setText("");
                return characters;
            }
            Toolkit.getDefaultToolkit().beep();
            password.requestFocusInWindow();
        }
    }

    /// Creates or returns the current modeless OAuth progress label.
    ///
    /// @param target OAuth target
    /// @return progress label
    private JLabel ensureOAuthDialog(AccountReauthenticationTarget target) {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable JLabel currentStatus = oauthStatus.get();
        if (currentStatus != null) {
            return currentStatus;
        }

        JDialog dialog = new JDialog(
                currentOwnerWindow(),
                i18n("account.login.refresh"),
                JDialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        JLabel status = new JLabel(i18n("account.methods.microsoft.logging_in"), SwingConstants.CENTER);
        status.setPreferredSize(new Dimension(460, 100));
        MicrosoftDeviceCodeButton deviceCode = new MicrosoftDeviceCodeButton(
                SwingLogFontPreferences.currentOrDefault(),
                SwingAccountReauthenticationInteraction::copyToClipboard);
        JButton cancel = new JButton(i18n("button.cancel"));
        cancel.addActionListener(event -> cancellationCommand.run());
        JButton open = new JButton(i18n("account.methods.microsoft.methods.browser"));
        open.setEnabled(false);
        open.addActionListener(event -> {
            @Nullable String location = oauthLocation.get();
            if (location != null) {
                openExternalLocation(location);
            }
        });
        JButton copy = new JButton(i18n("menu.copy"));
        copy.setEnabled(false);
        copy.addActionListener(event -> {
            @Nullable String code = oauthCode.get();
            @Nullable String location = oauthLocation.get();
            if (code != null) {
                copyToClipboard(code);
            } else if (location != null) {
                copyToClipboard(location);
            }
        });
        JPanel actions = new JPanel(new MigLayout("insets 0", "[]8[][grow]8[]", "[]"));
        actions.add(open);
        actions.add(copy);
        actions.add(new JLabel(), "growx");
        actions.add(cancel);
        JPanel root = new JPanel(new MigLayout(
                "insets 18, fill",
                "[grow,fill]",
                "[grow,fill][][pref!]"));
        root.add(status, "grow, wrap");
        root.add(deviceCode, "alignx center, wrap");
        root.add(actions, "growx");
        dialog.setContentPane(root);
        dialog.addWindowListener(new WindowAdapter() {
            /// Cancels when the user closes the progress dialog.
            @Override
            public void windowClosing(WindowEvent event) {
                cancellationCommand.run();
            }

            /// Cancels when the owning application window disposes the progress dialog.
            @Override
            public void windowClosed(WindowEvent event) {
                if (oauthDialog.compareAndSet(dialog, null)) {
                    oauthStatus.compareAndSet(status, null);
                    oauthDeviceCode.compareAndSet(deviceCode, null);
                    cancellationCommand.run();
                }
            }
        });
        dialog.pack();
        dialog.setLocationRelativeTo(currentOwner());
        if (!oauthDialog.compareAndSet(null, dialog)) {
            dialog.dispose();
            return Objects.requireNonNull(oauthStatus.get(), "OAuth progress label");
        }
        oauthStatus.set(status);
        oauthDeviceCode.set(deviceCode);
        oauthOpenButton.set(open);
        oauthCopyButton.set(copy);
        dialog.setVisible(true);
        return status;
    }

    /// Updates explicit browser-open and clipboard controls for the latest OAuth challenge.
    ///
    /// @param location trusted OAuth location
    /// @param code device code, or null for browser authorization
    private void updateOAuthActions(String location, @Nullable String code) {
        EdtDispatcher.requireEventDispatchThread();
        oauthLocation.set(location);
        oauthCode.set(code);
        @Nullable JButton open = oauthOpenButton.get();
        @Nullable JButton copy = oauthCopyButton.get();
        if (open != null) {
            open.setEnabled(true);
        }
        if (copy != null) {
            copy.setEnabled(true);
        }
    }

    /// Shows one nested option dialog and exposes it to external cancellation.
    ///
    /// @param message prompt content
    /// @param title prompt title
    /// @param messageType JOptionPane message type
    /// @param options explicit options
    /// @param initialValue initially focused option
    /// @return selected option index, or null after close
    private @Nullable Object showPrompt(
            Object message,
            String title,
            int messageType,
            Object @Unmodifiable [] options,
            Object initialValue) {
        EdtDispatcher.requireEventDispatchThread();
        JOptionPane pane = new JOptionPane(
                message,
                messageType,
                JOptionPane.DEFAULT_OPTION,
                null,
                options,
                initialValue);
        JDialog dialog = pane.createDialog(currentOwner(), title);
        if (!pendingPrompt.compareAndSet(null, dialog)) {
            dialog.dispose();
            throw new IllegalStateException("Another reauthentication prompt is already visible");
        }
        try {
            dialog.setVisible(true);
            @Nullable Object value = pane.getValue();
            if (value == null || value == JOptionPane.UNINITIALIZED_VALUE) {
                return null;
            }
            for (int index = 0; index < options.length; index++) {
                if (Objects.equals(options[index], value)) {
                    return index;
                }
            }
            return null;
        } finally {
            pendingPrompt.compareAndSet(dialog, null);
            dialog.dispose();
        }
    }

    /// Resolves the current owner component on the EDT.
    ///
    /// @return current owner, or null
    private @Nullable Component currentOwner() {
        EdtDispatcher.requireEventDispatchThread();
        return ownerSupplier.get();
    }

    /// Resolves the current top-level owner window on the EDT.
    ///
    /// @return owner window, or null
    private @Nullable Window currentOwnerWindow() {
        @Nullable Component owner = currentOwner();
        if (owner instanceof Window window) {
            return window;
        }
        return owner == null ? null : SwingUtilities.getWindowAncestor(owner);
    }

    /// Opens a trusted OAuth location in the system browser.
    ///
    /// @param location trusted OAuth location
    private static void openExternalLocation(String location) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create(location));
            }
        } catch (IOException | IllegalArgumentException | UnsupportedOperationException failure) {
            LOG.warning("Failed to open account reauthentication location", failure);
        }
    }

    /// Copies a device code to the platform clipboard.
    ///
    /// @param code device code
    private static void copyToClipboard(String code) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(code), null);
        } catch (IllegalStateException | java.awt.HeadlessException | SecurityException failure) {
            LOG.warning("Failed to copy account reauthentication device code", failure);
        }
    }
}
