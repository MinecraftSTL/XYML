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
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests EDT serialization, host leases, dynamic ownership, failure cleanup, and close gating.
@NotNullByDefault
public final class InstanceManagementCoordinatorTest {
    /// Worker-thread open and return create, mutate, and close exclusively on the EDT.
    @Test
    public void opensAndReturnsOnTheEventDispatchThread() throws Exception {
        List<String> events = new ArrayList<>();
        RecordingFactory factory = new RecordingFactory(events);
        RecordingHost host = new RecordingHost(events);
        InstanceManagementCoordinator coordinator = new InstanceManagementCoordinator(factory);
        Subscription attachment = coordinator.attachHost(host);
        AtomicReference<@Nullable Throwable> workerFailure = new AtomicReference<>();

        Thread opener = new Thread(() -> {
            try {
                coordinator.open("alpha").toCompletableFuture().join();
            } catch (Throwable failure) {
                workerFailure.set(failure);
            }
        }, "instance-management-open-worker");
        opener.start();
        opener.join();

        FakeView alpha = factory.views().get(0);
        alpha.returnCommand().run();
        EdtDispatcher.executeAndWait(() -> { });

        assertAll(
                () -> assertEquals(null, workerFailure.get()),
                () -> assertEquals(List.of("create:alpha", "show:alpha", "close:alpha", "list"), events),
                () -> assertTrue(factory.allCallsOnEdt.get()),
                () -> assertTrue(host.allCallsOnEdt.get()),
                () -> assertTrue(alpha.closedOnEdt.get()),
                () -> assertEquals(1, alpha.closeCalls.get()),
                () -> onEventDispatchThread(() -> assertEquals(null, coordinator.currentInstanceId())));

        attachment.unsubscribe();
        coordinator.close();
    }

    /// Repeated open fully closes and removes the old view before creating its replacement.
    @Test
    public void repeatedOpenClosesThePreviousViewBeforeCreatingTheNext() {
        List<String> events = new ArrayList<>();
        RecordingFactory factory = new RecordingFactory(events);
        RecordingHost host = new RecordingHost(events);
        InstanceManagementCoordinator coordinator = new InstanceManagementCoordinator(factory);
        Subscription attachment = coordinator.attachHost(host);

        coordinator.open("alpha").toCompletableFuture().join();
        coordinator.open("beta").toCompletableFuture().join();

        assertAll(
                () -> assertEquals(
                        List.of("create:alpha", "show:alpha", "close:alpha", "list", "create:beta", "show:beta"),
                        events),
                () -> assertEquals(1, factory.views().get(0).closeCalls.get()),
                () -> assertEquals(0, factory.views().get(1).closeCalls.get()),
                () -> onEventDispatchThread(() -> assertEquals("beta", coordinator.currentInstanceId())));

        coordinator.close();
        attachment.unsubscribe();
        assertAll(
                () -> assertEquals(1, factory.views().get(1).closeCalls.get()),
                () -> assertEquals("list", events.get(events.size() - 1)));
    }

    /// Inline continuations may open a replacement after open or return has fully released its transition guard.
    @Test
    public void inlineContinuationsCanOpenAfterTerminalCompletion() {
        RecordingFactory factory = new RecordingFactory(new ArrayList<>());
        RecordingHost host = new RecordingHost(new ArrayList<>());
        InstanceManagementCoordinator coordinator = new InstanceManagementCoordinator(factory);
        Subscription attachment = coordinator.attachHost(host);

        coordinator.open("alpha")
                .thenCompose((@Nullable Void ignored) -> coordinator.open("beta"))
                .toCompletableFuture()
                .join();
        coordinator.returnToInstanceList()
                .thenCompose((@Nullable Void ignored) -> coordinator.open("gamma"))
                .toCompletableFuture()
                .join();

        assertAll(
                () -> assertEquals(3, factory.createCalls.get()),
                () -> assertEquals(1, factory.views().get(0).closeCalls.get()),
                () -> assertEquals(1, factory.views().get(1).closeCalls.get()),
                () -> assertEquals(0, factory.views().get(2).closeCalls.get()),
                () -> onEventDispatchThread(() -> assertEquals("gamma", coordinator.currentInstanceId())));
        coordinator.close();
        attachment.unsubscribe();
    }

