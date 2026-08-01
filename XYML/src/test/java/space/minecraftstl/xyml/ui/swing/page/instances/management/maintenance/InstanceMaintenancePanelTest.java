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
package space.minecraftstl.xyml.ui.swing.page.instances.management.maintenance;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.launch.LaunchSession;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;

import javax.swing.JButton;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies lazy maintenance state, destructive confirmations, local archive handoff, and close isolation.
@NotNullByDefault
final class InstanceMaintenancePanelTest {
    /// Fixed instance identifier used by every focused page scenario.
    private static final GameInstanceID INSTANCE_ID = new GameInstanceID("instance");

    /// Loads no state during construction and maps local presence to precise command availability.
    @Test
    void activationIsLazyAndMapsSnapshotAvailability() {
        RecordingService service = new RecordingService();
        InstanceMaintenancePanel panel = createPanel(service, new RecordingInteractions(), new RecordingLaunchActions());

        EdtDispatcher.executeAndWait(() -> {
            assertEquals(0, service.loadCalls.get());
            panel.activate();
            assertEquals(1, service.loadCalls.get());
            assertNull(panel.displayedSnapshot());
        });
        InstanceMaintenanceSnapshot snapshot = new InstanceMaintenanceSnapshot(
                INSTANCE_ID,
                false,
                true,
                false,
                true);
        service.snapshotCompletion.complete(snapshot);

        EdtDispatcher.executeAndWait(() -> {
            assertSame(snapshot, panel.displayedSnapshot());
            assertFalse(button(panel, "instanceMaintenanceUpdateModpack").isEnabled());
            assertTrue(button(panel, "instanceMaintenanceRemoveAssets").isEnabled());
            assertFalse(button(panel, "instanceMaintenanceRemoveLibraries").isEnabled());
            assertTrue(button(panel, "instanceMaintenanceCleanGenerated").isEnabled());
            panel.close();
        });
    }

    /// Requires explicit shared-data confirmation and applies the task's authoritative resulting snapshot.
    @Test
    void sharedAssetRemovalRequiresConfirmationAndUpdatesAvailability() {
        RecordingService service = new RecordingService();
        RecordingInteractions interactions = new RecordingInteractions();
        InstanceMaintenancePanel panel = createPanel(service, interactions, new RecordingLaunchActions());
        activateWithSnapshot(panel, service, new InstanceMaintenanceSnapshot(
                INSTANCE_ID,
                true,
                true,
                true,
                true));
        service.mutationResult = new InstanceMaintenanceSnapshot(
                INSTANCE_ID,
                true,
                false,
                true,
                true);

        EdtDispatcher.executeAndWait(() -> {
            JButton removeAssets = button(panel, "instanceMaintenanceRemoveAssets");
            removeAssets.doClick();
            assertEquals(1, interactions.confirmCalls.get());
            assertTrue(interactions.lastSharedScope.get());
            assertEquals(0, service.removeAssetsCalls.get());

            interactions.destructiveApproved.set(true);
            removeAssets.doClick();
        });
        waitFor(() -> service.removeAssetsCalls.get() == 1);
        waitFor(() -> readAssetsPresent(panel) == Boolean.FALSE);

        EdtDispatcher.executeAndWait(() -> {
            assertEquals(1, service.removeAssetsCalls.get());
            assertFalse(button(panel, "instanceMaintenanceRemoveAssets").isEnabled());
            assertFalse(Objects.requireNonNull(panel.displayedSnapshot()).assetsPresent());
            panel.close();
        });
    }

    /// Passes the exact selected archive and UTF-8 entry-name policy to the provider-validated update task.
    @Test
    void modpackUpdateUsesSelectedArchiveAndUtf8Charset() {
        RecordingService service = new RecordingService();
        RecordingInteractions interactions = new RecordingInteractions();
        Path archive = Path.of("build", "maintenance-test", "update.mrpack");
        interactions.modpackArchive.set(archive);
        InstanceMaintenancePanel panel = createPanel(service, interactions, new RecordingLaunchActions());
        InstanceMaintenanceSnapshot snapshot = new InstanceMaintenanceSnapshot(
                INSTANCE_ID,
                true,
                false,
                false,
                false);
        service.mutationResult = snapshot;
        activateWithSnapshot(panel, service, snapshot);

        EdtDispatcher.executeAndWait(() -> button(panel, "instanceMaintenanceUpdateModpack").doClick());
        waitFor(() -> service.updateCalls.get() == 1);

        assertEquals(archive, service.updateArchive.get());
        assertEquals(Charset.forName("UTF-8"), service.updateCharset.get());
        EdtDispatcher.executeAndWait(panel::close);
    }

