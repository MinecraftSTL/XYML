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
package space.minecraftstl.xyml.ui.swing.page.schematics;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.schematic.LitematicFile;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.choice.ChoiceListEntry;
import space.minecraftstl.xyml.ui.swing.choice.ChoicePage;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;

import javax.swing.AbstractButton;
import javax.swing.JList;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Headless Swing tests for schematic browser loading, geometry, navigation, details, and closure.
@NotNullByDefault
public final class SchematicBrowserPanelTest {
    /// Localized browser text used by focused panel tests.
    private static final SchematicBrowserStrings STRINGS = new SchematicBrowserStrings(
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

    /// Construction stays I/O-free, start is lazy, and repeated close releases the model only once.
    @Test
    public void constructionIsIoFreeAndStartIsLazyAndIdempotent() {
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        FakeSchematicBrowserModel model = FakeSchematicBrowserModel.immediate(
                List.of(), snapshot(root, root, OptionalInt.empty(), 0L, SchematicBrowserStatus.IDLE, null, false));
        SchematicBrowserPanel panel = onEventDispatchThread(() -> new SchematicBrowserPanel(model, STRINGS));

        assertAll(
                () -> assertEquals(0, model.initialLoads.get()),
                () -> assertEquals(List.of(), model.requestedRanges()),
                () -> assertTrue(model.hasSubscribers()));

        onEventDispatchThread(() -> {
            assertAll(
                    () -> assertTrue(assertInstanceOf(
                            FlatSVGIcon.class, findButton(panel, "schematicsReturn").getIcon()).hasFound()),
                    () -> assertTrue(assertInstanceOf(
                            FlatSVGIcon.class, findButton(panel, "schematicsRefresh").getIcon()).hasFound()),
                    () -> assertTrue(assertInstanceOf(
                            FlatSVGIcon.class, findButton(panel, "schematicsOpenDirectory").getIcon()).hasFound()));
            panel.start();
            panel.start();
            assertEquals(1, model.initialLoads.get());
            panel.close();
            panel.close();
        });
        assertAll(
                () -> assertEquals(1, model.closeCalls.get()),
                () -> assertTrue(model.closedOnEdt.get()),
                () -> assertFalse(model.hasSubscribers()));
    }

    /// The first request contains exactly the rows implied by actual laid-out viewport geometry.
    @Test
    public void viewportGeometryDeterminesTheRequestedRange() {
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        @Unmodifiable List<SchematicBrowserItem> rows = directories(root, 1_000);
        FakeSchematicBrowserModel model = FakeSchematicBrowserModel.immediate(
                rows,
                snapshot(root, root, OptionalInt.of(rows.size()), 1L, SchematicBrowserStatus.READY, null, false));
        SchematicBrowserPanel panel = onEventDispatchThread(() -> new SchematicBrowserPanel(model, STRINGS));

        onEventDispatchThread(() -> {
            panel.setSize(new Dimension(940, 520));
            layoutRecursively(panel);
            panel.choiceList().refreshLoadPlan();

            JList<ChoiceListEntry<SchematicBrowserItem>> list = panel.choiceList().getList();
            IndexRange requested = model.requestedRanges().get(0);
            int viewportHeight = panel.choiceList().getViewport().getExtentSize().height;
            int rowHeight = list.getFixedCellHeight();
            int expectedVisibleRows = (viewportHeight + rowHeight - 1) / rowHeight;
            assertAll(
                    () -> assertEquals(expectedVisibleRows, requested.length()),
                    () -> assertTrue(requested.length() < rows.size()),
                    () -> assertEquals(javax.swing.ListSelectionModel.SINGLE_SELECTION,
                            list.getSelectionMode()));
            panel.close();
        });
    }

    /// Toolbar and double-click navigation work, worker errors expose retry, and new content clears selection.
    @Test
    public void navigatesRefreshesRetriesAndReloadsChangedDirectory() throws Exception {
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        Path child = root.resolve("child");
        @Unmodifiable List<SchematicBrowserItem> rootRows = List.of(
                new SchematicDirectoryItem(child, "child"));
        FakeSchematicBrowserModel model = FakeSchematicBrowserModel.immediate(
                rootRows,
                snapshot(root, root, OptionalInt.of(1), 1L, SchematicBrowserStatus.READY, null, false));
        SchematicBrowserPanel panel = onEventDispatchThread(() -> new SchematicBrowserPanel(model, STRINGS));

        onEventDispatchThread(() -> {
            panel.setSize(new Dimension(900, 480));
            layoutRecursively(panel);
            panel.choiceList().refreshLoadPlan();
            panel.choiceList().getList().setSelectedIndex(0);
            findButton(panel, "schematicsOpenDirectory").doClick();

            JList<ChoiceListEntry<SchematicBrowserItem>> list = panel.choiceList().getList();
            Rectangle bounds = Objects.requireNonNull(list.getCellBounds(0, 0));
            MouseEvent doubleClick = new MouseEvent(
                    list,
                    MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(),
                    0,
                    bounds.x + 2,
                    bounds.y + 2,
                    2,
                    false,
                    MouseEvent.BUTTON1);
            list.dispatchEvent(doubleClick);

            findButton(panel, "schematicsRefresh").doClick();
            findButton(panel, "schematicsReturn").doClick();
            assertAll(
                    () -> assertEquals(List.of(child, child), model.openedDirectories()),
                    () -> assertEquals(1, model.refreshes.get()),
                    () -> assertEquals(0, model.parentReturns.get()));
        });

        SchematicBrowserSnapshot loading = snapshot(
                root, root, OptionalInt.of(1), 1L, SchematicBrowserStatus.LOADING, null, false);
        SchematicBrowserSnapshot error = snapshot(
                root, root, OptionalInt.of(1), 1L, SchematicBrowserStatus.ERROR, "disk unavailable", false);
        Thread publisher = new Thread(() -> {
            model.publish(rootRows, loading);
            model.publish(rootRows, error);
        }, "schematics-panel-error-publisher");
        publisher.start();
        publisher.join();
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> {
            assertAll(
                    () -> assertEquals(error, panel.displayedSnapshot()),
                    () -> assertTrue(findComponent(panel, "schematicsError").isVisible()),
                    () -> assertTrue(findButton(panel, "schematicsRetry").isEnabled()));
            findButton(panel, "schematicsRetry").doClick();
            assertEquals(2, model.refreshes.get());

            @Unmodifiable List<SchematicBrowserItem> childRows = List.of(
                    new SchematicFileItem(child.resolve("broken.litematic"), "broken.litematic", null, "broken"));
            int requestsBefore = model.requestedRanges().size();
            model.publish(
                    childRows,
                    snapshot(root, child, OptionalInt.of(1), 2L, SchematicBrowserStatus.READY, null, true));
            findButton(panel, "schematicsReturn").doClick();
            assertAll(
                    () -> assertEquals(-1, panel.choiceList().getList().getSelectedIndex()),
                    () -> assertEquals(child, panel.displayedSnapshot().currentDirectory()),
                    () -> assertTrue(model.requestedRanges().size() > requestsBefore),
                    () -> assertEquals(1, model.parentReturns.get()));
            panel.close();
        });
    }

