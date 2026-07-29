/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.upgrade;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.Metadata;
import space.minecraftstl.xyml.java.JavaRuntime;
import space.minecraftstl.xyml.util.io.JarUtils;
import space.minecraftstl.xyml.util.platform.OperatingSystem;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Applies launcher artifacts and starts replacement or restarted Java processes without depending on a UI toolkit.
///
/// Presentation is deliberately excluded: startup callers receive a semantic [UpdateStartupResult], while the
/// legacy interactive updater can reuse the same verified request and process-launch operations.
@NotNullByDefault
public final class UpdateApplier {
    /// Launcher artifact naming pattern that can be updated to the current version after replacement.
    private static final Pattern VERSIONED_FILENAME = Pattern.compile(
            "^(?<prefix>[xX][yY][mM][lL][.-])(?<version>\\d+(?:\\.\\d+)*)(?<suffix>\\.[^.]+)$");

    /// Prevents construction of the update utility.
    private UpdateApplier() {
    }

    /// Processes `--apply-to` and post-upgrade startup conditions.
    ///
    /// This method performs filesystem and process operations but never creates or accesses desktop UI objects.
    ///
    /// @param args launcher command-line arguments treated as immutable
    /// @return semantic result for the launcher entry point
    public static UpdateStartupResult processArguments(String @Unmodifiable [] args) {
        Objects.requireNonNull(args, "args");

        Optional<Path> applyTarget = findApplyTarget(args);
        if (applyTarget.isPresent()) {
            if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS
                    && !OperatingSystem.isWindows7OrLater()) {
                return UpdateStartupResult.exitWithNotice(
                        UpdateStartupResult.Notice.UNSUPPORTED_WINDOWS_VERSION);
            }

            try {
                applyUpdate(applyTarget.get());
                return UpdateStartupResult.exit();
            } catch (IOException exception) {
                LOG.warning("Failed to apply update", exception);
                return UpdateStartupResult.failed(exception);
            }
        }

        if (isFirstLaunchAfterUpgrade()) {
            return UpdateStartupResult.exitWithNotice(
                    UpdateStartupResult.Notice.MANUAL_REBOOT_REQUIRED);
        }

