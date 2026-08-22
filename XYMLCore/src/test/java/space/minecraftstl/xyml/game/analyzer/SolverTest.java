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
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.launch.ProcessListener;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.platform.Bits;
import space.minecraftstl.xyml.util.platform.OperatingSystem;

import java.net.URI;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies presentation-neutral solver configuration, callbacks, and automatic task factories.
@NotNullByDefault
class SolverTest {
    /// Configures text metadata and advances after the standard next command.
    @Test
    void configuresAndAdvancesTextSolver() {
        TextSolver solver = new TextSolver("test.solver", List.of("argument"), "Fallback repair.");
        RecordingConfigurator configurator = new RecordingConfigurator();

        solver.configure(configurator);
        assertEquals("test.solver", configurator.descriptionKey);
        assertEquals(List.of("argument"), configurator.descriptionArguments);
        assertEquals("Fallback repair.", configurator.descriptionFallback);
        assertNull(configurator.task);

        solver.callbackSelection(configurator, Solver.BTN_NEXT);
        assertTrue(configurator.transferred);
        assertNull(configurator.transferredSolver);
    }

    /// Binds the exact stopped task and advances after automatic completion.
    @Test
    void configuresAutomaticTaskSolver() {
        Task<?> task = Task.completed(null);
        Solver solver = Solver.ofTask(task);
        RecordingConfigurator configurator = new RecordingConfigurator();

        solver.configure(configurator);
        assertSame(task, configurator.task);
        assertSame(task, solver.createTask());

        solver.callbackSelection(configurator, Solver.BTN_NEXT);
        assertTrue(configurator.transferred);
        assertNull(configurator.transferredSolver);
    }

    /// Marks a complete Java replacement task with the dedicated repair metadata.
    @Test
    void createsUninstallJreSolver() {
        Task<?> task = Task.completed(null);
        LogAnalyzable input = repairableInput(task);
        Solver solver = Solver.ofUninstallJRE(input);
        RecordingConfigurator configurator = new RecordingConfigurator();

        solver.configure(configurator);
        assertEquals("game.crash.solver.replace_java", solver.messageKey());
        assertSame(task, configurator.task);
    }

    /// Rejects the HMAT-style Java replacement factory when application repair context is unavailable.
    @Test
    void rejectsUninstallJreSolverWithoutRepairContext() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> Solver.ofUninstallJRE(new LogAnalyzable(
                        null,
                        null,
                        ProcessListener.ExitType.APPLICATION_ERROR,
                        OperatingSystem.CURRENT_OS,
                        OperatingSystem.CODE_PAGE,
                        null,
                        null,
                        null,
                        null,
                        Bits.UNKNOWN,
                        null,
                        List.of())));

        assertEquals("input does not provide a Java runtime repair", exception.getMessage());
    }

    /// Creates a minimal analysis input whose repair boundary returns the supplied stopped task.
    ///
    /// @param task stopped Java replacement task
    /// @return repairable immutable launch context
    private static LogAnalyzable repairableInput(Task<?> task) {
        return new LogAnalyzable(
                null,
                null,
                ProcessListener.ExitType.APPLICATION_ERROR,
                OperatingSystem.CURRENT_OS,
                OperatingSystem.CODE_PAGE,
                null,
                null,
                null,
                null,
                Bits.UNKNOWN,
                null,
                List.of()).withJavaRuntimeRepair(() -> task);
    }

    /// Minimal in-memory configurator used to inspect one solver step.
    @NotNullByDefault
    private static final class RecordingConfigurator implements SolverConfigurator {
        /// Configured image URI, or null when the step has no image.
        private @Nullable URI image;

        /// Configured description localization key, or null for an automatic step.
        private @Nullable String descriptionKey;

        /// Immutable configured description arguments.
        private @Unmodifiable List<Object> descriptionArguments = List.of();

        /// Configured fallback description, or null for an automatic step.
        private @Nullable String descriptionFallback;

        /// Bound automatic task, or null for a manual step.
        private @Nullable Task<?> task;

        /// Solver supplied to the latest transfer, or null to continue to the next diagnosis.
        private @Nullable Solver transferredSolver;

        /// Whether a transfer was requested.
        private boolean transferred;

        /// Next custom selection identifier.
        private int nextSelectionId = 256;

        /// Records the configured image URI.
        ///
        /// @param image toolkit-neutral image resource URI
        @Override
        public void setImage(URI image) {
            this.image = Objects.requireNonNull(image, "image");
        }

        /// Records immutable description metadata.
        ///
        /// @param messageKey localization key
        /// @param messageArguments immutable localization arguments
        /// @param fallbackMessage presentation-independent English fallback
        @Override
        public void setDescription(
                String messageKey,
                @Unmodifiable List<Object> messageArguments,
                String fallbackMessage) {
            descriptionKey = Objects.requireNonNull(messageKey, "messageKey");
            descriptionArguments = List.copyOf(Objects.requireNonNull(messageArguments, "messageArguments"));
            descriptionFallback = Objects.requireNonNull(fallbackMessage, "fallbackMessage");
        }

        /// Allocates one custom selection identifier.
        ///
        /// @param messageKey localization key
        /// @param messageArguments immutable localization arguments
        /// @param fallbackMessage presentation-independent English fallback
        /// @return allocated selection identifier
        @Override
        public int putButton(
                String messageKey,
                @Unmodifiable List<Object> messageArguments,
                String fallbackMessage) {
            Objects.requireNonNull(messageKey, "messageKey");
            List.copyOf(Objects.requireNonNull(messageArguments, "messageArguments"));
            Objects.requireNonNull(fallbackMessage, "fallbackMessage");
            return nextSelectionId++;
        }

        /// Records the automatic task bound to the step.
        ///
        /// @param task stopped repair task owned by this step
        @Override
        public void bindTask(Task<?> task) {
            this.task = Objects.requireNonNull(task, "task");
        }

        /// Records a transfer to another solver or the next diagnosis.
        ///
        /// @param solver next solver, or null to continue with the next diagnosis
        @Override
        public void transferTo(@Nullable Solver solver) {
            transferred = true;
            transferredSolver = solver;
        }
    }
}
