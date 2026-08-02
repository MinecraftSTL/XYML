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
package space.minecraftstl.xyml;

import space.minecraftstl.xyml.util.StringUtils;
import space.minecraftstl.xyml.util.io.JarUtils;
import space.minecraftstl.xyml.util.platform.Architecture;
import space.minecraftstl.xyml.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.EnumSet;

/// Stores product identity, version, service endpoints, and runtime paths for the launcher.
@NotNullByDefault
public final class Metadata {
    /// Prevents construction of this application-wide metadata holder.
    private Metadata() {
    }

    /// Short product name displayed by the launcher and passed to launched games.
    public static final String NAME = "XYML";

    /// Full product name displayed in launcher window titles and application metadata.
    public static final String FULL_NAME = "xOyz Minecraft Launcher";

    /// Current build version, optionally overridden for diagnostics and development.
    public static final String VERSION = System.getProperty("xyml.version.override", JarUtils.getAttribute("xyml.version", "@develop@"));

    /// Explicit Application User Model ID used for Windows taskbar grouping and pinning.
    public static final String WINDOWS_APP_USER_MODEL_ID = "space.minecraftstl.xyml";

    /// Short product title containing the product name and current version.
    public static final String TITLE = NAME + " " + VERSION;

    /// Full product title containing the full product name and current version.
    public static final String FULL_TITLE = FULL_NAME + " v" + VERSION;

    /// Oldest Java feature version capable of running the launcher.
    public static final int MINIMUM_REQUIRED_JAVA_VERSION = 17;

    /// Oldest Java feature version supported by launcher maintainers.
    public static final int MINIMUM_SUPPORTED_JAVA_VERSION = 17;

    /// Java feature version recommended for running the launcher.
    public static final int RECOMMENDED_JAVA_VERSION = 21;

    /// Project homepage.
    public static final String PUBLISH_URL = "https://github.com/MinecraftSTL/XYML";

    /// Launcher release download page.
    public static final String DOWNLOAD_URL = PUBLISH_URL + "/releases";

    /// Channel-specific update descriptor template, optionally overridden for deployment-specific infrastructure.
    ///
    /// The `{channel}` placeholder is resolved to `stable`, `beta`, `alpha`, or `dev` before query parameters are
    /// appended.
    public static final String XYML_UPDATE_URL = System.getProperty(
            "xyml.update_source.override",
            DOWNLOAD_URL + "/download/release-channels/xyml-update-{channel}.json");

    /// Release page used when an automatic update cannot be applied.
    public static final String MANUAL_UPDATE_URL = DOWNLOAD_URL;

    /// Project documentation root.
    public static final String DOCS_URL = PUBLISH_URL + "/tree/main/docs";

    /// Project issue-reporting page.
    public static final String CONTACT_URL = PUBLISH_URL + "/issues/new/choose";

    /// Project changelog and release page.
    public static final String CHANGELOG_URL = DOWNLOAD_URL;

    /// Project license page.
    public static final String EULA_URL = PUBLISH_URL + "/blob/main/LICENSE";

    /// Official XYML user QQ group invitation link.
    public static final String GROUPS_URL = "https://qm.qq.com/cgi-bin/qm/qr?k=wz9sCQuIj4TiQBHUpeuBGM-pZ83f5ini&jump_from=webapi&authKey=VKucBpojFUOiDWF7OCbmvDI6Vfkjr+S1m4e7+unOBAuEfW/j1yXYTnf50c+z/NWs";

    /// Build channel embedded in the launcher artifact.
    public static final String BUILD_CHANNEL = JarUtils.getAttribute("xyml.release.channel", "dev");

    /// Environment variable that disables the confirmation for illegal offline usernames.
    public static final String SKIP_OFFLINE_USERNAME_CHECK_ENVIRONMENT_VARIABLE = "XYML_SKIP_OFFLINE_USERNAME_CHECK";

    /// Whether the illegal offline username confirmation is disabled by the environment.
    public static final boolean SKIP_OFFLINE_USERNAME_CHECK =
            "true".equalsIgnoreCase(System.getenv(SKIP_OFFLINE_USERNAME_CHECK_ENVIRONMENT_VARIABLE));

    /// Source commit embedded in the launcher artifact, or `null` when unavailable.
    public static final @Nullable String GITHUB_SHA = JarUtils.getAttribute("xyml.version.hash", null);

    /// Whether this process was launched from an OS-native jpackage application image.
    public static final boolean PACKAGED = Boolean.getBoolean("xyml.packaged");

    /// Normalized process working directory captured when the launcher starts.
    public static final Path CURRENT_DIRECTORY = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();

    /// Platform-default Minecraft working directory.
    public static final Path MINECRAFT_DIRECTORY = OperatingSystem.getWorkingDirectory("minecraft");

    /// User-level launcher data directory.
    public static final Path XYML_USER_HOME;

    /// Local launcher state directory.
    public static final Path XYML_LOCAL_HOME;

    /// Shared launcher dependency directory.
    public static final Path DEPENDENCIES_DIRECTORY;

