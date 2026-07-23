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
package space.minecraftstl.xyml.ui.swing.page.home;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.game.launch.DefaultGameLaunchService;
import space.minecraftstl.xyml.game.launch.LaunchRequest;
import space.minecraftstl.xyml.game.launch.LaunchSession;
import space.minecraftstl.xyml.game.launch.LaunchStatus;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.observable.property.ReadOnlyProperty;
import space.minecraftstl.xyml.observable.property.SimpleObjectProperty;
import space.minecraftstl.xyml.task.presentation.TaskSnapshot;
import space.minecraftstl.xyml.task.presentation.TaskStatus;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;
import space.minecraftstl.xyml.util.platform.ManagedProcess;

import javax.swing.AbstractButton;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests home commands, worker-thread state updates, and off-screen layout rendering.
@NotNullByDefault
public final class HomePanelTest {
    /// Localized strings used by the focused page tests.
    private static final HomeStrings STRINGS = new HomeStrings(
            "Play", "Account", "No account", "Instance", "No instance",
            "Add instance", "Launch", "Launching", "Back to selections");

    /// Localized task strings used by the focused page tests.
    private static final TaskProgressStrings TASK_STRINGS = new TaskProgressStrings(
            "Waiting", "Running", "Completed", "Failed", "Cancelled",
            "Task progress", "Cancel", "Show details", "Hide details");

    /// Every visible command invokes its corresponding model command exactly once.
    @Test
    public void delegatesSelectionAndLaunchCommands() {
        FakeHomeModel model = new FakeHomeModel(readySnapshot());
        HomePanel panel = createPanel(model);

        onEventDispatchThread(() -> {
            findButton(panel, "homeAccount").doClick();
            findButton(panel, "homeInstance").doClick();
            findButton(panel, "homeAddInstance").doClick();
            findButton(panel, "homeLaunch").doClick();

            assertAll(
                    () -> assertEquals(1, model.accountSelections.get()),
                    () -> assertEquals(1, model.instanceSelections.get()),
                    () -> assertEquals(1, model.instanceAdditions.get()),
                    () -> assertEquals(1, model.launches.get()));
            panel.close();
        });
    }

