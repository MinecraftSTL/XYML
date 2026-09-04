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
package space.minecraftstl.xyml.ui.swing.page.nbt;

import space.minecraftstl.xyml.library.nbt.io.NBTCodec;
import space.minecraftstl.xyml.library.nbt.io.NBTPartialSaveException;
import space.minecraftstl.xyml.library.nbt.edit.NBTAddress;
import space.minecraftstl.xyml.library.nbt.edit.NBTEditException;
import space.minecraftstl.xyml.library.nbt.tag.ByteArrayTag;
import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;
import space.minecraftstl.xyml.library.nbt.tag.IntTag;
import space.minecraftstl.xyml.library.nbt.tag.ListTag;
import space.minecraftstl.xyml.library.nbt.tag.StringTag;
import space.minecraftstl.xyml.library.nbt.tag.TagType;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.nbt.NBTDocument;
import space.minecraftstl.xyml.nbt.NBTDocumentService;
import space.minecraftstl.xyml.ui.UiDispatcher;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies UI dispatch, typed mutation, stale conflicts, reload, and late-result suppression.
@NotNullByDefault
final class NBTEditorControllerTest {
    /// Temporary filesystem root used for real atomic backend transactions.
    @TempDir
    private Path temporaryDirectory;

    /// Opens, edits, saves, detects an external replacement, and reloads without EDT filesystem work.
    @Test
    void editsSavesAndRecoversFromAStaleSource() throws Exception {
        Path source = temporaryDirectory.resolve("level.dat");
        writeTag(source, new CompoundTag().addInt("value", 1).addString("name", "old"));
        ManualExecutor ioExecutor = new ManualExecutor();
        ManualUiDispatcher ui = new ManualUiDispatcher();
        NBTEditorController controller = new NBTEditorController(
                new NBTDocumentService(ioExecutor),
                ui);

        ui.run(() -> controller.open(source));
        assertEquals(NBTEditorStatus.OPENING, controller.snapshot().status());
        assertEquals(1, ioExecutor.pendingCount());
        ioExecutor.runNext();
        assertEquals(NBTEditorStatus.OPENING, controller.snapshot().status());
        assertEquals(1, ui.pendingCount());
        ui.runNext();
        assertEquals(NBTEditorStatus.READY, controller.snapshot().status());

        NBTDocument document = requiredDocument(controller);
        NBTEditorTreeNode valueNode = child(controller, 0);
        NBTValueEditResult invalid = ui.call(() -> controller.applyValueEdit(valueNode, "not-an-int"));
        assertFalse(invalid.applied());
        assertFalse(controller.snapshot().dirty());

        NBTValueEditResult edited = ui.call(() -> controller.applyValueEdit(valueNode, "2"));
        assertTrue(edited.applied());
        assertTrue(controller.snapshot().dirty());
        ui.run(controller::save);
        assertEquals(NBTEditorStatus.SAVING, controller.snapshot().status());
        ioExecutor.runNext();
        ui.runNext();
        assertEquals(NBTEditorStatus.READY, controller.snapshot().status());
        assertFalse(controller.snapshot().dirty());
        assertEquals(2, NBTCodec.of().readTag(source, TagType.COMPOUND).getInt("value"));

        NBTEditorTreeNode savedValueNode = child(controller, 0);
        assertTrue(ui.call(() -> controller.applyValueEdit(savedValueNode, "3")).applied());
        writeTag(source, new CompoundTag().addInt("value", 99).addString("name", "external"));
        ui.run(controller::save);
        ioExecutor.runNext();
        ui.runNext();
        assertEquals(NBTEditorStatus.CONFLICT, controller.snapshot().status());
        assertTrue(controller.snapshot().dirty());
        assertEquals(99, NBTCodec.of().readTag(source, TagType.COMPOUND).getInt("value"));
        ui.run(controller::save);
        assertEquals(0, ioExecutor.pendingCount());
        assertEquals(NBTEditorStatus.CONFLICT, controller.snapshot().status());

        ui.run(controller::reload);
        ioExecutor.runNext();
        ui.runNext();
        assertEquals(NBTEditorStatus.READY, controller.snapshot().status());
        assertFalse(controller.snapshot().dirty());
        CompoundTag reloaded = (CompoundTag) requiredDocument(controller).rootSnapshot();
        assertEquals(99, reloaded.getInt("value"));
        assertEquals("external", reloaded.getString("name"));
        ui.run(controller::close);
        ioExecutor.runAll();
    }

