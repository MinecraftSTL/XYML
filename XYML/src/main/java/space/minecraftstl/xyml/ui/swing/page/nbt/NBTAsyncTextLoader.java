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
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.text.AbstractDocument;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/// Loads potentially large editor text off the EDT and inserts it in bounded EDT chunks.
@NotNullByDefault
final class NBTAsyncTextLoader implements AutoCloseable {
    /// Destination text component.
    private final JTextComponent target;

    /// Maximum characters inserted during one EDT turn.
    private final int chunkSize;

    /// Caller-owned executor for detached snapshot formatting.
    private final Executor executor;

    /// Monotonic request identity used to reject late results.
    private long revision;

    /// Logical selection represented by the destination, or `null` after reset.
    private @Nullable Object key;

    /// Current background load, or `null` while idle.
    private @Nullable CompletableFuture<@Nullable String> load;

    /// Whether the current key has been inserted completely.
    private boolean loaded;

    /// Whether terminal closure has occurred.
    private boolean closed;

    /// Creates one loader for a specific text component.
    /// @param target destination text component
    /// @param chunkSize positive per-turn character limit
    /// @param executor caller-owned background executor
    NBTAsyncTextLoader(JTextComponent target, int chunkSize, Executor executor) {
        this.target = Objects.requireNonNull(target, "target");
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive");
        }
        this.chunkSize = chunkSize;
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /// Resets the destination only when its logical selection changes.
    /// @param newKey new logical selection, or `null`
    void reset(@Nullable Object newKey) {
        EdtDispatcher.requireEventDispatchThread();
        if (Objects.equals(key, newKey)) {
            return;
        }
        changeKey(newKey, true, false);
    }

    /// Changes the logical selection while preserving the visible draft during conversion.
    /// @param newKey new non-null logical selection
    void prepare(Object newKey) {
        EdtDispatcher.requireEventDispatchThread();
        Object selectedKey = Objects.requireNonNull(newKey, "newKey");
        if (!Objects.equals(key, selectedKey)) {
            changeKey(selectedKey, false, false);
        }
    }

    /// Reclaims a preserved visible draft as a completely loaded key after conversion rejection.
    /// @param retainedKey restored logical selection
    void retain(Object retainedKey) {
        EdtDispatcher.requireEventDispatchThread();
        Object selectedKey = Objects.requireNonNull(retainedKey, "retainedKey");
        if (Objects.equals(key, selectedKey)) {
            loaded = true;
        } else {
            changeKey(selectedKey, false, true);
        }
    }

    /// Changes key ownership, cancellation state, and optional visible text.
    private void changeKey(@Nullable Object newKey, boolean clear, boolean complete) {
        EdtDispatcher.requireEventDispatchThread();
        revision++;
        @Nullable CompletableFuture<@Nullable String> current = load;
        load = null;
        if (current != null) {
            current.cancel(true);
        }
        key = newKey;
        loaded = complete;
        if (clear) {
            target.setText("");
        }
    }

    /// Returns whether the requested key has completed insertion.
    /// @param candidate candidate logical selection
    /// @return whether its complete value is visible
    boolean isLoaded(Object candidate) {
        EdtDispatcher.requireEventDispatchThread();
        return loaded && Objects.equals(key, Objects.requireNonNull(candidate, "candidate"));
    }

    /// Returns whether a background load is currently active.
    /// @return whether loading is active
    boolean isLoading() {
        EdtDispatcher.requireEventDispatchThread();
        return load != null;
    }

