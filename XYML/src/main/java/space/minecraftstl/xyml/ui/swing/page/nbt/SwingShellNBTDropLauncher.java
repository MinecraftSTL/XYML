// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.nbt;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.nbt.NBTFileType;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.shell.AppShellFrame;
import space.minecraftstl.xyml.ui.swing.shell.ShellFileDropHandler;

import javax.swing.JComponent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/// Installs the application-wide route for dropped NBT documents.
///
/// The route performs lexical file-family matching only and delegates all document I/O and modeless-window
/// ownership to the [SwingNBTEditorLauncher] shared by every entry point in the containing application window.
@NotNullByDefault
public final class SwingShellNBTDropLauncher implements AutoCloseable {
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
            SwingNBTEditorLauncher editor = SwingNBTEditorLauncher.installShared(
                    owner.shellPanel(),
                    executor);
            SwingShellNBTDropLauncher launcher = install(
                    owner.shellPanel(),
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
    /// @param openCommand direct-path NBT editor command
    /// @param closeCommand editor lifecycle close command
    /// @return installed launcher lifecycle
    static SwingShellNBTDropLauncher install(
            JComponent target,
            Consumer<Path> openCommand,
            Runnable closeCommand) {
        EdtDispatcher.requireEventDispatchThread();
        return new SwingShellNBTDropLauncher(
                target,
                openCommand,
                closeCommand);
    }

    /// Creates and registers one default-workspace route on the EDT.
    ///
    /// @param target shell drop target
    /// @param openCommand direct-path NBT editor command
    /// @param closeCommand editor lifecycle close command
    private SwingShellNBTDropLauncher(
            JComponent target,
            Consumer<Path> openCommand,
            Runnable closeCommand) {
        EdtDispatcher.requireEventDispatchThread();
        this.openCommand = Objects.requireNonNull(openCommand, "openCommand");
        this.closeCommand = Objects.requireNonNull(closeCommand, "closeCommand");
        dropRegistration = ShellFileDropHandler.register(
                Objects.requireNonNull(target, "target"),
                this::supports,
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

    /// Returns whether one path is supported anywhere in the application shell.
    ///
    /// @param source normalized dropped path
    /// @return whether the path can be opened from the current shell page
    private boolean supports(Path source) {
        return !closed
                && NBTFileType.supports(Objects.requireNonNull(source, "source"));
    }

    /// Opens one accepted NBT document through the existing direct-path editor launcher.
    ///
    /// @param source normalized supported NBT path
    private void open(Path source) {
        EdtDispatcher.requireEventDispatchThread();
        Path candidate = Objects.requireNonNull(source, "source");
        if (supports(candidate)) {
            openCommand.accept(candidate);
        }
    }
}