    /// Keeps strict SNBT parsing and whole-tree mutation off the UI dispatcher.
    @Test
    void runsTransactionalEditsOnTheBackgroundExecutor() throws Exception {
        Path source = temporaryDirectory.resolve("background-edit.dat");
        writeTag(source, new CompoundTag().addInt("value", 1));
        ManualExecutor ioExecutor = new ManualExecutor();
        ManualUiDispatcher ui = new ManualUiDispatcher();
        NBTEditorController controller = new NBTEditorController(
                new NBTDocumentService(ioExecutor),
                ui);
        ui.run(() -> controller.open(source));
        ioExecutor.runNext();
        ui.runNext();
        NBTEditorTreeNode root = node(controller, NBTAddress.root());
        AtomicReference<CompletableFuture<NBTEditResult>> result = new AtomicReference<>();

        ui.run(() -> result.set(controller.replaceSnbtAsync(root, "{value:2}")));

        assertEquals(NBTEditorStatus.EDITING, controller.snapshot().status());
        assertEquals(1, ioExecutor.pendingCount());
        assertFalse(Objects.requireNonNull(result.get()).isDone());
        assertEquals(1, rootSnapshot(controller).getInt("value"));

        ioExecutor.runNext();
        assertEquals(NBTEditorStatus.EDITING, controller.snapshot().status());
        assertEquals(1, ui.pendingCount());
        ui.runNext();

        assertEquals(NBTEditorStatus.READY, controller.snapshot().status());
        assertTrue(Objects.requireNonNull(result.get()).join().applied());
        assertEquals(2, rootSnapshot(controller).getInt("value"));
        assertTrue(controller.snapshot().dirty());
        ui.run(controller::close);
        ioExecutor.runAll();
    }

    /// Locks the document and propagates an executor Error raised while an edit is submitted.
    @Test
    void failsClosedAfterAFatalEditSubmission() throws Exception {
        Path source = temporaryDirectory.resolve("fatal-edit.dat");
        writeTag(source, new CompoundTag().addInt("value", 1));
        AtomicBoolean reject = new AtomicBoolean();
        AssertionError fatal = new AssertionError("synthetic fatal edit failure");
        Executor executor = operation -> {
            if (reject.get()) {
                throw fatal;
            }
            operation.run();
        };
        ManualUiDispatcher ui = new ManualUiDispatcher();
        NBTEditorController controller = new NBTEditorController(new NBTDocumentService(executor), ui);
        ui.run(() -> controller.open(source));
        ui.runNext();
        NBTEditorTreeNode value = child(controller, 0);

        reject.set(true);
        assertSame(fatal, assertThrows(AssertionError.class, () ->
                ui.run(() -> controller.applyValueEditAsync(value, "2"))));

        assertEquals(NBTEditorStatus.EDIT_UNCERTAIN, controller.snapshot().status());
        assertTrue(controller.snapshot().dirty());
        assertFalse(ui.call(controller::canUndo));
        NBTEditResult rejected = ui.call(() -> controller.applyValueEditAsync(value, "2").join());
        assertFalse(rejected.applied());
        ui.run(controller::save);
        assertEquals(NBTEditorStatus.EDIT_UNCERTAIN, controller.snapshot().status());

        reject.set(false);
        ui.run(controller::reload);
        ui.runNext();
        assertEquals(NBTEditorStatus.READY, controller.snapshot().status());
        assertFalse(controller.snapshot().dirty());
        ui.run(controller::close);
    }

