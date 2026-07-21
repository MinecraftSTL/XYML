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

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/// A cooperative cancellation signal passed to a viewport data-source request.
@NotNullByDefault
public final class LoadCancellation {
    /// Whether cancellation has been requested.
    private final AtomicBoolean cancelled = new AtomicBoolean();

    /// Creates an active cancellation signal.
    public LoadCancellation() {
    }

    /// Requests cancellation of the associated load.
    public void cancel() {
        cancelled.set(true);
    }

    /// Returns whether cancellation has been requested.
    ///
    /// @return whether the associated result must be discarded
    public boolean isCancelled() {
        return cancelled.get();
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
