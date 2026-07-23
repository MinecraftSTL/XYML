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
package space.minecraftstl.xyml.game.launch;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.observable.property.ReadOnlyProperty;
import space.minecraftstl.xyml.task.presentation.TaskPresentationModel;
import space.minecraftstl.xyml.util.platform.ManagedProcess;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/// Exposes one launch preparation without owning the lifetime of a successfully created process.
@NotNullByDefault
public interface LaunchSession extends TaskPresentationModel {
    /// Returns the stable request captured for this preparation.
    ///
    /// @return immutable launch request
    LaunchRequest request();

    /// Returns the latest preparation status.
    ///
    /// @return current status
    LaunchStatus status();

    /// Returns a toolkit-neutral observable preparation status.
    ///
    /// Changes are delivered synchronously on the worker that completes preparation.
    ///
    /// @return read-only status property
    ReadOnlyProperty<LaunchStatus> statusProperty();

    /// Returns a completion stage for the created process.
    ///
    /// Cancellation completes this stage with [java.util.concurrent.CancellationException], while preparation
    /// failures complete it exceptionally with their original failure.
    ///
    /// @return process completion stage
    CompletionStage<ManagedProcess> completion();

    /// Returns the process after it has been created, including a process created concurrently with cancellation.
    ///
    /// @return created process, or an empty value while absent
    Optional<ManagedProcess> createdProcess();

    /// Returns the terminal non-cancellation failure when one was recorded.
    ///
    /// @return preparation failure, or an empty value otherwise
    Optional<Throwable> failure();

    /// Requests cooperative cancellation while preparation still owns the single-flight slot.
    ///
    /// This method never stops a process that has already been created.
    ///
    /// @return true only for the first accepted cancellation request
    boolean cancel();
}
