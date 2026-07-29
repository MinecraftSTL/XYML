/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.task;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.event.EventManager;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.property.ReadOnlyProperty;
import space.minecraftstl.xyml.util.Result;
import space.minecraftstl.xyml.util.function.ExceptionalConsumer;
import space.minecraftstl.xyml.util.function.ExceptionalFunction;
import space.minecraftstl.xyml.util.function.ExceptionalRunnable;
import space.minecraftstl.xyml.util.function.ExceptionalSupplier;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.*;
import java.util.stream.Stream;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Disposable task with toolkit-neutral lifecycle and progress observation.
@NotNullByDefault
public abstract class Task<T> {

    /// The importance level that controls this task's logging and UI visibility.
    private TaskSignificance significance = TaskSignificance.MAJOR;

    /// Returns this task's current importance level.
    public final TaskSignificance getSignificance() {
        return significance;
    }

    /// Sets this task's importance level and returns this task.
    public final Task<T> setSignificance(TaskSignificance significance) {
        this.significance = significance;
        return this;
    }

    // cancel
    /// Supplies external cancellation state after an executor attaches, or null before attachment.
    private @Nullable BooleanSupplier cancelled;

    /// Attaches the cancellation state owned by the executor.
    final void setCancelled(BooleanSupplier cancelled) {
        this.cancelled = cancelled;
    }

    /// Returns whether this task's thread or attached executor has been cancelled.
    protected final boolean isCancelled() {
        if (Thread.interrupted()) {
            Thread.currentThread().interrupt();
            return true;
        }

        return cancelled != null && cancelled.getAsBoolean();
    }

    // stage
    /// The optional grouping stage assigned directly to this task.
    private @Nullable String stage;

    /// Returns this task's grouping stage, or null when it should inherit one from its parent.
    public @Nullable String getStage() {
        return stage;
    }

    /// Sets this task's grouping stage, or clears it so the executor can inherit a parent stage.
    protected final void setStage(@Nullable String stage) {
        this.stage = stage;
    }

    /// The resolved parent stage, or null when no ancestor supplies one.
    private @Nullable String inheritedStage;

    /// Returns the resolved parent stage, or null when none exists.
    public @Nullable String getInheritedStage() {
        return inheritedStage;
    }

    /// Stores the resolved parent stage, including null when no ancestor supplies one.
    void setInheritedStage(@Nullable String inheritedStage) {
        this.inheritedStage = inheritedStage;
    }

    // properties
    /// Lazily created mutable task metadata, or null before first access.
    @Nullable Map<String, Object> properties;

    /// Returns the mutable task metadata map, creating it on first access.
    public Map<String, Object> getProperties() {
        if (properties == null) properties = new HashMap<>();
        return properties;
    }

    /// The executor notification callback, or null before this task is attached.
    private @Nullable Runnable notifyPropertiesChanged;

    /// Attaches the executor callback invoked after task metadata changes.
    void setNotifyPropertiesChanged(Runnable runnable) {
        this.notifyPropertiesChanged = runnable;
    }

    /// Notifies the attached executor that task metadata changed, when one is attached.
    protected void notifyPropertiesChanged() {
        if (notifyPropertiesChanged != null) {
            notifyPropertiesChanged.run();
        }
    }

    // state
    /// The current executor lifecycle state.
    private TaskState state = TaskState.READY;

    /// Returns the current executor lifecycle state.
    public final TaskState getState() {
        return state;
    }

    /// Replaces the executor lifecycle state.
    final void setState(TaskState state) {
        this.state = state;
    }

    // last exception
    /// The last execution failure, or null after success and cancellation without an error.
    private @Nullable Exception exception;

    /// Returns the failure from this task, a dependent, or a dependency; cancellation may leave it null.
    public final @Nullable Exception getException() {
        return exception;
    }

    /// Stores the latest execution failure, or clears it for successful or cancelled completion.
    final void setException(@Nullable Exception e) {
        exception = e;
    }

    /// The executor used for asynchronous continuations created from this task.
    private Executor executor = Schedulers.defaultScheduler();

    /// Returns the executor configured for this task.
    public final Executor getExecutor() {
        return executor;
    }

    /// Sets this task's executor and returns this task.
    public final Task<T> setExecutor(Executor executor) {
        this.executor = executor;
        return this;
    }

    // dependents succeeded
    /// Whether all prerequisite tasks completed successfully.
    private boolean dependentsSucceeded = false;

    /// Returns whether all prerequisite tasks completed successfully.
    public boolean isDependentsSucceeded() {
        return dependentsSucceeded;
    }

    /// Marks all prerequisite tasks as successfully completed.
    void setDependentsSucceeded() {
        dependentsSucceeded = true;
    }

    // dependencies succeeded
    /// Whether all follow-up tasks completed successfully.
    private boolean dependenciesSucceeded = false;

    /// Returns whether all follow-up tasks completed successfully.
    public boolean isDependenciesSucceeded() {
        return dependenciesSucceeded;
    }

    /// Marks all follow-up tasks as successfully completed.
    void setDependenciesSucceeded() {
        dependenciesSucceeded = true;
    }

