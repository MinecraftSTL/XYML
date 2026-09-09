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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.library.nbt.NBTElement;
import space.minecraftstl.xyml.library.nbt.edit.NBTAddress;
import space.minecraftstl.xyml.library.nbt.edit.NBTEditException;
import space.minecraftstl.xyml.library.nbt.edit.NBTEditor;
import space.minecraftstl.xyml.library.nbt.edit.NBTNode;
import space.minecraftstl.xyml.library.nbt.io.NBTCommitUncertainException;
import space.minecraftstl.xyml.library.nbt.io.NBTPartialSaveException;
import space.minecraftstl.xyml.library.nbt.tag.ListTag;
import space.minecraftstl.xyml.library.nbt.tag.Tag;
import space.minecraftstl.xyml.library.nbt.tag.TagType;
import space.minecraftstl.xyml.library.nbt.tag.ValueTag;
import space.minecraftstl.xyml.nbt.NBTDocument;
import space.minecraftstl.xyml.nbt.NBTDocumentService;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.ui.UiDispatcher;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Serializes NBT editor state and routes every mutation through the generic XoyzNBT editor.
///
/// File and editing operations are scheduled by [NBTDocumentService]. UI commands use immutable,
/// revision-bound nodes and publish a fresh model revision only after a complete transactional edit
/// returns to the UI dispatcher. Late asynchronous results are ignored and any document they own is
/// closed in the background.
@NotNullByDefault
public final class NBTEditorController implements AutoCloseable {
    /// Background file-session boundary.
    private final NBTDocumentService documentService;

    /// UI queue that serializes visible state transitions.
    private final UiDispatcher uiDispatcher;

    /// Synchronous subscribers notified only from the UI dispatcher.
    private final ValueChangeSupport<NBTEditorSnapshot> changes = new ValueChangeSupport<>(this);

    /// Latest immutable visible state.
    private volatile NBTEditorSnapshot snapshot = NBTEditorSnapshot.empty();

    /// Current open, edit, or save future, or `null` while idle.
    private @Nullable CompletableFuture<?> activeOperation;

    /// Latest open request deferred behind an operation that has already crossed its commit point.
    private @Nullable Path deferredOpen;

    /// Detached in-process clipboard tag, or `null` before a successful copy.
    private @Nullable Tag clipboard;

    /// Monotonic identity incremented whenever asynchronous work is replaced.
    private long operationRevision;

    /// Whether terminal closure has run on the UI dispatcher.
    private boolean closed;

    /// Creates a controller over an existing background service and UI queue.
    ///
    /// @param documentService background document service
    /// @param uiDispatcher UI state dispatcher
    public NBTEditorController(NBTDocumentService documentService, UiDispatcher uiDispatcher) {
        this.documentService = Objects.requireNonNull(documentService, "documentService");
        this.uiDispatcher = Objects.requireNonNull(uiDispatcher, "uiDispatcher");
    }

    /// Returns the latest immutable state from any thread.
    ///
    /// @return latest visible state
    public NBTEditorSnapshot snapshot() {
        return snapshot;
    }

    /// Registers one synchronous state listener.
    ///
    /// @param listener state-change listener
    /// @return independently removable registration
    public Subscription subscribe(ValueChangeListener<NBTEditorSnapshot> listener) {
        ValueChangeListener<NBTEditorSnapshot> checkedListener = Objects.requireNonNull(listener, "listener");
        return changes.subscribe(change -> {
            try {
                checkedListener.onChange(change);
            } catch (RuntimeException failure) {
                LOG.warning("An NBT editor state listener failed", failure);
            }
        });
    }

    /// Returns whether a detached copied tag is available.
    ///
    /// @return whether paste can be considered for a compatible parent
    public boolean hasClipboard() {
        requireUiThread();
        return clipboard != null;
    }

    /// Returns whether the current document can undo one edit.
    ///
    /// @return whether undo is available
    public boolean canUndo() {
        requireUiThread();
        @Nullable NBTDocument document = editableDocument(null);
        return document != null && document.editor().canUndo();
    }

    /// Returns whether the current document can redo one edit.
    ///
    /// @return whether redo is available
    public boolean canRedo() {
        requireUiThread();
        @Nullable NBTDocument document = editableDocument(null);
        return document != null && document.editor().canRedo();
    }

    /// Replaces the current selection with one asynchronously opened source.
    ///
    /// @param file candidate source path
    public void open(Path file) {
        requireUiThread();
        ensureOpen();
        Path target = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        NBTEditorSnapshot previous = snapshot;
        if (!tryBeginOperation()) {
            deferredOpen = target;
            return;
        }
        deferredOpen = null;
        long operation = operationRevision;
        publish(new NBTEditorSnapshot(
                NBTEditorStatus.OPENING,
                target,
                previous.document(),
                previous.dirty(),
                null,
                nextRevision()));
        startOpen(operation, previous, target);
    }

    /// Reopens the current source and discards the old in-memory document only after success.
    public void reload() {
        requireUiThread();
        ensureOpen();
        NBTEditorSnapshot previous = snapshot;
        @Nullable Path file = previous.file();
        if (file == null || previous.document() == null || previous.busy()) {
            return;
        }
        long operation = beginOperation();
        publish(new NBTEditorSnapshot(
                NBTEditorStatus.OPENING,
                file,
                previous.document(),
                previous.dirty(),
                null,
                nextRevision()));
        startReload(operation, previous, file);
    }

    /// Saves the current dirty or repair-required document with library-owned strict publication checks.
    public void save() {
        requireUiThread();
        ensureOpen();
        NBTEditorSnapshot previous = snapshot;
        @Nullable NBTDocument document = previous.document();
        if (document == null
                || previous.busy()
                || !previous.requiresSave()
                || previous.status() == NBTEditorStatus.EDIT_UNCERTAIN
                || previous.status() == NBTEditorStatus.COMMIT_UNCERTAIN) {
            return;
        }
        long operation = beginOperation();
        publish(new NBTEditorSnapshot(
                NBTEditorStatus.SAVING,
                previous.file(),
                document,
                true,
                null,
                nextRevision()));
        try {
            CompletableFuture<Void> future = documentService.save(document);
            activeOperation = future;
            future.whenComplete((@Nullable Void ignored, @Nullable Throwable failure) ->
                    dispatchCompletion(
                            () -> finishSave(operation, document, previous.status(), failure),
                            null));
        } catch (RuntimeException failure) {
            finishSave(operation, document, previous.status(), failure);
        }
    }

    /// Applies one exact type-preserving scalar edit.
    ///
    /// @param node selected current row
    /// @param text proposed scalar text
    /// @return validation and mutation result
    public NBTValueEditResult applyValueEdit(NBTEditorTreeNode node, String text) {
        NBTEditResult result = mutate(node, (editor, target) ->
                editor.setScalar(target, Objects.requireNonNull(text, "text")));
        return result.applied()
                ? NBTValueEditResult.success()
                : NBTValueEditResult.failure(Objects.requireNonNull(result.errorMessage(), "errorMessage"));
    }

