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
package space.minecraftstl.xyml.ui.swing.page.instances;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.setting.GameDirectoryManager;
import space.minecraftstl.xyml.ui.swing.choice.ChoicePage;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;
import space.minecraftstl.xyml.ui.swing.legacy.LegacyStateDispatcher;

import java.util.Objects;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/// Keeps the installed-instance model attached to the process-wide selected game directory.
///
/// Each directory owns a distinct repository and repository event stream. Switching directories therefore
/// replaces the complete delegate, cancels its viewport loads, and publishes a new wrapper content revision.
/// This prevents a toolbar directory change from leaving the instance list, management view, or lazy popup
/// attached to the previous repository.
@NotNullByDefault
public final class SelectedRepositoryInstancesModel implements InstancesModel, AutoCloseable {
    /// Lock guarding delegate replacement, wrapper snapshots, and closure.
    private final Object stateLock = new Object();

    /// Publishes wrapper snapshots whose revisions remain monotonic across repository changes.
    private final ValueChangeSupport<InstancesSnapshot> changes = new ValueChangeSupport<>(this);

    /// Subscription to the process-wide selected repository.
    private final Subscription repositorySubscription;

    /// Current repository-specific delegate.
    private InstancesModel delegate;

    /// Subscription to the current delegate's snapshots.
    private Subscription delegateSubscription;

    /// Cancellation signal invalidated whenever the selected repository changes.
    private LoadCancellation delegateCancellation = new LoadCancellation();

    /// Latest delegate snapshot used to detect delegate content changes.
    private InstancesSnapshot delegateSnapshot;

    /// Latest wrapper snapshot published to Swing consumers.
    private InstancesSnapshot snapshot;

    /// Monotonic wrapper revision independent of delegate-local revision counters.
    private long contentRevision;

    /// Generation identifying the current delegate and its asynchronous loads.
    private long delegateGeneration;

    /// Whether future loads, commands, and switches are rejected.
    private boolean closed;

    /// Creates the production model around every repository selected by the game-directory manager.
    ///
    /// @param backgroundExecutor caller-owned executor for repository and viewport I/O
    /// @param addCommand command opening the download workflow
    /// @param manageCommand command opening one selected instance-management view
    /// @param statusStrings localized repository state text
    public SelectedRepositoryInstancesModel(
            Executor backgroundExecutor,
            Runnable addCommand,
            Consumer<String> manageCommand,
            RepositoryInstancesStatusStrings statusStrings) {
        this(new ProductionSelectedModelSource(repository -> new RepositoryInstancesModel(
                        repository,
                        Objects.requireNonNull(backgroundExecutor, "backgroundExecutor"),
                        Objects.requireNonNull(addCommand, "addCommand"),
                        Objects.requireNonNull(manageCommand, "manageCommand"),
                        Objects.requireNonNull(statusStrings, "statusStrings"))));
    }

    /// Creates a switching model with an explicit selected-model source for focused tests.
    ///
    /// @param modelSource current selected model and transition source
    SelectedRepositoryInstancesModel(SelectedModelSource modelSource) {
        LegacyStateDispatcher.requireEventThread();
        SelectedModelSource validatedSource = Objects.requireNonNull(modelSource, "modelSource");
        delegate = Objects.requireNonNull(validatedSource.current(), "selected model source returned null");
        delegateSnapshot = delegate.snapshot();
        snapshot = mapSnapshot(delegateSnapshot, ++contentRevision);
        delegateSubscription = subscribeToDelegate(delegate, ++delegateGeneration);
        repositorySubscription = validatedSource.subscribe(change -> {
            InstancesModel replacement = Objects.requireNonNull(
                    change.currentValue(),
                    "selected model source published null");
            switchDelegate(replacement);
        });
    }

    /// Returns the latest wrapper state.
    ///
    /// @return current immutable instances snapshot
    @Override
    public InstancesSnapshot snapshot() {
        synchronized (stateLock) {
            requireOpen();
            return snapshot;
        }
    }

    /// Registers one listener for wrapper transitions.
    ///
    /// @param listener snapshot transition listener
    /// @return independently removable listener registration
    @Override
    public Subscription subscribe(ValueChangeListener<InstancesSnapshot> listener) {
        synchronized (stateLock) {
            requireOpen();
            return changes.subscribe(Objects.requireNonNull(listener, "listener"));
        }
    }