    /// Returns whether prerequisite failure prevents this task from executing.
    ///
    /// Tasks returning false are intended for executor-managed recovery flows rather than direct [#run()] calls.
    public boolean isRelyingOnDependents() {
        return true;
    }

    /// Returns whether follow-up failure makes this task chain fail.
    ///
    /// Tasks returning false are intended for executor-managed recovery flows rather than direct [#run()] calls.
    public boolean isRelyingOnDependencies() {
        return true;
    }

    // name
    /// The explicit display name, or null while the class name is used as the fallback.
    private @Nullable String name;

    /// Returns the explicit display name or this task's class name when none was assigned.
    public String getName() {
        return name != null ? name : getClass().getName();
    }

    /// Sets the non-null display name and returns this task.
    public Task<T> setName(String name) {
        this.name = name;
        return this;
    }

    /// Returns the class name alone for an implicit name, or the class and explicit display name together.
    @Override
    public String toString() {
        if (getClass().getName().equals(getName()))
            return getName();
        else
            return getClass().getName() + "[" + getName() + "]";
    }

    // result
    /// The task result, or null before production and for operations without a result.
    private @Nullable T result;

    /// The result synchronization callback, or null until [#storeTo(Consumer)] is called.
    private @Nullable Consumer<@Nullable T> resultConsumer;

    /// Returns the result after completion, or null before production and for result-less tasks.
    public @Nullable T getResult() {
        return result;
    }

    /// Stores a possibly absent result and forwards it to the attached result consumer.
    protected void setResult(@Nullable T result) {
        this.result = result;
        if (resultConsumer != null)
            resultConsumer.accept(result);
    }

    /// Attaches a result consumer, immediately synchronizes the current possibly absent result, and returns this task.
    public Task<T> storeTo(Consumer<@Nullable T> action) {
        this.resultConsumer = action;
        action.accept(getResult());
        return this;
    }

    // execution
    /// Returns whether the executor should invoke [#preExecute()] for this task.
    public boolean doPreExecute() {
        return false;
    }

    /// Performs optional work before prerequisite execution when [#doPreExecute()] returns true.
    public void preExecute() throws Exception {
    }

    /// Performs this task's primary operation and may store its result.
    public abstract void execute() throws Exception;

    /// Returns whether the executor should invoke [#postExecute()] after follow-up tasks terminate.
    public boolean doPostExecute() {
        return false;
    }

    /// Performs optional cleanup after all follow-up tasks terminate when [#doPostExecute()] returns true.
    ///
    /// Implementations can inspect [#isDependenciesSucceeded()] to distinguish successful and failed follow-ups.
    public void postExecute() throws Exception {
    }

    /// Returns prerequisite tasks that execute before this task's primary operation.
    ///
    /// The default implementation returns an independent mutable empty collection; modifying it does not change this
    /// task. Subclasses may expose their own mutable or immutable dependency collection contract.
    public Collection<? extends Task<?>> getDependents() {
        return new ArrayList<>();
    }

    /// Returns follow-up tasks that execute only after this task's primary operation succeeds.
    ///
    /// The default implementation returns an independent mutable empty collection; modifying it does not change this
    /// task. Subclasses may expose their own mutable or immutable dependency collection contract.
    public Collection<? extends Task<?>> getDependencies() {
        return new ArrayList<>();
    }

    /// The lazily created completion event manager, or null before its first subscription request.
    private volatile @Nullable EventManager<TaskEvent> onDone;

    /// Returns the completion event manager, creating it once when first requested.
    public EventManager<TaskEvent> onDone() {
        @Nullable EventManager<TaskEvent> onDone = this.onDone;
        if (onDone == null) {
            synchronized (this) {
                onDone = this.onDone;
                if (onDone == null) {
                    this.onDone = onDone = new EventManager<>();
                }
            }
        }

        return onDone;
    }

    /// Publishes a completion event when the event manager has already been requested.
    void fireDoneEvent(Object source, boolean failed) {
        @Nullable EventManager<TaskEvent> onDone = this.onDone;
        if (onDone != null)
            onDone.fireEvent(new TaskEvent(source, this, failed));
    }

    /// Progress source updated on the publishing worker thread.
    private final TaskProgressProperty observableProgress = new TaskProgressProperty(this, "progress", -1.0);

    /// Returns the read-only progress property.
    ///
    /// The initial value is `-1.0`, which means that the task has not reported a quantifiable progress value yet.
    /// Changes are delivered synchronously on the thread that calls [#updateProgressImmediately(double)].
    public final ReadOnlyProperty<Double> progressObservable() {
        return observableProgress;
    }

    /// Limits non-terminal progress notifications to one update per second.
    private long lastUpdateProgressTime = 0L;

    /// Updates progress from a completed-count and total-count pair.
    protected void updateProgress(long count, long total) {
        if (count < 0 || total < 0)
            throw new IllegalArgumentException("Invalid count or total: count=" + count + ", total=" + total);

        updateProgress(count < total ? (double) count / total : 1.0);
    }

