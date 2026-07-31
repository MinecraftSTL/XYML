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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.observable.property.ObservableValue;
import space.minecraftstl.xyml.observable.property.SimpleObjectProperty;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

/// Owns the process-wide Java-runtime state without depending on a presentation toolkit.
///
/// All state transitions are linearized by one lock. Only the newest concurrent refresh may replace the discovered
/// map, and additions or removals committed while that refresh scans are replayed over its result in call order. A
/// failed or superseded refresh therefore cannot roll back a newer explicit user action.
@NotNullByDefault
final class JavaRuntimeRegistry {
    /// Serializes map transitions, refresh ownership, and snapshot publication order.
    private final Object stateLock = new Object();

    /// Releases blocking readers after the first discovery result has been committed.
    private final CountDownLatch initializedLatch = new CountDownLatch(1);

    /// Publishes toolkit-neutral immutable snapshots.
    private final SimpleObjectProperty<JavaRuntimeSnapshot> snapshotProperty =
            new SimpleObjectProperty<>(this, "javaRuntimes", new JavaRuntimeSnapshot(false, 0L, List.of()));

    /// Immutable path-indexed state committed by the latest transition.
    private @Unmodifiable Map<Path, JavaRuntime> javaByBinary = Map.of();

    /// Explicit mutations recorded after the active refresh began.
    private final List<RefreshMutation> activeRefreshMutations = new ArrayList<>();

    /// Whether the initial discovery result has been committed.
    private boolean initialized;

    /// Revision assigned to the next published snapshot.
    private long snapshotRevision;

    /// Sequence assigned to the next refresh ticket.
    private long refreshSequence;

    /// Latest refresh ticket, or null when no refresh is eligible to commit.
    private @Nullable RefreshTicket activeRefresh;

    /// Returns the observable immutable snapshot.
    ObservableValue<JavaRuntimeSnapshot> snapshotProperty() {
        return snapshotProperty;
    }

    /// Returns whether initial discovery has completed.
    boolean isInitialized() {
        synchronized (stateLock) {
            return initialized;
        }
    }

    /// Commits the one allowed initial discovery result and releases waiting readers.
    ///
    /// @param discovered runtimes indexed by executable path
    void initialize(Map<Path, JavaRuntime> discovered) {
        Objects.requireNonNull(discovered, "discovered");
        synchronized (stateLock) {
            if (initialized) {
                throw new IllegalStateException("Java runtime registry is already initialized");
            }
            javaByBinary = immutableMap(discovered);
            initialized = true;
            try {
                publishSnapshotLocked();
            } finally {
                initializedLatch.countDown();
            }
        }
    }

    /// Waits for initialization and returns the current sorted immutable runtime list.
    ///
    /// @return current sorted immutable runtimes
    /// @throws InterruptedException if the caller is interrupted before initialization
    @Unmodifiable List<JavaRuntime> awaitRuntimes() throws InterruptedException {
        initializedLatch.await();
        synchronized (stateLock) {
            return Objects.requireNonNull(snapshotProperty.getValue(), "snapshot").getRuntimes();
        }
    }

    /// Waits for initialization and looks up one executable path.
    ///
    /// @param binary canonical executable path
    /// @return matching runtime, or null when the path is not registered
    /// @throws InterruptedException if the caller is interrupted before initialization
    @Nullable JavaRuntime awaitRuntime(Path binary) throws InterruptedException {
        Objects.requireNonNull(binary, "binary");
        initializedLatch.await();
        synchronized (stateLock) {
            return javaByBinary.get(binary);
        }
    }

    /// Starts a refresh generation after initialization.
    ///
    /// Starting a newer refresh supersedes every older ticket and begins a new mutation journal.
    ///
    /// @return ticket required to complete or cancel this refresh
    /// @throws InterruptedException if the caller is interrupted before initialization
    RefreshTicket beginRefresh() throws InterruptedException {
        initializedLatch.await();
        synchronized (stateLock) {
            RefreshTicket ticket = new RefreshTicket(++refreshSequence);
            activeRefresh = ticket;
            activeRefreshMutations.clear();
            return ticket;
        }
    }

    /// Commits a completed refresh when its ticket is still newest.
    ///
    /// Explicit additions and removals made after [#beginRefresh()] are replayed over the discovered map before the
    /// immutable snapshot is published.
    ///
    /// @param ticket refresh generation to complete
    /// @param discovered runtimes discovered by that generation
    /// @return true when this result was committed, or false when a newer refresh superseded it
    boolean completeRefresh(RefreshTicket ticket, Map<Path, JavaRuntime> discovered) {
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(discovered, "discovered");
        synchronized (stateLock) {
            if (activeRefresh != ticket) {
                return false;
            }

            Map<Path, JavaRuntime> refreshed = new HashMap<>(discovered);
            for (RefreshMutation mutation : activeRefreshMutations) {
                mutation.applyTo(refreshed);
            }
            javaByBinary = immutableMap(refreshed);
            activeRefresh = null;
            activeRefreshMutations.clear();
            publishSnapshotLocked();
            return true;
        }
    }

