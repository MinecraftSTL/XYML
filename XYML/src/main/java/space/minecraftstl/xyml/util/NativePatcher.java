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
package space.minecraftstl.xyml.util;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.addon.mod.LocalModFile;
import space.minecraftstl.xyml.addon.mod.ModManager;
import space.minecraftstl.xyml.game.Artifact;
import space.minecraftstl.xyml.game.DefaultGameRepository;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.game.Library;
import space.minecraftstl.xyml.game.Renderer;
import space.minecraftstl.xyml.java.JavaRuntime;
import space.minecraftstl.xyml.setting.GameSettings;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.platform.Architecture;
import space.minecraftstl.xyml.util.platform.OSVersion;
import space.minecraftstl.xyml.util.platform.OperatingSystem;
import space.minecraftstl.xyml.util.platform.Platform;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static space.minecraftstl.xyml.util.gson.JsonUtils.mapTypeOf;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Applies platform-specific native-library substitutions and launch compatibility checks.
///
/// @author Glavo
@NotNullByDefault
public final class NativePatcher {
    /// Sentinel distinguishing a missing replacement entry from an intentionally removed library.
    private static final Library NONEXISTENT_LIBRARY = new Library(new Artifact("com.example", "nonexistent", "0.0.0"));

    /// Lazily loaded native replacement tables keyed by target platform.
    private static final Map<Platform, Map<String, @Nullable Library>> NATIVES = new HashMap<>();

    /// Loads and caches the native replacement table for one platform.
    ///
    /// @param platform target Java runtime platform
    /// @return platform replacement table, or an empty map when no table is available
    private static Map<String, @Nullable Library> getNatives(Platform platform) {
        return NATIVES.computeIfAbsent(platform, p -> {
            //noinspection ConstantConditions
            try (Reader reader = new InputStreamReader(
                    NativePatcher.class.getResourceAsStream("/assets/natives.json"),
                    StandardCharsets.UTF_8)) {
                Map<String, Map<String, @Nullable Library>> replacements = JsonUtils.GSON.fromJson(
                        reader,
                        mapTypeOf(String.class, mapTypeOf(String.class, Library.class)));
                return replacements.getOrDefault(p.toString(), Collections.emptyMap());
            } catch (IOException e) {
                LOG.warning("Failed to load native library list", e);
                return Collections.emptyMap();
            }
        });
    }

    // https://github.com/LWJGL/lwjgl3/issues/1111
    /// Returns whether LWJGL 3.4.1 requires the MemoryUtil compatibility patch on the selected Java runtime.
    ///
    /// @param manifest resolved game manifest
    /// @param javaVersion Java feature version
    /// @return whether the compatibility patch is required
    public static boolean needPatchMemoryUtil(GameInstanceManifest manifest, int javaVersion) {
        return javaVersion >= 25 && javaVersion <= 26 && manifest.getLibraries().stream().anyMatch(library ->
                "org.lwjgl".equals(library.groupId())
                        && "lwjgl".equals(library.artifactId())
                        && "3.4.1".equals(library.version())
                        && library.classifier() == null
        );
    }

