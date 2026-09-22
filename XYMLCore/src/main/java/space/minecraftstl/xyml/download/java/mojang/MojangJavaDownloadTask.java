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
package space.minecraftstl.xyml.download.java.mojang;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.tukaani.xz.LZMAInputStream;
import space.minecraftstl.xyml.download.ArtifactMalformedException;
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.game.DownloadInfo;
import space.minecraftstl.xyml.game.GameJavaVersion;
import space.minecraftstl.xyml.java.JavaInfo;
import space.minecraftstl.xyml.task.FileDownloadTask;
import space.minecraftstl.xyml.task.GetTask;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.io.ChecksumMismatchException;
import space.minecraftstl.xyml.util.io.FileUtils;
import space.minecraftstl.xyml.util.platform.UnsupportedPlatformException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.*;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Downloads and atomically publishes one Mojang-managed Java runtime tree.
@NotNullByDefault
public final class MojangJavaDownloadTask extends Task<MojangJavaDownloadTask.Result> {

    /// Mojang Java runtime directory metadata endpoint.
    private static final String JAVA_LIST_URL = "https://piston-meta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";

    /// Provider used for metadata and runtime file candidates.
    private final DownloadProvider downloadProvider;

    /// Final managed runtime directory.
    private final Path target;

    /// Temporary runtime tree populated before publication.
    private final Path tempDir;

    /// Metadata task that resolves the selected runtime file manifest.
    private final Task<MojangJavaRemoteFiles> javaDownloadsTask;

    /// Runtime file tasks assembled after metadata resolution.
    private final List<Task<?>> dependencies = new ArrayList<>();

    /// Selected runtime metadata, or null before the metadata dependent completes.
    private volatile @Nullable MojangJavaDownloads.JavaDownload download;

    /// Creates a stopped Mojang runtime installation task with stable target paths.
    ///
    /// @param downloadProvider provider used for metadata and file candidates
    /// @param target final managed runtime directory
    /// @param tempDir temporary runtime directory
    /// @param javaVersion requested Mojang runtime component
    /// @param platform Mojang platform identifier
    public MojangJavaDownloadTask(
            DownloadProvider downloadProvider,
            Path target,
            Path tempDir,
            GameJavaVersion javaVersion,
            String platform) {
        this.target = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
        this.tempDir = Objects.requireNonNull(tempDir, "tempDir").toAbsolutePath().normalize();
        this.downloadProvider = Objects.requireNonNull(downloadProvider, "downloadProvider");
        setResources(TaskResource.javaRuntime(this.target), TaskResource.javaRuntime(this.tempDir));
        this.javaDownloadsTask = new GetTask(this.downloadProvider.injectURLWithCandidates(JAVA_LIST_URL))
                .thenComposeAsync(javaDownloadsJson -> {
                    MojangJavaDownloads allDownloads = JsonUtils.fromNonNullJson(
                            javaDownloadsJson,
                            MojangJavaDownloads.class);

                    @Nullable Map<String, List<MojangJavaDownloads.JavaDownload>> osDownloads =
                            allDownloads.downloads().get(platform);
                    @Nullable List<MojangJavaDownloads.JavaDownload> candidates = osDownloads == null
                            ? null
                            : osDownloads.get(javaVersion.component());
                    if (candidates == null) {
                        throw new UnsupportedPlatformException("Unsupported platform: " + platform);
                    }
                    for (MojangJavaDownloads.JavaDownload download : candidates) {
                        if (JavaInfo.parseVersion(download.version().name()) >= javaVersion.majorVersion()) {
                            this.download = download;
                            return new GetTask(this.downloadProvider.injectURLWithCandidates(
                                    download.manifest().getUrl()));
                        }
                    }
                    throw new UnsupportedPlatformException("Candidates: " + JsonUtils.GSON.toJson(candidates));
                })
                .asOrchestration()
                .thenApplyAsync(javaDownloadJson -> JsonUtils.fromNonNullJson(
                        javaDownloadJson,
                        MojangJavaRemoteFiles.class))
                .asOrchestration();
    }

    /// Returns the metadata lookup that must finish before runtime files can be selected.
    ///
    /// @return immutable singleton metadata task collection
    @Override
    public @Unmodifiable Collection<Task<?>> getDependents() {
        return Collections.singleton(javaDownloadsTask);
    }

