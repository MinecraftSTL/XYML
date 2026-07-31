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
package space.minecraftstl.xyml.ui.swing.startup;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.Timer;
import java.awt.Component;
import java.awt.Container;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Presents blocking safety decisions before the main Swing runtime is fully composed.
///
/// Calls from launcher-state or worker threads synchronously cross only into the Swing EDT; no Swing callback
/// waits for the originating thread.
@NotNullByDefault
public final class SwingStartupSafetyDialogs {
    /// Prevents utility instantiation.
    private SwingStartupSafetyDialogs() {
    }

    /// Shows one localized bootstrap message.
    ///
    /// @param severity message severity
    /// @param message localized message body
    public static void showMessage(Severity severity, String message) {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
        runOnEdt(() -> {
            JOptionPane.showMessageDialog(
                    null,
                    message,
                    title(severity),
                    messageType(severity));
            return Boolean.TRUE;
        });
    }

    /// Requests one explicit yes/no bootstrap decision.
    ///
    /// Closing the dialog is treated as rejection.
    ///
    /// @param severity message severity
    /// @param message localized message body
    /// @return whether the user explicitly selected yes
    public static boolean confirm(Severity severity, String message) {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
        return runOnEdt(() -> JOptionPane.showOptionDialog(
                null,
                message,
                title(severity),
                JOptionPane.YES_NO_OPTION,
                messageType(severity),
                null,
                new Object[]{i18n("button.yes"), i18n("button.no")},
                i18n("button.no")) == JOptionPane.YES_OPTION);
    }

    /// Requests confirmation after a visible countdown prevents accidental acceptance.
    ///
    /// Closing the dialog or selecting no is rejection. The positive button stays disabled until the
    /// complete positive countdown reaches zero.
    ///
    /// @param severity message severity
    /// @param message localized message body
    /// @param seconds positive countdown duration
    /// @return whether the user explicitly confirmed after the countdown
    public static boolean confirmWithCountdown(
            Severity severity,
            String message,
            int seconds) {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
        if (seconds <= 0) {
            throw new IllegalArgumentException("seconds must be positive");
        }
        return runOnEdt(() -> confirmWithCountdownOnEdt(severity, message, seconds));
    }

    /// Offers to copy a repair command before the caller terminates bootstrap.
    ///
    /// @param message localized failure and repair explanation
    /// @param command exact repair command copied on request
    /// @return whether the command was copied
    public static boolean offerCopyAndExit(String message, String command) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(command, "command");
        return runOnEdt(() -> {
            String copyLabel = i18n("button.copy_and_exit");
            String closeLabel = i18n("button.cancel");
            int selection = JOptionPane.showOptionDialog(
                    null,
                    message,
                    title(Severity.ERROR),
                    JOptionPane.DEFAULT_OPTION,
                    messageType(Severity.ERROR),
                    null,
                    new Object[]{copyLabel, closeLabel},
                    closeLabel);
            if (selection != 0) {
                return false;
            }
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                    new StringSelection(command),
                    null);
            return true;
        });
    }

    /// Runs the native countdown option pane on the Swing EDT.
    ///
    /// @param severity message severity
    /// @param message localized message body
    /// @param seconds positive countdown duration
    /// @return whether the positive option was selected after becoming enabled
    private static boolean confirmWithCountdownOnEdt(
            Severity severity,
            String message,
            int seconds) {
        EdtDispatcher.requireEventDispatchThread();
        String yesLabel = i18n("button.yes");
        String noLabel = i18n("button.no");
        JOptionPane pane = new JOptionPane(
                message,
                messageType(severity),
                JOptionPane.YES_NO_OPTION,
                null,
                new Object[]{yesLabel, noLabel},
                noLabel);
        JDialog dialog = pane.createDialog(null, title(severity));
        JButton yesButton = Objects.requireNonNull(
                findButton(dialog, yesLabel),
                "Swing did not create the positive countdown button");
        AtomicInteger remaining = new AtomicInteger(seconds);
        yesButton.setEnabled(false);
        yesButton.setText(i18n("button.ok.countdown", seconds));
        Timer countdown = new Timer(1_000, event -> {
            int next = remaining.decrementAndGet();
            if (next <= 0) {
                ((Timer) event.getSource()).stop();
                yesButton.setText(i18n("button.ok"));
                yesButton.setEnabled(true);
            } else {
                yesButton.setText(i18n("button.ok.countdown", next));
            }
        });
        countdown.setInitialDelay(1_000);
        countdown.start();
        try {
            dialog.setVisible(true);
            return Objects.equals(pane.getValue(), yesLabel);
        } finally {
            countdown.stop();
            dialog.dispose();
        }
    }

    /// Finds one exact-text button in a Swing component tree.
    ///
    /// @param component current component-tree root
    /// @param text exact button text
    /// @return matching button, or null when absent
    static @Nullable JButton findButton(Component component, String text) {
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(text, "text");
        if (component instanceof JButton button && text.equals(button.getText())) {
            return button;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                @Nullable JButton result = findButton(child, text);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    /// Maps one severity to a localized dialog title.
    ///
    /// @param severity message severity
    /// @return localized title
    static String title(Severity severity) {
        return switch (Objects.requireNonNull(severity, "severity")) {
            case INFO -> i18n("message.info");
            case WARNING -> i18n("message.warning");
            case ERROR -> i18n("message.error");
        };
    }

    /// Maps one severity to the matching `JOptionPane` message constant.
    ///
    /// @param severity message severity
    /// @return Swing message-type constant
    static int messageType(Severity severity) {
        return switch (Objects.requireNonNull(severity, "severity")) {
            case INFO -> JOptionPane.INFORMATION_MESSAGE;
            case WARNING -> JOptionPane.WARNING_MESSAGE;
            case ERROR -> JOptionPane.ERROR_MESSAGE;
        };
    }

    /// Runs one non-null dialog result on the Swing EDT.
    ///
    /// @param supplier EDT-confined result supplier
    /// @param <T> non-null result type
    /// @return supplied result
    private static <T> T runOnEdt(Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        AtomicReference<@Nullable T> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> result.set(Objects.requireNonNull(
                supplier.get(),
                "startup dialog returned null")));
        return Objects.requireNonNull(result.get(), "startup dialog did not run");
    }

    /// Bootstrap message severity independent from JavaFX alert types.
    @NotNullByDefault
    public enum Severity {
        /// Informational notice.
        INFO,

        /// Recoverable warning.
        WARNING,

        /// Blocking startup error.
        ERROR
    }
}
