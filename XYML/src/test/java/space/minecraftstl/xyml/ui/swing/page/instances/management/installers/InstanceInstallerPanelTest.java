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
package space.minecraftstl.xyml.ui.swing.page.instances.management.installers;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.download.LibraryAnalyzer;
import space.minecraftstl.xyml.download.RemoteVersion;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.page.downloads.loaders.DefaultGameLoaderCatalogModel;
import space.minecraftstl.xyml.ui.swing.page.downloads.loaders.GameLoaderCatalogItem;
import space.minecraftstl.xyml.ui.swing.page.downloads.loaders.GameLoaderCatalogSource;
import space.minecraftstl.xyml.ui.swing.page.downloads.loaders.GameLoaderKind;
import space.minecraftstl.xyml.ui.swing.page.downloads.loaders.LoaderSelectionWizardPanel;
import space.minecraftstl.xyml.ui.swing.page.downloads.loaders.LoaderSelectionWizardStrings;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;

import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.TransferHandler;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static space.minecraftstl.xyml.ui.swing.SwingFileTransferTestSupport.fileTransfer;

/// Verifies lazy installer-state activation, safe local actions, and exact remote-version task handoff.
@NotNullByDefault
final class InstanceInstallerPanelTest {
    /// The common instance identifier supplied by all focused panel scenarios.
    private static final GameInstanceID INSTANCE_ID = new GameInstanceID("instance");

    /// Activation alone starts exactly one asynchronous snapshot read and seeds retained loader state locally.
    @Test
    void activationIsLazyAndAppliesRetainedLoaderState() {
        RecordingInstallerService service = new RecordingInstallerService();
        RecordingInteractions interactions = new RecordingInteractions();
        LoaderSelectionWizardPanel wizard = wizardWith(List.of());
        InstanceInstallerSnapshot snapshot = snapshot(
                List.of(new InstanceInstallerEntry(
                        GameLoaderKind.FABRIC,
                        "0.16.0",
                        LibraryAnalyzer.LibraryMark.LibraryStatus.CLEAR)),
                List.of());

        InstanceInstallerPanel panel = createPanel(service, wizard, interactions);
        EdtDispatcher.executeAndWait(() -> {
            assertEquals(0, service.loadCalls.get());
            panel.activate();
            assertEquals(1, service.loadCalls.get());
            assertNull(panel.displayedSnapshot());
        });

        service.completeSnapshot(snapshot);
        EdtDispatcher.executeAndWait(() -> {
            assertSame(snapshot, panel.displayedSnapshot());
            LoaderSelectionWizardPanel embedded = findNamed(
                    panel,
                    "instanceInstallerLoaderWizard",
                    LoaderSelectionWizardPanel.class);
            assertEquals("1.20.1", embedded.selectionSnapshot().gameVersion().orElseThrow());
            assertTrue(embedded.selectedRemoteVersions().isEmpty());
            JList<?> installed = findNamed(panel, "instanceInstallerInstalledLoaderList", JList.class);
            assertEquals(1, installed.getModel().getSize());
            panel.close();
        });
    }