    /// Applies one structured value in decimal mode.
    ///
    /// @param node selected current row
    /// @param text proposed decimal value text
    /// @return transactional command result
    public NBTEditResult applyStructuredValue(NBTEditorTreeNode node, String text) {
        return applyStructuredValue(node, text, NBTNumberRadix.DECIMAL);
    }

    /// Applies one structured value in the selected numeric radix.
    ///
    /// @param node selected current row
    /// @param text proposed scalar or aggregate value text
    /// @param radix selected numeric input radix
    /// @return transactional command result
    NBTEditResult applyStructuredValue(NBTEditorTreeNode node, String text, NBTNumberRadix radix) {
        String value = Objects.requireNonNull(text, "text");
        NBTNumberRadix selectedRadix = Objects.requireNonNull(radix, "radix");
        return mutate(node, (editor, target) -> setStructuredValue(editor, target, value, selectedRadix));
    }

    /// Converts one selected tag through the generic XoyzNBT conversion transaction.
    ///
    /// @param node selected current row
    /// @param targetType requested target type
    /// @return transactional command result
    public NBTEditResult convertType(NBTEditorTreeNode node, TagType<?> targetType) {
        TagType<?> selectedType = Objects.requireNonNull(targetType, "targetType");
        return mutate(node, (editor, target) -> editor.convertType(target, selectedType));
    }

    /// Renames one Compound child.
    ///
    /// @param node selected current row
    /// @param name non-empty replacement name
    /// @return transactional command result
    public NBTEditResult rename(NBTEditorTreeNode node, String name) {
        return mutate(node, (editor, target) -> editor.rename(target, Objects.requireNonNull(name, "name")));
    }

    /// Inserts one detached tag into a compatible parent.
    ///
    /// @param parent selected parent row
    /// @param index insertion index
    /// @param tag detached source tag
    /// @return transactional command result
    public NBTEditResult insert(NBTEditorTreeNode parent, int index, Tag tag) {
        return mutate(parent, (editor, target) ->
                editor.insertTag(target, index, Objects.requireNonNull(tag, "tag")));
    }

    /// Replaces one selected tag from complete strict SNBT.
    ///
    /// @param node selected current row
    /// @param snbt complete subtree SNBT
    /// @return transactional command result
    public NBTEditResult replaceSnbt(NBTEditorTreeNode node, String snbt) {
        NBTEditorTreeNode selected = Objects.requireNonNull(node, "node");
        Tag replacement;
        try {
            replacement = NBTTagInput.parseSnbt(Objects.requireNonNull(snbt, "snbt"));
        } catch (IOException failure) {
            return NBTEditResult.failure(failureMessage(failure));
        }
        replacement.setName(selected.node().getName());
        return mutate(selected, (editor, target) -> editor.replace(target, replacement));
    }

    /// Removes one non-root tag.
    ///
    /// @param node selected current row
    /// @return transactional command result selecting the former parent
    public NBTEditResult delete(NBTEditorTreeNode node) {
        return mutate(node, NBTEditor::remove);
    }

    /// Moves one ordered child within its current parent.
    ///
    /// Region chunk slots and fixed chunk-root slots cannot be reordered.
    ///
    /// @param node selected current row
    /// @param offset `-1` to move up or `1` to move down
    /// @return transactional command result
    public NBTEditResult move(NBTEditorTreeNode node, int offset) {
        if (offset != -1 && offset != 1) {
            throw new IllegalArgumentException("offset must be -1 or 1");
        }
        NBTEditorTreeNode selected = Objects.requireNonNull(node, "node");
        if (isFixedSlot(selected.address())) {
            return NBTEditResult.failure("Fixed Region and chunk-root slots cannot be reordered");
        }
        return mutate(selected, (editor, target) -> {
            NBTNode parent = editor.resolve(target.getAddress().parent());
            return editor.move(target, parent, selected.parentIndex() + offset);
        });
    }

    /// Sets the declared element type of one empty List.
    ///
    /// @param node selected empty List row
    /// @param type declared element type, or `null` for TAG_End
    /// @return transactional command result
    public NBTEditResult setListElementType(NBTEditorTreeNode node, @Nullable TagType<?> type) {
        return mutate(node, (editor, target) -> editor.setListElementType(target, type));
    }

    /// Copies one selected tag into a detached in-process clipboard.
    ///
    /// @param node selected current row
    /// @return result retaining the current selection
    public NBTEditResult copy(NBTEditorTreeNode node) {
        requireUiThread();
        @Nullable NBTDocument document = editableDocument(node);
        if (document == null) {
            return NBTEditResult.failure("No editable NBT document is ready");
        }
        try {
            NBTElement detached = document.editor().snapshot(node.node());
            if (!(detached instanceof Tag tag)) {
                return NBTEditResult.failure("Only NBT tags can be copied");
            }
            clipboard = tag;
            return NBTEditResult.success(node.address());
        } catch (NBTEditException failure) {
            return NBTEditResult.failure(failure);
        }
    }

    /// Inserts a detached copy of the clipboard tag.
    ///
    /// @param parent selected destination parent
    /// @param index insertion index
    /// @return transactional command result
    public NBTEditResult paste(NBTEditorTreeNode parent, int index) {
        @Nullable Tag copied = clipboard;
        if (copied == null) {
            return NBTEditResult.failure("The NBT clipboard is empty");
        }
        return insert(parent, index, copied);
    }

    /// Undoes one edit and restores the closest still-existing preferred address.
    ///
    /// @param preferred preferred selection before undo
    /// @return transactional command result
    public NBTEditResult undo(NBTAddress preferred) {
        return history(Objects.requireNonNull(preferred, "preferred"), true);
    }

    /// Redoes one edit and restores the closest still-existing preferred address.
    ///
    /// @param preferred preferred selection before redo
    /// @return transactional command result
    public NBTEditResult redo(NBTAddress preferred) {
        return history(Objects.requireNonNull(preferred, "preferred"), false);
    }

    /// Applies one scalar edit on the background executor.
    ///
    /// @param node selected current row
    /// @param text proposed scalar text
    /// @return future result completed on the UI dispatcher
    public CompletableFuture<NBTEditResult> applyValueEditAsync(NBTEditorTreeNode node, String text) {
        String value = Objects.requireNonNull(text, "text");
        return mutateAsync(node, (editor, target) -> editor.setScalar(target, value));
    }

    /// Applies one structured value in decimal mode on the background executor.
    ///
    /// @param node selected current row
    /// @param text proposed decimal value text
    /// @return future result completed on the UI dispatcher
    public CompletableFuture<NBTEditResult> applyStructuredValueAsync(
            NBTEditorTreeNode node,
            String text) {
        return applyStructuredValueAsync(node, text, NBTNumberRadix.DECIMAL);
    }

