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
import space.minecraftstl.xyml.addon.mod.LocalModFile;
import space.minecraftstl.xyml.addon.mod.ModManager;
import space.minecraftstl.xyml.auth.AuthInfo;
import space.minecraftstl.xyml.game.CrashReportAnalyzer;
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
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.page.instances.management.InstanceLifecycleService;
import space.minecraftstl.xyml.ui.swing.page.instances.management.RepositoryInstanceLifecycleService;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/// Bridges the existing XYMLCore launcher services to MCP-safe structured operations.
///
/// This class deliberately contains no new game or mod-management algorithms. It delegates to the
/// repository, mod manager, Java manager, and launch monitor already used by XYML.
@NotNullByDefault
public final class XYMLMcpService implements XYMLMcpOperations {

    /// Maximum number of lines retained from one launch process.
    private static final int MAX_LOG_LINES = 20_000;

    /// Fixed lock-stripe count used to serialize operations without retaining client-provided identifiers.
    private static final int INSTANCE_OPERATION_LOCK_STRIPES = 64;

    /// URI matcher for the latest instance log.
    private static final Pattern LOG_RESOURCE = Pattern.compile(
            "^xyml://instances/([^/]+)/logs/latest\\.log$");

    /// URI matcher for an instance crash-report directory listing.
    private static final Pattern CRASH_DIRECTORY_RESOURCE = Pattern.compile(
            "^xyml://instances/([^/]+)/crash-reports/$");

    /// URI matcher for one instance crash-report file.
    private static final Pattern CRASH_REPORT_RESOURCE = Pattern.compile(
            "^xyml://instances/([^/]+)/crash-reports/([^/]+)$");

    /// Repository exposed by this server process.
    private final XYMLGameRepository repository;

    /// Existing launcher lifecycle service used for instance mutations.
    private final InstanceLifecycleService instanceLifecycle;

    /// Application-owned policy for every destructive MCP operation.
    private final McpDeletionConfirmation deletionConfirmation;

    /// Last known process state for each instance.
    private final Map<GameInstanceID, LaunchState> launchStates = new ConcurrentHashMap<>();

    /// Fixed lock stripes serializing MCP lifecycle, mod, and launch transitions.
    private final @Unmodifiable List<Object> instanceOperationLocks = createInstanceOperationLocks();

    /// Creates a service for one initialized XYML game repository.
    ///
    /// @param repository repository whose instances and settings are exposed
    /// @param deletionConfirmation launcher-owned confirmation policy for destructive operations
    public XYMLMcpService(
            XYMLGameRepository repository,
            McpDeletionConfirmation deletionConfirmation) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.instanceLifecycle = new RepositoryInstanceLifecycleService(repository);
        this.deletionConfirmation = Objects.requireNonNull(deletionConfirmation, "deletionConfirmation");
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

    /// Renames one installed instance and selects the renamed destination.
    ///
    /// @param sourceInstanceId existing instance identifier
    /// @param destinationInstanceId new instance identifier
    /// @return immutable rename outcome
    /// @throws IOException if validation or the repository mutation fails
    @Override
    public @Unmodifiable Map<String, Object> renameInstance(
            String sourceInstanceId,
            String destinationInstanceId) throws IOException {
        GameInstanceID source = id(sourceInstanceId);
        GameInstanceID destination = destinationId(destinationInstanceId);
        int sourceStripe = instanceOperationLockIndex(source);
        int destinationStripe = instanceOperationLockIndex(destination);
        Object firstLock = instanceOperationLocks.get(Math.min(sourceStripe, destinationStripe));
        Object secondLock = instanceOperationLocks.get(Math.max(sourceStripe, destinationStripe));
        synchronized (firstLock) {
            synchronized (secondLock) {
                requireInstance(source);
                requireTrackedLaunchStopped(source, "rename");
                instanceLifecycle.rename(source, destination);
                @Nullable LaunchState previousState = launchStates.remove(source);
                if (previousState != null) {
                    launchStates.put(destination, previousState);
                }
                EdtDispatcher.executeAndWait(() -> instanceLifecycle.reconcileSelection(destination));
                return Map.of(
                        "renamed", true,
                        "source_instance_id", source.id(),
                        "instance_id", destination.id());
            }
        }
    }