    /// Third-party removal stays disabled for uncertain structures and uses a confirmation boundary for clear entries.
    @Test
    void removesOnlyClearThirdPartyLibrariesAfterConfirmation() {
        RecordingInstallerService service = new RecordingInstallerService();
        RecordingInteractions interactions = new RecordingInteractions();
        InstanceOtherLibraryEntry uncertain = new InstanceOtherLibraryEntry(
                "external-library",
                "1.0",
                InstanceOtherLibraryEntry.StructureState.EXTERNALLY_UNCERTAIN);
        InstanceOtherLibraryEntry clear = new InstanceOtherLibraryEntry(
                "clear-library",
                "2.0",
                InstanceOtherLibraryEntry.StructureState.CLEAR);
        InstanceInstallerPanel panel = createPanel(service, wizardWith(List.of()), interactions);
        activateWithSnapshot(panel, service, snapshot(List.of(), List.of(uncertain, clear)));

        EdtDispatcher.executeAndWait(() -> {
            JList<?> otherLibraries = findNamed(panel, "instanceInstallerOtherLibraryList", JList.class);
            JButton remove = findNamed(panel, "instanceInstallerRemoveOtherLibrary", JButton.class);
            otherLibraries.setSelectedIndex(0);
            assertFalse(remove.isEnabled());
            otherLibraries.setSelectedIndex(1);
            assertTrue(remove.isEnabled());

            interactions.confirmRemoval = false;
            remove.doClick();
            assertEquals(0, service.removeCalls.get());
            assertEquals(1, interactions.confirmCalls.get());

            interactions.confirmRemoval = true;
            remove.doClick();
            assertEquals(1, service.removeCalls.get());
            assertEquals("clear-library", service.removedLibraryId);
            panel.close();
        });
    }

    /// Recognized loaders receive the same clear-only removal rule and map the selected kind to its Core ID.
    @Test
    void removesOnlyClearRecognizedLoadersAfterConfirmation() {
        RecordingInstallerService service = new RecordingInstallerService();
        RecordingInteractions interactions = new RecordingInteractions();
        InstanceInstallerEntry uncertain = new InstanceInstallerEntry(
                GameLoaderKind.FORGE,
                "47.2.0",
                LibraryAnalyzer.LibraryMark.LibraryStatus.UNSURE);
        InstanceInstallerEntry clear = new InstanceInstallerEntry(
                GameLoaderKind.FABRIC,
                "0.16.0",
                LibraryAnalyzer.LibraryMark.LibraryStatus.CLEAR);
        InstanceInstallerPanel panel = createPanel(service, wizardWith(List.of()), interactions);
        activateWithSnapshot(panel, service, snapshot(List.of(uncertain, clear), List.of()));

        EdtDispatcher.executeAndWait(() -> {
            JList<?> installedLoaders = findNamed(panel, "instanceInstallerInstalledLoaderList", JList.class);
            JButton remove = findNamed(panel, "instanceInstallerRemoveInstalledLoader", JButton.class);
            installedLoaders.setSelectedIndex(0);
            assertFalse(remove.isEnabled());
            installedLoaders.setSelectedIndex(1);
            assertTrue(remove.isEnabled());

            interactions.confirmRemoval = true;
            remove.doClick();
            assertEquals(1, service.removeCalls.get());
            assertEquals("fabric", service.removedLibraryId);
            panel.close();
        });
    }

    /// The offline command hands the exact locally selected jar path to the stopped service task.
    @Test
    void offlineSelectionUsesInjectedLocalChooser() {
        RecordingInstallerService service = new RecordingInstallerService();
        RecordingInteractions interactions = new RecordingInteractions();
        Path chosenInstaller = Path.of("C:/offline/forge-installer.jar");
        interactions.chosenInstaller = chosenInstaller;
        InstanceInstallerPanel panel = createPanel(service, wizardWith(List.of()), interactions);
        activateWithSnapshot(panel, service, snapshot(List.of(), List.of()));

        EdtDispatcher.executeAndWait(() -> {
            JButton offline = findNamed(panel, "instanceInstallerOfflineInstall", JButton.class);
            assertTrue(offline.isEnabled());
            offline.doClick();
            assertEquals(1, service.offlineCalls.get());
            assertEquals(chosenInstaller, service.offlineInstaller);
            panel.close();
        });
    }

