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
import space.minecraftstl.xyml.nbt.NBTDocument;
import space.minecraftstl.xyml.nbt.NBTDocumentService;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.ui.UiDispatcher;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/// Serializes NBT editor state on a UI dispatcher while the backend owns every filesystem access.
///
/// A monotonic operation identity invalidates replaced and cancelled work. Cancellation is best
/// effort because a filesystem provider may ignore interruption; every completion still checks the
/// identity before publishing, so a late result can never replace a newer document or a closed page.
@NotNullByDefault
public final class NBTEditorController implements AutoCloseable {
    /// Background NBT document boundary.
    private final NBTDocumentService documentService;

    /// UI queue that serializes visible state transitions.
    private final UiDispatcher uiDispatcher;

    /// Synchronous subscribers notified only from the UI dispatcher.
    private final ValueChangeSupport<NBTEditorSnapshot> changes = new ValueChangeSupport<>(this);

    /// Latest immutable visible state.
    private volatile NBTEditorSnapshot snapshot = NBTEditorSnapshot.empty();

    /// Current background future, or `null` while idle.
    private @Nullable CompletableFuture<?> activeOperation;

    /// Monotonic identity incremented whenever work is replaced or the controller closes.
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
    /// The caller should render `snapshot()` once before or after registration because subscribing
    /// does not replay the current value.
    ///
    /// @param listener state-change listener
    /// @return independently removable registration
    public Subscription subscribe(ValueChangeListener<NBTEditorSnapshot> listener) {
        return changes.subscribe(Objects.requireNonNull(listener, "listener"));
    }

    /// Replaces the current selection with one asynchronously opened source.
    ///
    /// The caller owns any dirty-document confirmation before invoking this method.
    ///
    /// @param file candidate source path
    public void open(Path file) {
        requireUiThread();
        ensureOpen();
        Path target = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        NBTEditorSnapshot previous = snapshot;
        long operation = beginOperation();
        publish(new NBTEditorSnapshot(
                NBTEditorStatus.OPENING,
                target,
                null,
                false,
                null,
                nextRevision()));
        try {
            CompletableFuture<NBTDocument> future = documentService.open(target);
            activeOperation = future;
            future.whenComplete((@Nullable NBTDocument document, @Nullable Throwable failure) ->
                    uiDispatcher.dispatch(() -> finishOpen(operation, previous, target, document, failure)));
        } catch (RuntimeException failure) {
            finishOpen(operation, previous, target, null, failure);
        }
    }

