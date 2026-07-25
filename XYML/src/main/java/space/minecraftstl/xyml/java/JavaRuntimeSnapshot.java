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
package space.minecraftstl.xyml.java;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Collection;
import java.util.List;

/// Describes one immutable, sorted view of the Java runtimes known to the launcher.
///
/// Revisions increase for every published initialization, refresh, addition, or removal. Before initialization the
/// runtime list is empty and [#isInitialized()] is false, which lets presentation adapters distinguish loading from
/// an initialized installation that genuinely contains no compatible Java runtimes.
@NotNullByDefault
public final class JavaRuntimeSnapshot {
    /// Whether initial runtime discovery has completed.
    private final boolean initialized;

    /// Monotonically increasing publication revision.
    private final long revision;

    /// Sorted immutable runtime values for this revision.
    private final @Unmodifiable List<JavaRuntime> runtimes;

    /// Creates an immutable runtime snapshot.
    ///
    /// @param initialized whether initial discovery has completed
    /// @param revision monotonically increasing publication revision
    /// @param runtimes runtimes to copy in their already sorted order
    JavaRuntimeSnapshot(boolean initialized, long revision, Collection<JavaRuntime> runtimes) {
        this.initialized = initialized;
        this.revision = revision;
        this.runtimes = List.copyOf(runtimes);
    }

    /// Returns whether initial runtime discovery has completed.
    public boolean isInitialized() {
        return initialized;
    }

    /// Returns the monotonically increasing publication revision.
    public long getRevision() {
        return revision;
    }

    /// Returns the sorted immutable runtimes for this revision.
    public @Unmodifiable List<JavaRuntime> getRuntimes() {
        return runtimes;
    }
}
