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
package space.minecraftstl.xyml.util;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies failure classification independently from native window availability.
@NotNullByDefault
class CrashReporterTest {
    /// Recognizes nested missing-class failures from their complete stack trace.
    @Test
    void recognizesKnownEnvironmentFailure() {
        Throwable failure = new IllegalStateException(
                "wrapper",
                new NoClassDefFoundError("missing/runtime/Class"));

        assertNotNull(CrashReporter.findKnownFailureMessage(failure));
    }

    /// Leaves ordinary launcher failures eligible for the complete report window.
    @Test
    void leavesUnknownFailureUnclassified() {
        assertNull(CrashReporter.findKnownFailureMessage(new IllegalStateException("ordinary failure")));
    }

    /// Reads update availability lazily so a later successful check changes the crash headline decision.
    @Test
    void readsInjectedUpdateAvailabilityAtPresentationTime() {
        AtomicBoolean updateAvailable = new AtomicBoolean();
        CrashReporter reporter = new CrashReporter(true, updateAvailable::get);

        assertFalse(reporter.isUpdateAvailable());
        updateAvailable.set(true);
        assertTrue(reporter.isUpdateAvailable());
    }
}