    /// Cancels queued and completed-but-undelivered edits without reviving a closed controller.
    @Test
    void ignoresEditResultsAfterClosure() throws Exception {
        Path source = temporaryDirectory.resolve("close-edit.dat");
        writeTag(source, new CompoundTag().addInt("value", 1));
        ManualExecutor ioExecutor = new ManualExecutor();
        ManualUiDispatcher ui = new ManualUiDispatcher();
        NBTEditorController controller = new NBTEditorController(
                new NBTDocumentService(ioExecutor),
                ui);
        ui.run(() -> controller.open(source));
        ioExecutor.runNext();
        ui.runNext();

        AtomicReference<CompletableFuture<NBTEditResult>> queued = new AtomicReference<>();
        ui.run(() -> queued.set(controller.applyValueEditAsync(child(controller, 0), "2")));
        ui.run(controller::close);
        ioExecutor.runAll();
        ui.runAll();
        ioExecutor.runAll();
        assertEquals(NBTEditorStatus.CLOSED, controller.snapshot().status());
        assertTrue(Objects.requireNonNull(queued.get()).isCancelled());

        NBTEditorController second = new NBTEditorController(
                new NBTDocumentService(ioExecutor),
                ui);
        ui.run(() -> second.open(source));
        ioExecutor.runNext();
        ui.runNext();
        AtomicReference<CompletableFuture<NBTEditResult>> completed = new AtomicReference<>();
        ui.run(() -> completed.set(second.applyValueEditAsync(child(second, 0), "3")));
        ioExecutor.runNext();
        assertEquals(1, ui.pendingCount());
        ui.run(second::close);
        ui.runAll();
        ioExecutor.runAll();
        assertEquals(NBTEditorStatus.CLOSED, second.snapshot().status());
        assertTrue(Objects.requireNonNull(completed.get()).isCancelled());
    }

    /// Preserves revision-bound rejection when an obsolete row is submitted asynchronously.
    @Test
    void rejectsStaleNodesOnTheBackgroundExecutor() throws Exception {
        Path source = temporaryDirectory.resolve("stale-async.dat");
        writeTag(source, new CompoundTag().addInt("value", 1));
        ManualExecutor ioExecutor = new ManualExecutor();
        ManualUiDispatcher ui = new ManualUiDispatcher();
        NBTEditorController controller = new NBTEditorController(
                new NBTDocumentService(ioExecutor),
                ui);
        ui.run(() -> controller.open(source));
        ioExecutor.runNext();
        ui.runNext();
        NBTEditorTreeNode stale = child(controller, 0);
        AtomicReference<CompletableFuture<NBTEditResult>> first = new AtomicReference<>();
        ui.run(() -> first.set(controller.applyValueEditAsync(stale, "2")));
        ioExecutor.runNext();
        ui.runNext();
        assertTrue(Objects.requireNonNull(first.get()).join().applied());

        AtomicReference<CompletableFuture<NBTEditResult>> second = new AtomicReference<>();
        ui.run(() -> second.set(controller.applyValueEditAsync(stale, "3")));
        ioExecutor.runNext();
        ui.runNext();
        NBTEditResult rejected = Objects.requireNonNull(second.get()).join();
        assertFalse(rejected.applied());
        assertSame(NBTEditException.Reason.STALE_NODE, rejected.reason());
        assertEquals(2, rootSnapshot(controller).getInt("value"));
        ui.run(controller::close);
        ioExecutor.runAll();
    }

