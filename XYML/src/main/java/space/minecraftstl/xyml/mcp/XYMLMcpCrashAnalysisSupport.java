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
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.game.GameJavaVersion;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.game.analyzer.LogAnalyzable;
import space.minecraftstl.xyml.java.JavaManager;
import space.minecraftstl.xyml.java.JavaRuntime;
import space.minecraftstl.xyml.launch.ProcessListener;
import space.minecraftstl.xyml.setting.GameSettings;
import space.minecraftstl.xyml.util.platform.Bits;
import space.minecraftstl.xyml.util.platform.OperatingSystem;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Builds immutable XYAT inputs and combines them with legacy MCP crash-analysis results.
///
/// Repository reads and resource ownership remain in [XYMLMcpService]. This helper accepts only captured values, so the
/// longer analyzer phase cannot consult mutable repository settings after the service hands off its configuration lease.
@NotNullByDefault
final class XYMLMcpCrashAnalysisSupport {
    /// Warning returned when Java discovery has not completed and must not block crash analysis.
    static final String JAVA_DISCOVERY_PENDING_WARNING =
            "XYAT Java runtime discovery is still pending; analysis continued without selected Java metadata.";

    /// Warning returned when initialized Java metadata still cannot be collected.
    static final String JAVA_CONTEXT_UNAVAILABLE_WARNING =
            "XYAT Java runtime context is unavailable; analysis continued without selected Java metadata.";

    /// Prevents construction of this stateless helper.
    private XYMLMcpCrashAnalysisSupport() {
    }

    /// Captures a complete analysis context from values resolved under the service's configuration resources.
    ///
    /// The caller must hold the instance and launcher configuration resources used by repository settings resolution.
    ///
    /// @param repository repository owning the analyzed instance
    /// @param repositoryDirectory captured normalized repository root
    /// @param instanceDirectory captured normalized instance root
    /// @param runDirectory captured normalized effective running directory
    /// @param instanceId analyzed instance identifier
    /// @param exitType captured process exit classification
    /// @param settings effective settings resolved under configuration resources
    /// @param warnings mutable response warnings receiving non-fatal Java context failures
    /// @return immutable complete analysis context
    /// @throws InterruptedException when selected Java discovery is interrupted
    static Context contextual(
            XYMLGameRepository repository,
            Path repositoryDirectory,
            Path instanceDirectory,
            Path runDirectory,
            GameInstanceID instanceId,
            ProcessListener.ExitType exitType,
            GameSettings.Effective settings,
            List<String> warnings) throws InterruptedException {
        XYMLGameRepository checkedRepository = Objects.requireNonNull(repository, "repository");
        GameSettings.Effective checkedSettings = Objects.requireNonNull(settings, "settings");
        List<String> checkedWarnings = Objects.requireNonNull(warnings, "warnings");
        GameInstanceManifest manifest = checkedRepository.getResolvedInstanceManifest(instanceId).launchManifest();
        @Nullable String gameVersion = checkedRepository.getGameVersion(manifest).orElse(null);
        @Nullable JavaRuntime javaRuntime = null;
        if (JavaManager.isInitialized()) {
            try {
                javaRuntime = checkedSettings.getJava(
                        gameVersion == null ? null : GameVersionNumber.asGameVersion(gameVersion),
                        manifest);
            } catch (RuntimeException javaContextFailure) {
                LOG.warning(
                        "Unable to collect optional XYAT Java context for " + instanceId.id(),
                        javaContextFailure);
                checkedWarnings.add(JAVA_CONTEXT_UNAVAILABLE_WARNING);
            }
        } else {
            checkedWarnings.add(JAVA_DISCOVERY_PENDING_WARNING);
        }
        return new Context(
                repositoryDirectory,
                instanceDirectory,
                runDirectory,
                instanceId,
                manifest,
                gameVersion,
                exitType,
                javaRuntime == null ? null : javaRuntime.getBinary(),
                requiredJavaVersion(gameVersion, manifest.javaVersion()),
                javaRuntime == null ? null : javaRuntime.getParsedVersion(),
                javaRuntime == null ? Bits.UNKNOWN : javaRuntime.getBits(),
                checkedSettings.getMaxMemory());
    }

