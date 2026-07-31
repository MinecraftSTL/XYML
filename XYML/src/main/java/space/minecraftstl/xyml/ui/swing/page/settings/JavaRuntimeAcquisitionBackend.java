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
import space.minecraftstl.xyml.game.GameJavaVersion;
import space.minecraftstl.xyml.java.JavaRuntime;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.platform.Platform;
import space.minecraftstl.xyml.util.platform.UnsupportedPlatformException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/// Isolates process-wide Java manager, repository, archive, and download-provider access for acquisition services.
@NotNullByDefault
interface JavaRuntimeAcquisitionBackend {
    /// Cooperative cancellation callback invoked inside long-running archive loops.
    @FunctionalInterface
    @NotNullByDefault
    interface CancellationCheck {
        /// Throws a cancellation exception when the owning task has been cancelled.
        void checkCancelled();
    }

    /// Returns the current platform used by the managed Java repository.
    ///
    /// @return current system platform
    Platform currentPlatform();

    /// Returns launcher-supported Mojang components for a platform without contacting remote services.
    ///
    /// @param platform target platform
    /// @return immutable supported component list in launcher-defined order
    @Unmodifiable List<GameJavaVersion> supportedMojangVersions(Platform platform);

    /// Returns whether one Mojang component has either a local manifest or installation directory.
    ///
    /// @param platform target platform
    /// @param version Mojang component
    /// @return whether its local manifest exists
    boolean isMojangRuntimeInstalled(Platform platform, GameJavaVersion version);

    /// Creates a stopped Java manager task for downloading and registering one Mojang component.
    ///
    /// @param platform target platform
    /// @param version selected Mojang component
    /// @return stopped manager download task
    Task<JavaRuntime> downloadMojangRuntime(Platform platform, GameJavaVersion version);

    /// Opens and validates one Java archive while periodically checking task cancellation.
    ///
    /// @param archiveFile local archive path
    /// @param cancellationCheck cooperative cancellation callback
    /// @return immutable inspection result
    /// @throws IOException when the archive cannot be opened or lacks a valid Java layout
    /// @throws UnsupportedPlatformException when the archive runtime cannot execute on the current system
    LocalJavaArchiveInspection inspectLocalArchive(
            Path archiveFile,
            CancellationCheck cancellationCheck) throws IOException, UnsupportedPlatformException;

    /// Copies a user-controlled archive while periodically checking task cancellation.
    ///
    /// @param archiveFile user-selected source archive
    /// @param cancellationCheck cooperative cancellation callback
    /// @return controlled temporary archive path
    /// @throws IOException when the source cannot be copied
    Path copyToManagedTemporaryArchive(
            Path archiveFile,
            CancellationCheck cancellationCheck) throws IOException;

    /// Revalidates and rewrites a controlled archive while periodically checking task cancellation.
    ///
    /// @param inspection inspection of a controlled source copy
    /// @param cancellationCheck cooperative cancellation callback
    /// @return inspection of the normalized install archive
    /// @throws IOException when revalidation or repacking fails
    /// @throws UnsupportedPlatformException when the revalidated runtime cannot execute on the current system
    LocalJavaArchiveInspection prepareInstallArchive(
            LocalJavaArchiveInspection inspection,
            CancellationCheck cancellationCheck) throws IOException, UnsupportedPlatformException;

    /// Best-effort deletes one acquisition-owned temporary archive.
    ///
    /// @param archiveFile temporary archive to delete
    void deleteManagedTemporaryArchive(Path archiveFile);

    /// Returns the normalized local managed-runtime root for one platform without accessing remote services.
    ///
    /// @param platform target platform
    /// @return managed platform root
    Path managedPlatformRoot(Platform platform);

    /// Returns whether a named runtime has either a manifest or installation directory for one platform.
    ///
    /// @param platform target platform
    /// @param name managed runtime name
    /// @return whether its local manifest exists
    boolean isNamedRuntimeInstalled(Platform platform, String name);

    /// Creates a stopped Java manager task for installing and registering one local archive.
    ///
    /// @param inspection previously validated archive metadata
    /// @param name validated managed-runtime name
    /// @return stopped manager installation task
    Task<JavaRuntime> installLocalArchive(LocalJavaArchiveInspection inspection, String name) throws IOException;
}