    /// Starts one load for the current key unless it is already loaded or in progress.
    /// @param candidate current logical selection
    /// @param supplier background text supplier
    /// @param accepted current-selection predicate evaluated on the EDT
    /// @param started EDT callback before dispatch
    /// @param succeeded EDT callback after complete insertion
    /// @param failed EDT callback with technical detail
    void load(
            Object candidate,
            Supplier<@Nullable String> supplier,
            BooleanSupplier accepted,
            Runnable started,
            Runnable succeeded,
            Consumer<String> failed) {
        EdtDispatcher.requireEventDispatchThread();
        Object selectedKey = Objects.requireNonNull(candidate, "candidate");
        if (closed || !Objects.equals(key, selectedKey) || loaded || load != null) {
            return;
        }
        Supplier<@Nullable String> selectedSupplier = Objects.requireNonNull(supplier, "supplier");
        BooleanSupplier acceptance = Objects.requireNonNull(accepted, "accepted");
        Runnable success = Objects.requireNonNull(succeeded, "succeeded");
        Consumer<String> failure = Objects.requireNonNull(failed, "failed");
        Objects.requireNonNull(started, "started").run();
        long request = ++revision;
        try {
            CompletableFuture<@Nullable String> future = CompletableFuture.supplyAsync(selectedSupplier, executor);
            load = future;
            future.whenComplete((@Nullable String text, @Nullable Throwable problem) ->
                    EdtDispatcher.execute(() -> finish(
                            request, selectedKey, acceptance, success, failure, text, problem)));
        } catch (RuntimeException problem) {
            failure.accept(detail(problem));
        }
    }

    /// Invalidates all pending work and clears the destination.
    @Override
    public void close() {
        EdtDispatcher.requireEventDispatchThread();
        closed = true;
        reset(null);
    }

    /// Accepts one current background result.
    private void finish(
            long request,
            Object selectedKey,
            BooleanSupplier accepted,
            Runnable succeeded,
            Consumer<String> failed,
            @Nullable String text,
            @Nullable Throwable problem) {
        if (!accepts(request, selectedKey, accepted)) {
            // A predicate can become false without a key change when the backing editor revision
            // advances. Release that completed request so the same row may be loaded again.
            if (request == revision) {
                load = null;
            }
            return;
        }
        if (problem != null || text == null) {
            load = null;
            @Nullable Throwable cause = problem == null ? null : unwrap(problem);
            if (cause instanceof Error error) {
                throw error;
            }
            failed.accept(cause == null ? "The selected value is no longer available" : detail(cause));
            return;
        }
        String previousText;
        try {
            previousText = readText();
        } catch (BadLocationException failure) {
            load = null;
            failed.accept(detail(failure));
            return;
        }
        int previousDot = target.getCaret().getDot();
        int previousMark = target.getCaret().getMark();
        try {
            replaceText("");
        } catch (BadLocationException failure) {
            failAndRestore(request, previousText, previousDot, previousMark, failed, failure);
            return;
        }
        append(request, selectedKey, accepted, succeeded, failed,
                previousText, previousDot, previousMark, text, 0);
    }

    /// Inserts the next bounded chunk and schedules any remainder.
    private void append(
            long request,
            Object selectedKey,
            BooleanSupplier accepted,
            Runnable succeeded,
            Consumer<String> failed,
            String previousText,
            int previousDot,
            int previousMark,
            String text,
            int offset) {
        if (!accepts(request, selectedKey, accepted)) {
            if (request == revision) {
                load = null;
                loaded = false;
                restoreOrReport(previousText, previousDot, previousMark, failed);
            }
            return;
        }
        int end = Math.min(offset + chunkSize, text.length());
        if (end > offset) {
            try {
                String chunk = text.substring(offset, end);
                Document document = target.getDocument();
                int insertionOffset = document.getLength();
                document.insertString(insertionOffset, chunk, null);
                if (document.getLength() != insertionOffset + chunk.length()
                        || !chunk.equals(document.getText(insertionOffset, chunk.length()))) {
                    throw new BadLocationException("The editor document changed a loaded text chunk", insertionOffset);
                }
            } catch (BadLocationException failure) {
                failAndRestore(request, previousText, previousDot, previousMark, failed, failure);
                return;
            }
        }
        if (end < text.length()) {
            EdtDispatcher.executeLater(() -> append(
                    request, selectedKey, accepted, succeeded, failed,
                    previousText, previousDot, previousMark, text, end));
            return;
        }
        load = null;
        loaded = true;
        target.setCaretPosition(0);
        succeeded.run();
    }

