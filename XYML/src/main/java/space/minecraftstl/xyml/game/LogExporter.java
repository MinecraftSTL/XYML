/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.game;

import kala.encdet.EncodingDetector;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.util.io.Zipper;
import space.minecraftstl.xyml.util.logging.Logger;
import space.minecraftstl.xyml.util.platform.OperatingSystem;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Exports launcher and game diagnostics without exposing forbidden authentication tokens.
@NotNullByDefault
public final class LogExporter {
    /// Prevents instantiation of the stateless exporter.
    private LogExporter() {
    }

    /// Exports launcher logs and the target instance's resolved version-manifest chain.
    ///
    /// @param zipFile destination archive
    /// @param gameRepository repository containing the target instance
    /// @param instanceId target installed instance identifier
    /// @param logs Minecraft process log text
    /// @param launchScript launch command text with sensitive tokens filtered before export
    /// @param logMatcher optional matcher used to select files from each log directory
    /// @return future completed after the archive has been written
    public static CompletableFuture<Void> exportLogs(
            Path zipFile, DefaultGameRepository repository, GameInstanceID instanceId, String logs, String launchScript,
            @Nullable PathMatcher logMatcher) {
        Path runDirectory = repository.getRunDirectory(instanceId);
        List<GameInstanceID> instances = new ArrayList<>();

        GameInstanceID currentInstanceId = instanceId;
        HashSet<GameInstanceID> resolvedSoFar = new HashSet<>();
        while (true) {
            if (resolvedSoFar.contains(currentInstanceId)) break;
            resolvedSoFar.add(currentInstanceId);
            GameInstanceManifest currentManifest = repository.getInstanceManifest(currentInstanceId);
            instances.add(currentInstanceId);

            if (currentManifest.inheritsFrom() != null) {
                currentInstanceId = currentManifest.inheritsFrom();
            } else {
                break;
            }
        }

        return CompletableFuture.runAsync(() -> {
            try (Zipper zipper = new Zipper(zipFile, true)) {
                processLogs(runDirectory.resolve("liteconfig"), "*.log", "liteconfig", zipper, logMatcher);
                processLogs(runDirectory.resolve("logs"), "*.log", "logs", zipper, logMatcher);
                processLogs(runDirectory, "*.log", "runDirectory", zipper, logMatcher);
                processLogs(runDirectory.resolve("crash-reports"), "*.txt", "crash-reports", zipper, logMatcher);

                zipper.putTextFile(LOG.getLogs(), "xyml.log");
                zipper.putTextFile(logs, "minecraft.log");
                zipper.putTextFile(Logger.filterForbiddenToken(launchScript), OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS ? "launch.bat" : "launch.sh");

                for (GameInstanceID id : instances) {
                    Path instanceJson = repository.getInstanceJson(id);
                    if (Files.exists(instanceJson)) {
                        zipper.putFile(instanceJson, id + ".json");
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /// Adds matching log files from one directory to the target archive.
    private static void processLogs(
            Path directory, String fileExtension, String logDirectory, Zipper zipper, @Nullable PathMatcher logMatcher) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, fileExtension)) {
            for (Path file : stream) {
                if (Files.isRegularFile(file)) {
                    if (logMatcher == null || logMatcher.matches(file)) {
                        try (BufferedReader reader = EncodingDetector.MODERN_WEB.newBufferedReader(file)) {
                            zipper.putLines(reader.lines().map(Logger::filterForbiddenToken), file.getFileName().toString());
                        } catch (IOException e) {
                            LOG.warning("Failed to read log file: " + file, e);
                        }
                    }
                }
            }
        } catch (Throwable e) {
            LOG.warning("Failed to find any log on " + logDirectory, e);
        }
    }
}
