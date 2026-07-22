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
package space.minecraftstl.xyml.ui.swing.task;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests task progress rendering and worker-to-EDT state delivery without creating a native window.
@NotNullByDefault
public final class TaskProgressPanelTest {
    /// Verifies that a worker-published snapshot becomes the final state rendered on the EDT.
    @Test
    public void appliesBackgroundThreadUpdatesOnEventDispatchThread() throws InterruptedException {
        FakeTaskPresentationModel model = new FakeTaskPresentationModel(new TaskSnapshot(
                "Prepare game",
                "Waiting for files",
                OptionalDouble.empty(),
                TaskStatus.WAITING,
                true,
                ""));
        TaskProgressPanel panel = onEventDispatchThread(() -> new TaskProgressPanel(model));
        TaskSnapshot runningSnapshot = new TaskSnapshot(
                "Prepare game",
                "Checking libraries",
                OptionalDouble.of(0.42),
                TaskStatus.RUNNING,
                true,
                "");

        Thread publisher = new Thread(() -> model.publish(runningSnapshot), "task-progress-test-publisher");
        publisher.start();
        publisher.join();
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> {
            assertAll(
                    () -> assertSame(runningSnapshot, panel.getDisplayedSnapshot()),
                    () -> assertFalse(panel.isProgressIndeterminate()),
                    () -> assertEquals(0.42, panel.getDisplayedProgress(), 0.001),
                    () -> assertTrue(panel.isCancellationActionVisible()),
                    () -> assertTrue(panel.isCancellationActionEnabled()));
            panel.close();
        });
    }

    /// Verifies that repeated panel commands invoke the model cancellation entry point only once.
    @Test
    public void requestsCancellationOnlyOnce() {
        FakeTaskPresentationModel model = new FakeTaskPresentationModel(new TaskSnapshot(
                "Download files",
                "Connecting",
                OptionalDouble.empty(),
                TaskStatus.RUNNING,
                true,
                ""));
        TaskProgressPanel panel = onEventDispatchThread(() -> new TaskProgressPanel(model));

        onEventDispatchThread(() -> {
            panel.requestCancellation();
            panel.requestCancellation();

            assertAll(
                    () -> assertEquals(1, model.cancellationInvocationCount()),
                    () -> assertTrue(model.wasCancellationAccepted()),
                    () -> assertFalse(panel.isCancellationActionVisible()),
                    () -> assertFalse(panel.isCancellationActionEnabled()));
            panel.close();
        });
    }

    /// Verifies indeterminate work, successful completion, and expandable failure details.
    @Test
    public void representsIndeterminateSuccessAndFailureStates() {
        FakeTaskPresentationModel successModel = new FakeTaskPresentationModel(new TaskSnapshot(
                "Install game",
                "Resolving version",
                OptionalDouble.empty(),
                TaskStatus.RUNNING,
                false,
                ""));
        TaskProgressPanel successPanel = onEventDispatchThread(() -> new TaskProgressPanel(successModel));

        onEventDispatchThread(() -> assertTrue(successPanel.isProgressIndeterminate()));
        successModel.publish(new TaskSnapshot(
                "Install game",
                "Ready",
                OptionalDouble.empty(),
                TaskStatus.SUCCEEDED,
                false,
                ""));
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> {
            assertAll(
                    () -> assertFalse(successPanel.isProgressIndeterminate()),
                    () -> assertEquals(1.0, successPanel.getDisplayedProgress()),
                    () -> assertEquals(TaskStatus.SUCCEEDED, successPanel.getDisplayedSnapshot().status()));
            successPanel.close();
        });

        FakeTaskPresentationModel failureModel = new FakeTaskPresentationModel(new TaskSnapshot(
                "Install game",
                "Library verification failed",
                OptionalDouble.empty(),
                TaskStatus.FAILED,
                false,
                "Expected checksum abc, received def"));
        TaskProgressPanel failurePanel = onEventDispatchThread(() -> new TaskProgressPanel(failureModel));

        onEventDispatchThread(() -> {
            assertFalse(failurePanel.isProgressIndeterminate());
            assertFalse(failurePanel.isDetailsExpanded());

            failurePanel.setDetailsExpanded(true);

            assertAll(
                    () -> assertTrue(failurePanel.isDetailsExpanded()),
                    () -> assertEquals(
                            "Expected checksum abc, received def",
                            failurePanel.getDisplayedDetails()),
                    () -> assertEquals(TaskStatus.FAILED, failurePanel.getDisplayedSnapshot().status()));
            failurePanel.close();
        });
    }

    /// Verifies that the complete component hierarchy paints visible pixels into an off-screen image.
    @Test
    public void paintsNonBlankSurfaceWithoutDisplay() {
        FakeTaskPresentationModel model = new FakeTaskPresentationModel(new TaskSnapshot(
                "Launch Minecraft",
                "Starting process",
                OptionalDouble.of(0.65),
                TaskStatus.RUNNING,
                true,
                "Process command is available for diagnostics"));
        TaskProgressPanel panel = onEventDispatchThread(() -> new TaskProgressPanel(model));

        BufferedImage image = onEventDispatchThread(() -> {
            Dimension size = panel.getPreferredSize();
            panel.setSize(size);
            layoutRecursively(panel);

            BufferedImage rendered = new BufferedImage(
                    size.width,
                    size.height,
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = rendered.createGraphics();
            try {
                panel.printAll(graphics);
            } finally {
                graphics.dispose();
            }
            panel.close();
            return rendered;
        });

        Set<Integer> paintedColors = distinctColors(image);
        assertTrue(paintedColors.size() > 1, "the off-screen task surface should contain visible structure");
    }

    /// Executes a value-producing operation synchronously on the Swing event dispatch thread.
    ///
    /// @param operation the operation to execute
    /// @param <T> the non-null result type
    /// @return the operation result
    private static <T extends Object> T onEventDispatchThread(Supplier<T> operation) {
        AtomicReference<@Nullable T> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> result.set(operation.get()));
        return java.util.Objects.requireNonNull(result.get(), "EDT operation did not provide a result");
    }

    /// Executes an operation synchronously on the Swing event dispatch thread.
    ///
    /// @param operation the operation to execute
    private static void onEventDispatchThread(Runnable operation) {
        EdtDispatcher.executeAndWait(operation);
    }

    /// Lays out a component tree before off-screen rendering.
    ///
    /// @param container the root container to lay out
    private static void layoutRecursively(Container container) {
        container.doLayout();
        for (Component component : container.getComponents()) {
            if (component instanceof Container childContainer) {
                layoutRecursively(childContainer);
            }
        }
    }

    /// Collects all pixel colors painted into an image.
    ///
    /// @param image the rendered task panel image
    /// @return the mutable set of distinct pixel colors
    private static Set<Integer> distinctColors(BufferedImage image) {
        Set<Integer> colors = new HashSet<>();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                colors.add(image.getRGB(x, y));
            }
        }
        return colors;
    }

    /// Thread-safe fake presentation model used to exercise worker publication and cancellation behavior.
    @NotNullByDefault
    private static final class FakeTaskPresentationModel implements TaskPresentationModel {
        /// Most recently published immutable snapshot.
        private final AtomicReference<TaskSnapshot> currentSnapshot;

        /// Change publisher that preserves the worker thread used by each test.
        private final ValueChangeSupport<TaskSnapshot> changes = new ValueChangeSupport<>(this);

        /// Number of raw calls made to the cancellation entry point.
        private final AtomicInteger cancellationInvocations = new AtomicInteger();

        /// Whether the fake task accepted its first cancellation request.
        private final AtomicBoolean cancellationAccepted = new AtomicBoolean();

        /// Creates a fake model with an initial snapshot.
        ///
        /// @param initialSnapshot the initial immutable state
        private FakeTaskPresentationModel(TaskSnapshot initialSnapshot) {
            currentSnapshot = new AtomicReference<>(initialSnapshot);
        }

        /// Returns the latest fake task state.
        ///
        /// @return the latest snapshot
        @Override
        public TaskSnapshot snapshot() {
            return currentSnapshot.get();
        }

        /// Registers a fake task state listener.
        ///
        /// @param listener the transition listener
        /// @return the listener subscription
        @Override
        public Subscription subscribe(ValueChangeListener<TaskSnapshot> listener) {
            return changes.subscribe(listener);
        }

        /// Records every invocation while accepting cancellation only once.
        @Override
        public void requestCancellation() {
            cancellationInvocations.incrementAndGet();
            cancellationAccepted.compareAndSet(false, true);
        }

        /// Publishes a replacement snapshot on the calling thread.
        ///
        /// @param snapshot the new immutable state
        private void publish(TaskSnapshot snapshot) {
            TaskSnapshot previousSnapshot = currentSnapshot.getAndSet(snapshot);
            changes.fireChange(previousSnapshot, snapshot);
        }

        /// Returns how often the panel invoked the cancellation entry point.
        ///
        /// @return raw cancellation invocation count
        private int cancellationInvocationCount() {
            return cancellationInvocations.get();
        }

        /// Returns whether the first cancellation request was accepted.
        ///
        /// @return `true` after cancellation is accepted
        private boolean wasCancellationAccepted() {
            return cancellationAccepted.get();
        }
    }
}
