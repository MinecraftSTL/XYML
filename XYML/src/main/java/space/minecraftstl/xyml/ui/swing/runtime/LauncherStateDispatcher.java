// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