    /// Applies one structured value in the selected numeric radix on the background executor.
    ///
    /// @param node selected current row
    /// @param text proposed scalar or aggregate value text
    /// @param radix selected numeric input radix
    /// @return future result completed on the UI dispatcher
    CompletableFuture<NBTEditResult> applyStructuredValueAsync(
            NBTEditorTreeNode node,
            String text,
            NBTNumberRadix radix) {
        String value = Objects.requireNonNull(text, "text");
        NBTNumberRadix selectedRadix = Objects.requireNonNull(radix, "radix");
        return mutateAsync(node, (editor, target) -> setStructuredValue(editor, target, value, selectedRadix));
    }

    /// Converts one selected tag on the background executor.
    ///
    /// @param node selected current row
    /// @param targetType requested target type
    /// @return future result completed on the UI dispatcher
    public CompletableFuture<NBTEditResult> convertTypeAsync(
            NBTEditorTreeNode node,
            TagType<?> targetType) {
        TagType<?> selectedType = Objects.requireNonNull(targetType, "targetType");
        return mutateAsync(node, (editor, target) -> editor.convertType(target, selectedType));
    }

    /// Renames one Compound child on the background executor.
    ///
    /// @param node selected current row
    /// @param name non-empty replacement name
    /// @return future result completed on the UI dispatcher
    public CompletableFuture<NBTEditResult> renameAsync(NBTEditorTreeNode node, String name) {
        String replacement = Objects.requireNonNull(name, "name");
        return mutateAsync(node, (editor, target) -> editor.rename(target, replacement));
    }

    /// Parses and inserts one form value on the background executor.
    ///
    /// @param parent selected destination parent
    /// @param index insertion index
    /// @param type exact new tag type
    /// @param name proposed tag name
    /// @param value complete type-specific value or SNBT
    /// @return future result completed on the UI dispatcher
    public CompletableFuture<NBTEditResult> createAndInsertAsync(
            NBTEditorTreeNode parent,
            int index,
            TagType<?> type,
            String name,
            String value) {
        TagType<?> selectedType = Objects.requireNonNull(type, "type");
        String selectedName = Objects.requireNonNull(name, "name");
        String selectedValue = Objects.requireNonNull(value, "value");
        return mutateAsync(parent, (editor, target) -> editor.insertTag(
                target,
                index,
                NBTTagInput.create(selectedType, selectedName, selectedValue)));
    }

    /// Parses and replaces one selected subtree on the background executor.
    ///
    /// @param node selected current row
    /// @param snbt complete strict subtree SNBT
    /// @return future result completed on the UI dispatcher
    public CompletableFuture<NBTEditResult> replaceSnbtAsync(NBTEditorTreeNode node, String snbt) {
        NBTEditorTreeNode selected = Objects.requireNonNull(node, "node");
        String source = Objects.requireNonNull(snbt, "snbt");
        return mutateAsync(selected, (editor, target) -> {
            Tag replacement = NBTTagInput.parseSnbt(source);
            replacement.setName(selected.node().getName());
            return editor.replace(target, replacement);
        });
    }

    /// Removes one non-root tag on the background executor.
    ///
    /// @param node selected current row
    /// @return future result completed on the UI dispatcher
    public CompletableFuture<NBTEditResult> deleteAsync(NBTEditorTreeNode node) {
        return mutateAsync(node, NBTEditor::remove);
    }

    /// Moves one ordered child on the background executor.
    ///
    /// @param node selected current row
    /// @param offset `-1` to move up or `1` to move down
    /// @return future result completed on the UI dispatcher
    public CompletableFuture<NBTEditResult> moveAsync(NBTEditorTreeNode node, int offset) {
        if (offset != -1 && offset != 1) {
            throw new IllegalArgumentException("offset must be -1 or 1");
        }
        NBTEditorTreeNode selected = Objects.requireNonNull(node, "node");
        if (isFixedSlot(selected.address())) {
            return CompletableFuture.completedFuture(
                    NBTEditResult.failure("Fixed Region and chunk-root slots cannot be reordered"));
        }
        return mutateAsync(selected, (editor, target) -> {
            NBTNode parent = editor.resolve(target.getAddress().parent());
            return editor.move(target, parent, selected.parentIndex() + offset);
        });
    }

    /// Sets one empty List's declared element type on the background executor.
    ///
    /// @param node selected empty List row
    /// @param type declared type, or null for TAG_End
    /// @return future result completed on the UI dispatcher
    public CompletableFuture<NBTEditResult> setListElementTypeAsync(
            NBTEditorTreeNode node,
            @Nullable TagType<?> type) {
        return mutateAsync(node, (editor, target) -> editor.setListElementType(target, type));
    }

    /// Copies one selected subtree on the background executor.
    ///
    /// @param node selected current row
    /// @return future result completed on the UI dispatcher after the clipboard is updated
    public CompletableFuture<NBTEditResult> copyAsync(NBTEditorTreeNode node) {
        NBTEditorTreeNode selected = Objects.requireNonNull(node, "node");
        AtomicReference<@Nullable Tag> copiedTag = new AtomicReference<>();
        CompletableFuture<NBTEditResult> task = startEditorTask(selected, (editor, target) -> {
            NBTElement detached = editor.snapshot(Objects.requireNonNull(target, "target"));
            if (!(detached instanceof Tag tag)) {
                return NBTEditResult.failure("Only NBT tags can be copied");
            }
            copiedTag.set(tag);
            return NBTEditResult.success(selected.address());
        }, false);
        return task.thenApply(result -> {
            requireUiThread();
            if (result.applied()) {
                clipboard = Objects.requireNonNull(copiedTag.get(), "copiedTag");
            }
            return result;
        });
    }

    /// Inserts a detached clipboard snapshot on the background executor.
    ///
    /// @param parent selected destination parent
    /// @param index insertion index
    /// @return future result completed on the UI dispatcher
    public CompletableFuture<NBTEditResult> pasteAsync(NBTEditorTreeNode parent, int index) {
        @Nullable Tag copied = clipboard;
        if (copied == null) {
            return CompletableFuture.completedFuture(NBTEditResult.failure("The NBT clipboard is empty"));
        }
        return mutateAsync(parent, (editor, target) -> editor.insertTag(target, index, copied));
    }

    /// Undoes one transaction on the background executor.
    ///
    /// @param preferred preferred selection before undo
    /// @return future result completed on the UI dispatcher
    public CompletableFuture<NBTEditResult> undoAsync(NBTAddress preferred) {
        return historyAsync(Objects.requireNonNull(preferred, "preferred"), true);
    }

    /// Redoes one transaction on the background executor.
    ///
    /// @param preferred preferred selection before redo
    /// @return future result completed on the UI dispatcher
    public CompletableFuture<NBTEditResult> redoAsync(NBTAddress preferred) {
        return historyAsync(Objects.requireNonNull(preferred, "preferred"), false);
    }

