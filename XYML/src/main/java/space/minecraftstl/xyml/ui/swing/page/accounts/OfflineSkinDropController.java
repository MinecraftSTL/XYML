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
