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
package space.minecraftstl.xyml.ui.swing.page.instances.management.installers;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.download.RemoteVersion;
import space.minecraftstl.xyml.task.Task;

import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.CompletionStage;

/// Provides asynchronous inspection and stopped Core tasks for installer mutations on one existing instance.
///
/// Snapshot reads begin immediately on the service's background executor. Mutation methods only build a
/// stopped [Task]; a future Swing page owns task-executor presentation, startup, cancellation, and terminal UI.
@NotNullByDefault
public interface InstanceInstallerManagementService {
    /// Reads one existing instance's recognized loader state without blocking the Swing event-dispatch thread.
    ///
    /// @param instanceId stable existing instance identifier
    /// @return asynchronous immutable installer snapshot
    CompletionStage<InstanceInstallerSnapshot> loadSnapshot(String instanceId);

    /// Builds an ordered installation task from original Core remote-version objects.
    ///
    /// The service defensively snapshots the supplied collection before task startup, then validates it
    /// against the authoritative current instance snapshot on its background executor.
    ///
    /// @param instanceId stable target instance identifier
    /// @param remoteVersions selected concrete Core versions in desired installation order
    /// @return stopped task that saves, refreshes, and returns the resulting installer snapshot
    Task<InstanceInstallerSnapshot> installRemoteVersions(
            String instanceId,
            Collection<? extends RemoteVersion> remoteVersions);

    /// Builds a task that removes one library identifier, saves metadata, refreshes, and returns a snapshot.
    ///
    /// @param instanceId stable target instance identifier
    /// @param libraryId exact Core library or patch identifier
    /// @return stopped removal task
    Task<InstanceInstallerSnapshot> removeLibrary(String instanceId, String libraryId);

    /// Builds a task that applies one supported local installer file to an existing instance.
    ///
    /// Core currently recognizes supported Forge, NeoForge, Cleanroom, and OptiFine installer formats.
    ///
    /// @param instanceId stable target instance identifier
    /// @param installer local installer file selected by the user
    /// @return stopped offline-installation task
    Task<InstanceInstallerSnapshot> installOffline(String instanceId, Path installer);
}
