/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2024 huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.java;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonWriter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.Metadata;
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.download.LibraryAnalyzer;
import space.minecraftstl.xyml.game.GameJavaVersion;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.game.JavaVersionConstraint;
import space.minecraftstl.xyml.setting.SettingsManager;
import space.minecraftstl.xyml.observable.property.ObservableValue;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.CacheRepository;
import space.minecraftstl.xyml.util.DigestUtils;
import space.minecraftstl.xyml.util.Lang;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.io.FileUtils;
import space.minecraftstl.xyml.util.platform.Architecture;
import space.minecraftstl.xyml.util.platform.OperatingSystem;
import space.minecraftstl.xyml.util.platform.Platform;
import space.minecraftstl.xyml.util.platform.UnsupportedPlatformException;
import space.minecraftstl.xyml.util.platform.windows.WinReg;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Discovers, validates, installs, and selects Java runtimes for game launches.
@NotNullByDefault
public final class JavaManager {

    /// Prevents instantiation of this process-wide runtime manager.
    private JavaManager() {
    }

    /// Common Java vendor directory names searched below Windows Program Files roots.
    private static final String @Unmodifiable [] KNOWN_VENDOR_DIRECTORIES = {
            "Java",
            "BellSoft",
            "AdoptOpenJDK",
            "Zulu",
            "Microsoft",
            "Eclipse Foundation",
            "Semeru"
    };

    /// User-scoped repository for launcher-managed Java runtimes.
    public static final XYMLJavaRepository REPOSITORY = new XYMLJavaRepository(Metadata.XYML_USER_HOME.resolve("java"));

    /// Launcher-local repository for portable managed Java runtimes.
    public static final XYMLJavaRepository LOCAL_REPOSITORY = new XYMLJavaRepository(Metadata.XYML_LOCAL_HOME.resolve("java"));

    /// Maps a supported platform to Mojang's runtime directory identifier.
    ///
    /// @param platform target platform
    /// @return Mojang platform identifier, or `null` when unsupported
    public static @Nullable String getMojangJavaPlatform(Platform platform) {
        if (platform.getOperatingSystem() == OperatingSystem.WINDOWS) {
            if (Architecture.SYSTEM_ARCH == Architecture.X86) {
                return "windows-x86";
            } else if (Architecture.SYSTEM_ARCH == Architecture.X86_64) {
                return "windows-x64";
            } else if (Architecture.SYSTEM_ARCH == Architecture.ARM64) {
                return "windows-arm64";
            }
        } else if (platform.getOperatingSystem() == OperatingSystem.LINUX) {
            if (Architecture.SYSTEM_ARCH == Architecture.X86) {
                return "linux-i386";
            } else if (Architecture.SYSTEM_ARCH == Architecture.X86_64) {
                return "linux";
            }
        } else if (platform.getOperatingSystem() == OperatingSystem.MACOS) {
            if (Architecture.SYSTEM_ARCH == Architecture.X86_64) {
                return "mac-os";
            } else if (Architecture.SYSTEM_ARCH == Architecture.ARM64) {
                return "mac-os-arm64";
            }
        }

        return null;
    }

    /// Resolves the standard Java executable below a Java home directory.
    ///
    /// @param javaHome Java home directory
    /// @return platform-specific Java executable path
    public static Path getExecutable(Path javaHome) {
        return javaHome.resolve("bin").resolve(OperatingSystem.CURRENT_OS.getJavaExecutable());
    }

    /// Resolves the Java executable inside Mojang's legacy macOS bundle layout.
    ///
    /// @param javaHome directory containing the `jre.bundle`
    /// @return bundled Java executable path
    public static Path getMacExecutable(Path javaHome) {
        return javaHome.resolve("jre.bundle/Contents/Home/bin/java");
    }

    /// Returns whether a Java runtime platform can execute on the current system.
    ///
    /// @param platform runtime platform
    /// @return whether the operating system and architecture are compatible
    public static boolean isCompatible(Platform platform) {
        if (platform.getOperatingSystem() != OperatingSystem.CURRENT_OS)
            return false;

        Architecture architecture = platform.getArchitecture();
        if (architecture == Architecture.SYSTEM_ARCH || architecture == Architecture.CURRENT_ARCH)
            return true;

        switch (OperatingSystem.CURRENT_OS) {
            case WINDOWS:
                if (Architecture.SYSTEM_ARCH == Architecture.X86_64)
                    // Windows x86-64 platform is compatible with x86 programs
                    return architecture == Architecture.X86;
                if (Architecture.SYSTEM_ARCH == Architecture.ARM64) {

                    // Since Windows 10 Build 21277, Windows Arm64 has been compatible with x86-64 programs via translation
                    if (architecture == Architecture.X86_64 && Platform.isSupportedTranslationX86_64())
                        return true;

                    // Windows Arm64 is compatible with x86 programs via translation
                    if (architecture == Architecture.X86)
                        return true;
                    return false;
                }
                break;
            case LINUX:
                if (Architecture.SYSTEM_ARCH == Architecture.X86_64)
                    return architecture == Architecture.X86;
                break;
            case MACOS:
                // macOS Arm64 compatible with x86-64 programs via Rosetta 2.
                if (Architecture.SYSTEM_ARCH == Architecture.ARM64 && Platform.isSupportedTranslationX86_64())
                    return architecture == Architecture.X86_64;
                break;
        }

        return false;
    }

