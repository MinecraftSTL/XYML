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
package space.minecraftstl.xyml.ui.swing.legacy;

import javafx.application.Platform;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/// Centralizes thread-confined access to legacy JavaFX state during the staged Swing migration.
///
/// Final JavaFX removal deletes this bridge after every legacy store is replaced by toolkit-neutral state.
@NotNullByDefault
public final class LegacyJavaFxDispatcher {
    /// Prevents utility instantiation.
    private LegacyJavaFxDispatcher() {
    }

    /// Runs an operation on the JavaFX application thread without blocking the caller.
    ///
    /// Operations already on the JavaFX application thread run immediately. Other callers enqueue the
    /// operation and return, which prevents a Swing EDT caller from entering a cross-toolkit wait cycle.
    ///
    /// @param operation operation requiring JavaFX thread confinement
    public static void execute(Runnable operation) {
        Objects.requireNonNull(operation, "operation");
        if (Platform.isFxApplicationThread()) {
            operation.run();
        } else {
            Platform.runLater(operation);
        }
    }

    /// Runs an operation synchronously on the JavaFX application thread.
    ///
    /// @param operation operation requiring JavaFX thread confinement
    public static void executeAndWait(Runnable operation) {
        Objects.requireNonNull(operation, "operation");
        if (Platform.isFxApplicationThread()) {
            operation.run();
            return;
        }

        FutureTask<Void> task = new FutureTask<>(operation, null);
        Platform.runLater(task);
        try {
            task.get();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a JavaFX bridge operation", failure);
        } catch (ExecutionException failure) {
            @Nullable Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("JavaFX bridge operation failed", cause);
        }
    }

    /// Requires direct legacy property access to run on the JavaFX application thread.
    public static void requireEventThread() {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("Legacy JavaFX state must be accessed on its application thread");
        }
    }
}