    /// Uses only the seven concrete XoyzNBT scalar setters and rejects a container edit.
    @Test
    void preservesEverySupportedScalarType() throws Exception {
        Path source = temporaryDirectory.resolve("types.dat");
        writeTag(source, new CompoundTag()
                .addByte("byte", (byte) 1)
                .addShort("short", (short) 2)
                .addInt("int", 3)
                .addLong("long", 4L)
                .addFloat("float", 5.0F)
                .addDouble("double", 6.0D)
                .addString("string", "seven")
                .addIntArray("array", new int[]{8}));
        ManualUiDispatcher ui = new ManualUiDispatcher();
        NBTEditorController controller = new NBTEditorController(
                new NBTDocumentService(Runnable::run),
                ui);
        ui.run(() -> controller.open(source));
        ui.runNext();
        String[] values = {"11", "12", "13", "14", "15.5", "16.5", " seventeen "};
        for (int index = 0; index < values.length; index++) {
            int childIndex = index;
            assertTrue(ui.call(() -> controller.applyValueEdit(
                    child(controller, childIndex),
                    values[childIndex])).applied());
        }
        assertFalse(ui.call(() -> controller.applyValueEdit(child(controller, 7), "9")).applied());

        CompoundTag root = (CompoundTag) requiredDocument(controller).rootSnapshot();
        assertEquals((byte) 11, root.getByte("byte"));
        assertEquals((short) 12, root.getShort("short"));
        assertEquals(13, root.getInt("int"));
        assertEquals(14L, root.getLong("long"));
        assertEquals(15.5F, root.getFloat("float"));
        assertEquals(16.5D, root.getDouble("double"));
        assertEquals(" seventeen ", root.getString("string"));
        ui.run(controller::close);
    }

    /// Preserves the binary root name when complete SNBT replaces a standalone root value.
    @Test
    void preservesNamedStandaloneRootDuringSnbtReplacement() throws Exception {
        Path source = temporaryDirectory.resolve("named-root.nbt");
        CompoundTag original = new CompoundTag().addInt("value", 1);
        original.setName("NamedRoot");
        writeTag(source, original);
        ManualUiDispatcher ui = new ManualUiDispatcher();
        NBTEditorController controller = new NBTEditorController(
                new NBTDocumentService(Runnable::run),
                ui);
        ui.run(() -> controller.open(source));
        ui.runNext();

        NBTEditResult replacement = ui.call(() -> controller.replaceSnbt(
                node(controller, NBTAddress.root()),
                "{value:2}"));

        assertTrue(replacement.applied());
        CompoundTag edited = rootSnapshot(controller);
        assertEquals("NamedRoot", edited.getName());
        assertEquals(2, edited.getInt("value"));
        ui.run(controller::save);
        ui.runNext();
        CompoundTag persisted = NBTCodec.of().readTag(source, TagType.COMPOUND);
        assertEquals("NamedRoot", persisted.getName());
        assertEquals(2, persisted.getInt("value"));
        ui.run(controller::close);
    }

