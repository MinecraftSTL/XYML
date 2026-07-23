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
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.page.schematics.SchematicBrowserPanel;
import space.minecraftstl.xyml.ui.swing.page.schematics.SchematicBrowserStrings;
import space.minecraftstl.xyml.ui.swing.page.schematics.SchematicMetadataStrings;

import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests schematic instance-management resolution, retry, return, and closure boundaries.
@NotNullByDefault
public final class SchematicInstanceManagementViewTest {
    /// Localized outer-shell text used by focused tests.
    private static final SchematicInstanceManagementStrings MANAGEMENT_STRINGS =
            new SchematicInstanceManagementStrings(
                    "Instances",
                    "Return to instances",
                    "Resolving schematic directory",
                    "Unable to resolve schematic directory",
                    "Retry resolution");

    /// Localized browser text used by focused tests.
    private static final SchematicBrowserStrings BROWSER_STRINGS = new SchematicBrowserStrings(
            "Schematics",
            "Up",
            "Return to parent directory",
            "Refresh",
            "Refreshing",
            "Refresh current directory",
            "Open",
            "Open selected directory",
            "Not loaded",
            "Loading schematics",
            "No schematics",
            "Unable to load schematics",
            "Retry",
            "Details",
            "Select a schematic",
            "Directory",
            "Unreadable schematic",
            "[Directory] ",
            new SchematicMetadataStrings(
                    "Path",
                    "Name",
                    "Author",
                    "Description",
                    "Created",
                    "Modified",
                    "Regions",
                    "Volume",
                    "Blocks",
                    "Size",
                    "Format version",
                    "Minecraft data version",
                    "Preview",
                    "Unknown",
                    "%d x %d x %d",
                    "%d x %d pixels; rendering deferred",
                    "%d pixels; rendering deferred",
                    "Unavailable"));

    /// Loading is installed first, resolution runs through the executor, and return stays outer-level.
    @Test
    public void resolvesOffEdtThenBuildsBrowserAndRunsReturnCommandOnEdt() {
        QueuedExecutor executor = new QueuedExecutor();
        AtomicBoolean resolutionOnEdt = new AtomicBoolean(true);
        AtomicBoolean returnOnEdt = new AtomicBoolean();
        AtomicInteger returnCalls = new AtomicInteger();
        Path resolvedRoot = Path.of("resolved", "schematics").toAbsolutePath().normalize();

        SchematicInstanceManagementView view = onEventDispatchThread(() ->
                new SchematicInstanceManagementView(
                        "alpha",
                        instanceId -> {
                            resolutionOnEdt.set(SwingUtilities.isEventDispatchThread());
                            assertEquals("alpha", instanceId);
                            return resolvedRoot;
                        },
                        executor,
                        MANAGEMENT_STRINGS,
                        BROWSER_STRINGS,
                        () -> {
                            returnOnEdt.set(SwingUtilities.isEventDispatchThread());
                            returnCalls.incrementAndGet();
                        }));

        onEventDispatchThread(() -> assertAll(
                () -> assertEquals("alpha", view.instanceId()),
                () -> assertSame(view, view.component()),
                () -> assertTrue(findComponent(view, "schematicInstanceLoading").isVisible()),
                () -> assertNull(findOptionalComponent(view, "schematicInstanceBrowser")),
                () -> assertEquals("Instances", findButton(view, "schematicInstanceReturn").getText()),
                () -> assertEquals(
                        "Return to instances",
                        findButton(view, "schematicInstanceReturn").getToolTipText())));
        assertEquals(1, executor.pendingCount());

        executor.runNext();
        flushEventDispatchThread();

        onEventDispatchThread(() -> {
            Component browser = findComponent(view, "schematicInstanceBrowser");
            assertAll(
                    () -> assertInstanceOf(SchematicBrowserPanel.class, browser),
                    () -> assertTrue(browser.isVisible()),
                    () -> assertFalse(resolutionOnEdt.get()));
            findButton(view, "schematicInstanceReturn").doClick();
        });
        assertAll(
                () -> assertEquals(1, returnCalls.get()),
                () -> assertTrue(returnOnEdt.get()));

        view.close();
    }

