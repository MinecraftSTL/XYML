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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.download.java.mojang.MojangJavaDownloadTask;
import space.minecraftstl.xyml.download.java.mojang.MojangJavaRemoteFiles;
import space.minecraftstl.xyml.game.DownloadInfo;
import space.minecraftstl.xyml.game.GameJavaVersion;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.io.FileUtils;
import space.minecraftstl.xyml.util.platform.OperatingSystem;
import space.minecraftstl.xyml.util.platform.Platform;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Stores launcher-managed Java runtimes and their manifests below one repository root.
@NotNullByDefault
public final class XYMLJavaRepository implements JavaRepository {
    /// Prefix used for Mojang-managed runtime names.
    public static final String MOJANG_JAVA_PREFIX = "mojang-";

    /// Managed Java repository root.
    private final Path root;

    /// Creates a managed Java repository rooted at a stable absolute path.
    ///
    /// @param root managed Java repository root
    public XYMLJavaRepository(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    public Path getPlatformRoot(Platform platform) {
        return root.resolve(platform.toString());
    }

    @Override
    public Path getJavaDir(Platform platform, String name) {
        return getPlatformRoot(platform).resolve(name);
    }

    public Path getJavaDir(Platform platform, GameJavaVersion gameJavaVersion) {
        return getJavaDir(platform, MOJANG_JAVA_PREFIX + gameJavaVersion.component());
    }

    @Override
    public Path getManifestFile(Platform platform, String name) {
        return getPlatformRoot(platform).resolve(name + ".json");
    }

    public Path getManifestFile(Platform platform, GameJavaVersion gameJavaVersion) {
        return getManifestFile(platform, MOJANG_JAVA_PREFIX + gameJavaVersion.component());
    }

    public boolean isInstalled(Platform platform, String name) {
        return Files.exists(getManifestFile(platform, name));
    }

    public boolean isInstalled(Platform platform, GameJavaVersion gameJavaVersion) {
        return isInstalled(platform, MOJANG_JAVA_PREFIX + gameJavaVersion.component());
    }

    public @Nullable Path getJavaExecutable(Platform platform, String name) {
        Path javaDir = getJavaDir(platform, name);
        try {
            return JavaManager.getExecutable(javaDir).toRealPath();
        } catch (IOException ignored) {
            if (platform.getOperatingSystem() == OperatingSystem.MACOS) {
                try {
                    return JavaManager.getMacExecutable(javaDir).toRealPath();
                } catch (IOException ignored1) {
                }
            }
        }

        return null;
    }

    public @Nullable Path getJavaExecutable(Platform platform, GameJavaVersion gameJavaVersion) {
        return getJavaExecutable(platform, MOJANG_JAVA_PREFIX + gameJavaVersion.component());
    }

    private static void getAllJava(List<Path> list, Platform platform, Path platformRoot) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(platformRoot)) {
            for (Path file : stream) {
                try {
                    String name = file.getFileName().toString();
                    if (name.endsWith(".json") && Files.isRegularFile(file)) {
                        Path javaDir = file.resolveSibling(name.substring(0, name.length() - ".json".length()));
                        Path executable;
                        try {
                            executable = JavaManager.getExecutable(javaDir).toRealPath();
                        } catch (IOException e) {
                            if (platform.getOperatingSystem() == OperatingSystem.MACOS)
                                executable = JavaManager.getMacExecutable(javaDir).toRealPath();
                            else
                                throw e;
                        }

                        if (Files.isDirectory(javaDir)) {
                            list.add(executable);
                        }
                    }
                } catch (Throwable e) {
                    LOG.warning("Failed to parse " + file, e);
                }
            }

        } catch (IOException ignored) {
        }
    }

    /// Returns an immutable snapshot of executable paths for installed runtimes on one platform.
    ///
    /// @param platform selected managed platform
    /// @return immutable installed executable snapshot
    @Override
    public @Unmodifiable Collection<Path> getAllJava(Platform platform) {
        Path platformRoot = getPlatformRoot(platform);
        if (!Files.isDirectory(platformRoot))
            return Collections.emptyList();

        ArrayList<Path> list = new ArrayList<>();

        getAllJava(list, platform, platformRoot);
        return List.copyOf(list);
    }

    /// Creates a Mojang runtime download chain with precise runtime, staging, and manifest resources.
    ///
    /// @param downloadProvider provider used for metadata and runtime files
    /// @param platform target managed platform
    /// @param gameJavaVersion selected Mojang runtime component
    /// @return stopped managed runtime installation task
    @Override
    public Task<JavaRuntime> getDownloadJavaTask(
            DownloadProvider downloadProvider,
            Platform platform,
            GameJavaVersion gameJavaVersion) {
        Path javaDir = getJavaDir(platform, gameJavaVersion);
        Path tempDir = getPlatformRoot(platform).resolve(".tmp").resolve(javaDir.getFileName());
        Path manifestFile = getManifestFile(platform, gameJavaVersion);

        Task<JavaRuntime> task = new MojangJavaDownloadTask(
                downloadProvider,
                javaDir,
                tempDir,
                gameJavaVersion,
                JavaManager.getMojangJavaPlatform(platform)).thenApplyAsync(result -> {
            Path executable;
            try {
                executable = JavaManager.getExecutable(javaDir).toRealPath();
            } catch (IOException e) {
                if (platform.getOperatingSystem() == OperatingSystem.MACOS)
                    executable = JavaManager.getMacExecutable(javaDir).toRealPath();
                else
                    throw e;
            }

            JavaInfo info;
            if (JavaManager.isCompatible(platform))
                info = JavaInfoUtils.fromExecutable(executable);
            else
                info = new JavaInfo(platform, result.download().version().name(), null);

            Map<String, Object> update = new LinkedHashMap<>();
            update.put("provider", "mojang");
            update.put("component", gameJavaVersion.component());

            Map<String, JavaLocalFiles.Local> files = new LinkedHashMap<>();
            result.remoteFiles().files().forEach((path, file) -> {
                if (file instanceof MojangJavaRemoteFiles.RemoteFile) {
                    DownloadInfo downloadInfo = ((MojangJavaRemoteFiles.RemoteFile) file).getDownloads().get("raw");
                    if (downloadInfo != null) {
                        files.put(path, new JavaLocalFiles.LocalFile(downloadInfo.getSha1(), downloadInfo.getSize()));
                    }
                } else if (file instanceof MojangJavaRemoteFiles.RemoteDirectory) {
                    files.put(path, new JavaLocalFiles.LocalDirectory());
                } else if (file instanceof MojangJavaRemoteFiles.RemoteLink) {
                    files.put(
                            path,
                            new JavaLocalFiles.LocalLink(
                                    ((MojangJavaRemoteFiles.RemoteLink) file).getTarget()));
                }
            });

            JavaManifest manifest = new JavaManifest(info, update, files);
            JsonUtils.writeToJsonFile(manifestFile, manifest);
            return JavaRuntime.of(executable, info, true);
        });
        return task.setResources(
                TaskResource.javaRuntime(javaDir),
                TaskResource.javaRuntime(tempDir),
                TaskResource.configuration(manifestFile));
    }

    /// Creates a local Java archive installation chain with stable output and input resources.
    ///
    /// @param platform target managed platform
    /// @param name managed runtime name
    /// @param update immutable manifest update metadata
    /// @param archiveFile input Java archive
    /// @return stopped managed runtime installation task
    public Task<JavaRuntime> getInstallJavaTask(
            Platform platform,
            String name,
            @Unmodifiable Map<String, Object> update,
            Path archiveFile) {
        Path javaDir = getJavaDir(platform, name);
        Path manifestFile = getManifestFile(platform, name);
        Task<JavaRuntime> task = new JavaInstallTask(javaDir, update, archiveFile).thenApplyAsync(result -> {
            if (!result.info().getPlatform().equals(platform)) {
                throw new IOException(
                        "Platform is mismatch: expected " + platform
                                + " but got " + result.info().getPlatform());
            }

            Path executable = javaDir.resolve("bin")
                    .resolve(platform.getOperatingSystem().getJavaExecutable())
                    .toRealPath();
            JsonUtils.writeToJsonFile(manifestFile, result);
            return JavaRuntime.of(executable, result.info(), true);
        });
        return task.setResources(
                TaskResource.javaRuntime(javaDir),
                TaskResource.archive(archiveFile),
                TaskResource.configuration(manifestFile));
    }

    /// Creates a task deleting one named runtime and its manifest.
    ///
    /// @param platform managed runtime platform
    /// @param name managed runtime name
    /// @return stopped deletion task
    @Override
    public Task<Void> getUninstallJavaTask(Platform platform, String name) {
        Path manifestFile = getManifestFile(platform, name);
        Path javaDir = getJavaDir(platform, name);
        return Task.runAsync(() -> {
            Files.deleteIfExists(manifestFile);
            FileUtils.deleteDirectory(javaDir);
        }).setResources(
                TaskResource.javaRuntime(javaDir),
                TaskResource.configuration(manifestFile));
    }

    /// Creates a task deleting the managed runtime containing one executable.
    ///
    /// @param java managed runtime descriptor
    /// @return stopped deletion task
    @Override
    public Task<Void> getUninstallJavaTask(JavaRuntime java) {
        Path platformRoot = getPlatformRoot(java.getPlatform());
        return Task.runAsync(() -> {
            Path relativized = platformRoot.relativize(java.getBinary());

            if (relativized.getNameCount() > 1) {
                String name = relativized.getName(0).toString();
                Files.deleteIfExists(getManifestFile(java.getPlatform(), name));
                FileUtils.deleteDirectory(getJavaDir(java.getPlatform(), name));
            }
        }).setResources(TaskResource.javaRuntime(platformRoot));
    }
}