    /// Toolkit-neutral state and observable snapshots for discovered runtimes.
    private static final JavaRuntimeRegistry JAVA_RUNTIMES = new JavaRuntimeRegistry();

    /// Returns whether initial Java runtime discovery has completed.
    public static boolean isInitialized() {
        return JAVA_RUNTIMES.isInitialized();
    }

    /// Waits for initial discovery and returns the sorted immutable runtime snapshot.
    ///
    /// @return current sorted immutable runtimes
    /// @throws InterruptedException if the caller is interrupted before initialization
    public static @Unmodifiable List<JavaRuntime> getAllJava() throws InterruptedException {
        return JAVA_RUNTIMES.awaitRuntimes();
    }

    /// Returns the toolkit-neutral observable runtime snapshot.
    ///
    /// @return process-wide runtime snapshot observable
    public static ObservableValue<JavaRuntimeSnapshot> getAllJavaSnapshotObservable() {
        return JAVA_RUNTIMES.snapshotProperty();
    }

    /// Resolves and probes one Java executable, using the registry snapshot when available.
    ///
    /// @param executable Java executable path
    /// @return discovered runtime metadata
    /// @throws IOException if the executable cannot be resolved or probed
    /// @throws InterruptedException if runtime initialization is interrupted
    public static JavaRuntime getJava(Path executable) throws IOException, InterruptedException {
        executable = executable.toRealPath();

        @Nullable JavaRuntime javaRuntime = JAVA_RUNTIMES.awaitRuntime(executable);
        if (javaRuntime != null) {
            return javaRuntime;
        }

        JavaInfo info = JavaInfoUtils.fromExecutable(executable);
        return JavaRuntime.of(executable, info, false);
    }

    /// Starts a background rescan of all potential Java runtime locations.
    public static void refresh() {
        Task.runAsync(() -> {
            JavaRuntimeRegistry.RefreshTicket ticket = JAVA_RUNTIMES.beginRefresh();
            try {
                JAVA_RUNTIMES.completeRefresh(ticket, searchPotentialJavaExecutables(false));
            } finally {
                JAVA_RUNTIMES.cancelRefresh(ticket);
            }
        }).start();
    }

    /// Creates a task that validates and registers a user-selected Java executable.
    ///
    /// @param binary Java executable path
    /// @return registration task yielding the discovered runtime
    public static Task<JavaRuntime> getAddJavaTask(Path binary) {
        return Task.supplyAsync("Get Java", () -> JavaManager.getJava(binary))
                .thenApplyAsync(Schedulers.ui(), javaRuntime -> {
                    if (!JavaManager.isCompatible(javaRuntime.getPlatform())) {
                        throw new UnsupportedPlatformException("Incompatible platform: " + javaRuntime.getPlatform());
                    }

                    String pathString = javaRuntime.getBinary().toString();

                    if (SettingsManager.registerUserJavaPath(pathString)) {
                        addJava(javaRuntime);
                    }
                    return javaRuntime;
                });
    }

    /// Creates a task that downloads and registers a managed Java runtime.
    ///
    /// @param downloadProvider artifact download provider
    /// @param platform target platform
    /// @param gameJavaVersion requested game Java component
    /// @return download and registration task
    public static Task<JavaRuntime> getDownloadJavaTask(DownloadProvider downloadProvider, Platform platform, GameJavaVersion gameJavaVersion) {
        return REPOSITORY.getDownloadJavaTask(downloadProvider, platform, gameJavaVersion)
                .thenApplyAsync(Schedulers.ui(), java -> {
                    addJava(java);
                    return java;
                });
    }

    /// Creates a task that installs and registers Java from an existing archive.
    ///
    /// @param platform target platform
    /// @param name managed runtime name
    /// @param update runtime metadata
    /// @param archiveFile downloaded runtime archive
    /// @return installation and registration task
    public static Task<JavaRuntime> getInstallJavaTask(Platform platform, String name, Map<String, Object> update, Path archiveFile) {
        return REPOSITORY.getInstallJavaTask(platform, name, update, archiveFile)
                .thenApplyAsync(Schedulers.ui(), java -> {
                    addJava(java);
                    return java;
                });
    }

