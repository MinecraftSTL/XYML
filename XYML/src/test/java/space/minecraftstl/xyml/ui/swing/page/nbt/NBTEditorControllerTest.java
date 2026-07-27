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

import org.glavo.nbt.io.NBTCodec;
import org.glavo.nbt.tag.CompoundTag;
import org.glavo.nbt.tag.TagType;
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
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        NBTLazyTreeModel model = new NBTLazyTreeModel(document);
        NBTEditorTreeNode valueNode = model.getRoot().childAt(0);
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

        assertTrue(ui.call(() -> controller.applyValueEdit(valueNode, "3")).applied());
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
        CompoundTag reloaded = (CompoundTag) requiredDocument(controller).rootElement();
        assertEquals(99, reloaded.getInt("value"));
        assertEquals("external", reloaded.getString("name"));
        ui.run(controller::close);
    }

    /// Uses only the seven concrete HelloNBT scalar setters and rejects a container edit.
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
        NBTLazyTreeModel model = new NBTLazyTreeModel(requiredDocument(controller));
        String[] values = {"11", "12", "13", "14", "15.5", "16.5", " seventeen "};
        for (int index = 0; index < values.length; index++) {
            int childIndex = index;
            assertTrue(ui.call(() -> controller.applyValueEdit(
                    model.getRoot().childAt(childIndex),
                    values[childIndex])).applied());
        }
        assertFalse(ui.call(() -> controller.applyValueEdit(model.getRoot().childAt(7), "9")).applied());

        CompoundTag root = (CompoundTag) requiredDocument(controller).rootElement();
        assertEquals((byte) 11, root.getByte("byte"));
        assertEquals((short) 12, root.getShort("short"));
        assertEquals(13, root.getInt("int"));
        assertEquals(14L, root.getLong("long"));
        assertEquals(15.5F, root.getFloat("float"));
        assertEquals(16.5D, root.getDouble("double"));
        assertEquals(" seventeen ", root.getString("string"));
        ui.run(controller::close);
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
        CompoundTag loaded = (CompoundTag) requiredDocument(controller).rootElement();
        assertEquals(2, loaded.getInt("value"));

        ui.run(() -> controller.open(first));
        ui.run(controller::close);
        assertEquals(NBTEditorStatus.CLOSED, controller.snapshot().status());
        ioExecutor.runAll();
        ui.runAll();
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
