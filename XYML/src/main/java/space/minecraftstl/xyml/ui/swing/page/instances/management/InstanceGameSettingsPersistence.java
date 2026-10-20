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
package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskExecutor;
import space.minecraftstl.xyml.task.TaskListener;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;

/// Owns one asynchronous instance-settings save and its cancellable task lease.
@NotNullByDefault
final class InstanceGameSettingsPersistence implements AutoCloseable {
    /// Receives a terminal task state on the event-dispatch thread.
    @FunctionalInterface
    interface Completion {
        /// Handles one terminal save state.
        ///
        /// @param previous settings rendered before the save
        /// @param executor completed task executor
        /// @param successful whether the save completed successfully
        void complete(InstanceGameSettingsSnapshot previous, TaskExecutor executor, boolean successful);
    }

    /// Store owning the durable instance settings.
    private final InstanceGameSettingsStore store;

    /// Callback that applies the terminal result to the editor.
    private final Completion completion;

    /// Current task executor, or null when idle.
    private @Nullable TaskExecutor activeExecutor;

    /// Current task listener registration, or null when idle.
    private @Nullable Subscription activeSubscription;

    /// Creates a persistence controller for one settings store.
    ///
    /// @param store durable settings store
    /// @param completion terminal result callback
    InstanceGameSettingsPersistence(InstanceGameSettingsStore store, Completion completion) {
        this.store = java.util.Objects.requireNonNull(store, "store");
        this.completion = java.util.Objects.requireNonNull(completion, "completion");
    }

    /// Returns whether a save currently owns the editor.
    ///
    /// @return true while a task is active
    boolean isBusy() {
        EdtDispatcher.requireEventDispatchThread();
        return activeExecutor != null;
    }

    /// Starts one resource-aware save task.
    ///
    /// @param previous settings rendered before the save
    /// @param candidate validated settings to persist
    void start(InstanceGameSettingsSnapshot previous, InstanceGameSettingsSnapshot candidate) {
        EdtDispatcher.requireEventDispatchThread();
        if (activeExecutor != null) {
            return;
        }
        Task<@Nullable Void> task = store.saveTask(candidate, Schedulers.io());
        TaskExecutor executor = task.executor();
        activeExecutor = executor;
        activeSubscription = executor.subscribeTaskListener(new TaskListener() {
            /// Marshals completion to the event-dispatch thread.
            @Override
            public void onStop(boolean successful, TaskExecutor completedExecutor) {
                SwingUiDispatcher.INSTANCE.dispatchOrRun(
                        () -> finish(previous, completedExecutor, successful));
            }
        });
        try {
            executor.start();
        } catch (RuntimeException | Error failure) {
            clearActiveTask();
            throw failure;
        }
    }

    /// Cancels the active task and releases its listener during editor disposal.
    @Override
    public void close() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable TaskExecutor executor = activeExecutor;
        clearActiveTask();
        if (executor != null) {
            executor.cancel();
        }
    }

    /// Applies one terminal state after confirming it belongs to the current task.
    private void finish(
            InstanceGameSettingsSnapshot previous,
            TaskExecutor executor,
            boolean successful) {
        EdtDispatcher.requireEventDispatchThread();
        if (activeExecutor != executor) {
            return;
        }
        clearActiveTask();
        completion.complete(previous, executor, successful);
    }

    /// Removes the listener and clears the active task identity.
    private void clearActiveTask() {
        if (activeSubscription != null) {
            activeSubscription.unsubscribe();
            activeSubscription = null;
        }
        activeExecutor = null;
    }
}
