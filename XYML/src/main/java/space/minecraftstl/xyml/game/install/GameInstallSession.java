// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.game.install;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.observable.property.ReadOnlyProperty;
import space.minecraftstl.xyml.task.presentation.TaskPresentationModel;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/// Exposes one vanilla installation through toolkit-neutral lifecycle and task presentation state.
@NotNullByDefault
public interface GameInstallSession extends TaskPresentationModel {
    /// Returns the stable request captured before asynchronous preparation.
    ///
    /// @return immutable installation request
    GameInstallRequest request();

    /// Returns the latest authoritative installation status.
    ///
    /// @return current installation status
    GameInstallStatus status();

    /// Returns the observable installation status.
    ///
    /// Changes are published synchronously by the worker performing the transition.
    ///
    /// @return read-only status property
    ReadOnlyProperty<GameInstallStatus> statusProperty();

    /// Returns whether the underlying task executor has entered the application task registry.
    ///
    /// @return read-only submitted-state property
    ReadOnlyProperty<Boolean> submittedProperty();

    /// Returns a stage that completes normally only after repository post-processing succeeds.
    ///
    /// Cancellation completes with [java.util.concurrent.CancellationException].
    ///
    /// @return minimal installation completion stage
    CompletionStage<Void> completion();

    /// Returns the exact non-cancellation terminal failure when one exists.
    ///
    /// @return terminal failure, or an empty value otherwise
    Optional<Throwable> failure();

    /// Requests cooperative cancellation while installation is active.
    ///
    /// @return true only for the first accepted request
    boolean cancel();
}