    /// Selecting readable and unreadable files exposes metadata and retained parse failures.
    @Test
    public void displaysReadableMetadataAndUnreadableFailure() throws Exception {
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        LitematicFile metadata = LitematicFile.load(litematicFixture());
        @Unmodifiable List<SchematicBrowserItem> rows = List.of(
                new SchematicFileItem(root.resolve("readable.litematic"), "readable.litematic", metadata, null),
                new SchematicFileItem(root.resolve("broken.litematic"), "broken.litematic", null, "invalid gzip"));
        FakeSchematicBrowserModel model = FakeSchematicBrowserModel.immediate(
                rows,
                snapshot(root, root, OptionalInt.of(2), 1L, SchematicBrowserStatus.READY, null, false));
        SchematicBrowserPanel panel = onEventDispatchThread(() -> new SchematicBrowserPanel(model, STRINGS));

        onEventDispatchThread(() -> {
            panel.setSize(new Dimension(900, 520));
            layoutRecursively(panel);
            panel.choiceList().refreshLoadPlan();
            panel.choiceList().getList().setSelectedIndex(0);
            String readable = panel.displayedDetailsText();
            assertAll(
                    () -> assertTrue(readable.contains("Author: hsds")),
                    () -> assertTrue(readable.contains("Blocks: 1334")),
                    () -> assertTrue(readable.contains("Size: 17 x 26 x 13")),
                    () -> assertTrue(readable.contains("Preview:")));

            panel.choiceList().getList().setSelectedIndex(1);
            String unreadable = panel.displayedDetailsText();
            assertAll(
                    () -> assertTrue(unreadable.contains("Unreadable schematic")),
                    () -> assertTrue(unreadable.contains("invalid gzip")));
            panel.close();
        });
    }