    /// Duplicates one installed instance and selects the new destination.
    ///
    /// @param sourceInstanceId existing instance identifier
    /// @param destinationInstanceId new instance identifier
    /// @param copySaves whether saved worlds should be copied
    /// @return immutable duplication outcome
    /// @throws IOException if validation or the repository mutation fails
    @Override
    public @Unmodifiable Map<String, Object> duplicateInstance(
            String sourceInstanceId,
            String destinationInstanceId,
            boolean copySaves) throws IOException {
        GameInstanceID source = id(sourceInstanceId);
        GameInstanceID destination = destinationId(destinationInstanceId);
        int sourceStripe = instanceOperationLockIndex(source);
        int destinationStripe = instanceOperationLockIndex(destination);
        Object firstLock = instanceOperationLocks.get(Math.min(sourceStripe, destinationStripe));
        Object secondLock = instanceOperationLocks.get(Math.max(sourceStripe, destinationStripe));
        synchronized (firstLock) {
            synchronized (secondLock) {
                requireInstance(source);
                requireTrackedLaunchStopped(source, "duplicate");
                instanceLifecycle.duplicate(source, destination, copySaves);
                EdtDispatcher.executeAndWait(() -> instanceLifecycle.reconcileSelection(destination));
                return Map.of(
                        "duplicated", true,
                        "source_instance_id", source.id(),
                        "instance_id", destination.id(),
                        "copied_saves", copySaves);
            }
        }
    }

    /// Deletes one installed instance only after the configured launcher confirmation succeeds.
    ///
    /// @param instanceId existing instance identifier
    /// @return immutable approval and deletion outcome
    /// @throws IOException if the approved repository mutation fails
    @Override
    public @Unmodifiable Map<String, Object> deleteInstance(String instanceId) throws IOException {
        GameInstanceID target = id(instanceId);
        synchronized (instanceOperationLock(target)) {
            requireInstance(target);
            requireTrackedLaunchStopped(target, "delete");
            if (!deletionConfirmation.confirm(McpDeletionConfirmation.DeletionRequest.instance(target))) {
                return Map.of("approved", false, "deleted", false, "instance_id", target.id());
            }
            requireInstance(target);
            requireTrackedLaunchStopped(target, "delete");
            instanceLifecycle.delete(target);
            launchStates.remove(target);
            EdtDispatcher.executeAndWait(() -> instanceLifecycle.reconcileSelection(null));
            return Map.of("approved", true, "deleted", true, "instance_id", target.id());
        }
    }

    /// Returns the absolute mods directory for an instance.
    ///
    /// @param instanceId instance identifier
    /// @return absolute mods directory
    @Override
    public String getModsDirectory(String instanceId) {
        return repository.getModsDirectory(id(instanceId)).toAbsolutePath().normalize().toString();
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
        requireInstance(id);
        String rawLog = logText != null ? logText : readLog(id);
        XYMLMcpCrashReportResolver.Resolution resolution = XYMLMcpCrashReportResolver.resolve(
                crashReportRoot(id),
                rawLog,
                crashReportPath,
                logText == null);
        @Unmodifiable Map<String, Object> analysis = XYMLMcpCrashAnalyzer.analyze(rawLog, resolution.report());
        Map<String, Object> result = new LinkedHashMap<>(analysis);
        result.put("instance_id", id.id());
        result.put("crash_report_source", resolution.source());
        result.put("warnings", resolution.warnings());
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
        String checkedLog = Objects.requireNonNull(logText, "logText");
        @Nullable String report = CrashReportAnalyzer.extractCrashReport(checkedLog);
        Map<String, Object> result = new LinkedHashMap<>(XYMLMcpCrashAnalyzer.analyze(checkedLog, report));
        result.put("crash_report_source", report == null
                ? XYMLMcpCrashReportResolver.SOURCE_NONE
                : XYMLMcpCrashReportResolver.SOURCE_EMBEDDED);
        result.put("warnings", List.of());
        return Map.copyOf(result);
    }

