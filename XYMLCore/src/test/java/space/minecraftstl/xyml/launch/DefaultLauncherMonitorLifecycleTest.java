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
package space.minecraftstl.xyml.launch;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies the terminal boundary around the complete process exit monitor.
@NotNullByDefault
class DefaultLauncherMonitorLifecycleTest {
    /// Reports successful completion only after all monitor work returns.
    @Test
    void reportsCompletionAfterMonitorReturns() {
        List<String> events = new ArrayList<>();
        RecordingProcessListener listener = new RecordingProcessListener(events, null);

        DefaultLauncher.runMonitorLifecycle(
                () -> events.add("monitor"),
                listener);

        assertEquals(List.of("monitor", "complete"), events);
        assertNull(listener.monitorFailure());
    }

    /// Preserves and reports an exact monitor failure before rethrowing it.
    @Test
    void reportsExactMonitorFailure() {
        IllegalStateException failure = new IllegalStateException("monitor failed");
        RecordingProcessListener listener = new RecordingProcessListener(new ArrayList<>(), null);

        IllegalStateException observed = assertThrows(
                IllegalStateException.class,
                () -> DefaultLauncher.runMonitorLifecycle(
                        () -> {
                            throw failure;
                        },
                        listener));

        assertSame(failure, observed);
        assertSame(failure, listener.monitorFailure());
    }

    /// Retains the monitor failure when terminal notification also fails.
    @Test
    void suppressesNotificationFailureOntoMonitorFailure() {
        IllegalStateException monitorFailure = new IllegalStateException("monitor failed");
        IllegalArgumentException notificationFailure =
                new IllegalArgumentException("notification failed");
        RecordingProcessListener listener = new RecordingProcessListener(
                new ArrayList<>(),
                notificationFailure);

        IllegalStateException observed = assertThrows(
                IllegalStateException.class,
                () -> DefaultLauncher.runMonitorLifecycle(
                        () -> {
                            throw monitorFailure;
                        },
                        listener));

        assertSame(monitorFailure, observed);
        assertEquals(List.of(notificationFailure), List.of(observed.getSuppressed()));
    }

    /// Minimal listener recording monitor completion without process-output behavior.
    @NotNullByDefault
    private static final class RecordingProcessListener implements ProcessListener {
        /// Ordered test events.
        private final List<String> events;

        /// Optional failure raised during terminal notification.
        private final @Nullable RuntimeException notificationFailure;

        /// Failure received from the monitor, or null after normal completion.
        private @Nullable Throwable monitorFailure;

        /// Creates one deterministic listener.
        ///
        /// @param events ordered event destination
        /// @param notificationFailure optional terminal-notification failure
        private RecordingProcessListener(
                List<String> events,
                @Nullable RuntimeException notificationFailure) {
            this.events = events;
            this.notificationFailure = notificationFailure;
        }

        /// Ignores output in this lifecycle-only test.
        ///
        /// @param log decoded output line
        /// @param isErrorStream whether the line came from stderr
        @Override
        public void onLog(String log, boolean isErrorStream) {
        }

        /// Ignores classified exit in this lifecycle-only test.
        ///
        /// @param exitCode raw exit code
        /// @param exitType classified exit type
        @Override
        public void onExit(int exitCode, ExitType exitType) {
        }

        /// Records terminal monitor completion and optionally raises a deterministic failure.
        ///
        /// @param failure monitor failure, or null after normal completion
        @Override
        public void onMonitorComplete(@Nullable Throwable failure) {
            monitorFailure = failure;
            events.add("complete");
            if (notificationFailure != null) {
                throw notificationFailure;
            }
        }

        /// Returns the terminal failure supplied by the monitor.
        ///
        /// @return monitor failure, or null after normal completion
        private @Nullable Throwable monitorFailure() {
            return monitorFailure;
        }
    }
}
