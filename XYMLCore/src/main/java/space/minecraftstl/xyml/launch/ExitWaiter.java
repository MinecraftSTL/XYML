// SPDX-FileCopyrightText: Copyright (C) 2020 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.launch;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.util.Log4jLevel;
import space.minecraftstl.xyml.util.StringUtils;
import space.minecraftstl.xyml.util.platform.ManagedProcess;
import space.minecraftstl.xyml.util.platform.OperatingSystem;

import java.util.Collection;
import java.util.List;
import java.util.function.BiConsumer;

/// Waits for process termination and reports the classified result to its listener.
///
/// @author huangyuhui
@NotNullByDefault
final class ExitWaiter implements Runnable {

    private final ManagedProcess process;
    private final Collection<Thread> joins;
    private final BiConsumer<Integer, ProcessListener.ExitType> watcher;

    /**
     * Constructor.
     *
     * @param process the process to wait for
     * @param watcher the callback that will be called after process stops.
     */
    public ExitWaiter(ManagedProcess process, Collection<Thread> joins, BiConsumer<Integer, ProcessListener.ExitType> watcher) {
        this.process = process;
        this.joins = joins;
        this.watcher = watcher;
    }

    /// Waits for output pumps before classifying termination and invoking the listener.
    @Override
    public void run() {
        try {
            int exitCode = process.getProcess().waitFor();

            for (Thread thread : joins)
                thread.join();

            List<String> errorLines = process.getLines(Log4jLevel::guessLogLineError);
            ProcessListener.ExitType exitType;

            // A forced launcher stop is not an application failure even when the operating system reports a non-zero
            // exit code before this waiter is interrupted.
            if (process.isForceStopRequested()) {
                exitType = ProcessListener.ExitType.INTERRUPTED;
            } else if (exitCode != 0 && StringUtils.containsOne(errorLines,
                    "Could not create the Java Virtual Machine.",
                    "Error occurred during initialization of VM",
                    "A fatal exception has occurred. Program will exit.")) {
                exitType = ProcessListener.ExitType.JVM_ERROR;
            } else if (exitCode != 0 || StringUtils.containsOne(errorLines,
                    "Crash report saved to", "Could not save crash report to", "This crash report has been saved to:",
                    "Unable to launch", "An exception was thrown, the game will display an error screen and halt.")) {
                if (exitCode == 137 && OperatingSystem.CURRENT_OS.isLinuxOrBSD()) {
                    exitType = ProcessListener.ExitType.SIGKILL;
                } else {
                    exitType = ProcessListener.ExitType.APPLICATION_ERROR;
                }
            } else {
                exitType = ProcessListener.ExitType.NORMAL;
            }

            watcher.accept(exitCode, exitType);
        } catch (InterruptedException e) {
            watcher.accept(1, ProcessListener.ExitType.INTERRUPTED);
        }
    }

}
