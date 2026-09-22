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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import space.minecraftstl.xyml.util.platform.ManagedProcess;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies launcher-driven exit classification on [ExitWaiter].
@NotNullByDefault
final class ExitWaiterTest {
    /// Verifies a forced stop remains interrupted even when the operating system exits non-zero first.
    @Test
    @Timeout(10)
    void forcedStopMapsNonZeroExitToInterrupted() {
        NonZeroExitProcess rawProcess = new NonZeroExitProcess();
        ManagedProcess process = new ManagedProcess(rawProcess, List.of("java", "test.Main"));
        process.forceStop();
        AtomicInteger observedExitCode = new AtomicInteger();
        AtomicReference<ProcessListener.ExitType> observedExitType =
                new AtomicReference<>(ProcessListener.ExitType.NORMAL);

        new ExitWaiter(process, List.of(), (exitCode, exitType) -> {
            observedExitCode.set(exitCode);
            observedExitType.set(exitType);
        }).run();

        assertEquals(NonZeroExitProcess.EXIT_CODE, observedExitCode.get());
        assertEquals(ProcessListener.ExitType.INTERRUPTED, observedExitType.get());
    }

    /// Minimal process fixture whose first observed exit is non-zero.
    @NotNullByDefault
    private static final class NonZeroExitProcess extends Process {
        /// Stable non-zero exit code returned to the monitor.
        private static final int EXIT_CODE = 137;

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

        /// Returns the stable non-zero exit code.
        @Override
        public int waitFor() {
            return EXIT_CODE;
        }

        /// Returns the stable non-zero exit code.
        @Override
        public int exitValue() {
            return EXIT_CODE;
        }

        /// Accepts the ordinary termination request.
        @Override
        public void destroy() {
        }

        /// Accepts the forced termination request.
        @Override
        public Process destroyForcibly() {
            return this;
        }

        /// Reports the fixture as exited.
        @Override
        public boolean isAlive() {
            return false;
        }
    }
}