    /// Returns pretty SNBT for a selected tag without exposing the working tree.
    ///
    /// This read-only operation may run on a caller-owned background executor. The immutable node
    /// handle and volatile controller snapshot ensure that an obsolete selection fails closed.
    ///
    /// @param node selected current row
    /// @return selected subtree SNBT, or `null` for Region and Chunk container rows
    public @Nullable String subtreeSnbt(NBTEditorTreeNode node) {
        NBTEditorTreeNode selected = Objects.requireNonNull(node, "node");
        if (selected.node().getType() == null) {
            return null;
        }
        @Nullable NBTDocument document = currentDocument(selected);
        if (document == null) {
            return null;
        }
        try {
            NBTElement detached = document.editor().snapshot(selected.node());
            return detached instanceof Tag tag ? NBTTagInput.toSnbt(tag) : null;
        } catch (NBTEditException failure) {
            return null;
        }
    }

    /// Returns one structured value in decimal mode after validating the revision-bound node.
    ///
    /// @param node selected current row
    /// @return editable value text, or `null` for a non-value container
    public @Nullable String structuredValue(NBTEditorTreeNode node) {
        return structuredValue(node, NBTNumberRadix.DECIMAL);
    }

    /// Returns one structured value in the selected numeric display radix.
    ///
    /// @param node selected current row
    /// @param radix requested numeric display radix
    /// @return editable value text, or `null` for an unsupported container
    @Nullable String structuredValue(NBTEditorTreeNode node, NBTNumberRadix radix) {
        NBTEditorTreeNode selected = Objects.requireNonNull(node, "node");
        NBTNumberRadix selectedRadix = Objects.requireNonNull(radix, "radix");
        @Nullable TagType<?> type = selected.node().getType();
        if (type == null) {
            return null;
        }
        @Nullable NBTDocument document = currentDocument(selected);
        if (document == null) {
            return null;
        }
        try {
            NBTElement detached = document.editor().snapshot(selected.node());
            if (detached instanceof ValueTag<?> value) {
                return NBTStructuredValueCodec.formatScalar(type, value.getValue().toString(), selectedRadix);
            }
            if (detached instanceof Tag tag
                    && NBTStructuredValueCodec.isEditableAggregate(type, listElementType(tag))) {
                return NBTStructuredValueCodec.formatAggregate(tag, selectedRadix);
            }
            return null;
        } catch (NBTEditException failure) {
            return null;
        }
    }

    /// Converts a visible structured-value draft between numeric display bases without editing it.
    ///
    /// The selected tag snapshot supplies only the exact scalar, List, or array type. The entered
    /// text is parsed completely in the source radix before any reformatted text is returned.
    ///
    /// @param node selected current row
    /// @param text complete visible draft
    /// @param sourceRadix radix currently represented by the draft
    /// @param targetRadix requested display radix
    /// @return reformatted complete draft
    /// @throws IllegalArgumentException if the draft is invalid in its current radix
    /// @throws IllegalStateException if the selected node is stale or unsupported
    String reformatStructuredValue(
            NBTEditorTreeNode node,
            String text,
            NBTNumberRadix sourceRadix,
            NBTNumberRadix targetRadix) {
        NBTEditorTreeNode selected = Objects.requireNonNull(node, "node");
        @Nullable TagType<?> type = selected.node().getType();
        @Nullable NBTDocument document = currentDocument(selected);
        if (type == null || document == null) {
            throw new IllegalStateException("The selected numeric value is no longer available");
        }
        try {
            NBTElement detached = document.editor().snapshot(selected.node());
            if (detached instanceof ValueTag<?>) {
                String scalar = NBTStructuredValueCodec.parseScalar(type, text, sourceRadix);
                return NBTStructuredValueCodec.formatScalar(type, scalar, targetRadix);
            }
            if (detached instanceof Tag tag
                    && NBTStructuredValueCodec.isEditableAggregate(type, listElementType(tag))) {
                Tag parsed = NBTStructuredValueCodec.parseAggregate(tag, text, sourceRadix);
                return NBTStructuredValueCodec.formatAggregate(parsed, targetRadix);
            }
            throw new IllegalStateException("The selected tag has no numeric structured value");
        } catch (IOException failure) {
            throw new IllegalArgumentException("The current numeric draft is invalid", failure);
        } catch (NBTEditException failure) {
            throw new IllegalStateException("The selected numeric value is no longer available", failure);
        }
    }

    /// Returns value-, parent-, and root-aware conversion targets from the generic editor.
    ///
    /// @param node selected current tag row
    /// @return immutable targets including the current type, or an empty list for a non-tag row
    public @Unmodifiable List<TagType<?>> convertibleTypes(NBTEditorTreeNode node) {
        requireUiThread();
        NBTEditorTreeNode selected = Objects.requireNonNull(node, "node");
        @Nullable TagType<?> type = selected.node().getType();
        if (type == null) {
            return List.of();
        }
        @Nullable NBTDocument document = currentDocument(selected);
        if (document == null) {
            return List.of(type);
        }
        try {
            return List.copyOf(document.editor().getConvertibleTypes(selected.node()));
        } catch (NBTEditException failure) {
            return List.of(type);
        }
    }

    /// Returns the declared type of a selected List through a detached snapshot.
    ///
    /// @param node selected current row
    /// @return declared element type, or `null` for TAG_End or a non-List row
    public @Nullable TagType<?> listElementType(NBTEditorTreeNode node) {
        requireUiThread();
        NBTEditorTreeNode selected = Objects.requireNonNull(node, "node");
        if (selected.node().getType() != TagType.LIST) {
            return null;
        }
        @Nullable NBTDocument document = currentDocument(selected);
        if (document == null) {
            return null;
        }
        try {
            NBTElement detached = document.editor().snapshot(selected.node());
            return detached instanceof ListTag<?> list ? list.getElementType() : null;
        } catch (NBTEditException failure) {
            return null;
        }
    }

    /// Cancels current work, releases the current document in the background, and ignores late results.
    @Override
    public void close() {
        uiDispatcher.dispatchOrRun(this::closeOnUiThread);
    }

    /// Starts one background open and routes completion back to the UI dispatcher.
    ///
    /// @param operation operation identity
    /// @param previous state restored on failure
    /// @param target normalized source
    private void startOpen(long operation, NBTEditorSnapshot previous, Path target) {
        try {
            @Nullable NBTDocument previousDocument = previous.document();
            CompletableFuture<NBTDocument> future = previousDocument != null
                    && previousDocument.file().equals(target)
                    ? documentService.reload(previousDocument)
                    : documentService.open(target);
            activeOperation = future;
            future.whenComplete((@Nullable NBTDocument document, @Nullable Throwable failure) ->
                    dispatchCompletion(
                            () -> finishOpen(operation, previous, target, document, failure),
                            document));
        } catch (RuntimeException failure) {
            finishOpen(operation, previous, target, null, failure);
        }
    }