    /// Reads a supported `xyml://` resource URI.
    ///
    /// @param uri resource URI
    /// @return immutable resource URI, MIME type, and text
    /// @throws IOException if a resource file cannot be read
    @Override
    public @Unmodifiable Map<String, String> readResource(String uri) throws IOException {
        Matcher logMatcher = LOG_RESOURCE.matcher(uri);
        if (logMatcher.matches()) {
            GameInstanceID id = instanceIdFromUri(logMatcher.group(1));
            requireInstance(id);
            return textResource(uri, readLog(id));
        }

        Matcher directoryMatcher = CRASH_DIRECTORY_RESOURCE.matcher(uri);
        if (directoryMatcher.matches()) {
            GameInstanceID id = instanceIdFromUri(directoryMatcher.group(1));
            requireInstance(id);
            return textResource(uri, listCrashReports(id));
        }

        Matcher reportMatcher = CRASH_REPORT_RESOURCE.matcher(uri);
        if (reportMatcher.matches()) {
            GameInstanceID id = instanceIdFromUri(reportMatcher.group(1));
            requireInstance(id);
            return textResource(uri, readCrashReport(id, decodePathSegment(reportMatcher.group(2))));
        }
        throw new IllegalArgumentException("Unsupported XYML resource URI: " + uri);
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
        GameInstanceID id = id(instanceId);
        synchronized (instanceOperationLock(id)) {
            requireInstance(id);
            ModManager manager = repository.getModManager(id);
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
    }

    /// Changes the Java selection to a numeric version or executable path.
    ///
    /// @param instanceId instance identifier
    /// @param javaVersion numeric Java version, or blank when `javaPath` is used
    /// @param javaPath executable path, or blank when `javaVersion` is used
    /// @param inherit whether all Java overrides should be removed
    /// @return resulting effective settings
    @Override
    public @Unmodifiable Map<String, Object> setJavaVersion(
            String instanceId,
            @Nullable String javaVersion,
            @Nullable String javaPath,
            boolean inherit) {
        GameInstanceID id = id(instanceId);
        synchronized (instanceOperationLock(id)) {
            GameSettings.Instance setting = writableSettings(id);
            boolean hasVersion = javaVersion != null && !javaVersion.isBlank();
            boolean hasPath = javaPath != null && !javaPath.isBlank();
            if (inherit) {
                if (hasVersion || hasPath) {
                    throw new IllegalArgumentException("Inherited Java settings cannot include a version or path");
                }
                setting.getOverrideProperties().remove(GameSettings.PROPERTY_JAVA_TYPE);
                setting.getOverrideProperties().remove(GameSettings.PROPERTY_CUSTOM_JAVA_VERSION);
                setting.getOverrideProperties().remove(GameSettings.PROPERTY_CUSTOM_JAVA_PATH);
            } else if (hasVersion == hasPath) {
                throw new IllegalArgumentException("Exactly one of javaVersion or javaPath must be supplied");
            } else if (hasPath) {
                setting.getOverrideProperties().add(GameSettings.PROPERTY_JAVA_TYPE);
                setting.javaTypeProperty().setValue(JavaVersionType.CUSTOM);
                setting.getOverrideProperties().add(GameSettings.PROPERTY_CUSTOM_JAVA_PATH);
                setting.customJavaPathProperty().setValue(Path.of(javaPath).toAbsolutePath().normalize().toString());
                setting.getOverrideProperties().remove(GameSettings.PROPERTY_CUSTOM_JAVA_VERSION);
            } else {
                int major = parsePositive(javaVersion, "javaVersion");
                setting.getOverrideProperties().add(GameSettings.PROPERTY_JAVA_TYPE);
                setting.javaTypeProperty().setValue(JavaVersionType.VERSION);
                setting.getOverrideProperties().add(GameSettings.PROPERTY_CUSTOM_JAVA_VERSION);
                setting.customJavaVersionProperty().setValue(Integer.toString(major));
                setting.getOverrideProperties().remove(GameSettings.PROPERTY_CUSTOM_JAVA_PATH);
            }
            repository.saveGameSettings(id);
            return getInstanceSettings(instanceId);
        }
    }

    /// Changes minimum and maximum heap memory in MiB.
    ///
    /// @param instanceId instance identifier
    /// @param minMemory minimum heap, or null to leave unchanged
    /// @param maxMemory maximum heap, or null to leave unchanged
    /// @param inherit whether both heap overrides should be removed
    /// @return resulting effective settings
    @Override
    public @Unmodifiable Map<String, Object> setMemory(
            String instanceId,
            @Nullable Integer minMemory,
            @Nullable Integer maxMemory,
            boolean inherit) {
        GameInstanceID id = id(instanceId);
        synchronized (instanceOperationLock(id)) {
            GameSettings.Instance setting = writableSettings(id);
            if (inherit) {
                if (minMemory != null || maxMemory != null) {
                    throw new IllegalArgumentException("Inherited memory settings cannot include heap values");
                }
                setting.getOverrideProperties().remove(GameSettings.PROPERTY_MIN_MEMORY);
                setting.getOverrideProperties().remove(GameSettings.PROPERTY_MAX_MEMORY);
            } else {
                if (minMemory == null && maxMemory == null) {
                    throw new IllegalArgumentException("At least one memory value must be supplied");
                }
                GameSettings.Effective effective = repository.getEffectiveGameSettings(id);
                validateMemoryUpdate(
                        minMemory,
                        maxMemory,
                        effective.getInheritable(GameSettings::minMemoryProperty),
                        effective.getMaxMemory());
                if (minMemory != null) {
                    applyMemoryOverride(
                            setting, GameSettings.PROPERTY_MIN_MEMORY, setting.minMemoryProperty(), minMemory);
                }
                if (maxMemory != null) {
                    applyMemoryOverride(
                            setting, GameSettings.PROPERTY_MAX_MEMORY, setting.maxMemoryProperty(), maxMemory);
                }
            }
            repository.saveGameSettings(id);
            return getInstanceSettings(instanceId);
        }
    }

    /// Validates a partial heap update against the effective values it leaves unchanged.
    ///
    /// @param minMemory requested minimum heap, or null to retain the effective minimum
    /// @param maxMemory requested maximum heap, or null to retain the effective maximum
    /// @param currentMinMemory current effective minimum heap, or null for the launcher default
    /// @param currentMaxMemory current effective maximum heap
    static void validateMemoryUpdate(
            @Nullable Integer minMemory,
            @Nullable Integer maxMemory,
            @Nullable Integer currentMinMemory,
            int currentMaxMemory) {
        if (minMemory != null && minMemory < 0 || maxMemory != null && maxMemory <= 0) {
            throw new IllegalArgumentException("Memory values must be positive (minimum may be zero)");
        }
        int resultingMin = minMemory != null
                ? minMemory : Objects.requireNonNullElse(currentMinMemory, 0);
        int resultingMax = maxMemory != null ? maxMemory : currentMaxMemory;
        if (resultingMin > resultingMax) {
            throw new IllegalArgumentException("minMemory must not exceed maxMemory");
        }
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
    /// @param options JVM options string, or null when inheritance is requested
    /// @param inherit whether the JVM-options override should be removed
    /// @return resulting effective settings
    @Override
    public @Unmodifiable Map<String, Object> setJvmOptions(
            String instanceId,
            @Nullable String options,
            boolean inherit) {
        GameInstanceID id = id(instanceId);
        synchronized (instanceOperationLock(id)) {
            GameSettings.Instance setting = writableSettings(id);
            if (inherit) {
                if (options != null) {
                    throw new IllegalArgumentException("Inherited JVM options cannot include an options value");
                }
                setting.getOverrideProperties().remove(GameSettings.PROPERTY_JVM_OPTIONS);
            } else {
                setting.getOverrideProperties().add(GameSettings.PROPERTY_JVM_OPTIONS);
                setting.jvmOptionsProperty().setValue(Objects.requireNonNull(options, "options"));
            }
            repository.saveGameSettings(id);
            return getInstanceSettings(instanceId);
        }
    }

    /// Changes window dimensions and fullscreen state.
    ///
    /// @param instanceId instance identifier
    /// @param width optional width
    /// @param height optional height
    /// @param fullscreen optional fullscreen state
    /// @param inherit whether all window overrides should be removed
    /// @return resulting effective settings
    @Override
    public @Unmodifiable Map<String, Object> setWindowOptions(
            String instanceId,
            @Nullable Integer width,
            @Nullable Integer height,
            @Nullable Boolean fullscreen,
            boolean inherit) {
        if (width != null && width < 0 || height != null && height < 0) {
            throw new IllegalArgumentException("Window dimensions must not be negative");
        }
        GameInstanceID id = id(instanceId);
        synchronized (instanceOperationLock(id)) {
            GameSettings.Instance setting = writableSettings(id);
            if (inherit) {
                if (width != null || height != null || fullscreen != null) {
                    throw new IllegalArgumentException("Inherited window settings cannot include explicit values");
                }
                setting.getOverrideProperties().remove(GameSettings.PROPERTY_WIDTH);
                setting.getOverrideProperties().remove(GameSettings.PROPERTY_HEIGHT);
                setting.getOverrideProperties().remove(GameSettings.PROPERTY_WINDOW_TYPE);
            } else {
                if (width == null && height == null && fullscreen == null) {
                    throw new IllegalArgumentException("At least one window setting must be supplied");
                }
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
                    setting.windowTypeProperty().setValue(
                            fullscreen ? GameWindowType.FULLSCREEN : GameWindowType.WINDOWED);
                }
            }
            repository.saveGameSettings(id);
            return getInstanceSettings(instanceId);
        }
    }

    /// Enables one mod file through ModManager's `.disabled` transition.
    ///
    /// @param instanceId target instance
    /// @param path mod file path
    /// @return resulting file path
    @Override
    public String enableMod(String instanceId, String path) throws IOException {
        GameInstanceID id = id(instanceId);
        synchronized (instanceOperationLock(id)) {
            requireInstance(id);
            return transitionMod(id, path, true).toString();
        }
    }

    /// Disables one mod file through ModManager's `.disabled` transition.
    ///
    /// @param instanceId target instance
    /// @param path mod file path
    /// @return resulting file path
    @Override
    public String disableMod(String instanceId, String path) throws IOException {
        GameInstanceID id = id(instanceId);
        synchronized (instanceOperationLock(id)) {
            requireInstance(id);
            return transitionMod(id, path, false).toString();
        }
    }

    /// Removes selected local mod files after applying the launcher-owned confirmation policy.
    ///
    /// @param instanceId target instance
    /// @param paths files to remove
    /// @return immutable approval state and removed paths
    @Override
    public @Unmodifiable Map<String, Object> removeMods(String instanceId, List<String> paths) throws IOException {
        GameInstanceID id = id(instanceId);
        synchronized (instanceOperationLock(id)) {
            requireInstance(id);
            ModManager manager = repository.getModManager(id);
            if (paths.isEmpty()) {
                return Map.of("approved", true, "removed", List.of());
            }
            Map<Path, LocalModFile> managedMods = managedModIndex(manager);
            Map<Path, LocalModFile> selected = new LinkedHashMap<>();
            for (String rawPath : paths) {
                Path expected = Path.of(rawPath).toAbsolutePath().normalize();
                @Nullable LocalModFile mod = managedMods.get(expected);
                if (mod == null) {
                    throw new IOException("Mod is not managed by this instance: " + expected);
                }
                selected.put(expected, mod);
            }
            List<String> selectedPaths = selected.keySet().stream()
                    .map(Path::toString)
                    .toList();
            if (!deletionConfirmation.confirm(
                    McpDeletionConfirmation.DeletionRequest.mods(id, selectedPaths.size()))) {
                return Map.of("approved", false, "removed", List.of());
            }
            requireInstance(id);
            try {
                manager.removeMods(selected.values().toArray(LocalModFile[]::new));
            } finally {
                manager.invalidateCache();
            }
            return Map.of("approved", true, "removed", selectedPaths);
        }
    }

    /// Starts an instance with the launcher-generated options and captures monitor state.
    ///
    /// @param instanceId target instance
    /// @return launch acceptance and process metadata
    @Override
    public @Unmodifiable Map<String, Object> launchGame(String instanceId) throws Exception {
        GameInstanceID id = id(instanceId);
        synchronized (instanceOperationLock(id)) {
            requireInstance(id);
            @Nullable LaunchState existing = launchStates.get(id);
            if (existing != null && existing.process != null && existing.process.isRunning()) {
                throw new IllegalStateException("A tracked launch is already running for this instance");
            }
            GameInstanceManifest manifest = repository.getResolvedInstanceManifest(id).launchManifest();
            JavaRuntime java = repository.getEffectiveGameSettings(id).getJava(
                    repository.getGameVersion(id)
                            .map(space.minecraftstl.xyml.util.versioning.GameVersionNumber::asGameVersion)
                            .orElse(null),
                    manifest);
            if (java == null) {
                throw new IllegalStateException("No compatible Java runtime was found");
            }
            LaunchOptions options = repository.getLaunchOptions(
                    id, java, repository.getRunDirectory(id), List.of(), List.of(), false)
                    .setDaemon(true)
                    .create();
            LaunchState state = new LaunchState();
            DefaultLauncher launcher = new DefaultLauncher(repository, manifest,
                    offlineAuth(id), options, state, true);
            state.process = launcher.launch();
            launchStates.put(id, state);
            return Map.of("instance_id", id.id(), "started", true,
                    "running", state.process.isRunning());
        }
    }

    /// Stops a running instance process if one is tracked.
    ///
    /// @param instanceId target instance
    /// @return whether a process was stopped
    @Override
    public @Unmodifiable Map<String, Object> stopGame(String instanceId) {
        GameInstanceID id = id(instanceId);
        synchronized (instanceOperationLock(id)) {
            LaunchState state = launchStates.get(id);
            if (state == null || state.process == null || !state.process.isRunning()) {
                return Map.of("instance_id", id.id(), "stopped", false);
            }
            state.process.stop();
            return Map.of("instance_id", id.id(), "stopped", true);
        }
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

    /// Resolves one identifier and verifies it is a valid XYML instance ID.
    private GameInstanceID id(String raw) {
        return new GameInstanceID(Objects.requireNonNull(raw, "instanceId"));
    }

    /// Resolves a filesystem-safe destination instance identifier.
    ///
    /// @param raw raw destination identifier
    /// @return validated destination identifier
    private GameInstanceID destinationId(String raw) {
        String checked = Objects.requireNonNull(raw, "destinationInstanceId");
        if (!instanceLifecycle.isValidDestinationId(checked)) {
            throw new IllegalArgumentException("Invalid destination instance identifier: " + checked);
        }
        return new GameInstanceID(checked);
    }

    /// Verifies that an instance exists before a mutating operation.
    private void requireInstance(GameInstanceID id) {
        if (!repository.hasInstance(id)) {
            throw new IllegalArgumentException("Unknown instance: " + id.id());
        }
    }

    /// Rejects lifecycle mutations while their source has a running MCP-tracked process.
    ///
    /// @param id source instance identifier
    /// @param operation requested lifecycle operation
    private void requireTrackedLaunchStopped(GameInstanceID id, String operation) {
        @Nullable LaunchState state = launchStates.get(id);
        if (state != null && state.process != null && state.process.isRunning()) {
            throw new IllegalStateException("Cannot " + operation + " an instance while its tracked game is running");
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

    /// Creates a text resource result.
    ///
    /// @param uri resource URI
    /// @param text resource text
    /// @return immutable resource map
    private static @Unmodifiable Map<String, String> textResource(String uri, String text) {
        return Map.of("uri", uri, "mime_type", "text/plain", "text", text);
    }

    /// Lists direct regular files in one instance's crash-report directory.
    ///
    /// @param id instance identifier
    /// @return one file name per line, or an empty string when the directory is absent
    /// @throws IOException if the directory cannot be listed
    private String listCrashReports(GameInstanceID id) throws IOException {
        Path root = crashReportRoot(id);
        if (!Files.isDirectory(root)) {
            return "";
        }
        try (Stream<Path> paths = Files.list(root)) {
            return paths.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .sorted(Comparator.naturalOrder())
                    .collect(Collectors.joining("\n"));
        }
    }

    /// Decodes and validates one URI path segment.
    ///
    /// @param raw encoded path segment
    /// @return decoded safe path segment
    private static String decodePathSegment(String raw) {
        final String decoded;
        try {
            decoded = URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid XYML resource URI", exception);
        }
        if (decoded.isBlank() || decoded.contains("/") || decoded.contains("\\")
                || ".".equals(decoded) || "..".equals(decoded)) {
            throw new IllegalArgumentException("Invalid XYML resource path segment");
        }
        return decoded;
    }

    /// Decodes an instance identifier embedded in a resource URI.
    ///
    /// @param raw encoded instance identifier
    /// @return validated instance identifier
    private static GameInstanceID instanceIdFromUri(String raw) {
        return new GameInstanceID(decodePathSegment(raw));
    }

    /// Reads one crash report after proving it belongs to the selected instance.
    ///
    /// @param id instance identifier
    /// @param rawPath report file name or absolute path
    /// @return UTF-8 report text
    /// @throws IOException if the path escapes the instance or cannot be read
    private String readCrashReport(GameInstanceID id, String rawPath) throws IOException {
        return XYMLMcpCrashReportResolver.readReport(crashReportRoot(id), rawPath);
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

    /// Resolves a mod path and applies an enable/disable transition.
    private Path transitionMod(GameInstanceID id, String rawPath, boolean enable) throws IOException {
        ModManager manager = repository.getModManager(id);
        Path path = Path.of(rawPath).toAbsolutePath().normalize();
        @Nullable LocalModFile mod = managedModIndex(manager).get(path);
        if (mod == null) {
            throw new IOException("Mod is not managed by this instance: " + path);
        }
        try {
            Path result = enable ? manager.enableMod(mod.getFile()) : manager.disableMod(mod.getFile());
            return result.toAbsolutePath().normalize();
        } finally {
            manager.invalidateCache();
        }
    }

    /// Creates the fixed lock stripes shared by instance operations.
    ///
    /// @return immutable lock-stripe list
    private static @Unmodifiable List<Object> createInstanceOperationLocks() {
        List<Object> locks = new ArrayList<>(INSTANCE_OPERATION_LOCK_STRIPES);
        for (int i = 0; i < INSTANCE_OPERATION_LOCK_STRIPES; i++) {
            locks.add(new Object());
        }
        return List.copyOf(locks);
    }

    /// Returns the stable operation lock associated with one instance identifier.
    ///
    /// @param id instance identifier
    /// @return stable per-instance lock
    private Object instanceOperationLock(GameInstanceID id) {
        return instanceOperationLocks.get(instanceOperationLockIndex(id));
    }

    /// Returns the stable operation lock-stripe index associated with one instance identifier.
    ///
    /// @param id instance identifier
    /// @return index into the fixed lock-stripe list
    private int instanceOperationLockIndex(GameInstanceID id) {
        return Math.floorMod(id.hashCode(), instanceOperationLocks.size());
    }

    /// Indexes only regular managed mod files whose real paths remain under the instance mods directory.
    ///
    /// @param manager instance mod manager
    /// @return immutable normalized-path index
    /// @throws IOException if managed mod files cannot be loaded or resolved
    private static @Unmodifiable Map<Path, LocalModFile> managedModIndex(ModManager manager) throws IOException {
        List<LocalModFile> localFiles = manager.getLocalFiles();
        if (localFiles.isEmpty()) {
            return Map.of();
        }
        Path root = manager.getDirectory().toAbsolutePath().normalize();
        Path realRoot = root.toRealPath();
        Map<Path, LocalModFile> result = new LinkedHashMap<>();
        for (LocalModFile mod : localFiles) {
            Path path = mod.getFile().toAbsolutePath().normalize();
            if (!path.startsWith(root) || !Files.isRegularFile(path)) {
                continue;
            }
            Path realPath = path.toRealPath();
            if (realPath.startsWith(realRoot)) {
                result.put(path, mod);
            }
        }
        return Map.copyOf(result);
    }

    /// Creates an offline account for a deterministic launch test.
    private static AuthInfo offlineAuth(GameInstanceID id) {
        UUID uuid = UUID.nameUUIDFromBytes(id.id().getBytes(StandardCharsets.UTF_8));
        return new AuthInfo("XYML-MCP", uuid, "xyml-mcp", AuthInfo.USER_TYPE_LEGACY, "{}");
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
