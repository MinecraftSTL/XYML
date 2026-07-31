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
import space.minecraftstl.xyml.Metadata;
import space.minecraftstl.xyml.download.LibraryAnalyzer;
import space.minecraftstl.xyml.game.DefaultGameRepository;
import space.minecraftstl.xyml.game.LaunchOptions;
import space.minecraftstl.xyml.game.Log;
import space.minecraftstl.xyml.game.Version;
import space.minecraftstl.xyml.launch.ProcessListener;
import space.minecraftstl.xyml.util.Lang;
import space.minecraftstl.xyml.util.platform.Architecture;
import space.minecraftstl.xyml.util.platform.OperatingSystem;
import space.minecraftstl.xyml.util.platform.SystemInfo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static space.minecraftstl.xyml.util.DataSizeUnit.MEGABYTES;
import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Immutable data displayed and analyzed by one Swing game-crash window.
@NotNullByDefault
final class GameCrashWindowModel {
    /// Classified process-exit outcome used for the window headline.
    private final ProcessListener.ExitType exitType;

    /// Ordered immutable environment details shown beside the diagnosis.
    private final @Unmodifiable List<Detail> details;

    /// Immutable snapshot of captured process-output entries.
    private final @Unmodifiable List<Log> capturedLogs;

    /// Path to the on-disk log written by the launched game.
    private final Path latestLog;

    /// Creates an immutable game-crash window model.
    ///
    /// @param exitType classified process-exit outcome
    /// @param details ordered environment details
    /// @param capturedLogs captured in-memory process-output entries
    /// @param latestLog path to the launched instance's latest log
    GameCrashWindowModel(
            ProcessListener.ExitType exitType,
            List<Detail> details,
            List<Log> capturedLogs,
            Path latestLog) {
        this.exitType = Objects.requireNonNull(exitType, "exitType");
        this.details = List.copyOf(Objects.requireNonNull(details, "details"));
        this.capturedLogs = List.copyOf(Objects.requireNonNull(capturedLogs, "capturedLogs"));
        this.latestLog = Objects.requireNonNull(latestLog, "latestLog");
    }

    /// Builds the production model from one completed launch.
    ///
    /// @param exitType classified process-exit outcome
    /// @param repository repository owning the launched instance
    /// @param version launched version
    /// @param launchOptions resolved launch configuration
    /// @param capturedLogs captured in-memory process-output entries
    /// @return immutable display and analysis model
    static GameCrashWindowModel fromLaunch(
            ProcessListener.ExitType exitType,
            DefaultGameRepository repository,
            Version version,
            LaunchOptions launchOptions,
            List<Log> capturedLogs) {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(launchOptions, "launchOptions");

        List<Detail> details = new ArrayList<>();
        details.add(new Detail(i18n("launcher"), Metadata.VERSION));
        details.add(new Detail(i18n("game.instance"), version.getId()));
        details.add(new Detail(
                i18n("settings.physical_memory"),
                MEGABYTES.formatBytes(SystemInfo.getTotalMemorySize())));
        details.add(new Detail(
                i18n("settings.memory"),
                Optional.ofNullable(launchOptions.getMaxMemory())
                        .map(memory -> memory + " " + i18n("settings.memory.unit.mib"))
                        .orElse("-")));
        details.add(new Detail("Java", javaDescription(launchOptions)));
        details.add(new Detail(
                i18n("system.operating_system"),
                Lang.requireNonNullElse(OperatingSystem.OS_RELEASE_NAME, OperatingSystem.SYSTEM_NAME)));
        details.add(new Detail(i18n("system.architecture"), Architecture.SYSTEM_ARCH.getDisplayName()));

        @Nullable String gameVersion = repository.getGameVersion(version).orElse(null);
        LibraryAnalyzer analyzer = LibraryAnalyzer.analyze(version, gameVersion);
        for (LibraryAnalyzer.LibraryType type : LibraryAnalyzer.LibraryType.values()) {
            if (!type.getPatchId().isEmpty()) {
                analyzer.getVersion(type).ifPresent(loaderVersion -> details.add(new Detail(
                        i18n("install.installer." + type.getPatchId()),
                        loaderVersion)));
            }
        }

        details.add(new Detail(
                i18n("game.directory"),
                launchOptions.getGameDir().toAbsolutePath().toString()));
        details.add(new Detail(
                i18n("settings.game.java_directory"),
                launchOptions.getJava().getBinary().toAbsolutePath().toString()));

        Path latestLog = repository.getRunDirectory(version.getId()).resolve("logs/latest.log");
        return new GameCrashWindowModel(exitType, details, capturedLogs, latestLog);
    }

    /// Returns the classified process-exit outcome.
    ///
    /// @return exit type used for the headline
    ProcessListener.ExitType exitType() {
        return exitType;
    }

    /// Returns ordered immutable environment details.
    ///
    /// @return environment detail snapshot
    @Unmodifiable List<Detail> details() {
        return details;
    }

    /// Returns the immutable captured process-log snapshot.
    ///
    /// @return captured logs in arrival order
    @Unmodifiable List<Log> capturedLogs() {
        return capturedLogs;
    }

    /// Returns the instance's latest-log path.
    ///
    /// @return latest-log path
    Path latestLog() {
        return latestLog;
    }

    /// Formats the configured Java version and its non-native architecture.
    ///
    /// @param launchOptions resolved launch configuration
    /// @return Java version suitable for compact display
    private static String javaDescription(LaunchOptions launchOptions) {
        Architecture architecture = launchOptions.getJava().getArchitecture();
        if (architecture == Architecture.SYSTEM_ARCH) {
            return launchOptions.getJava().getVersion();
        }
        return launchOptions.getJava().getVersion() + " (" + architecture.getDisplayName() + ")";
    }

    /// One localized label and selectable environment value.
    ///
    /// @param label localized detail label
    /// @param value detail value captured at window creation
    @NotNullByDefault
    record Detail(String label, String value) {
        /// Validates one detail pair.
        Detail {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(value, "value");
        }
    }
}