    /// Starts an in-session reload while retaining the old document until its replacement is ready.
    ///
    /// @param operation operation identity
    /// @param previous state restored on failure
    /// @param target normalized source
    private void startReload(long operation, NBTEditorSnapshot previous, Path target) {
        @Nullable NBTDocument document = previous.document();
        if (document == null) {
            startOpen(operation, previous, target);
            return;
        }
        try {
            CompletableFuture<NBTDocument> future = documentService.reload(document);
            activeOperation = future;
            future.whenComplete((@Nullable NBTDocument replacement, @Nullable Throwable failure) ->
                    dispatchCompletion(
                            () -> finishOpen(operation, previous, target, replacement, failure),
                            replacement));
        } catch (RuntimeException failure) {
            finishOpen(operation, previous, target, null, failure);
        }
    }

    /// Completes an open or reload on the UI dispatcher.
    ///
    /// @param operation operation identity
    /// @param previous state restored on failure
    /// @param target requested source
    /// @param document loaded document, or `null` on failure
    /// @param failure completion failure, or `null` on success
    private void finishOpen(
            long operation,
            NBTEditorSnapshot previous,
            Path target,
            @Nullable NBTDocument document,
            @Nullable Throwable failure) {
        requireUiThread();
        if (!accepts(operation)) {
            release(document);
            return;
        }
        activeOperation = null;
        if (failure == null && document != null) {
            @Nullable NBTDocument oldDocument = previous.document();
            if (oldDocument != document) {
                release(oldDocument);
            }
            clipboard = null;
            completePublishedOperation(
                    () -> publish(new NBTEditorSnapshot(
                            NBTEditorStatus.READY,
                            target,
                            document,
                            document.isDirty(),
                            null,
                            nextRevision())),
                    null,
                    null);
            return;
        }
        release(document);
        @Nullable NBTDocument retainedDocument = previous.document();
        if (retainedDocument != null && retainedDocument.isClosed()) {
            retainedDocument = null;
        }
        Path visibleFile = retainedDocument == null ? target : retainedDocument.file();
        @Nullable NBTDocument recoveryDocument = retainedDocument;
        completePublishedOperation(
                () -> publish(new NBTEditorSnapshot(
                        retainedOpenFailureStatus(previous.status(), recoveryDocument != null),
                        visibleFile,
                        recoveryDocument,
                        recoveryDocument != null && previous.dirty(),
                        failureMessage(failure),
                        nextRevision())),
                null,
                null);
    }

    /// Completes one save and preserves late dirty edits through the editor savepoint.
    ///
    /// @param operation operation identity
    /// @param document document supplied to the save
    /// @param previousStatus state from which this save was started
    /// @param failure completion failure, or `null` on success
    private void finishSave(
            long operation,
            NBTDocument document,
            NBTEditorStatus previousStatus,
            @Nullable Throwable failure) {
        requireUiThread();
        if (!accepts(operation)) {
            return;
        }
        activeOperation = null;
        boolean dirty = document.isDirty();
        if (failure == null) {
            completePublishedOperation(
                    () -> publish(new NBTEditorSnapshot(
                            NBTEditorStatus.READY,
                            snapshot.file(),
                            document,
                            dirty,
                            null,
                            nextRevision())),
                    null,
                    null);
            return;
        }
        Throwable cause = unwrap(failure);
        completePublishedOperation(
                () -> publish(new NBTEditorSnapshot(
                        classifySaveFailure(previousStatus, cause),
                        snapshot.file(),
                        document,
                        dirty,
                        failureMessage(cause),
                        nextRevision())),
                null,
                null);
    }

    /// Schedules one node mutation without running deep-copy or validation work on the UI thread.
    ///
    /// @param node selected current row
    /// @param mutation editor operation
    /// @return future result completed on the UI dispatcher
    private CompletableFuture<NBTEditResult> mutateAsync(
            NBTEditorTreeNode node,
            EditorMutation mutation) {
        NBTEditorTreeNode selected = Objects.requireNonNull(node, "node");
        EditorMutation selectedMutation = Objects.requireNonNull(mutation, "mutation");
        return startEditorTask(selected, (editor, target) -> {
            NBTNode changed = selectedMutation.apply(editor, Objects.requireNonNull(target, "target"));
            return NBTEditResult.success(changed.getAddress());
        }, true);
    }

    /// Applies one structured value through the correct editor primitive.
    ///
    /// @param editor owning generic editor
    /// @param target selected current node
    /// @param text complete value-field text
    /// @param radix selected numeric input radix
    /// @return changed current node
    /// @throws IOException if structured parsing fails
    /// @throws NBTEditException if the editor rejects the transaction
    private static NBTNode setStructuredValue(
            NBTEditor<? extends NBTElement> editor,
            NBTNode target,
            String text,
            NBTNumberRadix radix) throws IOException, NBTEditException {
        @Nullable TagType<?> type = target.getType();
        if (type == null) {
            throw new IOException("The selected node has no editable tag value");
        }
        NBTElement detached = editor.snapshot(target);
        if (detached instanceof Tag source
                && NBTStructuredValueCodec.isEditableAggregate(type, listElementType(source))) {
            return editor.replaceContent(target, NBTStructuredValueCodec.parseAggregate(source, text, radix));
        }
        String scalar = NBTStructuredValueCodec.parseScalar(type, text, radix);
        return editor.setScalar(target, scalar);
    }

    /// Returns a detached List tag's declared element type.
    ///
    /// @param tag detached selected tag
    /// @return declared List element type, or `null` for a non-List tag
    private static @Nullable TagType<?> listElementType(Tag tag) {
        return tag instanceof ListTag<?> list ? list.getElementType() : null;
    }

    /// Schedules one history operation and calculates a conservative surviving selection.
    ///
    /// @param preferred preferred pre-operation selection
    /// @param undo whether to undo rather than redo
    /// @return future result completed on the UI dispatcher
    private CompletableFuture<NBTEditResult> historyAsync(NBTAddress preferred, boolean undo) {
        NBTAddress selection = Objects.requireNonNull(preferred, "preferred");
        return startEditorTask(null, (editor, target) -> {
            if (undo) {
                editor.undo();
            } else {
                editor.redo();
            }
            return NBTEditResult.success(nearestAddress(editor, historySelectionAddress(selection)));
        }, true);
    }