    /// Routes every structural command through revision-bound transactional editor operations.
    @Test
    void appliesStructuralClipboardArrayAndHistoryCommands() throws Exception {
        Path source = temporaryDirectory.resolve("structure.dat");
        ListTag<IntTag> numbers = new ListTag<>(TagType.INT);
        numbers.addTag(new IntTag(1)).addTag(new IntTag(2));
        ListTag<CompoundTag> records = new ListTag<>(TagType.COMPOUND);
        records.addTag(new CompoundTag().addString("name", "one"));
        records.addTag(new CompoundTag().addString("name", "two"));
        writeTag(source, new CompoundTag()
                .addInt("first", 1)
                .addTag("numbers", numbers)
                .addTag("records", records)
                .addTag("empty", new ListTag<>())
                .addByteArray("bytes", new byte[]{1, 2}));
        ManualUiDispatcher ui = new ManualUiDispatcher();
        NBTEditorController controller = new NBTEditorController(
                new NBTDocumentService(Runnable::run),
                ui);
        ui.run(() -> controller.open(source));
        ui.runNext();

        NBTEditorTreeNode staleRoot = node(controller, NBTAddress.root());
        NBTEditResult inserted = ui.call(() -> controller.insert(
                staleRoot,
                1,
                new StringTag("middle").setName("inserted")));
        assertTrue(inserted.applied());
        assertEquals(NBTAddress.root().appendName("inserted"), inserted.selection());

        NBTEditResult stale = ui.call(() -> controller.insert(
                staleRoot,
                0,
                new IntTag(99).setName("stale")));
        assertFalse(stale.applied());
        assertSame(NBTEditException.Reason.STALE_NODE, stale.reason());

        NBTAddress insertedAddress = NBTAddress.root().appendName("inserted");
        NBTEditResult renamed = ui.call(() -> controller.rename(node(controller, insertedAddress), "renamed"));
        assertTrue(renamed.applied());
        NBTAddress renamedAddress = NBTAddress.root().appendName("renamed");
        assertEquals(renamedAddress, renamed.selection());

        NBTEditResult duplicate = ui.call(() -> controller.rename(node(controller, renamedAddress), "first"));
        assertFalse(duplicate.applied());
        assertSame(NBTEditException.Reason.DUPLICATE_NAME, duplicate.reason());

        NBTEditResult replaced = ui.call(() -> controller.replaceSnbt(
                node(controller, renamedAddress),
                "\"replacement\""));
        assertTrue(replaced.applied());
        assertEquals(renamedAddress, replaced.selection());

        NBTAddress numbersAddress = NBTAddress.root().appendName("numbers");
        NBTAddress firstNumberAddress = numbersAddress.appendIndex(0);
        assertTrue(ui.call(() -> controller.copy(node(controller, firstNumberAddress))).applied());
        assertTrue(ui.call(controller::hasClipboard));
        NBTEditResult pasted = ui.call(() -> controller.paste(node(controller, numbersAddress), 2));
        assertTrue(pasted.applied());
        assertEquals(numbersAddress.appendIndex(2), pasted.selection());

        NBTEditResult moved = ui.call(() -> controller.move(
                node(controller, numbersAddress.appendIndex(2)),
                -1));
        assertTrue(moved.applied());
        assertEquals(numbersAddress.appendIndex(1), moved.selection());
        NBTEditResult deleted = ui.call(() -> controller.delete(node(controller, numbersAddress.appendIndex(0))));
        assertTrue(deleted.applied());
        assertEquals(numbersAddress, deleted.selection());

        NBTAddress recordsAddress = NBTAddress.root().appendName("records");
        assertTrue(ui.call(() -> controller.move(
                node(controller, recordsAddress.appendIndex(1)),
                -1)).applied());
        NBTEditResult nestedUndo = ui.call(() -> controller.undo(
                recordsAddress.appendIndex(0).appendName("name")));
        assertTrue(nestedUndo.applied());
        assertEquals(recordsAddress, nestedUndo.selection());
        NBTEditResult nestedRedo = ui.call(() -> controller.redo(
                recordsAddress.appendIndex(1).appendName("name")));
        assertTrue(nestedRedo.applied());
        assertEquals(recordsAddress, nestedRedo.selection());

        NBTAddress emptyAddress = NBTAddress.root().appendName("empty");
        assertTrue(ui.call(() -> controller.setListElementType(
                node(controller, emptyAddress),
                TagType.INT)).applied());
        assertSame(TagType.INT, ui.call(() -> controller.listElementType(node(controller, emptyAddress))));
        assertTrue(ui.call(() -> controller.insert(
                node(controller, emptyAddress),
                0,
                new IntTag(7))).applied());

        NBTAddress firstByteAddress = NBTAddress.root().appendName("bytes").appendIndex(0);
        assertTrue(ui.call(() -> controller.applyValueEdit(
                node(controller, firstByteAddress),
                "-128")).applied());
        assertFalse(ui.call(() -> controller.applyValueEdit(
                node(controller, firstByteAddress),
                "128")).applied());

        CompoundTag editedRoot = rootSnapshot(controller);
        assertEquals("replacement", editedRoot.getString("renamed"));
        ListTag<?> editedNumbers = (ListTag<?>) editedRoot.get("numbers");
        assertEquals(2, editedNumbers.size());
        assertEquals(1, ((IntTag) editedNumbers.getTag(0)).getValue());
        assertEquals(2, ((IntTag) editedNumbers.getTag(1)).getValue());
        ListTag<?> editedEmpty = (ListTag<?>) editedRoot.get("empty");
        assertSame(TagType.INT, editedEmpty.getElementType());
        assertEquals(7, ((IntTag) editedEmpty.getTag(0)).getValue());
        assertEquals((byte) -128, ((ByteArrayTag) editedRoot.get("bytes")).getValue(0));

        NBTEditResult undone = ui.call(() -> controller.undo(firstByteAddress));
        assertTrue(undone.applied());
        assertEquals(firstByteAddress.parent(), undone.selection());
        assertEquals((byte) 1, ((ByteArrayTag) rootSnapshot(controller).get("bytes")).getValue(0));
        assertTrue(ui.call(controller::canRedo));
        NBTEditResult redone = ui.call(() -> controller.redo(firstByteAddress));
        assertTrue(redone.applied());
        assertEquals(firstByteAddress.parent(), redone.selection());
        assertEquals((byte) -128, ((ByteArrayTag) rootSnapshot(controller).get("bytes")).getValue(0));
        String subtree = ui.call(() -> controller.subtreeSnbt(node(controller, NBTAddress.root())));
        assertNotNull(subtree);
        assertTrue(subtree.contains("renamed"));
        ui.run(controller::close);
    }

