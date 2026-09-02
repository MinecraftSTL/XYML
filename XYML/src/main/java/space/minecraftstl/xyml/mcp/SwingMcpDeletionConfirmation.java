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

import javax.swing.JOptionPane;
import java.awt.Component;
import java.awt.Window;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Presents launcher-owned deletion confirmation dialogs on the Swing event-dispatch thread.
@NotNullByDefault
public final class SwingMcpDeletionConfirmation implements McpDeletionConfirmation {
    /// Supplies the latest persisted deletion-confirmation preference.
    private final BooleanSupplier confirmationRequired;

    /// Native dialog boundary used by production and headless tests.
    private final ConfirmationDialog dialog;

    /// Creates a production confirmation policy backed by `JOptionPane`.
    ///
    /// @param confirmationRequired supplies whether destructive calls require manual approval
    public SwingMcpDeletionConfirmation(BooleanSupplier confirmationRequired) {
        this(confirmationRequired, SwingMcpDeletionConfirmation::showDialog);
    }

    /// Creates a policy with an explicit dialog boundary.
    ///
    /// @param confirmationRequired supplies whether destructive calls require manual approval
    /// @param dialog confirmation presentation
    SwingMcpDeletionConfirmation(BooleanSupplier confirmationRequired, ConfirmationDialog dialog) {
        this.confirmationRequired = Objects.requireNonNull(confirmationRequired, "confirmationRequired");
        this.dialog = Objects.requireNonNull(dialog, "dialog");
    }

    /// Returns immediately when confirmation is disabled, otherwise waits for an EDT-owned dialog.
    ///
    /// @param request immutable deletion description
    /// @return whether the launcher user approved the deletion
    @Override
    public boolean confirm(DeletionRequest request) {
        DeletionRequest checkedRequest = Objects.requireNonNull(request, "request");
        if (!confirmationRequired.getAsBoolean()) {
            return true;
        }

        AtomicBoolean approved = new AtomicBoolean();
        EdtDispatcher.executeAndWait(() -> approved.set(dialog.confirm(
                activeWindow(),
                message(checkedRequest),
                i18n("mcp.deletion_confirmation.title"))));
        return approved.get();
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
    /// @param title localized dialog title
    /// @return whether the user chose the affirmative action
    private static boolean showDialog(@Nullable Component owner, String message, String title) {
        return JOptionPane.showConfirmDialog(
                owner,
                message,
                title,
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
    }

    /// Abstracts the native dialog for deterministic tests.
    @NotNullByDefault
    @FunctionalInterface
    interface ConfirmationDialog {
        /// Presents one deletion warning.
        ///
        /// @param owner active launcher window, or null
        /// @param message localized warning
        /// @param title localized title
        /// @return whether the user approved the deletion
        boolean confirm(@Nullable Component owner, String message, String title);
    }
}