    /// Cancels a failed refresh when its ticket is still newest.
    ///
    /// @param ticket refresh generation to cancel
    void cancelRefresh(RefreshTicket ticket) {
        Objects.requireNonNull(ticket, "ticket");
        synchronized (stateLock) {
            if (activeRefresh == ticket) {
                activeRefresh = null;
                activeRefreshMutations.clear();
            }
        }
    }

    /// Adds one runtime after initialization when its executable path is not already present.
    ///
    /// @param javaRuntime runtime to add
    /// @return true when the state changed
    /// @throws InterruptedException if the caller is interrupted before initialization
    boolean add(JavaRuntime javaRuntime) throws InterruptedException {
        Objects.requireNonNull(javaRuntime, "javaRuntime");
        initializedLatch.await();
        synchronized (stateLock) {
            Path binary = javaRuntime.getBinary();
            if (javaByBinary.containsKey(binary)) {
                return false;
            }

            Map<Path, JavaRuntime> changed = new HashMap<>(javaByBinary);
            changed.put(binary, javaRuntime);
            javaByBinary = immutableMap(changed);
            if (activeRefresh != null) {
                activeRefreshMutations.add(RefreshMutation.add(javaRuntime));
            }
            publishSnapshotLocked();
            return true;
        }
    }

    /// Removes one runtime after initialization when its executable path is present.
    ///
    /// @param binary executable path to remove
    /// @return true when the state changed
    /// @throws InterruptedException if the caller is interrupted before initialization
    boolean remove(Path binary) throws InterruptedException {
        Objects.requireNonNull(binary, "binary");
        initializedLatch.await();
        synchronized (stateLock) {
            if (!javaByBinary.containsKey(binary)) {
                return false;
            }

            Map<Path, JavaRuntime> changed = new HashMap<>(javaByBinary);
            changed.remove(binary);
            javaByBinary = immutableMap(changed);
            if (activeRefresh != null) {
                activeRefreshMutations.add(RefreshMutation.remove(binary));
            }
            publishSnapshotLocked();
            return true;
        }
    }

    /// Copies a discovered map so callers cannot mutate committed state.
    ///
    /// @param runtimes runtime map to copy
    /// @return immutable map copy
    private static @Unmodifiable Map<Path, JavaRuntime> immutableMap(Map<Path, JavaRuntime> runtimes) {
        return Map.copyOf(runtimes);
    }

    /// Publishes the next sorted immutable snapshot while holding [#stateLock].
    private void publishSnapshotLocked() {
        @Unmodifiable List<JavaRuntime> sorted = javaByBinary.values().stream().sorted().toList();
        snapshotProperty.setValue(new JavaRuntimeSnapshot(true, ++snapshotRevision, sorted));
    }

    /// Identifies one refresh generation by object identity and sequence for diagnostics.
    @NotNullByDefault
    static final class RefreshTicket {
        /// Monotonically increasing refresh sequence.
        private final long sequence;

        /// Creates a refresh ticket.
        ///
        /// @param sequence monotonic refresh sequence
        private RefreshTicket(long sequence) {
            this.sequence = sequence;
        }

        /// Returns a diagnostic representation of this ticket.
        ///
        /// @return ticket sequence text
        @Override
        public String toString() {
            return "Java runtime refresh #" + sequence;
        }
    }

    /// Represents one explicit addition or removal that must survive an in-flight refresh.
    @NotNullByDefault
    private static final class RefreshMutation {
        /// Executable path affected by this mutation.
        private final Path binary;

        /// Runtime to add, or null when this mutation removes [#binary].
        private final @Nullable JavaRuntime addedRuntime;

        /// Creates one refresh mutation.
        ///
        /// @param binary affected executable path
        /// @param addedRuntime runtime to add, or null for removal
        private RefreshMutation(Path binary, @Nullable JavaRuntime addedRuntime) {
            this.binary = Objects.requireNonNull(binary, "binary");
            this.addedRuntime = addedRuntime;
        }

        /// Creates an addition mutation.
        ///
        /// @param javaRuntime runtime to add
        /// @return addition mutation
        private static RefreshMutation add(JavaRuntime javaRuntime) {
            return new RefreshMutation(javaRuntime.getBinary(), javaRuntime);
        }

        /// Creates a removal mutation.
        ///
        /// @param binary executable path to remove
        /// @return removal mutation
        private static RefreshMutation remove(Path binary) {
            return new RefreshMutation(binary, null);
        }

        /// Applies this mutation to a mutable refresh result.
        ///
        /// @param runtimes mutable path-indexed refresh result
        private void applyTo(Map<Path, JavaRuntime> runtimes) {
            if (addedRuntime == null) {
                runtimes.remove(binary);
            } else {
                runtimes.put(binary, addedRuntime);
            }
        }
    }
}
