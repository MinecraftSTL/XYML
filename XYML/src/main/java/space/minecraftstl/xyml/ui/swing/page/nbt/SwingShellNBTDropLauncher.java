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
package space.minecraftstl.xyml.ui.swing.page.nbt;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.nbt.NBTFileType;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.shell.AppShellFrame;
import space.minecraftstl.xyml.ui.swing.shell.ShellFileDropHandler;
import space.minecraftstl.xyml.ui.swing.shell.ShellPageId;

import javax.swing.JComponent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/// Installs the default-instance-workspace route for dropped NBT documents.
///
/// The route performs lexical file-family matching only and delegates all document I/O and modeless-window
/// ownership to [SwingNBTEditorLauncher]. It is deliberately disabled on every side destination, including
/// the explicit instance-list page, to match the former launcher's root-page behavior.
@NotNullByDefault
public final class SwingShellNBTDropLauncher implements AutoCloseable {
    /// Supplies the currently selected side destination, or null for the default workspace.
    private final Supplier<@Nullable ShellPageId> selectedPageSupplier;

    /// Existing direct-path NBT editor command.
    private final Consumer<Path> openCommand;

    /// Existing editor lifecycle close command.
    private final Runnable closeCommand;

    /// Independently removable NBT route installed on the shell.
    private final ShellFileDropHandler.RouteRegistration dropRegistration;

    /// Whether owner or composition closure has permanently disabled this launcher.
    private boolean closed;

    /// Installs the production NBT route and ties its lifetime to the launcher frame.
    ///
    /// @param frame production launcher frame
    /// @param ioExecutor caller-owned executor for NBT and bundled-icon I/O
    /// @return installed launcher lifecycle
    public static SwingShellNBTDropLauncher install(
            AppShellFrame frame,
            Executor ioExecutor) {
        AppShellFrame owner = Objects.requireNonNull(frame, "frame");
        Executor executor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        AtomicReference<@Nullable SwingShellNBTDropLauncher> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> {
            SwingNBTEditorLauncher editor = SwingNBTEditorLauncher.createForDirectPaths(
                    owner.shellPanel(),
                    executor);
            SwingShellNBTDropLauncher launcher = install(
                    owner.shellPanel(),
                    owner.shellPanel()::selectedPage,
                    editor::open,
                    editor::close);
            owner.addWindowListener(new WindowAdapter() {
                /// Releases the drop route and modeless editor after owner disposal.
                ///
                /// @param event native owner-window event
                @Override
                public void windowClosed(WindowEvent event) {
                    Objects.requireNonNull(event, "event");
                    launcher.close();
                }
            });
            result.set(launcher);
        });
        return Objects.requireNonNull(result.get(), "shell NBT launcher was not installed");
    }

    /// Installs an injected launcher boundary for headless tests.
    ///
    /// @param target shell drop target
    /// @param selectedPageSupplier current side-destination supplier
    /// @param openCommand direct-path NBT editor command
    /// @param closeCommand editor lifecycle close command
    /// @return installed launcher lifecycle
    static SwingShellNBTDropLauncher install(
            JComponent target,
            Supplier<@Nullable ShellPageId> selectedPageSupplier,
            Consumer<Path> openCommand,
            Runnable closeCommand) {
        EdtDispatcher.requireEventDispatchThread();
        return new SwingShellNBTDropLauncher(
                target,
                selectedPageSupplier,
                openCommand,
                closeCommand);
    }

    /// Creates and registers one default-workspace route on the EDT.
    ///
    /// @param target shell drop target
    /// @param selectedPageSupplier current side-destination supplier
    /// @param openCommand direct-path NBT editor command
    /// @param closeCommand editor lifecycle close command
    private SwingShellNBTDropLauncher(
            JComponent target,
            Supplier<@Nullable ShellPageId> selectedPageSupplier,
            Consumer<Path> openCommand,
            Runnable closeCommand) {
        EdtDispatcher.requireEventDispatchThread();
        this.selectedPageSupplier = Objects.requireNonNull(selectedPageSupplier, "selectedPageSupplier");
        this.openCommand = Objects.requireNonNull(openCommand, "openCommand");
        this.closeCommand = Objects.requireNonNull(closeCommand, "closeCommand");
        dropRegistration = ShellFileDropHandler.register(
                Objects.requireNonNull(target, "target"),
                this::supportsOnDefaultWorkspace,
                this::open);
    }

    /// Removes the NBT route and closes the owned editor lifecycle from any caller thread.
    @Override
    public void close() {
        EdtDispatcher.executeAndWait(() -> {
            if (closed) {
                return;
            }
            closed = true;
            dropRegistration.close();
            closeCommand.run();
        });
    }

    /// Returns whether one path is supported while the default instance workspace is visible.
    ///
    /// @param source normalized dropped path
    /// @return whether the path can be opened from the current shell page
    private boolean supportsOnDefaultWorkspace(Path source) {
        return !closed
                && selectedPageSupplier.get() == null
                && NBTFileType.supports(Objects.requireNonNull(source, "source"));
    }

    /// Opens one accepted NBT document through the existing direct-path editor launcher.
    ///
    /// @param source normalized supported NBT path
    private void open(Path source) {
        EdtDispatcher.requireEventDispatchThread();
        Path candidate = Objects.requireNonNull(source, "source");
        if (supportsOnDefaultWorkspace(candidate)) {
            openCommand.accept(candidate);
        }
    }
}