    /// A failed root lookup publishes injected diagnostics and a retry can create the browser.
    @Test
    public void retriesFailedDirectoryResolution() {
        QueuedExecutor executor = new QueuedExecutor();
        AtomicInteger attempts = new AtomicInteger();
        Path resolvedRoot = Path.of("retry", "schematics").toAbsolutePath().normalize();
        SchematicInstanceManagementView view = onEventDispatchThread(() ->
                new SchematicInstanceManagementView(
                        "beta",
                        instanceId -> {
                            if (attempts.incrementAndGet() == 1) {
                                throw new IOException("repository unavailable");
                            }
                            return resolvedRoot;
                        },
                        executor,
                        MANAGEMENT_STRINGS,
                        BROWSER_STRINGS,
                        () -> { }));

        executor.runNext();
        flushEventDispatchThread();

        onEventDispatchThread(() -> {
            JLabel failure = assertInstanceOf(
                    JLabel.class,
                    findComponent(view, "schematicInstanceFailure"));
            AbstractButton retry = findButton(view, "schematicInstanceRetry");
            assertAll(
                    () -> assertTrue(failure.isVisible()),
                    () -> assertEquals(
                            "Unable to resolve schematic directory: repository unavailable",
                            failure.getText()),
                    () -> assertTrue(retry.isEnabled()),
                    () -> assertNull(findOptionalComponent(view, "schematicInstanceBrowser")));
            retry.doClick();
            assertAll(
                    () -> assertTrue(findComponent(view, "schematicInstanceLoading").isVisible()),
                    () -> assertFalse(retry.isEnabled()));
        });
        assertEquals(1, executor.pendingCount());

        executor.runNext();
        flushEventDispatchThread();

        onEventDispatchThread(() -> assertTrue(
                findComponent(view, "schematicInstanceBrowser").isVisible()));
        assertEquals(2, attempts.get());
        view.close();
    }

    /// Worker-thread close waits for EDT cleanup, closes the browser, and remains idempotent.
    @Test
    public void closesSynchronouslyAcrossThreadsAndIsIdempotent() throws Exception {
        QueuedExecutor executor = new QueuedExecutor();
        SchematicInstanceManagementView view = onEventDispatchThread(() ->
                new SchematicInstanceManagementView(
                        "gamma",
                        instanceId -> Path.of("gamma", "schematics"),
                        executor,
                        MANAGEMENT_STRINGS,
                        BROWSER_STRINGS,
                        () -> { }));
        executor.runNext();
        flushEventDispatchThread();
        SchematicBrowserPanel browser = onEventDispatchThread(() -> assertInstanceOf(
                SchematicBrowserPanel.class,
                findComponent(view, "schematicInstanceBrowser")));
        AtomicReference<@Nullable Throwable> closeFailure = new AtomicReference<>();

        Thread firstCloser = new Thread(() -> closeCapturing(view, closeFailure), "schematic-view-close-1");
        firstCloser.start();
        firstCloser.join();

        onEventDispatchThread(() -> assertAll(
                () -> assertNull(closeFailure.get()),
                () -> assertNull(findOptionalComponent(view, "schematicInstanceBrowser")),
                () -> assertFalse(findButton(view, "schematicInstanceReturn").isEnabled()),
                () -> assertFalse(findButton(browser, "schematicsRefresh").isEnabled())));

        Thread secondCloser = new Thread(() -> closeCapturing(view, closeFailure), "schematic-view-close-2");
        secondCloser.start();
        secondCloser.join();
        assertNull(closeFailure.get());
    }

