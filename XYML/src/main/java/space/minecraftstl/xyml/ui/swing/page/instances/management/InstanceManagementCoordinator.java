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
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/// Owns one dynamically hosted instance-management view and serializes its lifecycle on the EDT.
///
/// Open and return commands may originate on any thread. A host attachment is exclusive and its
/// subscription detaches synchronously on the EDT. Replacing, returning, detaching, or closing
/// always attempts both view disposal and host restoration. Internal fields are detached before
/// any external factory, host, or view invocation, and no internal monitor spans external code.
@NotNullByDefault
public final class InstanceManagementCoordinator implements AutoCloseable {
    /// Factory invoked only on the EDT for validated stable identifiers.
    private final InstanceManagementViewFactory viewFactory;

    /// Gate set before close queues EDT cleanup so late queued opens cannot create a view.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Shared terminal result allowing concurrent close callers to await the first cleanup.
    private final CompletableFuture<@Nullable Void> closeCompletion = new CompletableFuture<>();

    /// Attached instances-page host, accessed only on the EDT.
    private @Nullable InstanceManagementHost host;

    /// Generation distinguishing a current host lease from stale subscriptions.
    private long hostGeneration;

    /// Current coordinator-owned dynamic view, accessed only on the EDT.
    private @Nullable InstanceManagementView currentView;

    /// Stable identifier matching the current view, or null while the list is visible.
    private @Nullable GameInstanceID currentInstanceId;

    /// Reentrant external lifecycle invocation depth on the EDT.
    private int transitionDepth;

    /// Creates an empty coordinator with no attached page host.
    ///
    /// @param viewFactory dynamic management view factory
    public InstanceManagementCoordinator(InstanceManagementViewFactory viewFactory) {
        this.viewFactory = Objects.requireNonNull(viewFactory, "viewFactory");
    }

