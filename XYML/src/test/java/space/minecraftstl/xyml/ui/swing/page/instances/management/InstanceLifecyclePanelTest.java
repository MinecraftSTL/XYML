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

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies lifecycle controls schedule real service mutations off the EDT and return after success.
@NotNullByDefault
final class InstanceLifecyclePanelTest {
    /// Runs a rename in the background, reconciles the renamed selection, and returns to the list owner.
    @Test
    void renameRunsOffEdtAndReturnsAfterSelectionReconciliation() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        RecordingService service = new RecordingService();
        RecordingInteractions interactions = new RecordingInteractions();
        interactions.renameDestination.set("renamed");
        AtomicInteger completed = new AtomicInteger();
        AtomicReference<@Nullable InstanceLifecyclePanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> panelReference.set(new InstanceLifecyclePanel(
                    "source",
                    service,
                    executor,
                    InstanceLifecycleStrings.english(),
                    interactions,
                    completed::incrementAndGet)));
            InstanceLifecyclePanel panel = Objects.requireNonNull(panelReference.get());

            EdtDispatcher.executeAndWait(() -> {
                JButton rename = Objects.requireNonNull(
                        findNamed(panel, "instanceLifecycleRename", JButton.class),
                        "rename button");
                assertNotNull(rename);
                rename.doClick();
                assertFalse(rename.isEnabled());
            });
            awaitBackgroundWork(executor);

            assertEquals(new RenameCall("source", "renamed"), service.renameCall.get());
            assertFalse(service.mutationRanOnEdt.get());
            assertEquals("renamed", service.reconciledSelection.get());
            assertTrue(service.reconciledOnEdt.get());
            assertEquals(1, completed.get());
            assertNull(interactions.failureDetail.get());
        } finally {
            closePanel(panelReference.get());
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Propagates the native duplicate worlds choice to the background lifecycle service.
    @Test
    void duplicatePreservesWorldCopyChoice() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        RecordingService service = new RecordingService();
        RecordingInteractions interactions = new RecordingInteractions();
        interactions.duplicateRequest.set(new InstanceLifecycleDuplicateRequest("copy", true));
        AtomicInteger completed = new AtomicInteger();
        AtomicReference<@Nullable InstanceLifecyclePanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> panelReference.set(new InstanceLifecyclePanel(
                    "source",
                    service,
                    executor,
                    InstanceLifecycleStrings.english(),
                    interactions,
                    completed::incrementAndGet)));
            InstanceLifecyclePanel panel = Objects.requireNonNull(panelReference.get());

            EdtDispatcher.executeAndWait(() -> {
                JButton duplicate = Objects.requireNonNull(
                        findNamed(panel, "instanceLifecycleDuplicate", JButton.class),
                        "duplicate button");
                assertNotNull(duplicate);
                duplicate.doClick();
            });
            awaitBackgroundWork(executor);

            assertEquals(new DuplicateCall("source", "copy", true), service.duplicateCall.get());
            assertFalse(service.mutationRanOnEdt.get());
            assertEquals("copy", service.reconciledSelection.get());
            assertEquals(1, completed.get());
        } finally {
            closePanel(panelReference.get());
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Requires confirmation for deletion and only schedules repository removal after it is granted.
    @Test
    void deleteRequiresConfirmationBeforeBackgroundRemoval() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        RecordingService service = new RecordingService();
        RecordingInteractions interactions = new RecordingInteractions();
        AtomicInteger completed = new AtomicInteger();
        AtomicReference<@Nullable InstanceLifecyclePanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> panelReference.set(new InstanceLifecyclePanel(
                    "source",
                    service,
                    executor,
                    InstanceLifecycleStrings.english(),
                    interactions,
                    completed::incrementAndGet)));
            InstanceLifecyclePanel panel = Objects.requireNonNull(panelReference.get());

            EdtDispatcher.executeAndWait(() -> {
                JButton delete = Objects.requireNonNull(
                        findNamed(panel, "instanceLifecycleDelete", JButton.class),
                        "delete button");
                assertNotNull(delete);
                delete.doClick();
            });
            awaitBackgroundWork(executor);
            assertEquals(0, service.deleteCount.get());
            assertEquals(0, completed.get());

            interactions.deleteApproved.set(true);
            EdtDispatcher.executeAndWait(() -> {
                JButton delete = Objects.requireNonNull(
                        findNamed(panel, "instanceLifecycleDelete", JButton.class),
                        "delete button");
                assertNotNull(delete);
                delete.doClick();
            });
            awaitBackgroundWork(executor);

            assertEquals(1, service.deleteCount.get());
            assertFalse(service.mutationRanOnEdt.get());
            assertNull(service.reconciledSelection.get());
            assertEquals(1, completed.get());
        } finally {
            closePanel(panelReference.get());
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Rejects an invalid dialog destination before any background filesystem mutation can be submitted.
    @Test
    void invalidDestinationShowsFailureWithoutSubmittingMutation() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        RecordingService service = new RecordingService();
        RecordingInteractions interactions = new RecordingInteractions();
        interactions.renameDestination.set("invalid");
        AtomicReference<@Nullable InstanceLifecyclePanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> panelReference.set(new InstanceLifecyclePanel(
                    "source",
                    service,
                    executor,
                    InstanceLifecycleStrings.english(),
                    interactions,
                    () -> { })));
            InstanceLifecyclePanel panel = Objects.requireNonNull(panelReference.get());

            EdtDispatcher.executeAndWait(() -> {
                JButton rename = Objects.requireNonNull(
                        findNamed(panel, "instanceLifecycleRename", JButton.class),
                        "rename button");
                assertNotNull(rename);
                rename.doClick();
            });
            awaitBackgroundWork(executor);

            assertNull(service.renameCall.get());
            assertEquals(InstanceLifecycleStrings.english().renameFailure(), interactions.failureDetail.get());
        } finally {
            closePanel(panelReference.get());
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Waits for a FIFO executor barrier and every EDT callback posted before that barrier.
    ///
    /// @param executor lifecycle background executor
    /// @throws Exception when the barrier cannot complete
    private static void awaitBackgroundWork(ExecutorService executor) throws Exception {
        executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
        EdtDispatcher.executeAndWait(() -> { });
    }

    /// Closes a panel from any test cleanup thread when construction completed.
    ///
    /// @param panel panel to close, or `null` after construction failure
    private static void closePanel(@Nullable InstanceLifecyclePanel panel) {
        if (panel != null) {
            panel.close();
        }
    }

    /// Finds a named descendant of one exact Swing component type.
    ///
    /// @param root component tree root
    /// @param name stable component name
    /// @param type required component type
    /// @param <T> component type
    /// @return matching component, or `null` when absent
    private static <T extends JComponent> @Nullable T findNamed(
            Container root,
            String name,
            Class<T> type) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component) && name.equals(component.getName())) {
                return type.cast(component);
            }
            if (component instanceof Container child) {
                @Nullable T nested = findNamed(child, name, type);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    /// Test service recording lifecycle calls without touching a repository or filesystem.
    @NotNullByDefault
    private static final class RecordingService implements InstanceLifecycleService {
        /// Recorded rename request, or `null` before a rename.
        private final AtomicReference<@Nullable RenameCall> renameCall = new AtomicReference<>();

        /// Recorded duplicate request, or `null` before duplication.
        private final AtomicReference<@Nullable DuplicateCall> duplicateCall = new AtomicReference<>();

        /// Number of deletion requests.
        private final AtomicInteger deleteCount = new AtomicInteger();

        /// Whether a blocking mutation accidentally ran on the Swing EDT.
        private final AtomicBoolean mutationRanOnEdt = new AtomicBoolean();

        /// Reconciled preferred selection, or `null` after deletion.
        private final AtomicReference<@Nullable String> reconciledSelection = new AtomicReference<>();

        /// Whether selection reconciliation correctly returned to the EDT.
        private final AtomicBoolean reconciledOnEdt = new AtomicBoolean();

        /// Treats a single known test destination as invalid.
        ///
        /// @param destinationId candidate destination identifier
        /// @return whether the candidate is allowed
        @Override
        public boolean isValidDestinationId(String destinationId) {
            return !"invalid".equals(destinationId);
        }

        /// Records one background rename request.
        ///
        /// @param sourceId source identifier
        /// @param destinationId destination identifier
        /// @throws IOException never thrown by this deterministic test implementation
        @Override
        public void rename(String sourceId, String destinationId) throws IOException {
            mutationRanOnEdt.compareAndSet(false, SwingUtilities.isEventDispatchThread());
            renameCall.set(new RenameCall(sourceId, destinationId));
        }

        /// Records one background duplicate request.
        ///
        /// @param sourceId source identifier
        /// @param destinationId destination identifier
        /// @param copySaves whether worlds should be copied
        /// @throws IOException never thrown by this deterministic test implementation
        @Override
        public void duplicate(String sourceId, String destinationId, boolean copySaves) throws IOException {
            mutationRanOnEdt.compareAndSet(false, SwingUtilities.isEventDispatchThread());
            duplicateCall.set(new DuplicateCall(sourceId, destinationId, copySaves));
        }

        /// Records one background deletion request.
        ///
        /// @param sourceId source identifier
        /// @throws IOException never thrown by this deterministic test implementation
        @Override
        public void delete(String sourceId) throws IOException {
            mutationRanOnEdt.compareAndSet(false, SwingUtilities.isEventDispatchThread());
            deleteCount.incrementAndGet();
        }

        /// Records selection reconciliation and asserts it occurs on the EDT.
        ///
        /// @param preferredId preferred selection, or `null` after deletion
        @Override
        public void reconcileSelection(@Nullable String preferredId) {
            reconciledOnEdt.set(SwingUtilities.isEventDispatchThread());
            reconciledSelection.set(preferredId);
        }
    }

    /// Test native interaction substitute returning preconfigured choices without opening dialogs.
    @NotNullByDefault
    private static final class RecordingInteractions implements InstanceLifecycleInteractions {
        /// Requested rename destination, or `null` to simulate cancellation.
        private final AtomicReference<@Nullable String> renameDestination = new AtomicReference<>();

        /// Requested duplicate data, or `null` to simulate cancellation.
        private final AtomicReference<@Nullable InstanceLifecycleDuplicateRequest> duplicateRequest = new AtomicReference<>();

        /// Whether deletion should be approved.
        private final AtomicBoolean deleteApproved = new AtomicBoolean();

        /// Latest shown failure detail, or `null` when no failure was shown.
        private final AtomicReference<@Nullable String> failureDetail = new AtomicReference<>();

        /// Returns the configured rename destination.
        ///
        /// @param owner unused dialog owner
        /// @param sourceId unused source identifier
        /// @return configured destination, or `null`
        @Override
        public @Nullable String requestRename(Component owner, String sourceId) {
            return renameDestination.get();
        }

        /// Returns the configured duplicate request.
        ///
        /// @param owner unused dialog owner
        /// @param sourceId unused source identifier
        /// @return configured duplicate request, or `null`
        @Override
        public @Nullable InstanceLifecycleDuplicateRequest requestDuplicate(Component owner, String sourceId) {
            return duplicateRequest.get();
        }

        /// Returns the configured deletion approval.
        ///
        /// @param owner unused dialog owner
        /// @param sourceId unused source identifier
        /// @return configured deletion approval
        @Override
        public boolean confirmDelete(Component owner, String sourceId) {
            return deleteApproved.get();
        }

        /// Records one failure detail rather than opening a native dialog.
        ///
        /// @param owner unused dialog owner
        /// @param title unused failure title
        /// @param detail failure detail
        @Override
        public void showFailure(Component owner, String title, String detail) {
            failureDetail.set(detail);
        }
    }

    /// Immutable recorded rename invocation.
    ///
    /// @param sourceId source identifier
    /// @param destinationId destination identifier
    @NotNullByDefault
    private record RenameCall(String sourceId, String destinationId) {
    }

    /// Immutable recorded duplicate invocation.
    ///
    /// @param sourceId source identifier
    /// @param destinationId destination identifier
    /// @param copySaves whether worlds are copied
    @NotNullByDefault
    private record DuplicateCall(String sourceId, String destinationId, boolean copySaves) {
    }
}
