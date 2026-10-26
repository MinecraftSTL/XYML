// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management.installers;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.download.ComponentRemoteVersion;
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
    CompletionStage<InstanceInstallerSnapshot> loadSnapshot(GameInstanceID instanceId);

    /// Builds an ordered installation task from original Core remote-version objects.
    ///
    /// The service defensively snapshots the supplied collection before task startup, then validates it
    /// against the authoritative current instance snapshot on its background executor.
    ///
    /// @param instanceId stable target instance identifier
    /// @param remoteVersions selected concrete Core versions in desired installation order
    /// @return stopped task that saves, refreshes, and returns the resulting installer snapshot
    Task<InstanceInstallerSnapshot> installRemoteVersions(
            GameInstanceID instanceId,
            Collection<? extends ComponentRemoteVersion> remoteVersions);

    /// Builds a task that removes one library identifier, saves metadata, refreshes, and returns a snapshot.
    ///
    /// @param instanceId stable target instance identifier
    /// @param libraryId exact Core library or patch identifier
    /// @return stopped removal task
    Task<InstanceInstallerSnapshot> removeLibrary(GameInstanceID instanceId, String libraryId);

    /// Builds a task that applies one supported local installer file to an existing instance.
    ///
    /// Core currently recognizes supported Forge, NeoForge, Cleanroom, and OptiFine installer formats.
    ///
    /// @param instanceId stable target instance identifier
    /// @param installer local installer file selected by the user
    /// @return stopped offline-installation task
    Task<InstanceInstallerSnapshot> installOffline(GameInstanceID instanceId, Path installer);
}
