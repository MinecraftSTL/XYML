// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.ui.swing.shell.ShellFileDropHandler;

import javax.swing.JComponent;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/// Routes one page-scoped PNG drop into the existing offline-skin workflow.
@NotNullByDefault
final class OfflineSkinDropController implements AutoCloseable {
    /// Current account-state gate supplied by the owning dialog.
    private final BooleanSupplier inputAvailable;

    /// Existing command that stages and validates a local skin image.
    private final Consumer<Path> skinCommand;

    /// Independently removable route installed on the preview surface.
    private final ShellFileDropHandler.RouteRegistration registration;

    /// Installs one PNG-only route on the supplied preview surface.
    ///
    /// @param target offline-skin preview surface
    /// @param inputAvailable current account writable state
    /// @param skinCommand existing local-skin staging command
    /// @return installed route controller
    static OfflineSkinDropController install(
            JComponent target,
            BooleanSupplier inputAvailable,
            Consumer<Path> skinCommand) {
        return new OfflineSkinDropController(target, inputAvailable, skinCommand);
    }

    /// Creates and installs one page-scoped PNG route.
    ///
    /// @param target offline-skin preview surface
    /// @param inputAvailable current account writable state
    /// @param skinCommand existing local-skin staging command
    private OfflineSkinDropController(
            JComponent target,
            BooleanSupplier inputAvailable,
            Consumer<Path> skinCommand) {
        this.inputAvailable = Objects.requireNonNull(inputAvailable, "inputAvailable");
        this.skinCommand = Objects.requireNonNull(skinCommand, "skinCommand");
        registration = ShellFileDropHandler.register(
                Objects.requireNonNull(target, "target"),
                this::supports,
                this::open);
    }

    /// Removes this controller's route without disturbing sibling handlers.
    @Override
    public void close() {
        registration.close();
    }

    /// Returns whether one dropped path is a PNG accepted by the current account state.
    ///
    /// @param source normalized dropped path
    /// @return whether the path can be staged as a local skin
    private boolean supports(Path source) {
        if (!inputAvailable.getAsBoolean()) {
            return false;
        }
        @Nullable Path fileName = Objects.requireNonNull(source, "source").getFileName();
        return fileName != null && fileName.toString().toLowerCase(Locale.ROOT).endsWith(".png");
    }

    /// Delegates one accepted PNG to the dialog's existing staging command.
    ///
    /// @param source normalized supported path
    private void open(Path source) {
        Path candidate = Objects.requireNonNull(source, "source");
        if (supports(candidate)) {
            skinCommand.accept(candidate);
        }
    }
}