    /// Attaches the one instances-page host and returns a synchronous detachable lease.
    ///
    /// This method may be called from any thread. A second simultaneous host is rejected. Closing
    /// the returned subscription closes the current view, restores the list, and detaches only the
    /// matching host generation; a stale lease cannot detach a later host.
    ///
    /// @param newHost exclusive page host
    /// @return matching detachable host lease
    public Subscription attachHost(InstanceManagementHost newHost) {
        Objects.requireNonNull(newHost, "newHost");
        if (closed.get()) {
            throw new IllegalStateException("Instance management coordinator is closed");
        }
        AtomicReference<@Nullable Subscription> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> {
            requireOpen();
            if (host != null) {
                throw new IllegalStateException("An instance management host is already attached");
            }
            host = newHost;
            long attachedGeneration = ++hostGeneration;
            result.set(Subscription.create(() -> detachHost(newHost, attachedGeneration)));
        });
        return Objects.requireNonNull(result.get(), "host attachment was not created");
    }

    /// Opens management for one stable repository instance identifier.
    ///
    /// Repeated opens fully attempt disposal of the previous view before invoking the factory for
    /// the replacement. The returned stage completes after the EDT host mutation or with the exact
    /// lifecycle failure, including suppressed cleanup failures.
    ///
    /// @param instanceId stable non-blank repository identifier
    /// @return asynchronous EDT operation completion
    public CompletionStage<@Nullable Void> open(GameInstanceID instanceId) {
        Objects.requireNonNull(instanceId, "instanceId");
        if (closed.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Instance management coordinator is closed"));
        }
        CompletableFuture<@Nullable Void> result = new CompletableFuture<>();
        dispatch(() -> openOnEventDispatchThread(instanceId, result), result);
        return result;
    }

    /// Closes the current view and restores the attached instances list.
    ///
    /// @return asynchronous EDT operation completion
    public CompletionStage<@Nullable Void> returnToInstanceList() {
        if (closed.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Instance management coordinator is closed"));
        }
        CompletableFuture<@Nullable Void> result = new CompletableFuture<>();
        dispatch(() -> returnOnEventDispatchThread(result), result);
        return result;
    }

    /// Returns the active stable identifier for focused integrations.
    ///
    /// @return active instance identifier, or null while the list is visible
    public @Nullable GameInstanceID currentInstanceId() {
        EdtDispatcher.requireEventDispatchThread();
        return currentInstanceId;
    }

    /// Returns whether close has begun and future opens are gated.
    ///
    /// @return whether the coordinator is closed
    public boolean isClosed() {
        return closed.get();
    }

    /// Closes the current view and detaches the host exactly once from any caller thread.
    ///
    /// Concurrent non-EDT callers await the first cleanup and observe the same failure identity.
    /// A reentrant EDT close returns without waiting on its own in-progress cleanup.
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            awaitExistingClose();
            return;
        }

        @Nullable Throwable failure = null;
        try {
            EdtDispatcher.executeAndWait(this::closeOnEventDispatchThread);
        } catch (RuntimeException | Error closingFailure) {
            failure = closingFailure;
        }
        if (failure == null) {
            closeCompletion.complete(null);
        } else {
            closeCompletion.completeExceptionally(failure);
            rethrowUnchecked(failure);
        }
    }

    /// Creates and hosts one replacement view on the EDT.
    ///
    /// @param instanceId stable instance identifier
    /// @param result command completion
    private void openOnEventDispatchThread(
            GameInstanceID instanceId,
            CompletableFuture<@Nullable Void> result) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get()) {
            result.completeExceptionally(new CancellationException("Instance management coordinator was closed"));
            return;
        }
        if (transitionDepth > 0) {
            result.completeExceptionally(new IllegalStateException("Instance management transition is in progress"));
            return;
        }
        @Nullable InstanceManagementHost attachedHost = host;
        long attachedGeneration = hostGeneration;
        if (attachedHost == null) {
            result.completeExceptionally(new IllegalStateException("No instance management host is attached"));
            return;
        }

        @Nullable Throwable failure;
        transitionDepth++;
        try {
            failure = replaceCurrentView(instanceId, attachedHost, attachedGeneration);
        } finally {
            transitionDepth--;
        }
        complete(result, failure);
    }

    /// Closes any previous view and creates its replacement while one EDT transition is active.
    ///
    /// @param instanceId stable instance identifier
    /// @param attachedHost captured host
    /// @param attachedGeneration captured host generation
    /// @return lifecycle failure, or null after success
    private @Nullable Throwable replaceCurrentView(
            GameInstanceID instanceId,
            InstanceManagementHost attachedHost,
            long attachedGeneration) {
        @Nullable InstanceManagementView previousView = currentView;
        currentView = null;
        currentInstanceId = null;
        @Nullable Throwable replacementFailure = previousView == null
                ? null
                : closeViewAndRestoreHost(previousView, attachedHost, null);
        if (replacementFailure != null) {
            return replacementFailure;
        }
        if (closed.get()) {
            return new CancellationException("Instance management coordinator was closed");
        }
        if (host != attachedHost || hostGeneration != attachedGeneration) {
            return new IllegalStateException("Instance management host was detached");
        }
        return createAndShow(instanceId, attachedHost, attachedGeneration);
    }

    /// Invokes the factory, validates its result, and transfers it into the attached host.
    ///
    /// @param instanceId stable instance identifier
    /// @param attachedHost captured host
    /// @param attachedGeneration captured host generation
    /// @return lifecycle failure, or null after success
    private @Nullable Throwable createAndShow(
            GameInstanceID instanceId,
            InstanceManagementHost attachedHost,
            long attachedGeneration) {
        @Nullable InstanceManagementView createdView = null;
        try {
            createdView = Objects.requireNonNull(
                    viewFactory.create(instanceId, () -> returnToInstanceList()),
                    "instance management factory returned null");
            GameInstanceID createdInstanceId = Objects.requireNonNull(
                    createdView.instanceId(),
                    "instance management view returned null instanceId");
            if (!instanceId.equals(createdInstanceId)) {
                throw new IllegalArgumentException("Instance management view identifier did not match its request");
            }
            JComponent component = Objects.requireNonNull(
                    createdView.component(),
                    "instance management view returned null component");
            if (closed.get()) {
                throw new CancellationException("Instance management coordinator was closed");
            }
            if (host != attachedHost || hostGeneration != attachedGeneration) {
                throw new IllegalStateException("Instance management host was detached");
            }

            attachedHost.showManagementView(component);
            if (closed.get()) {
                throw new CancellationException("Instance management coordinator was closed");
            }
            if (host != attachedHost || hostGeneration != attachedGeneration) {
                throw new IllegalStateException("Instance management host was detached");
            }
            currentView = createdView;
            currentInstanceId = instanceId;
            return null;
        } catch (RuntimeException | Error failure) {
            @Nullable Throwable cleanupFailure = failure;
            if (createdView != null) {
                cleanupFailure = closeViewAndRestoreHost(createdView, attachedHost, cleanupFailure);
            } else {
                cleanupFailure = attempt(cleanupFailure, attachedHost::showInstanceList);
            }
            return Objects.requireNonNull(cleanupFailure);
        }
    }

    /// Returns to the list on the EDT and reports exact cleanup failures.
    ///
    /// @param result command completion
    private void returnOnEventDispatchThread(CompletableFuture<@Nullable Void> result) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get()) {
            result.completeExceptionally(new CancellationException("Instance management coordinator was closed"));
            return;
        }
        if (transitionDepth > 0) {
            result.completeExceptionally(new IllegalStateException("Instance management transition is in progress"));
            return;
        }
        @Nullable InstanceManagementView view = currentView;
        @Nullable InstanceManagementHost attachedHost = host;
        currentView = null;
        currentInstanceId = null;
        if (view == null || attachedHost == null) {
            result.complete(null);
            return;
        }

        @Nullable Throwable failure;
        transitionDepth++;
        try {
            failure = closeViewAndRestoreHost(view, attachedHost, null);
        } finally {
            transitionDepth--;
        }
        complete(result, failure);
    }

    /// Detaches only the matching host lease and synchronously releases its current view.
    ///
    /// @param expectedHost host captured by the lease
    /// @param expectedGeneration host generation captured by the lease
    private void detachHost(InstanceManagementHost expectedHost, long expectedGeneration) {
        EdtDispatcher.executeAndWait(() -> detachHostOnEventDispatchThread(expectedHost, expectedGeneration));
    }

    /// Applies one matching host detach on the EDT.
    ///
    /// @param expectedHost host captured by the lease
    /// @param expectedGeneration host generation captured by the lease
    private void detachHostOnEventDispatchThread(
            InstanceManagementHost expectedHost,
            long expectedGeneration) {
        EdtDispatcher.requireEventDispatchThread();
        if (host != expectedHost || hostGeneration != expectedGeneration) {
            return;
        }
        @Nullable InstanceManagementView view = currentView;
        host = null;
        hostGeneration++;
        currentView = null;
        currentInstanceId = null;
        if (view == null) {
            return;
        }

        transitionDepth++;
        try {
            @Nullable Throwable failure = closeViewAndRestoreHost(view, expectedHost, null);
            if (failure != null) {
                rethrowUnchecked(failure);
            }
        } finally {
            transitionDepth--;
        }
    }

    /// Clears coordinator state before closing its view and restoring its host on the EDT.
    private void closeOnEventDispatchThread() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable InstanceManagementView view = currentView;
        @Nullable InstanceManagementHost attachedHost = host;
        currentView = null;
        currentInstanceId = null;
        host = null;
        hostGeneration++;
        if (attachedHost == null) {
            if (view != null) {
                @Nullable Throwable failure = attempt(null, view::close);
                if (failure != null) {
                    rethrowUnchecked(failure);
                }
            }
            return;
        }

        transitionDepth++;
        try {
            @Nullable Throwable failure = view == null
                    ? attempt(null, attachedHost::showInstanceList)
                    : closeViewAndRestoreHost(view, attachedHost, null);
            if (failure != null) {
                rethrowUnchecked(failure);
            }
        } finally {
            transitionDepth--;
        }
    }

    /// Attempts full view disposal followed by list restoration without skipping either action.
    ///
    /// @param view view to close
    /// @param attachedHost host to restore
    /// @param primary earlier failure, or null
    /// @return primary failure with later cleanup failures suppressed, or null
    private static @Nullable Throwable closeViewAndRestoreHost(
            InstanceManagementView view,
            InstanceManagementHost attachedHost,
            @Nullable Throwable primary) {
        @Nullable Throwable failure = attempt(primary, view::close);
        return attempt(failure, attachedHost::showInstanceList);
    }

    /// Runs one external lifecycle action and preserves the first failure identity.
    ///
    /// @param primary first failure, or null
    /// @param action external lifecycle action
    /// @return first failure with any later distinct failure suppressed
    private static @Nullable Throwable attempt(
            @Nullable Throwable primary,
            Runnable action) {
        try {
            action.run();
            return primary;
        } catch (RuntimeException | Error failure) {
            if (primary == null) {
                return failure;
            }
            if (primary != failure) {
                primary.addSuppressed(failure);
            }
            return primary;
        }
    }

    /// Dispatches one lifecycle operation and completes its stage when dispatch itself fails.
    ///
    /// @param operation EDT lifecycle operation
    /// @param result operation completion
    private static void dispatch(
            Runnable operation,
            CompletableFuture<@Nullable Void> result) {
        try {
            EdtDispatcher.execute(operation);
        } catch (RuntimeException | Error failure) {
            result.completeExceptionally(failure);
        }
    }

    /// Completes one command only after its EDT transition guard has been released.
    ///
    /// @param result command completion
    /// @param failure lifecycle failure, or null after success
    private static void complete(
            CompletableFuture<@Nullable Void> result,
            @Nullable Throwable failure) {
        if (failure == null) {
            result.complete(null);
        } else {
            result.completeExceptionally(failure);
        }
    }

    /// Rejects host attachment after close begins.
    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Instance management coordinator is closed");
        }
    }

    /// Awaits the first close caller without deadlocking an EDT reentrant close.
    private void awaitExistingClose() {
        if (SwingUtilities.isEventDispatchThread() && !closeCompletion.isDone()) {
            return;
        }
        try {
            closeCompletion.join();
        } catch (CompletionException failure) {
            rethrowUnchecked(Objects.requireNonNull(failure.getCause(), "close failure had no cause"));
        }
    }

    /// Rethrows one unchecked lifecycle failure without wrapping its identity.
    ///
    /// @param failure lifecycle failure
    private static void rethrowUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Unexpected checked instance-management failure", failure);
    }
}