    /// Creates a task that unregisters and deletes a launcher-managed Java runtime.
    ///
    /// @param java managed runtime
    /// @return uninstall task, or an already-completed task when the runtime is outside the repository
    public static Task<Void> getUninstallJavaTask(JavaRuntime java) {
        assert java.isManaged();

        Path platformRoot;
        try {
            platformRoot = REPOSITORY.getPlatformRoot(java.getPlatform()).toRealPath();
        } catch (Throwable ignored) {
            return Task.completed(null);
        }

        if (!java.getBinary().startsWith(platformRoot))
            return Task.completed(null);

        Path relativized = platformRoot.relativize(java.getBinary());
        if (relativized.getNameCount() > 1) {
            String name = relativized.getName(0).toString();
            return Task.composeAsync(() -> {
                removeJava(java);
                return REPOSITORY.getUninstallJavaTask(java.getPlatform(), name);
            });
        } else {
            return Task.completed(null);
        }
    }

    /// Adds a runtime to the observable registry.
    ///
    /// @param java runtime to add
    /// @throws InterruptedException if registry initialization is interrupted
    public static void addJava(JavaRuntime java) throws InterruptedException {
        JAVA_RUNTIMES.add(java);
    }

    /// Removes a runtime from the observable registry.
    ///
    /// @param java runtime to remove
    /// @throws InterruptedException if registry initialization is interrupted
    public static void removeJava(JavaRuntime java) throws InterruptedException {
        removeJava(java.getBinary());
    }

    /// Removes the runtime registered at a canonical executable path.
    ///
    /// @param realPath canonical Java executable path
    /// @throws InterruptedException if registry initialization is interrupted
    public static void removeJava(Path realPath) throws InterruptedException {
        JAVA_RUNTIMES.remove(realPath);
    }

    /// Chooses the newer patch level nearest the recommended major Java version.
    ///
    /// @param java1 current candidate, or `null` when no candidate exists
    /// @param java2 new candidate
    /// @return preferred candidate
    private static JavaRuntime chooseJava(@Nullable JavaRuntime java1, JavaRuntime java2) {
        if (java1 == null)
            return java2;

        if (java1.getParsedVersion() != java2.getParsedVersion())
            // Prefer the Java version that is closer to the game's recommended Java version
            return java1.getParsedVersion() < java2.getParsedVersion() ? java1 : java2;
        else
            return java1.getVersionNumber().compareTo(java2.getVersionNumber()) >= 0 ? java1 : java2;
    }

    /// Selects a compatible Java runtime for a game from the process-wide registry.
    ///
    /// @param gameVersion parsed game version, or `null` when unknown
    /// @param manifest complete instance manifest, or `null` when unavailable
    /// @return preferred runtime, or `null` when no compatible runtime exists
    /// @throws InterruptedException if runtime initialization is interrupted
    @Nullable
    public static JavaRuntime findSuitableJava(
            @Nullable GameVersionNumber gameVersion,
            @Nullable GameInstanceManifest manifest) throws InterruptedException {
        return findSuitableJava(getAllJava(), gameVersion, manifest);
    }

    /// Selects a compatible Java runtime for a game from explicit candidates.
    ///
    /// @param javaRuntimes candidate runtimes
    /// @param gameVersion parsed game version, or `null` when unknown
    /// @param manifest complete instance manifest, or `null` when unavailable
    /// @return preferred runtime, or `null` when no compatible runtime exists
    @Nullable
    public static JavaRuntime findSuitableJava(
            Collection<JavaRuntime> javaRuntimes,
            @Nullable GameVersionNumber gameVersion,
            @Nullable GameInstanceManifest manifest) {
        @Nullable LibraryAnalyzer analyzer = manifest != null
                ? LibraryAnalyzer.analyze(manifest, gameVersion != null ? gameVersion.toString() : null)
                : null;

        boolean forceX86 = Architecture.SYSTEM_ARCH == Architecture.ARM64
                && (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS || OperatingSystem.CURRENT_OS == OperatingSystem.MACOS)
                && (gameVersion == null || gameVersion.compareTo("1.6") < 0);

        @Nullable JavaRuntime mandatory = null;
        @Nullable JavaRuntime suggested = null;
        for (JavaRuntime java : javaRuntimes) {
            if (forceX86) {
                if (!java.getArchitecture().isX86())
                    continue;
            } else {
                if (java.getArchitecture() != Architecture.SYSTEM_ARCH)
                    continue;
            }

            boolean violationMandatory = false;
            boolean violationSuggested = false;

            for (JavaVersionConstraint constraint : JavaVersionConstraint.ALL) {
                if (constraint.appliesToVersion(gameVersion, manifest, java, analyzer)) {
                    if (!constraint.checkJava(gameVersion, manifest, java, analyzer)) {
                        if (constraint.isMandatory()) {
                            violationMandatory = true;
                        } else {
                            violationSuggested = true;
                        }
                    }
                }
            }

            if (!violationMandatory) {
                mandatory = chooseJava(mandatory, java);

                if (!violationSuggested)
                    suggested = chooseJava(suggested, java);
            }
        }

        return suggested != null ? suggested : mandatory;
    }

    /// Performs initial synchronous runtime discovery and publishes the first registry snapshot.
    public static void initialize() {
        Map<Path, JavaRuntime> allJava = searchPotentialJavaExecutables(true);
        JAVA_RUNTIMES.initialize(allJava);
    }