    /// Replacement stops after fully attempting old-view cleanup and never constructs the next view on failure.
    @Test
    public void replacementCleanupFailurePreventsNewConstruction() {
        RuntimeException viewCloseFailure = new IllegalStateException("old view close failed");
        Error hostRestoreFailure = new AssertionError("old host restore failed");
        RecordingFactory factory = new RecordingFactory(new ArrayList<>());
        RecordingHost host = new RecordingHost(new ArrayList<>());
        InstanceManagementCoordinator coordinator = new InstanceManagementCoordinator(factory);
        Subscription attachment = coordinator.attachHost(host);
        coordinator.open("alpha").toCompletableFuture().join();
        factory.views().get(0).closeFailure = viewCloseFailure;
        host.showListFailure = hostRestoreFailure;

        Throwable failure = completionFailure(coordinator.open("beta"));

        assertAll(
                () -> assertSame(viewCloseFailure, failure),
                () -> assertEquals(List.of(hostRestoreFailure), List.of(failure.getSuppressed())),
                () -> assertEquals(1, factory.createCalls.get()),
                () -> assertEquals(1, factory.views().get(0).closeCalls.get()),
                () -> assertEquals(1, host.showListCalls.get()),
                () -> onEventDispatchThread(() -> assertEquals(null, coordinator.currentInstanceId())));
        host.showListFailure = null;
        coordinator.close();
        attachment.unsubscribe();
    }

    /// A host lease is exclusive, closes its current view synchronously, and cannot detach a later host.
    @Test
    public void hostAttachmentIsExclusiveAndGenerationScoped() {
        RecordingFactory factory = new RecordingFactory(new ArrayList<>());
        RecordingHost firstHost = new RecordingHost(new ArrayList<>());
        RecordingHost secondHost = new RecordingHost(new ArrayList<>());
        InstanceManagementCoordinator coordinator = new InstanceManagementCoordinator(factory);
        Subscription firstAttachment = coordinator.attachHost(firstHost);
        assertThrows(IllegalStateException.class, () -> coordinator.attachHost(secondHost));
        coordinator.open("alpha").toCompletableFuture().join();

        Thread detacher = new Thread(firstAttachment::unsubscribe, "instance-management-detach-worker");
        detacher.start();
        try {
            detacher.join();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }

        assertAll(
                () -> assertEquals(1, factory.views().get(0).closeCalls.get()),
                () -> assertTrue(factory.views().get(0).closedOnEdt.get()),
                () -> assertEquals(1, firstHost.showListCalls.get()),
                () -> assertThrows(CompletionException.class,
                        () -> coordinator.open("without-host").toCompletableFuture().join()));

        Subscription secondAttachment = coordinator.attachHost(secondHost);
        firstAttachment.unsubscribe();
        coordinator.open("beta").toCompletableFuture().join();
        assertEquals(1, secondHost.showManagementCalls.get());
        secondAttachment.unsubscribe();
        coordinator.close();
    }

    /// A host-add failure remains primary while view-close and host-restore failures are suppressed.
    @Test
    public void hostAddFailureAttemptsAllCleanupAndPreservesIdentity() {
        RuntimeException hostAddFailure = new IllegalStateException("host add failed");
        RuntimeException viewCloseFailure = new IllegalArgumentException("view close failed");
        Error hostRestoreFailure = new AssertionError("host restore failed");
        RecordingFactory factory = new RecordingFactory(new ArrayList<>());
        factory.nextViewCloseFailure = viewCloseFailure;
        RecordingHost host = new RecordingHost(new ArrayList<>());
        host.showManagementFailure = hostAddFailure;
        host.showListFailure = hostRestoreFailure;
        InstanceManagementCoordinator coordinator = new InstanceManagementCoordinator(factory);
        Subscription attachment = coordinator.attachHost(host);

        Throwable failure = completionFailure(coordinator.open("alpha"));

        assertAll(
                () -> assertSame(hostAddFailure, failure),
                () -> assertEquals(List.of(viewCloseFailure, hostRestoreFailure),
                        List.of(failure.getSuppressed())),
                () -> assertEquals(1, factory.views().get(0).closeCalls.get()),
                () -> assertEquals(1, host.showListCalls.get()),
                () -> onEventDispatchThread(() -> assertEquals(null, coordinator.currentInstanceId())));

        host.showManagementFailure = null;
        host.showListFailure = null;
        coordinator.close();
        attachment.unsubscribe();
    }

    /// Factory construction failure remains primary and still attempts host restoration.
    @Test
    public void constructionFailureRestoresTheHostAndSuppressesItsFailure() {
        Error constructionFailure = new AssertionError("factory failed");
        RuntimeException hostRestoreFailure = new IllegalStateException("list failed");
        RecordingFactory factory = new RecordingFactory(new ArrayList<>());
        factory.nextConstructionFailure = constructionFailure;
        RecordingHost host = new RecordingHost(new ArrayList<>());
        host.showListFailure = hostRestoreFailure;
        InstanceManagementCoordinator coordinator = new InstanceManagementCoordinator(factory);
        Subscription attachment = coordinator.attachHost(host);

        Throwable failure = completionFailure(coordinator.open("alpha"));

        assertAll(
                () -> assertSame(constructionFailure, failure),
                () -> assertEquals(List.of(hostRestoreFailure), List.of(failure.getSuppressed())),
                () -> assertEquals(1, host.showListCalls.get()),
                () -> assertEquals(List.of(), factory.views()));

        host.showListFailure = null;
        coordinator.close();
        attachment.unsubscribe();
    }

