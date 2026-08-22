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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.task.Task;

import java.util.List;
import java.util.Objects;

/// Describes an actionable repair through a presentation-toolkit-neutral wizard contract.
@NotNullByDefault
public interface Solver {
    /// Selection identifier used by the standard next command.
    int BTN_NEXT = 0;

    /// Returns the localization key used by launcher presentation layers.
    ///
    /// @return stable localization key
    String messageKey();

    /// Returns immutable non-null arguments for the localized repair text.
    ///
    /// @return immutable formatting arguments
    @Unmodifiable List<Object> messageArguments();

    /// Returns a presentation-independent English repair description.
    ///
    /// @return fallback repair text suitable for Core and MCP callers
    String fallbackMessage();

    /// Configures the current manual or automatic repair step.
    ///
    /// Presentation layers invoke this method on their UI thread. Implementations must configure either descriptive
    /// content or one automatic task without starting the task themselves.
    ///
    /// @param configurator presentation-neutral step configurator
    void configure(SolverConfigurator configurator);

    /// Handles one user selection or automatic-task completion.
    ///
    /// Presentation layers invoke this method on their UI thread. `BTN_NEXT` represents the standard next command;
    /// other identifiers are allocated by [SolverConfigurator#putButton(String, List, String)].
    ///
    /// @param configurator presentation-neutral step configurator
    /// @param selectionId selected command identifier
    void callbackSelection(SolverConfigurator configurator, int selectionId);

    /// Returns the optional executable repair task bound by this solver.
    ///
    /// @return executable repair task, or null when the repair requires user action
    default @Nullable Task<?> createTask() {
        return null;
    }

    /// Creates an automatic solver around one stopped task.
    ///
    /// @param task stopped repair task that a configurator may start
    /// @return automatic solver that advances after task completion
    static Solver ofTask(Task<?> task) {
        return new TaskSolver(
                "game.crash.solver.automatic",
                List.of(),
                "Apply the automatic repair.",
                Objects.requireNonNull(task, "task"));
    }

    /// Creates the Java-runtime replacement solver for one analyzable launch.
    ///
    /// The input owns a Core-neutral repair boundary supplied by the application layer. This preserves HMAT's
    /// `ofUninstallJRE(LogAnalyzable)` contract without making Core depend on the launcher's Java manager.
    ///
    /// @param input immutable launch context with an application Java repair boundary
    /// @return automatic Java-runtime replacement solver
    /// @throws IllegalArgumentException when the input has no Java repair boundary
    static Solver ofUninstallJRE(LogAnalyzable input) {
        return ofUninstallJRE(
                input,
                "game.crash.solver.replace_java",
                List.of(),
                "Replace the incompatible Java runtime and select a compatible runtime.");
    }

    /// Creates a Java-runtime replacement solver while retaining analyzer-specific diagnosis text.
    ///
    /// @param input immutable launch context with an application Java repair boundary
    /// @param messageKey localization key describing the diagnosed Java incompatibility
    /// @param messageArguments immutable localization arguments
    /// @param fallbackMessage presentation-independent English diagnosis and repair text
    /// @return automatic Java-runtime replacement solver with analyzer-specific metadata
    /// @throws IllegalArgumentException when the input has no Java repair boundary
    static Solver ofUninstallJRE(
            LogAnalyzable input,
            String messageKey,
            @Unmodifiable List<Object> messageArguments,
            String fallbackMessage) {
        LogAnalyzable checkedInput = Objects.requireNonNull(input, "input");
        LogAnalyzable.@Nullable JavaRuntimeRepair repair = checkedInput.javaRuntimeRepair();
        if (repair == null) {
            throw new IllegalArgumentException("input does not provide a Java runtime repair");
        }
        Task<?> replacementTask = Objects.requireNonNull(repair.createTask(), "Java runtime repair task");
        return new TaskSolver(
                Objects.requireNonNull(messageKey, "messageKey"),
                List.copyOf(Objects.requireNonNull(messageArguments, "messageArguments")),
                Objects.requireNonNull(fallbackMessage, "fallbackMessage"),
                replacementTask);
    }
}