    /// Reopens the current source asynchronously and discards the current in-memory document on success.
    ///
    /// The caller owns any dirty-document confirmation before invoking this method.
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
        try {
            CompletableFuture<NBTDocument> future = documentService.open(file);
            activeOperation = future;
            future.whenComplete((@Nullable NBTDocument document, @Nullable Throwable failure) ->
                    uiDispatcher.dispatch(() -> finishOpen(operation, previous, file, document, failure)));
        } catch (RuntimeException failure) {
            finishOpen(operation, previous, file, null, failure);
        }
    }

    /// Saves the current document asynchronously using stale-source protection.
    public void save() {
        requireUiThread();
        ensureOpen();
        NBTEditorSnapshot previous = snapshot;
        @Nullable NBTDocument document = previous.document();
        if (document == null
                || previous.busy()
                || !previous.dirty()
                || previous.status() == NBTEditorStatus.CONFLICT) {
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
                    uiDispatcher.dispatch(() -> finishSave(operation, previous, failure)));
        } catch (RuntimeException failure) {
            finishSave(operation, previous, failure);
        }
    }

    /// Applies one type-preserving scalar edit immediately on the UI dispatcher.
    ///
    /// @param node selected node from the current document's tree model
    /// @param text proposed scalar text
    /// @return validation and mutation result
    public NBTValueEditResult applyValueEdit(NBTEditorTreeNode node, String text) {
        requireUiThread();
        ensureOpen();
        NBTEditorSnapshot current = snapshot;
        @Nullable NBTDocument document = current.document();
        if (document == null || current.busy()) {
            return NBTValueEditResult.failure("No editable NBT document is ready");
        }
        NBTEditorTreeNode selected = Objects.requireNonNull(node, "node");
        if (!selected.belongsTo(document)) {
            return NBTValueEditResult.failure("The selected node belongs to an obsolete document");
        }
        NBTValueEditResult result = NBTValueEditor.apply(selected.element(), text);
        if (result.applied()) {
            NBTEditorStatus status = current.status() == NBTEditorStatus.CONFLICT
                    ? NBTEditorStatus.CONFLICT
                    : NBTEditorStatus.READY;
            publish(new NBTEditorSnapshot(
                    status,
                    current.file(),
                    document,
                    true,
                    status == NBTEditorStatus.CONFLICT ? current.message() : null,
                    nextRevision()));
        }
        return result;
    }

    /// Cancels current work and permanently ignores every late result.
    @Override
    public void close() {
        uiDispatcher.dispatchOrRun(this::closeOnUiThread);
    }

    /// Completes an open or reload on the UI dispatcher.
    ///
    /// @param operation operation identity
    /// @param previous state restored on reload failure
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
            return;
        }
        activeOperation = null;
        if (failure == null && document != null) {
            publish(new NBTEditorSnapshot(
                    NBTEditorStatus.READY,
                    target,
                    document,
                    false,
                    null,
                    nextRevision()));
            return;
        }
        @Nullable NBTDocument retainedDocument = previous.document();
        Path visibleFile = retainedDocument == null ? target : Objects.requireNonNull(previous.file(), "previous.file");
        publish(new NBTEditorSnapshot(
                NBTEditorStatus.ERROR,
                visibleFile,
                retainedDocument,
                retainedDocument != null && previous.dirty(),
                failureMessage(failure),
                nextRevision()));
    }

    /// Completes one save on the UI dispatcher.
    ///
    /// @param operation operation identity
    /// @param previous pre-save document state
    /// @param failure completion failure, or `null` on success
    private void finishSave(
            long operation,
            NBTEditorSnapshot previous,
            @Nullable Throwable failure) {
        requireUiThread();
        if (!accepts(operation)) {
            return;
        }
        activeOperation = null;
        if (failure == null) {
            publish(new NBTEditorSnapshot(
                    NBTEditorStatus.READY,
                    previous.file(),
                    previous.document(),
                    false,
                    null,
                    nextRevision()));
            return;
        }
        Throwable cause = unwrap(failure);
        publish(new NBTEditorSnapshot(
                isConflict(cause) ? NBTEditorStatus.CONFLICT : NBTEditorStatus.ERROR,
                previous.file(),
                previous.document(),
                true,
                failureMessage(cause),
                nextRevision()));
    }

    /// Starts a replacement operation and invalidates the previous future.
    ///
    /// @return new operation identity
    private long beginOperation() {
        @Nullable CompletableFuture<?> current = activeOperation;
        activeOperation = null;
        operationRevision++;
        if (current != null) {
            current.cancel(true);
        }
        return operationRevision;
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
        @Nullable CompletableFuture<?> current = activeOperation;
        activeOperation = null;
        if (current != null) {
            current.cancel(true);
        }
        publish(new NBTEditorSnapshot(
                NBTEditorStatus.CLOSED,
                snapshot.file(),
                snapshot.document(),
                snapshot.dirty(),
                null,
                nextRevision()));
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

    /// Unwraps asynchronous wrapper failures.
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

    /// Builds a concise non-empty status detail.
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

    /// Classifies the backend's stale-source failures without treating unrelated I/O as conflicts.
    ///
    /// @param failure unwrapped failure
    /// @return whether the source changed outside this document
    private static boolean isConflict(Throwable failure) {
        if (!(failure instanceof IOException)) {
            return false;
        }
        @Nullable String message = failure.getMessage();
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("changed since it was opened")
                || normalized.contains("changed while a save was being staged");
    }
}
