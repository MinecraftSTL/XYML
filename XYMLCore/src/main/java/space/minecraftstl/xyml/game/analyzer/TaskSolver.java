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
package space.minecraftstl.xyml.game.analyzer;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.task.Task;

import java.util.List;
import java.util.Objects;

/// Automatic repair solver backed by one stopped task.
///
/// @param messageKey localization key used by non-wizard presentation layers
/// @param messageArguments immutable localization arguments
/// @param fallbackMessage presentation-independent English repair text
/// @param task stopped repair task bound during configuration
@NotNullByDefault
record TaskSolver(
        String messageKey,
        @Unmodifiable List<Object> messageArguments,
        String fallbackMessage,
        Task<?> task) implements Solver {
    /// Defensively copies repair metadata and validates the stopped task reference.
    TaskSolver {
        Objects.requireNonNull(messageKey, "messageKey");
        messageArguments = List.copyOf(Objects.requireNonNull(messageArguments, "messageArguments"));
        Objects.requireNonNull(fallbackMessage, "fallbackMessage");
        Objects.requireNonNull(task, "task");
    }

    /// Binds the automatic repair task to the current solver step.
    ///
    /// @param configurator presentation-neutral step configurator
    @Override
    public void configure(SolverConfigurator configurator) {
        Objects.requireNonNull(configurator, "configurator").bindTask(task);
    }

    /// Advances after the automatic repair task completes.
    ///
    /// @param configurator presentation-neutral step configurator
    /// @param selectionId completed-task selection identifier
    @Override
    public void callbackSelection(SolverConfigurator configurator, int selectionId) {
        Objects.requireNonNull(configurator, "configurator").transferTo(null);
    }

    /// Returns the exact task bound during configuration.
    ///
    /// @return stopped repair task
    @Override
    public Task<?> createTask() {
        return task;
    }
}
