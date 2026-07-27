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
package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.download.java.JavaPackageType;
import space.minecraftstl.xyml.download.java.disco.DiscoFetchJavaListTask;
import space.minecraftstl.xyml.download.java.disco.DiscoJavaDistribution;
import space.minecraftstl.xyml.download.java.disco.DiscoJavaRemoteVersion;
import space.minecraftstl.xyml.java.JavaRuntime;
import space.minecraftstl.xyml.setting.DownloadProviders;
import space.minecraftstl.xyml.task.BoundedTextFetchTask;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.platform.Platform;
import space.minecraftstl.xyml.util.platform.UnsupportedPlatformException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/// Production Disco backend that delegates all archive work to the existing bounded acquisition process backend.
@NotNullByDefault
final class DiscoJavaRuntimeAcquisitionProcessBackend
        implements DiscoJavaRuntimeAcquisitionBackend {
    /// Configured launcher-wide download provider.
    private final DownloadProvider downloadProvider;

    /// Existing bounded archive inspection, normalization, download, and publication backend.
    private final JavaRuntimeAcquisitionProcessBackend archiveBackend;

    /// Existing install-name validator sharing the same archive and repository backend.
    private final JavaManagerRuntimeAcquisitionService localAcquisitionService;

    /// Creates the production backend using current download preferences and standard archive resource limits.
    DiscoJavaRuntimeAcquisitionProcessBackend() {
        this(
                DownloadProviders.getDownloadProvider(),
                new JavaManagerRuntimeAcquisitionService.ProcessBackend());
    }

    /// Creates a backend around explicit collaborators for focused integration tests.
    ///
    /// @param downloadProvider provider used for Disco metadata and archive candidates
    /// @param archiveBackend existing managed Java archive backend
    DiscoJavaRuntimeAcquisitionProcessBackend(
            DownloadProvider downloadProvider,
            JavaRuntimeAcquisitionProcessBackend archiveBackend) {
        this.downloadProvider = Objects.requireNonNull(downloadProvider, "downloadProvider");
        this.archiveBackend = Objects.requireNonNull(archiveBackend, "archiveBackend");
        this.localAcquisitionService = new JavaManagerRuntimeAcquisitionService(archiveBackend);
    }

    /// Returns the exact system platform used by the existing Java repository.
    ///
    /// @return current system platform
    @Override
    public Platform currentPlatform() {
        return archiveBackend.currentPlatform();
    }

    /// Creates the established Core Disco query for one explicit distribution and platform.
    ///
    /// @param distribution explicitly selected distribution
    /// @param platform target platform
    /// @return stopped version-list task
    @Override
    public Task<EnumMap<JavaPackageType, TreeMap<Integer, DiscoJavaRemoteVersion>>> fetchVersions(
            DiscoJavaDistribution distribution,
            Platform platform) {
        return new DiscoFetchJavaListTask(downloadProvider, distribution, platform);
    }

    /// Creates the established provider-aware text request without starting it.
    ///
    /// @param uri HTTP or HTTPS metadata URI
    /// @param maximumBytes positive decoded response byte ceiling
    /// @return stopped metadata request
    @Override
    public Task<String> fetchText(String uri, long maximumBytes) {
        return new BoundedTextFetchTask(
                downloadProvider.injectURLWithCandidates(uri),
                maximumBytes);
    }

    /// Creates a hard-bounded checksum-verifying archive download through the existing process backend.
    ///
    /// @param uri HTTP or HTTPS archive URI
    /// @param archiveSuffix parser-significant archive suffix
    /// @param checksumAlgorithm normalized JCA checksum algorithm
    /// @param checksum expected lowercase hexadecimal checksum
    /// @return stopped managed temporary archive download task
    @Override
    public Task<Path> downloadArchive(
            String uri,
            String archiveSuffix,
            String checksumAlgorithm,
            String checksum) {
        return archiveBackend.downloadManagedTemporaryArchive(
                downloadProvider.injectURLWithCandidates(uri),
                archiveSuffix,
                checksumAlgorithm,
                checksum);
    }

    /// Delegates downloaded archives to the established container preflight and structural validator.
    ///
    /// @param archiveFile managed downloaded archive
    /// @param cancellationCheck cooperative cancellation callback
    /// @return immutable archive inspection
    /// @throws IOException when the archive is unsafe, malformed, or unstable
    /// @throws UnsupportedPlatformException when the runtime cannot execute on this host
    @Override
    public LocalJavaArchiveInspection inspectArchive(
            Path archiveFile,
            JavaRuntimeAcquisitionBackend.CancellationCheck cancellationCheck)
            throws IOException, UnsupportedPlatformException {
        return archiveBackend.inspectLocalArchive(archiveFile, cancellationCheck);
    }

    /// Delegates to the normalized Java Home repacker after inspection.
    ///
    /// @param inspection validated downloaded archive
    /// @param cancellationCheck cooperative cancellation callback
    /// @return immutable normalized archive inspection
    /// @throws IOException when revalidation or rewriting fails
    /// @throws UnsupportedPlatformException when the normalized runtime cannot execute on this host
    @Override
    public LocalJavaArchiveInspection prepareInstallArchive(
            LocalJavaArchiveInspection inspection,
            JavaRuntimeAcquisitionBackend.CancellationCheck cancellationCheck)
            throws IOException, UnsupportedPlatformException {
        return archiveBackend.prepareInstallArchive(inspection, cancellationCheck);
    }

    /// Reuses the local acquisition service's exact syntax, containment, and collision rules.
    ///
    /// @param platform managed repository platform
    /// @param name proposed managed-runtime name
    /// @return exact validation status
    @Override
    public JavaRuntimeInstallNameStatus validateInstallName(
            Platform platform,
            String name) {
        return localAcquisitionService.validateInstallName(platform, name);
    }

    /// Delegates safe extraction and atomic publication while retaining caller update metadata.
    ///
    /// @param inspection normalized controlled install archive
    /// @param name validated managed-runtime name
    /// @param updateMetadata immutable manifest update metadata
    /// @return stopped installation task
    /// @throws IOException when final target paths are unavailable or unsafe
    @Override
    public Task<JavaRuntime> installArchive(
            LocalJavaArchiveInspection inspection,
            String name,
            @Unmodifiable Map<String, Object> updateMetadata) throws IOException {
        return archiveBackend.installArchive(inspection, name, updateMetadata);
    }

    /// Delegates best-effort cleanup to the existing managed temporary archive owner.
    ///
    /// @param archiveFile temporary archive to delete
    @Override
    public void deleteTemporaryArchive(Path archiveFile) {
        archiveBackend.deleteManagedTemporaryArchive(archiveFile);
    }
}