    /// Returns the exact item count of the current selected repository.
    ///
    /// @return current exact item count
    @Override
    public OptionalInt exactItemCount() {
        synchronized (stateLock) {
            requireOpen();
            return OptionalInt.of(snapshot.itemCount());
        }
    }

    /// Returns stable IDs from the current selected-repository delegate.
    @Override
    public @Unmodifiable List<String> stableItemIds() {
        return currentDelegate().stableItemIds();
    }

    /// Loads one identified row while rejecting a completion from a replaced repository.
    @Override
    public CompletionStage<InstanceListItem> loadItem(
            String stableId,
            LoadCancellation cancellation) {
        Objects.requireNonNull(stableId, "stableId");
        Objects.requireNonNull(cancellation, "cancellation");
        final InstancesModel requestDelegate;
        final LoadCancellation requestCancellation;
        final long requestGeneration;
        synchronized (stateLock) {
            requireOpen();
            requestDelegate = delegate;
            requestGeneration = delegateGeneration;
            requestCancellation = LoadCancellation.linkedTo(cancellation, delegateCancellation);
        }
        return requestDelegate.loadItem(stableId, requestCancellation).thenApply(item -> {
            requestCancellation.throwIfCancelled();
            synchronized (stateLock) {
                if (closed || requestGeneration != delegateGeneration || requestDelegate != delegate) {
                    throw new CancellationException("Selected repository changed during identified row loading");
                }
            }
            return Objects.requireNonNull(item, "delegate returned null identified row");
        });
    }

    /// Delegates one viewport request while linking it to repository-switch cancellation.
    ///
    /// @param desiredRange measured viewport range
    /// @param cancellation caller-owned cancellation signal
    /// @return current-repository choice page
    @Override
    public CompletionStage<ChoicePage<InstanceListItem>> load(
            IndexRange desiredRange,
            LoadCancellation cancellation) {
        Objects.requireNonNull(desiredRange, "desiredRange");
        Objects.requireNonNull(cancellation, "cancellation");
        final InstancesModel requestDelegate;
        final LoadCancellation requestCancellation;
        final long requestGeneration;
        synchronized (stateLock) {
            requireOpen();
            requestDelegate = delegate;
            requestGeneration = delegateGeneration;
            requestCancellation = LoadCancellation.linkedTo(cancellation, delegateCancellation);
        }
        return requestDelegate.load(desiredRange, requestCancellation).thenApply(page -> {
            requestCancellation.throwIfCancelled();
            synchronized (stateLock) {
                if (closed || requestGeneration != delegateGeneration || requestDelegate != delegate) {
                    throw new CancellationException("Selected repository changed during viewport loading");
                }
            }
            return Objects.requireNonNull(page, "delegate returned null choice page");
        });
    }

    /// Selects an instance in the current repository.
    ///
    /// @param instanceId stable instance identifier
    @Override
    public void selectInstance(String instanceId) {
        currentDelegate().selectInstance(Objects.requireNonNull(instanceId, "instanceId"));
    }

    /// Refreshes the current selected repository.
    @Override
    public void refreshInstances() {
        currentDelegate().refreshInstances();
    }

    /// Opens the shared new-instance workflow.
    @Override
    public void addInstance() {
        currentDelegate().addInstance();
    }

    /// Opens management for the selected instance in the current repository.
    @Override
    public void manageSelectedInstance() {
        currentDelegate().manageSelectedInstance();
    }

    /// Returns the current selected-repository generation.
    ///
    /// @return non-negative repository selection generation
    @Override
    public long selectionContextRevision() {
        synchronized (stateLock) {
            requireOpen();
            return delegateGeneration;
        }
    }