    /// Passes the exact validated remote source to the remote modpack update task.
    @Test
    void modpackUpdateUsesSelectedRemoteUri() {
        RecordingService service = new RecordingService();
        RecordingInteractions interactions = new RecordingInteractions();
        URI source = URI.create("https://example.invalid/server-manifest.json");
        interactions.modpackUri.set(source);
        InstanceMaintenancePanel panel = createPanel(service, interactions, new RecordingLaunchActions());
        InstanceMaintenanceSnapshot snapshot = new InstanceMaintenanceSnapshot(
                INSTANCE_ID,
                true,
                false,
                false,
                false);
        service.mutationResult = snapshot;
        activateWithSnapshot(panel, service, snapshot);

        EdtDispatcher.executeAndWait(() -> button(panel, "instanceMaintenanceUpdateModpackUrl").doClick());
        waitFor(() -> service.remoteUpdateCalls.get() == 1);

        assertEquals(source, service.updateUri.get());
        EdtDispatcher.executeAndWait(panel::close);
    }

    /// Delivers the exact chosen script path and reports the exact completed result.
    @Test
    void scriptExportPreservesDestinationAndReportsCompletion() {
        RecordingService service = new RecordingService();
        RecordingInteractions interactions = new RecordingInteractions();
        RecordingLaunchActions launchActions = new RecordingLaunchActions();
        Path script = Path.of("build", "maintenance-test", "launch.ps1");
        interactions.launchScript.set(script);
        launchActions.exportResult = script;
        InstanceMaintenancePanel panel = createPanel(service, interactions, launchActions);
        activateWithSnapshot(panel, service, new InstanceMaintenanceSnapshot(
                INSTANCE_ID,
                false,
                false,
                false,
                false));

        EdtDispatcher.executeAndWait(() -> button(panel, "instanceMaintenanceExportScript").doClick());
        EdtDispatcher.executeAndWait(() -> {
            assertEquals(script, launchActions.exportDestination.get());
            assertEquals(1, interactions.successCalls.get());
            assertTrue(Objects.requireNonNull(interactions.successDetail.get()).contains(script.toString()));
            panel.close();
        });
    }

    /// Rejects a delayed initial snapshot after close without opening a failure dialog.
    @Test
    void closeSuppressesLateSnapshotCompletion() {
        RecordingService service = new RecordingService();
        RecordingInteractions interactions = new RecordingInteractions();
        InstanceMaintenancePanel panel = createPanel(service, interactions, new RecordingLaunchActions());
        EdtDispatcher.executeAndWait(() -> {
            panel.activate();
            panel.close();
        });

        service.snapshotCompletion.complete(new InstanceMaintenanceSnapshot(
                INSTANCE_ID,
                true,
                true,
                true,
                true));
        EdtDispatcher.executeAndWait(() -> {
            assertNull(panel.displayedSnapshot());
            assertEquals(0, interactions.failureCalls.get());
        });
    }

