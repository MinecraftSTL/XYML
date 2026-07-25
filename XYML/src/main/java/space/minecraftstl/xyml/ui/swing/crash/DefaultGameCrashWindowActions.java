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
package space.minecraftstl.xyml.ui.swing.crash;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.DefaultGameRepository;
import space.minecraftstl.xyml.game.LaunchOptions;
import space.minecraftstl.xyml.game.Log;
import space.minecraftstl.xyml.game.LogExporter;
import space.minecraftstl.xyml.game.Version;
import space.minecraftstl.xyml.util.platform.ManagedProcess;
import space.minecraftstl.xyml.util.platform.OperatingSystem;
import space.minecraftstl.xyml.util.platform.SystemUtils;
import space.minecraftstl.xyml.util.platform.CommandBuilder;

import java.awt.Desktop;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Production actions for one completed game process and its repository.
@NotNullByDefault
final class DefaultGameCrashWindowActions implements GameCrashWindowActions {
    /// Thread-safe timestamp format used for exported crash bundles.
    private static final DateTimeFormatter FILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss");

    /// Managed process whose command and start time are included in the bundle.
    private final ManagedProcess process;

    /// Repository used by `LogExporter` to collect instance files.
    private final DefaultGameRepository repository;

    /// Version identifier used as a fallback when launch options omit a display version.
    private final Version version;

    /// Resolved launch configuration supplying version and game-directory information.
    private final LaunchOptions launchOptions;

    /// Immutable captured process-output snapshot.
    private final @Unmodifiable List<Log> logs;

    /// Action opening the already-created Swing game-log window.
    private final Runnable showGameLogs;

    /// Executor used to prepare the potentially large captured-log string.
    private final Executor executor;

    /// Creates production game-crash actions.
    ///
    /// @param process completed managed process
    /// @param repository repository owning the launched instance
    /// @param version launched version
    /// @param launchOptions resolved launch configuration
    /// @param logs immutable captured process-output snapshot
    /// @param showGameLogs action opening the Swing game-log window
    /// @param executor executor for export preparation
    DefaultGameCrashWindowActions(
            ManagedProcess process,
            DefaultGameRepository repository,
            Version version,
            LaunchOptions launchOptions,
            List<Log> logs,
            Runnable showGameLogs,
            Executor executor) {
        this.process = Objects.requireNonNull(process, "process");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.version = Objects.requireNonNull(version, "version");
        this.launchOptions = Objects.requireNonNull(launchOptions, "launchOptions");
        this.logs = List.copyOf(Objects.requireNonNull(logs, "logs"));
        this.showGameLogs = Objects.requireNonNull(showGameLogs, "showGameLogs");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /// Builds one timestamped crash bundle through the existing `LogExporter` implementation.
    ///
    /// @return asynchronous absolute zip-file path
    @Override
    public CompletionStage<Path> exportCrashLogs() {
        Path target = Path.of(
                "minecraft-exported-crash-info-" + LocalDateTime.now().format(FILE_TIMESTAMP) + ".zip")
                .toAbsolutePath();
        return CompletableFuture.supplyAsync(
                        () -> logs.stream().map(Log::getLog).collect(Collectors.joining("\n")),
                        executor)
                .thenCompose(capturedText -> {
                    long processStartTime = processStartTime();
                    return LogExporter.exportLogs(
                            target,
                            repository,
                            Objects.requireNonNullElse(launchOptions.getVersionName(), version.getId()),
                            capturedText,
                            new CommandBuilder().addAll(process.getCommands()).toString(),
                            path -> belongsToCurrentLaunch(path, processStartTime));
                })
                .thenApply(ignored -> target);
    }

    /// Reveals the exported bundle using platform-specific file selection and a folder fallback.
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

    /// Runs the injected action that opens or raises the existing Swing game-log window.
    @Override
    public void showGameLogs() {
        showGameLogs.run();
    }

    /// Opens a trusted link through the native desktop browser.
    ///
    /// @param destination link destination
    /// @throws IOException when browsing is unsupported or fails
    @Override
    public void openLink(URI destination) throws IOException {
        Objects.requireNonNull(destination, "destination");
        if (!Desktop.isDesktopSupported()) {
            throw new IOException("Desktop integration is unavailable");
        }
        Desktop desktop = Desktop.getDesktop();
        if (!desktop.isSupported(Desktop.Action.BROWSE)) {
            throw new IOException("Desktop browsing is unavailable");
        }
        desktop.browse(destination);
    }

    /// Reports whether a log file was modified after the launched process started.
    ///
    /// @param path candidate log file
    /// @param processStartTime launched process start epoch milliseconds
    /// @return true when the candidate belongs to this launch
    private static boolean belongsToCurrentLaunch(Path path, long processStartTime) {
        try {
            FileTime lastModifiedTime = Files.getLastModifiedTime(path);
            return lastModifiedTime.toMillis() >= processStartTime;
        } catch (Throwable exception) {
            LOG.warning("Failed to read file attributes", exception);
            return false;
        }
    }

    /// Resolves the process start time with the launcher start time as a defensive fallback.
    ///
    /// @return process start epoch milliseconds, or zero if neither source is available
    private long processStartTime() {
        return process.getProcess().info().startInstant().map(Instant::toEpochMilli).orElseGet(() -> {
            try {
                return ManagementFactory.getRuntimeMXBean().getStartTime();
            } catch (Throwable exception) {
                LOG.warning("Failed to get process start time", exception);
                return 0L;
            }
        });
    }

    /// Builds the platform-specific command that selects a file when one is available.
    ///
    /// @param file absolute file to reveal
    /// @return immutable command, or null when the platform has no supported selector
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
}