        return UpdateStartupResult.continueLaunch();
    }

    /// Verifies a downloaded launcher and starts it with an instruction to replace the current artifact.
    ///
    /// @param updateTo downloaded replacement launcher
    /// @param currentArtifact artifact that the replacement process must overwrite
    /// @throws IOException when verification or process startup fails
    public static void requestUpdate(Path updateTo, Path currentArtifact) throws IOException {
        Objects.requireNonNull(updateTo, "updateTo");
        Objects.requireNonNull(currentArtifact, "currentArtifact");
        requirePortableJarMode("in-place launcher update");
        if (!IntegrityChecker.DISABLE_SELF_INTEGRITY_CHECK) {
            IntegrityChecker.verifyJar(updateTo);
        }
        startJava(updateTo, List.of("--apply-to", currentArtifact.toString()));
    }

    /// Starts a launcher JAR with no application arguments.
    ///
    /// @param jar launcher JAR to start
    /// @throws IOException when process startup fails
    public static void startJava(Path jar) throws IOException {
        startJava(jar, List.of());
    }

    /// Starts a launcher JAR while preserving supported VM input arguments from the current process.
    ///
    /// @param jar launcher JAR to start
    /// @param appArguments immutable application arguments appended after the JAR path
    /// @throws IOException when process startup fails
    public static void startJava(Path jar, @Unmodifiable List<String> appArguments) throws IOException {
        Objects.requireNonNull(jar, "jar");
        Objects.requireNonNull(appArguments, "appArguments");
        requirePortableJarMode("JAR process launch");
        @Unmodifiable List<String> commandLine = buildJavaCommand(
                JavaRuntime.getDefault().getBinary(),
                currentRuntimeInputArguments(),
                jar,
                appArguments);
        LOG.info("Starting process: " + commandLine);
        new ProcessBuilder(commandLine)
                .directory(Paths.get("").toAbsolutePath().toFile())
                .inheritIO()
                .start();
    }

    /// Restarts the OS-native launcher executable supplied by jpackage.
    ///
    /// @throws IOException when this is not a packaged runtime, the launcher path is unavailable, or startup fails
    public static void startPackagedApplication() throws IOException {
        if (!Metadata.PACKAGED) {
            throw new IOException("Native application restart requires a packaged launcher runtime");
        }
        Path launcher = packagedApplicationPath(System.getProperty("jpackage.app-path"))
                .orElseThrow(() -> new IOException("Packaged application launcher path is unavailable"));
        LOG.info("Starting packaged application: " + launcher);
        @Nullable Path parent = launcher.getParent();
        ProcessBuilder builder = new ProcessBuilder(launcher.toString()).inheritIO();
        if (parent != null) {
            builder.directory(parent.toFile());
        }
        builder.start();
    }

    /// Resolves the currently running launcher artifact.
    ///
    /// @return current launcher JAR path
    /// @throws IOException when the runtime location is unavailable
    public static Path currentApplicationLocation() throws IOException {
        requirePortableJarMode("current JAR lookup");
        @Nullable Path path = JarUtils.thisJarPath();
        if (path == null) {
            throw new IOException("Failed to find current XYML location");
        }
        return path;
    }

    /// Normalizes the native launcher path published by jpackage.
    ///
    /// @param configuredPath system-property value, or null when unavailable
    /// @return normalized absolute path when non-blank
    static Optional<Path> packagedApplicationPath(@Nullable String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Path.of(configuredPath).toAbsolutePath().normalize());
    }

    /// Recognizes the exact update-application argument shape.
    ///
    /// @param args launcher command-line arguments
    /// @return replacement target when the arguments are exactly `--apply-to <path>`
    static Optional<Path> findApplyTarget(String @Unmodifiable [] args) {
        Objects.requireNonNull(args, "args");
        if (args.length == 2 && "--apply-to".equals(args[0])) {
            return Optional.of(Paths.get(args[1]));
        }
        return Optional.empty();
    }

    /// Builds the immutable child Java command without starting a process.
    ///
    /// Only `-D` and `-X` VM input arguments are retained, matching the established restart behavior.
    ///
    /// @param javaBinary Java executable
    /// @param runtimeInputArguments immutable current-process VM arguments
    /// @param jar launcher JAR to start
    /// @param appArguments immutable application arguments
    /// @return immutable command line suitable for [ProcessBuilder]
    static @Unmodifiable List<String> buildJavaCommand(
            Path javaBinary,
            @Unmodifiable List<String> runtimeInputArguments,
            Path jar,
            @Unmodifiable List<String> appArguments) {
        Objects.requireNonNull(javaBinary, "javaBinary");
        Objects.requireNonNull(runtimeInputArguments, "runtimeInputArguments");
        Objects.requireNonNull(jar, "jar");
        Objects.requireNonNull(appArguments, "appArguments");

        List<String> commandLine = new ArrayList<>();
        commandLine.add(javaBinary.toString());
        for (String inputArgument : runtimeInputArguments) {
            if (inputArgument.startsWith("-D") || inputArgument.startsWith("-X")) {
                commandLine.add(inputArgument);
            }
        }
        commandLine.add("-jar");
        commandLine.add(jar.toAbsolutePath().toString());
        commandLine.addAll(appArguments);
        return List.copyOf(commandLine);
    }

    /// Determines the versioned filename to use after replacing an artifact.
    ///
    /// @param path replacement target
    /// @param newVersion current launcher version
    /// @return sibling path with the new version, or empty when no rename is required
    static Optional<Path> tryRename(Path path, String newVersion) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(newVersion, "newVersion");
        String filename = path.getFileName().toString();
        Matcher matcher = VERSIONED_FILENAME.matcher(filename);
        if (matcher.find()) {
            String newFilename = matcher.group("prefix") + newVersion + matcher.group("suffix");
            if (!newFilename.equals(filename)) {
                return Optional.of(path.resolveSibling(newFilename));
            }
        }
        return Optional.empty();
    }

    /// Applies the current process artifact over a requested target and starts the replaced launcher.
    ///
    /// @param initialTarget artifact to replace
    /// @throws IOException when verification, replacement, or process startup fails
    private static void applyUpdate(Path initialTarget) throws IOException {
        requirePortableJarMode("in-place launcher update");
        LOG.info("Applying update to " + initialTarget);

        Path target = initialTarget;
        Path self = currentApplicationLocation();
        if (!IntegrityChecker.DISABLE_SELF_INTEGRITY_CHECK && !IntegrityChecker.isSelfVerified()) {
            throw new IOException("Self verification failed");
        }
        ExecutableHeaderHelper.copyWithHeader(self, target);

        Optional<Path> newFilename = tryRename(target, Metadata.VERSION);
        if (newFilename.isPresent()) {
            LOG.info("Move " + target + " to " + newFilename.get());
            try {
                Files.move(target, newFilename.get());
                target = newFilename.get();
            } catch (IOException exception) {
                LOG.warning("Failed to move target", exception);
            }
        }

        startJava(target);
    }

    /// Detects the fixed update location that requires a manual reboot.
    ///
    /// @return `true` when the current artifact is the fixed versioned update path
    private static boolean isFirstLaunchAfterUpgrade() {
        @Nullable Path currentPath = JarUtils.thisJarPath();
        if (currentPath == null) {
            return false;
        }
        Path updated = Metadata.XYML_USER_HOME.resolve("XYML-" + Metadata.VERSION + ".jar");
        return currentPath.equals(updated.toAbsolutePath());
    }

    /// Captures current VM input arguments with a system-property fallback for restricted runtimes.
    ///
    /// @return immutable candidate VM arguments
    private static @Unmodifiable List<String> currentRuntimeInputArguments() {
        try {
            return List.copyOf(ManagementFactory.getRuntimeMXBean().getInputArguments());
        } catch (Throwable ignored) {
            List<String> arguments = new ArrayList<>();
            for (Map.Entry<Object, Object> entry : System.getProperties().entrySet()) {
                if (entry.getKey() instanceof String key && key.startsWith("xyml.")) {
                    arguments.add("-D" + key + "=" + entry.getValue());
                }
            }
            return List.copyOf(arguments);
        }
    }

    /// Rejects single-JAR replacement and restart operations inside an immutable application image.
    ///
    /// @param operation operation being attempted
    /// @throws IOException when the launcher is running from jpackage
    private static void requirePortableJarMode(String operation) throws IOException {
        if (Metadata.PACKAGED) {
            throw new IOException(operation + " is unavailable for packaged applications");
        }
    }
}