    /// Updates progress, coalescing non-terminal updates that arrive within one second.
    protected void updateProgress(double progress) {
        if (progress < 0 || progress > 1.0 || Double.isNaN(progress))
            throw new IllegalArgumentException("Invalid progress: " + progress);

        long now = System.currentTimeMillis();
        if (progress == 1.0 || now - lastUpdateProgressTime >= 1000L) {
            updateProgressImmediately(progress);
            lastUpdateProgressTime = now;
        }
    }

    /// Publishes an immediate progress value synchronously to isolated listeners.
    protected void updateProgressImmediately(double progress) {
        // assert progress >= 0 && progress <= 1.0;
        publishObservableProgress(progress);
    }

    /// Publishes a neutral progress value to independently isolated listeners.
    private void publishObservableProgress(double progress) {
        observableProgress.publish(progress);
    }

    /// Runs dependents, this task body, and dependencies synchronously, then returns the possibly absent result.
    public final @Nullable T run() throws Exception {
        if (getSignificance().shouldLog())
            LOG.trace("Executing task: " + getName());

        for (Task<?> task : getDependents())
            doSubTask(task);
        execute();
        for (Task<?> task : getDependencies())
            doSubTask(task);
        fireDoneEvent(this, false);

        return getResult();
    }

    /// Runs one subtask while mirroring its latest progress into this task.
    private void doSubTask(Task<?> task) throws Exception {
        @Nullable Double initialProgress = task.progressObservable().getValue();
        if (initialProgress != null) {
            publishObservableProgress(initialProgress);
        }
        Subscription neutralProgressSubscription = task.progressObservable().subscribe(ignoredChange -> {
            @Nullable Double currentProgress = task.progressObservable().getValue();
            if (currentProgress != null) {
                publishObservableProgress(currentProgress);
            }
        });
        try {
            task.run();
        } finally {
            neutralProgressSubscription.unsubscribe();
        }
    }

    /// Creates a stopped asynchronous executor rooted at this task.
    public final TaskExecutor executor() {
        return new AsyncTaskExecutor(this);
    }

    /// Creates an asynchronous executor rooted at this task and optionally starts it immediately.
    public final TaskExecutor executor(boolean start) {
        TaskExecutor executor = new AsyncTaskExecutor(this);
        if (start)
            executor.start();
        return executor;
    }

    /// Creates a stopped asynchronous executor and attaches the supplied task listener.
    public final TaskExecutor executor(TaskListener taskListener) {
        TaskExecutor executor = new AsyncTaskExecutor(this);
        executor.subscribeTaskListener(taskListener);
        return executor;
    }

    /// Starts this task with a newly created asynchronous executor.
    public final void start() {
        executor().start();
    }

    /// Executes this task through a new executor's synchronous test path and returns its success state.
    public final boolean test() {
        return executor().test();
    }

    /// Creates a moderate continuation that transforms this task's result on the default scheduler.
    public <U, E extends Exception> Task<U> thenApplyAsync(
            ExceptionalFunction<@Nullable T, @Nullable U, E> fn) {
        return thenApplyAsync(Schedulers.defaultScheduler(), fn);
    }

    /// Creates a moderate continuation that transforms this task's result on the supplied executor.
    public <U, E extends Exception> Task<U> thenApplyAsync(
            Executor executor, ExceptionalFunction<@Nullable T, @Nullable U, E> fn) {
        return thenApplyAsync(getCaller(), executor, fn).setSignificance(TaskSignificance.MODERATE);
    }

    /// Creates a named continuation that transforms this task's result on the supplied executor.
    public <U, E extends Exception> Task<U> thenApplyAsync(
            String name, Executor executor, ExceptionalFunction<@Nullable T, @Nullable U, E> fn) {
        return new UniApply<>(fn).setExecutor(executor).setName(name);
    }

    /// Creates a result-consuming continuation on the default scheduler.
    public <E extends Exception> Task<@Nullable Void> thenAcceptAsync(ExceptionalConsumer<@Nullable T, E> action) {
        return thenAcceptAsync(Schedulers.defaultScheduler(), action);
    }

    /// Creates a result-consuming continuation on the supplied executor.
    public <E extends Exception> Task<@Nullable Void> thenAcceptAsync(
            Executor executor, ExceptionalConsumer<@Nullable T, E> action) {
        return thenAcceptAsync(getCaller(), executor, action).setSignificance(TaskSignificance.MODERATE);
    }

    /// Creates a named result-consuming continuation on the supplied executor.
    public <E extends Exception> Task<@Nullable Void> thenAcceptAsync(
            String name, Executor executor, ExceptionalConsumer<@Nullable T, E> action) {
        return thenApplyAsync(name, executor, result -> {
            action.accept(result);
            return null;
        });
    }

    /// Creates a result-independent continuation on the default scheduler.
    public <E extends Exception> Task<@Nullable Void> thenRunAsync(ExceptionalRunnable<E> action) {
        return thenRunAsync(Schedulers.defaultScheduler(), action);
    }

