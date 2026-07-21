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
package space.minecraftstl.xyml.ui.swing;

import org.jetbrains.annotations.NotNullByDefault;

import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;

/// Centralizes dispatch and thread assertions for Swing component access.
@NotNullByDefault
public final class EdtDispatcher {
    /// Prevents construction of this utility class.
    private EdtDispatcher() {
    }

    /// Runs an action immediately on the EDT or queues it when called from another thread.
    ///
    /// @param action the UI action to run
    public static void execute(Runnable action) {
        Objects.requireNonNull(action);

        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    /// Always queues an action after work already pending on the EDT.
    ///
    /// @param action the UI action to queue
    public static void executeLater(Runnable action) {
        SwingUtilities.invokeLater(Objects.requireNonNull(action));
    }

    /// Runs an action on the EDT and waits until it has completed.
    ///
    /// Runtime failures and errors from the action are rethrown on the calling thread. Interruption restores the interrupt flag and
    /// raises an {@link IllegalStateException} because returning before the UI mutation has completed would violate this method's contract.
    ///
    /// @param action the UI action to run
    public static void executeAndWait(Runnable action) {
        Objects.requireNonNull(action);

        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }

        try {
            SwingUtilities.invokeAndWait(action);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the Swing event dispatch thread", e);
        } catch (InvocationTargetException e) {
            rethrowActionFailure(Objects.requireNonNull(e.getCause(), "Invocation failure did not contain a cause"));
        }
    }

    /// Verifies that the current code is running on the EDT.
    ///
    /// @throws IllegalStateException when called from any other thread
    public static void requireEventDispatchThread() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Swing UI access must occur on the event dispatch thread");
        }
    }

    /// Rethrows a failure captured by {@link SwingUtilities#invokeAndWait(Runnable)} without losing its type.
    ///
    /// @param failure the action failure
    private static void rethrowActionFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Swing event dispatch action failed", failure);
    }
}
