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
package space.minecraftstl.xyml.ai;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.addon.mod.LocalModFile;
import space.minecraftstl.xyml.addon.mod.ModManager;
import space.minecraftstl.xyml.auth.AuthInfo;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.game.LaunchOptions;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.java.JavaManager;
import space.minecraftstl.xyml.java.JavaRuntime;
import space.minecraftstl.xyml.launch.DefaultLauncher;
import space.minecraftstl.xyml.launch.ProcessListener;
import space.minecraftstl.xyml.setting.GameSettings;
import space.minecraftstl.xyml.setting.GameWindowType;
import space.minecraftstl.xyml.setting.JavaVersionType;
import space.minecraftstl.xyml.setting.property.InheritableProperty;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/// Bridges the existing XYMLCore launcher services to MCP-safe structured operations.
///
/// This class deliberately contains no new game or mod-management algorithms. It delegates to the
/// repository, mod manager, Java manager, and launch monitor already used by XYML.
@NotNullByDefault
public final class XYMLMcpService implements XYMLMcpOperations {

    /// Maximum number of lines returned by one log request.
    private static final int MAX_LOG_LINES = 20_000;

    /// URI matcher for a latest-log resource.
    private static final Pattern LOG_RESOURCE = Pattern.compile(
            "^xyml://instances/([^/]+)/logs/latest\\.log$");

    /// URI matcher for one crash-report resource.
    private static final Pattern CRASH_RESOURCE = Pattern.compile(
            "^xyml://instances/([^/]+)/crash-reports/$");

    /// URI matcher for an individual crash-report resource.
    private static final Pattern CRASH_REPORT_RESOURCE = Pattern.compile(
            "^xyml://instances/([^/]+)/crash-reports/([^/]+)$");

    /// Repository exposed by this server process.
    private final XYMLGameRepository repository;

    /// Last known process state for each instance.
    private final Map<GameInstanceID, LaunchState> launchStates = new ConcurrentHashMap<>();

