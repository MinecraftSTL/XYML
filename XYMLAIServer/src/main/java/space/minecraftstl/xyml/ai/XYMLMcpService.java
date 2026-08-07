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
import space.minecraftstl.xyml.addon.RemoteAddon;
import space.minecraftstl.xyml.addon.RemoteAddonRepository;
import space.minecraftstl.xyml.addon.mod.LocalModFile;
import space.minecraftstl.xyml.addon.mod.ModManager;
import space.minecraftstl.xyml.addon.repository.CurseForgeRemoteAddonRepository;
import space.minecraftstl.xyml.addon.repository.ModrinthRemoteAddonRepository;
import space.minecraftstl.xyml.auth.AuthInfo;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.download.RemoteVersion;
import space.minecraftstl.xyml.download.VersionList;
import space.minecraftstl.xyml.game.CrashReportAnalyzer;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.game.LaunchOptions;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.java.JavaManager;
import space.minecraftstl.xyml.java.JavaRuntime;
import space.minecraftstl.xyml.launch.DefaultLauncher;
import space.minecraftstl.xyml.launch.ProcessListener;
import space.minecraftstl.xyml.modpack.Modpack;
import space.minecraftstl.xyml.game.ModpackHelper;
import space.minecraftstl.xyml.setting.DownloadProviders;
import space.minecraftstl.xyml.setting.GameSettings;
import space.minecraftstl.xyml.setting.GameWindowType;
import space.minecraftstl.xyml.setting.JavaVersionType;
import space.minecraftstl.xyml.task.FileDownloadTask;
import space.minecraftstl.xyml.task.Task;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
/// repository, dependency manager, remote add-on repositories, and launch monitor already used by XYML.
@NotNullByDefault
public final class XYMLMcpService {

    /// Maximum number of lines returned by one log request.
    private static final int MAX_LOG_LINES = 20_000;

    /// URI matcher for a latest-log resource.
    private static final Pattern LOG_RESOURCE = Pattern.compile(
            "^xyml://instances/([^/]+)/logs/latest\\.log$");

    /// URI matcher for one crash-report resource.
    private static final Pattern CRASH_RESOURCE = Pattern.compile(
            "^xyml://instances/([^/]+)/crash-reports/$");

    /// Repository exposed by this server process.
    private final XYMLGameRepository repository;

    /// Download provider selected by the running launcher.
    private final DownloadProvider downloadProvider;

    /// Last known process state for each instance.
    private final Map<GameInstanceID, LaunchState> launchStates = new ConcurrentHashMap<>();

    /// Creates a service for one initialized XYML game repository.
    ///
    /// @param repository repository whose instances and settings are exposed
    public XYMLMcpService(XYMLGameRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.downloadProvider = DownloadProviders.getDownloadProvider();
    }

    /// Returns all installed instances and their root directories.
    ///
    /// @return immutable instance summaries
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
    public String getModsDirectory(String instanceId) {
        return repository.getModsDirectory(id(instanceId)).toAbsolutePath().normalize().toString();
    }

    /// Reads the tail of the latest game log.
    ///
    /// @param instanceId instance identifier
    /// @param requestedLines requested number of lines
    /// @return log metadata and text
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
    public @Unmodifiable Map<String, Object> analyzeCrash(
            String instanceId,
            @Nullable String logText,
            @Nullable String crashReportPath) throws IOException {
        GameInstanceID id = id(instanceId);
        String rawLog = logText != null ? logText : readLog(id);
        @Nullable String report = null;
        if (crashReportPath != null && !crashReportPath.isBlank()) {
            report = Files.readString(Path.of(crashReportPath), StandardCharsets.UTF_8);
        }
        Map<String, Object> analysis = analyzeCrashText(rawLog, report);
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
        return analyzeCrashText(logText, null);
    }

    /// Applies the existing crash analyzer to text and an optional explicit report.
    private static @Unmodifiable Map<String, Object> analyzeCrashText(
            String logText, @Nullable String explicitReport) {
        @Nullable String report = explicitReport;
        if (report == null) {
            try {
                report = CrashReportAnalyzer.findCrashReport(logText);
            } catch (IOException | InvalidPathException ignored) {
                report = null;
            }
            if (report == null) {
                report = CrashReportAnalyzer.extractCrashReport(logText);
            }
        }
        List<Map<String, Object>> matches = new ArrayList<>();
        for (CrashReportAnalyzer.Result result : CrashReportAnalyzer.analyze(logText)) {
            Map<String, Object> match = new LinkedHashMap<>();
            match.put("rule", result.rule().name());
            match.put("log", result.log());
            Matcher matcher = result.matcher();
            for (String group : result.rule().getGroupNames()) {
                match.put(group, groupValue(matcher, group));
            }
            matches.add(Map.copyOf(match));
        }
        matches.sort(Comparator.comparing(value -> String.valueOf(value.get("rule"))));
        return Map.of(
                "matches", List.copyOf(matches),
                "crash_report", report == null ? "" : report,
                "keywords", List.copyOf(CrashReportAnalyzer.findKeywordsFromCrashReport(
                        report == null ? "" : report)));
    }