    /// Cancels current loads, releases both subscriptions, and closes the active delegate.
    @Override
    public void close() {
        final Subscription oldDelegateSubscription;
        final InstancesModel oldDelegate;
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            closed = true;
            delegateCancellation.cancel();
            oldDelegateSubscription = delegateSubscription;
            oldDelegate = delegate;
        }
        @Nullable Throwable failure = null;
        failure = attempt(failure, repositorySubscription::unsubscribe);
        failure = attempt(failure, oldDelegateSubscription::unsubscribe);
        failure = attempt(failure, () -> closeDelegate(oldDelegate));
        rethrowFailure(failure);
    }

    /// Replaces the repository-specific delegate and publishes one invalidating wrapper revision.
    ///
    /// @param newDelegate newly selected repository model
    private void switchDelegate(InstancesModel newDelegate) {
        LegacyStateDispatcher.requireEventThread();
        Objects.requireNonNull(newDelegate, "newDelegate");
        final InstancesModel oldDelegate;
        final Subscription oldSubscription;
        final InstancesSnapshot previous;
        final InstancesSnapshot replacement;
        synchronized (stateLock) {
            if (closed) {
                closeDelegate(newDelegate);
                return;
            }
            InstancesSnapshot newDelegateSnapshot;
            long generation = delegateGeneration + 1L;
            Subscription newSubscription;
            try {
                newDelegateSnapshot = newDelegate.snapshot();
                newSubscription = subscribeToDelegate(newDelegate, generation);
            } catch (RuntimeException | Error failure) {
                closeDelegateAfterFailure(newDelegate, failure);
                throw failure;
            }

            oldDelegate = delegate;
            oldSubscription = delegateSubscription;
            delegateCancellation.cancel();
            delegateCancellation = new LoadCancellation();
            delegate = newDelegate;
            delegateSubscription = newSubscription;
            delegateSnapshot = newDelegateSnapshot;
            delegateGeneration = generation;
            previous = snapshot;
            replacement = mapSnapshot(newDelegateSnapshot, ++contentRevision);
            snapshot = replacement;
        }

        @Nullable Throwable failure = null;
        failure = attempt(failure, oldSubscription::unsubscribe);
        failure = attempt(failure, () -> closeDelegate(oldDelegate));
        failure = attempt(failure, () -> publish(previous, replacement));
        rethrowFailure(failure);
    }

    /// Subscribes to one exact delegate generation.
    ///
    /// @param subscribedDelegate delegate being observed
    /// @param generation matching delegate generation
    /// @return delegate listener registration
    private Subscription subscribeToDelegate(InstancesModel subscribedDelegate, long generation) {
        return Objects.requireNonNull(
                subscribedDelegate.subscribe(change -> delegateChanged(subscribedDelegate, generation)),
                "delegate returned null subscription");
    }

    /// Maps one current-delegate transition to the wrapper revision domain.
    ///
    /// @param changedDelegate delegate that emitted the transition
    /// @param generation delegate generation captured by its subscription
    private void delegateChanged(InstancesModel changedDelegate, long generation) {
        InstancesSnapshot previous;
        InstancesSnapshot replacement;
        synchronized (stateLock) {
            if (closed || delegate != changedDelegate || delegateGeneration != generation) {
                return;
            }
            InstancesSnapshot currentDelegateSnapshot = changedDelegate.snapshot();
            if (currentDelegateSnapshot.contentRevision() != delegateSnapshot.contentRevision()) {
                contentRevision++;
            }
            delegateSnapshot = currentDelegateSnapshot;
            previous = snapshot;
            replacement = mapSnapshot(currentDelegateSnapshot, contentRevision);
            snapshot = replacement;
        }
        publish(previous, replacement);
    }

    /// Returns the current delegate after checking the wrapper lifecycle.
    ///
    /// @return active repository-specific model
    private InstancesModel currentDelegate() {
        synchronized (stateLock) {
            requireOpen();
            return delegate;
        }
    }

    /// Copies delegate state while replacing its repository-local content revision.
    ///
    /// @param source current delegate state
    /// @param wrapperRevision monotonic wrapper revision
    /// @return wrapper snapshot
    private static InstancesSnapshot mapSnapshot(InstancesSnapshot source, long wrapperRevision) {
        return new InstancesSnapshot(
                source.selectedIndex(),
                source.itemCount(),
                wrapperRevision,
                source.statusText(),
                source.refreshing(),
                source.listEnabled(),
                source.refreshEnabled(),
                source.addEnabled(),
                source.manageEnabled());
    }

    /// Publishes an already-applied wrapper transition synchronously.
    ///
    /// @param previous previous wrapper state
    /// @param replacement replacement wrapper state
    private void publish(InstancesSnapshot previous, InstancesSnapshot replacement) {
        changes.fireChange(previous, replacement);
    }

    /// Closes an active delegate through its model contract.
    ///
    /// @param model delegate to close
    private static void closeDelegate(InstancesModel model) {
        if (model instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (RuntimeException | Error failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException("Failed to close selected-repository instances model", failure);
            }
        }
    }

    /// Closes a partially installed delegate without hiding the original failure.
    ///
    /// @param model partially installed delegate
    /// @param failure original installation failure
    private static void closeDelegateAfterFailure(InstancesModel model, Throwable failure) {
        try {
            closeDelegate(model);
        } catch (RuntimeException | Error closingFailure) {
            if (closingFailure != failure) {
                failure.addSuppressed(closingFailure);
            }
        }
    }

    /// Attempts one cleanup or publication action while retaining the first failure.
    ///
    /// @param previous first earlier failure, or null
    /// @param action next action to run
    /// @return first failure with later failures suppressed, or null
    private static @Nullable Throwable attempt(
            @Nullable Throwable previous,
            Runnable action) {
        try {
            Objects.requireNonNull(action, "action").run();
            return previous;
        } catch (Throwable failure) {
            if (previous == null) {
                return failure;
            }
            if (previous != failure) {
                previous.addSuppressed(failure);
            }
            return previous;
        }
    }

    /// Rethrows one aggregated operation failure without losing unchecked identity.
    ///
    /// @param failure aggregated failure, or null after success
    private static void rethrowFailure(@Nullable Throwable failure) {
        if (failure == null) {
            return;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Selected-repository instance operation failed", failure);
    }

    /// Rejects operations after closure.
    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Selected-repository instances model is closed");
        }
    }

    /// Creates one independently owned model for a selected repository.
    @FunctionalInterface
    @NotNullByDefault
    interface RepositoryModelFactory {
        /// Creates one model for the exact selected repository.
        ///
        /// @param repository selected repository
        /// @return fresh repository model
        InstancesModel create(XYMLGameRepository repository);
    }

    /// Supplies the selected repository model and publishes later model changes.
    @NotNullByDefault
    interface SelectedModelSource {
        /// Returns the repository model selected at construction time.
        ///
        /// @return current selected repository model
        InstancesModel current();

        /// Subscribes to subsequent selected-model changes.
        ///
        /// @param listener selected-model transition listener
        /// @return independently removable registration
        Subscription subscribe(ValueChangeListener<InstancesModel> listener);
    }

    /// Production adapter mapping process-wide game-directory selections to fresh repository models.
    @NotNullByDefault
    private static final class ProductionSelectedModelSource implements SelectedModelSource {
        /// Factory creating each repository-specific model.
        private final RepositoryModelFactory modelFactory;

        /// Creates an adapter around one validated model factory.
        ///
        /// @param modelFactory repository-specific model factory
        private ProductionSelectedModelSource(RepositoryModelFactory modelFactory) {
            this.modelFactory = Objects.requireNonNull(modelFactory, "modelFactory");
        }

        /// Creates a model for the process-wide selected repository.
        @Override
        public InstancesModel current() {
            return createModel(GameDirectoryManager.getSelectedRepository());
        }

        /// Maps process-wide selected-repository transitions to newly owned models.
        ///
        /// @param listener selected-model transition listener
        /// @return independently removable property registration
        @Override
        public Subscription subscribe(ValueChangeListener<InstancesModel> listener) {
            ValueChangeListener<InstancesModel> validatedListener = Objects.requireNonNull(listener, "listener");
            return GameDirectoryManager.selectedRepositoryProperty().subscribe(change -> {
                XYMLGameRepository repository = Objects.requireNonNull(
                        change.currentValue(),
                        "selected repository property published null");
                validatedListener.onChange(new ValueChange<>(this, null, createModel(repository)));
            });
        }

        /// Creates and validates one selected-repository model.
        ///
        /// @param repository selected repository
        /// @return fresh independently owned model
        private InstancesModel createModel(XYMLGameRepository repository) {
            return Objects.requireNonNull(
                    modelFactory.create(Objects.requireNonNull(repository, "repository")),
                    "repository model factory returned null");
        }
    }
}