    /// Combines legacy crash-report matching with one retained XYAT analysis session.
    ///
    /// @param context immutable settings and repository snapshot
    /// @param rawLog immutable analyzed log text
    /// @param resolution resolved crash-report input
    /// @param contextWarnings warnings collected while resolving optional context
    /// @param launcherOwnedLog whether the text came from the captured instance latest-log path
    /// @param coordinator bounded XYAT analysis and repair coordinator
    /// @param missingDependencySearch optional application missing-dependency boundary
    /// @param javaRuntimeRepair optional application Java-repair boundary
    /// @param sourceValidator launcher-owned source validator, or null for supplied text
    /// @return immutable combined analysis response
    static @Unmodifiable Map<String, Object> analyze(
            Context context,
            String rawLog,
            XYMLMcpCrashReportResolver.Resolution resolution,
            @Unmodifiable List<String> contextWarnings,
            boolean launcherOwnedLog,
            XYMLMcpCrashRepairCoordinator coordinator,
            @Nullable LogAnalyzable.MissingDependencySearch missingDependencySearch,
            @Nullable LogAnalyzable.JavaRuntimeRepair javaRuntimeRepair,
            @Nullable XYMLMcpCrashRepairCoordinator.SourceValidator sourceValidator) {
        @Unmodifiable Map<String, Object> analysis = XYMLMcpCrashAnalyzer.analyze(rawLog, resolution.report());
        Map<String, Object> result = new LinkedHashMap<>(analysis);
        result.put("instance_id", context.instanceId().id());
        result.put("crash_report_source", resolution.source());
        List<String> warnings = new ArrayList<>(contextWarnings);
        warnings.addAll(resolution.warnings());
        result.put("warnings", List.copyOf(warnings));
        String fingerprint = fingerprint(rawLog);
        result.putAll(coordinator.analyze(
                context.instanceId().id(),
                launcherOwnedLog
                        ? XYMLMcpCrashRepairCoordinator.AnalysisSource.LAUNCHER_LATEST_LOG
                        : XYMLMcpCrashRepairCoordinator.AnalysisSource.PROVIDED_LOG,
                fingerprint,
                analyzerInput(context, rawLog, launcherOwnedLog, missingDependencySearch, javaRuntimeRepair),
                sourceValidator));
        return Map.copyOf(result);
    }

    /// Computes a stable SHA-256 fingerprint without retaining or exposing log contents.
    ///
    /// @param text text to fingerprint
    /// @return lowercase SHA-256 value prefixed by its algorithm
    static String fingerprint(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(Objects.requireNonNull(text, "text").getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is required by the Java platform", impossible);
        }
    }

    /// Builds contextual immutable XYAT input without consulting repository state.
    ///
    /// @param context immutable settings and repository snapshot
    /// @param rawLog immutable analyzed log text
    /// @param launcherOwnedLog whether the text came from the captured instance latest-log path
    /// @param missingDependencySearch optional application missing-dependency boundary
    /// @param javaRuntimeRepair optional application Java-repair boundary
    /// @return immutable analyzer input
    private static LogAnalyzable analyzerInput(
            Context context,
            String rawLog,
            boolean launcherOwnedLog,
            @Nullable LogAnalyzable.MissingDependencySearch missingDependencySearch,
            @Nullable LogAnalyzable.JavaRuntimeRepair javaRuntimeRepair) {
        LogAnalyzable input = new LogAnalyzable(
                context.gameVersion(),
                context.manifest() == null ? null : context.manifest().mainClass(),
                context.exitType(),
                OperatingSystem.CURRENT_OS,
                OperatingSystem.CODE_PAGE,
                context.runDirectory(),
                context.javaBinary(),
                context.requiredJavaVersion(),
                context.currentJavaVersion(),
                context.javaBits(),
                context.maxMemoryMiB(),
                rawLog.lines().toList());
        if (!launcherOwnedLog) {
            return input;
        }
        if (missingDependencySearch != null) {
            input = input.withMissingDependencySearch(missingDependencySearch);
        }
        return javaRuntimeRepair == null ? input : input.withJavaRuntimeRepair(javaRuntimeRepair);
    }

