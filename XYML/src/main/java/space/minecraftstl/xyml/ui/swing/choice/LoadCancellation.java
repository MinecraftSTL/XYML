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
