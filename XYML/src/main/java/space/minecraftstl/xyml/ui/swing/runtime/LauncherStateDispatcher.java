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
package space.minecraftstl.xyml.ui.swing.runtime;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;

/// Centralizes thread-confined access to launcher observable state during the staged Swing migration.
///
/// Production access is serialized on the Swing event dispatch thread even while some stores retain
/// their previous observable-property representation.
@NotNullByDefault
public final class LauncherStateDispatcher {
    /// Prevents utility instantiation.
    private LauncherStateDispatcher() {
    }

    /// Runs an operation on the Swing event dispatch thread without blocking the caller.
    ///
    /// Operations already on the Swing event dispatch thread run immediately. Other callers enqueue the
    /// operation and return.
    ///
    /// @param operation operation requiring launcher-state thread confinement
    public static void execute(Runnable operation) {
        Objects.requireNonNull(operation, "operation");
        if (SwingUtilities.isEventDispatchThread()) {
            operation.run();
        } else {
            SwingUtilities.invokeLater(operation);
        }
    }

    /// Runs an operation synchronously on the Swing event dispatch thread.
    ///
    /// @param operation operation requiring launcher-state thread confinement
    public static void executeAndWait(Runnable operation) {
        Objects.requireNonNull(operation, "operation");
        if (SwingUtilities.isEventDispatchThread()) {
            operation.run();
            return;
        }

        try {
            SwingUtilities.invokeAndWait(operation);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a launcher-state operation", failure);
        } catch (InvocationTargetException failure) {
            @Nullable Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Launcher-state operation failed", cause);
        }
    }

    /// Requires direct launcher property access to run on the Swing event dispatch thread.
    public static void requireEventThread() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Launcher state must be accessed on the Swing event dispatch thread");
        }
    }
}
