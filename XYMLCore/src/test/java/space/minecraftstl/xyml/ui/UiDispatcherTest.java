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
package space.minecraftstl.xyml.ui;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Tests the toolkit-neutral UI dispatch contract.
@NotNullByDefault
public final class UiDispatcherTest {
    /// Verifies that work is queued when the caller is outside the UI thread.
    @Test
    public void dispatchesWhenOutsideUiThread() {
        RecordingUiDispatcher dispatcher = new RecordingUiDispatcher();
        AtomicInteger executions = new AtomicInteger();

        dispatcher.dispatchOrRun(executions::incrementAndGet);

        assertEquals(0, executions.get());
        assertEquals(1, dispatcher.pendingCount());

        dispatcher.runNextOnDispatchThread();

        assertEquals(1, executions.get());
        assertEquals(0, dispatcher.pendingCount());
    }

    /// Verifies that work runs inline when the caller is already on the UI thread.
    @Test
    public void runsImmediatelyOnUiThread() {
        RecordingUiDispatcher dispatcher = new RecordingUiDispatcher();
        AtomicInteger executions = new AtomicInteger();

        dispatcher.runOnDispatchThread(() -> dispatcher.dispatchOrRun(executions::incrementAndGet));

        assertEquals(1, executions.get());
        assertEquals(0, dispatcher.pendingCount());
    }

    /// In-memory dispatcher used to test thread-boundary decisions without a desktop toolkit.
    @NotNullByDefault
    private static final class RecordingUiDispatcher implements UiDispatcher {
        /// Operations waiting for simulated UI-thread execution.
        private final Queue<Runnable> pending = new ArrayDeque<>();

        /// Whether the current test callback represents the UI thread.
        private boolean dispatchThread;

        /// Returns whether the test is currently executing a simulated UI callback.
        @Override
        public boolean isDispatchThread() {
            return dispatchThread;
        }

        /// Stores an operation until the test advances the simulated queue.
        @Override
        public void dispatch(Runnable operation) {
            pending.add(operation);
        }

        /// Returns the number of queued operations.
        private int pendingCount() {
            return pending.size();
        }

        /// Runs the oldest queued operation on the simulated UI thread.
        private void runNextOnDispatchThread() {
            runOnDispatchThread(pending.remove());
        }

        /// Runs an operation while reporting that the current callback is on the simulated UI thread.
        private void runOnDispatchThread(Runnable operation) {
            dispatchThread = true;
            try {
                operation.run();
            } finally {
                dispatchThread = false;
            }
        }
    }
}