    /// Applies native-library filtering and platform substitutions to one resolved manifest.
    ///
    /// @param repository repository owning the launched instance
    /// @param manifest resolved launch manifest
    /// @param gameVersion resolved Minecraft version, or `null` when unavailable
    /// @param javaVersion selected Java runtime
    /// @param settings effective launch settings
    /// @param javaArguments mutable Java argument list receiving required compatibility flags
    /// @return manifest containing the selected native libraries
    public static GameInstanceManifest patchNative(DefaultGameRepository repository,
                                                    GameInstanceManifest manifest,
                                                    @Nullable String gameVersion,
                                                    JavaRuntime javaVersion,
                                                    GameSettings.Effective settings,
                                                    List<String> javaArguments) {
        if (settings.getInheritable(GameSettings::useCustomNativesProperty)) {
            if (gameVersion != null && GameVersionNumber.compare(gameVersion, "1.19") < 0)
                return manifest;

            ArrayList<Library> newLibraries = new ArrayList<>();
            for (Library library : manifest.getLibraries()) {
                if (!library.appliesToCurrentEnvironment())
                    continue;

                if (library.classifier() == null
                        || !library.artifactId().startsWith("lwjgl")
                        || !library.classifier().startsWith("natives")) {
                    newLibraries.add(library);
                }
            }
            return manifest.withLibraries(newLibraries);
        }

        final boolean useNativeGLFWorSDL = settings.getInheritable(GameSettings::useNativeGLFWorSDLProperty);
        final boolean useNativeOpenAL = settings.getInheritable(GameSettings::useNativeOpenALProperty);

        if (OperatingSystem.CURRENT_OS.isLinuxOrBSD() && (useNativeGLFWorSDL || useNativeOpenAL)
                && gameVersion != null && GameVersionNumber.compare(gameVersion, "1.19") >= 0) {

            manifest = manifest.withLibraries(manifest.getLibraries().stream()
                    .filter(library -> {
                        if (shouldFilterBundledNative(library, useNativeGLFWorSDL, useNativeOpenAL)) {
                            LOG.info("Filter out " + library.name());
                            return false;
                        }
                        return true;
                    })
                    .collect(Collectors.toList()));
        }

        // Try patch natives

        OperatingSystem os = javaVersion.getPlatform().getOperatingSystem();
        Architecture arch = javaVersion.getArchitecture();
        @Nullable GameVersionNumber gameVersionNumber =
                gameVersion != null ? GameVersionNumber.asGameVersion(gameVersion) : null;

        if (settings.getInheritable(GameSettings::notPatchNativesProperty))
            return manifest;

        if (arch.isX86() && (os == OperatingSystem.WINDOWS || os == OperatingSystem.LINUX || os == OperatingSystem.MACOS))
            return manifest;

        if (arch == Architecture.ARM64 && (os == OperatingSystem.MACOS || os == OperatingSystem.WINDOWS)
                && gameVersionNumber != null
                && gameVersionNumber.compareTo("1.19") >= 0)
            return manifest;

        Map<String, @Nullable Library> replacements = getNatives(javaVersion.getPlatform());
        if (replacements.isEmpty()) {
            LOG.warning("No alternative native library provided for platform " + javaVersion.getPlatform());
            return manifest;
        }

        boolean lwjglVersionChanged = false;
        ArrayList<Library> newLibraries = new ArrayList<>();
        for (Library library : manifest.getLibraries()) {
            if (!library.appliesToCurrentEnvironment())
                continue;

            if (library.isNative()) {
                @Nullable Library replacement =
                        replacements.getOrDefault(library.name() + ":natives", NONEXISTENT_LIBRARY);
                if (replacement == NONEXISTENT_LIBRARY) {
                    LOG.warning("No alternative native library " + library.name() + ":natives provided for platform " + javaVersion.getPlatform());
                    newLibraries.add(library);
                } else if (replacement != null) {
                    LOG.info("Replace " + library.name() + ":natives with " + replacement.name());
                    newLibraries.add(replacement);
                }
            } else {
                @Nullable Library replacement = replacements.getOrDefault(library.name(), NONEXISTENT_LIBRARY);
                if (replacement == NONEXISTENT_LIBRARY) {
                    newLibraries.add(library);
                } else if (replacement != null) {
                    LOG.info("Replace " + library.name() + " with " + replacement.name());
                    newLibraries.add(replacement);

                    if ("org.lwjgl:lwjgl".equals(library.name()) && !Objects.equals(library.version(), replacement.version())) {
                        lwjglVersionChanged = true;
                    }
                }
            }
        }

        if (lwjglVersionChanged) {
            ModManager modManager = repository.getModManager(manifest.id());
            try {
                for (LocalModFile mod : modManager.getLocalFiles()) {
                    if ("sodium".equals(mod.getId())) {
                        // https://github.com/CaffeineMC/sodium/issues/2561
                        javaArguments.add("-Dsodium.checks.issue2561=false");
                        break;
                    }
                }
            } catch (Exception e) {
                LOG.warning("Failed to get mods", e);
            }
        }

        return manifest.withLibraries(newLibraries);
    }

    /// Returns the Windows Mesa loader required by a non-default renderer.
    ///
    /// @param java selected Java runtime
    /// @param renderer requested renderer
    /// @param windowsVersion detected Windows version
    /// @return matching loader, or `null` when no loader is required or supported
    /// @see <a href="https://github.com/HMCL-dev/mesa-loader-windows">Java Mesa Loader for Windows</a>
    public static @Nullable Library getWindowsMesaLoader(
            JavaRuntime java,
            Renderer renderer,
            OSVersion windowsVersion) {
        if (renderer == Renderer.DEFAULT)
            return null;

        if (windowsVersion.isAtLeast(OSVersion.WINDOWS_10)) {
            return getNatives(java.getPlatform()).get("mesa-loader");
        } else if (windowsVersion.isAtLeast(OSVersion.WINDOWS_7)) {
            if (renderer == Renderer.OpenGL.LLVMPIPE)
                return getNatives(java.getPlatform()).get("software-renderer-loader");
            else
                return null;
        } else {
            return null;
        }
    }