    /// Lists Java runtimes already discovered by JavaManager.
    ///
    /// @return immutable runtime summaries
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

    /// Searches one remote add-on repository.
    ///
    /// @param source remote source name
    /// @param type add-on type
    /// @param query search text
    /// @param gameVersion target game version
    /// @param category optional category identifier
    /// @param sort sort name
    /// @param page zero-based page number
    /// @param pageSize page size
    /// @return immutable search result
    public @Unmodifiable Map<String, Object> searchAddons(
            String source,
            String type,
            String query,
            String gameVersion,
            @Nullable String category,
            String sort,
            int page,
            int pageSize) throws IOException {
        RemoteAddon.Source addonSource = addonSource(source);
        RemoteAddon.Type addonType = addonType(type);
        RemoteAddonRepository repo = requireRepository(addonSource, addonType);
        @Nullable RemoteAddonRepository.Category categoryValue = category == null || category.isBlank()
                ? null
                : new RemoteAddonRepository.Category(null, category, List.of());
        RemoteAddonRepository.SortType sortType = enumValue(RemoteAddonRepository.SortType.class, sort, "POPULARITY");
        RemoteAddonRepository.SearchResult search = repo.search(
                downloadProvider,
                gameVersion,
                categoryValue,
                Math.max(0, page),
                Math.max(1, Math.min(100, pageSize)),
                query,
                sortType,
                RemoteAddonRepository.SortOrder.DESC);
        List<Map<String, Object>> results = search.getResults().map(this::addonMap).toList();
        return Map.of("source", addonSource.name().toLowerCase(Locale.ROOT),
                "type", addonType.name().toLowerCase(Locale.ROOT),
                "page", Math.max(0, page),
                "total_pages", search.getTotalPages(),
                "results", List.copyOf(results));
    }

    /// Returns versions published for one remote add-on project.
    ///
    /// @param source remote source name
    /// @param type add-on type
    /// @param projectId remote project identifier
    /// @return immutable version summaries
    public @Unmodifiable List<Map<String, Object>> getAddonVersions(
            String source, String type, String projectId) throws IOException {
        RemoteAddon.Source addonSource = addonSource(source);
        RemoteAddonRepository repo = requireRepository(addonSource, addonType(type));
        return repo.getRemoteVersionsById(downloadProvider, projectId).map(this::versionMap).toList();
    }

    /// Returns categories supported by one remote add-on source and type.
    ///
    /// @param source remote source name
    /// @param type add-on type
    /// @return immutable category summaries
    public @Unmodifiable List<Map<String, Object>> getAddonCategories(String source, String type) throws IOException {
        RemoteAddon.Source addonSource = addonSource(source);
        RemoteAddonRepository repo = requireRepository(addonSource, addonType(type));
        return repo.getCategories().map(category -> Map.of(
                "id", category.id(),
                "subcategories", List.copyOf(category.subcategories()))).toList();
    }

    /// Resolves a local add-on file to its remote version by its SHA-1.
    ///
    /// @param source remote source name
    /// @param type add-on type
    /// @param path local add-on path
    /// @return matching version or an explicit not-found result
    public @Unmodifiable Map<String, Object> getRemoteVersionByLocalFile(
            String source, String type, String path) throws IOException {
        RemoteAddon.Source addonSource = addonSource(source);
        RemoteAddonRepository repo = requireRepository(addonSource, addonType(type));
        @Nullable RemoteAddon.Version version = repo.getRemoteVersionByLocalFile(Path.of(path)).orElse(null);
        return version == null
                ? Map.of("found", false)
                : Map.of("found", true, "version", versionMap(version));
    }

    /// Lists base game versions from the existing game version list.
    ///
    /// @return immutable version summaries
    public @Unmodifiable List<Map<String, Object>> listRemoteGameVersions() {
        return listVersions("game", "");
    }

    /// Lists versions for one loader and game version.
    ///
    /// @param loader loader identifier used by XYMLCore
    /// @param gameVersion target game version
    /// @return immutable loader-version summaries
    public @Unmodifiable List<Map<String, Object>> listModloaderVersions(String loader, String gameVersion) {
        return listVersions(loader, gameVersion);
    }