    /// A ready installer page accepts one dropped JAR or EXE and detaches its route on close.
    @Test
    void installsSupportedDroppedOfflineInstallerOnlyWhileReady() {
        RecordingInstallerService service = new RecordingInstallerService();
        InstanceInstallerPanel panel = createPanel(
                service,
                wizardWith(List.of()),
                new RecordingInteractions());
        activateWithSnapshot(panel, service, snapshot(List.of(), List.of()));

        EdtDispatcher.executeAndWait(() -> {
            TransferHandler handler = Objects.requireNonNull(panel.getTransferHandler());
            Path installer = Path.of("C:/offline/NeoForge-INSTALLER.JAR");
            TransferHandler.TransferSupport supported = fileTransfer(panel, List.of(installer));
            TransferHandler.TransferSupport unsupported = fileTransfer(panel, List.of(Path.of("notes.txt")));

            assertTrue(handler.canImport(supported));
            assertFalse(handler.canImport(unsupported));
            assertTrue(handler.importData(supported));
            assertEquals(1, service.offlineCalls.get());
            assertEquals(installer.toAbsolutePath().normalize(), service.offlineInstaller);

            panel.close();
            assertNull(panel.getTransferHandler());
        });
    }

    /// Online installation receives the original remote-version object selected by the embedded shared wizard.
    @Test
    void onlineInstallationPreservesOriginalRemoteVersionObjects() {
        RemoteVersion fabric = remoteVersion("fabric", "1.20.1", "0.16.0");
        RecordingInstallerService service = new RecordingInstallerService();
        RecordingInteractions interactions = new RecordingInteractions();
        LoaderSelectionWizardPanel wizard = wizardWith(List.of(
                new GameLoaderCatalogItem(GameLoaderKind.FABRIC, fabric)));
        InstanceInstallerPanel panel = createPanel(service, wizard, interactions);
        activateWithSnapshot(panel, service, snapshot(List.of(), List.of()));

        EdtDispatcher.executeAndWait(() -> {
            JButton fabricKind = findNamed(panel, "loaderKind_FABRIC", JButton.class);
            JButton loadVersions = findNamed(panel, "loaderLoadVersions", JButton.class);
            JButton addSelection = findNamed(panel, "loaderAddSelection", JButton.class);
            JList<?> versionList = findNamed(panel, "loaderVersionListView", JList.class);
            JButton install = findNamed(panel, "instanceInstallerOnlineInstall", JButton.class);

            fabricKind.doClick();
            loadVersions.doClick();
            prepareLoaderVersionViewport(wizard);
            versionList.setSelectedIndex(0);
            assertTrue(addSelection.isEnabled());
            addSelection.doClick();
            assertTrue(install.isEnabled());
            install.doClick();

            assertEquals(1, service.remoteInstallCalls.get());
            assertEquals(1, service.installedRemoteVersions.size());
            assertSame(fabric, service.installedRemoteVersions.get(0));
            panel.close();
        });
    }

    /// A rejected task executor becomes visible failure feedback rather than escaping the Swing event-dispatch thread.
    @Test
    void rejectedTaskExecutorIsReportedWithoutEscapingTheEventDispatchThread() {
        RecordingInstallerService service = new RecordingInstallerService();
        service.mutationExecutor = command -> {
            throw new RejectedExecutionException("test rejection");
        };
        RecordingInteractions interactions = new RecordingInteractions();
        interactions.chosenInstaller = Path.of("C:/offline/rejected-installer.jar");
        InstanceInstallerPanel panel = createPanel(service, wizardWith(List.of()), interactions);
        activateWithSnapshot(panel, service, snapshot(List.of(), List.of()));

        EdtDispatcher.executeAndWait(() -> {
            JButton offline = findNamed(panel, "instanceInstallerOfflineInstall", JButton.class);
            assertDoesNotThrow(() -> {
                offline.doClick();
            });
            assertEquals(1, service.offlineCalls.get());
        });

        waitFor(() -> interactions.failureCalls.get() == 1);
        EdtDispatcher.executeAndWait(() -> {
            assertEquals(1, interactions.failureCalls.get());
            assertTrue(offlineButton(panel).isEnabled());
            panel.close();
        });
    }

