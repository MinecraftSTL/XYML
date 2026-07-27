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
package space.minecraftstl.xyml.ui.swing.page.instances.management.maintenance;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.task.Task;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.concurrent.CompletionStage;

/// Supplies stopped Core tasks for one instance's repair and cleanup operations.
///
/// Snapshot loading starts immediately on the implementation's worker. Mutation methods only construct
/// stopped tasks; the Swing page owns executor startup, cancellation, progress presentation, and completion.
@NotNullByDefault
public interface InstanceMaintenanceService {
    /// Reads the latest local maintenance scope without blocking the Swing event-dispatch thread.
    ///
    /// @return asynchronous authoritative snapshot
    CompletionStage<InstanceMaintenanceSnapshot> loadSnapshot();

    /// Creates a stopped update task for a locally selected archive matching the installed modpack type.
    ///
    /// @param archive local `.zip` or `.mrpack` archive
    /// @param charset archive entry-name charset
    /// @return stopped update and refresh task yielding the new snapshot
    Task<InstanceMaintenanceSnapshot> updateModpack(Path archive, Charset charset);

    /// Creates a stopped task that forcibly refreshes the selected instance's asset index and objects.
    ///
    /// @return stopped asset repair task yielding the new snapshot
    Task<InstanceMaintenanceSnapshot> redownloadAssets();

    /// Creates a stopped task that deletes shared assets and the instance's legacy resources directory.
    ///
    /// @return stopped destructive cleanup task yielding the new snapshot
    Task<InstanceMaintenanceSnapshot> removeAssets();

    /// Creates a stopped task that deletes the repository-wide libraries directory.
    ///
    /// @return stopped destructive cleanup task yielding the new snapshot
    Task<InstanceMaintenanceSnapshot> removeLibraries();

    /// Creates a stopped task that removes generated logs and crash reports for the repository and instance.
    ///
    /// @return stopped generated-file cleanup task yielding the new snapshot
    Task<InstanceMaintenanceSnapshot> cleanGeneratedFiles();
}