    /// Treats the library's strict-open fingerprint race as an external source conflict.
    @Test
    void classifiesSourceChangesDuringReadAsConflicts() {
        assertSame(
                NBTEditorStatus.CONFLICT,
                NBTEditorController.classifySaveFailure(
                        new IOException("NBT source changed while it was being read")));
        assertSame(
                NBTEditorStatus.ERROR,
                NBTEditorController.classifySaveFailure(new IOException("Disk is unavailable")));
        assertSame(
                NBTEditorStatus.CONFLICT,
                NBTEditorController.classifySaveFailure(new NBTPartialSaveException(
                        java.util.List.of(0),
                        1,
                        new IOException("Region changed after opening"))));
    }

    /// Retains partial-save recovery after an ordinary retry failure and permits stricter upgrades.
    @Test
    void retainsPartialRecoveryAcrossFailedRetries() {
        assertSame(
                NBTEditorStatus.PARTIAL_SAVE,
                NBTEditorController.classifySaveFailure(
                        NBTEditorStatus.PARTIAL_SAVE,
                        new IOException("Disk is unavailable")));
        assertSame(
                NBTEditorStatus.CONFLICT,
                NBTEditorController.classifySaveFailure(
                        NBTEditorStatus.PARTIAL_SAVE,
                        new IOException("Region path was replaced")));
        assertSame(
                NBTEditorStatus.COMMIT_UNCERTAIN,
                NBTEditorController.classifySaveFailure(
                        NBTEditorStatus.PARTIAL_SAVE,
                        new IOException("Region commit state is uncertain")));
    }

    /// Keeps a partial Region publication retryable after undo reaches the original editor savepoint.
    @Test
    void requiresRecoverySaveForCleanPartialState() {
        NBTEditorSnapshot partial = new NBTEditorSnapshot(
                NBTEditorStatus.PARTIAL_SAVE,
                temporaryDirectory.resolve("r.0.0.mca"),
                null,
                false,
                "slot 0 committed",
                1L);

        assertTrue(partial.requiresSave());
        assertFalse(NBTEditorSnapshot.empty().requiresSave());
    }

    /// Does not downgrade a retained recovery document when its reload or replacement fails.
    @Test
    void retainsRecoveryStateAfterOpenFailure() {
        assertSame(
                NBTEditorStatus.COMMIT_UNCERTAIN,
                NBTEditorController.retainedOpenFailureStatus(NBTEditorStatus.COMMIT_UNCERTAIN, true));
        assertSame(
                NBTEditorStatus.CONFLICT,
                NBTEditorController.retainedOpenFailureStatus(NBTEditorStatus.CONFLICT, true));
        assertSame(
                NBTEditorStatus.PARTIAL_SAVE,
                NBTEditorController.retainedOpenFailureStatus(NBTEditorStatus.PARTIAL_SAVE, true));
        assertSame(
                NBTEditorStatus.ERROR,
                NBTEditorController.retainedOpenFailureStatus(NBTEditorStatus.READY, true));
        assertSame(
                NBTEditorStatus.ERROR,
                NBTEditorController.retainedOpenFailureStatus(NBTEditorStatus.COMMIT_UNCERTAIN, false));
    }