    /// Close attempts view and host cleanup, and every caller observes the same primary failure.
    @Test
    public void closePreservesFailureIdentityAndSuppressedCleanup() {
        RuntimeException viewCloseFailure = new IllegalStateException("close failed");
        Error hostRestoreFailure = new AssertionError("restore failed");
        RecordingFactory factory = new RecordingFactory(new ArrayList<>());
        RecordingHost host = new RecordingHost(new ArrayList<>());
        InstanceManagementCoordinator coordinator = new InstanceManagementCoordinator(factory);
        Subscription attachment = coordinator.attachHost(host);
        coordinator.open("alpha").toCompletableFuture().join();
        factory.views().get(0).closeFailure = viewCloseFailure;
        host.showListFailure = hostRestoreFailure;

        RuntimeException first = assertThrows(RuntimeException.class, coordinator::close);
        RuntimeException second = assertThrows(RuntimeException.class, coordinator::close);

        assertAll(
                () -> assertSame(viewCloseFailure, first),
                () -> assertSame(viewCloseFailure, second),
                () -> assertEquals(List.of(hostRestoreFailure), List.of(first.getSuppressed())),
                () -> assertEquals(1, factory.views().get(0).closeCalls.get()),
                () -> assertEquals(1, host.showListCalls.get()),
                () -> assertTrue(coordinator.isClosed()));
        attachment.unsubscribe();
    }

    /// An open queued before close but executed after the close gate never invokes the factory.
    @Test
    public void lateQueuedOpenDoesNotCreateAfterCloseBegins() throws Exception {
        RecordingFactory factory = new RecordingFactory(new ArrayList<>());
        RecordingHost host = new RecordingHost(new ArrayList<>());
        InstanceManagementCoordinator coordinator = new InstanceManagementCoordinator(factory);
        Subscription attachment = coordinator.attachHost(host);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        EdtDispatcher.execute(() -> {
            blockerStarted.countDown();
            awaitUninterruptibly(releaseBlocker);
        });
        assertTrue(blockerStarted.await(5, TimeUnit.SECONDS));

        CompletionStage<@Nullable Void> lateOpen = coordinator.open("late");
        AtomicReference<@Nullable Throwable> closeFailure = new AtomicReference<>();
        Thread closer = new Thread(() -> {
            try {
                coordinator.close();
            } catch (Throwable failure) {
                closeFailure.set(failure);
            }
        }, "instance-management-close-worker");
        closer.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!coordinator.isClosed() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        boolean closedBeforeRelease = coordinator.isClosed();
        releaseBlocker.countDown();
        closer.join(TimeUnit.SECONDS.toMillis(5));

        assertAll(
                () -> assertTrue(closedBeforeRelease),
                () -> assertFalse(closer.isAlive()),
                () -> assertEquals(null, closeFailure.get()),
                () -> assertThrows(CancellationException.class, () -> lateOpen.toCompletableFuture().join()),
                () -> assertEquals(0, factory.createCalls.get()),
                () -> assertEquals(List.of(), factory.views()),
                () -> assertTrue(host.allCallsOnEdt.get()));
        attachment.unsubscribe();
    }

    /// Returns the exact underlying completion failure.
    ///
    /// @param stage failed operation
    /// @return exact underlying failure
    private static Throwable completionFailure(CompletionStage<@Nullable Void> stage) {
        CompletionException wrapper = assertThrows(
                CompletionException.class,
                () -> stage.toCompletableFuture().join());
        return Objects.requireNonNull(wrapper.getCause(), "completion failure had no cause");
    }

    /// Runs one assertion synchronously on the EDT.
    ///
    /// @param assertion EDT assertion
    private static void onEventDispatchThread(Runnable assertion) {
        EdtDispatcher.executeAndWait(assertion);
    }

    /// Waits for a latch while restoring interruption after release.
    ///
    /// @param latch latch to await
    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /// Configurable factory recording call order, EDT affinity, and created views.
    @NotNullByDefault
    private static final class RecordingFactory implements InstanceManagementViewFactory {
        /// Shared lifecycle event order.
        private final List<String> events;

        /// Created views in call order.
        private final List<FakeView> views = new ArrayList<>();

        /// Factory call count.
        private final AtomicInteger createCalls = new AtomicInteger();

        /// Whether every factory call occurred on the EDT.
        private final AtomicBoolean allCallsOnEdt = new AtomicBoolean(true);