    /// A worker-published launching snapshot disables duplicate launch and selection commands on the EDT.
    @Test
    public void appliesWorkerPublishedLaunchingState() throws InterruptedException {
        FakeHomeModel model = new FakeHomeModel(readySnapshot());
        HomePanel panel = createPanel(model);
        HomeSnapshot launching = new HomeSnapshot(
                "Alex", "Microsoft", "Long Modded Instance Name", "1.21.1 / Fabric",
                "Preparing game", false, true, false);

        Thread publisher = new Thread(() -> model.publish(launching), "home-panel-test-publisher");
        publisher.start();
        publisher.join();
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> {
            AbstractButton launchButton = findButton(panel, "homeLaunch");
            assertAll(
                    () -> assertEquals(launching, panel.displayedSnapshot()),
                    () -> assertEquals("Launching", launchButton.getText()),
                    () -> assertFalse(launchButton.isEnabled()),
                    () -> assertFalse(findButton(panel, "homeAccount").isEnabled()),
                    () -> assertFalse(findButton(panel, "homeInstance").isEnabled()));
            panel.close();
        });
    }

    /// The page paints an opaque, varied surface at a constrained shell content size.
    @Test
    public void paintsNonBlankSurfaceWithLongSelectionText() {
        FakeHomeModel model = new FakeHomeModel(new HomeSnapshot(
                "A very long player account name that must remain inside the selector",
                "External authentication provider with long status",
                "A very long modded instance name that must be truncated by pixel width",
                "Minecraft 1.21.1 with a long loader description",
                "Ready", true, false, true));
        HomePanel panel = createPanel(model);

        BufferedImage image = onEventDispatchThread(() -> {
            Dimension size = new Dimension(820, 520);
            panel.setSize(size);
            layoutRecursively(panel);
            BufferedImage rendered = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = rendered.createGraphics();
            try {
                panel.printAll(graphics);
            } finally {
                graphics.dispose();
            }
            panel.close();
            return rendered;
        });

        assertTrue(distinctColors(image).size() > 4);
    }

    /// The integrated launch-task card stays above the fixed action band at the constrained shell content size.
    @Test
    public void laysOutLaunchTaskWithoutOverlappingActions() {
        FakeHomeModel model = new FakeHomeModel(new HomeSnapshot(
                "Steve", "Offline", "Minecraft 1.21", "Vanilla",
                "Preparing game", false, true, false));
        model.publishLaunchSession(new FakeLaunchSession());
        HomePanel panel = createPanel(model);

        BufferedImage image = onEventDispatchThread(() -> {
            Dimension size = new Dimension(820, 520);
            panel.setSize(size);
            layoutRecursively(panel);

            Component taskHost = findComponent(panel, "homeTaskProgressHost");
            Component actionBand = findComponent(panel, "homeActionBand");
            Component taskTitle = findComponent(panel, "taskTitle");
            Rectangle taskBounds = SwingUtilities.convertRectangle(
                    taskHost.getParent(), taskHost.getBounds(), panel);
            Rectangle actionBounds = SwingUtilities.convertRectangle(
                    actionBand.getParent(), actionBand.getBounds(), panel);
            assertAll(
                    () -> assertTrue(panel.isTaskViewVisible()),
                    () -> assertTrue(taskBounds.width > 0),
                    () -> assertTrue(taskBounds.height > 0),
                    () -> assertTrue(taskBounds.y + taskBounds.height <= actionBounds.y),
                    () -> assertTrue(taskTitle.getWidth() > 0),
                    () -> assertTrue(taskTitle.getHeight() > 0));

            BufferedImage rendered = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = rendered.createGraphics();
            try {
                panel.printAll(graphics);
            } finally {
                graphics.dispose();
            }
            panel.close();
            return rendered;
        });

        assertTrue(distinctColors(image).size() > 4);
    }

    /// A launch session selects the task card, retains terminal details, and permits returning after completion.
    @Test
    public void presentsLaunchTaskUntilUserReturnsAfterTerminalState() throws InterruptedException {
        FakeHomeModel model = new FakeHomeModel(readySnapshot());
        HomePanel panel = createPanel(model);
        FakeLaunchSession firstSession = new FakeLaunchSession();
        HomeSnapshot launching = new HomeSnapshot(
                "Steve", "Offline", "Minecraft 1.21", "Vanilla",
                "Preparing game", false, true, false);

        Thread publisher = new Thread(() -> {
            model.publish(launching);
            model.publishLaunchSession(firstSession);
        }, "home-launch-session-test-publisher");
        publisher.start();
        publisher.join();
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> assertAll(
                () -> assertTrue(panel.isTaskViewVisible()),
                () -> assertFalse(findButton(panel, "homeBackToSelections").isEnabled()),
                () -> assertFalse(findButton(panel, "homeLaunch").isEnabled())));

        firstSession.completeSuccessfully();
        model.publish(readySnapshot());
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> {
            assertAll(
                    () -> assertTrue(panel.isTaskViewVisible()),
                    () -> assertTrue(findButton(panel, "homeBackToSelections").isEnabled()),
                    () -> assertTrue(findButton(panel, "homeLaunch").isEnabled()));
        });

        model.publish(launching);
        EdtDispatcher.executeAndWait(() -> { });
        onEventDispatchThread(() -> assertFalse(findButton(panel, "homeBackToSelections").isEnabled()));

        model.publish(readySnapshot());
        EdtDispatcher.executeAndWait(() -> { });
        onEventDispatchThread(() -> {
            assertTrue(findButton(panel, "homeBackToSelections").isEnabled());
            findButton(panel, "homeBackToSelections").doClick();
            assertFalse(panel.isTaskViewVisible());
        });

        FakeLaunchSession secondSession = new FakeLaunchSession();
        model.publishLaunchSession(secondSession);
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> {
            assertTrue(panel.isTaskViewVisible());
            panel.close();
        });
    }

    /// The real home model and launch service drive task presentation, terminal return, and a second launch.
    @Test
    public void integratesRealHomeModelLaunchSessionAndPanelSequence() {
        MutableSelectionStore store = new MutableSelectionStore(new HomeSelectionState(
                "account-a", "directory-a", "instance-a",
                "Alex", "Microsoft", "Minecraft 1.21.1", "Fabric"));
        DefaultGameLaunchService launchService = new DefaultGameLaunchService(
                request -> {
                    throw new AssertionError("queued integration launch must not create a task");
                },
                command -> { },
                "Launch Minecraft",
                "Waiting for launch");
        LauncherHomeModel model = new LauncherHomeModel(
                store,
                new HomeStatusStrings("Ready", "Choose account", "Choose instance"),
                () -> { },
                () -> { },
                () -> { },
                launchService::launch);
        HomePanel panel = createPanel(model);
        try {
            onEventDispatchThread(() -> findButton(panel, "homeLaunch").doClick());
            LaunchSession firstSession = model.launchSessionProperty().getValue().orElseThrow();

            onEventDispatchThread(() -> assertAll(
                    () -> assertTrue(panel.isTaskViewVisible()),
                    () -> assertFalse(findButton(panel, "homeBackToSelections").isEnabled()),
                    () -> assertFalse(findButton(panel, "homeLaunch").isEnabled())));

            assertTrue(firstSession.cancel());
            EdtDispatcher.executeAndWait(() -> { });
            onEventDispatchThread(() -> {
                assertAll(
                        () -> assertTrue(panel.isTaskViewVisible()),
                        () -> assertTrue(findButton(panel, "homeBackToSelections").isEnabled()),
                        () -> assertTrue(findButton(panel, "homeLaunch").isEnabled()));
                findButton(panel, "homeBackToSelections").doClick();
                assertFalse(panel.isTaskViewVisible());
                findButton(panel, "homeLaunch").doClick();
            });

            LaunchSession secondSession = model.launchSessionProperty().getValue().orElseThrow();
            onEventDispatchThread(() -> assertAll(
                    () -> assertNotSame(firstSession, secondSession),
                    () -> assertTrue(panel.isTaskViewVisible()),
                    () -> assertFalse(findButton(panel, "homeBackToSelections").isEnabled())));
        } finally {
            panel.close();
            model.close();
            launchService.close();
        }
    }

    /// Closing waits for an in-flight EDT publication and rejects every later model transition.
    @Test
    public void closeFormsBarrierAgainstInFlightAndLatePublications() throws InterruptedException {
        FakeHomeModel model = new FakeHomeModel(readySnapshot());
        HomePanel panel = createPanel(model);
        HomeSnapshot launching = new HomeSnapshot(
                "Steve", "Offline", "Minecraft 1.21", "Vanilla",
                "Preparing game", false, true, false);
        CountDownLatch snapshotEntered = new CountDownLatch(1);
        CountDownLatch releaseSnapshot = new CountDownLatch(1);
        CountDownLatch closeReturned = new CountDownLatch(1);
        model.blockNextSnapshotRead(snapshotEntered, releaseSnapshot);
        model.publish(launching);

        Thread closer = new Thread(() -> {
            panel.close();
            closeReturned.countDown();
        }, "home-panel-close-barrier-test");
        try {
            assertTrue(snapshotEntered.await(5, TimeUnit.SECONDS));
            closer.start();
            assertFalse(closeReturned.await(100, TimeUnit.MILLISECONDS));
        } finally {
            releaseSnapshot.countDown();
        }

        assertTrue(closeReturned.await(5, TimeUnit.SECONDS));
        closer.join();
        model.publish(readySnapshot());
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> assertEquals(launching, panel.displayedSnapshot()));
    }

    /// A transient task-panel initialization failure is retried for the same session on the next home invalidation.
    @Test
    public void retriesSameSessionAfterTransientTaskPanelFailure() {
        FakeHomeModel model = new FakeHomeModel(readySnapshot());
        HomePanel panel = createPanel(model);
        FakeLaunchSession session = new FakeLaunchSession();
        IllegalStateException expectedFailure = new IllegalStateException("task snapshot unavailable");
        session.failNextSnapshotRead(expectedFailure);

        IllegalStateException actualFailure = onEventDispatchThread(() -> assertThrows(
                IllegalStateException.class,
                () -> model.publishLaunchSession(session)));
        onEventDispatchThread(() -> assertAll(
                () -> assertSame(expectedFailure, actualFailure),
                () -> assertFalse(panel.isTaskViewVisible())));

        model.publish(new HomeSnapshot(
                "Steve", "Offline", "Minecraft 1.21", "Vanilla",
                "Ready after retry", true, false, true));
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> {
            assertTrue(panel.isTaskViewVisible());
            panel.close();
        });
    }

    /// A constructor failure releases both home registrations and the temporary task registration.
    @Test
    public void releasesEverySubscriptionWhenInitialTaskPanelFails() {
        FakeHomeModel model = new FakeHomeModel(readySnapshot());
        FakeLaunchSession session = new FakeLaunchSession();
        IllegalStateException expectedFailure = new IllegalStateException("initial task snapshot unavailable");
        session.failNextSnapshotRead(expectedFailure);
        model.publishLaunchSession(session);

        IllegalStateException actualFailure = onEventDispatchThread(() -> assertThrows(
                IllegalStateException.class,
                () -> new HomePanel(model, STRINGS, TASK_STRINGS, null, Duration.ZERO)));

        assertAll(
                () -> assertSame(expectedFailure, actualFailure),
                () -> assertEquals(0, model.activeHomeSubscriptionCount()),
                () -> assertEquals(0, model.activeLaunchSubscriptionCount()),
                () -> assertFalse(session.hasTaskSubscribers()));
    }

    /// Close attempts both registrations even when they throw the same failure instance.
    @Test
    public void closeAggregatesCleanupWithoutSelfSuppression() {
        FakeHomeModel model = new FakeHomeModel(readySnapshot());
        HomePanel panel = createPanel(model);
        IllegalStateException sharedFailure = new IllegalStateException("subscription cleanup failed");
        model.failNextHomeUnsubscribe(sharedFailure);
        model.failNextLaunchUnsubscribe(sharedFailure);

        IllegalStateException actualFailure = onEventDispatchThread(
                () -> assertThrows(IllegalStateException.class, panel::close));

        assertAll(
                () -> assertSame(sharedFailure, actualFailure),
                () -> assertEquals(0, model.activeHomeSubscriptionCount()),
                () -> assertEquals(0, model.activeLaunchSubscriptionCount()));
    }

    /// Clearing a session still returns to selectors when the child task cleanup reports a failure.
    @Test
    public void sessionClearReturnsToSelectionsAfterTaskCleanupFailure() {
        FakeHomeModel model = new FakeHomeModel(readySnapshot());
        FakeLaunchSession session = new FakeLaunchSession();
        HomePanel panel = createPanel(model);
        onEventDispatchThread(() -> model.publishLaunchSession(session));
        IllegalStateException expectedFailure = new IllegalStateException("task cleanup failed");
        session.failNextTaskUnsubscribe(expectedFailure);

        IllegalStateException actualFailure = onEventDispatchThread(() -> assertThrows(
                IllegalStateException.class,
                model::clearLaunchSession));

        onEventDispatchThread(() -> {
            assertAll(
                    () -> assertSame(expectedFailure, actualFailure),
                    () -> assertFalse(panel.isTaskViewVisible()),
                    () -> assertFalse(session.hasTaskSubscribers()));
            panel.close();
        });
    }

    /// Creates the home panel with explicit localized task presentation and no animation.
    ///
    /// @param model fake home model
    /// @return initialized home panel
    private static HomePanel createPanel(HomeModel model) {
        return onEventDispatchThread(() -> new HomePanel(
                model,
                STRINGS,
                TASK_STRINGS,
                null,
                Duration.ZERO));
    }

    /// Creates the normal selected-account and selected-instance launch state.
    ///
    /// @return ready home snapshot
    private static HomeSnapshot readySnapshot() {
        return new HomeSnapshot(
                "Steve", "Offline", "Minecraft 1.21", "Vanilla", "Ready", true, false, true);
    }

    /// Finds a named button in a Swing hierarchy.
    ///
    /// @param root hierarchy root
    /// @param name stable component name
    /// @return matching command button
    private static AbstractButton findButton(Container root, String name) {
        @Nullable AbstractButton result = findOptionalButton(root, name);
        if (result == null) {
            throw new IllegalArgumentException("Missing button: " + name);
        }
        return result;
    }

    /// Finds a named component in a Swing hierarchy.
    ///
    /// @param root hierarchy root
    /// @param name stable component name
    /// @return matching component
    private static Component findComponent(Container root, String name) {
        @Nullable Component result = findOptionalComponent(root, name);
        if (result == null) {
            throw new IllegalArgumentException("Missing component: " + name);
        }
        return result;
    }

    /// Searches a hierarchy for any named component.
    ///
    /// @param root hierarchy root
    /// @param name stable component name
    /// @return matching component, or null when absent
    private static @Nullable Component findOptionalComponent(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (Objects.equals(name, child.getName())) {
                return child;
            }
            if (child instanceof Container container) {
                @Nullable Component nested = findOptionalComponent(container, name);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    /// Searches a hierarchy for a named button.
    ///
    /// @param root hierarchy root
    /// @param name stable component name
    /// @return matching button, or null when absent
    private static @Nullable AbstractButton findOptionalButton(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (child instanceof AbstractButton button && Objects.equals(name, button.getName())) {
                return button;
            }
            if (child instanceof Container container) {
                @Nullable AbstractButton nested = findOptionalButton(container, name);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    /// Runs a value-producing operation synchronously on the EDT.
    ///
    /// @param operation operation to run
    /// @param <T> non-null result type
    /// @return operation result
    private static <T extends Object> T onEventDispatchThread(Supplier<T> operation) {
        AtomicReference<@Nullable T> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> result.set(operation.get()));
        return Objects.requireNonNull(result.get(), "EDT operation did not return a result");
    }

    /// Runs an operation synchronously on the EDT.
    ///
    /// @param operation operation to run
    private static void onEventDispatchThread(Runnable operation) {
        EdtDispatcher.executeAndWait(operation);
    }

    /// Recursively lays out a component hierarchy before off-screen painting.
    ///
    /// @param container hierarchy root
    private static void layoutRecursively(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                layoutRecursively(nested);
            }
        }
    }

    /// Collects all pixel colors painted into an image.
    ///
    /// @param image rendered home page
    /// @return mutable distinct-color set
    private static Set<Integer> distinctColors(BufferedImage image) {
        Set<Integer> colors = new HashSet<>();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                colors.add(image.getRGB(x, y));
            }
        }
        return colors;
    }

    /// Mutable selection store used by the real home-model integration test.
    @NotNullByDefault
    private static final class MutableSelectionStore implements HomeSelectionStore {
        /// Latest immutable selection.
        private final AtomicReference<HomeSelectionState> current;

        /// Selection transition publisher.
        private final ValueChangeSupport<HomeSelectionState> changes = new ValueChangeSupport<>(this);

        /// Creates a store with one ready selection.
        ///
        /// @param initialSelection initial selection
        private MutableSelectionStore(HomeSelectionState initialSelection) {
            current = new AtomicReference<>(initialSelection);
        }

        /// Returns the current selection.
        @Override
        public HomeSelectionState snapshot() {
            return current.get();
        }

        /// Registers a selection listener.
        @Override
        public Subscription subscribe(ValueChangeListener<HomeSelectionState> listener) {
            return changes.subscribe(listener);
        }
    }

    /// Thread-safe fake home model with explicit command counters.
    @NotNullByDefault
    private static final class FakeHomeModel implements HomeModel {
        /// Latest immutable home state.
        private final AtomicReference<HomeSnapshot> current;

        /// Home snapshot transition publisher.
        private final ValueChangeSupport<HomeSnapshot> changes = new ValueChangeSupport<>(this);

        /// Optional fake launch session retained for the home-view contract.
        private final TrackingProperty<Optional<LaunchSession>> launchSession =
                new TrackingProperty<>(this, "launchSession", Optional.empty());

        /// Number of active home snapshot registrations.
        private final AtomicInteger activeHomeSubscriptions = new AtomicInteger();

        /// Failure thrown after the next home unsubscribe, or null when cleanup succeeds.
        private final AtomicReference<@Nullable RuntimeException> nextHomeUnsubscribeFailure =
                new AtomicReference<>();

        /// Latch notified when the next explicitly blocked snapshot read begins.
        private final AtomicReference<@Nullable CountDownLatch> nextSnapshotEntered = new AtomicReference<>();

        /// Latch releasing the next explicitly blocked snapshot read.
        private final AtomicReference<@Nullable CountDownLatch> nextSnapshotRelease = new AtomicReference<>();

        /// Account-selection command count.
        private final AtomicInteger accountSelections = new AtomicInteger();

        /// Instance-selection command count.
        private final AtomicInteger instanceSelections = new AtomicInteger();

        /// New-instance command count.
        private final AtomicInteger instanceAdditions = new AtomicInteger();

        /// Launch command count.
        private final AtomicInteger launches = new AtomicInteger();

        /// Creates a fake model with initial state.
        ///
        /// @param initialSnapshot initial home state
        private FakeHomeModel(HomeSnapshot initialSnapshot) {
            current = new AtomicReference<>(initialSnapshot);
        }

        /// Returns the latest fake home state.
        @Override
        public HomeSnapshot snapshot() {
            @Nullable CountDownLatch entered = nextSnapshotEntered.getAndSet(null);
            if (entered != null) {
                CountDownLatch release = Objects.requireNonNull(
                        nextSnapshotRelease.getAndSet(null),
                        "blocked snapshot release latch");
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted while blocking a fake snapshot read", exception);
                }
            }
            return current.get();
        }

        /// Registers a fake home-state listener.
        @Override
        public Subscription subscribe(ValueChangeListener<HomeSnapshot> listener) {
            Subscription registration = changes.subscribe(listener);
            activeHomeSubscriptions.incrementAndGet();
            return Subscription.create(() -> {
                try {
                    registration.unsubscribe();
                } finally {
                    activeHomeSubscriptions.decrementAndGet();
                }
                @Nullable RuntimeException failure = nextHomeUnsubscribeFailure.getAndSet(null);
                if (failure != null) {
                    throw failure;
                }
            });
        }

        /// Returns the optional fake launch-session property.
        @Override
        public ReadOnlyProperty<Optional<LaunchSession>> launchSessionProperty() {
            return launchSession;
        }

        /// Records account-selection invocation.
        @Override
        public void selectAccount() {
            accountSelections.incrementAndGet();
        }

        /// Records instance-selection invocation.
        @Override
        public void selectInstance() {
            instanceSelections.incrementAndGet();
        }

        /// Records new-instance invocation.
        @Override
        public void addInstance() {
            instanceAdditions.incrementAndGet();
        }

        /// Records launch invocation.
        @Override
        public void launch() {
            launches.incrementAndGet();
        }

        /// Publishes one replacement snapshot on the calling thread.
        ///
        /// @param replacement new home state
        private void publish(HomeSnapshot replacement) {
            HomeSnapshot previous = current.getAndSet(replacement);
            changes.fireChange(previous, replacement);
        }

        /// Publishes a replacement launch-session identity on the calling thread.
        ///
        /// @param replacement replacement launch session
        private void publishLaunchSession(LaunchSession replacement) {
            launchSession.set(Optional.of(replacement));
        }

        /// Clears the current fake launch session.
        private void clearLaunchSession() {
            launchSession.set(Optional.empty());
        }

        /// Returns the number of active home-state registrations.
        ///
        /// @return active home registration count
        private int activeHomeSubscriptionCount() {
            return activeHomeSubscriptions.get();
        }

        /// Returns the number of active launch-session registrations.
        ///
        /// @return active launch-session registration count
        private int activeLaunchSubscriptionCount() {
            return launchSession.activeSubscriptionCount();
        }

        /// Configures the next home-registration cleanup to fail after releasing its listener.
        ///
        /// @param failure cleanup failure
        private void failNextHomeUnsubscribe(RuntimeException failure) {
            nextHomeUnsubscribeFailure.set(Objects.requireNonNull(failure, "failure"));
        }

        /// Configures the next launch-registration cleanup to fail after releasing its listener.
        ///
        /// @param failure cleanup failure
        private void failNextLaunchUnsubscribe(RuntimeException failure) {
            launchSession.failNextUnsubscribe(failure);
        }

        /// Configures the next snapshot read to expose and wait on deterministic test latches.
        ///
        /// @param entered latch notified when the read begins
        /// @param release latch allowing the read to finish
        private void blockNextSnapshotRead(CountDownLatch entered, CountDownLatch release) {
            nextSnapshotRelease.set(release);
            nextSnapshotEntered.set(entered);
        }
    }

    /// Minimal observable launch session used to exercise the home task card.
    @NotNullByDefault
    private static final class FakeLaunchSession implements LaunchSession {
        /// Stable request returned by the fake session.
        private final LaunchRequest request = new LaunchRequest("account", "directory", "instance");

        /// Observable launch lifecycle.
        private final SimpleObjectProperty<LaunchStatus> status =
                new SimpleObjectProperty<>(this, "status", LaunchStatus.PREPARING);

        /// Observable task-presentation transitions.
        private final ValueChangeSupport<TaskSnapshot> taskChanges = new ValueChangeSupport<>(this);

        /// Completion retained to satisfy the launch-session contract.
        private final CompletableFuture<ManagedProcess> completion = new CompletableFuture<>();

        /// Ensures cooperative cancellation is accepted at most once.
        private final AtomicBoolean cancellationAccepted = new AtomicBoolean();

        /// Latest task snapshot.
        private final AtomicReference<TaskSnapshot> taskSnapshot = new AtomicReference<>(new TaskSnapshot(
                "Launch Minecraft",
                "Preparing game",
                OptionalDouble.empty(),
                TaskStatus.RUNNING,
                true,
                ""));

        /// Failure thrown by the next task snapshot read, or null when reads should succeed.
        private final AtomicReference<@Nullable RuntimeException> nextSnapshotFailure = new AtomicReference<>();

        /// Failure thrown after the next task-listener cleanup, or null when cleanup succeeds.
        private final AtomicReference<@Nullable RuntimeException> nextTaskUnsubscribeFailure = new AtomicReference<>();

        /// Returns the stable fake launch request.
        @Override
        public LaunchRequest request() {
            return request;
        }

        /// Returns the current fake launch status.
        @Override
        public LaunchStatus status() {
            return Objects.requireNonNull(status.get(), "fake launch status");
        }

        /// Returns the observable fake launch status.
        @Override
        public ReadOnlyProperty<LaunchStatus> statusProperty() {
            return status;
        }

        /// Returns the uncompleted fake process stage.
        @Override
        public CompletionStage<ManagedProcess> completion() {
            return completion;
        }

        /// Returns no process because the fake does not create operating-system resources.
        @Override
        public Optional<ManagedProcess> createdProcess() {
            return Optional.empty();
        }

        /// Returns no failure for the successful fake path.
        @Override
        public Optional<Throwable> failure() {
            return Optional.empty();
        }

        /// Accepts cancellation once while preparation remains active.
        @Override
        public boolean cancel() {
            if (status() != LaunchStatus.PREPARING || !cancellationAccepted.compareAndSet(false, true)) {
                return false;
            }
            publishTerminal(LaunchStatus.CANCELLED, TaskStatus.CANCELLED, "Cancelled");
            completion.cancel(false);
            return true;
        }

        /// Returns the latest fake task snapshot.
        @Override
        public TaskSnapshot snapshot() {
            @Nullable RuntimeException failure = nextSnapshotFailure.getAndSet(null);
            if (failure != null) {
                throw failure;
            }
            return taskSnapshot.get();
        }

        /// Registers a fake task-presentation listener.
        @Override
        public Subscription subscribe(ValueChangeListener<TaskSnapshot> listener) {
            Subscription registration = taskChanges.subscribe(listener);
            return Subscription.create(() -> {
                registration.unsubscribe();
                @Nullable RuntimeException failure = nextTaskUnsubscribeFailure.getAndSet(null);
                if (failure != null) {
                    throw failure;
                }
            });
        }

        /// Delegates the presentation cancellation command to the launch contract.
        @Override
        public void requestCancellation() {
            cancel();
        }

        /// Completes the fake preparation without manufacturing a managed process.
        private void completeSuccessfully() {
            publishTerminal(LaunchStatus.PROCESS_CREATED, TaskStatus.SUCCEEDED, "Process created");
        }

        /// Configures the next task snapshot read to fail once.
        ///
        /// @param failure failure thrown by the next read
        private void failNextSnapshotRead(RuntimeException failure) {
            nextSnapshotFailure.set(Objects.requireNonNull(failure, "failure"));
        }

        /// Returns whether a task-presentation listener remains registered.
        ///
        /// @return `true` when a task panel still observes this session
        private boolean hasTaskSubscribers() {
            return taskChanges.hasSubscribers();
        }

        /// Configures the next task-listener cleanup to fail after releasing its registration.
        ///
        /// @param failure cleanup failure
        private void failNextTaskUnsubscribe(RuntimeException failure) {
            nextTaskUnsubscribeFailure.set(Objects.requireNonNull(failure, "failure"));
        }

        /// Publishes matching launch and task terminal states.
        ///
        /// @param launchStatus replacement launch status
        /// @param taskStatus replacement task status
        /// @param phase replacement task phase
        private void publishTerminal(LaunchStatus launchStatus, TaskStatus taskStatus, String phase) {
            status.set(launchStatus);
            TaskSnapshot replacement = new TaskSnapshot(
                    "Launch Minecraft",
                    phase,
                    OptionalDouble.empty(),
                    taskStatus,
                    false,
                    "");
            TaskSnapshot previous = taskSnapshot.getAndSet(replacement);
            taskChanges.fireChange(previous, replacement);
        }
    }

    /// Mutable property test double that counts active read-only registrations.
    ///
    /// @param <T> property value type
    @NotNullByDefault
    private static final class TrackingProperty<T> implements ReadOnlyProperty<T> {
        /// Mutable delegate supplying property semantics.
        private final SimpleObjectProperty<T> delegate;

        /// Number of registrations not yet unsubscribed.
        private final AtomicInteger activeSubscriptions = new AtomicInteger();

        /// Failure thrown after the next unsubscribe, or null when cleanup succeeds.
        private final AtomicReference<@Nullable RuntimeException> nextUnsubscribeFailure = new AtomicReference<>();

        /// Creates a tracking property with explicit metadata and value.
        ///
        /// @param bean property owner
        /// @param name property name
        /// @param initialValue initial property value
        private TrackingProperty(Object bean, String name, T initialValue) {
            delegate = new SimpleObjectProperty<>(bean, name, initialValue);
        }

        /// Returns the current delegated value.
        @Override
        public @Nullable T getValue() {
            return delegate.getValue();
        }

        /// Registers a listener and counts it until cleanup.
        @Override
        public Subscription subscribe(ValueChangeListener<T> listener) {
            Subscription registration = delegate.subscribe(listener);
            activeSubscriptions.incrementAndGet();
            return Subscription.create(() -> {
                try {
                    registration.unsubscribe();
                } finally {
                    activeSubscriptions.decrementAndGet();
                }
                @Nullable RuntimeException failure = nextUnsubscribeFailure.getAndSet(null);
                if (failure != null) {
                    throw failure;
                }
            });
        }

        /// Returns the delegated property owner.
        @Override
        public @Nullable Object getBean() {
            return delegate.getBean();
        }

        /// Returns the delegated property name.
        @Override
        public String getName() {
            return delegate.getName();
        }

        /// Replaces the delegated value.
        ///
        /// @param value replacement value
        private void set(T value) {
            delegate.set(value);
        }

        /// Returns the number of active registrations.
        ///
        /// @return active registration count
        private int activeSubscriptionCount() {
            return activeSubscriptions.get();
        }

        /// Configures the next registration cleanup to fail after releasing its listener.
        ///
        /// @param failure cleanup failure
        private void failNextUnsubscribe(RuntimeException failure) {
            nextUnsubscribeFailure.set(Objects.requireNonNull(failure, "failure"));
        }
    }
}