    /// Renders every command at a compact management width without clipping or overlapping controls.
    @Test
    void compactOffscreenLayoutIsNonblankAndKeepsCommandsSeparate() {
        RecordingService service = new RecordingService();
        InstanceMaintenancePanel panel = createPanel(service, new RecordingInteractions(), new RecordingLaunchActions());
        activateWithSnapshot(panel, service, new InstanceMaintenanceSnapshot(
                INSTANCE_ID,
                true,
                true,
                true,
                true));

        AtomicReference<@Nullable BufferedImage> rendered = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> {
            panel.setSize(720, 600);
            layoutTree(panel);
            @Unmodifiable List<String> names = List.of(
                    "instanceMaintenanceTestLaunch",
                    "instanceMaintenanceExportScript",
                    "instanceMaintenanceUpdateModpack",
                    "instanceMaintenanceUpdateModpackUrl",
                    "instanceMaintenanceUpdateModpackRepository",
                    "instanceMaintenanceRedownloadAssets",
                    "instanceMaintenanceRemoveAssets",
                    "instanceMaintenanceRemoveLibraries",
                    "instanceMaintenanceCleanGenerated");
            List<Rectangle> occupied = new ArrayList<>();
            for (String name : names) {
                JButton action = button(panel, name);
                assertTrue(action.getWidth() >= action.getPreferredSize().width, name + " is horizontally clipped");
                Rectangle bounds = SwingUtilities.convertRectangle(
                        action.getParent(),
                        action.getBounds(),
                        panel);
                assertTrue(bounds.x >= 0 && bounds.y >= 0, name + " starts outside the panel");
                assertTrue(bounds.getMaxX() <= panel.getWidth(), name + " extends past the panel width");
                for (Rectangle previous : occupied) {
                    assertFalse(previous.intersects(bounds), name + " overlaps another command");
                }
                occupied.add(bounds);
            }

            BufferedImage image = new BufferedImage(
                    panel.getWidth(),
                    panel.getHeight(),
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            try {
                panel.printAll(graphics);
            } finally {
                graphics.dispose();
            }
            rendered.set(image);
            panel.close();
        });

        BufferedImage image = Objects.requireNonNull(rendered.get(), "rendered image");
        int background = image.getRGB(0, 0);
        int distinctPixels = 0;
        for (int y = 0; y < image.getHeight(); y += 3) {
            for (int x = 0; x < image.getWidth(); x += 3) {
                if (image.getRGB(x, y) != background) {
                    distinctPixels++;
                }
            }
        }
        assertTrue(distinctPixels > 100, "offscreen maintenance rendering is blank");
    }