    /// A mutation remains single-flight while its worker is pending, and closing rejects its eventual late completion.
    @Test
    void activeMutationIsSingleFlightAndCloseSuppressesItsDelayedCompletion() {
        RemoteVersion fabric = remoteVersion("fabric", "1.20.1", "0.16.0");
        RecordingInstallerService service = new RecordingInstallerService();
        QueuedExecutor queuedMutationExecutor = new QueuedExecutor();
        service.mutationExecutor = queuedMutationExecutor;
        RecordingInteractions interactions = new RecordingInteractions();
        LoaderSelectionWizardPanel wizard = wizardWith(List.of(
                new GameLoaderCatalogItem(GameLoaderKind.FABRIC, fabric)));
        InstanceInstallerPanel panel = createPanel(service, wizard, interactions);
        activateWithSnapshot(panel, service, snapshot(List.of(), List.of()));

        EdtDispatcher.executeAndWait(() -> {
            JButton fabricKind = findNamed(panel, "loaderKind_FABRIC", JButton.class);
            JButton loadVersions = findNamed(panel, "loaderLoadVersions", JButton.class);
            JButton addSelection = findNamed(panel, "loaderAddSelection", JButton.class);
            JList<?> versionList = findNamed(panel, "loaderVersionListView", JList.class);
            JButton install = findNamed(panel, "instanceInstallerOnlineInstall", JButton.class);

            fabricKind.doClick();
            loadVersions.doClick();
            prepareLoaderVersionViewport(wizard);
            versionList.setSelectedIndex(0);
            addSelection.doClick();
            install.doClick();
            assertEquals(1, service.remoteInstallCalls.get());
            assertFalse(install.isEnabled());
            install.doClick();
            assertEquals(1, service.remoteInstallCalls.get());
        });

        waitFor(queuedMutationExecutor::hasPendingWork);
        EdtDispatcher.executeAndWait(panel::close);
        queuedMutationExecutor.runAll();
        EdtDispatcher.executeAndWait(() -> {
            assertNull(panel.displayedSnapshot());
            assertEquals(0, interactions.failureCalls.get());
        });
    }

    /// Closing while a snapshot is still pending prevents its eventual completion from mutating the disposed page.
    @Test
    void closeSuppressesLateSnapshotPublication() {
        RecordingInstallerService service = new RecordingInstallerService();
        InstanceInstallerPanel panel = createPanel(service, wizardWith(List.of()), new RecordingInteractions());
        EdtDispatcher.executeAndWait(() -> {
            panel.activate();
            panel.close();
        });

        service.completeSnapshot(snapshot(List.of(), List.of()));
        EdtDispatcher.executeAndWait(() -> assertNull(panel.displayedSnapshot()));
    }

    /// Creates an activated panel with deterministic local task presentation and no native dialogs.
    ///
    /// @param service fake installer service
    /// @param wizard local injected loader selector
    /// @param interactions fake native interaction boundary
    /// @return constructed, inactive installer panel
    private static InstanceInstallerPanel createPanel(
            RecordingInstallerService service,
            LoaderSelectionWizardPanel wizard,
            RecordingInteractions interactions) {
        final InstanceInstallerPanel[] holder = new InstanceInstallerPanel[1];
        EdtDispatcher.executeAndWait(() -> holder[0] = new InstanceInstallerPanel(
                INSTANCE_ID,
                service,
                wizard,
                interactions,
                TaskProgressStrings.english(),
                null,
                Duration.ZERO));
        return Objects.requireNonNull(holder[0], "panel");
    }

    /// Activates one panel, completes its pending service snapshot, and waits for EDT application.
    ///
    /// @param panel target panel
    /// @param service corresponding fake service
    /// @param snapshot exact completed snapshot
    private static void activateWithSnapshot(
            InstanceInstallerPanel panel,
            RecordingInstallerService service,
            InstanceInstallerSnapshot snapshot) {
        EdtDispatcher.executeAndWait(panel::activate);
        service.completeSnapshot(snapshot);
        EdtDispatcher.executeAndWait(() -> { });
    }

