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
import space.minecraftstl.xyml.game.GameJavaVersion;
import space.minecraftstl.xyml.java.JavaRuntime;
import space.minecraftstl.xyml.task.Task;

import java.nio.file.Path;

/// Provides offline capability reads and stopped tasks for acquiring launcher-managed Java runtimes.
///
/// Implementations must not contact remote services while they are constructed, while [#loadSnapshot()] is created,
/// or while a task is merely created. Network and archive access begins only after the caller starts a returned task.
@NotNullByDefault
public interface JavaRuntimeAcquisitionService {
    /// Creates a stopped task that reads the current platform's built-in choices and local installation markers.
    ///
    /// The task performs only local capability and repository reads; it never contacts a download endpoint.
    ///
    /// @return stopped task yielding an immutable acquisition capability snapshot
    Task<JavaRuntimeAcquisitionSnapshot> loadSnapshot();

    /// Returns whether a path has one of the supported local Java archive suffixes.
    ///
    /// This check is purely lexical and does not open the file.
    ///
    /// @param archiveFile candidate local archive path
    /// @return whether the path ends with `.zip` or `.tar.gz`
    boolean supportsLocalArchive(Path archiveFile);

    /// Creates a stopped task that downloads one supported Mojang runtime for the current platform.
    ///
    /// @param version selected built-in Mojang runtime version
    /// @return stopped download and registration task
    Task<JavaRuntime> downloadMojangRuntime(GameJavaVersion version);

    /// Creates a stopped task that opens and validates one local Java archive.
    ///
    /// @param archiveFile selected `.zip` or `.tar.gz` archive
    /// @return stopped archive inspection task
    Task<LocalJavaArchiveInspection> inspectLocalArchive(Path archiveFile);

    /// Validates a proposed launcher-managed installation name against syntax, reserved names, and local state.
    ///
    /// This method may read the local managed-runtime repository but must not contact remote services.
    ///
    /// @param inspection inspected archive metadata that determines the target platform repository
    /// @param name proposed managed-runtime name
    /// @return exact validation status
    JavaRuntimeInstallNameStatus validateInstallName(LocalJavaArchiveInspection inspection, String name);

    /// Creates a stopped task that installs an inspected archive under a validated managed-runtime name.
    ///
    /// Name availability and archive platform metadata are checked again after task startup.
    ///
    /// @param inspection previously inspected local archive
    /// @param name proposed managed-runtime name
    /// @return stopped installation and registration task
    Task<JavaRuntime> installLocalArchive(LocalJavaArchiveInspection inspection, String name);
}