        /// Failure thrown by the next construction, or null.
        private @Nullable Throwable nextConstructionFailure;

        /// Close failure assigned to the next created view, or null.
        private @Nullable Throwable nextViewCloseFailure;

        /// Creates a recording factory.
        ///
        /// @param events shared event order
        private RecordingFactory(List<String> events) {
            this.events = events;
        }

        /// Creates or fails one view while recording EDT affinity.
        @Override
        public InstanceManagementView create(String instanceId, Runnable returnCommand) {
            createCalls.incrementAndGet();
            allCallsOnEdt.compareAndSet(true, SwingUtilities.isEventDispatchThread());
            events.add("create:" + instanceId);
            @Nullable Throwable constructionFailure = nextConstructionFailure;
            nextConstructionFailure = null;
            if (constructionFailure != null) {
                rethrow(constructionFailure);
            }
            FakeView view = new FakeView(instanceId, returnCommand, events);
            view.closeFailure = nextViewCloseFailure;
            nextViewCloseFailure = null;
            views.add(view);
            return view;
        }

        /// Returns an immutable created-view snapshot.
        ///
        /// @return created views
        private @Unmodifiable List<FakeView> views() {
            return List.copyOf(views);
        }
    }

    /// Configurable host recording card mutations and EDT affinity.
    @NotNullByDefault
    private static final class RecordingHost implements InstanceManagementHost {
        /// Shared lifecycle event order.
        private final List<String> events;

        /// Management card mutation count.
        private final AtomicInteger showManagementCalls = new AtomicInteger();

        /// List restoration count.
        private final AtomicInteger showListCalls = new AtomicInteger();

        /// Whether every host mutation occurred on the EDT.
        private final AtomicBoolean allCallsOnEdt = new AtomicBoolean(true);

        /// Failure thrown while showing management, or null.
        private @Nullable Throwable showManagementFailure;

        /// Failure thrown while restoring the list, or null.
        private @Nullable Throwable showListFailure;

        /// Creates a recording host.
        ///
        /// @param events shared event order
        private RecordingHost(List<String> events) {
            this.events = events;
        }

        /// Records and optionally fails management-card display.
        @Override
        public void showManagementView(JComponent component) {
            allCallsOnEdt.compareAndSet(true, SwingUtilities.isEventDispatchThread());
            showManagementCalls.incrementAndGet();
            events.add("show:" + component.getName());
            @Nullable Throwable failure = showManagementFailure;
            if (failure != null) {
                rethrow(failure);
            }
        }

        /// Records and optionally fails list restoration.
        @Override
        public void showInstanceList() {
            allCallsOnEdt.compareAndSet(true, SwingUtilities.isEventDispatchThread());
            showListCalls.incrementAndGet();
            events.add("list");
            @Nullable Throwable failure = showListFailure;
            if (failure != null) {
                rethrow(failure);
            }
        }
    }

    /// Configurable dynamic view recording close ownership and return command.
    @NotNullByDefault
    private static final class FakeView implements InstanceManagementView {
        /// Stable instance identifier.
        private final String instanceId;

        /// Named root component.
        private final JPanel component = new JPanel();

        /// Coordinator return command captured by the factory.
        private final Runnable returnCommand;

        /// Shared lifecycle event order.
        private final List<String> events;

        /// Close call count.
        private final AtomicInteger closeCalls = new AtomicInteger();

        /// Whether close ran on the EDT.
        private final AtomicBoolean closedOnEdt = new AtomicBoolean();

        /// Failure thrown during close, or null.
        private @Nullable Throwable closeFailure;

        /// Creates one fake view.
        ///
        /// @param instanceId stable identifier
        /// @param returnCommand coordinator return command
        /// @param events shared event order
        private FakeView(String instanceId, Runnable returnCommand, List<String> events) {
            this.instanceId = instanceId;
            this.returnCommand = returnCommand;
            this.events = events;
            component.setName(instanceId);
        }

        /// Returns the stable identifier.
        @Override
        public String instanceId() {
            return instanceId;
        }

        /// Returns the named root component.
        @Override
        public JComponent component() {
            return component;
        }

        /// Records and optionally fails owned close.
        @Override
        public void close() {
            closeCalls.incrementAndGet();
            closedOnEdt.set(SwingUtilities.isEventDispatchThread());
            events.add("close:" + instanceId);
            @Nullable Throwable failure = closeFailure;
            if (failure != null) {
                rethrow(failure);
            }
        }

        /// Returns the captured return command.
        ///
        /// @return return command
        private Runnable returnCommand() {
            return returnCommand;
        }
    }

    /// Rethrows a configured unchecked test failure without changing identity.
    ///
    /// @param failure configured failure
    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new AssertionError(failure);
    }
}