    /// Searches managed repositories, operating-system locations, environment paths, and user entries.
    ///
    /// @param useCache whether valid cached runtime metadata may avoid probing executables
    /// @return runtimes indexed by canonical executable path
    private static Map<Path, JavaRuntime> searchPotentialJavaExecutables(boolean useCache) {
        Searcher searcher = new Searcher(Metadata.XYML_USER_HOME.resolve("javaCache.json"));
        if (useCache)
            searcher.loadCache();

        searcher.searchAllJavaInRepository(Platform.SYSTEM_PLATFORM);
        switch (OperatingSystem.CURRENT_OS) {
            case WINDOWS:
                if (Architecture.SYSTEM_ARCH == Architecture.X86_64)
                    searcher.searchAllJavaInRepository(Platform.WINDOWS_X86);
                if (Architecture.SYSTEM_ARCH == Architecture.ARM64) {
                    if (Platform.isSupportedTranslationX86_64())
                        searcher.searchAllJavaInRepository(Platform.WINDOWS_X86_64);
                    searcher.searchAllJavaInRepository(Platform.WINDOWS_X86);
                }
                break;
            case MACOS:
                if (Architecture.SYSTEM_ARCH == Architecture.ARM64 && Platform.isSupportedTranslationX86_64())
                    searcher.searchAllJavaInRepository(Platform.MACOS_X86_64);
                break;
        }

        switch (OperatingSystem.CURRENT_OS) {
            case WINDOWS:
                searcher.queryJavaInRegistryKey(WinReg.HKEY.HKEY_LOCAL_MACHINE, "SOFTWARE\\JavaSoft\\Java Runtime Environment");
                searcher.queryJavaInRegistryKey(WinReg.HKEY.HKEY_LOCAL_MACHINE, "SOFTWARE\\JavaSoft\\Java Development Kit");
                searcher.queryJavaInRegistryKey(WinReg.HKEY.HKEY_LOCAL_MACHINE, "SOFTWARE\\JavaSoft\\JRE");
                searcher.queryJavaInRegistryKey(WinReg.HKEY.HKEY_LOCAL_MACHINE, "SOFTWARE\\JavaSoft\\JDK");

                searcher.searchJavaInProgramFiles("ProgramFiles", "C:\\Program Files");
                searcher.searchJavaInProgramFiles("ProgramFiles(x86)", "C:\\Program Files (x86)");
                break;
            case LINUX:
                searcher.searchAllJavaInDirectory(Path.of("/usr/java"));      // Oracle RPMs
                searcher.searchAllJavaInDirectory(Path.of("/usr/lib/jvm"));   // General locations
                searcher.searchAllJavaInDirectory(Path.of("/usr/lib32/jvm")); // General locations
                searcher.searchAllJavaInDirectory(Path.of("/usr/lib64/jvm")); // General locations
                searcher.searchAllJavaInDirectory(Path.of(System.getProperty("user.home"), "/.sdkman/candidates/java")); // SDKMAN!
                break;
            case MACOS:
                searcher.searchJavaInMacJavaVirtualMachines(Path.of("/Library/Java/JavaVirtualMachines"));
                searcher.searchJavaInMacJavaVirtualMachines(Path.of(System.getProperty("user.home"), "/Library/Java/JavaVirtualMachines"));
                searcher.tryAddJavaExecutable(Path.of("/Library/Internet Plug-Ins/JavaAppletPlugin.plugin/Contents/Home/bin/java"));
                searcher.tryAddJavaExecutable(Path.of("/Applications/Xcode.app/Contents/Applications/Application Loader.app/Contents/MacOS/itms/java/bin/java"));
                // Homebrew
                searcher.tryAddJavaExecutable(Path.of("/opt/homebrew/opt/java/bin/java"));
                searcher.searchAllJavaInDirectory(Path.of("/opt/homebrew/Cellar/openjdk"));
                try (DirectoryStream<Path> dirs = Files.newDirectoryStream(Path.of("/opt/homebrew/Cellar"), "openjdk@*")) {
                    for (Path dir : dirs) {
                        searcher.searchAllJavaInDirectory(dir);
                    }
                } catch (IOException e) {
                    LOG.warning("Failed to get subdirectories of /opt/homebrew/Cellar");
                }
                break;

            default:
                break;
        }

        // Search Minecraft bundled runtimes
        if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS && Architecture.SYSTEM_ARCH.isX86()) {
            FileUtils.tryGetPath(System.getenv("localappdata"), "Packages\\Microsoft.4297127D64EC6_8wekyb3d8bbwe\\LocalCache\\Local\\runtime")
                    .ifPresent(it -> searcher.searchAllOfficialJava(it, false));

            FileUtils.tryGetPath(Lang.requireNonNullElse(System.getenv("ProgramFiles(x86)"), "C:\\Program Files (x86)"), "Minecraft Launcher\\runtime")
                    .ifPresent(it -> searcher.searchAllOfficialJava(it, false));
        } else if (OperatingSystem.CURRENT_OS == OperatingSystem.LINUX && Architecture.SYSTEM_ARCH == Architecture.X86_64) {
            searcher.searchAllOfficialJava(Path.of(System.getProperty("user.home"), ".minecraft/runtime"), false);
        } else if (OperatingSystem.CURRENT_OS == OperatingSystem.MACOS) {
            searcher.searchAllOfficialJava(Path.of(System.getProperty("user.home"), "Library/Application Support/minecraft/runtime"), false);
        }
        searcher.searchAllOfficialJava(CacheRepository.getInstance().getCacheDirectory().resolve("java"), true);

