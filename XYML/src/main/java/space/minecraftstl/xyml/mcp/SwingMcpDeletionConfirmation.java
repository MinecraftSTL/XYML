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
package space.minecraftstl.xyml.mcp;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Window;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Presents launcher-owned deletion confirmation dialogs on the Swing event-dispatch thread.
@NotNullByDefault
public final class SwingMcpDeletionConfirmation implements McpDeletionConfirmation {
    /// Resolves the latest persisted confirmation preference for each deletion category.
    private final Predicate<DeletionKind> confirmationRequired;

    /// Resolves whether launcher settings can persist an opt-out request.
    private final BooleanSupplier confirmationWritable;

    /// Disables future confirmation for one deletion category after explicit approval.
    private final Consumer<DeletionKind> disableConfirmation;

    /// Native dialog boundary used by production and headless tests.
    private final ConfirmationDialog dialog;

    /// Creates a production confirmation policy backed by `JOptionPane`.
    ///
    /// @param confirmationRequired resolves whether each deletion category requires manual approval
    /// @param confirmationWritable resolves whether an opt-out request can be persisted
    /// @param disableConfirmation disables future confirmation for one deletion category
    public SwingMcpDeletionConfirmation(
            Predicate<DeletionKind> confirmationRequired,
            BooleanSupplier confirmationWritable,
            Consumer<DeletionKind> disableConfirmation) {
        this(
                confirmationRequired,
                confirmationWritable,
                disableConfirmation,
                SwingMcpDeletionConfirmation::showDialog);
    }

    /// Creates a policy with an explicit dialog boundary.
    ///
    /// @param confirmationRequired resolves whether each deletion category requires manual approval
    /// @param confirmationWritable resolves whether an opt-out request can be persisted
    /// @param disableConfirmation disables future confirmation for one deletion category
    /// @param dialog confirmation presentation
    SwingMcpDeletionConfirmation(
            Predicate<DeletionKind> confirmationRequired,
            BooleanSupplier confirmationWritable,
            Consumer<DeletionKind> disableConfirmation,
            ConfirmationDialog dialog) {
        this.confirmationRequired = Objects.requireNonNull(confirmationRequired, "confirmationRequired");
        this.confirmationWritable = Objects.requireNonNull(confirmationWritable, "confirmationWritable");
        this.disableConfirmation = Objects.requireNonNull(disableConfirmation, "disableConfirmation");
        this.dialog = Objects.requireNonNull(dialog, "dialog");
    }

    /// Returns immediately when confirmation is disabled, otherwise waits for an EDT-owned dialog.
    ///
    /// @param request immutable deletion description
    /// @return whether the launcher user approved the deletion
    @Override
    public boolean confirm(DeletionRequest request) {
        DeletionRequest checkedRequest = Objects.requireNonNull(request, "request");
        AtomicReference<ConfirmationDecision> decision = new AtomicReference<>(ConfirmationDecision.cancelled());
        EdtDispatcher.executeAndWait(() -> {
            if (!confirmationRequired.test(checkedRequest.kind())) {
                decision.set(ConfirmationDecision.bypassed());
                return;
            }
            boolean writable = confirmationWritable.getAsBoolean();
            ConfirmationDecision currentDecision = dialog.confirm(
                    activeWindow(),
                    message(checkedRequest),
                    disableConfirmationMessage(checkedRequest.kind()),
                    i18n("mcp.deletion_confirmation.title"),
                    writable);
            decision.set(currentDecision);
            if (writable && currentDecision.approved() && currentDecision.disableFutureConfirmation()) {
                disableConfirmation.accept(checkedRequest.kind());
            }
        });
        return decision.get().approved();
    }

    /// Formats the localized message for one deletion category.
    ///
    /// @param request deletion being presented
    /// @return localized warning text
    private static String message(DeletionRequest request) {
        return switch (request.kind()) {
            case INSTANCE -> i18n("mcp.deletion_confirmation.instance", request.instanceId().id());
            case MODS -> i18n(
                    "mcp.deletion_confirmation.mods",
                    request.itemCount(),
                    request.instanceId().id());
        };
    }

    /// Formats the localized opt-out label for one deletion category.
    ///
    /// @param kind deletion category being presented
    /// @return localized opt-out label
    private static String disableConfirmationMessage(DeletionKind kind) {
        return switch (kind) {
            case INSTANCE -> i18n("mcp.deletion_confirmation.disable_instance");
            case MODS -> i18n("mcp.deletion_confirmation.disable_mods");
        };
    }

    /// Finds the active visible launcher window for native dialog ownership.
    ///
    /// @return active or visible window, or null before the launcher window is available
    private static @Nullable Component activeWindow() {
        @Nullable Window visible = null;
        for (Window window : Window.getWindows()) {
            if (window.isActive()) {
                return window;
            }
            if (visible == null && window.isVisible()) {
                visible = window;
            }
        }
        return visible;
    }

    /// Shows the production warning dialog.
    ///
    /// @param owner active launcher window, or null before one exists
    /// @param message localized deletion warning
    /// @param disableConfirmationMessage localized label for disabling future confirmation
    /// @param title localized dialog title
    /// @param confirmationWritable whether an opt-out request can be persisted
    /// @return immutable dialog decision
    private static ConfirmationDecision showDialog(
            @Nullable Component owner,
            String message,
            String disableConfirmationMessage,
            String title,
            boolean confirmationWritable) {
        JLabel warningLabel = new JLabel(message);
        warningLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        JCheckBox disableConfirmationBox = new JCheckBox(disableConfirmationMessage);
        disableConfirmationBox.setName("mcpDeletionDisableConfirmation");
        disableConfirmationBox.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.add(warningLabel);
        if (confirmationWritable) {
            content.add(Box.createVerticalStrut(12));
            content.add(disableConfirmationBox);
        }

        int option = JOptionPane.showConfirmDialog(
                owner,
                content,
                title,
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        return new ConfirmationDecision(
                option == JOptionPane.OK_OPTION,
                confirmationWritable && disableConfirmationBox.isSelected());
    }

    /// Abstracts the native dialog for deterministic tests.
    @NotNullByDefault
    @FunctionalInterface
    interface ConfirmationDialog {
        /// Presents one deletion warning.
        ///
        /// @param owner active launcher window, or null
        /// @param message localized warning
        /// @param disableConfirmationMessage localized label for disabling future confirmation
        /// @param title localized title
        /// @param confirmationWritable whether an opt-out request can be persisted
        /// @return immutable dialog decision
        ConfirmationDecision confirm(
                @Nullable Component owner,
                String message,
                String disableConfirmationMessage,
                String title,
                boolean confirmationWritable);
    }

    /// Captures the user's decision and optional preference change from one confirmation dialog.
    ///
    /// @param approved whether the current deletion was approved
    /// @param disableFutureConfirmation whether future confirmation should be disabled for this category
    @NotNullByDefault
    record ConfirmationDecision(boolean approved, boolean disableFutureConfirmation) {
        /// Creates a decision returned by the confirmation dialog.
        ConfirmationDecision {
        }

        /// Creates the initial cancelled decision used before the EDT completes.
        ///
        /// @return cancelled decision without a preference change
        static ConfirmationDecision cancelled() {
            return new ConfirmationDecision(false, false);
        }

        /// Creates an approved decision that bypasses a disabled confirmation dialog.
        ///
        /// @return approved decision without a preference change
        static ConfirmationDecision bypassed() {
            return new ConfirmationDecision(true, false);
        }
    }
}
