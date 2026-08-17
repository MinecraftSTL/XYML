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
import org.junit.jupiter.api.io.TempDir;
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
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
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
import static space.minecraftstl.xyml.ui.swing.SwingFileTransferTestSupport.fileTransfer;

/// Headless Swing tests for schematic browser loading, geometry, navigation, details, and closure.
@NotNullByDefault
public final class SchematicBrowserPanelTest {
    /// Temporary regular files used by shape-aware drop tests.
    @TempDir
    private Path temporaryDirectory;

    /// Localized file-operation text used by focused panel tests.
    private static final SchematicBrowserActionStrings ACTION_STRINGS = new SchematicBrowserActionStrings(
            "Import",
            "Import Litematic files",
            "Import schematics",
            "Litematic files",
            "New folder",
            "Create a schematic folder",
            "Folder name",
            "Delete",
            "Delete selected item",
            "Delete %s?",
            "Reveal",
            "Reveal selected item",
            "Updating schematics",
            "Unable to update schematics",
            "Schematic operation failed",
            "Unable to reveal schematic");

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
                    "Unavailable"),
            ACTION_STRINGS);

    /// Construction stays I/O-free, start is lazy, and repeated close releases the model only once.
    @Test
    public void constructionIsIoFreeAndStartIsLazyAndIdempotent() {
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        FakeSchematicBrowserModel model = FakeSchematicBrowserModel.immediate(
                List.of(), snapshot(root, root, OptionalInt.empty(), 0L, SchematicBrowserStatus.IDLE, null, false));
        SchematicBrowserPanel panel = onEventDispatchThread(() ->
                new SchematicBrowserPanel(model, STRINGS, new FakeSchematicBrowserInteractions()));

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
                            FlatSVGIcon.class, findButton(panel, "schematicsImport").getIcon()).hasFound()),
                    () -> assertTrue(assertInstanceOf(
                            FlatSVGIcon.class,
                            findButton(panel, "schematicsCreateDirectory").getIcon()).hasFound()),
                    () -> assertTrue(assertInstanceOf(
                            FlatSVGIcon.class,
                            findButton(panel, "schematicsOpenDirectory").getIcon()).hasFound()),
                    () -> assertTrue(assertInstanceOf(
                            FlatSVGIcon.class, findButton(panel, "schematicsReveal").getIcon()).hasFound()),
                    () -> assertTrue(assertInstanceOf(
                            FlatSVGIcon.class, findButton(panel, "schematicsDelete").getIcon()).hasFound()),
                    () -> assertAccessibleAction(
                            findButton(panel, "schematicsImport"),
                            ACTION_STRINGS.importAction(),
                            ACTION_STRINGS.importTooltip()),
                    () -> assertAccessibleAction(
                            findButton(panel, "schematicsCreateDirectory"),
                            ACTION_STRINGS.createDirectoryAction(),
                            ACTION_STRINGS.createDirectoryTooltip()),
                    () -> assertAccessibleAction(
                            findButton(panel, "schematicsOpenDirectory"),
                            STRINGS.openDirectoryAction(),
                            STRINGS.openDirectoryTooltip()),
                    () -> assertAccessibleAction(
                            findButton(panel, "schematicsReveal"),
                            ACTION_STRINGS.revealAction(),
                            ACTION_STRINGS.revealTooltip()),
                    () -> assertAccessibleAction(
                            findButton(panel, "schematicsDelete"),
                            ACTION_STRINGS.deleteAction(),
                            ACTION_STRINGS.deleteTooltip()));
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

    /// The first request contains visible rows plus one measured viewport of forward warming.
    @Test
    public void viewportGeometryDeterminesTheRequestedRange() {
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        @Unmodifiable List<SchematicBrowserItem> rows = directories(root, 1_000);
        FakeSchematicBrowserModel model = FakeSchematicBrowserModel.immediate(
                rows,
                snapshot(root, root, OptionalInt.of(rows.size()), 1L, SchematicBrowserStatus.READY, null, false));
        SchematicBrowserPanel panel = onEventDispatchThread(() ->
                new SchematicBrowserPanel(model, STRINGS, new FakeSchematicBrowserInteractions()));

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
                    () -> assertEquals(expectedVisibleRows * 2, requested.length()),
                    () -> assertTrue(requested.length() < rows.size()),
                    () -> assertEquals(javax.swing.ListSelectionModel.SINGLE_SELECTION,
                            list.getSelectionMode()));
            panel.close();
        });
    }

    /// Long localized commands collapse to icons and remain inside narrow action strips.
    @Test
    public void narrowActionStripsUseStableIconPresentation() {
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        SchematicDirectoryItem row = new SchematicDirectoryItem(root.resolve("child"), "child");
        FakeSchematicBrowserModel model = FakeSchematicBrowserModel.immediate(
                List.of(row),
                snapshot(root, root, OptionalInt.of(1), 1L, SchematicBrowserStatus.READY, null, false));
        SchematicBrowserStrings longStrings = longActionStrings();
        SchematicBrowserPanel panel = onEventDispatchThread(() -> new SchematicBrowserPanel(
                model, longStrings, new FakeSchematicBrowserInteractions()));

        onEventDispatchThread(() -> {
            panel.setSize(new Dimension(480, 420));
            panel.start();
            layoutRecursively(panel);

            @Unmodifiable List<AbstractButton> actions = List.of(
                    findButton(panel, "schematicsReturn"),
                    findButton(panel, "schematicsRefresh"),
                    findButton(panel, "schematicsImport"),
                    findButton(panel, "schematicsCreateDirectory"),
                    findButton(panel, "schematicsOpenDirectory"),
                    findButton(panel, "schematicsReveal"),
                    findButton(panel, "schematicsDelete"));
            for (AbstractButton action : actions) {
                assertAll(
                        () -> assertNull(action.getText()),
                        () -> assertTrue(Objects.requireNonNull(
                                action.getAccessibleContext().getAccessibleName()).length() > 20),
                        () -> assertActionWithinParent(action));
            }
            model.publish(
                    List.of(row),
                    snapshot(
                            root,
                            root,
                            OptionalInt.of(1),
                            1L,
                            SchematicBrowserStatus.LOADING,
                            null,
                            false));
            layoutRecursively(panel);
            AbstractButton refresh = findButton(panel, "schematicsRefresh");
            assertAll(
                    () -> assertNull(refresh.getText()),
                    () -> assertEquals(
                            longStrings.refreshingAction(),
                            refresh.getAccessibleContext().getAccessibleName()),
                    () -> assertActionWithinParent(refresh));
            panel.close();
        });
    }

    /// The full-label threshold includes both outer FlowLayout gaps without wrapping a hidden row.
    @Test
    public void actionStripUsesExactFullLabelWidthThreshold() {
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        FakeSchematicBrowserModel model = FakeSchematicBrowserModel.immediate(
                List.of(),
                snapshot(root, root, OptionalInt.of(0), 1L, SchematicBrowserStatus.READY, null, false));
        SchematicBrowserPanel panel = onEventDispatchThread(() ->
                new SchematicBrowserPanel(model, STRINGS, new FakeSchematicBrowserInteractions()));

        onEventDispatchThread(() -> {
            AbstractButton returnAction = findButton(panel, "schematicsReturn");
            AbstractButton refreshAction = findButton(panel, "schematicsRefresh");
            Container actionStrip = returnAction.getParent();
            int fullWidth = actionStrip.getPreferredSize().width;

            actionStrip.setSize(fullWidth - 1, 40);
            actionStrip.doLayout();
            assertAll(
                    () -> assertNull(returnAction.getText()),
                    () -> assertNull(refreshAction.getText()),
                    () -> assertActionWithinParent(returnAction),
                    () -> assertActionWithinParent(refreshAction));

            actionStrip.setSize(fullWidth, 40);
            actionStrip.doLayout();
            assertAll(
                    () -> assertEquals(STRINGS.returnAction(), returnAction.getText()),
                    () -> assertEquals(STRINGS.refreshAction(), refreshAction.getText()),
                    () -> assertActionWithinParent(returnAction),
                    () -> assertActionWithinParent(refreshAction));
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
        SchematicBrowserPanel panel = onEventDispatchThread(() ->
                new SchematicBrowserPanel(model, STRINGS, new FakeSchematicBrowserInteractions()));

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
        SchematicBrowserPanel panel = onEventDispatchThread(() ->
                new SchematicBrowserPanel(model, STRINGS, new FakeSchematicBrowserInteractions()));

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
        SchematicBrowserPanel panel = onEventDispatchThread(() ->
                new SchematicBrowserPanel(model, STRINGS, new FakeSchematicBrowserInteractions()));
        onEventDispatchThread(() -> {
            panel.setSize(new Dimension(800, 420));
            layoutRecursively(panel);
            panel.start();
            panel.choiceList().refreshLoadPlan();
            assertFalse(model.pendingLoads().isEmpty());
        });
        @Unmodifiable List<ListDataListener> retainedListListeners = onEventDispatchThread(() ->
                List.of(panel.choiceList().getChoiceModel().getListDataListeners()));

        Thread closer = new Thread(panel::close, "schematics-panel-close-worker");
        closer.start();
        closer.join();

        SchematicBrowserSnapshot late = snapshot(
                root, root.resolve("late"), OptionalInt.of(1), 2L, SchematicBrowserStatus.READY, null, true);
        model.publish(List.of(new SchematicDirectoryItem(root.resolve("late"), "late")), late);
        model.completePendingLoads();
        model.initialLoad.complete(late);
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> {
            JTextArea details = assertInstanceOf(
                    JTextArea.class, findComponent(panel, "schematicsDetailsText"));
            details.setText("closed details sentinel");
            ListDataEvent lateListEvent = new ListDataEvent(
                    panel.choiceList().getChoiceModel(), ListDataEvent.CONTENTS_CHANGED, 0, 0);
            retainedListListeners.forEach(listener -> listener.contentsChanged(lateListEvent));

            assertAll(
                    () -> assertEquals(initial, panel.displayedSnapshot()),
                    () -> assertEquals(0, panel.choiceList().getChoiceModel().getSize()),
                    () -> assertEquals("closed details sentinel", details.getText()),
                    () -> assertFalse(retainedListListeners.isEmpty()),
                    () -> assertFalse(findButton(panel, "schematicsRefresh").isEnabled()),
                    () -> assertEquals(1, model.closeCalls.get()),
                    () -> assertTrue(model.closedOnEdt.get()),
                    () -> assertFalse(model.hasSubscribers()));
        });
    }

    /// Cancelled chooser, prompt, and confirmation dialogs never reach the model.
    @Test
    public void cancelledDialogsDoNotStartWrites() {
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        SchematicDirectoryItem row = new SchematicDirectoryItem(root.resolve("child"), "child");
        FakeSchematicBrowserModel model = FakeSchematicBrowserModel.immediate(
                List.of(row),
                snapshot(root, root, OptionalInt.of(1), 1L, SchematicBrowserStatus.READY, null, false));
        FakeSchematicBrowserInteractions interactions = new FakeSchematicBrowserInteractions();
        SchematicBrowserPanel panel = onEventDispatchThread(() ->
                new SchematicBrowserPanel(model, STRINGS, interactions));

        onEventDispatchThread(() -> {
            prepareLoadedList(panel);
            panel.choiceList().getList().setSelectedIndex(0);
            findButton(panel, "schematicsImport").doClick();
            findButton(panel, "schematicsCreateDirectory").doClick();
            findButton(panel, "schematicsDelete").doClick();

            assertAll(
                    () -> assertEquals(1, interactions.importChooserCalls),
                    () -> assertEquals(1, interactions.directoryPromptCalls),
                    () -> assertEquals(List.of(row), interactions.confirmedTargets),
                    () -> assertEquals(List.of(), model.importedFiles()),
                    () -> assertEquals(List.of(), model.createdDirectoryNames()),
                    () -> assertEquals(List.of(), model.deletedPaths()));
            panel.close();
        });
    }

    /// Confirmed actions pass the exact captured chooser values, name, row, and path.
    @Test
    public void confirmedActionsUseExactCapturedModelArguments() {
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        SchematicDirectoryItem row = new SchematicDirectoryItem(root.resolve("child"), "child");
        FakeSchematicBrowserModel model = FakeSchematicBrowserModel.immediate(
                List.of(row),
                snapshot(root, root, OptionalInt.of(1), 1L, SchematicBrowserStatus.READY, null, false));
        FakeSchematicBrowserInteractions interactions = new FakeSchematicBrowserInteractions();
        @Unmodifiable List<Path> sources = List.of(
                Path.of("source-one.litematic"),
                Path.of("source-two.litematic"));
        interactions.importSelection = sources;
        interactions.directoryName = "new folder";
        interactions.deletionConfirmed = true;
        SchematicBrowserPanel panel = onEventDispatchThread(() ->
                new SchematicBrowserPanel(model, STRINGS, interactions));

        onEventDispatchThread(() -> {
            prepareLoadedList(panel);
            panel.choiceList().getList().setSelectedIndex(0);
            findButton(panel, "schematicsImport").doClick();
            findButton(panel, "schematicsCreateDirectory").doClick();
            findButton(panel, "schematicsDelete").doClick();

            assertAll(
                    () -> assertEquals(root, interactions.importDirectory),
                    () -> assertEquals(List.of(sources), model.importedFiles()),
                    () -> assertEquals(List.of("new folder"), model.createdDirectoryNames()),
                    () -> assertEquals(List.of(row.path()), model.deletedPaths()),
                    () -> assertSame(row, interactions.confirmedTargets.get(0)));
            panel.close();
        });
    }

    /// A ready page imports supported dropped Litematica files and ignores adjacent payloads.
    @Test
    public void importsSupportedDroppedSchematicsOnlyWhileWritable() throws Exception {
        Path root = temporaryDirectory.resolve("schematics").toAbsolutePath().normalize();
        Path first = Files.createFile(temporaryDirectory.resolve("first.litematic"));
        Path unsupported = Files.createFile(temporaryDirectory.resolve("notes.txt"));
        Path second = Files.createFile(temporaryDirectory.resolve("SECOND.LITEMATIC"));
        SchematicBrowserSnapshot ready = snapshot(
                root, root, OptionalInt.of(0), 1L, SchematicBrowserStatus.READY, null, false);
        FakeSchematicBrowserModel model = FakeSchematicBrowserModel.immediate(List.of(), ready);
        SchematicBrowserPanel panel = onEventDispatchThread(() -> new SchematicBrowserPanel(
                model,
                STRINGS,
                new FakeSchematicBrowserInteractions()));

        onEventDispatchThread(() -> {
            TransferHandler handler = Objects.requireNonNull(panel.getTransferHandler());
            TransferHandler.TransferSupport transfer = fileTransfer(
                    panel,
                    List.of(first, unsupported, second));

            assertTrue(handler.canImport(transfer));
            assertTrue(handler.importData(transfer));
            assertEquals(List.of(List.of(first, second)), model.importedFiles());

            panel.close();
            assertNull(panel.getTransferHandler());
        });
    }

    /// Modal results are discarded after closure, directory changes, busy writes, or selection changes.
    @Test
    public void modalResultsAreRevalidatedBeforeModelMutation() {
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        @Unmodifiable List<SchematicBrowserItem> rows = List.of(
                new SchematicDirectoryItem(root.resolve("first"), "first"),
                new SchematicDirectoryItem(root.resolve("second"), "second"));
        SchematicBrowserSnapshot ready = snapshot(
                root, root, OptionalInt.of(2), 1L, SchematicBrowserStatus.READY, null, false);
        FakeSchematicBrowserModel model = FakeSchematicBrowserModel.immediate(rows, ready);
        FakeSchematicBrowserInteractions interactions = new FakeSchematicBrowserInteractions();
        interactions.importSelection = List.of(Path.of("source.litematic"));
        interactions.directoryName = "new-folder";
        interactions.deletionConfirmed = true;
        SchematicBrowserPanel panel = onEventDispatchThread(() ->
                new SchematicBrowserPanel(model, STRINGS, interactions));

        onEventDispatchThread(() -> {
            prepareLoadedList(panel);
            interactions.importDialogHook = () -> model.publish(
                    rows,
                    snapshot(
                            root,
                            root,
                            OptionalInt.of(2),
                            1L,
                            SchematicBrowserStatus.READY,
                            null,
                            false,
                            SchematicBrowserWriteStatus.BUSY,
                            null));
            findButton(panel, "schematicsImport").doClick();
            assertEquals(List.of(), model.importedFiles());

            model.publish(rows, ready);
            Path changedDirectory = root.resolve("changed");
            interactions.directoryDialogHook = () -> model.publish(
                    rows,
                    snapshot(
                            root,
                            changedDirectory,
                            OptionalInt.of(2),
                            2L,
                            SchematicBrowserStatus.READY,
                            null,
                            true));
            findButton(panel, "schematicsCreateDirectory").doClick();
            assertEquals(List.of(), model.createdDirectoryNames());

            SchematicBrowserSnapshot restored = snapshot(
                    root, root, OptionalInt.of(2), 3L, SchematicBrowserStatus.READY, null, false);
            model.publish(rows, restored);
            prepareLoadedList(panel);
            panel.choiceList().getList().setSelectedIndex(0);
            interactions.deleteDialogHook = () -> panel.choiceList().getList().setSelectedIndex(1);
            findButton(panel, "schematicsDelete").doClick();
            assertEquals(List.of(), model.deletedPaths());

            interactions.importDialogHook = panel::close;
            findButton(panel, "schematicsImport").doClick();
            assertAll(
                    () -> assertEquals(List.of(), model.importedFiles()),
                    () -> assertEquals(1, model.closeCalls.get()));
        });
    }

    /// Busy and failed writes retain content and selection while applying exact command gating.
    @Test
    public void writeLifecycleRetainsSelectionAndUsesGenericVisibleFailureStatus() {
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        Path current = root.resolve("nested");
        SchematicDirectoryItem row = new SchematicDirectoryItem(current.resolve("child"), "child");
        @Unmodifiable List<SchematicBrowserItem> rows = List.of(row);
        FakeSchematicBrowserModel model = FakeSchematicBrowserModel.immediate(
                rows,
                snapshot(root, current, OptionalInt.of(1), 1L, SchematicBrowserStatus.READY, null, true));
        SchematicBrowserPanel panel = onEventDispatchThread(() -> new SchematicBrowserPanel(
                model,
                STRINGS,
                new FakeSchematicBrowserInteractions()));

        onEventDispatchThread(() -> {
            prepareLoadedList(panel);
            JList<ChoiceListEntry<SchematicBrowserItem>> list = panel.choiceList().getList();
            list.setSelectedIndex(0);
            model.publish(
                    rows,
                    snapshot(
                            root,
                            current,
                            OptionalInt.of(1),
                            1L,
                            SchematicBrowserStatus.READY,
                            null,
                            true,
                            SchematicBrowserWriteStatus.BUSY,
                            null));

            JLabel status = assertInstanceOf(JLabel.class, findComponent(panel, "schematicsStatus"));
            assertAll(
                    () -> assertEquals(0, list.getSelectedIndex()),
                    () -> assertFalse(list.isEnabled()),
                    () -> assertFalse(findButton(panel, "schematicsReturn").isEnabled()),
                    () -> assertFalse(findButton(panel, "schematicsRefresh").isEnabled()),
                    () -> assertFalse(findButton(panel, "schematicsImport").isEnabled()),
                    () -> assertFalse(findButton(panel, "schematicsCreateDirectory").isEnabled()),
                    () -> assertFalse(findButton(panel, "schematicsOpenDirectory").isEnabled()),
                    () -> assertFalse(findButton(panel, "schematicsReveal").isEnabled()),
                    () -> assertFalse(findButton(panel, "schematicsDelete").isEnabled()),
                    () -> assertEquals(ACTION_STRINGS.writingStatus(), status.getText()));

            String diagnostic = "private disk diagnostic";
            model.publish(
                    rows,
                    snapshot(
                            root,
                            current,
                            OptionalInt.of(1),
                            1L,
                            SchematicBrowserStatus.READY,
                            null,
                            true,
                            SchematicBrowserWriteStatus.ERROR,
                            diagnostic));
            assertAll(
                    () -> assertEquals(0, list.getSelectedIndex()),
                    () -> assertTrue(list.isEnabled()),
                    () -> assertTrue(findButton(panel, "schematicsReturn").isEnabled()),
                    () -> assertTrue(findButton(panel, "schematicsRefresh").isEnabled()),
                    () -> assertTrue(findButton(panel, "schematicsImport").isEnabled()),
                    () -> assertTrue(findButton(panel, "schematicsCreateDirectory").isEnabled()),
                    () -> assertTrue(findButton(panel, "schematicsOpenDirectory").isEnabled()),
                    () -> assertTrue(findButton(panel, "schematicsReveal").isEnabled()),
                    () -> assertTrue(findButton(panel, "schematicsDelete").isEnabled()),
                    () -> assertEquals(ACTION_STRINGS.writeFailedStatus(), status.getText()),
                    () -> assertFalse(status.getText().contains(diagnostic)),
                    () -> assertTrue(Objects.requireNonNull(status.getToolTipText()).contains(diagnostic)),
                    () -> assertTrue(Objects.requireNonNull(
                            status.getAccessibleContext().getAccessibleDescription()).contains(diagnostic)));

            model.publish(
                    rows,
                    snapshot(root, current, OptionalInt.of(1), 2L, SchematicBrowserStatus.READY, null, true));
            assertEquals(-1, list.getSelectedIndex());
            panel.close();
        });
    }

    /// Published write errors suppress duplicate dialogs while unpublished failures remain visible.
    @Test
    public void writeFailureObservationDeduplicatesPublishedErrorsAndUnwrapsFailures() {
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        SchematicBrowserSnapshot ready = snapshot(
                root, root, OptionalInt.of(0), 1L, SchematicBrowserStatus.READY, null, false);
        FakeSchematicBrowserModel model = FakeSchematicBrowserModel.immediate(List.of(), ready);
        FakeSchematicBrowserInteractions interactions = new FakeSchematicBrowserInteractions();
        interactions.importSelection = List.of(Path.of("source.litematic"));
        interactions.directoryName = "invalid/name";
        SchematicBrowserPanel panel = onEventDispatchThread(() ->
                new SchematicBrowserPanel(model, STRINGS, interactions));

        onEventDispatchThread(() -> {
            CompletableFuture<SchematicBrowserSnapshot> publishedFailure = new CompletableFuture<>();
            model.setNextWriteCompletion(publishedFailure);
            findButton(panel, "schematicsImport").doClick();
            String detail = "disk full";
            model.publish(
                    List.of(),
                    snapshot(
                            root,
                            root,
                            OptionalInt.of(0),
                            1L,
                            SchematicBrowserStatus.READY,
                            null,
                            false,
                            SchematicBrowserWriteStatus.ERROR,
                            detail));
            publishedFailure.completeExceptionally(new IllegalStateException(detail));
            assertEquals(List.of(), interactions.failures);

            model.publish(List.of(), ready);
            model.setNextWriteCompletion(CompletableFuture.failedFuture(
                    new CompletionException(new ExecutionException(
                            new IllegalArgumentException("invalid component")))));
            findButton(panel, "schematicsCreateDirectory").doClick();
            assertEquals(
                    List.of(new FailurePresentation(
                            ACTION_STRINGS.operationFailedTitle(),
                            "invalid component",
                            true)),
                    interactions.failures);

            model.setNextWriteCompletion(CompletableFuture.failedFuture(
                    new CancellationException("closed")));
            findButton(panel, "schematicsImport").doClick();
            assertEquals(1, interactions.failures.size());
            panel.close();
        });
    }

    /// Reveal gates only itself, reports resolved failures on the EDT, and drops late close callbacks.
    @Test
    public void revealCompletionIsIsolatedAndCloseDropsLateFeedback() {
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        SchematicFileItem row = new SchematicFileItem(
                root.resolve("broken.litematic"),
                "broken.litematic",
                null,
                "unreadable");
        FakeSchematicBrowserModel model = FakeSchematicBrowserModel.immediate(
                List.of(row),
                snapshot(root, root, OptionalInt.of(1), 1L, SchematicBrowserStatus.READY, null, false));
        FakeSchematicBrowserInteractions interactions = new FakeSchematicBrowserInteractions();
        CompletableFuture<@Nullable Void> failedReveal = new CompletableFuture<>();
        interactions.revealCompletion = failedReveal;
        SchematicBrowserPanel panel = onEventDispatchThread(() ->
                new SchematicBrowserPanel(model, STRINGS, interactions));

        onEventDispatchThread(() -> {
            prepareLoadedList(panel);
            panel.choiceList().getList().setSelectedIndex(0);
            findButton(panel, "schematicsReveal").doClick();
            assertAll(
                    () -> assertSame(row, interactions.revealedTargets.get(0)),
                    () -> assertFalse(findButton(panel, "schematicsReveal").isEnabled()),
                    () -> assertTrue(findButton(panel, "schematicsDelete").isEnabled()),
                    () -> assertTrue(findButton(panel, "schematicsImport").isEnabled()),
                    () -> assertTrue(findButton(panel, "schematicsCreateDirectory").isEnabled()),
                    () -> assertTrue(findButton(panel, "schematicsRefresh").isEnabled()),
                    () -> assertTrue(panel.choiceList().getList().isEnabled()));
        });

        failedReveal.completeExceptionally(new CompletionException(
                new ExecutionException(new IllegalStateException("desktop unavailable"))));
        EdtDispatcher.executeAndWait(() -> { });
        onEventDispatchThread(() -> assertAll(
                () -> assertEquals(
                        List.of(new FailurePresentation(
                                ACTION_STRINGS.revealFailedTitle(),
                                "desktop unavailable",
                                true)),
                        interactions.failures),
                () -> assertTrue(findButton(panel, "schematicsReveal").isEnabled())));

        CompletableFuture<@Nullable Void> cancelledReveal = new CompletableFuture<>();
        onEventDispatchThread(() -> {
            interactions.revealCompletion = cancelledReveal;
            findButton(panel, "schematicsReveal").doClick();
        });
        cancelledReveal.completeExceptionally(new CancellationException("cancelled"));
        EdtDispatcher.executeAndWait(() -> { });
        onEventDispatchThread(() -> assertAll(
                () -> assertEquals(1, interactions.failures.size()),
                () -> assertTrue(findButton(panel, "schematicsReveal").isEnabled())));

        CompletableFuture<@Nullable Void> lateReveal = new CompletableFuture<>();
        onEventDispatchThread(() -> {
            interactions.revealCompletion = lateReveal;
            findButton(panel, "schematicsReveal").doClick();
            panel.close();
        });
        lateReveal.completeExceptionally(new IllegalStateException("late failure"));
        EdtDispatcher.executeAndWait(() -> { });
        onEventDispatchThread(() -> assertAll(
                () -> assertEquals(1, interactions.failures.size()),
                () -> assertFalse(findButton(panel, "schematicsReveal").isEnabled()),
                () -> assertEquals(1, model.closeCalls.get())));
    }

    /// Lays out the panel and resolves the first viewport range against an immediate fake model.
    ///
    /// @param panel panel under test
    private static void prepareLoadedList(SchematicBrowserPanel panel) {
        panel.setSize(new Dimension(900, 520));
        layoutRecursively(panel);
        panel.choiceList().refreshLoadPlan();
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

    /// Creates one browser snapshot with an explicit independent write lifecycle.
    ///
    /// @param root immutable root
    /// @param current current directory
    /// @param count exact count or empty before discovery
    /// @param revision content revision
    /// @param status scan lifecycle status
    /// @param failure scan failure text or null
    /// @param canReturn whether parent navigation is enabled
    /// @param writeStatus write lifecycle status
    /// @param writeFailure write failure text or null
    /// @return immutable snapshot
    private static SchematicBrowserSnapshot snapshot(
            Path root,
            Path current,
            OptionalInt count,
            long revision,
            SchematicBrowserStatus status,
            @Nullable String failure,
            boolean canReturn,
            SchematicBrowserWriteStatus writeStatus,
            @Nullable String writeFailure) {
        return new SchematicBrowserSnapshot(
                root,
                current,
                count,
                revision,
                status,
                failure,
                canReturn,
                writeStatus,
                writeFailure);
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

    /// Creates deliberately long command labels for responsive action-strip coverage.
    ///
    /// @return browser presentation with long visible action labels
    private static SchematicBrowserStrings longActionStrings() {
        String longReturn = "Return to the parent schematic directory immediately";
        String longRefresh = "Refresh every schematic in the current directory";
        String longOpen = "Open the selected schematic directory now";
        SchematicBrowserActionStrings longActions = new SchematicBrowserActionStrings(
                "Import multiple Litematic schematic files from this computer",
                ACTION_STRINGS.importTooltip(),
                ACTION_STRINGS.importDialogTitle(),
                ACTION_STRINGS.litematicFileDescription(),
                "Create a new child directory for schematic files",
                ACTION_STRINGS.createDirectoryTooltip(),
                ACTION_STRINGS.createDirectoryPrompt(),
                "Delete the selected schematic item permanently",
                ACTION_STRINGS.deleteTooltip(),
                ACTION_STRINGS.deleteConfirmationFormat(),
                "Reveal the selected schematic in the platform file manager",
                ACTION_STRINGS.revealTooltip(),
                ACTION_STRINGS.writingStatus(),
                ACTION_STRINGS.writeFailedStatus(),
                ACTION_STRINGS.operationFailedTitle(),
                ACTION_STRINGS.revealFailedTitle());
        return new SchematicBrowserStrings(
                STRINGS.pageTitle(),
                longReturn,
                STRINGS.returnTooltip(),
                longRefresh,
                longRefresh,
                STRINGS.refreshTooltip(),
                longOpen,
                STRINGS.openDirectoryTooltip(),
                STRINGS.idleText(),
                STRINGS.loadingText(),
                STRINGS.emptyText(),
                STRINGS.errorTitle(),
                STRINGS.retryAction(),
                STRINGS.detailsTitle(),
                STRINGS.noSelectionText(),
                STRINGS.directorySelectionText(),
                STRINGS.unreadableText(),
                STRINGS.directoryRowPrefix(),
                STRINGS.metadata(),
                longActions);
    }

    /// Verifies one laid-out action stays fully inside its responsive strip.
    ///
    /// @param action action being checked
    private static void assertActionWithinParent(AbstractButton action) {
        Container parent = action.getParent();
        Rectangle bounds = action.getBounds();
        assertAll(
                () -> assertTrue(bounds.width > 0),
                () -> assertTrue(bounds.height > 0),
                () -> assertTrue(bounds.x >= 0),
                () -> assertTrue(bounds.y >= 0),
                () -> assertTrue(bounds.x + bounds.width <= parent.getWidth()),
                () -> assertTrue(bounds.y + bounds.height <= parent.getHeight()));
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

    /// Verifies visible, hover, and assistive presentation for one command.
    ///
    /// @param button command under test
    /// @param text expected visible and accessible name
    /// @param description expected tooltip and accessible description
    private static void assertAccessibleAction(
            AbstractButton button,
            String text,
            String description) {
        assertAll(
                () -> assertEquals(text, button.getText()),
                () -> assertEquals(description, button.getToolTipText()),
                () -> assertEquals(text, button.getAccessibleContext().getAccessibleName()),
                () -> assertEquals(
                        description,
                        button.getAccessibleContext().getAccessibleDescription()));
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

    /// One failure presentation captured from the panel interaction boundary.
    ///
    /// @param title localized dialog title
    /// @param detail resolved failure detail
    /// @param shownOnEdt whether presentation occurred on the event-dispatch thread
    @NotNullByDefault
    private record FailurePresentation(String title, String detail, boolean shownOnEdt) {
    }

    /// Deterministic modal and desktop interaction fake for action-focused panel tests.
    @NotNullByDefault
    private static final class FakeSchematicBrowserInteractions implements SchematicBrowserInteractions {
        /// Import selection returned by the next and subsequent chooser calls.
        private @Unmodifiable List<Path> importSelection = List.of();

        /// Directory name returned by the next and subsequent prompt calls, or null to cancel.
        private @Nullable String directoryName;

        /// Whether the next and subsequent deletion confirmations are accepted.
        private boolean deletionConfirmed;

        /// Reveal completion returned by the next and subsequent reveal calls.
        private CompletionStage<@Nullable Void> revealCompletion =
                CompletableFuture.completedFuture(null);

        /// Hook run while the import chooser is notionally open.
        private Runnable importDialogHook = () -> { };

        /// Hook run while the directory prompt is notionally open.
        private Runnable directoryDialogHook = () -> { };

        /// Hook run while the deletion confirmation is notionally open.
        private Runnable deleteDialogHook = () -> { };

        /// Last directory supplied to the import chooser, or null before use.
        private @Nullable Path importDirectory;

        /// Number of import chooser invocations.
        private int importChooserCalls;

        /// Number of directory prompt invocations.
        private int directoryPromptCalls;

        /// Targets supplied to deletion confirmation.
        private final List<SchematicBrowserItem> confirmedTargets = new ArrayList<>();

        /// Targets supplied to platform reveal.
        private final List<SchematicBrowserItem> revealedTargets = new ArrayList<>();

        /// Failure presentations captured from the panel.
        private final List<FailurePresentation> failures = new ArrayList<>();

        /// Returns the configured import selection after running the modal hook.
        @Override
        public @Unmodifiable List<Path> chooseImportFiles(
                Component owner,
                Path currentDirectory) {
            assertTrue(SwingUtilities.isEventDispatchThread());
            Objects.requireNonNull(owner, "owner");
            importChooserCalls++;
            importDirectory = currentDirectory;
            importDialogHook.run();
            return importSelection;
        }

        /// Returns the configured directory name after running the modal hook.
        @Override
        public @Nullable String promptDirectoryName(Component owner) {
            assertTrue(SwingUtilities.isEventDispatchThread());
            Objects.requireNonNull(owner, "owner");
            directoryPromptCalls++;
            directoryDialogHook.run();
            return directoryName;
        }

        /// Returns the configured confirmation after running the modal hook.
        @Override
        public boolean confirmDelete(Component owner, SchematicBrowserItem target) {
            assertTrue(SwingUtilities.isEventDispatchThread());
            Objects.requireNonNull(owner, "owner");
            confirmedTargets.add(target);
            deleteDialogHook.run();
            return deletionConfirmed;
        }

        /// Captures the exact reveal target and returns the configured completion.
        @Override
        public CompletionStage<@Nullable Void> reveal(SchematicBrowserItem target) {
            revealedTargets.add(target);
            return revealCompletion;
        }

        /// Captures one failure presentation and its dispatch thread.
        @Override
        public void showFailure(Component owner, String title, String detail) {
            Objects.requireNonNull(owner, "owner");
            failures.add(new FailurePresentation(
                    title,
                    detail,
                    SwingUtilities.isEventDispatchThread()));
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

        /// Immutable source lists supplied to import commands.
        private final List<List<Path>> imports = new ArrayList<>();

        /// Exact names supplied to create-directory commands.
        private final List<String> createdDirectories = new ArrayList<>();

        /// Exact paths supplied to deletion commands.
        private final List<Path> deletedTargets = new ArrayList<>();

        /// One-shot write completion override, or null for immediate current-state success.
        private @Nullable CompletionStage<SchematicBrowserSnapshot> nextWriteCompletion;

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

        /// Captures an immutable import source list and returns the configured completion.
        @Override
        public synchronized CompletionStage<SchematicBrowserSnapshot> importFiles(List<Path> sourceFiles) {
            imports.add(List.copyOf(sourceFiles));
            return takeWriteCompletion();
        }

        /// Captures an exact directory name and returns the configured completion.
        @Override
        public synchronized CompletionStage<SchematicBrowserSnapshot> createDirectory(String directoryName) {
            createdDirectories.add(directoryName);
            return takeWriteCompletion();
        }

        /// Captures an exact deletion path and returns the configured completion.
        @Override
        public synchronized CompletionStage<SchematicBrowserSnapshot> delete(Path target) {
            deletedTargets.add(target);
            return takeWriteCompletion();
        }

        /// Consumes the one-shot write completion or returns immediate current-state success.
        ///
        /// @return configured write stage
        private CompletionStage<SchematicBrowserSnapshot> takeWriteCompletion() {
            @Nullable CompletionStage<SchematicBrowserSnapshot> configured = nextWriteCompletion;
            nextWriteCompletion = null;
            return configured == null
                    ? CompletableFuture.completedFuture(current.get())
                    : configured;
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

        /// Returns captured immutable import source lists.
        ///
        /// @return immutable command history
        private synchronized @Unmodifiable List<List<Path>> importedFiles() {
            return imports.stream().map(List::copyOf).toList();
        }

        /// Returns captured directory names.
        ///
        /// @return immutable command history
        private synchronized @Unmodifiable List<String> createdDirectoryNames() {
            return List.copyOf(createdDirectories);
        }

        /// Returns captured deletion targets.
        ///
        /// @return immutable command history
        private synchronized @Unmodifiable List<Path> deletedPaths() {
            return List.copyOf(deletedTargets);
        }

        /// Configures one write command completion.
        ///
        /// @param completion one-shot completion
        private synchronized void setNextWriteCompletion(
                CompletionStage<SchematicBrowserSnapshot> completion) {
            nextWriteCompletion = completion;
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
