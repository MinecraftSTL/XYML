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
package space.minecraftstl.xyml.util.platform;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies forced termination state and monitor-thread ownership on [ManagedProcess].
@NotNullByDefault
final class ManagedProcessTest {
    /// Verifies one idempotent forced stop kills the direct process and interrupts related threads.
    @Test
    @Timeout(10)
    void forceStopIsIdempotentAndInterruptsRelatedThreads() throws Exception {
        RecordingProcess rawProcess = new RecordingProcess();
        ManagedProcess process = new ManagedProcess(rawProcess, List.of("java", "test.Main"));
        CountDownLatch monitorInterrupted = new CountDownLatch(1);
        Thread monitor = new Thread(() -> {
            try {
                Thread.sleep(60_000L);
            } catch (InterruptedException expected) {
                monitorInterrupted.countDown();
                Thread.currentThread().interrupt();
            }
        }, "managed-process-monitor");
        monitor.setDaemon(true);
        process.addRelatedThread(monitor);
        monitor.start();

        process.forceStop();
        process.forceStop();

        assertTrue(process.isForceStopRequested());
        assertFalse(process.isRunning());
        assertEquals(1, rawProcess.forceDestroyCalls());
        assertEquals(0, rawProcess.destroyCalls());
        assertTrue(monitorInterrupted.await(5, TimeUnit.SECONDS));
        monitor.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(monitor.isAlive());
    }

    /// Minimal process fixture that records graceful and forced termination requests.
    @NotNullByDefault
    private static final class RecordingProcess extends Process {
        /// Count of ordinary destruction requests.
        private final AtomicInteger destroyCalls = new AtomicInteger();

        /// Count of forced destruction requests.
        private final AtomicInteger forceDestroyCalls = new AtomicInteger();

        /// Whether this fixture remains alive.
        private volatile boolean alive = true;

        /// Returns a disposable standard-input sink.
        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        /// Returns an empty standard-output stream.
        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        /// Returns an empty standard-error stream.
        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        /// Marks the fixture exited.
        @Override
        public int waitFor() {
            alive = false;
            return 0;
        }

        /// Returns the fixture exit code.
        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException("process is still alive");
            }
            return 0;
        }

        /// Records one ordinary destruction request.
        @Override
        public void destroy() {
            destroyCalls.incrementAndGet();
            alive = false;
        }

        /// Records one forced destruction request.
        @Override
        public Process destroyForcibly() {
            forceDestroyCalls.incrementAndGet();
            alive = false;
            return this;
        }

        /// Returns whether the fixture process remains alive.
        @Override
        public boolean isAlive() {
            return alive;
        }

        /// Returns the ordinary destruction call count.
        ///
        /// @return graceful destruction count
        private int destroyCalls() {
            return destroyCalls.get();
        }

        /// Returns the forced destruction call count.
        ///
        /// @return forced destruction count
        private int forceDestroyCalls() {
            return forceDestroyCalls.get();
        }
    }
}