    /// Creates a result-independent continuation on the supplied executor.
    public <E extends Exception> Task<@Nullable Void> thenRunAsync(Executor executor, ExceptionalRunnable<E> action) {
        return thenRunAsync(getCaller(), executor, action).setSignificance(TaskSignificance.MODERATE);
    }

    /// Creates a named result-independent continuation on the supplied executor.
    public <E extends Exception> Task<@Nullable Void> thenRunAsync(
            String name, Executor executor, ExceptionalRunnable<E> action) {
        return thenApplyAsync(name, executor, ignore -> {
            action.run();
            return null;
        });
    }

    /// Creates a continuation that obtains a new result on the default scheduler.
    public final <U> Task<U> thenSupplyAsync(Callable<@Nullable U> fn) {
        return thenComposeAsync(() -> Task.supplyAsync(fn));
    }

    /// Creates a named continuation that obtains a new result on the default scheduler.
    public final <U> Task<U> thenSupplyAsync(String name, Callable<@Nullable U> fn) {
        return thenComposeAsync(() -> Task.supplyAsync(name, fn));
    }

    /// Chains the supplied task after this task and stops the chain when this task fails.
    public final <U> Task<U> thenComposeAsync(Task<U> other) {
        return thenComposeAsync(() -> other);
    }

    /// Supplies an optional successor after this task on the default scheduler.
    public final <U> Task<U> thenComposeAsync(ExceptionalSupplier<@Nullable Task<U>, ?> fn) {
        return thenComposeAsync(Schedulers.defaultScheduler(), fn);
    }

    /// Supplies an optional successor after this task on the supplied executor.
    public final <U> Task<U> thenComposeAsync(
            Executor executor, ExceptionalSupplier<@Nullable Task<U>, ?> fn) {
        return new UniCompose<>(fn, true).setExecutor(executor);
    }

    /// Maps this task's result to an optional successor on the default scheduler.
    public <U, E extends Exception> Task<U> thenComposeAsync(
            ExceptionalFunction<@Nullable T, @Nullable Task<U>, E> fn) {
        return thenComposeAsync(Schedulers.defaultScheduler(), fn);
    }

    /// Maps this task's result to an optional successor on the supplied executor.
    public <U, E extends Exception> Task<U> thenComposeAsync(
            Executor executor, ExceptionalFunction<@Nullable T, @Nullable Task<U>, E> fn) {
        return new UniCompose<>(fn, true).setExecutor(executor);
    }

    /// Chains the supplied task after this task while allowing the chain to continue after predecessor failure.
    public final <U> Task<U> withComposeAsync(Task<U> other) {
        return withComposeAsync(() -> other);
    }

    /// Supplies an optional successor while allowing the chain to continue after predecessor failure.
    public final <U, E extends Exception> Task<U> withComposeAsync(
            ExceptionalSupplier<@Nullable Task<U>, E> fn) {
        return new UniCompose<>(fn, false);
    }

    /// Creates a result-independent continuation that may run after predecessor failure on the default scheduler.
    public <E extends Exception> Task<@Nullable Void> withRunAsync(ExceptionalRunnable<E> action) {
        return withRunAsync(Schedulers.defaultScheduler(), action);
    }

    /// Creates a result-independent continuation that may run after predecessor failure on the supplied executor.
    public <E extends Exception> Task<@Nullable Void> withRunAsync(Executor executor, ExceptionalRunnable<E> action) {
        return withRunAsync(getCaller(), executor, action).setSignificance(TaskSignificance.MODERATE);
    }

    /// Creates a named continuation that may run after predecessor failure on the supplied executor.
    public <E extends Exception> Task<@Nullable Void> withRunAsync(
            String name, Executor executor, ExceptionalRunnable<E> action) {
        return new UniCompose<>(() -> Task.runAsync(name, executor, action), false);
    }

    /// Creates a completion continuation on the default scheduler that receives this task's optional failure.
    public final Task<@Nullable Void> whenComplete(FinalizedCallback action) {
        return whenComplete(Schedulers.defaultScheduler(), action);
    }

    /// Creates a completion continuation on the supplied executor that receives this task's optional failure.
    public final Task<@Nullable Void> whenComplete(Executor executor, FinalizedCallback action) {
        return new Task<@Nullable Void>() {
            {
                setSignificance(TaskSignificance.MODERATE);
            }

            /// Invokes the completion callback and rethrows the predecessor failure after callback delivery.
            @Override
            public void execute() throws Exception {
                if (isDependentsSucceeded() != (Task.this.getException() == null))
                    throw new AssertionError("When whenComplete succeeded, Task.exception must be null.", Task.this.getException());

                action.execute(Task.this.getException());

                if (!isDependentsSucceeded()) {
                    setSignificance(TaskSignificance.MINOR);
                    if (Task.this.getException() == null)
                        throw new AssertionError("When failed, exception cannot be null");
                    else
                        throw Task.this.getException();
                }
            }

            /// Returns the outer task as this continuation's prerequisite.
            @Override
            public @Unmodifiable Collection<Task<?>> getDependents() {
                return Collections.singleton(Task.this);
            }

            /// Allows this continuation to inspect and report a failed prerequisite.
            @Override
            public boolean isRelyingOnDependents() {
                return false;
            }
        }.setExecutor(executor).setName(getCaller()).setSignificance(TaskSignificance.MODERATE);
    }