    /// Resolves the exact manifest recommendation or vanilla minimum Java version.
    ///
    /// @param gameVersion detected Minecraft version, or null when unavailable
    /// @param declaredVersion manifest-declared recommendation, or null when absent
    /// @return recommended major version, or null when it cannot be determined safely
    private static @Nullable Integer requiredJavaVersion(
            @Nullable String gameVersion,
            @Nullable GameJavaVersion declaredVersion) {
        if (declaredVersion != null) {
            return declaredVersion.majorVersion();
        }
        if (gameVersion == null) {
            return null;
        }
        @Nullable GameJavaVersion minimum = GameJavaVersion.getMinimumJavaVersion(
                GameVersionNumber.asGameVersion(gameVersion));
        return minimum == null ? null : minimum.majorVersion();
    }

    /// Immutable settings and repository snapshot passed to the independent analyzer phase.
    ///
    /// @param repositoryDirectory captured normalized repository root
    /// @param instanceDirectory captured normalized instance root
    /// @param runDirectory captured normalized effective running directory
    /// @param instanceId analyzed instance identifier
    /// @param manifest resolved launch manifest, or null when optional context collection failed
    /// @param gameVersion detected Minecraft version, or null when unavailable
    /// @param exitType captured process exit classification
    /// @param javaBinary selected Java executable, or null when unavailable
    /// @param requiredJavaVersion recommended Java major, or null when unknown
    /// @param currentJavaVersion selected Java major, or null when unknown
    /// @param javaBits selected runtime bitness
    /// @param maxMemoryMiB configured maximum heap, or null when automatic or unavailable
    @NotNullByDefault
    record Context(
            Path repositoryDirectory,
            Path instanceDirectory,
            Path runDirectory,
            GameInstanceID instanceId,
            @Nullable GameInstanceManifest manifest,
            @Nullable String gameVersion,
            ProcessListener.ExitType exitType,
            @Nullable Path javaBinary,
            @Nullable Integer requiredJavaVersion,
            @Nullable Integer currentJavaVersion,
            Bits javaBits,
            @Nullable Integer maxMemoryMiB) {
        /// Normalizes filesystem components and rejects absent required context.
        Context {
            repositoryDirectory = Objects.requireNonNull(repositoryDirectory, "repositoryDirectory")
                    .toAbsolutePath()
                    .normalize();
            instanceDirectory = Objects.requireNonNull(instanceDirectory, "instanceDirectory")
                    .toAbsolutePath()
                    .normalize();
            runDirectory = Objects.requireNonNull(runDirectory, "runDirectory").toAbsolutePath().normalize();
            instanceId = Objects.requireNonNull(instanceId, "instanceId");
            exitType = Objects.requireNonNull(exitType, "exitType");
            if (javaBinary != null) {
                javaBinary = javaBinary.toAbsolutePath().normalize();
            }
            javaBits = Objects.requireNonNull(javaBits, "javaBits");
        }

        /// Creates a log-only context after optional manifest or settings resolution fails.
        ///
        /// @param repositoryDirectory captured normalized repository root
        /// @param instanceDirectory captured normalized instance root
        /// @param runDirectory captured normalized effective running directory
        /// @param instanceId analyzed instance identifier
        /// @param exitType captured process exit classification
        /// @return immutable degraded analysis context
        static Context basic(
                Path repositoryDirectory,
                Path instanceDirectory,
                Path runDirectory,
                GameInstanceID instanceId,
                ProcessListener.ExitType exitType) {
            return new Context(
                    repositoryDirectory,
                    instanceDirectory,
                    runDirectory,
                    instanceId,
                    null,
                    null,
                    exitType,
                    null,
                    null,
                    null,
                    Bits.UNKNOWN,
                    null);
        }
    }
}