    /// Isolates ordinary listener failures after a committed edit and still notifies later listeners.
    @Test
    void preservesCommittedResultWhenAStateListenerFails() throws Exception {
        Path source = temporaryDirectory.resolve("listener.dat");
        writeTag(source, new CompoundTag().addInt("value", 1));
        ManualUiDispatcher ui = new ManualUiDispatcher();
        NBTEditorController controller = new NBTEditorController(
                new NBTDocumentService(Runnable::run),
                ui);
        ui.run(() -> controller.open(source));
        ui.runNext();
        AtomicInteger delivered = new AtomicInteger();
        controller.subscribe(change -> {
            throw new IllegalStateException("synthetic listener failure");
        });
        controller.subscribe(change -> delivered.incrementAndGet());

        NBTValueEditResult result = ui.call(() -> controller.applyValueEdit(child(controller, 0), "2"));

        assertTrue(result.applied());
        assertEquals(1, delivered.get());
        assertEquals(2, rootSnapshot(controller).getInt("value"));
        assertTrue(controller.snapshot().dirty());
        ui.run(controller::close);
    }

    /// Retains and asynchronously closes the old file session when replacement is cancelled by closure.
    @Test
    void closesRetainedDocumentWhenClosedDuringReplacement() throws Exception {
        Path first = temporaryDirectory.resolve("retained.dat");
        Path second = temporaryDirectory.resolve("replacement.dat");
        writeTag(first, new CompoundTag().addInt("value", 1));
        writeTag(second, new CompoundTag().addInt("value", 2));
        ManualExecutor ioExecutor = new ManualExecutor();
        ManualUiDispatcher ui = new ManualUiDispatcher();
        NBTEditorController controller = new NBTEditorController(
                new NBTDocumentService(ioExecutor),
                ui);
        ui.run(() -> controller.open(first));
        ioExecutor.runNext();
        ui.runNext();
        NBTDocument retained = requiredDocument(controller);

        ui.run(() -> controller.open(second));
        assertEquals(NBTEditorStatus.OPENING, controller.snapshot().status());
        assertSame(retained, controller.snapshot().document());
        ui.run(controller::close);
        assertFalse(retained.isClosed());

        ioExecutor.runAll();
        ui.runAll();
        ioExecutor.runAll();
        assertTrue(retained.isClosed());
        assertEquals(NBTEditorStatus.CLOSED, controller.snapshot().status());
    }

    /// Replacing or closing an operation prevents every cancelled completion from changing state.
    @Test
    void ignoresReplacedAndPostCloseResults() throws Exception {
        Path first = temporaryDirectory.resolve("first.dat");
        Path second = temporaryDirectory.resolve("second.dat");
        writeTag(first, new CompoundTag().addInt("value", 1));
        writeTag(second, new CompoundTag().addInt("value", 2));
        ManualExecutor ioExecutor = new ManualExecutor();
        ManualUiDispatcher ui = new ManualUiDispatcher();
        NBTEditorController controller = new NBTEditorController(
                new NBTDocumentService(ioExecutor),
                ui);

        ui.run(() -> controller.open(first));
        ui.run(() -> controller.open(second));
        ioExecutor.runAll();
        ui.runAll();
        assertEquals(second.toAbsolutePath().normalize(), controller.snapshot().file());
        CompoundTag loaded = (CompoundTag) requiredDocument(controller).rootSnapshot();
        assertEquals(2, loaded.getInt("value"));

        ui.run(() -> controller.open(first));
        ui.run(controller::close);
        assertEquals(NBTEditorStatus.CLOSED, controller.snapshot().status());
        ioExecutor.runAll();
        ui.runAll();
        ioExecutor.runAll();
        assertEquals(NBTEditorStatus.CLOSED, controller.snapshot().status());
        assertEquals(first.toAbsolutePath().normalize(), controller.snapshot().file());
    }

    /// Returns the required loaded document from a ready controller.
    ///
    /// @param controller source controller
    /// @return non-null loaded document
    private static NBTDocument requiredDocument(NBTEditorController controller) {
        return java.util.Objects.requireNonNull(controller.snapshot().document(), "document");
    }