    /// Creates a completion continuation that receives this task's possibly absent result and failure.
    public Task<@Nullable Void> whenComplete(Executor executor, FinalizedCallbackWithResult<T> action) {
        return whenComplete(executor, (exception -> action.execute(getResult(), exception)));
    }

    /// Wraps successful results and failures in a [Result] while preventing prerequisite failure from stopping it.
    public Task<Result<@Nullable T>> wrapResult() {
        return new Task<Result<@Nullable T>>() {
            {
                setSignificance(TaskSignificance.MODERATE);
            }

            /// Converts the outer task's terminal state to a success or failure [Result].
            @Override
            public void execute() throws Exception {
                if (isDependentsSucceeded() != (Task.this.getException() == null))
                    throw new AssertionError("When whenComplete succeeded, Task.exception must be null.", Task.this.getException());

                if (isDependentsSucceeded()) {
                    setResult(Result.success(Task.this.getResult()));
                } else {
                    setSignificance(TaskSignificance.MINOR);
                    setResult(Result.failure(Task.this.getException()));
                }
            }

            /// Returns the outer task as the result wrapper's prerequisite.
            @Override
            public @Unmodifiable Collection<Task<?>> getDependents() {
                return Collections.singleton(Task.this);
            }

            /// Allows result wrapping to execute after a failed prerequisite.
            @Override
            public boolean isRelyingOnDependents() {
                return false;
            }
        }.setExecutor(executor).setName(getCaller()).setSignificance(TaskSignificance.MODERATE);
    }

    /// Creates a completion continuation that routes success and failure to separate optional actions.
    public final <E1 extends Exception, E2 extends Exception> Task<@Nullable Void> whenComplete(
            Executor executor, @Nullable ExceptionalRunnable<E1> success,
            @Nullable ExceptionalConsumer<Exception, E2> failure) {
        return whenComplete(executor, exception -> {
            if (exception == null) {
                if (success != null)
                    try {
                        success.run();
                    } catch (Exception e) {
                        LOG.warning("Failed to execute " + success, e);
                        if (failure != null)
                            failure.accept(e);
                    }
            } else {
                if (failure != null)
                    failure.accept(exception);
            }
        });
    }

    /// Creates a completion continuation that passes the result on success and the exception on failure.
    public <E1 extends Exception, E2 extends Exception> Task<@Nullable Void> whenComplete(
            Executor executor, ExceptionalConsumer<@Nullable T, E1> success,
            @Nullable ExceptionalConsumer<Exception, E2> failure) {
        return whenComplete(executor, () -> success.accept(getResult()), failure);
    }

    /// Wraps this task with an explicit grouping stage.
    public Task<T> withStage(String stage) {
        return new StageTask(stage);
    }

    /// Wraps this task with named asymptotic progress controlled by the supplied completion signal and curve factor.
    public Task<T> withFakeProgress(String name, BooleanSupplier done, double k) {
        return new FakeProgressTask(done, k).setExecutor(Schedulers.defaultScheduler()).setName(name).setSignificance(TaskSignificance.MAJOR);
    }

    /// Describes a primary stage and any aliases that should be presented with it.
    @NotNullByDefault
    public record StagesHint(String stage, @Unmodifiable List<String> aliases) {
        /// Defensively copies aliases so the record remains immutable.
        public StagesHint {
            aliases = List.copyOf(aliases);
        }

        /// Creates a stage hint without aliases.
        public StagesHint(String stage) {
            this(stage, List.of());
        }
    }

    /// Wraps this task with immutable stage hints parsed from the supplied names.
    public Task<T> withStagesHints(String... hints) {
        return withStagesHints(Arrays.stream(hints).map(StagesHint::new).toList());
    }

    /// Wraps this task with immutable stage hints supplied as varargs.
    public Task<T> withStagesHints(StagesHint... hints) {
        return new StagesHintTask(List.of(hints));
    }

    /// Wraps this task with an immutable defensive copy of the supplied stage hints.
    public Task<T> withStagesHints(List<StagesHint> hints) {
        return new StagesHintTask(hints);
    }

    /// Wraps this task with alternate stage hints for task-list presentation.
    @NotNullByDefault
    public class StagesHintTask extends Task<T> {
        /// Immutable stage metadata exposed to the task executor.
        private final @Unmodifiable List<StagesHint> hints;

        /// Creates a wrapper and defensively copies its stage metadata.
        public StagesHintTask(List<StagesHint> hints) {
            this.hints = List.copyOf(hints);
        }

        /// Returns the outer task as this wrapper's sole prerequisite.
        @Override
        public @Unmodifiable Collection<Task<?>> getDependents() {
            return Collections.singleton(Task.this);
        }

        /// Copies the outer task's possibly absent result into this wrapper.
        @Override
        public void execute() {
            setResult(Task.this.getResult());
        }

