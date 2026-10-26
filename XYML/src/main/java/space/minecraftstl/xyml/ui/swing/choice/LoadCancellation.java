// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.choice;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Objects.requireNonNull;

/// A cooperative cancellation signal passed to a viewport data-source request.
@NotNullByDefault
public final class LoadCancellation {
    /// Whether cancellation has been requested.
    private final AtomicBoolean cancelled = new AtomicBoolean();

    /// First upstream signal observed without taking ownership, or null for a root signal.
    private final @Nullable LoadCancellation firstUpstream;

    /// Second upstream signal observed without taking ownership, or null for a root signal.
    private final @Nullable LoadCancellation secondUpstream;

    /// Creates an active cancellation signal.
    public LoadCancellation() {
        this(null, null);
    }

    /// Creates a signal linked to two independently owned upstream signals.
    ///
    /// @param firstUpstream first signal to observe, or null for none
    /// @param secondUpstream second signal to observe, or null for none
    private LoadCancellation(
            @Nullable LoadCancellation firstUpstream,
            @Nullable LoadCancellation secondUpstream) {
        this.firstUpstream = firstUpstream;
        this.secondUpstream = secondUpstream;
    }

    /// Creates an independently cancellable signal that observes either upstream signal.
    ///
    /// Cancelling the returned signal never mutates either caller-owned upstream signal.
    ///
    /// @param first first upstream signal
    /// @param second second upstream signal
    /// @return linked cancellation signal
    public static LoadCancellation linkedTo(
            LoadCancellation first,
            LoadCancellation second) {
        return new LoadCancellation(
                requireNonNull(first, "first"),
                requireNonNull(second, "second"));
    }

    /// Requests cancellation of the associated load.
    public void cancel() {
        cancelled.set(true);
    }

    /// Returns whether cancellation has been requested.
    ///
    /// @return whether the associated result must be discarded
    public boolean isCancelled() {
        return cancelled.get()
                || firstUpstream != null && firstUpstream.isCancelled()
                || secondUpstream != null && secondUpstream.isCancelled();
    }

    /// Throws when cancellation has been requested.
    ///
    /// Data sources may call this between expensive loading steps.
    public void throwIfCancelled() {
        if (isCancelled()) {
            throw new CancellationException("Viewport load was cancelled");
        }
    }
}