    static {
        @Nullable String xymlHome = System.getProperty("xyml.home", System.getenv("XYML_USER_HOME"));
        if (StringUtils.isBlank(xymlHome)) {
            if (OperatingSystem.CURRENT_OS.isLinuxOrBSD()) {
                @Nullable String xdgData = System.getenv("XDG_DATA_HOME");
                if (StringUtils.isNotBlank(xdgData)) {
                    XYML_USER_HOME = Path.of(xdgData, "xyml").toAbsolutePath().normalize();
                } else {
                    XYML_USER_HOME = Path.of(System.getProperty("user.home"), ".local", "share", "xyml").toAbsolutePath().normalize();
                }
            } else {
                XYML_USER_HOME = OperatingSystem.getWorkingDirectory("xyml");
            }
        } else {
            XYML_USER_HOME = Path.of(xymlHome).toAbsolutePath().normalize();
        }

        @Nullable String xymlCurrentDir = System.getProperty("xyml.dir", System.getenv("XYML_LOCAL_HOME"));
        XYML_LOCAL_HOME = StringUtils.isNotBlank(xymlCurrentDir)
                ? Path.of(xymlCurrentDir).toAbsolutePath().normalize()
                : PACKAGED ? packagedLocalHome() : CURRENT_DIRECTORY.resolve(".xyml");

        @Nullable String xymlDependencies = System.getProperty("xyml.dependencies.dir", System.getenv("XYML_DEPENDENCIES_DIR"));
        DEPENDENCIES_DIRECTORY = StringUtils.isNotBlank(xymlDependencies)
                ? Path.of(xymlDependencies).toAbsolutePath().normalize()
                : XYML_LOCAL_HOME.resolve("dependencies");
    }

    /// Resolves writable cache and log storage for a native packaged application.
    ///
    /// @return normalized user-writable platform cache directory
    private static Path packagedLocalHome() {
        Path userHome = Path.of(System.getProperty("user.home", "."));
        Path path = switch (OperatingSystem.CURRENT_OS) {
            case WINDOWS -> {
                @Nullable String localAppData = System.getenv("LOCALAPPDATA");
                yield StringUtils.isNotBlank(localAppData)
                        ? Path.of(localAppData, "XYML")
                        : userHome.resolve("AppData").resolve("Local").resolve("XYML");
            }
            case MACOS -> userHome.resolve("Library").resolve("Caches").resolve("XYML");
            case LINUX, FREEBSD -> {
                @Nullable String xdgCache = System.getenv("XDG_CACHE_HOME");
                yield StringUtils.isNotBlank(xdgCache)
                        ? Path.of(xdgCache, "xyml")
                        : userHome.resolve(".cache").resolve("xyml");
            }
            case UNKNOWN -> XYML_USER_HOME.resolve("cache");
        };
        return path.toAbsolutePath().normalize();
    }

    /// Returns whether the artifact belongs to the stable release channel.
    public static boolean isStable() {
        return "stable".equals(BUILD_CHANNEL);
    }

    /// Returns whether the artifact belongs to the public beta release channel.
    public static boolean isBeta() {
        return "beta".equals(BUILD_CHANNEL);
    }

    /// Returns whether the artifact belongs to the internal alpha release channel.
    public static boolean isAlpha() {
        return "alpha".equals(BUILD_CHANNEL);
    }

    /// Returns whether the artifact belongs to the development release channel.
    public static boolean isDev() {
        return "dev".equals(BUILD_CHANNEL);
    }

    /// Returns the recommended Java download page for the current platform, or `null` when unsupported.
    public static @Nullable String getSuggestedJavaDownloadLink() {
        if (OperatingSystem.CURRENT_OS == OperatingSystem.LINUX && Architecture.SYSTEM_ARCH == Architecture.LOONGARCH64_OW)
            return "https://www.loongnix.cn/zh/api/java/downloads-jdk21/index.html";
        else {
            EnumSet<Architecture> supportedArchitectures;
            if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS)
                supportedArchitectures = EnumSet.of(Architecture.X86_64, Architecture.X86, Architecture.ARM64);
            else if (OperatingSystem.CURRENT_OS == OperatingSystem.LINUX)
                supportedArchitectures = EnumSet.of(
                        Architecture.X86_64, Architecture.X86,
                        Architecture.ARM64, Architecture.ARM32,
                        Architecture.RISCV64, Architecture.LOONGARCH64
                );
            else if (OperatingSystem.CURRENT_OS == OperatingSystem.MACOS)
                supportedArchitectures = EnumSet.of(Architecture.X86_64, Architecture.ARM64);
            else
                supportedArchitectures = EnumSet.noneOf(Architecture.class);
            if (supportedArchitectures.contains(Architecture.SYSTEM_ARCH))
                return String.format("https://docs.hmcl.net/downloads/%s/%s.html",
                        OperatingSystem.CURRENT_OS.getCheckedName(),
                        Architecture.SYSTEM_ARCH.getCheckedName()
                );
            else
                return null;
        }
    }
}