        /// Returns immutable stage metadata.
        public @Unmodifiable List<StagesHint> getHints() {
            return hints;
        }
    }

    /// Wraps this task with a count-presentation stage.
    public Task<T> withCounter(String countStage) {
        return new CountTask(countStage);
    }

    /// Creates a result-less task for the supplied action on the default scheduler.
    public static Task<@Nullable Void> runAsync(ExceptionalRunnable<?> closure) {
        return runAsync(Schedulers.defaultScheduler(), closure);
    }

    /// Creates a named result-less task for the supplied action on the default scheduler.
    public static Task<@Nullable Void> runAsync(String name, ExceptionalRunnable<?> closure) {
        return runAsync(name, Schedulers.defaultScheduler(), closure);
    }

    /// Creates a moderate result-less task for the supplied action and executor.
    public static Task<@Nullable Void> runAsync(Executor executor, ExceptionalRunnable<?> closure) {
        return runAsync(getCaller(), executor, closure).setSignificance(TaskSignificance.MODERATE);
    }

    /// Creates a named result-less task for the supplied action and executor.
    public static Task<@Nullable Void> runAsync(String name, Executor executor, ExceptionalRunnable<?> closure) {
        return new SimpleTask<>(closure.toCallable()).setExecutor(executor).setName(name);
    }

    /// Creates a moderate dynamically composed task on the default scheduler.
    public static <T> Task<T> composeAsync(ExceptionalSupplier<@Nullable Task<T>, ?> fn) {
        return composeAsync(getCaller(), fn).setSignificance(TaskSignificance.MODERATE);
    }

    /// Creates a named dynamically composed task on the default scheduler.
    public static <T> Task<T> composeAsync(String name, ExceptionalSupplier<@Nullable Task<T>, ?> fn) {
        return new Task<T>() {
            /// The dynamically supplied task, or null when the composition intentionally has no dependency.
            private @Nullable Task<T> then;

            /// Obtains the optional follow-up task and mirrors its result when present.
            @Override
            public void execute() throws Exception {
                then = fn.get();
                if (then != null)
                    then.storeTo(this::setResult);
            }

            /// Returns the dynamically supplied task as the sole follow-up, or an empty immutable collection.
            @Override
            public @Unmodifiable Collection<Task<?>> getDependencies() {
                return then == null ? Collections.emptySet() : Collections.singleton(then);
            }
        }.setName(name);
    }

    /// Creates a dynamically composed task on the supplied executor.
    public static <T> Task<T> composeAsync(
            Executor executor, ExceptionalSupplier<@Nullable Task<T>, ?> fn) {
        return composeAsync(fn).setExecutor(executor);
    }

    /// Creates a moderate value-producing task on the default scheduler.
    public static <V> Task<V> supplyAsync(Callable<@Nullable V> callable) {
        return supplyAsync(getCaller(), callable).setSignificance(TaskSignificance.MODERATE);
    }

    /// Creates a moderate value-producing task on the supplied executor.
    public static <V> Task<V> supplyAsync(Executor executor, Callable<@Nullable V> callable) {
        return supplyAsync(getCaller(), executor, callable).setSignificance(TaskSignificance.MODERATE);
    }

    /// Creates a named value-producing task on the default scheduler.
    public static <V> Task<V> supplyAsync(String name, Callable<@Nullable V> callable) {
        return supplyAsync(name, Schedulers.defaultScheduler(), callable);
    }

    /// Creates a named value-producing task on the supplied executor.
    public static <V> Task<V> supplyAsync(String name, Executor executor, Callable<@Nullable V> callable) {
        return new SimpleTask<>(callable).setExecutor(executor).setName(name);
    }

    /// Creates a task backed by an already completed future containing the supplied possibly absent value.
    public static <V> Task<V> completed(@Nullable V value) {
        return fromCompletableFuture(CompletableFuture.completedFuture(value));
    }

    /// Creates a minor task that waits for every supplied prerequisite and returns their results in immutable order.
    @SafeVarargs
    public static <T> Task<@Unmodifiable List<@Nullable T>> allOf(Task<? extends T>... tasks) {
        return allOf(Arrays.asList(tasks));
    }

    /// Creates a minor task from an immutable snapshot of prerequisites and returns their results in immutable order.
    public static <T> Task<@Unmodifiable List<@Nullable T>> allOf(
            Collection<? extends Task<? extends T>> tasks) {
        @Unmodifiable List<Task<? extends T>> taskSnapshot = List.copyOf(tasks);
        return new Task<@Unmodifiable List<@Nullable T>>() {
            {
                setSignificance(TaskSignificance.MINOR);
            }

            /// Collects prerequisite results into an unmodifiable list after every prerequisite succeeds.
            @Override
            public void execute() {
                setResult(taskSnapshot.stream().<@Nullable T>map(Task::getResult).toList());
            }

            /// Returns the immutable prerequisite snapshot.
            @Override
            public @Unmodifiable Collection<? extends Task<?>> getDependents() {
                return taskSnapshot;
            }
        };
    }