    /// Releases a current failed request, restores its prior draft, and reports the root cause.
    ///
    /// @param request failed request identity
    /// @param previousText exact text visible before loading began
    /// @param previousDot prior caret dot
    /// @param previousMark prior caret mark
    /// @param failed caller-owned failure callback
    /// @param problem insertion or replacement failure
    private void failAndRestore(
            long request,
            String previousText,
            int previousDot,
            int previousMark,
            Consumer<String> failed,
            BadLocationException problem) {
        if (request != revision) {
            return;
        }
        load = null;
        loaded = false;
        @Nullable String restorationFailure = restore(previousText, previousDot, previousMark);
        String failureDetail = detail(problem);
        failed.accept(restorationFailure == null
                ? failureDetail
                : failureDetail + "; previous editor text could not be restored: " + restorationFailure);
    }

    /// Restores an abandoned current request, reporting only if restoration itself fails.
    ///
    /// @param previousText exact text visible before loading began
    /// @param previousDot prior caret dot
    /// @param previousMark prior caret mark
    /// @param failed caller-owned failure callback
    private void restoreOrReport(
            String previousText,
            int previousDot,
            int previousMark,
            Consumer<String> failed) {
        @Nullable String restorationFailure = restore(previousText, previousDot, previousMark);
        if (restorationFailure != null) {
            failed.accept("Previous editor text could not be restored: " + restorationFailure);
        }
    }

    /// Restores exact prior text and selection direction after an interrupted replacement.
    ///
    /// @param previousText exact prior text
    /// @param previousDot prior caret dot
    /// @param previousMark prior caret mark
    /// @return failure detail, or `null` after complete restoration
    private @Nullable String restore(String previousText, int previousDot, int previousMark) {
        try {
            replaceText(previousText);
            int length = target.getDocument().getLength();
            target.getCaret().setDot(Math.min(previousMark, length));
            target.getCaret().moveDot(Math.min(previousDot, length));
            return null;
        } catch (BadLocationException | RuntimeException failure) {
            return detail(failure);
        }
    }

    /// Strictly replaces the complete target document and verifies filters did not alter the text.
    ///
    /// @param text exact replacement text
    /// @throws BadLocationException if replacement fails or a filter changes the text
    private void replaceText(String text) throws BadLocationException {
        Document document = target.getDocument();
        if (document instanceof AbstractDocument abstractDocument) {
            abstractDocument.replace(0, document.getLength(), text, null);
        } else {
            document.remove(0, document.getLength());
            document.insertString(0, text, null);
        }
        if (!text.equals(readText())) {
            throw new BadLocationException("The editor document changed replacement text", 0);
        }
    }

    /// Reads the complete target document without JTextComponent's nullable fallback.
    ///
    /// @return exact target text
    /// @throws BadLocationException if the document cannot provide its own valid range
    private String readText() throws BadLocationException {
        Document document = target.getDocument();
        return document.getText(0, document.getLength());
    }

    /// Returns whether one request still owns the destination.
    private boolean accepts(long request, Object selectedKey, BooleanSupplier accepted) {
        return !closed && request == revision && Objects.equals(key, selectedKey) && accepted.getAsBoolean();
    }

    /// Returns concise non-empty detail for one asynchronous failure.
    private static String detail(Throwable failure) {
        Throwable cause = Objects.requireNonNull(failure, "failure");
        @Nullable String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    /// Removes a completion wrapper while preserving the original failure type.
    ///
    /// @param failure completion failure
    /// @return unwrapped cause, or the supplied failure when no cause is present
    private static Throwable unwrap(Throwable failure) {
        Throwable current = Objects.requireNonNull(failure, "failure");
        while (current instanceof java.util.concurrent.CompletionException
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
