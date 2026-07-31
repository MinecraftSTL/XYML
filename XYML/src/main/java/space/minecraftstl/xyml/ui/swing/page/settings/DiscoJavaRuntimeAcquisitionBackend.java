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
import space.minecraftstl.xyml.download.java.JavaPackageType;
import space.minecraftstl.xyml.download.java.disco.DiscoJavaDistribution;
import space.minecraftstl.xyml.download.java.disco.DiscoJavaRemoteVersion;
import space.minecraftstl.xyml.java.JavaRuntime;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.platform.Platform;
import space.minecraftstl.xyml.util.platform.UnsupportedPlatformException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;

/// Isolates Disco network access and the existing managed Java archive pipeline for deterministic service tests.
@NotNullByDefault
interface DiscoJavaRuntimeAcquisitionBackend {
    /// Returns the current system platform without contacting a remote service.
    ///
    /// @return current system platform
    Platform currentPlatform();

    /// Creates the established Disco version-list task for one distribution and platform.
    ///
    /// @param distribution explicitly selected distribution
    /// @param platform target platform
    /// @return stopped version-list task
    Task<EnumMap<JavaPackageType, TreeMap<Integer, DiscoJavaRemoteVersion>>> fetchVersions(
            DiscoJavaDistribution distribution,
            Platform platform);

    /// Creates a stopped provider-aware text request for Disco package or checksum metadata.
    ///
    /// @param uri HTTP or HTTPS metadata URI
    /// @param maximumBytes positive decoded response byte ceiling
    /// @return stopped text task
    Task<String> fetchText(String uri, long maximumBytes);

    /// Creates a stopped bounded and checksum-verifying download into a managed temporary archive.
    ///
    /// @param uri HTTP or HTTPS archive URI
    /// @param archiveSuffix parser-significant `.zip` or `.tar.gz` suffix
    /// @param checksumAlgorithm normalized JCA checksum algorithm
    /// @param checksum expected lowercase hexadecimal checksum
    /// @return stopped download task yielding the managed temporary archive
    Task<Path> downloadArchive(
            String uri,
            String archiveSuffix,
            String checksumAlgorithm,
            String checksum);

    /// Inspects one managed downloaded archive with the established archive preflight and resource ceilings.
    ///
    /// @param archiveFile managed temporary archive
    /// @param cancellationCheck cooperative cancellation callback
    /// @return immutable validated archive inspection
    /// @throws IOException when the archive is unsafe, malformed, or unstable
    /// @throws UnsupportedPlatformException when the runtime cannot execute on this host
    LocalJavaArchiveInspection inspectArchive(
            Path archiveFile,
            JavaRuntimeAcquisitionBackend.CancellationCheck cancellationCheck)
            throws IOException, UnsupportedPlatformException;

    /// Rewrites one inspected archive into the normalized controlled install ZIP.
    ///
    /// @param inspection validated downloaded archive
    /// @param cancellationCheck cooperative cancellation callback
    /// @return immutable normalized archive inspection
    /// @throws IOException when revalidation or rewriting fails
    /// @throws UnsupportedPlatformException when the normalized runtime cannot execute on this host
    LocalJavaArchiveInspection prepareInstallArchive(
            LocalJavaArchiveInspection inspection,
            JavaRuntimeAcquisitionBackend.CancellationCheck cancellationCheck)
            throws IOException, UnsupportedPlatformException;

    /// Classifies one managed runtime name against repository syntax and collision rules.
    ///
    /// @param platform managed repository platform
    /// @param name proposed managed-runtime name
    /// @return exact validation status
    JavaRuntimeInstallNameStatus validateInstallName(Platform platform, String name);

    /// Creates the safe extraction and atomic publication task with immutable update metadata.
    ///
    /// @param inspection normalized controlled install archive
    /// @param name validated managed-runtime name
    /// @param updateMetadata immutable manifest update metadata
    /// @return stopped installation task
    /// @throws IOException when final target paths are unavailable or unsafe
    Task<JavaRuntime> installArchive(
            LocalJavaArchiveInspection inspection,
            String name,
            @Unmodifiable Map<String, Object> updateMetadata) throws IOException;

    /// Best-effort deletes one acquisition-owned temporary archive.
    ///
    /// @param archiveFile temporary archive to delete
    void deleteTemporaryArchive(Path archiveFile);
}