    /// Gives the detached lazy loader list actual viewport geometry so its first catalog row materializes.
    ///
    /// @param wizard embedded loader selector under test
    private static void prepareLoaderVersionViewport(LoaderSelectionWizardPanel wizard) {
        LoaderSelectionWizardPanel target = Objects.requireNonNull(wizard, "wizard");
        target.versionChoiceList().setSize(480, 180);
        target.versionChoiceList().getViewport().setExtentSize(new Dimension(480, 160));
        target.versionChoiceList().getList().setSize(480, 160);
        target.versionChoiceList().refreshLoadPlan();
    }

    /// Creates one valid current-instance snapshot for focused panel tests.
    ///
    /// @param loaders recognized installed loaders
    /// @param otherLibraries third-party library rows
    /// @return immutable valid snapshot
    private static InstanceInstallerSnapshot snapshot(
            List<InstanceInstallerEntry> loaders,
            List<InstanceOtherLibraryEntry> otherLibraries) {
        return new InstanceInstallerSnapshot(
                INSTANCE_ID,
                Optional.of("1.20.1"),
                loaders,
                otherLibraries);
    }

    /// Creates a local wizard whose source exposes only preconfigured exact remote-version rows after user action.
    ///
    /// @param items explicit source items
    /// @return unactivated local loader wizard
    private static LoaderSelectionWizardPanel wizardWith(List<GameLoaderCatalogItem> items) {
        Map<GameLoaderKind, @Unmodifiable List<GameLoaderCatalogItem>> byKind = new EnumMap<>(GameLoaderKind.class);
        for (GameLoaderCatalogItem item : items) {
            byKind.computeIfAbsent(item.kind(), ignored -> new java.util.ArrayList<>()).add(item);
        }
        Map<GameLoaderKind, @Unmodifiable List<GameLoaderCatalogItem>> immutableByKind =
                new EnumMap<>(GameLoaderKind.class);
        for (Map.Entry<GameLoaderKind, @Unmodifiable List<GameLoaderCatalogItem>> entry : byKind.entrySet()) {
            immutableByKind.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        GameLoaderCatalogSource source = request -> CompletableFuture.completedFuture(
                immutableByKind.getOrDefault(request.kind(), List.of()));
        DefaultGameLoaderCatalogModel model = new DefaultGameLoaderCatalogModel(source);
        final LoaderSelectionWizardPanel[] holder = new LoaderSelectionWizardPanel[1];
        EdtDispatcher.executeAndWait(() -> holder[0] = new LoaderSelectionWizardPanel(
                model,
                Runnable::run,
                LoaderSelectionWizardStrings.english()));
        return Objects.requireNonNull(holder[0], "wizard");
    }

    /// Creates one concrete Core remote version suitable for the shared loader catalog.
    ///
    /// @param libraryId Core loader library identifier
    /// @param gameVersion target Minecraft version
    /// @param selfVersion concrete loader version
    /// @return exact Core remote version
    private static RemoteVersion remoteVersion(String libraryId, String gameVersion, String selfVersion) {
        return new RemoteVersion(
                Objects.requireNonNull(libraryId, "libraryId"),
                Objects.requireNonNull(gameVersion, "gameVersion"),
                Objects.requireNonNull(selfVersion, "selfVersion"),
                Instant.EPOCH,
                List.of("https://example.invalid/" + libraryId + ".jar"));
    }

    /// Finds the stable offline command without duplicating its deterministic component name at call sites.
    ///
    /// @param panel target component tree
    /// @return offline installation command
    private static JButton offlineButton(InstanceInstallerPanel panel) {
        return findNamed(panel, "instanceInstallerOfflineInstall", JButton.class);
    }

    /// Waits for an asynchronous test condition while keeping a bounded failure mode for executor regressions.
    ///
    /// @param condition expected eventual state
    private static void waitFor(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(condition.getAsBoolean(), "Timed out waiting for asynchronous panel state");
    }

    /// Finds a named Swing child recursively and verifies its expected type.
    ///
    /// @param root component tree root
    /// @param name required deterministic component name
    /// @param type required child type
    /// @param <T> expected child type
    /// @return matching named component
    private static <T extends Component> T findNamed(Container root, String name, Class<T> type) {
        for (Component component : Objects.requireNonNull(root, "root").getComponents()) {
            if (Objects.requireNonNull(name, "name").equals(component.getName()) && type.isInstance(component)) {
                return type.cast(component);
            }
            if (component instanceof Container child) {
                try {
                    return findNamed(child, name, type);
                } catch (IllegalArgumentException ignored) {
                    // Continue searching sibling component branches.
                }
            }
        }
        throw new IllegalArgumentException("No component named " + name);
    }

    /// Records panel service calls and exposes a manually completed initial snapshot for deterministic
    /// activation tests.
    @NotNullByDefault
    private static final class RecordingInstallerService implements InstanceInstallerManagementService {
        /// Number of snapshot reads requested by the panel.
        private final AtomicInteger loadCalls = new AtomicInteger();

        /// Completion for the current initial snapshot request.
        private CompletableFuture<InstanceInstallerSnapshot> snapshotCompletion = new CompletableFuture<>();

        /// Number of online installer-task requests.
        private final AtomicInteger remoteInstallCalls = new AtomicInteger();

        /// Number of third-party removal-task requests.
        private final AtomicInteger removeCalls = new AtomicInteger();

        /// Number of offline installer-task requests.
        private final AtomicInteger offlineCalls = new AtomicInteger();

        /// Exact original remote versions supplied by the panel, or an empty list before installation.
        private List<RemoteVersion> installedRemoteVersions = List.of();

        /// Exact third-party library requested for removal, or null before a confirmed removal.
        private @Nullable String removedLibraryId;

        /// Exact local file supplied by the offline chooser, or null before an offline command.
        private @Nullable Path offlineInstaller;

        /// Latest successful mutation result supplied by direct task fakes.
        private InstanceInstallerSnapshot mutationResult = snapshot(List.of(), List.of());

        /// Executor used by mutation task bodies, allowing tests to reject or defer their execution deterministically.
        private Executor mutationExecutor = Runnable::run;

        /// Records one asynchronous snapshot read without completing it until the test supplies a result.
        ///
        /// @param instanceId target instance identifier
        /// @return manually completable snapshot stage
        @Override
        public CompletionStage<InstanceInstallerSnapshot> loadSnapshot(GameInstanceID instanceId) {
            assertEquals(INSTANCE_ID, instanceId);
            loadCalls.incrementAndGet();
            return snapshotCompletion;
        }

        /// Captures exact remote objects and returns a direct successful task for the latest snapshot.
        ///
        /// @param instanceId target instance identifier
        /// @param remoteVersions exact selected Core remote versions
        /// @return direct successful mutation task
        @Override
        public Task<InstanceInstallerSnapshot> installRemoteVersions(
                GameInstanceID instanceId,
                Collection<? extends RemoteVersion> remoteVersions) {
            assertEquals(INSTANCE_ID, instanceId);
            remoteInstallCalls.incrementAndGet();
            installedRemoteVersions = List.copyOf(remoteVersions);
            return immediateMutationTask();
        }

        /// Captures one removal request and returns a direct successful task for the latest snapshot.
        ///
        /// @param instanceId target instance identifier
        /// @param libraryId exact third-party library identifier
        /// @return direct successful mutation task
        @Override
        public Task<InstanceInstallerSnapshot> removeLibrary(GameInstanceID instanceId, String libraryId) {
            assertEquals(INSTANCE_ID, instanceId);
            removeCalls.incrementAndGet();
            removedLibraryId = libraryId;
            return immediateMutationTask();
        }

        /// Captures one offline path and returns a direct successful task for the latest snapshot.
        ///
        /// @param instanceId target instance identifier
        /// @param installer selected local installer
        /// @return direct successful mutation task
        @Override
        public Task<InstanceInstallerSnapshot> installOffline(GameInstanceID instanceId, Path installer) {
            assertEquals(INSTANCE_ID, instanceId);
            offlineCalls.incrementAndGet();
            offlineInstaller = installer;
            return immediateMutationTask();
        }

        /// Completes the current snapshot read and prepares a new deferred completion for any future refresh.
        ///
        /// @param snapshot exact snapshot supplied by the test
        private void completeSnapshot(InstanceInstallerSnapshot snapshot) {
            mutationResult = Objects.requireNonNull(snapshot, "snapshot");
            snapshotCompletion.complete(snapshot);
            snapshotCompletion = new CompletableFuture<>();
        }

        /// Creates a direct task that returns the latest accepted mutation snapshot without background I/O.
        ///
        /// @return direct successful task
        private Task<InstanceInstallerSnapshot> immediateMutationTask() {
            return Task.supplyAsync(mutationExecutor, () -> mutationResult);
        }
    }

    /// Supplies deterministic native chooser, confirmation, and failure behavior without opening desktop dialogs.
    @NotNullByDefault
    private static final class RecordingInteractions implements InstanceInstallerInteractions {
        /// Chosen local installer path, or null when the simulated chooser is cancelled.
        private @Nullable Path chosenInstaller;

        /// Whether the simulated clear-library removal confirmation approves a mutation.
        private boolean confirmRemoval;

        /// Number of clear-library confirmation requests.
        private final AtomicInteger confirmCalls = new AtomicInteger();

        /// Number of terminal failure dialog requests.
        private final AtomicInteger failureCalls = new AtomicInteger();

        /// Returns the configured local chooser result.
        ///
        /// @param owner ignored native owner
        /// @return configured installer path, or null after simulated cancellation
        @Override
        public @Nullable Path chooseOfflineInstaller(Component owner) {
            return chosenInstaller;
        }

        /// Records a removal confirmation request and returns the configured response.
        ///
        /// @param owner ignored native owner
        /// @param library selected clear third-party library
        /// @return configured confirmation decision
        @Override
        public boolean confirmRemoval(Component owner, String libraryId) {
            Objects.requireNonNull(libraryId, "libraryId");
            confirmCalls.incrementAndGet();
            return confirmRemoval;
        }

        /// Records a terminal failure without showing a native dialog.
        ///
        /// @param owner ignored native owner
        /// @param title failure title
        /// @param detail failure detail
        @Override
        public void showFailure(Component owner, String title, String detail) {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(detail, "detail");
            failureCalls.incrementAndGet();
        }
    }

    /// Queues task work until the test explicitly permits it to execute.
    @NotNullByDefault
    private static final class QueuedExecutor implements Executor {
        /// Pending task bodies in submission order.
        private final java.util.ArrayDeque<Runnable> pending = new java.util.ArrayDeque<>();

        /// Records one asynchronous submission without running it immediately.
        ///
        /// @param command task body submitted by Core
        @Override
        public synchronized void execute(Runnable command) {
            pending.addLast(Objects.requireNonNull(command, "command"));
        }

        /// Returns whether Core has submitted at least one delayed task body.
        ///
        /// @return whether pending work exists
        private synchronized boolean hasPendingWork() {
            return !pending.isEmpty();
        }

        /// Runs every queued task body in original submission order.
        private void runAll() {
            while (true) {
                final Runnable command;
                synchronized (this) {
                    command = pending.pollFirst();
                }
                if (command == null) {
                    return;
                }
                command.run();
            }
        }
    }
}