    /// Close does not await a blocking resolver and prevents its eventual result from constructing UI.
    @Test
    public void closeCancelsPublicationFromAnAlreadyRunningResolution() throws Exception {
        CountDownLatch resolutionEntered = new CountDownLatch(1);
        CountDownLatch releaseResolution = new CountDownLatch(1);
        CountDownLatch closeReturned = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<@Nullable Throwable> closeFailure = new AtomicReference<>();
        AtomicBoolean resolutionOnEdt = new AtomicBoolean(true);
        SchematicInstanceManagementView view = onEventDispatchThread(() ->
                new SchematicInstanceManagementView(
                        "delta",
                        instanceId -> {
                            resolutionOnEdt.set(SwingUtilities.isEventDispatchThread());
                            resolutionEntered.countDown();
                            awaitUninterruptibly(releaseResolution);
                            return Path.of("delta", "schematics");
                        },
                        executor,
                        MANAGEMENT_STRINGS,
                        BROWSER_STRINGS,
                        () -> { }));

        try {
            assertTrue(resolutionEntered.await(5, TimeUnit.SECONDS));
            Thread closer = new Thread(() -> {
                closeCapturing(view, closeFailure);
                closeReturned.countDown();
            }, "schematic-view-blocked-resolution-close");
            closer.start();

            assertTrue(closeReturned.await(5, TimeUnit.SECONDS));
            closer.join();
            onEventDispatchThread(() -> assertAll(
                    () -> assertNull(closeFailure.get()),
                    () -> assertFalse(resolutionOnEdt.get()),
                    () -> assertFalse(findButton(view, "schematicInstanceReturn").isEnabled()),
                    () -> assertNull(findOptionalComponent(view, "schematicInstanceBrowser"))));

            releaseResolution.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            flushEventDispatchThread();

            onEventDispatchThread(() -> assertNull(
                    findOptionalComponent(view, "schematicInstanceBrowser")));
            view.close();
        } finally {
            releaseResolution.countDown();
            executor.shutdownNow();
        }
    }

    /// Runs close while retaining any unchecked failure for assertions on the test thread.
    ///
    /// @param view view to close
    /// @param failure captured close failure
    private static void closeCapturing(
            SchematicInstanceManagementView view,
            AtomicReference<@Nullable Throwable> failure) {
        try {
            view.close();
        } catch (RuntimeException | Error closeFailure) {
            failure.compareAndSet(null, closeFailure);
        }
    }

    /// Waits for a latch while restoring interruption after the controlled operation completes.
    ///
    /// @param latch latch controlling completion
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

    /// Finds one named button in a Swing hierarchy.
    ///
    /// @param root hierarchy root
    /// @param name stable component name
    /// @return matching button
    private static AbstractButton findButton(Container root, String name) {
        Component component = findComponent(root, name);
        if (component instanceof AbstractButton button) {
            return button;
        }
        throw new IllegalArgumentException("Named component is not a button: " + name);
    }

    /// Finds one named component recursively.
    ///
    /// @param root hierarchy root
    /// @param name stable component name
    /// @return matching component
    private static Component findComponent(Container root, String name) {
        @Nullable Component component = findOptionalComponent(root, name);
        if (component == null) {
            throw new IllegalArgumentException("Missing component: " + name);
        }
        return component;
    }

    /// Finds one optional named component recursively.
    ///
    /// @param root hierarchy root
    /// @param name stable component name
    /// @return matching component, or null when absent
    private static @Nullable Component findOptionalComponent(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (Objects.equals(name, child.getName())) {
                return child;
            }
            if (child instanceof Container nested) {
                @Nullable Component match = findOptionalComponent(nested, name);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    /// Flushes callbacks queued for the EDT.
    private static void flushEventDispatchThread() {
        EdtDispatcher.executeAndWait(() -> { });
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

    /// Runs one operation synchronously on the EDT.
    ///
    /// @param operation operation to run
    private static void onEventDispatchThread(Runnable operation) {
        EdtDispatcher.executeAndWait(operation);
    }

    /// Deterministic caller-owned executor that runs submitted work only when requested.
    @NotNullByDefault
    private static final class QueuedExecutor implements Executor {
        /// Pending tasks in submission order.
        private final Deque<Runnable> tasks = new ArrayDeque<>();

        /// Enqueues work without running it.
        ///
        /// @param command submitted work
        @Override
        public synchronized void execute(Runnable command) {
            tasks.addLast(command);
        }

        /// Runs the oldest queued task on the calling test thread.
        private void runNext() {
            Runnable task;
            synchronized (this) {
                task = tasks.removeFirst();
            }
            task.run();
        }

        /// Returns the number of queued tasks.
        ///
        /// @return pending task count
        private synchronized int pendingCount() {
            return tasks.size();
        }
    }
}