    /// Classifies native-launch support for one game version and runtime platform.
    ///
    /// @param gameVersion parsed Minecraft version
    /// @param platform selected Java runtime platform
    /// @param systemVersion detected operating-system version
    /// @return support classification
    public static SupportStatus checkSupportedStatus(
            GameVersionNumber gameVersion,
            Platform platform,
            OSVersion systemVersion) {
        if (platform.equals(Platform.WINDOWS_X86_64)) {
            if (!systemVersion.isAtLeast(OSVersion.WINDOWS_7) && gameVersion.isAtLeast("1.20.5", "24w14a"))
                return SupportStatus.UNSUPPORTED;

            return SupportStatus.OFFICIAL_SUPPORTED;
        }

        if (platform.equals(Platform.MACOS_X86_64) || platform.equals(Platform.LINUX_X86_64))
            return SupportStatus.OFFICIAL_SUPPORTED;

        if (platform.equals(Platform.WINDOWS_X86) || platform.equals(Platform.LINUX_X86)) {
            if (gameVersion.isAtLeast("1.20.5", "24w14a"))
                return SupportStatus.UNSUPPORTED;
            else
                return SupportStatus.OFFICIAL_SUPPORTED;
        }

        if (platform.equals(Platform.WINDOWS_ARM64) || platform.equals(Platform.MACOS_ARM64)) {
            if (gameVersion.compareTo("1.19") >= 0)
                return SupportStatus.OFFICIAL_SUPPORTED;

            String minVersion = platform.getOperatingSystem() == OperatingSystem.WINDOWS
                    ? "1.8"
                    : "1.6";

            return gameVersion.compareTo(minVersion) >= 0
                    ? SupportStatus.LAUNCHER_SUPPORTED
                    : SupportStatus.TRANSLATION_SUPPORTED;
        }

        @Nullable String minVersion = null;
        @Nullable String maxVersion = null;

        if (platform.equals(Platform.FREEBSD_X86_64)) {
            minVersion = "1.13";
        } else if (platform.equals(Platform.LINUX_ARM64)) {
            minVersion = "1.6";
        } else if (platform.equals(Platform.LINUX_RISCV64)) {
            minVersion = "1.8";

            if (gameVersion.compareTo("1.21.5") > 0 && gameVersion.compareTo("26.1-snapshot-8") < 0) {
                // LWJGL version mismatch
                return SupportStatus.UNSUPPORTED;
            }
        } else if (platform.equals(Platform.LINUX_LOONGARCH64)) {
            minVersion = "1.6";
        } else if (platform.equals(Platform.LINUX_LOONGARCH64_OW)) {
            minVersion = "1.6";
            maxVersion = "1.20.1";
        } else if (platform.equals(Platform.LINUX_MIPS64EL) || platform.equals(Platform.LINUX_ARM32)) {
            minVersion = "1.8";
            maxVersion = "1.20.1";
        }

        if (minVersion != null) {
            if (gameVersion.compareTo(minVersion) >= 0) {
                if (maxVersion != null && gameVersion.compareTo(maxVersion) > 0)
                    return SupportStatus.UNSUPPORTED;

                String[] defaultGameVersions = GameVersionNumber.getDefaultGameVersions();
                if (defaultGameVersions.length > 0 && gameVersion.compareTo(defaultGameVersions[0]) > 0) {
                    return SupportStatus.UNTESTED;
                }
                return SupportStatus.LAUNCHER_SUPPORTED;
            } else {
                return SupportStatus.UNSUPPORTED;
            }
        }

        return SupportStatus.UNTESTED;
    }

    /// Determines whether a bundled LWJGL native conflicts with enabled system-native settings.
    ///
    /// @param library candidate manifest library
    /// @param useNativeGLFWorSDL whether the system GLFW or SDL library is enabled
    /// @param useNativeOpenAL whether the system OpenAL library is enabled
    /// @return whether the bundled native should be removed
    static boolean shouldFilterBundledNative(
            Library library,
            boolean useNativeGLFWorSDL,
            boolean useNativeOpenAL) {
        @Nullable String classifier = Objects.requireNonNull(library, "library").classifier();
        if (classifier == null || !classifier.startsWith("natives") || !"org.lwjgl".equals(library.groupId())) {
            return false;
        }
        return (useNativeGLFWorSDL
                && ("lwjgl-glfw".equals(library.artifactId()) || library.artifactId().contains("sdl")))
                || (useNativeOpenAL && "lwjgl-openal".equals(library.artifactId()));
    }

    /// Native-launch support classification for the selected platform and game version.
    @NotNullByDefault
    public enum SupportStatus {
        /// Mojang directly supports this platform and version.
        OFFICIAL_SUPPORTED,

        /// XYML supplies the required native substitutions.
        LAUNCHER_SUPPORTED,

        /// A translation layer can provide runtime support.
        TRANSLATION_SUPPORTED,

        /// No definitive compatibility classification is available.
        UNTESTED,

        /// The selected platform and game version combination is unsupported.
        UNSUPPORTED
    }

    /// Prevents instantiation of this static utility class.
    private NativePatcher() {
    }
}