    /// Claims the controller operation slot and dispatches one in-memory editor task.
    ///
    /// @param node selected row, or null for a history operation
    /// @param task complete background task
    /// @param mutates whether success advances the editor revision
    /// @return future result completed only after UI state is reconciled
    private CompletableFuture<NBTEditResult> startEditorTask(
            @Nullable NBTEditorTreeNode node,
            EditorTask task,
            boolean mutates) {
        requireUiThread();
        ensureOpen();
        @Nullable NBTDocument document = editableDocument(node);
        if (document == null) {
            return CompletableFuture.completedFuture(
                    NBTEditResult.failure("No editable NBT document is ready"));
        }
        EditorTask selectedTask = Objects.requireNonNull(task, "task");
        NBTEditorSnapshot previous = snapshot;
        long operation = beginOperation();
        publish(new NBTEditorSnapshot(
                NBTEditorStatus.EDITING,
                previous.file(),
                document,
                previous.dirty(),
                null,
                nextRevision()));

        CompletableFuture<NBTEditResult> delivery = new CompletableFuture<>();
        final CompletableFuture<NBTEditResult> work;
        try {
            work = documentService.supplyAsync(() -> runEditorTask(document, node, selectedTask));
        } catch (Error failure) {
            publishFatalEditorFailure(previous, document, failure, delivery);
            throw failure;
        } catch (RuntimeException failure) {
            publish(new NBTEditorSnapshot(
                    previous.status(),
                    previous.file(),
                    document,
                    document.isDirty(),
                    previous.message(),
                    nextRevision()));
            delivery.complete(NBTEditResult.failure(failureMessage(failure)));
            return delivery;
        }
        activeOperation = work;
        work.whenComplete((@Nullable NBTEditResult result, @Nullable Throwable failure) ->
                dispatchCompletion(
                        () -> finishEditorTask(
                                operation,
                                previous,
                                document,
                                mutates,
                                result,
                                failure,
                                delivery),
                        null));
        return delivery;
    }

    /// Runs one editor task while excluding save and close operations on the same document.
    ///
    /// @param document current document
    /// @param node selected row, or null for history
    /// @param task background task
    /// @return successful or rejected command result
    private static NBTEditResult runEditorTask(
            NBTDocument document,
            @Nullable NBTEditorTreeNode node,
            EditorTask task) {
        synchronized (document) {
            try {
                return task.apply(document.editor(), node == null ? null : node.node());
            } catch (NBTEditException failure) {
                return NBTEditResult.failure(failure);
            } catch (IOException | RuntimeException failure) {
                return NBTEditResult.failure(failureMessage(failure));
            }
        }
    }

    /// Reconciles one background edit and completes its UI-thread delivery future.
    ///
    /// @param operation operation identity
    /// @param previous state before editing began
    /// @param document edited document
    /// @param mutates whether a successful result changed the editor tree
    /// @param result task result, or null after exceptional completion
    /// @param failure exceptional task failure, or null after ordinary completion
    /// @param delivery caller-visible UI-thread result future
    private void finishEditorTask(
            long operation,
            NBTEditorSnapshot previous,
            NBTDocument document,
            boolean mutates,
            @Nullable NBTEditResult result,
            @Nullable Throwable failure,
            CompletableFuture<NBTEditResult> delivery) {
        requireUiThread();
        if (!accepts(operation)) {
            delivery.cancel(false);
            return;
        }
        activeOperation = null;
        if (failure != null && unwrap(failure) instanceof Error error) {
            publishFatalEditorFailure(previous, document, error, delivery);
            return;
        }
        NBTEditResult completed = failure == null && result != null
                ? result
                : NBTEditResult.failure(failureMessage(failure));
        Runnable publication = mutates && completed.applied()
                ? () -> publishEdited(document, previous.status(), previous.message())
                : () -> publish(new NBTEditorSnapshot(
                        previous.status(),
                        previous.file(),
                        document,
                        document.isDirty(),
                        previous.message(),
                        nextRevision()));
        completePublishedOperation(publication, () -> delivery.complete(completed), null);
    }

    /// Locks an editor document after a fatal background failure and propagates the original error.
    ///
    /// The working tree may have changed before the fatal failure escaped its transaction. Marking
    /// the view dirty is therefore conservative: it preserves discard confirmation while save and
    /// further edits remain disabled until a strict reload replaces the document.
    ///
    /// @param previous state before editing began
    /// @param document potentially affected document
    /// @param failure fatal failure to expose
    /// @param delivery caller-visible future to complete exceptionally
    private void publishFatalEditorFailure(
            NBTEditorSnapshot previous,
            NBTDocument document,
            Error failure,
            CompletableFuture<NBTEditResult> delivery) {
        activeOperation = null;
        completePublishedOperation(
                () -> publish(new NBTEditorSnapshot(
                        NBTEditorStatus.EDIT_UNCERTAIN,
                        previous.file(),
                        document,
                        true,
                        failureMessage(failure),
                        nextRevision())),
                () -> delivery.completeExceptionally(failure),
                failure);
    }

    /// Applies one current-node editor operation and publishes only after it commits.
    ///
    /// @param node selected row
    /// @param mutation editor operation
    /// @return transactional command result
    private NBTEditResult mutate(NBTEditorTreeNode node, EditorMutation mutation) {
        requireUiThread();
        ensureOpen();
        NBTEditorTreeNode selected = Objects.requireNonNull(node, "node");
        @Nullable NBTDocument document = editableDocument(selected);
        if (document == null) {
            return NBTEditResult.failure("No editable NBT document is ready");
        }
        final NBTNode result;
        try {
            result = mutation.apply(document.editor(), selected.node());
        } catch (NBTEditException failure) {
            return NBTEditResult.failure(failure);
        } catch (IOException failure) {
            return NBTEditResult.failure(failureMessage(failure));
        } catch (RuntimeException failure) {
            return NBTEditResult.failure(failureMessage(failure));
        }
        publishEdited(document);
        return NBTEditResult.success(result.getAddress());
    }

    /// Applies undo or redo and selects the nearest surviving address.
    ///
    /// @param preferred preferred pre-operation selection
    /// @param undo whether to undo rather than redo
    /// @return transactional command result
    private NBTEditResult history(NBTAddress preferred, boolean undo) {
        requireUiThread();
        ensureOpen();
        @Nullable NBTDocument document = editableDocument(null);
        if (document == null) {
            return NBTEditResult.failure("No editable NBT document is ready");
        }
        NBTEditor<? extends NBTElement> editor = document.editor();
        try {
            if (undo) {
                editor.undo();
            } else {
                editor.redo();
            }
            NBTAddress selection = nearestAddress(editor, historySelectionAddress(preferred));
            publishEdited(document);
            return NBTEditResult.success(selection);
        } catch (NBTEditException failure) {
            return NBTEditResult.failure(failure);
        }
    }

    /// Publishes a successful synchronous edit while retaining recovery states that need reload.
    ///
    /// @param document edited document
    private void publishEdited(NBTDocument document) {
        publishEdited(document, snapshot.status(), snapshot.message());
    }

    /// Publishes a successful edit relative to the state from which background work started.
    ///
    /// @param document edited document
    /// @param previousStatus state before editing began
    /// @param previousMessage retained recovery detail, or null
    private void publishEdited(
            NBTDocument document,
            NBTEditorStatus previousStatus,
            @Nullable String previousMessage) {
        boolean retainRecovery = previousStatus == NBTEditorStatus.PARTIAL_SAVE;
        publish(new NBTEditorSnapshot(
                retainRecovery ? previousStatus : NBTEditorStatus.READY,
                snapshot.file(),
                document,
                document.isDirty(),
                retainRecovery ? previousMessage : null,
                nextRevision()));
    }

