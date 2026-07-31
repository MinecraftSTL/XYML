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
package space.minecraftstl.xyml.ui.swing.log;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.GameDumpGenerator;
import space.minecraftstl.xyml.util.platform.ManagedProcess;
import space.minecraftstl.xyml.util.platform.OperatingSystem;
import space.minecraftstl.xyml.util.platform.SystemUtils;

import java.awt.Desktop;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/// Connects the toolkit-neutral game log actions to one managed game process and the local desktop.
@NotNullByDefault
final class ManagedProcessGameLogWindowActions implements GameLogWindowActions {
    /// Thread-safe timestamp format shared by exported file names.
    private static final DateTimeFormatter FILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss");

    /// Managed game process controlled by the window.
    private final ManagedProcess process;

    /// Creates production actions for one managed process.
    ///
    /// @param process managed game process
    ManagedProcessGameLogWindowActions(ManagedProcess process) {
        this.process = Objects.requireNonNull(process, "process");
    }

    /// Registers a weak-window callback with the raw process exit stage.
    ///
    /// @param listener callback invoked for normal or exceptional completion
    @Override
    public void registerProcessExit(Runnable listener) {
        Runnable exitListener = Objects.requireNonNull(listener, "listener");
        process.getProcess().onExit().whenComplete(
                (@Nullable Process ignored, @Nullable Throwable failure) -> exitListener.run());
    }

    /// Reports whether the managed process is still running.
    ///
    /// @return true while the process has not exited
    @Override
    public boolean isProcessRunning() {
        return process.isRunning();
    }

    /// Reports whether the runtime contains JVM attachment support for a running process.
    ///
    /// @return true when stack-dump export is currently available
    @Override
    public boolean canExportDump() {
        return process.isRunning() && SystemUtils.supportJVMAttachment();
    }

    /// Stops the managed process and related stream threads.
    @Override
    public void terminateGame() {
        process.stop();
    }

    /// Writes the immutable log snapshot to a timestamped file in the current directory.
    ///
    /// @param lines immutable text snapshot in source order
    /// @return absolute path of the exported file
    /// @throws IOException when the file cannot be written
    @Override
    public Path exportLogs(@Unmodifiable List<String> lines) throws IOException {
        Path target = timestampedPath("minecraft-exported-logs-");
        Files.write(target, Objects.requireNonNull(lines, "lines"));
        return target;
    }

    /// Generates a timestamped JVM stack dump for the running process.
    ///
    /// @return absolute path of the generated dump
    /// @throws Exception when the process has exited or attachment fails
    @Override
    public Path exportDump() throws Exception {
        if (!process.isRunning()) {
            throw new IOException("The game process has already exited");
        }
        Path target = timestampedPath("minecraft-exported-jstack-dump-");
        GameDumpGenerator.writeDumpTo(process.getProcess().pid(), target);
        return target;
    }

    /// Reveals an export using platform-specific selection and then a folder fallback.
    ///
    /// @param file exported file to reveal
    /// @throws Exception when native selection and the desktop folder fallback both fail
    @Override
    public void revealFile(Path file) throws Exception {
        Path absoluteFile = Objects.requireNonNull(file, "file").toAbsolutePath();
        @Nullable @Unmodifiable List<String> revealCommand = revealCommand(absoluteFile);
        if (revealCommand != null) {
            int exitCode = SystemUtils.callExternalProcess(revealCommand);
            if (exitCode == 0 || (exitCode == 1 && OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS)) {
                return;
            }
        }

        @Nullable Path parent = absoluteFile.getParent();
        if (parent == null || !Desktop.isDesktopSupported()) {
            throw new IOException("No desktop file-manager integration is available");
        }
        Desktop desktop = Desktop.getDesktop();
        if (!desktop.isSupported(Desktop.Action.OPEN)) {
            throw new IOException("Desktop folder opening is unavailable");
        }
        desktop.open(parent.toFile());
    }

    /// Replaces the desktop clipboard text.
    ///
    /// @param text selected log text
    @Override
    public void copyText(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                new StringSelection(Objects.requireNonNull(text, "text")),
                null);
    }

    /// Builds the platform-specific command that selects a file, when one is available.
    ///
    /// @param file absolute file to reveal
    /// @return immutable command, or null when the platform has no supported selection command
    private static @Nullable @Unmodifiable List<String> revealCommand(Path file) {
        String path = file.toString();
        if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
            return List.of("explorer.exe", "/select,", path);
        }
        if (OperatingSystem.CURRENT_OS == OperatingSystem.MACOS) {
            return List.of("/usr/bin/open", "-R", path);
        }
        if (OperatingSystem.CURRENT_OS.isLinuxOrBSD() && SystemUtils.which("dbus-send") != null) {
            return List.of(
                    "dbus-send",
                    "--print-reply",
                    "--dest=org.freedesktop.FileManager1",
                    "/org/freedesktop/FileManager1",
                    "org.freedesktop.FileManager1.ShowItems",
                    "array:string:" + file.toUri(),
                    "string:");
        }
        return null;
    }

    /// Creates an absolute timestamped log path in the current directory.
    ///
    /// @param prefix file-name prefix identifying the export type
    /// @return absolute export path ending in {@code .log}
    private static Path timestampedPath(String prefix) {
        return Path.of(prefix + LocalDateTime.now().format(FILE_TIMESTAMP) + ".log").toAbsolutePath();
    }
}
