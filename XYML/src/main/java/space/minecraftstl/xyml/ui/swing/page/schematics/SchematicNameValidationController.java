// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.schematics;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Keeps at most one validation request active and one latest input pending.
/// All controller state and result delivery are confined to the Swing EDT.
@NotNullByDefault
final class SchematicNameValidationController implements AutoCloseable {
    /// Caller-owned asynchronous validation boundary.
    private final Function<String, CompletionStage<@Nullable String>> validator;
    /// Reports validity and an optional localized error; pending validation is false with no error.
    private final BiConsumer<Boolean, @Nullable String> listener;
    /// Latest input revision invalidates every earlier asynchronous response.
    private long revision;
    /// Latest input waiting behind the single active validation.
    private @Nullable String pending;
    /// Whether one validation owns the execution slot.
    private boolean checking;
    /// Closed dialogs never receive late completions.
    private boolean closed;

    /// Creates a controller with injected validation and presentation boundaries.
    SchematicNameValidationController(Function<String, CompletionStage<@Nullable String>> validator,
                                      BiConsumer<Boolean, @Nullable String> listener) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /// Coalesces repeated edits without queueing unbounded background I/O.
    void request(String name) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) return;
        if (revision == Long.MAX_VALUE) {
            closed = true;
            pending = null;
            listener.accept(false, i18n("schematics.create_directory.failed"));
            return;
        }
        revision++;
        pending = Objects.requireNonNull(name, "name");
        listener.accept(false, null);
        if (!checking) startLatest();
    }

    /// Starts the latest pending input and captures its exact revision.
    private void startLatest() {
        String name = Objects.requireNonNull(pending, "pending input");
        pending = null;
        long captured = revision;
        checking = true;
        try {
            validator.apply(name).whenComplete((@Nullable String error, @Nullable Throwable failure) ->
                    EdtDispatcher.execute(() -> complete(captured, error, failure)));
        } catch (RuntimeException failure) {
            complete(captured, null, failure);
        }
    }

    /// Rejects obsolete results before starting the next coalesced request.
    private void complete(long captured, @Nullable String error, @Nullable Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        checking = false;
        if (closed) return;
        if (captured == revision) {
            @Nullable String diagnostic = failure == null ? error : i18n("schematics.create_directory.failed");
            listener.accept(diagnostic == null, diagnostic);
        }
        if (pending != null) startLatest();
    }

    /// Invalidates outstanding responses without claiming that cancellation stopped underlying I/O.
    @Override
    public void close() {
        EdtDispatcher.requireEventDispatchThread();
        closed = true;
        pending = null;
    }
}