    /// Returns the current document if a row belongs to it.
    ///
    /// @param node selected row, or `null` when no row identity is required
    /// @return current document, or `null` for an obsolete row or absent document
    private @Nullable NBTDocument currentDocument(@Nullable NBTEditorTreeNode node) {
        @Nullable NBTDocument document = snapshot.document();
        return document != null && (node == null || node.belongsTo(document)) ? document : null;
    }

    /// Returns the current document only when mutations are allowed.
    ///
    /// @param node selected row, or `null` for a history command
    /// @return editable current document, or `null`
    private @Nullable NBTDocument editableDocument(@Nullable NBTEditorTreeNode node) {
        if (snapshot.busy()
                || snapshot.status() == NBTEditorStatus.EDIT_UNCERTAIN
                || snapshot.status() == NBTEditorStatus.COMMIT_UNCERTAIN
                || snapshot.status() == NBTEditorStatus.CLOSED) {
            return null;
        }
        return currentDocument(node);
    }

    /// Finds the deepest prefix which still resolves after a structural history operation.
    ///
    /// @param editor current editor
    /// @param preferred requested address
    /// @return preferred address or its nearest existing parent
    private static NBTAddress nearestAddress(
            NBTEditor<? extends NBTElement> editor,
            NBTAddress preferred) {
        NBTAddress current = preferred;
        while (true) {
            try {
                editor.resolve(current);
                return current;
            } catch (NBTEditException missing) {
                if (current.isRoot()) {
                    return NBTAddress.root();
                }
                current = current.parent();
            }
        }
    }

    /// Returns a history selection which cannot silently retarget a different indexed subtree.
    ///
    /// List and primitive-array indices identify positions rather than stable element identities.
    /// A structural undo or redo can therefore leave the same index, or any named descendant below
    /// it, occupied by another element. Selecting the outer indexed container is the only
    /// conservative restoration available from an address alone.
    ///
    /// @param preferred selection before the history operation
    /// @return preferred address, or the parent before its first index segment
    private static NBTAddress historySelectionAddress(NBTAddress preferred) {
        @Unmodifiable List<NBTAddress.Segment> segments = preferred.segments();
        for (int index = 0; index < segments.size(); index++) {
            if (segments.get(index) instanceof NBTAddress.IndexSegment) {
                return NBTAddress.of(segments.subList(0, index));
            }
        }
        return preferred;
    }

    /// Returns whether an address identifies a fixed Region or chunk-root slot.
    ///
    /// @param address selected address
    /// @return whether sibling reordering is forbidden
    private static boolean isFixedSlot(NBTAddress address) {
        if (address.segments().isEmpty()) {
            return true;
        }
        NBTAddress.Segment segment = address.segments().get(address.segments().size() - 1);
        return segment instanceof NBTAddress.RegionChunkSegment
                || segment instanceof NBTAddress.ChunkRootSegment;
    }

    /// Dispatches one asynchronous completion and fails closed if submission or delivery fails.
    ///
    /// A successfully opened document belongs to the callback until the controller snapshot accepts it. Submission or
    /// callback failure therefore closes a still-undelivered session so its task resource lease cannot remain held
    /// forever. Save and edit completions pass no document because the visible snapshot still owns their session.
    ///
    /// @param completion UI-thread completion action
    /// @param undeliveredDocument newly opened document to release if delivery fails before publication, or `null`
    private void dispatchCompletion(Runnable completion, @Nullable NBTDocument undeliveredDocument) {
        Runnable checkedCompletion = Objects.requireNonNull(completion, "completion");
        AtomicBoolean started = new AtomicBoolean();
        Runnable guardedCompletion = () -> {
            started.set(true);
            try {
                checkedCompletion.run();
            } catch (RuntimeException | Error completionFailure) {
                releaseUndeliveredDocument(undeliveredDocument, completionFailure);
                LOG.warning("Failed to complete an NBT editor operation on the UI dispatcher", completionFailure);
                throw completionFailure;
            }
        };
        try {
            uiDispatcher.dispatch(guardedCompletion);
        } catch (RuntimeException | Error dispatchFailure) {
            if (!started.get()) {
                releaseUndeliveredDocument(undeliveredDocument, dispatchFailure);
                LOG.warning("Failed to dispatch an NBT editor completion", dispatchFailure);
            }
            throw dispatchFailure;
        }
    }

