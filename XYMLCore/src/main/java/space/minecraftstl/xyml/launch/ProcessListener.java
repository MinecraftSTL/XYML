/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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
import space.minecraftstl.xyml.util.platform.ManagedProcess;

/// Receives process attachment, output, classified exit, and monitor-lifecycle notifications.
@NotNullByDefault
public interface ProcessListener {
    /// Receives the newly started managed process before monitor threads begin.
    ///
    /// Shared stateless listeners normally retain the default no-op implementation.
    ///
    /// @param process started managed process
    default void setProcess(ManagedProcess process) {
    }

    /// Receives one decoded stdout or stderr line.
    ///
    /// Calls from the two stream pumps may be concurrent.
    ///
    /// @param log decoded process-output line
    /// @param isErrorStream whether the line came from stderr
    void onLog(String log, boolean isErrorStream);

    /// Receives the classified process exit after both stream pumps finish.
    ///
    /// The enclosing monitor may still run a configured post-exit command afterward. Use
    /// [#onMonitorComplete(Throwable)] when cleanup must wait for that complete lifecycle.
    ///
    /// @param exitCode raw process exit code
    /// @param exitType classified process exit type
    void onExit(int exitCode, ExitType exitType);

    /// Receives terminal completion of the entire exit monitor, including post-exit commands.
    ///
    /// This callback runs exactly once for every listener attached to a successfully started monitor,
    /// including when classification, event publication, the exit listener, or the monitor itself fails.
    ///
    /// @param failure monitor failure, or null after normal completion
    default void onMonitorComplete(@Nullable Throwable failure) {
    }

    /// Classified process-exit outcome.
    @NotNullByDefault
    enum ExitType {
        /// JVM-level fatal error was detected.
        JVM_ERROR,

        /// Application-level crash was detected.
        APPLICATION_ERROR,

        /// The operating system reported a kill signal or equivalent forced termination.
        SIGKILL,

        /// The process exited normally.
        NORMAL,

        /// Launcher-driven interruption stopped monitoring.
        INTERRUPTED
    }
}