    /// Creates a service for one initialized XYML game repository.
    ///
    /// @param repository repository whose instances and settings are exposed
    public XYMLMcpService(XYMLGameRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /// Returns all installed instances and their root directories.
    ///
    /// @return immutable instance summaries
    @Override
    public @Unmodifiable List<Map<String, Object>> listInstances() {
        repository.refresh();
        List<Map<String, Object>> result = new ArrayList<>();
        for (GameInstanceManifest manifest : repository.getInstanceManifests()) {
            result.add(Map.of(
                    "id", manifest.id().id(),
                    "root", repository.getInstanceRoot(manifest.id()).toAbsolutePath().normalize().toString(),
                    "game_version", repository.getGameVersion(manifest).orElse("")));
        }
        return List.copyOf(result);
    }

    /// Returns the effective settings needed to diagnose or adjust one instance.
    ///
    /// @param instanceId instance identifier
    /// @return immutable effective-settings map
    @Override
    public @Unmodifiable Map<String, Object> getInstanceSettings(String instanceId) {
        GameInstanceID id = id(instanceId);
        GameSettings.Effective effective = repository.getEffectiveGameSettings(id);
        return Map.of(
                "instance_id", id.id(),
                "java_type", effective.getInheritable(GameSettings::javaTypeProperty).name(),
                "java_version", effective.getInheritable(GameSettings::customJavaVersionProperty),
                "java_path", effective.getInheritable(GameSettings::customJavaPathProperty),
                "min_memory_mb", nullableNumber(effective.getInheritable(GameSettings::minMemoryProperty)),
                "max_memory_mb", effective.getMaxMemory(),
                "jvm_options", effective.getInheritable(GameSettings::jvmOptionsProperty),
                "width", effective.getWidth(),
                "height", effective.getHeight(),
                "window_type", effective.getInheritable(GameSettings::windowTypeProperty).name());
    }

    /// Returns the absolute mods directory for an instance.
    ///
    /// @param instanceId instance identifier
    /// @return absolute mods directory
    @Override
    public String getModsDirectory(String instanceId) {
        return repository.getModsDirectory(id(instanceId)).toAbsolutePath().normalize().toString();
    }

    /// Reads the tail of the latest game log.
    ///
    /// @param instanceId instance identifier
    /// @param requestedLines requested number of lines
    /// @return log metadata and text
    @Override
    public @Unmodifiable Map<String, Object> getLogs(String instanceId, int requestedLines) throws IOException {
        GameInstanceID id = id(instanceId);
        Path file = latestLog(id);
        int lines = Math.max(1, Math.min(MAX_LOG_LINES, requestedLines));
        List<String> all = Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
        int from = Math.max(0, all.size() - lines);
        return Map.of(
                "instance_id", id.id(),
                "path", file.toAbsolutePath().normalize().toString(),
                "exists", Files.isRegularFile(file),
                "line_count", all.size() - from,
                "text", String.join("\n", all.subList(from, all.size())));
    }

    /// Analyzes a supplied log or the latest instance log with CrashReportAnalyzer.
    ///
    /// @param instanceId instance identifier
    /// @param logText optional raw log text
    /// @param crashReportPath optional crash-report file path
    /// @return structured rule matches and extracted crash report
    @Override
    public @Unmodifiable Map<String, Object> analyzeCrash(
            String instanceId,
            @Nullable String logText,
            @Nullable String crashReportPath) throws IOException {
        GameInstanceID id = id(instanceId);
        String rawLog = logText != null ? logText : readLog(id);
        @Nullable String report = crashReportPath == null || crashReportPath.isBlank()
                ? null
                : readCrashReport(id, crashReportPath);
        Map<String, Object> analysis = XYMLMcpCrashAnalyzer.analyze(rawLog, report);
        Map<String, Object> result = new LinkedHashMap<>(analysis);
        result.put("instance_id", id.id());
        return Map.copyOf(result);
    }

    /// Analyzes log text without requiring an initialized game repository.
    ///
    /// This overload is useful for offline diagnostics and tests that only need the existing
    /// CrashReportAnalyzer rules.
    ///
    /// @param logText raw log text
    /// @return structured rule matches and extracted crash report
    public static @Unmodifiable Map<String, Object> analyzeCrashText(String logText) {
        return XYMLMcpCrashAnalyzer.analyze(logText, null);
    }

    /// Lists Java runtimes already discovered by JavaManager.
    ///
    /// @return immutable runtime summaries
    @Override
    public @Unmodifiable List<Map<String, Object>> listJavaRuntimes() throws InterruptedException {
        List<Map<String, Object>> result = new ArrayList<>();
        for (JavaRuntime runtime : JavaManager.getAllJava()) {
            result.add(Map.of(
                    "path", runtime.getBinary().toAbsolutePath().normalize().toString(),
                    "version", runtime.getVersion(),
                    "major", runtime.getParsedVersion(),
                    "bits", runtime.getBits().name(),
                    "managed", runtime.isManaged(),
                    "jdk", runtime.isJDK()));
        }
        return List.copyOf(result);
    }

    /// Lists locally installed mods and their enabled state.
    ///
    /// @param instanceId instance identifier
    /// @return immutable mod summaries
    @Override
    public @Unmodifiable List<Map<String, Object>> listLocalMods(String instanceId) throws IOException {
        ModManager manager = repository.getModManager(id(instanceId));
        List<Map<String, Object>> result = new ArrayList<>();
        for (LocalModFile mod : manager.getLocalFiles()) {
            result.add(Map.of(
                    "id", mod.getId(),
                    "name", mod.getName(),
                    "version", mod.getVersion(),
                    "path", mod.getFile().toAbsolutePath().normalize().toString(),
                    "enabled", mod.isActive(),
                    "loader", mod.getModLoaderType().name()));
        }
        return List.copyOf(result);
    }

    /// Changes the Java selection to a numeric version or executable path.
    ///
    /// @param instanceId instance identifier
    /// @param javaVersion numeric Java version, or blank when `javaPath` is used
    /// @param javaPath executable path, or blank when `javaVersion` is used
    /// @return resulting effective settings
    @Override
    public @Unmodifiable Map<String, Object> setJavaVersion(
            String instanceId, @Nullable String javaVersion, @Nullable String javaPath) {
        GameInstanceID id = id(instanceId);
        GameSettings.Instance setting = writableSettings(id);
        if (javaPath != null && !javaPath.isBlank()) {
            setting.getOverrideProperties().add(GameSettings.PROPERTY_JAVA_TYPE);
            setting.javaTypeProperty().setValue(JavaVersionType.CUSTOM);
            setting.getOverrideProperties().add(GameSettings.PROPERTY_CUSTOM_JAVA_PATH);
            setting.customJavaPathProperty().setValue(Path.of(javaPath).toAbsolutePath().normalize().toString());
        } else {
            int major = parsePositive(javaVersion, "javaVersion");
            setting.getOverrideProperties().add(GameSettings.PROPERTY_JAVA_TYPE);
            setting.javaTypeProperty().setValue(JavaVersionType.VERSION);
            setting.getOverrideProperties().add(GameSettings.PROPERTY_CUSTOM_JAVA_VERSION);
            setting.customJavaVersionProperty().setValue(Integer.toString(major));
        }
        repository.saveGameSettings(id);
        return getInstanceSettings(instanceId);
    }

    /// Changes minimum and maximum heap memory in MiB.
    ///
    /// @param instanceId instance identifier
    /// @param minMemory minimum heap, or null to inherit
    /// @param maxMemory maximum heap, or null to inherit
    /// @return resulting effective settings
    @Override
    public @Unmodifiable Map<String, Object> setMemory(
            String instanceId, @Nullable Integer minMemory, @Nullable Integer maxMemory) {
        if (minMemory != null && minMemory < 0 || maxMemory != null && maxMemory <= 0) {
            throw new IllegalArgumentException("Memory values must be positive (minimum may be zero)");
        }
        if (minMemory != null && maxMemory != null && minMemory > maxMemory) {
            throw new IllegalArgumentException("minMemory must not exceed maxMemory");
        }
        GameInstanceID id = id(instanceId);
        GameSettings.Instance setting = writableSettings(id);
        applyMemoryOverride(setting, GameSettings.PROPERTY_MIN_MEMORY, setting.minMemoryProperty(), minMemory);
        applyMemoryOverride(setting, GameSettings.PROPERTY_MAX_MEMORY, setting.maxMemoryProperty(), maxMemory);
        repository.saveGameSettings(id);
        return getInstanceSettings(instanceId);
    }

    /// Applies one heap setting value while preserving the instance inheritance contract.
    ///
    /// A null value removes the instance override; it does not create an override whose value is
    /// null, because effective settings resolve null direct values to their property defaults.
    ///
    /// @param setting instance settings to update
    /// @param propertyName serialized override-property name
    /// @param property property receiving a non-null override value
    /// @param value requested value, or null to inherit
    static void applyMemoryOverride(
            GameSettings.Instance setting,
            String propertyName,
            InheritableProperty<@Nullable Integer> property,
            @Nullable Integer value) {
        if (value == null) {
            setting.getOverrideProperties().remove(propertyName);
        } else {
            setting.getOverrideProperties().add(propertyName);
            property.setValue(value);
        }
    }

    /// Changes the raw JVM options string.
    ///
    /// @param instanceId instance identifier
    /// @param options JVM options string
    /// @return resulting effective settings
    @Override
    public @Unmodifiable Map<String, Object> setJvmOptions(String instanceId, String options) {
        GameInstanceID id = id(instanceId);
        GameSettings.Instance setting = writableSettings(id);
        setting.getOverrideProperties().add(GameSettings.PROPERTY_JVM_OPTIONS);
        setting.jvmOptionsProperty().setValue(Objects.requireNonNull(options));
        repository.saveGameSettings(id);
        return getInstanceSettings(instanceId);
    }

    /// Changes window dimensions and fullscreen state.
    ///
    /// @param instanceId instance identifier
    /// @param width optional width
    /// @param height optional height
    /// @param fullscreen optional fullscreen state
    /// @return resulting effective settings
    @Override
    public @Unmodifiable Map<String, Object> setWindowOptions(
            String instanceId, @Nullable Integer width, @Nullable Integer height, @Nullable Boolean fullscreen) {
        if (width != null && width < 0 || height != null && height < 0) {
            throw new IllegalArgumentException("Window dimensions must not be negative");
        }
        GameInstanceID id = id(instanceId);
        GameSettings.Instance setting = writableSettings(id);
        if (width != null) {
            setting.getOverrideProperties().add(GameSettings.PROPERTY_WIDTH);
            setting.widthProperty().setValue(width.doubleValue());
        }
        if (height != null) {
            setting.getOverrideProperties().add(GameSettings.PROPERTY_HEIGHT);
            setting.heightProperty().setValue(height.doubleValue());
        }
        if (fullscreen != null) {
            setting.getOverrideProperties().add(GameSettings.PROPERTY_WINDOW_TYPE);
            setting.windowTypeProperty().setValue(fullscreen ? GameWindowType.FULLSCREEN : GameWindowType.WINDOWED);
        }
        repository.saveGameSettings(id);
        return getInstanceSettings(instanceId);
    }

    /// Enables one mod file through ModManager's `.disabled` transition.
    ///
    /// @param instanceId target instance
    /// @param path mod file path
    /// @return resulting file path
    @Override
    public String enableMod(String instanceId, String path) throws IOException {
        return transitionMod(id(instanceId), path, true).toString();
    }

    /// Disables one mod file through ModManager's `.disabled` transition.
    ///
    /// @param instanceId target instance
    /// @param path mod file path
    /// @return resulting file path
    @Override
    public String disableMod(String instanceId, String path) throws IOException {
        return transitionMod(id(instanceId), path, false).toString();
    }

    /// Removes selected local mod files after the caller has confirmed the high-impact operation.
    ///
    /// @param instanceId target instance
    /// @param paths files to remove
    /// @return removed paths
    @Override
    public @Unmodifiable List<String> removeMods(String instanceId, List<String> paths) throws IOException {
        GameInstanceID id = id(instanceId);
        ModManager manager = repository.getModManager(id);
        Path modsRoot = manager.getDirectory().toAbsolutePath().normalize();
        List<LocalModFile> selected = new ArrayList<>();
        for (String rawPath : paths) {
            Path expected = Path.of(rawPath).toAbsolutePath().normalize();
            if (!expected.startsWith(modsRoot)) {
                throw new IOException("Mod path is outside the instance mods directory: " + expected);
            }
            for (LocalModFile mod : manager.getLocalFiles()) {
                if (mod.getFile().toAbsolutePath().normalize().equals(expected)) {
                    selected.add(mod);
                    break;
                }
            }
        }
        manager.removeMods(selected.toArray(LocalModFile[]::new));
        return selected.stream().map(mod -> mod.getFile().toAbsolutePath().normalize().toString()).toList();
    }

    /// Starts an instance with the launcher-generated options and captures monitor state.
    ///
    /// @param instanceId target instance
    /// @return launch acceptance and process metadata
    @Override
    public @Unmodifiable Map<String, Object> launchGame(String instanceId) throws Exception {
        GameInstanceID id = id(instanceId);
        GameInstanceManifest manifest = repository.getResolvedInstanceManifest(id).launchManifest();
        JavaRuntime java = repository.getEffectiveGameSettings(id).getJava(
                repository.getGameVersion(id).map(space.minecraftstl.xyml.util.versioning.GameVersionNumber::asGameVersion).orElse(null),
                manifest);
        if (java == null) {
            throw new IllegalStateException("No compatible Java runtime was found");
        }
        LaunchOptions options = repository.getLaunchOptions(
                id, java, repository.getRunDirectory(id), List.of(), List.of(), false)
                .setDaemon(true)
                .create();
        LaunchState state = new LaunchState();
        launchStates.put(id, state);
        DefaultLauncher launcher = new DefaultLauncher(repository, manifest,
                offlineAuth(id), options, state, true);
        state.process = launcher.launch();
        return Map.of("instance_id", id.id(), "started", true,
                "running", state.process.isRunning());
    }

    /// Stops a running instance process if one is tracked.
    ///
    /// @param instanceId target instance
    /// @return whether a process was stopped
    @Override
    public @Unmodifiable Map<String, Object> stopGame(String instanceId) {
        GameInstanceID id = id(instanceId);
        LaunchState state = launchStates.get(id);
        if (state == null || state.process == null || !state.process.isRunning()) {
            return Map.of("instance_id", id.id(), "stopped", false);
        }
        state.process.stop();
        return Map.of("instance_id", id.id(), "stopped", true);
    }

    /// Returns current process state, exit code, and ExitType classification.
    ///
    /// @param instanceId target instance
    /// @return immutable launch state
    @Override
    public @Unmodifiable Map<String, Object> getLaunchStatus(String instanceId) {
        GameInstanceID id = id(instanceId);
        LaunchState state = launchStates.get(id);
        if (state == null) {
            return Map.of("instance_id", id.id(), "started", false, "running", false,
                    "exit_code", nullValue(), "exit_type", nullValue(), "logs", List.of());
        }
        @Nullable Integer exitCode = state.exitCode;
        @Nullable ProcessListener.ExitType exitType = state.exitType;
        return Map.of("instance_id", id.id(), "started", true,
                "running", state.process != null && state.process.isRunning(),
                "exit_code", exitCode == null ? nullValue() : exitCode,
                "exit_type", exitType == null ? nullValue() : exitType.name(),
                "logs", state.logsSnapshot());
    }

    /// Reads a supported `xyml://` resource URI.
    ///
    /// @param uri resource URI
    /// @return resource URI, MIME type, and text
    @Override
    public @Unmodifiable Map<String, String> readResource(String uri) throws IOException {
        Matcher logMatcher = LOG_RESOURCE.matcher(uri);
        if (logMatcher.matches()) {
            GameInstanceID id = id(decodeSegment(logMatcher.group(1)));
            Path path = latestLog(id);
            return Map.of("uri", uri, "mime_type", "text/plain", "text", readIfPresent(path));
        }
        Matcher crashMatcher = CRASH_RESOURCE.matcher(uri);
        if (crashMatcher.matches()) {
            GameInstanceID id = id(decodeSegment(crashMatcher.group(1)));
            return Map.of("uri", uri, "mime_type", "text/uri-list",
                    "text", listCrashReportUris(id));
        }
        Matcher reportMatcher = CRASH_REPORT_RESOURCE.matcher(uri);
        if (reportMatcher.matches()) {
            GameInstanceID id = id(decodeSegment(reportMatcher.group(1)));
            String reportName = decodeSegment(reportMatcher.group(2));
            return Map.of("uri", uri, "mime_type", "text/plain",
                    "text", readCrashReport(id, reportName));
        }
        throw new IllegalArgumentException("Unsupported XYML resource URI: " + uri);
    }

    /// Resolves one identifier and verifies it is a valid XYML instance ID.
    private GameInstanceID id(String raw) {
        return new GameInstanceID(Objects.requireNonNull(raw, "instanceId"));
    }

    /// Verifies that an instance exists before a mutating operation.
    private void requireInstance(GameInstanceID id) {
        if (!repository.hasInstance(id)) {
            throw new IllegalArgumentException("Unknown instance: " + id.id());
        }
    }

    /// Loads writable instance settings or reports why they cannot be changed.
    private GameSettings.Instance writableSettings(GameInstanceID id) {
        requireInstance(id);
        @Nullable GameSettings.Instance setting = repository.getInstanceGameSettingsOrCreate(id);
        if (setting == null || repository.isInstanceGameSettingsReadOnly(id)) {
            throw new IllegalStateException("Instance settings are read-only: " + id.id());
        }
        return setting;
    }

    /// Returns the latest log path for an instance.
    private Path latestLog(GameInstanceID id) {
        Path run = repository.getRunDirectory(id);
        Path latest = run.resolve("logs/latest.log");
        return Files.exists(latest) ? latest : run.resolve("latest.log");
    }

    /// Returns the normalized crash-report directory for an instance.
    ///
    /// @param id instance identifier
    /// @return normalized crash-report directory
    private Path crashReportRoot(GameInstanceID id) {
        return repository.getRunDirectory(id).resolve("crash-reports").toAbsolutePath().normalize();
    }

    /// Lists direct crash-report files as MCP resource URIs.
    ///
    /// @param id instance identifier
    /// @return newline-delimited resource URIs
    /// @throws IOException if the directory cannot be read
    private String listCrashReportUris(GameInstanceID id) throws IOException {
        Path root = crashReportRoot(id);
        if (!Files.isDirectory(root)) {
            return "";
        }
        String prefix = "xyml://instances/" + encodeSegment(id.id()) + "/crash-reports/";
        try (Stream<Path> paths = Files.list(root)) {
            return paths.filter(Files::isRegularFile)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .sorted()
                    .map(name -> prefix + encodeSegment(name))
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("");
        }
    }

    /// Reads one crash report after proving it belongs to the selected instance.
    ///
    /// @param id instance identifier
    /// @param rawPath report file name or absolute path
    /// @return UTF-8 report text
    /// @throws IOException if the path escapes the instance or cannot be read
    private String readCrashReport(GameInstanceID id, String rawPath) throws IOException {
        Path root = crashReportRoot(id);
        Path supplied = Path.of(rawPath);
        Path candidate = supplied.isAbsolute() ? supplied.normalize() : root.resolve(supplied).normalize();
        if (!root.equals(candidate.getParent()) || !Files.isRegularFile(candidate)) {
            throw new IOException("Crash report is not a direct file in the instance crash-reports directory");
        }
        Path realRoot = root.toRealPath();
        Path realReport = candidate.toRealPath();
        if (!realRoot.equals(realReport.getParent())) {
            throw new IOException("Crash report resolves outside the instance crash-reports directory");
        }
        return Files.readString(realReport, StandardCharsets.UTF_8);
    }

    /// Reads an instance log, returning an empty string when it does not exist.
    private String readLog(GameInstanceID id) throws IOException {
        Path path = latestLog(id);
        return readIfPresent(path);
    }

    /// Reads a file as UTF-8 when it is a regular file.
    private String readIfPresent(Path path) throws IOException {
        return Files.isRegularFile(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
    }

    /// Converts a nullable number to a JSON-safe value.
    private static Object nullableNumber(@Nullable Integer value) {
        return value == null ? nullValue() : value;
    }

    /// Returns a JSON-safe null sentinel accepted by MCP structured content.
    private static Object nullValue() {
        return "";
    }

    /// Parses a positive integer argument.
    private static int parsePositive(@Nullable String value, String name) {
        try {
            int result = Integer.parseInt(Objects.requireNonNull(value, name));
            if (result <= 0) {
                throw new NumberFormatException();
            }
            return result;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a positive integer", e);
        }
    }

    /// Encodes one value for use as an MCP URI path segment.
    ///
    /// @param value raw segment value
    /// @return percent-encoded segment
    private static String encodeSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /// Decodes one MCP URI path segment.
    ///
    /// @param value percent-encoded segment
    /// @return decoded segment
    private static String decodeSegment(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    /// Resolves a mod path and applies an enable/disable transition.
    private Path transitionMod(GameInstanceID id, String rawPath, boolean enable) throws IOException {
        ModManager manager = repository.getModManager(id);
        Path root = manager.getDirectory().toAbsolutePath().normalize();
        Path path = Path.of(rawPath).toAbsolutePath().normalize();
        if (!path.startsWith(root)) {
            throw new IOException("Mod path is outside the instance mods directory: " + path);
        }
        return enable ? manager.enableMod(path) : manager.disableMod(path);
    }

    /// Creates an offline account for a deterministic AI launch test.
    private static AuthInfo offlineAuth(GameInstanceID id) {
        UUID uuid = UUID.nameUUIDFromBytes(id.id().getBytes(StandardCharsets.UTF_8));
        return new AuthInfo("XYML-AI", uuid, "xyml-ai", AuthInfo.USER_TYPE_LEGACY, "{}");
    }

    /// Captures output and terminal state for one managed launch process.
    @NotNullByDefault
    private static final class LaunchState implements ProcessListener {
        /// Process created by DefaultLauncher, or null before launch returns.
        private volatile @Nullable space.minecraftstl.xyml.util.platform.ManagedProcess process;

        /// Captured output lines.
        private final List<String> logs = java.util.Collections.synchronizedList(new ArrayList<>());

        /// Last raw exit code.
        private volatile @Nullable Integer exitCode;

        /// Last classified exit type.
        private volatile @Nullable ProcessListener.ExitType exitType;

        /// Captures a decoded stdout or stderr line.
        @Override
        public void onLog(String log, boolean isErrorStream) {
            synchronized (logs) {
                if (logs.size() < MAX_LOG_LINES) {
                    logs.add((isErrorStream ? "[stderr] " : "") + log);
                }
            }
        }

        /// Returns an immutable snapshot of captured process output.
        ///
        /// @return immutable log snapshot
        private @Unmodifiable List<String> logsSnapshot() {
            synchronized (logs) {
                return List.copyOf(logs);
            }
        }

        /// Captures the classified process exit.
        @Override
        public void onExit(int exitCode, ProcessListener.ExitType exitType) {
            this.exitCode = exitCode;
            this.exitType = exitType;
        }
    }
}