    /// Close owns model disposal and drops worker notifications and viewport completions that arrive late.
    @Test
    public void closeDropsLateModelAndViewportCompletions() throws Exception {
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        @Unmodifiable List<SchematicBrowserItem> rows = directories(root, 20);
        SchematicBrowserSnapshot initial = snapshot(
                root, root, OptionalInt.of(rows.size()), 1L, SchematicBrowserStatus.READY, null, false);
        FakeSchematicBrowserModel model = FakeSchematicBrowserModel.controlled(rows, initial);
        SchematicBrowserPanel panel = onEventDispatchThread(() -> new SchematicBrowserPanel(model, STRINGS));
        onEventDispatchThread(() -> {
            panel.setSize(new Dimension(800, 420));
            layoutRecursively(panel);
            panel.start();
            panel.choiceList().refreshLoadPlan();
            assertFalse(model.pendingLoads().isEmpty());
        });

        Thread closer = new Thread(panel::close, "schematics-panel-close-worker");
        closer.start();
        closer.join();

        SchematicBrowserSnapshot late = snapshot(
                root, root.resolve("late"), OptionalInt.of(1), 2L, SchematicBrowserStatus.READY, null, true);
        model.publish(List.of(new SchematicDirectoryItem(root.resolve("late"), "late")), late);
        model.completePendingLoads();
        model.initialLoad.complete(late);
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> assertAll(
                () -> assertEquals(initial, panel.displayedSnapshot()),
                () -> assertEquals(0, panel.choiceList().getChoiceModel().getSize()),
                () -> assertFalse(findButton(panel, "schematicsRefresh").isEnabled()),
                () -> assertEquals(1, model.closeCalls.get()),
                () -> assertTrue(model.closedOnEdt.get()),
                () -> assertFalse(model.hasSubscribers())));
    }

    /// Creates deterministic directory rows.
    ///
    /// @param root row path root
    /// @param count row count
    /// @return immutable rows
    private static @Unmodifiable List<SchematicBrowserItem> directories(Path root, int count) {
        List<SchematicBrowserItem> rows = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            rows.add(new SchematicDirectoryItem(root.resolve("directory-" + index), "directory-" + index));
        }
        return List.copyOf(rows);
    }

    /// Creates one browser snapshot.
    ///
    /// @param root immutable root
    /// @param current current directory
    /// @param count exact count or empty before discovery
    /// @param revision content revision
    /// @param status lifecycle status
    /// @param failure failure text or null
    /// @param canReturn whether parent navigation is enabled
    /// @return immutable snapshot
    private static SchematicBrowserSnapshot snapshot(
            Path root,
            Path current,
            OptionalInt count,
            long revision,
            SchematicBrowserStatus status,
            @Nullable String failure,
            boolean canReturn) {
        return new SchematicBrowserSnapshot(root, current, count, revision, status, failure, canReturn);
    }

    /// Locates the existing core Litematic test fixture from either common Gradle working directory.
    ///
    /// @return existing fixture path
    private static Path litematicFixture() {
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        Path rootCandidate = workingDirectory.resolve(
                "XYMLCore/src/test/resources/schematics/test.litematic");
        if (Files.isRegularFile(rootCandidate)) {
            return rootCandidate;
        }
        Path subprojectCandidate = workingDirectory.resolve(
                "../XYMLCore/src/test/resources/schematics/test.litematic").normalize();
        if (Files.isRegularFile(subprojectCandidate)) {
            return subprojectCandidate;
        }
        throw new IllegalStateException("Missing Litematic test fixture from " + workingDirectory);
    }

    /// Finds a named button in a Swing hierarchy.
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
        for (Component child : root.getComponents()) {
            if (Objects.equals(name, child.getName())) {
                return child;
            }
            if (child instanceof Container nested) {
                try {
                    return findComponent(nested, name);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        throw new IllegalArgumentException("Missing component: " + name);
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

    /// Recursively lays out a hierarchy before viewport measurement.
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

    /// One captured viewport request awaiting optional test completion.
    ///
    /// @param range requested range
    /// @param rows immutable source rows captured at request time
    /// @param future request future
    @NotNullByDefault
    private record PendingLoad(
            IndexRange range,
            @Unmodifiable List<SchematicBrowserItem> rows,
            CompletableFuture<ChoicePage<SchematicBrowserItem>> future) {
    }

    /// Thread-safe fake model with immediate or controlled viewport completion.
    @NotNullByDefault
    private static final class FakeSchematicBrowserModel implements SchematicBrowserModel {
        /// Latest immutable indexed rows.
        private volatile @Unmodifiable List<SchematicBrowserItem> rows;

        /// Latest immutable browser state.
        private final AtomicReference<SchematicBrowserSnapshot> current;

        /// Model transition publisher.
        private final ValueChangeSupport<SchematicBrowserSnapshot> changes = new ValueChangeSupport<>(this);

        /// Whether viewport requests complete immediately.
        private final boolean immediateLoads;

        /// Captured viewport ranges.
        private final List<IndexRange> ranges = new ArrayList<>();

        /// Controlled viewport requests.
        private final List<PendingLoad> pending = new ArrayList<>();

        /// Opened directory paths.
        private final List<Path> opened = new ArrayList<>();

        /// Initial lazy-load completion.
        private final CompletableFuture<SchematicBrowserSnapshot> initialLoad = new CompletableFuture<>();

        /// Number of initial-load requests.
        private final AtomicInteger initialLoads = new AtomicInteger();

        /// Number of refresh commands.
        private final AtomicInteger refreshes = new AtomicInteger();

        /// Number of parent navigation commands.
        private final AtomicInteger parentReturns = new AtomicInteger();

        /// Number of close calls.
        private final AtomicInteger closeCalls = new AtomicInteger();

        /// Whether model close ran on the EDT.
        private final AtomicBoolean closedOnEdt = new AtomicBoolean();

        /// Creates one fake model.
        ///
        /// @param rows initial rows
        /// @param snapshot initial state
        /// @param immediateLoads whether viewport stages complete immediately
        private FakeSchematicBrowserModel(
                @Unmodifiable List<SchematicBrowserItem> rows,
                SchematicBrowserSnapshot snapshot,
                boolean immediateLoads) {
            this.rows = List.copyOf(rows);
            current = new AtomicReference<>(snapshot);
            this.immediateLoads = immediateLoads;
        }

        /// Creates an immediate fake.
        ///
        /// @param rows initial rows
        /// @param snapshot initial state
        /// @return immediate fake
        private static FakeSchematicBrowserModel immediate(
                @Unmodifiable List<SchematicBrowserItem> rows,
                SchematicBrowserSnapshot snapshot) {
            return new FakeSchematicBrowserModel(rows, snapshot, true);
        }

        /// Creates a controlled fake.
        ///
        /// @param rows initial rows
        /// @param snapshot initial state
        /// @return controlled fake
        private static FakeSchematicBrowserModel controlled(
                @Unmodifiable List<SchematicBrowserItem> rows,
                SchematicBrowserSnapshot snapshot) {
            return new FakeSchematicBrowserModel(rows, snapshot, false);
        }

        /// Returns current fake state.
        @Override
        public SchematicBrowserSnapshot snapshot() {
            return current.get();
        }

        /// Registers a fake state listener.
        @Override
        public Subscription subscribe(ValueChangeListener<SchematicBrowserSnapshot> listener) {
            return changes.subscribe(listener);
        }

        /// Returns the current exact count.
        @Override
        public OptionalInt exactItemCount() {
            return current.get().itemCount();
        }

        /// Captures and optionally completes one viewport request.
        @Override
        public synchronized CompletionStage<ChoicePage<SchematicBrowserItem>> load(
                IndexRange desiredRange,
                LoadCancellation cancellation) {
            ranges.add(desiredRange);
            @Unmodifiable List<SchematicBrowserItem> captured = rows;
            if (immediateLoads) {
                return CompletableFuture.completedFuture(page(desiredRange, captured));
            }
            CompletableFuture<ChoicePage<SchematicBrowserItem>> future = new CompletableFuture<>();
            pending.add(new PendingLoad(desiredRange, captured, future));
            return future;
        }

        /// Records one initial lazy-load request.
        @Override
        public CompletionStage<SchematicBrowserSnapshot> loadIfNeeded() {
            initialLoads.incrementAndGet();
            return initialLoad;
        }

        /// Records one refresh command.
        @Override
        public CompletionStage<SchematicBrowserSnapshot> refresh() {
            refreshes.incrementAndGet();
            return CompletableFuture.completedFuture(current.get());
        }

        /// Records one child navigation command.
        @Override
        public synchronized CompletionStage<SchematicBrowserSnapshot> openDirectory(Path directory) {
            opened.add(directory);
            return CompletableFuture.completedFuture(current.get());
        }

        /// Records one parent navigation command.
        @Override
        public CompletionStage<SchematicBrowserSnapshot> returnToParent() {
            parentReturns.incrementAndGet();
            return CompletableFuture.completedFuture(current.get());
        }

        /// Rejects imports because existing panel tests do not expose write interactions.
        @Override
        public CompletionStage<SchematicBrowserSnapshot> importFiles(List<Path> sourceFiles) {
            return unsupportedWrite();
        }

        /// Rejects directory creation because existing panel tests do not expose write interactions.
        @Override
        public CompletionStage<SchematicBrowserSnapshot> createDirectory(String directoryName) {
            return unsupportedWrite();
        }

        /// Rejects deletion because existing panel tests do not expose write interactions.
        @Override
        public CompletionStage<SchematicBrowserSnapshot> delete(Path target) {
            return unsupportedWrite();
        }

        /// Returns one explicit unsupported write stage without changing fake state.
        ///
        /// @return asynchronously observable unsupported-operation failure
        private static CompletionStage<SchematicBrowserSnapshot> unsupportedWrite() {
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("Panel fake does not implement writes"));
        }

        /// Records owned model disposal and its thread.
        @Override
        public void close() {
            closeCalls.incrementAndGet();
            closedOnEdt.set(SwingUtilities.isEventDispatchThread());
        }

        /// Publishes replacement rows and state from any test thread.
        ///
        /// @param replacement replacement rows
        /// @param snapshot replacement state
        private void publish(
                @Unmodifiable List<SchematicBrowserItem> replacement,
                SchematicBrowserSnapshot snapshot) {
            rows = List.copyOf(replacement);
            SchematicBrowserSnapshot previous = current.getAndSet(snapshot);
            changes.fireChange(previous, snapshot);
        }

        /// Returns captured viewport ranges.
        ///
        /// @return immutable ranges
        private synchronized @Unmodifiable List<IndexRange> requestedRanges() {
            return List.copyOf(ranges);
        }

        /// Returns opened directories.
        ///
        /// @return immutable opened paths
        private synchronized @Unmodifiable List<Path> openedDirectories() {
            return List.copyOf(opened);
        }

        /// Returns pending viewport requests.
        ///
        /// @return immutable pending requests
        private synchronized @Unmodifiable List<PendingLoad> pendingLoads() {
            return List.copyOf(pending);
        }

        /// Completes every currently pending viewport request.
        private void completePendingLoads() {
            @Unmodifiable List<PendingLoad> loads;
            synchronized (this) {
                loads = List.copyOf(pending);
                pending.clear();
            }
            for (PendingLoad load : loads) {
                load.future().complete(page(load.range(), load.rows()));
            }
        }

        /// Returns whether a panel subscriber remains.
        ///
        /// @return whether listeners remain
        private boolean hasSubscribers() {
            return changes.hasSubscribers();
        }

        /// Creates one exact clamped page.
        ///
        /// @param desiredRange requested range
        /// @param rows source rows
        /// @return exact page
        private static ChoicePage<SchematicBrowserItem> page(
                IndexRange desiredRange,
                @Unmodifiable List<SchematicBrowserItem> rows) {
            IndexRange actual = desiredRange.clampToItemCount(rows.size());
            return new ChoicePage<>(
                    actual,
                    List.copyOf(rows.subList(actual.startInclusive(), actual.endExclusive())),
                    OptionalInt.of(rows.size()),
                    actual.endExclusive() == rows.size());
        }
    }
}