        // Search in PATH.
        if (System.getenv("PATH") != null) {
            String @Unmodifiable [] paths = System.getenv("PATH").split(File.pathSeparator);
            for (String path : paths) {
                // https://github.com/HMCL-dev/HMCL/issues/4079
                // https://github.com/Meloong-Git/PCL/issues/4261
                if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS && path.toLowerCase(Locale.ROOT)
                        .contains("\\common files\\oracle\\java\\")) {
                    continue;
                }

                try {
                    searcher.tryAddJavaExecutable(Path.of(path, OperatingSystem.CURRENT_OS.getJavaExecutable()));
                } catch (InvalidPathException ignored) {
                }
            }
        }

        if (System.getenv("XYML_JRES") != null) {
            String @Unmodifiable [] paths = System.getenv("XYML_JRES").split(File.pathSeparator);
            for (String path : paths) {
                try {
                    searcher.tryAddJavaHome(Path.of(path));
                } catch (InvalidPathException ignored) {
                }
            }
        }

        searcher.searchAllJavaInDirectory(Path.of(System.getProperty("user.home"), ".jdks"));

        for (String javaPath : SettingsManager.getUserJavaPathsSnapshot()) {
            try {
                searcher.tryAddJavaExecutable(Path.of(javaPath));
            } catch (InvalidPathException e) {
                LOG.warning("Invalid Java path: " + javaPath);
            }
        }

        @Nullable JavaRuntime currentJava = JavaRuntime.CURRENT_JAVA;
        if (currentJava != null) {
            if (Metadata.PACKAGED) {
                // A jlink image contains only launcher modules and cannot run arbitrary Minecraft versions.
                searcher.javaRuntimes.remove(currentJava.getBinary());
            } else if (!searcher.javaRuntimes.containsKey(currentJava.getBinary())
                    && !SettingsManager.isUserJavaPathDisabled(currentJava.getBinary().toString())) {
                searcher.addResult(currentJava.getBinary(), currentJava);
            }
        }

        searcher.saveCache();

        LOG.trace(searcher.javaRuntimes.values().stream().sorted()
                .map(it -> String.format(" - %s %s (%s, %s): %s",
                        it.isJDK() ? "JDK" : "JRE",
                        it.getVersion(),
                        it.getPlatform().getArchitecture().getDisplayName(),
                        Lang.requireNonNullElse(it.getVendor(), "Unknown"),
                        it.getBinary()))
                .collect(Collectors.joining("\n", "Finished Java lookup, found " + searcher.javaRuntimes.size() + "\n", "")));
        return searcher.javaRuntimes;
    }

    /// Mutable accumulator that discovers runtimes and maintains the executable metadata cache.
    @NotNullByDefault
    private static final class Searcher {
        /// JSON cache file used across launcher runs.
        private final Path cacheFile;

        /// Successfully discovered runtimes indexed by canonical executable path.
        final Map<Path, JavaRuntime> javaRuntimes = new HashMap<>();

        /// Cached executable metadata indexed by canonical executable path.
        private final LinkedHashMap<Path, JavaInfoCache> caches = new LinkedHashMap<>();

        /// Executables that failed probing during this search.
        private final Set<Path> failed = new HashSet<>();

        /// Whether the persistent cache must be rewritten after discovery.
        private boolean needRefreshCache = false;

        /// Creates a runtime search accumulator.
        ///
        /// @param cacheFile persistent executable metadata cache
        Searcher(Path cacheFile) {
            this.cacheFile = cacheFile;
        }

        /// Pattern for supported major and optional minor cache schema versions.
        private static final Pattern CACHE_VERSION_PATTERN = Pattern.compile("(?<major>\\d+)(?:\\.(?<minor>\\d+))?");

        /// Cache schema major version; mismatches invalidate the cache.
        private static final int CACHE_MAJOR_VERSION = 0;

        /// Minimum readable cache schema minor version.
        private static final int CACHE_MINOR_VERSION = 0;

        /// Cached metadata for one canonical Java executable.
        ///
        /// @param key fingerprint of executable and runtime library files
        /// @param info probed Java metadata
        @NotNullByDefault
        private record JavaInfoCache(String key, JavaInfo info) {
        }

        /// Loads valid cached runtime metadata and marks stale data for replacement.
        void loadCache() {
            if (Files.notExists(cacheFile))
                return;

            try {
                @Nullable JsonObject jsonFile = JsonUtils.fromJsonFile(cacheFile, JsonObject.class);
                @Nullable JsonElement fileVersion = Objects.requireNonNull(jsonFile).get("version");

                Matcher matcher;
                if (fileVersion instanceof JsonPrimitive version
                        && (matcher = CACHE_VERSION_PATTERN.matcher(version.getAsString())).matches()) {
                    int major = Integer.parseInt(matcher.group("major"));

                    @Nullable String minorString = matcher.group("minor");
                    int minor = minorString != null ? Integer.parseInt(minorString) : 0;

                    if (major != CACHE_MAJOR_VERSION || minor < CACHE_MINOR_VERSION)
                        throw new IOException("Unsupported cache file, version: %s".formatted(version.getAsString()));
                } else
                    throw new IOException("Invalid version JSON: " + fileVersion);

                @Nullable JsonArray cachesArray = jsonFile.getAsJsonArray("caches");

                for (JsonElement element : Objects.requireNonNull(cachesArray)) {
                    try {
                        var obj = (JsonObject) element;

                        Path realPath = Path.of(obj.getAsJsonPrimitive("path").getAsString()).toRealPath();
                        String key = obj.getAsJsonPrimitive("key").getAsString();

                        OperatingSystem osName = OperatingSystem.parseOSName(obj.getAsJsonPrimitive("os.name").getAsString());
                        Architecture osArch = Architecture.parseArchName(obj.getAsJsonPrimitive("os.arch").getAsString());
                        String javaVersion = obj.getAsJsonPrimitive("java.version").getAsString();

                        JavaInfo.Builder infoBuilder = JavaInfo.newBuilder(Platform.getPlatform(osName, osArch), javaVersion);

                        if (obj.get("java.vendor") instanceof JsonPrimitive vendor)
                            infoBuilder.setVendor(vendor.getAsString());

                        caches.put(realPath, new JavaInfoCache(key, infoBuilder.build()));
                    } catch (Exception e) {
                        LOG.warning("Invalid cache: " + element);
                        needRefreshCache = true;
                    }
                }
            } catch (Exception ex) {
                LOG.warning("Failed to load cache file: " + cacheFile);
                needRefreshCache = true;
            }
        }

        /// Writes the refreshed executable metadata cache when discovery changed it.
        void saveCache() {
            if (!needRefreshCache)
                return;

            needRefreshCache = false;
            try {
                FileUtils.saveSafely(cacheFile, output -> {
                    try (var writer = new JsonWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
                        writer.beginObject();

                        writer.name("version").value("%d.%d".formatted(CACHE_MAJOR_VERSION, CACHE_MINOR_VERSION));

                        writer.name("caches");
                        writer.beginArray();
                        for (Map.Entry<Path, JavaInfoCache> entry : caches.entrySet()) {
                            Path path = entry.getKey();
                            JavaInfoCache cache = entry.getValue();
                            JavaInfo info = cache.info();

                            writer.beginObject();

                            writer.name("path").value(path.toString());
                            writer.name("key").value(cache.key());

                            writer.name("os.name").value(info.getPlatform().os().getCheckedName());
                            writer.name("os.arch").value(info.getPlatform().arch().getCheckedName());
                            writer.name("java.version").value(info.getVersion());
                            if (info.getVendor() != null)
                                writer.name("java.vendor").value(info.getVendor());

                            writer.endObject();
                        }
                        writer.endArray();

                        writer.endObject();
                    }
                });
            } catch (Exception e) {
                LOG.warning("Failed to save cache file: " + cacheFile);
            }
        }

        /// Creates a stable fingerprint for a Java executable and its runtime files.
        ///
        /// @param realPath canonical Java executable path
        /// @return cache fingerprint, or `null` when the runtime layout cannot be fingerprinted
        private static @Nullable String createCacheKey(Path realPath) {
            @Nullable Path binDir = realPath.getParent();
            if (binDir == null || !FileUtils.getName(binDir).equals("bin"))
                return null;

            if (Files.isRegularFile(realPath.resolveSibling("ikvm.properties")))
                return null;

            @Nullable Path javaHome = binDir.getParent();
            if (javaHome == null)
                return null;

            Path libDir = javaHome.resolve("lib");
            if (!Files.isDirectory(libDir))
                return null;

            BasicFileAttributes launcherAttributes;
            @Nullable String releaseHash = null;
            @Nullable BasicFileAttributes coreLibsAttributes = null;

            try {
                launcherAttributes = Files.readAttributes(realPath, BasicFileAttributes.class);

                Path releaseFile = javaHome.resolve("release");
                if (Files.exists(releaseFile)) {
                    releaseHash = DigestUtils.digestToString("SHA-1", releaseFile);
                } else {
                    Path coreLibsFile = libDir.resolve("rt.jar");
                    if (!Files.isRegularFile(coreLibsFile)) {
                        coreLibsFile = javaHome.resolve("jre/lib/rt.jar");
                        if (!Files.isRegularFile(coreLibsFile))
                            return null;

                        coreLibsAttributes = Files.readAttributes(coreLibsFile, BasicFileAttributes.class);
                    }
                }
            } catch (Exception e) {
                LOG.warning("Failed to create cache key for " + realPath, e);
                return null;
            }

            StringJoiner joiner = new StringJoiner(",");

            joiner.add("sz:" + launcherAttributes.size());
            joiner.add("lm:" + launcherAttributes.lastModifiedTime().toMillis());

            if (releaseHash != null)
                joiner.add(releaseHash);

            if (coreLibsAttributes != null) {
                joiner.add("rsz:" + coreLibsAttributes.size());
                joiner.add("rlm:" + coreLibsAttributes.lastModifiedTime().toMillis());
            }

            return joiner.toString();
        }

        /// Adds a prevalidated runtime to the search result.
        ///
        /// @param realPath canonical Java executable path
        /// @param javaRuntime runtime metadata
        void addResult(Path realPath, JavaRuntime javaRuntime) {
            javaRuntimes.put(realPath, javaRuntime);
        }

        /// Probes the standard Java executable below a Java home directory.
        ///
        /// @param javaHome Java home directory
        void tryAddJavaHome(Path javaHome) {
            tryAddJavaExecutable(getExecutable(javaHome));
        }

        /// Probes an unmanaged Java executable.
        ///
        /// @param executable candidate executable path
        void tryAddJavaExecutable(Path executable) {
            tryAddJavaExecutable(executable, false);
        }

        /// Adds a compatible runtime from cache or by probing its executable.
        ///
        /// @param executable candidate executable path
        /// @param isManaged whether the launcher owns the runtime installation
        void tryAddJavaExecutable(Path executable, boolean isManaged) {
            try {
                executable = executable.toRealPath();
            } catch (IOException e) {
                return;
            }

            if (javaRuntimes.containsKey(executable)
                    || failed.contains(executable)
                    || SettingsManager.isUserJavaPathDisabled(executable.toString())) {
                return;
            }

            @Nullable String cacheKey = createCacheKey(executable);
            if (cacheKey != null) {
                @Nullable JavaInfoCache cache = caches.get(executable);
                if (cache != null) {
                    if (isCompatible(cache.info().getPlatform()) && cacheKey.equals(cache.key())) {
                        javaRuntimes.put(executable, JavaRuntime.of(executable, cache.info(), isManaged));
                        return;
                    } else {
                        caches.remove(executable);
                        needRefreshCache = true;
                    }
                }
            } else if (caches.remove(executable) != null) {
                needRefreshCache = true;
            }

            JavaInfo info;
            try {
                info = JavaInfoUtils.fromExecutable(executable);
            } catch (IOException e) {
                LOG.warning("Failed to lookup Java executable at " + executable, e);
                failed.add(executable);
                return;
            }

            if (cacheKey != null) {
                caches.put(executable, new JavaInfoCache(cacheKey, info));
                needRefreshCache = true;
            }

            javaRuntimes.put(executable, JavaRuntime.of(executable, info, isManaged));
        }

        /// Discovers a Mojang runtime component after optionally checking its file manifest.
        ///
        /// @param platform Mojang platform identifier
        /// @param component runtime component root
        /// @param verify whether every manifest entry must exist
        void tryAddJavaInComponentDir(String platform, Path component, boolean verify) {
            Path sha1File = component.resolve(platform).resolve(component.getFileName() + ".sha1");
            if (!Files.isRegularFile(sha1File))
                return;

            Path dir = component.resolve(platform).resolve(component.getFileName());

            if (verify) {
                try (BufferedReader reader = Files.newBufferedReader(sha1File)) {
                    @Nullable String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.isEmpty()) continue;

                        int idx = line.indexOf(" /#//");
                        if (idx <= 0)
                            throw new IOException("Illegal line: " + line);

                        Path file = dir.resolve(line.substring(0, idx));

                        // Should we check the sha1 of files? This will take a lot of time.
                        if (Files.notExists(file))
                            throw new NoSuchFileException(file.toAbsolutePath().toString());
                    }
                } catch (IOException e) {
                    LOG.warning("Failed to verify Java in " + component, e);
                    return;
                }
            }

            if (OperatingSystem.CURRENT_OS == OperatingSystem.MACOS) {
                Path macPath = dir.resolve("jre.bundle/Contents/Home");
                if (Files.exists(macPath)) {
                    tryAddJavaHome(macPath);
                    return;
                } else
                    LOG.warning("The Java is not in 'jre.bundle/Contents/Home'");
            }

            tryAddJavaHome(dir);
        }

        /// Adds every managed runtime compatible with a platform from both repositories.
        ///
        /// @param platform target platform
        void searchAllJavaInRepository(Platform platform) {
            for (Path java : REPOSITORY.getAllJava(platform)) {
                tryAddJavaExecutable(java, true);
            }

            for (Path java : LOCAL_REPOSITORY.getAllJava(platform)) {
                tryAddJavaExecutable(java, true);
            }

            if (platform.os() == OperatingSystem.MACOS) {
                // In the past, we used 'osx' as the checked name for macOS
                Path platformRoot = REPOSITORY.getPlatformRoot(platform).resolveSibling("osx-" + platform.getArchitecture().getCheckedName());
                searchAllJavaInDirectory(platformRoot);
            }
        }

        /// Searches Mojang launcher runtime components for all compatible architectures.
        ///
        /// @param directory Mojang runtime root
        /// @param verify whether component manifests must be verified
        void searchAllOfficialJava(Path directory, boolean verify) {
            if (!Files.isDirectory(directory))
                return;
            // Examples:
            // $HOME/Library/Application Support/minecraft/runtime/java-runtime-beta/mac-os/java-runtime-beta/jre.bundle/Contents/Home
            // $HOME/.minecraft/runtime/java-runtime-beta/linux/java-runtime-beta

            @Nullable String javaPlatform = getMojangJavaPlatform(Platform.SYSTEM_PLATFORM);
            if (javaPlatform != null) {
                searchAllOfficialJava(directory, javaPlatform, verify);
            }

            if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
                if (Architecture.SYSTEM_ARCH == Architecture.X86_64) {
                    searchAllOfficialJava(directory, getMojangJavaPlatform(Platform.WINDOWS_X86), verify);
                } else if (Architecture.SYSTEM_ARCH == Architecture.ARM64) {
                    if (Platform.isSupportedTranslationX86_64()) {
                        searchAllOfficialJava(directory, getMojangJavaPlatform(Platform.WINDOWS_X86_64), verify);
                    }
                    searchAllOfficialJava(directory, getMojangJavaPlatform(Platform.WINDOWS_X86), verify);
                }
            } else if (OperatingSystem.CURRENT_OS == OperatingSystem.MACOS) {
                if (Architecture.SYSTEM_ARCH == Architecture.ARM64 && Platform.isSupportedTranslationX86_64()) {
                    searchAllOfficialJava(directory, getMojangJavaPlatform(Platform.MACOS_X86_64), verify);
                }
            }
        }

        /// Searches every component below a Mojang runtime root for one platform identifier.
        ///
        /// @param directory Mojang runtime root
        /// @param platform Mojang platform identifier
        /// @param verify whether component manifests must be verified
        void searchAllOfficialJava(Path directory, String platform, boolean verify) {
            try (DirectoryStream<Path> dir = Files.newDirectoryStream(directory)) {
                // component can be jre-legacy, java-runtime-alpha, java-runtime-beta, java-runtime-gamma or any other being added in the future.
                for (Path component : dir) {
                    tryAddJavaInComponentDir(platform, component, verify);
                }
            } catch (IOException e) {
                LOG.warning("Failed to list java-runtime directory " + directory, e);
            }
        }

        /// Probes each direct child of a directory as a Java home.
        ///
        /// @param directory directory containing Java installations
        void searchAllJavaInDirectory(Path directory) {
            if (!Files.isDirectory(directory)) {
                return;
            }

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                for (Path subDir : stream) {
                    tryAddJavaHome(subDir);
                }
            } catch (IOException e) {
                LOG.warning("Failed to find Java in " + directory, e);
            }
        }

        /// Searches known vendor directories below a Windows Program Files root.
        ///
        /// @param env environment variable naming the root
        /// @param defaultValue fallback root when the environment variable is absent
        void searchJavaInProgramFiles(String env, String defaultValue) {
            String programFiles = Lang.requireNonNullElse(System.getenv(env), defaultValue);
            Path path;
            try {
                path = Path.of(programFiles);
            } catch (InvalidPathException ignored) {
                return;
            }

            for (String vendor : KNOWN_VENDOR_DIRECTORIES) {
                searchAllJavaInDirectory(path.resolve(vendor));
            }
        }

        /// Searches macOS Java Virtual Machines bundles below a directory.
        ///
        /// @param directory directory containing JDK or JRE bundles
        void searchJavaInMacJavaVirtualMachines(Path directory) {
            if (!Files.isDirectory(directory)) {
                return;
            }

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                for (Path subDir : stream) {
                    tryAddJavaHome(subDir.resolve("Contents/Home"));
                }
            } catch (IOException e) {
                LOG.warning("Failed to find Java in " + directory, e);
            }
        }

        /// Searches MSI-managed Java homes registered below a Windows registry key.
        ///
        /// @param hkey registry hive
        /// @param location JavaSoft registry location
        void queryJavaInRegistryKey(WinReg.HKEY hkey, String location) {
            @Nullable WinReg reg = WinReg.INSTANCE;
            if (reg == null)
                return;

            for (String java : reg.querySubKeys(hkey, location)) {
                if (!reg.querySubKeys(hkey, java).contains(java + "\\MSI"))
                    continue;
                if (reg.queryValue(hkey, java, "JavaHome") instanceof String home) {
                    try {
                        tryAddJavaHome(Path.of(home));
                    } catch (InvalidPathException e) {
                        LOG.warning("Invalid Java path in system registry: " + home);
                    }
                }
            }
        }

    }
}