    /// Changes the Java selection to a numeric version or executable path.
    ///
    /// @param instanceId instance identifier
    /// @param javaVersion numeric Java version, or blank when `javaPath` is used
    /// @param javaPath executable path, or blank when `javaVersion` is used
    /// @return resulting effective settings
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
        setting.getOverrideProperties().add(GameSettings.PROPERTY_MIN_MEMORY);
        setting.minMemoryProperty().setValue(minMemory);
        setting.getOverrideProperties().add(GameSettings.PROPERTY_MAX_MEMORY);
        setting.maxMemoryProperty().setValue(maxMemory);
        repository.saveGameSettings(id);
        return getInstanceSettings(instanceId);
    }

    /// Changes the raw JVM options string.
    ///
    /// @param instanceId instance identifier
    /// @param options JVM options string
    /// @return resulting effective settings
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

    /// Installs a selected remote add-on file into the instance mods directory.
    ///
    /// @param instanceId target instance
    /// @param source remote source
    /// @param type add-on type
    /// @param projectId project identifier
    /// @param versionId version identifier
    /// @return installed file metadata
    public @Unmodifiable Map<String, Object> installAddon(
            String instanceId, String source, String type, String projectId, String versionId) throws Exception {
        GameInstanceID id = id(instanceId);
        RemoteAddon.Source addonSource = addonSource(source);
        RemoteAddonRepository repo = requireRepository(addonSource, addonType(type));
        RemoteAddon.File file = repo.getAddonFile(projectId, versionId);
        Path mods = repository.getModsDirectory(id).toAbsolutePath().normalize();
        String filename = Path.of(file.filename()).getFileName().toString();
        Path destination = mods.resolve(filename).normalize();
        if (!destination.getParent().equals(mods)) {
            throw new IOException("Remote file name is not a direct mods file");
        }
        FileDownloadTask download = new FileDownloadTask(file.url(), destination, file.getIntegrityCheck());
        download.test();
        if (download.getException() != null) {
            throw download.getException();
        }
        repository.getModManager(id).refresh();
        return Map.of("path", destination.toString(), "filename", filename, "source", addonSource.name());
    }

    /// Copies a local mod into an instance using ModManager's existing validation.
    ///
    /// @param instanceId target instance
    /// @param path local mod archive
    /// @return installed path
    public @Unmodifiable Map<String, Object> installLocalAddon(String instanceId, String path) throws IOException {
        GameInstanceID id = id(instanceId);
        Path source = Path.of(path).toAbsolutePath().normalize();
        ModManager manager = repository.getModManager(id);
        manager.addMod(source);
        return Map.of("path", manager.getDirectory().resolve(source.getFileName()).toAbsolutePath().normalize().toString());
    }

    /// Enables one mod file through ModManager's `.disabled` transition.
    ///
    /// @param instanceId target instance
    /// @param path mod file path
    /// @return resulting file path
    public String enableMod(String instanceId, String path) throws IOException {
        return transitionMod(id(instanceId), path, true).toString();
    }

    /// Disables one mod file through ModManager's `.disabled` transition.
    ///
    /// @param instanceId target instance
    /// @param path mod file path
    /// @return resulting file path
    public String disableMod(String instanceId, String path) throws IOException {
        return transitionMod(id(instanceId), path, false).toString();
    }

    /// Removes selected local mod files after the caller has confirmed the high-impact operation.
    ///
    /// @param instanceId target instance
    /// @param paths files to remove
    /// @return removed paths
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

    /// Installs a local modpack through the format-specific Modpack provider.
    ///
    /// @param instanceId target/new instance identifier
    /// @param path local zip or mrpack path
    /// @return instance and source metadata
    public @Unmodifiable Map<String, Object> installLocalModpack(String instanceId, String path) throws Exception {
        GameInstanceID id = id(instanceId);
        Path archive = Path.of(path).toAbsolutePath().normalize();
        Modpack modpack = ModpackHelper.readModpackManifest(archive, StandardCharsets.UTF_8);
        DefaultDependencyManager dependency = repository.getDependency();
        Task<?> task = modpack.getInstallTask(dependency, archive, id, null);
        runTask(task);
        repository.refresh();
        return Map.of("instance_id", id.id(), "path", archive.toString(), "name", modpack.getName());
    }

    /// Installs a base game version into an instance after explicit confirmation.
    ///
    /// @param instanceId target instance
    /// @param gameVersion base version
    /// @return resulting instance identifier
    public @Unmodifiable Map<String, Object> installGameVersion(String instanceId, String gameVersion) throws Exception {
        requireInstance(id(instanceId));
        DefaultDependencyManager dependency = repository.getDependency();
        runTask(dependency.newGameBuilder().name(id(instanceId)).gameVersion(gameVersion).buildAsync());
        repository.refresh();
        return Map.of("instance_id", instanceId, "game_version", gameVersion);
    }

    /// Installs a selected ModLoader version into an instance after explicit confirmation.
    ///
    /// @param instanceId target instance
    /// @param gameVersion target game version
    /// @param loader loader identifier
    /// @param loaderVersion loader version
    /// @return resulting instance identifier
    public @Unmodifiable Map<String, Object> installModloader(
            String instanceId, String gameVersion, String loader, String loaderVersion) throws Exception {
        DefaultDependencyManager dependency = repository.getDependency();
        runTask(dependency.newGameBuilder()
                .name(id(instanceId))
                .gameVersion(gameVersion)
                .version(loader, loaderVersion)
                .buildAsync());
        repository.refresh();
        return Map.of("instance_id", instanceId, "game_version", gameVersion,
                "loader", loader, "loader_version", loaderVersion);
    }

    /// Creates a new instance and installs its requested game and optional loader.
    ///
    /// @param instanceId new instance identifier
    /// @param gameVersion base game version
    /// @param loader optional loader identifier
    /// @param loaderVersion optional loader version
    /// @return resulting instance identifier
    public @Unmodifiable Map<String, Object> createInstance(
            String instanceId,
            String gameVersion,
            @Nullable String loader,
            @Nullable String loaderVersion) throws Exception {
        GameInstanceID id = id(instanceId);
        if (repository.hasInstance(id)) {
            throw new IllegalArgumentException("Instance already exists: " + instanceId);
        }
        DefaultDependencyManager dependency = repository.getDependency();
        var builder = dependency.newGameBuilder().name(id).gameVersion(gameVersion);
        if (loader != null && !loader.isBlank()) {
            if (loaderVersion == null || loaderVersion.isBlank()) {
                throw new IllegalArgumentException("loaderVersion is required when loader is provided");
            }
            builder.version(loader, loaderVersion);
        }
        runTask(builder.buildAsync());
        repository.refresh();
        return Map.of("instance_id", instanceId, "game_version", gameVersion,
                "loader", loader == null ? "" : loader);
    }

    /// Starts an instance with the launcher-generated options and captures monitor state.
    ///
    /// @param instanceId target instance
    /// @return launch acceptance and process metadata
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
                "running", state.process.isRunning(), "command", List.copyOf(state.process.getCommands()));
    }

    /// Stops a running instance process if one is tracked.
    ///
    /// @param instanceId target instance
    /// @return whether a process was stopped
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
                "logs", List.copyOf(state.logs));
    }

    /// Reads a supported `xyml://` resource URI.
    ///
    /// @param uri resource URI
    /// @return resource URI, MIME type, and text
    public @Unmodifiable Map<String, String> readResource(String uri) throws IOException {
        Matcher logMatcher = LOG_RESOURCE.matcher(uri);
        if (logMatcher.matches()) {
            GameInstanceID id = id(logMatcher.group(1));
            Path path = latestLog(id);
            return Map.of("uri", uri, "mime_type", "text/plain", "text", readIfPresent(path));
        }
        Matcher crashMatcher = CRASH_RESOURCE.matcher(uri);
        if (crashMatcher.matches()) {
            GameInstanceID id = id(crashMatcher.group(1));
            Path root = repository.getRunDirectory(id).resolve("crash-reports").toAbsolutePath().normalize();
            Path path = latestCrashReport(root);
            return Map.of("uri", uri, "mime_type", "text/plain", "text", readIfPresent(path));
        }
        throw new IllegalArgumentException("Unsupported XYML resource URI: " + uri);
    }

    /// Returns the repository used by this service.
    ///
    /// @return repository reference
    public XYMLGameRepository repository() {
        return repository;
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

    /// Finds the newest regular crash report in one instance's crash-report directory.
    private static Path latestCrashReport(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return root.resolve("latest-crash-report.txt");
        }
        try (Stream<Path> paths = Files.list(root)) {
            return paths.filter(Files::isRegularFile)
                    .max(Comparator.comparingLong(XYMLMcpService::lastModifiedMillis))
                    .orElse(root.resolve("latest-crash-report.txt"));
        }
    }

    /// Returns a file modification timestamp without hiding directory read failures.
    private static long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return Long.MIN_VALUE;
        }
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

    /// Safely obtains a named regex group from a crash rule.
    private static String groupValue(Matcher matcher, String name) {
        try {
            @Nullable String value = matcher.group(name);
            return value == null ? "" : value;
        } catch (IllegalArgumentException ignored) {
            return "";
        }
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

    /// Returns one enum value with a default for omitted or blank sort names.
    private static <E extends Enum<E>> E enumValue(Class<E> type, @Nullable String value, String fallback) {
        String normalized = value == null || value.isBlank() ? fallback : value;
        try {
            return Enum.valueOf(type, normalized.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported value " + value + " for " + type.getSimpleName(), e);
        }
    }

    /// Converts a remote source name to the existing enum.
    private static RemoteAddon.Source addonSource(String source) {
        return enumValue(RemoteAddon.Source.class, source, "MODRINTH");
    }

    /// Converts an add-on type name to the existing enum.
    private static RemoteAddon.Type addonType(String type) {
        String normalized = type == null ? "MOD" : type.trim().replace('-', '_').replace(' ', '_');
        if ("RESOURCEPACK".equalsIgnoreCase(normalized)) {
            normalized = "RESOURCE_PACK";
        } else if ("SHADERPACK".equalsIgnoreCase(normalized)) {
            normalized = "SHADER_PACK";
        }
        return enumValue(RemoteAddon.Type.class, normalized, "MOD");
    }

    /// Selects a repository for one remote source and type.
    private static RemoteAddonRepository requireRepository(RemoteAddon.Source source, RemoteAddon.Type type) {
        @Nullable RemoteAddonRepository result = source.getRepoForType(type);
        if (result == null) {
            throw new IllegalArgumentException("The source does not support add-on type: " + type);
        }
        return result;
    }

    /// Converts one remote add-on to MCP-safe scalar data.
    private Map<String, Object> addonMap(RemoteAddon addon) {
        return Map.of("id", addonProjectId(addon), "slug", addon.slug(), "title", addon.title(), "author", addon.author(),
                "description", addon.description(), "categories", List.copyOf(addon.categories()),
                "page_url", addon.pageUrl(), "icon_url", addon.iconUrl(),
                "type", addon.type() == null ? "" : addon.type().name());
    }

    /// Returns the source-specific project identifier used by installation APIs.
    private static String addonProjectId(RemoteAddon addon) {
        if (addon.data() instanceof ModrinthRemoteAddonRepository.ProjectSearchResult result) {
            return result.projectId();
        }
        if (addon.data() instanceof CurseForgeRemoteAddonRepository.CurseAddon result) {
            return Integer.toString(result.id());
        }
        return addon.slug();
    }

    /// Converts one remote version to MCP-safe scalar data.
    private Map<String, Object> versionMap(RemoteAddon.Version version) {
        RemoteAddon.File file = version.file();
        return Map.of("project_id", version.projectId(), "version_id", version.version(),
                "name", version.name(), "game_versions", List.copyOf(version.gameVersions()),
                "loaders", version.loaders().stream().map(Enum::name).toList(),
                "file_url", file == null ? "" : file.url(),
                "filename", file == null ? "" : file.filename(),
                "hashes", file == null ? Map.of() : Map.copyOf(file.hashes()),
                "published_at", version.datePublished().toString());
    }

    /// Loads and maps one built-in version list.
    private @Unmodifiable List<Map<String, Object>> listVersions(String id, String gameVersion) {
        VersionList<?> versions = downloadProvider.getVersionListById(id);
        runTaskUnchecked(versions.loadAsync(gameVersion));
        List<Map<String, Object>> result = new ArrayList<>();
        for (RemoteVersion version : versions.getVersions(gameVersion)) {
            result.add(Map.of("id", version.getSelfVersion(), "game_version", version.getGameVersion(),
                    "library", version.getLibraryId(), "type", version.getVersionType().name(),
                    "release_date", version.getReleaseDate().toString(), "urls", List.copyOf(version.getUrls())));
        }
        return List.copyOf(result);
    }

    /// Runs an XYML task synchronously and propagates its failure.
    private static void runTask(Task<?> task) throws Exception {
        if (!task.test()) {
            @Nullable Exception failure = task.getException();
            throw failure == null ? new IllegalStateException("XYML task failed") : failure;
        }
    }

    /// Runs an XYML task from a non-throwing version-list API.
    private static void runTaskUnchecked(Task<?> task) {
        try {
            runTask(task);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to load remote version list", e);
        }
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
            if (logs.size() < MAX_LOG_LINES) {
                logs.add((isErrorStream ? "[stderr] " : "") + log);
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
