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
package space.minecraftstl.xyml.ui.swing.page.instances.management.datapacks;

import space.minecraftstl.xyml.library.nbt.io.NBTCodec;
import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.addon.datapack.DataPack;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.choice.ChoicePage;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceList;
import space.minecraftstl.xyml.ui.swing.page.instances.management.worlds.WorldCatalogItem;
import space.minecraftstl.xyml.ui.swing.page.instances.management.worlds.WorldCatalogImport;
import space.minecraftstl.xyml.ui.swing.page.instances.management.worlds.WorldCatalogModel;
import space.minecraftstl.xyml.ui.swing.page.instances.management.worlds.WorldCatalogSnapshot;
import space.minecraftstl.xyml.ui.swing.page.instances.management.worlds.WorldCatalogStatus;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.TransferHandler;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static space.minecraftstl.xyml.ui.swing.SwingFileTransferTestSupport.fileTransfer;

/// Verifies lazy world activation and real local DataPack import, reveal, and deletion commands.
@NotNullByDefault
final class DataPackManagementPanelTest {
    /// Temporary instance root containing a valid saved world and data-pack fixtures.
    @TempDir
    private Path temporaryDirectory;

    /// Starts world indexing only after activation, then performs selected-world pack mutations locally.
    @Test
    void lazilyLoadsSelectedWorldAndRunsLocalDataPackActions() throws Exception {
        Path savesDirectory = Files.createDirectories(temporaryDirectory.resolve("run").resolve("saves"));
        Path worldDirectory = createWorldDirectory(savesDirectory, "fixture-world");
        Path dataPacksDirectory = Files.createDirectories(worldDirectory.resolve("datapacks"));
        createDirectoryDataPack(dataPacksDirectory, "existing");
        Path archive = createDataPackArchive(temporaryDirectory.resolve("imported.zip"));
        WorldCatalogItem world = new WorldCatalogItem(
                worldDirectory,
                "fixture-world",
                "fixture-world",
                1L,
                "1.20.1",
                false,
                null);
        SingleWorldCatalogModel model = new SingleWorldCatalogModel(savesDirectory, world);
        RecordingInteractions interactions = new RecordingInteractions(archive);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<@Nullable DataPackManagementPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> panelReference.set(new DataPackManagementPanel(
                    model,
                    DataPackManagementStrings.english(),
                    interactions,
                    executor)));
            DataPackManagementPanel panel = Objects.requireNonNull(panelReference.get());

            EdtDispatcher.executeAndWait(() -> {
                assertEquals(0, model.loadRequests.get());
                panel.activate();
                assertEquals(1, model.loadRequests.get());
                prepareViewport(panel.worldChoiceList());
            });
            drainEdt();

            EdtDispatcher.executeAndWait(() -> panel.worldChoiceList().getList().setSelectedIndex(0));
            awaitBackgroundWork(executor);
            EdtDispatcher.executeAndWait(() -> {
                assertEquals(OptionalInt.of(1), panel.dataPackChoiceList().getChoiceModel().exactItemCount());
                JButton openDirectory = findNamed(panel, "dataPacksOpenDirectory", JButton.class);
                JButton importButton = findNamed(panel, "dataPacksImport", JButton.class);
                assertNotNull(openDirectory);
                assertNotNull(importButton);
                assertTrue(openDirectory.isEnabled());
                assertTrue(importButton.isEnabled());
                openDirectory.doClick();
                assertEquals(dataPacksDirectory.toAbsolutePath().normalize(), interactions.openedDirectory.get());
                importButton.doClick();
            });
            awaitBackgroundWork(executor);

            EdtDispatcher.executeAndWait(() -> {
                assertEquals(OptionalInt.of(2), panel.dataPackChoiceList().getChoiceModel().exactItemCount());
                prepareViewport(panel.dataPackChoiceList());
            });
            drainEdt();

