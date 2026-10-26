// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.launch;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import space.minecraftstl.xyml.event.EventBus;
import space.minecraftstl.xyml.event.RefreshedGameInstancesEvent;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.task.FetchTask;
import space.minecraftstl.xyml.util.platform.ManagedProcess;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies process callbacks and independent speed events while retaining repository-event delivery.
@NotNullByDefault
@Timeout(10)
final class MergeEventCompatibilityTest {
    /// Successful exits invoke the listener with the normal classification.
    @Test
    void normalExitPreservesNotificationOrder() {
        assertEquals(List.of("watcher:NORMAL"), observe(0, null, false, false));
    }

    /// Application and JVM failures retain distinct listener classifications.
    @Test
    void failureExitPreservesNotificationOrder() {
        assertEquals(List.of("watcher:APPLICATION_ERROR"),
                observe(1, null, false, false));
        assertEquals(List.of("watcher:JVM_ERROR"),
                observe(1, "[00:00:00] [main/ERROR]: Could not create the Java Virtual Machine.", false, false));
    }

    /// Forced stops and interrupted waits both report interruption to the listener.
    @Test
    void preservesStopAndInterruptionSemantics() {
        assertEquals(List.of("watcher:INTERRUPTED"), observe(1, null, true, false));
        assertEquals(List.of("watcher:INTERRUPTED"), observe(1, null, false, true));
    }

    /// Repository events still use the global bus, while explicit speed events reach only their own manager.
    @Test
    void speedManagerIsIndependentAndRepositoryEventsRemainAvailable() {
        Object source = new Object();
        AtomicInteger refreshed = new AtomicInteger();
        AtomicInteger globalSpeed = new AtomicInteger();
        AtomicInteger directSpeed = new AtomicInteger();
        assertNotSame(EventBus.EVENT_BUS.channel(FetchTask.SpeedEvent.class), FetchTask.SPEED_EVENT);
        try (Subscription refresh = EventBus.EVENT_BUS.channel(RefreshedGameInstancesEvent.class).subscribe(event -> {
            if (event.getSource() == source)
                refreshed.incrementAndGet();
        }); Subscription global = EventBus.EVENT_BUS.channel(FetchTask.SpeedEvent.class).subscribe(event -> {
            if (event.getSource() == source)
                globalSpeed.incrementAndGet();
        }); Subscription speed = FetchTask.SPEED_EVENT.subscribe(event -> {
            if (event.getSource() == source) {
                assertEquals(73L, event.getSpeed());
                directSpeed.incrementAndGet();
            }
        })) {
            EventBus.EVENT_BUS.fireEvent(new RefreshedGameInstancesEvent(source));
            FetchTask.SPEED_EVENT.fireEvent(new FetchTask.SpeedEvent(source, 73L));
        }
        assertEquals(1, refreshed.get());
        assertEquals(1, directSpeed.get());
        assertEquals(0, globalSpeed.get());
    }

    /// Runs the existing waiter with a synthetic process and records listener callbacks.
    ///
    /// @param code process exit code
    /// @param error optional logged JVM failure
    /// @param forced whether the launcher requested a forced stop
    /// @param interrupted whether waiting throws interruption
    /// @return immutable ordered notification names
    private static @Unmodifiable List<String> observe(
            int code, @Nullable String error, boolean forced, boolean interrupted) {
        ManagedProcess process = new ManagedProcess(new FixtureProcess(code, interrupted), List.of("java", "fixture"));
        if (error != null)
            process.addLine(error);
        if (forced)
            process.forceStop();
        List<String> observed = new ArrayList<>();
        new ExitWaiter(process, List.of(), (exitCode, exitType) -> observed.add("watcher:" + exitType)).run();
        return List.copyOf(observed);
    }

    /// Synthetic exited process with an optional interrupted wait.
    @NotNullByDefault
    private static final class FixtureProcess extends Process {
        /// Stable reported exit code.
        private final int code;
        /// Whether the wait operation must report interruption.
        private final boolean interrupted;

        /// Stores deterministic process behavior.
        private FixtureProcess(int code, boolean interrupted) {
            this.code = code;
            this.interrupted = interrupted;
        }

        /// Returns a disposable input sink.
        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        /// Returns empty process output.
        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        /// Returns empty process error output.
        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        /// Reports the synthetic wait outcome.
        @Override
        public int waitFor() throws InterruptedException {
            if (interrupted)
                throw new InterruptedException("Synthetic interrupted wait");
            return code;
        }

        /// Returns the fixed exit code.
        @Override
        public int exitValue() {
            return code;
        }

        /// Accepts termination of the already exited fixture.
        @Override
        public void destroy() {
        }

        /// Returns the already exited fixture after forced termination.
        @Override
        public Process destroyForcibly() {
            return this;
        }

        /// Reports no running native process.
        @Override
        public boolean isAlive() {
            return false;
        }
    }
}
