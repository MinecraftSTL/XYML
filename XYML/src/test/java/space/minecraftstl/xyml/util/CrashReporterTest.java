// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