            EdtDispatcher.executeAndWait(() -> {
                JTextField search = findNamed(panel, "dataPacksSearch", JTextField.class);
                JButton clearSearch = findNamed(panel, "dataPacksSearchClear", JButton.class);
                JButton selectAll = findNamed(panel, "dataPacksSelectAll", JButton.class);
                JButton disable = findNamed(panel, "dataPacksDisable", JButton.class);
                JButton deleteButton = findNamed(panel, "dataPacksDelete", JButton.class);
                assertNotNull(search);
                assertNotNull(clearSearch);
                assertNotNull(selectAll);
                assertNotNull(disable);
                assertNotNull(deleteButton);

                search.setText("IMPORTED");
                search.postActionEvent();
                assertEquals(OptionalInt.of(1), panel.dataPackChoiceList().getChoiceModel().exactItemCount());
                search.setText("regex:^existing$");
                search.postActionEvent();
                assertEquals(OptionalInt.of(1), panel.dataPackChoiceList().getChoiceModel().exactItemCount());
                search.setText("regex:[");
                search.postActionEvent();
                assertEquals(OptionalInt.of(0), panel.dataPackChoiceList().getChoiceModel().exactItemCount());
                assertTrue(clearSearch.isEnabled());
                clearSearch.doClick();
                assertEquals(OptionalInt.of(2), panel.dataPackChoiceList().getChoiceModel().exactItemCount());

                selectAll.doClick();
                assertEquals(2, panel.dataPackChoiceList().getList().getSelectedIndices().length);
                assertTrue(disable.isEnabled());
                disable.doClick();
            });
            awaitBackgroundWork(executor);

            assertTrue(Files.isRegularFile(dataPacksDirectory.resolve("existing/pack.mcmeta.disabled")));
            assertTrue(Files.isRegularFile(dataPacksDirectory.resolve("imported.zip.disabled")));
            EdtDispatcher.executeAndWait(() -> {
                JButton selectAll = findNamed(panel, "dataPacksSelectAll", JButton.class);
                JButton deleteButton = findNamed(panel, "dataPacksDelete", JButton.class);
                assertNotNull(selectAll);
                assertNotNull(deleteButton);
                assertEquals(OptionalInt.of(2), panel.dataPackChoiceList().getChoiceModel().exactItemCount());
                selectAll.doClick();
                assertTrue(deleteButton.isEnabled());
                deleteButton.doClick();
            });
            awaitBackgroundWork(executor);