    /// Builds stopped download and extraction tasks for the selected runtime manifest.
    @Override
    public void execute() throws Exception {
        MojangJavaRemoteFiles remoteFiles = Objects.requireNonNull(
                javaDownloadsTask.getResult(),
                "Java remote files");
        for (Map.Entry<String, MojangJavaRemoteFiles.Remote> entry : remoteFiles.files().entrySet()) {
            Path dest = tempDir.resolve(entry.getKey());
            if (entry.getValue() instanceof MojangJavaRemoteFiles.RemoteFile file) {
                // Use local file if it already exists
                try {
                    BasicFileAttributes localFileAttributes = Files.readAttributes(dest, BasicFileAttributes.class);
                    if (localFileAttributes.isRegularFile() && file.getDownloads().containsKey("raw")) {
                        DownloadInfo downloadInfo = Objects.requireNonNull(
                                file.getDownloads().get("raw"),
                                "raw download");
                        if (localFileAttributes.size() == downloadInfo.getSize()) {
                            ChecksumMismatchException.verifyChecksum(dest, "SHA-1", downloadInfo.getSha1());
                            LOG.info("Skip existing file: " + dest);
                            continue;
                        }
                    }
                } catch (IOException ignored) {
                }

                if (file.getDownloads().containsKey("lzma")) {
                    DownloadInfo download = Objects.requireNonNull(
                            file.getDownloads().get("lzma"),
                            "LZMA download");
                    @Nullable DownloadInfo raw = file.getDownloads().get("raw");

                    @Nullable String rawSha1;
                    if (raw != null && raw.getSha1() != null) {
                        rawSha1 = raw.getSha1();
                    } else {
                        rawSha1 = null;
                    }

                    Path tempFile = tempDir.resolve(entry.getKey() + ".lzma");
                    var task = new FileDownloadTask(
                            downloadProvider.injectURLWithCandidates(download.getUrl()),
                            tempFile,
                            new FileDownloadTask.IntegrityCheck("SHA-1", download.getSha1()));
                    task.setName(entry.getKey());
                    dependencies.add(task.thenRunAsync(() -> {
                        Path decompressed = tempDir.resolve(entry.getKey() + ".tmp");
                        var digest = MessageDigest.getInstance("SHA-1");
                        try (var input = new DigestInputStream(
                                new LZMAInputStream(Files.newInputStream(tempFile)),
                                digest)) {
                            Files.copy(input, decompressed, StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException e) {
                            throw new ArtifactMalformedException("File " + entry.getKey() + " is malformed", e);
                        }

                        String actualSha1 = HexFormat.of().formatHex(digest.digest());

                        if (rawSha1 != null && !actualSha1.equalsIgnoreCase(rawSha1)) {
                            throw new ArtifactMalformedException(
                                    "File " + entry.getKey() + " has incorrect SHA-1 hash: expected "
                                            + rawSha1 + ", got " + actualSha1);
                        }

                        try {
                            Files.deleteIfExists(tempFile);
                        } catch (IOException e) {
                            LOG.warning("Failed to delete temporary file: " + tempFile, e);
                        }

                        Files.move(decompressed, dest, StandardCopyOption.REPLACE_EXISTING);
                        if (file.isExecutable()) {
                            FileUtils.setExecutable(dest);
                        }
                    }).setResources(
                            TaskResource.javaRuntime(tempDir),
                            TaskResource.javaRuntime(target)));
                } else if (file.getDownloads().containsKey("raw")) {
                    DownloadInfo download = Objects.requireNonNull(
                            file.getDownloads().get("raw"),
                            "raw download");
                    var task = new FileDownloadTask(
                            downloadProvider.injectURLWithCandidates(download.getUrl()),
                            dest,
                            new FileDownloadTask.IntegrityCheck("SHA-1", download.getSha1()));
                    task.setName(entry.getKey());
                    if (file.isExecutable()) {
                        dependencies.add(task.thenRunAsync(() -> FileUtils.setExecutable(dest))
                                .setResources(TaskResource.javaRuntime(target)));
                    } else {
                        dependencies.add(task);
                    }
                } else {
                    continue;
                }
            } else if (entry.getValue() instanceof MojangJavaRemoteFiles.RemoteDirectory) {
                Files.createDirectories(dest);
            } else if (entry.getValue() instanceof MojangJavaRemoteFiles.RemoteLink link) {
                Files.deleteIfExists(dest);
                Files.createSymbolicLink(dest, Paths.get(link.getTarget()));
            }
        }
    }

    /// Returns the runtime file tasks assembled during execution.
    ///
    /// @return immutable dependency snapshot
    @Override
    public @Unmodifiable List<Task<?>> getDependencies() {
        return List.copyOf(dependencies);
    }

    /// Requests final publication after every runtime file task terminates.
    ///
    /// @return always true
    @Override
    public boolean doPostExecute() {
        return true;
    }

    /// Publishes a complete temporary runtime tree and records its metadata result.
    @Override
    public void postExecute() throws Exception {
        if (isDependenciesSucceeded()) {
            FileUtils.cleanDirectory(target);

            if (Files.getFileStore(target).equals(Files.getFileStore(tempDir))) {
                Files.move(tempDir, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                FileUtils.copyDirectory(tempDir, target);
                FileUtils.deleteDirectory(tempDir);
            }
            setResult(new Result(
                    Objects.requireNonNull(download, "selected Java download"),
                    Objects.requireNonNull(javaDownloadsTask.getResult(), "Java remote files")));
        }
    }

    /// Mojang metadata retained after a successful runtime installation.
    ///
    /// @param download selected runtime descriptor
    /// @param remoteFiles selected runtime file manifest
    @NotNullByDefault
    public record Result(MojangJavaDownloads.JavaDownload download, MojangJavaRemoteFiles remoteFiles) {
    }
}
