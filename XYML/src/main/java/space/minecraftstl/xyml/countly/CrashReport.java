// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.countly;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.Metadata;
import space.minecraftstl.xyml.util.StringUtils;
import space.minecraftstl.xyml.util.platform.Architecture;
import space.minecraftstl.xyml.util.platform.OperatingSystem;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/// Immutable launcher crash information used for local presentation and report eligibility checks.
@NotNullByDefault
public final class CrashReport {

    /// Thread on which the uncaught failure occurred.
    private final Thread thread;

    /// Original uncaught failure.
    private final Throwable throwable;

    /// Stable stack-trace text captured when the report is created.
    private final String stackTrace;

    /// Captures one uncaught failure and its originating thread.
    ///
    /// @param thread thread on which the failure occurred
    /// @param throwable uncaught failure
    public CrashReport(Thread thread, Throwable throwable) {
        this.thread = thread;
        this.throwable = throwable;
        stackTrace = StringUtils.getStackTrace(throwable);
    }

    /// Returns the original uncaught failure.
    ///
    /// @return original failure
    public Throwable getThrowable() {
        return this.throwable;
    }

    /// Returns whether this launcher-owned non-VM failure is eligible for reporting.
    ///
    /// @return whether the stack belongs to XYML and the VM remains usable
    public boolean shouldBeReport() {
        if (!stackTrace.contains("space.minecraftstl"))
            return false;

        if (throwable instanceof VirtualMachineError)
            return false;

        return true;
    }

    /// Formats the crash, environment, and memory details for user-visible diagnostics.
    ///
    /// @return complete plain-text XYML crash report
    public String getDisplayText() {
        return "---- XYML Crash Report ----\n" +
                "  Version: " + Metadata.VERSION + "\n" +
                "  Time: " + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now()) + "\n" +
                "  Thread: " + thread + "\n" +
                "\n  Content: \n    " +
                stackTrace + "\n\n" +
                "-- System Details --\n" +
                "  Operating System: " + OperatingSystem.SYSTEM_NAME + ' ' + OperatingSystem.SYSTEM_VERSION.getVersion() + "\n" +
                "  System Architecture: " + Architecture.SYSTEM_ARCH.getDisplayName() + "\n" +
                "  Java Architecture: " + Architecture.CURRENT_ARCH.getDisplayName() + "\n" +
                "  Java Version: " + System.getProperty("java.version") + ", " + System.getProperty("java.vendor") + "\n" +
                "  Java VM Version: " + System.getProperty("java.vm.name") + " (" + System.getProperty("java.vm.info") + "), " + System.getProperty("java.vm.vendor") + "\n" +
                "  JVM Max Memory: " + Runtime.getRuntime().maxMemory() + "\n" +
                "  JVM Total Memory: " + Runtime.getRuntime().totalMemory() + "\n" +
                "  JVM Free Memory: " + Runtime.getRuntime().freeMemory() + "\n";
    }
}