    /// Releases a successfully opened document which a failed completion did not publish.
    ///
    /// @param document newly opened document, or `null` for a completion without transferred ownership
    /// @param primaryFailure failure which remains authoritative if cleanup also fails
    private void releaseUndeliveredDocument(@Nullable NBTDocument document, Throwable primaryFailure) {
        if (document == null || snapshot.document() == document) {
            return;
        }
        try {
            release(document);
        } catch (RuntimeException | Error cleanupFailure) {
            if (cleanupFailure != primaryFailure) {
                primaryFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    /// Publishes a terminal operation state and runs mandatory completion steps despite listener failures.
    ///
    /// The first failure remains authoritative; later listener, caller-future, or deferred-open failures are attached
    /// as suppressed exceptions. A background fatal failure may be supplied as the initial cause so UI listener errors
    /// never replace it.
    ///
    /// @param publication synchronous state publication
    /// @param completion caller-visible completion action, or `null` when none exists
    /// @param primaryFailure existing primary failure, or `null`
    private void completePublishedOperation(
            Runnable publication,
            @Nullable Runnable completion,
            @Nullable Throwable primaryFailure) {
        @Nullable Throwable failure = runCompletionStep(primaryFailure, publication);
        if (completion != null) {
            failure = runCompletionStep(failure, completion);
        }
        failure = runCompletionStep(failure, this::startDeferredOpen);
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
    }

    /// Runs one mandatory UI completion step while retaining the first unchecked failure.
    ///
    /// @param first earlier failure, or `null`
    /// @param step mandatory completion step
    /// @return the first failure with any later failure suppressed, or `null` after success
    private static @Nullable Throwable runCompletionStep(@Nullable Throwable first, Runnable step) {
        try {
            Objects.requireNonNull(step, "step").run();
            return first;
        } catch (RuntimeException | Error next) {
            if (first == null) {
                return next;
            }
            if (first != next) {
                first.addSuppressed(next);
            }
            return first;
        }
    }

    /// Starts replacement work and invalidates the previous future.
    ///
    /// @return new operation identity
    private long beginOperation() {
        if (!tryBeginOperation()) {
            throw new IllegalStateException("Cannot replace an NBT operation after its commit point");
        }
        return operationRevision;
    }

    /// Attempts to start replacement work without invalidating a committed result awaiting UI reconciliation.
    ///
    /// @return whether a new operation identity was allocated
    private boolean tryBeginOperation() {
        @Nullable CompletableFuture<?> current = activeOperation;
        if (current != null && !current.cancel(true)) {
            return false;
        }
        activeOperation = null;
        operationRevision++;
        return true;
    }

    /// Starts the latest open request deferred behind a committed operation, if any.
    private void startDeferredOpen() {
        @Nullable Path target = deferredOpen;
        deferredOpen = null;
        if (target != null && !closed) {
            open(target);
        }
    }

    /// Returns whether a completion still owns the visible state.
    ///
    /// @param operation completed operation identity
    /// @return whether the result may be published
    private boolean accepts(long operation) {
        return !closed && operation == operationRevision;
    }

    /// Publishes one UI-thread transition.
    ///
    /// @param replacement replacement visible state
    private void publish(NBTEditorSnapshot replacement) {
        NBTEditorSnapshot previous = snapshot;
        snapshot = Objects.requireNonNull(replacement, "replacement");
        changes.fireChange(previous, replacement);
    }

    /// Returns the next visible-state revision.
    ///
    /// @return previous revision plus one
    private long nextRevision() {
        return snapshot.revision() + 1L;
    }

    /// Runs terminal closure on the UI dispatcher.
    private void closeOnUiThread() {
        requireUiThread();
        if (closed) {
            return;
        }
        closed = true;
        operationRevision++;
        deferredOpen = null;
        @Nullable CompletableFuture<?> current = activeOperation;
        activeOperation = null;
        if (current != null) {
            current.cancel(true);
        }
        @Nullable NBTDocument document = snapshot.document();
        release(document);
        clipboard = null;
        publish(new NBTEditorSnapshot(
                NBTEditorStatus.CLOSED,
                snapshot.file(),
                null,
                false,
                null,
                nextRevision()));
    }

    /// Releases a document asynchronously and logs ordinary cleanup failures.
    ///
    /// @param document document to close, or `null`
    private void release(@Nullable NBTDocument document) {
        if (document == null) {
            return;
        }
        try {
            documentService.close(document).whenComplete((@Nullable Void ignored, @Nullable Throwable failure) -> {
                if (failure != null) {
                    LOG.warning("Failed to close an NBT document", unwrap(failure));
                }
            });
        } catch (RuntimeException failure) {
            LOG.warning("Failed to schedule NBT document closure", failure);
        }
    }

    /// Verifies serialized access to controller state.
    private void requireUiThread() {
        if (!uiDispatcher.isDispatchThread()) {
            throw new IllegalStateException("NBT editor state must be changed on its UI dispatcher");
        }
    }

    /// Rejects operations after terminal closure.
    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("NBT editor controller is closed");
        }
    }

    /// Unwraps asynchronous completion wrappers.
    ///
    /// @param failure completion or direct failure
    /// @return deepest meaningful cause
    private static Throwable unwrap(Throwable failure) {
        Throwable current = Objects.requireNonNull(failure, "failure");
        while (current instanceof CompletionException && current.getCause() != null) {
            current = Objects.requireNonNull(current.getCause(), "current.getCause");
        }
        return current;
    }

    /// Builds concise non-empty technical detail.
    ///
    /// @param failure optional completion failure
    /// @return concise technical detail
    private static String failureMessage(@Nullable Throwable failure) {
        if (failure == null) {
            return "NBT operation completed without a document";
        }
        Throwable cause = unwrap(failure);
        @Nullable String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    /// Classifies a failed save into a stable recovery state.
    ///
    /// @param failure unwrapped save failure
    /// @return recovery status
    static NBTEditorStatus classifySaveFailure(Throwable failure) {
        if (containsCause(failure, NBTCommitUncertainException.class)
                || normalizedMessage(failure).contains("commit state is uncertain")) {
            return NBTEditorStatus.COMMIT_UNCERTAIN;
        }
        if (containsCause(failure, NBTPartialSaveException.class)) {
            return NBTEditorStatus.PARTIAL_SAVE;
        }
        return NBTEditorStatus.ERROR;
    }

    /// Classifies a failed save without losing an earlier partial-publication recovery boundary.
    ///
    /// A generic retry failure cannot prove that the disk and current editor snapshot agree. Only
    /// success clears `PARTIAL_SAVE`; an uncertain commit may upgrade it to a stricter recovery
    /// state.
    ///
    /// @param previousStatus state from which the save was started
    /// @param failure unwrapped save failure
    /// @return recovery status after the failed attempt
    static NBTEditorStatus classifySaveFailure(
            NBTEditorStatus previousStatus,
            Throwable failure) {
        NBTEditorStatus classified = classifySaveFailure(failure);
        return previousStatus == NBTEditorStatus.PARTIAL_SAVE && classified == NBTEditorStatus.ERROR
                ? NBTEditorStatus.PARTIAL_SAVE
                : classified;
    }

    /// Preserves a retained document's recovery boundary when a replacement open fails.
    ///
    /// @param previousStatus state which required the open or reload
    /// @param retainedDocument whether the old document is still visible
    /// @return state to publish with the new failure detail
    static NBTEditorStatus retainedOpenFailureStatus(
            NBTEditorStatus previousStatus,
            boolean retainedDocument) {
        if (!retainedDocument) {
            return NBTEditorStatus.ERROR;
        }
        return switch (Objects.requireNonNull(previousStatus, "previousStatus")) {
            case PARTIAL_SAVE, EDIT_UNCERTAIN, COMMIT_UNCERTAIN -> previousStatus;
            default -> NBTEditorStatus.ERROR;
        };
    }

    /// Returns whether a cause chain contains a requested checked type.
    ///
    /// @param failure initial failure
    /// @param type requested cause class
    /// @return whether the class occurs in the cause chain
    private static boolean containsCause(Throwable failure, Class<? extends Throwable> type) {
        @Nullable Throwable current = Objects.requireNonNull(failure, "failure");
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /// Returns a locale-stable normalized failure message.
    ///
    /// @param failure source failure
    /// @return lowercase message, or an empty string
    private static String normalizedMessage(Throwable failure) {
        @Nullable String message = failure.getMessage();
        return message == null ? "" : message.toLowerCase(Locale.ROOT);
    }

    /// One checked XoyzNBT mutation applied to a current immutable node.
    @FunctionalInterface
    @NotNullByDefault
    private interface EditorMutation {
        /// Applies one mutation.
        ///
        /// @param editor current document editor
        /// @param target current target node
        /// @return refreshed current node
        /// @throws IOException if strict text parsing fails
        /// @throws NBTEditException if validation rejects the operation
        NBTNode apply(NBTEditor<? extends NBTElement> editor, NBTNode target)
                throws IOException, NBTEditException;
    }

    /// One complete background operation over the current editor and optional selected node.
    @FunctionalInterface
    @NotNullByDefault
    private interface EditorTask {
        /// Executes one serialized editor operation.
        ///
        /// @param editor current document editor
        /// @param target current target node, or null for history
        /// @return complete command result
        /// @throws IOException if strict parsing fails
        /// @throws NBTEditException if validation rejects the operation
        NBTEditResult apply(
                NBTEditor<? extends NBTElement> editor,
                @Nullable NBTNode target) throws IOException, NBTEditException;
    }
}
