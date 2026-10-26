// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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

    /// Requests cancellation while preparation still owns the single-flight slot.
    ///
    /// If the task produces a process after cancellation is accepted, that process is forcefully terminated and the
    /// session reaches [LaunchStatus#CANCELLED] instead of exposing a successful process result. Once preparation has
    /// already reached [LaunchStatus#PROCESS_CREATED], later cancellation requests are rejected.
    ///
    /// @return true only for the first accepted cancellation request
    boolean cancel();
}