            assertFalse(Files.exists(dataPacksDirectory.resolve("existing")));
            assertFalse(Files.exists(dataPacksDirectory.resolve("imported.zip.disabled")));
            EdtDispatcher.executeAndWait(() ->
                    assertEquals(OptionalInt.of(0), panel.dataPackChoiceList().getChoiceModel().exactItemCount()));
        } finally {
            @Nullable DataPackManagementPanel panel = panelReference.get();
            if (panel != null) {
                panel.close();
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// A selected supported world receives every dropped ZIP while adjacent files are ignored.
    @Test
    void installsSupportedDroppedDataPacksOnlyForTheSelectedWorld() throws Exception {
        Path savesDirectory = Files.createDirectories(temporaryDirectory.resolve("drop-run").resolve("saves"));
        Path worldDirectory = createWorldDirectory(savesDirectory, "drop-world");
        Path dataPacksDirectory = Files.createDirectories(worldDirectory.resolve("datapacks"));
        Path first = createDataPackArchive(temporaryDirectory.resolve("first.zip"));
        Path unsupported = Files.createFile(temporaryDirectory.resolve("notes.txt"));
        Path second = createDataPackArchive(temporaryDirectory.resolve("SECOND.ZIP"));
        WorldCatalogItem world = new WorldCatalogItem(
                worldDirectory,
                "drop-world",
                "drop-world",
                1L,
                "1.20.1",
                false,
                null);
        SingleWorldCatalogModel model = new SingleWorldCatalogModel(savesDirectory, world);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<@Nullable DataPackManagementPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> panelReference.set(new DataPackManagementPanel(
                    model,
                    DataPackManagementStrings.english(),
                    new RecordingInteractions(first),
                    executor)));
            DataPackManagementPanel panel = Objects.requireNonNull(panelReference.get());

            EdtDispatcher.executeAndWait(() -> {
                TransferHandler handler = Objects.requireNonNull(panel.getTransferHandler());
                assertFalse(handler.canImport(fileTransfer(panel, List.of(first))));
                panel.activate();
                prepareViewport(panel.worldChoiceList());
                panel.worldChoiceList().getList().setSelectedIndex(0);
            });
            awaitBackgroundWork(executor);

            EdtDispatcher.executeAndWait(() -> {
                TransferHandler handler = Objects.requireNonNull(panel.getTransferHandler());
                TransferHandler.TransferSupport transfer = fileTransfer(
                        panel,
                        List.of(first, unsupported, second));
                assertTrue(handler.canImport(transfer));
                assertTrue(handler.importData(transfer));
                assertFalse(handler.canImport(fileTransfer(panel, List.of(first))));
            });
            awaitBackgroundWork(executor);

            assertTrue(Files.isRegularFile(dataPacksDirectory.resolve("first.zip")));
            assertTrue(Files.isRegularFile(dataPacksDirectory.resolve("SECOND.ZIP")));
            EdtDispatcher.executeAndWait(() -> {
                panel.close();
                assertNull(panel.getTransferHandler());
            });
        } finally {
            @Nullable DataPackManagementPanel panel = panelReference.get();
            if (panel != null) {
                panel.close();
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// A constrained real page host gives both existing list scroll panes a positive viewport instead of clipping.
    @Test
    void constrainedHeightShrinksTheSplitIntoBothChoiceListViewports() {
        Path savesDirectory = temporaryDirectory.resolve("compact-saves");
        WorldCatalogItem world = new WorldCatalogItem(
                savesDirectory.resolve("compact-world"),
                "compact-world",
                "Compact World",
                1L,
                "1.21.1",
                false,
                null);
        SingleWorldCatalogModel model = new SingleWorldCatalogModel(savesDirectory, world);
        RecordingInteractions interactions = new RecordingInteractions(temporaryDirectory.resolve("unused.zip"));

        EdtDispatcher.executeAndWait(() -> {
            DataPackManagementPanel panel = new DataPackManagementPanel(
                    model,
                    DataPackManagementStrings.english(),
                    interactions,
                    Runnable::run);
            panel.setSize(new Dimension(800, 220));
            layoutRecursively(panel);

            JSplitPane split = Objects.requireNonNull(
                    findNamed(panel, "dataPackManagementSplit", JSplitPane.class));
            assertEquals(new Dimension(0, 0), split.getMinimumSize());
            assertTrue(panel.worldChoiceList().getViewport().getExtentSize().height > 0);
            assertTrue(panel.dataPackChoiceList().getViewport().getExtentSize().height > 0);
            panel.close();
        });
    }

    /// Gives a detached viewport list deterministic geometry and requests its visible rows.
    ///
    /// @param choiceList detached list whose source should receive a viewport demand
    private static void prepareViewport(ViewportChoiceList<?> choiceList) {
        choiceList.setSize(320, 180);
        choiceList.getViewport().setExtentSize(new Dimension(320, 160));
        choiceList.getList().setSize(320, 160);
        choiceList.refreshLoadPlan();
    }

    /// Recursively lays out only the dimensions allocated by the real parent hierarchy.
    ///
    /// @param container root or nested container
    private static void layoutRecursively(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                layoutRecursively(nested);
            }
        }
    }

    /// Runs a FIFO executor barrier and all EDT callbacks queued before it.
    ///
    /// @param executor background executor used by the panel
    /// @throws Exception when background work does not settle promptly
    private static void awaitBackgroundWork(ExecutorService executor) throws Exception {
        executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
        drainEdt();
    }

    /// Flushes callbacks queued on the Swing event-dispatch thread.
    private static void drainEdt() {
        EdtDispatcher.executeAndWait(() -> { });
    }

    /// Finds a named descendant with the requested component type.
    ///
    /// @param root root component to inspect
    /// @param name deterministic target component name
    /// @param type expected Swing component type
    /// @param <T> expected component type
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

    /// Creates the minimum valid world NBT needed by Core World and data-pack support checks.
    ///
    /// @param savesDirectory instance saves directory
    /// @param name durable world directory and stored level name
    /// @return valid local world directory
    /// @throws IOException when the test fixture cannot be written
    private static Path createWorldDirectory(Path savesDirectory, String name) throws IOException {
        Path directory = Files.createDirectory(savesDirectory.resolve(name));
        CompoundTag version = new CompoundTag().addString("Name", "1.20.1");
        CompoundTag data = new CompoundTag()
                .addString("LevelName", name)
                .addLong("LastPlayed", 1L)
                .addTag("Version", version);
        CompoundTag root = new CompoundTag().addTag("Data", data);
        try (OutputStream output = new GZIPOutputStream(Files.newOutputStream(directory.resolve("level.dat")))) {
            NBTCodec.of().writeTag(output, root);
        }
        return directory;
    }

    /// Creates one enabled directory-form data pack.
    ///
    /// @param dataPacksDirectory world data-pack directory
    /// @param name durable pack identifier
    /// @throws IOException when the fixture cannot be written
    private static void createDirectoryDataPack(Path dataPacksDirectory, String name) throws IOException {
        Path packDirectory = Files.createDirectory(dataPacksDirectory.resolve(name));
        Files.writeString(packDirectory.resolve("pack.mcmeta"), packMetadata());
    }

    /// Creates a single-pack ZIP archive accepted by Core DataPack installation.
    ///
    /// @param archive target archive path
    /// @return the completed archive path
    /// @throws IOException when the archive cannot be written
    private static Path createDataPackArchive(Path archive) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("pack.mcmeta"));
            output.write(packMetadata().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return archive;
    }

    /// Returns deterministic valid pack metadata.
    ///
    /// @return valid `pack.mcmeta` JSON
    private static String packMetadata() {
        return "{\"pack\":{\"pack_format\":15,\"description\":\"fixture\"}}";
    }

    /// Minimal exact lazy-world model exposing a single valid world only through viewport demand.
    @NotNullByDefault
    private static final class SingleWorldCatalogModel implements WorldCatalogModel {
        /// Directories belonging to the managed test instance.
        private final Path savesDirectory;

        /// Viewport-materialized test world row.
        private final WorldCatalogItem world;

        /// Counts explicit lazy activation requests.
        private final AtomicInteger loadRequests = new AtomicInteger();

        /// Stable ready snapshot because test data already has a known shallow index.
        private final WorldCatalogSnapshot snapshot = new WorldCatalogSnapshot(
                OptionalInt.of(1),
                1L,
                WorldCatalogStatus.READY,
                "1 world",
                "",
                true,
                true,
                false);

        /// Creates a deterministic one-world source for a detached Swing panel test.
        ///
        /// @param savesDirectory managed saves directory
        /// @param world valid row returned for viewport index zero
        private SingleWorldCatalogModel(Path savesDirectory, WorldCatalogItem world) {
            this.savesDirectory = Objects.requireNonNull(savesDirectory, "savesDirectory");
            this.world = Objects.requireNonNull(world, "world");
        }

        /// Returns the immutable ready snapshot without filesystem work.
        ///
        /// @return one-world ready snapshot
        @Override
        public WorldCatalogSnapshot snapshot() {
            return snapshot;
        }

        /// Creates a no-op subscription because this deterministic test source never transitions.
        ///
        /// @param listener ignored future-change listener
        /// @return independently closable no-op subscription
        @Override
        public Subscription subscribe(ValueChangeListener<WorldCatalogSnapshot> listener) {
            Objects.requireNonNull(listener, "listener");
            return Subscription.create(() -> { });
        }

        /// Returns the managed saves directory without listing it.
        ///
        /// @return stable local saves directory
        @Override
        public Path savesDirectory() {
            return savesDirectory;
        }

        /// Records the panel's lazy activation transition.
        @Override
        public void loadIfNeeded() {
            loadRequests.incrementAndGet();
        }

        /// Records an explicit refresh request as another index request.
        @Override
        public void refresh() {
            loadRequests.incrementAndGet();
        }

        /// Rejects unsupported world archive work from this focused data-pack test source.
        ///
        /// @param archive unused archive path
        /// @return failed future explaining the unsupported operation
        @Override
        public CompletionStage<WorldCatalogImport> inspectImport(Path archive) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("World import is outside this test"));
        }

        /// Rejects unsupported world installation from this focused data-pack test source.
        ///
        /// @param world unused world import candidate
        /// @param targetName unused installation target name
        /// @return failed future explaining the unsupported operation
        @Override
        public CompletionStage<WorldCatalogSnapshot> installWorld(
                WorldCatalogImport world,
                String targetName) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("World install is outside this test"));
        }

        /// Rejects unsupported world deletion from this focused data-pack test source.
        ///
        /// @param world unused selected world
        /// @return failed future explaining the unsupported operation
        @Override
        public CompletionStage<WorldCatalogSnapshot> deleteWorld(WorldCatalogItem world) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("World deletion is outside this test"));
        }

        /// Exposes the single known shallow-index row count.
        ///
        /// @return exact count one
        @Override
        public OptionalInt exactItemCount() {
            return OptionalInt.of(1);
        }

        /// Exposes a stable revision for the immutable one-world source.
        ///
        /// @return stable source revision one
        @Override
        public OptionalLong sourceRevision() {
            return OptionalLong.of(1L);
        }

        /// Returns the single world only for the requested visible range.
        ///
        /// @param desiredRange viewport-derived range
        /// @param cancellation cooperative cancellation signal
        /// @return immediately completed page for the clamped range
        @Override
        public CompletionStage<ChoicePage<WorldCatalogItem>> load(
                IndexRange desiredRange,
                LoadCancellation cancellation) {
            IndexRange requested = Objects.requireNonNull(desiredRange, "desiredRange");
            Objects.requireNonNull(cancellation, "cancellation").throwIfCancelled();
            IndexRange effective = requested.clampToItemCount(1);
            List<WorldCatalogItem> values = effective.isEmpty() ? List.of() : List.of(world);
            return CompletableFuture.completedFuture(new ChoicePage<>(
                    effective,
                    values,
                    OptionalInt.of(1),
                    effective.endExclusive() == 1));
        }

        /// Releases no resources because this deterministic source owns no work.
        @Override
        public void close() {
        }
    }

    /// Native-interaction substitute that records true panel requests without opening dialogs or Desktop.
    @NotNullByDefault
    private static final class RecordingInteractions implements DataPackManagementInteractions {
        /// Archive returned by the deterministic import chooser.
        private final Path archive;

        /// Last directory passed to the reveal boundary, or `null` before a reveal command.
        private final AtomicReference<@Nullable Path> openedDirectory = new AtomicReference<>();

        /// Creates interactions that always choose the supplied valid archive.
        ///
        /// @param archive valid local data-pack ZIP
        private RecordingInteractions(Path archive) {
            this.archive = Objects.requireNonNull(archive, "archive");
        }

        /// Returns the configured local archive instead of opening a native chooser.
        ///
        /// @param owner unused chooser owner
        /// @param initialDirectory unused initial directory
        /// @return configured valid ZIP archive
        @Override
        public Path chooseDataPackArchive(Component owner, Path initialDirectory) {
            return archive;
        }

        /// Always accepts deletion so the test exercises the true Core filesystem mutation.
        ///
        /// @param owner unused dialog owner
        /// @param dataPacks unused selected data packs
        /// @return true
        @Override
        public boolean confirmDelete(Component owner, @Unmodifiable List<DataPack.Pack> dataPacks) {
            return true;
        }

        /// Records the requested directory and returns a successful immediate desktop stage.
        ///
        /// @param directory directory requested by the panel
        /// @return completed successful nullable-void stage
        @Override
        public CompletionStage<@Nullable Void> openDirectory(Path directory) {
            openedDirectory.set(directory);
            return CompletableFuture.completedFuture(null);
        }

        /// Fails the test if any action reports an unexpected error dialog.
        ///
        /// @param owner unused dialog owner
        /// @param title unused dialog title
        /// @param detail unexpected concise failure detail
        @Override
        public void showFailure(Component owner, String title, String detail) {
            throw new AssertionError("Unexpected data-pack operation failure: " + detail);
        }
    }
}
