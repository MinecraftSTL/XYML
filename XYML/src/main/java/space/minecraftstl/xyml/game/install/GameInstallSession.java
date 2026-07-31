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