    /// Chains the supplied tasks in order and returns the final task, or a result-less task for an empty input.
    public static Task<?> runSequentially(Task<?>... tasks) {
        if (tasks.length == 0) {
            return new SimpleTask<>(() -> null);
        }

        Task<?> task = tasks[0];
        for (int i = 1; i < tasks.length; i++) {
            task = task.thenComposeAsync(tasks[i]);
        }
        return task;
    }

    /// Adapts a completable future with a possibly absent value to a task.
    public static <T> Task<T> fromCompletableFuture(CompletableFuture<@Nullable T> future) {
        return new CompletableFutureTask<T>() {
            /// Returns the adapted future unchanged.
            @Override
            public CompletableFuture<@Nullable T> getFuture(TaskCompletableFuture executor) {
                return future;
            }
        };
    }

    /// Controls task logging and visibility significance.
    @NotNullByDefault
    public enum TaskSignificance {
        /// User-visible task that is logged and shown in progress presentation.
        MAJOR,

        /// Logged continuation that is omitted from major task presentation.
        MODERATE,

        /// Internal task that is neither logged nor shown.
        MINOR;

        /// Returns whether tasks with this significance should produce trace logging.
        public boolean shouldLog() {
            return this != MINOR;
        }

        /// Returns whether tasks with this significance should appear as major progress work.
        public boolean shouldShow() {
            return this == MAJOR;
        }
    }

    /// Identifies the executor lifecycle state of a task.
    @NotNullByDefault
    public enum TaskState {
        /// The task has not started.
        READY,

        /// The task is currently executing.
        RUNNING,

        /// The task body completed before final dependency processing.
        EXECUTED,

        /// The task and its required subtask chain succeeded.
        SUCCEEDED,

        /// The task or a required subtask failed.
        FAILED
    }

    /// Handles task finalization with an optional failure.
    @FunctionalInterface
    @NotNullByDefault
    public interface FinalizedCallback {
        /// Handles task finalization with the failure, or null after success.
        void execute(@Nullable Exception exception) throws Exception;
    }

    /// Handles task finalization with a possibly absent result and failure.
    @FunctionalInterface
    @NotNullByDefault
    public interface FinalizedCallbackWithResult<T> {
        /// Handles task finalization with its possibly absent result and failure.
        void execute(@Nullable T result, @Nullable Exception exception) throws Exception;
    }

    /// Package prefix excluded when deriving a task name from the external caller.
    private static final String PACKAGE_PREFIX = Task.class.getPackageName() + ".";

    /// Selects stack frames outside the task implementation package.
    private static final Predicate<StackWalker.StackFrame> PREDICATE =
            stackFrame -> !stackFrame.getClassName().startsWith(PACKAGE_PREFIX);

    /// Finds the first external stack frame from a stack walk.
    private static final Function<Stream<StackWalker.StackFrame>, Optional<StackWalker.StackFrame>> FUNCTION =
            stream -> stream.filter(PREDICATE).findFirst();

    /// Formats one external stack frame as a stable default task name.
    private static final Function<StackWalker.StackFrame, String> FRAME_MAPPING = frame -> {
        @Nullable String fileName = frame.getFileName();
        if (fileName != null)
            return frame.getClassName() + '.' + frame.getMethodName() + '(' + fileName + ':' + frame.getLineNumber() + ')';
        else
            return frame.getClassName() + '.' + frame.getMethodName();
    };

    /// Returns the first external caller description, or `Unknown` when no external frame exists.
    private static String getCaller() {
        return StackWalker.getInstance().walk(FUNCTION).map(FRAME_MAPPING).orElse("Unknown");
    }

    /// Executes one callable and stores its possibly absent result.
    @NotNullByDefault
    private static final class SimpleTask<T> extends Task<T> {

        /// The operation whose nullable return value becomes this task's result.
        private final Callable<@Nullable T> callable;

        /// Creates a task backed by one operation.
        SimpleTask(Callable<@Nullable T> callable) {
            this.callable = callable;
        }

        /// Invokes the callable and stores its possibly absent return value.
        @Override
        public void execute() throws Exception {
            setResult(callable.call());
        }
    }

    /// Applies one transformation after the outer task completes.
    @NotNullByDefault
    private class UniApply<R> extends Task<R> {
        /// The transformation applied to this outer task's possibly absent result.
        private final ExceptionalFunction<@Nullable T, @Nullable R, ?> callable;

        /// Creates a dependent transformation task.
        UniApply(ExceptionalFunction<@Nullable T, @Nullable R, ?> callable) {
            this.callable = callable;
        }

        /// Returns the outer task as this transformation's sole prerequisite.
        @Override
        public @Unmodifiable Collection<Task<?>> getDependents() {
            return Collections.singleton(Task.this);
        }

        /// Applies the transformation to the outer task's possibly absent result.
        @Override
        public void execute() throws Exception {
            setResult(callable.apply(Task.this.getResult()));
        }
    }

    /// Combines the outer predecessor with a dynamically supplied successor task.
    @NotNullByDefault
    private final class UniCompose<U> extends Task<U> {