    /// Keeps the task-progress footer reachable inside the management host's constrained content height.
    @Test
    void constrainedHeightScrollsTheCompleteMaintenancePage() {
        RecordingService service = new RecordingService();
        InstanceMaintenancePanel panel = createPanel(service, new RecordingInteractions(), new RecordingLaunchActions());

        EdtDispatcher.executeAndWait(() -> {
            panel.setSize(720, 340);
            layoutTree(panel);
            JScrollPane scroll = Objects.requireNonNull(
                    findNamed(panel, "instanceMaintenanceScroll", JScrollPane.class),
                    "maintenance scroll");
            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, scroll.getHorizontalScrollBarPolicy());
            assertFalse(scroll.isOpaque());
            assertFalse(scroll.getViewport().isOpaque());
            assertTrue(
                    scroll.getVerticalScrollBar().getMaximum()
                            > scroll.getVerticalScrollBar().getVisibleAmount());

            int bottom = scroll.getVerticalScrollBar().getMaximum()
                    - scroll.getVerticalScrollBar().getVisibleAmount();
            scroll.getVerticalScrollBar().setValue(bottom);
            assertEquals(bottom, scroll.getVerticalScrollBar().getValue());
            panel.close();
        });
    }

    /// Creates an inactive page with deterministic test collaborators.
    ///
    /// @param service fake Core operation boundary
    /// @param interactions fake native interaction boundary
    /// @param launchActions fake launch command boundary
    /// @return constructed inactive panel
    private static InstanceMaintenancePanel createPanel(
            RecordingService service,
            RecordingInteractions interactions,
            RecordingLaunchActions launchActions) {
        AtomicReference<@Nullable InstanceMaintenancePanel> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> result.set(new InstanceMaintenancePanel(
                INSTANCE_ID,
                Path.of("build", "maintenance-test", INSTANCE_ID.id()),
                service,
                launchActions,
                InstanceMaintenanceStrings.english(),
                interactions,
                TaskProgressStrings.english(),
                null,
                Duration.ZERO)));
        return Objects.requireNonNull(result.get(), "panel");
    }

    /// Activates a panel and completes its pending initial snapshot.
    ///
    /// @param panel panel to activate
    /// @param service fake service owning the pending snapshot
    /// @param snapshot snapshot to publish
    private static void activateWithSnapshot(
            InstanceMaintenancePanel panel,
            RecordingService service,
            InstanceMaintenanceSnapshot snapshot) {
        EdtDispatcher.executeAndWait(panel::activate);
        service.snapshotCompletion.complete(snapshot);
        EdtDispatcher.executeAndWait(() -> { });
    }

    /// Reads the latest snapshot's asset presence safely on the EDT.
    ///
    /// @param panel panel whose authoritative state should be inspected
    /// @return boxed asset presence, or null before a snapshot is available
    private static @Nullable Boolean readAssetsPresent(InstanceMaintenancePanel panel) {
        AtomicReference<@Nullable Boolean> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> {
            @Nullable InstanceMaintenanceSnapshot snapshot = panel.displayedSnapshot();
            result.set(snapshot == null ? null : snapshot.assetsPresent());
        });
        return result.get();
    }

    /// Recursively lays out one offscreen component tree before geometry assertions and painting.
    ///
    /// @param container component subtree root
    private static void layoutTree(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                layoutTree(nested);
            }
        }
    }

    /// Waits for one asynchronous condition with a bounded timeout.
    ///
    /// @param condition condition polled from the test thread
    private static void waitFor(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(condition.getAsBoolean(), "condition did not become true before timeout");
    }

    /// Finds one named button descendant.
    ///
    /// @param root component tree root
    /// @param name stable component name
    /// @return matching button
    private static JButton button(Container root, String name) {
        @Nullable JButton result = findNamed(root, name, JButton.class);
        return Objects.requireNonNull(result, "missing button: " + name);
    }

    /// Finds a named descendant of one exact Swing type.
    ///
    /// @param root component tree root
    /// @param name stable component name
    /// @param type required component type
    /// @param <T> component type
    /// @return matching component, or null when absent
    private static <T extends Component> @Nullable T findNamed(
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

    /// Fake service exposing pending snapshot completion and immediate stopped mutation tasks.
    @NotNullByDefault
    private static final class RecordingService implements InstanceMaintenanceService {
        /// Initial snapshot completion controlled by each test.
        private final CompletableFuture<InstanceMaintenanceSnapshot> snapshotCompletion = new CompletableFuture<>();

        /// Number of initial snapshot reads.
        private final AtomicInteger loadCalls = new AtomicInteger();

        /// Number of modpack update tasks requested.
        private final AtomicInteger updateCalls = new AtomicInteger();

        /// Exact selected update archive, or null before a request.
        private final AtomicReference<@Nullable Path> updateArchive = new AtomicReference<>();

        /// Exact selected archive charset, or null before a request.
        private final AtomicReference<@Nullable Charset> updateCharset = new AtomicReference<>();

        /// Number of remote modpack update tasks requested.
        private final AtomicInteger remoteUpdateCalls = new AtomicInteger();

        /// Exact selected remote update source, or null before a request.
        private final AtomicReference<@Nullable URI> updateUri = new AtomicReference<>();

        /// Number of shared asset removal tasks requested.
        private final AtomicInteger removeAssetsCalls = new AtomicInteger();

        /// Snapshot yielded by every mutation task.
        private InstanceMaintenanceSnapshot mutationResult = new InstanceMaintenanceSnapshot(
                INSTANCE_ID,
                false,
                false,
                false,
                false);

        /// Returns the test-controlled initial snapshot completion.
        @Override
        public CompletionStage<InstanceMaintenanceSnapshot> loadSnapshot() {
            loadCalls.incrementAndGet();
            return snapshotCompletion;
        }

        /// Records one archive update request.
        ///
        /// @param archive selected update archive
        /// @param charset selected archive charset
        /// @return immediate stopped result task
        @Override
        public Task<InstanceMaintenanceSnapshot> updateModpack(Path archive, Charset charset) {
            updateCalls.incrementAndGet();
            updateArchive.set(archive);
            updateCharset.set(charset);
            return Task.completed(mutationResult);
        }

        /// Records one remote update request.
        ///
        /// @param source selected remote source
        /// @return immediate stopped result task
        @Override
        public Task<InstanceMaintenanceSnapshot> updateModpack(URI source) {
            remoteUpdateCalls.incrementAndGet();
            updateUri.set(source);
            return Task.completed(mutationResult);
        }

        /// Returns an immediate successful asset repair task.
        @Override
        public Task<InstanceMaintenanceSnapshot> redownloadAssets() {
            return Task.completed(mutationResult);
        }

        /// Records one shared asset removal request.
        @Override
        public Task<InstanceMaintenanceSnapshot> removeAssets() {
            removeAssetsCalls.incrementAndGet();
            return Task.completed(mutationResult);
        }

        /// Returns an immediate successful library removal task.
        @Override
        public Task<InstanceMaintenanceSnapshot> removeLibraries() {
            return Task.completed(mutationResult);
        }

        /// Returns an immediate successful generated-file cleanup task.
        @Override
        public Task<InstanceMaintenanceSnapshot> cleanGeneratedFiles() {
            return Task.completed(mutationResult);
        }
    }

    /// Fake native interactions with explicit choices and recorded outcomes.
    @NotNullByDefault
    private static final class RecordingInteractions implements InstanceMaintenanceInteractions {
        /// Selected modpack archive, or null to simulate cancellation.
        private final AtomicReference<@Nullable Path> modpackArchive = new AtomicReference<>();

        /// Selected remote modpack source, or null to simulate cancellation.
        private final AtomicReference<@Nullable URI> modpackUri = new AtomicReference<>();

        /// Selected launch script, or null to simulate cancellation.
        private final AtomicReference<@Nullable Path> launchScript = new AtomicReference<>();

        /// Whether destructive operations are approved.
        private final AtomicBoolean destructiveApproved = new AtomicBoolean();

        /// Number of destructive confirmation requests.
        private final AtomicInteger confirmCalls = new AtomicInteger();

        /// Whether the latest destructive confirmation covered shared data.
        private final AtomicBoolean lastSharedScope = new AtomicBoolean();

        /// Number of shown failures.
        private final AtomicInteger failureCalls = new AtomicInteger();

        /// Number of shown successful path results.
        private final AtomicInteger successCalls = new AtomicInteger();

        /// Latest successful detail, or null before success.
        private final AtomicReference<@Nullable String> successDetail = new AtomicReference<>();

        /// Returns the configured update archive.
        ///
        /// @param owner unused native owner
        /// @return configured archive, or null
        @Override
        public @Nullable Path chooseModpackArchive(Component owner) {
            return modpackArchive.get();
        }

        /// Returns the configured remote source.
        ///
        /// @param owner unused native owner
        /// @return configured remote URI, or null
        @Override
        public @Nullable URI chooseModpackUri(Component owner) {
            return modpackUri.get();
        }

        /// Returns the configured script destination.
        ///
        /// @param owner unused native owner
        /// @param initialDirectory unused initial directory
        /// @return configured script, or null
        @Override
        public @Nullable Path chooseLaunchScript(Component owner, Path initialDirectory) {
            return launchScript.get();
        }

        /// Records and returns destructive approval.
        ///
        /// @param owner unused native owner
        /// @param action unused visible action
        /// @param sharedScope shared-data flag
        /// @return configured approval
        @Override
        public boolean confirmDestructive(Component owner, String action, boolean sharedScope) {
            confirmCalls.incrementAndGet();
            lastSharedScope.set(sharedScope);
            return destructiveApproved.get();
        }

        /// Records one failure.
        ///
        /// @param owner unused native owner
        /// @param title unused title
        /// @param detail unused detail
        @Override
        public void showFailure(Component owner, String title, String detail) {
            failureCalls.incrementAndGet();
        }

        /// Records one successful detail.
        ///
        /// @param owner unused native owner
        /// @param title unused title
        /// @param detail exact completion detail
        @Override
        public void showSuccess(Component owner, String title, String detail) {
            successCalls.incrementAndGet();
            successDetail.set(detail);
        }
    }

    /// Fake launch boundary supporting only script export in these page tests.
    @NotNullByDefault
    private static final class RecordingLaunchActions implements InstanceMaintenanceLaunchActions {
        /// Exact script destination received by the export command.
        private final AtomicReference<@Nullable Path> exportDestination = new AtomicReference<>();

        /// Script path completed by the export command.
        private Path exportResult = Path.of("launch.ps1");

        /// Rejects unexpected test launch invocation.
        @Override
        public LaunchSession testLaunch() {
            throw new AssertionError("test launch was not expected");
        }

        /// Completes with the configured script result after recording the exact destination.
        ///
        /// @param scriptFile exact selected destination
        /// @return completed configured result
        @Override
        public CompletionStage<Path> exportLaunchScript(Path scriptFile) {
            exportDestination.set(scriptFile);
            return CompletableFuture.completedFuture(exportResult);
        }
    }
}