    /// Returns a detached Compound root at the current editor revision.
    ///
    /// @param controller ready controller
    /// @return detached current root
    private static CompoundTag rootSnapshot(NBTEditorController controller) {
        return (CompoundTag) requiredDocument(controller).rootSnapshot();
    }

    /// Resolves one structural address at the current editor revision.
    ///
    /// @param controller ready controller
    /// @param address exact current address
    /// @return current immutable Swing row
    private static NBTEditorTreeNode node(NBTEditorController controller, NBTAddress address) {
        NBTLazyTreeModel model = new NBTLazyTreeModel(requiredDocument(controller));
        return (NBTEditorTreeNode) model.pathForAddress(address).getLastPathComponent();
    }

    /// Resolves one direct root child at the current editor revision.
    ///
    /// @param controller ready controller
    /// @param index direct child index
    /// @return current immutable Swing row
    private static NBTEditorTreeNode child(NBTEditorController controller, int index) {
        return new NBTLazyTreeModel(requiredDocument(controller)).getRoot().childAt(index);
    }

    /// Writes one deterministic GZIP standalone NBT fixture.
    ///
    /// @param target fixture target
    /// @param root compound root
    /// @throws IOException when fixture serialization fails
    private static void writeTag(Path target, CompoundTag root) throws IOException {
        try (OutputStream rawOutput = new BufferedOutputStream(Files.newOutputStream(target));
             GZIPOutputStream gzipOutput = new GZIPOutputStream(rawOutput)) {
            NBTCodec.of().writeTag(gzipOutput, root);
        }
    }

    /// Deterministic caller-owned blocking executor.
    @NotNullByDefault
    private static final class ManualExecutor implements Executor {
        /// FIFO of submitted operations.
        private final Queue<Runnable> commands = new ArrayDeque<>();

        /// Queues one operation without running it.
        ///
        /// @param command submitted operation
        @Override
        public void execute(Runnable command) {
            commands.add(command);
        }

        /// Returns the pending command count.
        ///
        /// @return queued command count
        private int pendingCount() {
            return commands.size();
        }

        /// Runs the next submitted command.
        private void runNext() {
            commands.remove().run();
        }

        /// Drains every submitted command, including commands added while draining.
        private void runAll() {
            while (!commands.isEmpty()) {
                runNext();
            }
        }
    }

    /// Deterministic toolkit-neutral UI queue that exposes its dispatch context to the controller.
    @NotNullByDefault
    private static final class ManualUiDispatcher implements UiDispatcher {
        /// FIFO of asynchronously dispatched UI operations.
        private final Queue<Runnable> commands = new ArrayDeque<>();

        /// Whether the current test call is executing in the simulated UI context.
        private boolean dispatchThread;

        /// Returns whether the simulated UI context is active.
        ///
        /// @return simulated UI-thread state
        @Override
        public boolean isDispatchThread() {
            return dispatchThread;
        }

        /// Queues one UI callback without running it.
        ///
        /// @param operation submitted callback
        @Override
        public void dispatch(Runnable operation) {
            commands.add(operation);
        }

        /// Runs one action in the simulated UI context.
        ///
        /// @param action action to run
        private void run(Runnable action) {
            boolean previous = dispatchThread;
            dispatchThread = true;
            try {
                action.run();
            } finally {
                dispatchThread = previous;
            }
        }

        /// Runs one value operation in the simulated UI context.
        ///
        /// @param operation value operation
        /// @param <T> result type
        /// @return operation result
        private <T> T call(Supplier<T> operation) {
            java.util.concurrent.atomic.AtomicReference<T> result = new java.util.concurrent.atomic.AtomicReference<>();
            run(() -> result.set(operation.get()));
            return java.util.Objects.requireNonNull(result.get(), "result");
        }

        /// Returns the pending UI callback count.
        ///
        /// @return queued callback count
        private int pendingCount() {
            return commands.size();
        }

        /// Runs the next queued callback in the simulated UI context.
        private void runNext() {
            Runnable command = commands.remove();
            run(command);
        }

        /// Drains every queued callback.
        private void runAll() {
            while (!commands.isEmpty()) {
                runNext();
            }
        }
    }
}