        /// Whether predecessor failure prevents this composition from continuing.
        private final boolean relyingOnDependents;

        /// The dynamically supplied successor, or null when the composition has no follow-up task.
        private @Nullable Task<U> succ;

        /// Produces the optional successor from the predecessor's possibly absent result.
        private final ExceptionalFunction<@Nullable T, @Nullable Task<U>, ?> fn;

        /// Creates a composition from a supplier and configures whether predecessor failure stops the chain.
        UniCompose(ExceptionalSupplier<@Nullable Task<U>, ?> fn, boolean relyingOnDependents) {
            this(result -> fn.get(), relyingOnDependents);
        }

        /// Creates a composition from a result function and configures whether predecessor failure stops the chain.
        UniCompose(ExceptionalFunction<@Nullable T, @Nullable Task<U>, ?> fn, boolean relyingOnDependents) {
            this.fn = fn;
            this.relyingOnDependents = relyingOnDependents;

            setSignificance(TaskSignificance.MODERATE);
            setName(fn.toString());
        }

        /// Supplies the optional successor and mirrors its result when present.
        @Override
        public void execute() throws Exception {
            setName(fn.toString());
            succ = fn.apply(Task.this.getResult());
            if (succ != null)
                succ.storeTo(this::setResult);
        }

        /// Returns the outer task as this composition's sole prerequisite.
        @Override
        public @Unmodifiable Collection<Task<?>> getDependents() {
            return Collections.singleton(Task.this);
        }

        /// Returns the supplied successor as a follow-up, or an empty immutable collection.
        @Override
        public @Unmodifiable Collection<Task<?>> getDependencies() {
            return succ == null ? Collections.emptySet() : Collections.singleton(succ);
        }

        /// Returns whether outer-task failure prevents this composition from running.
        @Override
        public boolean isRelyingOnDependents() {
            return relyingOnDependents;
        }
    }

    /// Wraps this task while assigning an explicit grouping stage.
    @NotNullByDefault
    private final class StageTask extends Task<T> {
        /// Creates a stage wrapper with the supplied non-null stage.
        private StageTask(String stage) {
            this.setStage(stage);
        }

        /// Returns the outer task as this wrapper's sole prerequisite.
        @Override
        public @Unmodifiable Collection<Task<?>> getDependents() {
            return Collections.singleton(Task.this);
        }

        /// Copies the outer task's possibly absent result into this wrapper.
        @Override
        public void execute() {
            setResult(Task.this.getResult());
        }
    }

    /// Reports asymptotic synthetic progress while waiting for the outer task's completion signal.
    @NotNullByDefault
    private final class FakeProgressTask extends Task<T> {
        /// Upper bound reserved until the completion signal becomes true.
        private static final double MAX_VALUE = 0.98D;

        /// Reports whether the wrapped operation has finished.
        private final BooleanSupplier done;

        /// Controls the asymptotic progress curve.
        private final double k;

        /// Creates a synthetic-progress wrapper.
        private FakeProgressTask(BooleanSupplier done, double k) {
            this.done = done;
            this.k = k;
        }

        /// Returns the outer task as this wrapper's sole prerequisite.
        @Override
        public @Unmodifiable Collection<Task<?>> getDependents() {
            return Collections.singleton(Task.this);
        }

        /// Publishes asymptotic progress until completion, then reports terminal progress and mirrors the result.
        @Override
        public void execute() throws InterruptedException {
            if (!done.getAsBoolean()) {
                updateProgress(0.0D);

                final long start = System.currentTimeMillis();
                final double k2 = k / MAX_VALUE;
                while (!done.getAsBoolean()) {
                    updateProgressImmediately(-k / ((System.currentTimeMillis() - start) / 1000D + k2) + MAX_VALUE);

                    Thread.sleep(1000);
                }
            }

            updateProgress(1.0D);
            setResult(Task.this.getResult());
        }
    }

    /// Wraps this task with a stage used by task-count presentation.
    @NotNullByDefault
    public final class CountTask extends Task<T> {
        /// Stage key used by count-aware presentation.
        private final String countStage;

        /// Creates a minor count wrapper for the supplied stage.
        private CountTask(String countStage) {
            this.countStage = countStage;
            setSignificance(TaskSignificance.MINOR);
        }

        /// Returns the stage key used by count-aware presentation.
        public String getCountStage() {
            return countStage;
        }

        /// Returns the outer task as this wrapper's sole prerequisite.
        @Override
        public @Unmodifiable Collection<Task<?>> getDependents() {
            return Collections.singleton(Task.this);
        }

        /// Copies the outer task's possibly absent result into this wrapper.
        @Override
        public void execute() throws Exception {
            setResult(Task.this.getResult());
        }

        /// Requests post-execution notification after dependency processing.
        @Override
        public boolean doPostExecute() {
            return true;
        }

        /// Notifies the executor that count-related task properties may have changed.
        @Override
        public void postExecute() throws Exception {
            notifyPropertiesChanged();
        }
    }
}
