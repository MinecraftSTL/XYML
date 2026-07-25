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
package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.java.JavaManager;
import space.minecraftstl.xyml.java.JavaRuntime;
import space.minecraftstl.xyml.java.JavaRuntimeSnapshot;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.collection.CollectionChangeListener;
import space.minecraftstl.xyml.observable.collection.SetChange;
import space.minecraftstl.xyml.observable.property.ObservableValue;
import space.minecraftstl.xyml.setting.SettingsManager;
import space.minecraftstl.xyml.setting.UserSettings;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/// Adapts process-wide [JavaManager] discovery and user settings to the Swing runtime lifecycle model.
///
/// Every lifecycle method returns a stopped task. Read-only and runtime-kind checks are evaluated when that task runs,
/// so callers can safely create tasks for confirmation or progress presentation without changing launcher state.
@NotNullByDefault
public final class JavaManagerRuntimeManagementService implements JavaRuntimeManagementService {
    /// Access boundary for process-wide Java discovery, user settings, and registry mutation.
    private final JavaRuntimeManagementBackend backend;

    /// Creates a production adapter backed by [JavaManager] and [SettingsManager].
    public JavaManagerRuntimeManagementService() {
        this(new ProcessBackend());
    }

    /// Creates a lifecycle service around a supplied state backend.
    ///
    /// @param backend backend used for discovery and mutations
    JavaManagerRuntimeManagementService(JavaRuntimeManagementBackend backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    /// Returns the latest merged runtime, write-access, and disabled-path snapshot.
    ///
    /// @return current immutable runtime-management state
    @Override
    public JavaRuntimeManagementSnapshot snapshot() {
        return mapSnapshot(
                backend.runtimeSnapshots().getValue(),
                backend.isWritable(),
                backend.disabledJavaPathsSnapshot());
    }

    /// Subscribes to both runtime discovery and disabled-path mutations.
    ///
    /// @param listener listener receiving merged snapshot transitions
    /// @return one subscription that removes both underlying registrations
    @Override
    public Subscription subscribe(ValueChangeListener<JavaRuntimeManagementSnapshot> listener) {
        @Nullable JavaRuntimeSnapshot initialRuntimeSnapshot = backend.runtimeSnapshots().getValue();
        boolean initialWritable = backend.isWritable();
        Set<String> initialDisabledPaths = backend.disabledJavaPathsSnapshot();
        SnapshotPublisher publisher = new SnapshotPublisher(
                Objects.requireNonNull(listener, "listener"),
                initialRuntimeSnapshot,
                initialWritable,
                initialDisabledPaths);
        Subscription runtimeSubscription = backend.runtimeSnapshots().subscribe(
                change -> publisher.runtimeChanged(change.currentValue()));
        Subscription disabledSubscription;
        try {
            disabledSubscription = backend.subscribeDisabledJavaPaths(publisher::disabledPathsChanged);
        } catch (RuntimeException failure) {
            runtimeSubscription.unsubscribe();
            throw failure;
        }

        try {
            // Reconcile changes that landed between the initial snapshots and the two source subscriptions. Sequence
            // guards prevent a freshly delivered event from being overwritten by an older reconciliation snapshot.
            long runtimeSequence = publisher.runtimeSequence();
            publisher.reconcileRuntime(runtimeSequence, backend.runtimeSnapshots().getValue());
            long disabledSequence = publisher.disabledSequence();
            publisher.reconcileDisabled(disabledSequence, backend.disabledJavaPathsSnapshot());
            publisher.writableChanged(backend.isWritable());
        } catch (RuntimeException failure) {
            unsubscribeBoth(runtimeSubscription, disabledSubscription);
            throw failure;
        }
        return Subscription.create(() -> unsubscribeBoth(runtimeSubscription, disabledSubscription));
    }

    /// Starts a local rescan through the process-wide manager.
    @Override
    public void refreshLocalRuntimes() {
        backend.refreshLocalRuntimes();
    }

    /// Creates a stopped task that validates and registers a user-selected local Java path.
    ///
    /// @param selectedPath executable or Java home selected by the user
    /// @return stopped local registration task
    @Override
    public Task<JavaRuntime> addLocalRuntime(Path selectedPath) {
        Path candidate = Objects.requireNonNull(selectedPath, "selectedPath");
        return Task.composeAsync("Add local Java runtime", () -> {
            requireWritable();
            return backend.addLocalRuntime(resolveExecutable(candidate));
        });
    }

    /// Creates a stopped task that disables one unmanaged Java runtime.
    ///
    /// @param runtime unmanaged runtime to hide from discovery
    /// @return stopped disable task
    @Override
    public Task<@Nullable Void> disableLocalRuntime(JavaRuntime runtime) {
        JavaRuntime target = Objects.requireNonNull(runtime, "runtime");
        return Task.runAsync("Disable local Java runtime", Schedulers.ui(), () -> {
            requireWritable();
            if (target.isManaged()) {
                throw new IllegalArgumentException("Managed Java runtimes must be uninstalled instead of disabled");
            }
            backend.disableLocalRuntime(target);
        });
    }

    /// Creates a stopped task that uninstalls one launcher-managed Java runtime.
    ///
    /// @param runtime managed runtime to remove
    /// @return stopped uninstall task
    @Override
    public Task<@Nullable Void> uninstallManagedRuntime(JavaRuntime runtime) {
        JavaRuntime target = Objects.requireNonNull(runtime, "runtime");
        return Task.composeAsync("Uninstall managed Java runtime", () -> {
            if (!target.isManaged()) {
                throw new IllegalArgumentException("Only managed Java runtimes can be uninstalled");
            }
            return backend.uninstallManagedRuntime(target);
        });
    }

    /// Creates a stopped background task that probes one selected disabled path.
    ///
    /// @param disabledRuntime disabled entry selected by the user
    /// @return stopped inspection task preserving the original configured path
    @Override
    public Task<DisabledJavaRuntimeEntry> inspectDisabledRuntime(DisabledJavaRuntimeEntry disabledRuntime) {
        DisabledJavaRuntimeEntry target = Objects.requireNonNull(disabledRuntime, "disabledRuntime");
        return Task.supplyAsync("Inspect disabled Java runtime", () -> {
            requireCurrentDisabledEntry(target);
            DisabledJavaRuntimeEntry inspected = backend.inspectDisabledRuntime(target.configuredPath());
            if (!target.configuredPath().equals(inspected.configuredPath())) {
                throw new IllegalStateException("Java runtime inspection changed the configured path");
            }
            return inspected;
        });
    }

    /// Creates a stopped task that restores one inspected available disabled executable.
    ///
    /// The original configured path is removed only in a successful registration continuation. This supplements the
    /// Java manager's canonical-path removal and handles paths containing aliases, symbolic links, or `..` segments.
    ///
    /// @param disabledRuntime disabled executable entry to restore
    /// @return stopped restore task
    @Override
    public Task<JavaRuntime> restoreDisabledRuntime(DisabledJavaRuntimeEntry disabledRuntime) {
        DisabledJavaRuntimeEntry target = Objects.requireNonNull(disabledRuntime, "disabledRuntime");
        return Task.composeAsync("Restore disabled Java runtime", () -> {
            requireWritable();
            requireCurrentDisabledEntry(target);
            @Nullable Path resolvedBinary = target.resolvedBinary();
            if (target.status() != DisabledJavaRuntimeEntry.Status.AVAILABLE || resolvedBinary == null) {
                throw new IllegalArgumentException("Disabled Java runtime must be inspected and available before restore");
            }
            return backend.addLocalRuntime(resolvedBinary).thenApplyAsync(Schedulers.ui(), runtime -> {
                backend.removeDisabledRuntime(target.configuredPath());
                return Objects.requireNonNull(runtime, "Java registration completed without a runtime");
            });
        });
    }

    /// Creates a stopped task that forcibly removes one exact disabled path.
    ///
    /// @param disabledRuntime disabled executable entry to forget
    /// @return stopped removal task
    @Override
    public Task<@Nullable Void> removeDisabledRuntime(DisabledJavaRuntimeEntry disabledRuntime) {
        DisabledJavaRuntimeEntry target = Objects.requireNonNull(disabledRuntime, "disabledRuntime");
        return Task.runAsync("Remove disabled Java runtime", Schedulers.ui(), () -> {
            requireWritable();
            requireCurrentDisabledEntry(target);
            if (!backend.removeDisabledRuntime(target.configuredPath())) {
                throw new IllegalStateException("Disabled Java runtime changed before it could be removed");
            }
        });
    }

    /// Fails the running lifecycle task when user settings cannot be changed.
    private void requireWritable() {
        if (!backend.isWritable()) {
            throw new IllegalStateException("User Java settings are read-only");
        }
    }

    /// Fails a stale task whose configured path is no longer disabled.
    ///
    /// @param entry entry captured by the caller
    private void requireCurrentDisabledEntry(DisabledJavaRuntimeEntry entry) {
        if (!backend.disabledJavaPathsSnapshot().contains(entry.configuredPath())) {
            throw new IllegalStateException("Java runtime is no longer disabled");
        }
    }

    /// Returns a Java executable from either an executable path or a selected Java home directory.
    ///
    /// @param selectedPath user-selected candidate path
    /// @return executable candidate passed to JavaManager validation
    private static Path resolveExecutable(Path selectedPath) {
        Path normalized = selectedPath.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            return normalized;
        }
        Path executable = JavaManager.getExecutable(normalized);
        if (Files.isRegularFile(executable)) {
            return executable;
        }
        Path macExecutable = JavaManager.getMacExecutable(normalized);
        return Files.isRegularFile(macExecutable) ? macExecutable : executable;
    }

    /// Maps cached discovery state and an immutable disabled-path snapshot without touching the filesystem.
    ///
    /// @param runtimeSnapshot process-wide discovery snapshot, or null before first publication
    /// @param writable whether user Java settings may currently be changed
    /// @param disabledPaths immutable or privately owned disabled configured paths
    /// @return non-null merged management snapshot
    private static JavaRuntimeManagementSnapshot mapSnapshot(
            @Nullable JavaRuntimeSnapshot runtimeSnapshot,
            boolean writable,
            Collection<String> disabledPaths) {
        @Unmodifiable List<DisabledJavaRuntimeEntry> disabledRuntimes = disabledPaths.stream()
                .sorted(Comparator.naturalOrder())
                .map(DisabledJavaRuntimeEntry::unchecked)
                .toList();
        if (runtimeSnapshot == null) {
            return new JavaRuntimeManagementSnapshot(
                    false,
                    0L,
                    writable,
                    List.of(),
                    disabledRuntimes);
        }
        return new JavaRuntimeManagementSnapshot(
                runtimeSnapshot.isInitialized(),
                runtimeSnapshot.getRevision(),
                writable,
                runtimeSnapshot.getRuntimes(),
                disabledRuntimes);
    }

    /// Cancels both source subscriptions even when the first cancellation fails.
    ///
    /// @param runtimeSubscription runtime-discovery subscription
    /// @param disabledSubscription disabled-path subscription
    private static void unsubscribeBoth(
            Subscription runtimeSubscription,
            Subscription disabledSubscription) {
        try {
            runtimeSubscription.unsubscribe();
        } finally {
            disabledSubscription.unsubscribe();
        }
    }

    /// Serializes merged delivery for one listener using only immutable source events and private cached state.
    @NotNullByDefault
    private final class SnapshotPublisher {
        /// Listener receiving merged runtime transitions.
        private final ValueChangeListener<JavaRuntimeManagementSnapshot> listener;

        /// Latest runtime discovery snapshot cached from the runtime observable.
        private @Nullable JavaRuntimeSnapshot runtimeSnapshot;

        /// Latest write-access flag captured during subscription setup.
        private boolean writable;

        /// Privately owned disabled paths updated from immutable [SetChange] values.
        private final LinkedHashSet<String> disabledPaths;

        /// Ordered merged changes waiting for listener delivery outside the state monitor.
        private final ArrayDeque<ValueChange<JavaRuntimeManagementSnapshot>> pendingChanges = new ArrayDeque<>();

        /// Whether one source thread currently owns the listener-delivery loop.
        private boolean delivering;

        /// Number of runtime events applied after this publisher was created.
        private long runtimeSequence;

        /// Number of disabled-path events applied after this publisher was created.
        private long disabledSequence;

        /// Last snapshot delivered or captured for this registration.
        private JavaRuntimeManagementSnapshot previousSnapshot;

        /// Creates one per-subscription publisher.
        ///
        /// @param listener target listener
        /// @param runtimeSnapshot runtime state captured before source subscriptions are attached
        /// @param writable initial write-access state
        /// @param disabledPaths immutable disabled paths captured before source subscriptions are attached
        private SnapshotPublisher(
                ValueChangeListener<JavaRuntimeManagementSnapshot> listener,
                @Nullable JavaRuntimeSnapshot runtimeSnapshot,
                boolean writable,
                Set<String> disabledPaths) {
            this.listener = listener;
            this.runtimeSnapshot = runtimeSnapshot;
            this.writable = writable;
            this.disabledPaths = new LinkedHashSet<>(disabledPaths);
            this.previousSnapshot = mapSnapshot(runtimeSnapshot, writable, this.disabledPaths);
        }

        /// Returns the current runtime event sequence for race-safe reconciliation.
        private synchronized long runtimeSequence() {
            return runtimeSequence;
        }

        /// Returns the current disabled-path event sequence for race-safe reconciliation.
        private synchronized long disabledSequence() {
            return disabledSequence;
        }

        /// Applies one immutable runtime transition without reading the backend disabled collection.
        ///
        /// @param currentSnapshot latest runtime discovery value, or null
        private void runtimeChanged(@Nullable JavaRuntimeSnapshot currentSnapshot) {
            boolean shouldDrain;
            synchronized (this) {
                runtimeSnapshot = currentSnapshot;
                runtimeSequence++;
                shouldDrain = queueSnapshotChangeLocked();
            }
            if (shouldDrain) {
                drainChanges();
            }
        }

        /// Applies exact additions and removals to the private disabled-path cache.
        ///
        /// @param change immutable set mutation
        private void disabledPathsChanged(SetChange<String> change) {
            boolean shouldDrain;
            synchronized (this) {
                disabledPaths.removeAll(change.removedElements());
                disabledPaths.addAll(change.addedElements());
                disabledSequence++;
                shouldDrain = queueSnapshotChangeLocked();
            }
            if (shouldDrain) {
                drainChanges();
            }
        }

        /// Reconciles a runtime value only when no newer runtime event arrived during the read.
        ///
        /// @param expectedSequence sequence captured before reading the runtime observable
        /// @param currentSnapshot latest runtime observable value, or null
        private void reconcileRuntime(
                long expectedSequence,
                @Nullable JavaRuntimeSnapshot currentSnapshot) {
            boolean shouldDrain;
            synchronized (this) {
                if (runtimeSequence != expectedSequence) {
                    return;
                }
                runtimeSnapshot = currentSnapshot;
                shouldDrain = queueSnapshotChangeLocked();
            }
            if (shouldDrain) {
                drainChanges();
            }
        }

        /// Reconciles immutable disabled paths only when no newer set event arrived during the snapshot read.
        ///
        /// @param expectedSequence sequence captured before reading the immutable backend snapshot
        /// @param currentPaths latest immutable disabled path snapshot
        private void reconcileDisabled(long expectedSequence, Set<String> currentPaths) {
            boolean shouldDrain;
            synchronized (this) {
                if (disabledSequence != expectedSequence) {
                    return;
                }
                disabledPaths.clear();
                disabledPaths.addAll(currentPaths);
                shouldDrain = queueSnapshotChangeLocked();
            }
            if (shouldDrain) {
                drainChanges();
            }
        }

        /// Updates cached write access during subscription reconciliation.
        ///
        /// @param currentWritable current user-settings write access
        private void writableChanged(boolean currentWritable) {
            boolean shouldDrain;
            synchronized (this) {
                writable = currentWritable;
                shouldDrain = queueSnapshotChangeLocked();
            }
            if (shouldDrain) {
                drainChanges();
            }
        }

        /// Queues the merged value when it changed and elects one source thread to drain listener notifications.
        ///
        /// @return whether the calling source thread must start the delivery loop after releasing the state monitor
        private boolean queueSnapshotChangeLocked() {
            JavaRuntimeManagementSnapshot currentSnapshot = mapSnapshot(
                    runtimeSnapshot,
                    writable,
                    disabledPaths);
            if (previousSnapshot.equals(currentSnapshot)) {
                return false;
            }
            JavaRuntimeManagementSnapshot oldSnapshot = previousSnapshot;
            previousSnapshot = currentSnapshot;
            pendingChanges.addLast(new ValueChange<>(
                    JavaManagerRuntimeManagementService.this,
                    oldSnapshot,
                    currentSnapshot));
            if (delivering) {
                return false;
            }
            delivering = true;
            return true;
        }

        /// Delivers queued changes in state-update order without holding the publisher monitor.
        private void drainChanges() {
            while (true) {
                ValueChange<JavaRuntimeManagementSnapshot> change;
                synchronized (this) {
                    change = pendingChanges.pollFirst();
                    if (change == null) {
                        delivering = false;
                        return;
                    }
                }
                try {
                    listener.onChange(change);
                } catch (RuntimeException | Error failure) {
                    synchronized (this) {
                        pendingChanges.clear();
                        delivering = false;
                    }
                    throw failure;
                }
            }
        }
    }

    /// Production backend that delegates to the launcher's process-wide Java and settings managers.
    @NotNullByDefault
    private static final class ProcessBackend implements JavaRuntimeManagementBackend {
        /// Returns the Java manager's observable discovery state.
        @Override
        public ObservableValue<JavaRuntimeSnapshot> runtimeSnapshots() {
            return JavaManager.getAllJavaSnapshotObservable();
        }

        /// Copies disabled Java paths on the EDT so callers never iterate the live observable set.
        @Override
        public @Unmodifiable Set<String> disabledJavaPathsSnapshot() {
            return onEventDispatchThread(() -> Set.copyOf(
                    SettingsManager.userSettings().getDisabledJava()));
        }

        /// Registers a disabled-path listener on the EDT and exposes only immutable [SetChange] values.
        ///
        /// @param listener listener receiving exact disabled-path changes
        /// @return independently removable disabled-path subscription
        @Override
        public Subscription subscribeDisabledJavaPaths(
                CollectionChangeListener<SetChange<String>> listener) {
            CollectionChangeListener<SetChange<String>> target = Objects.requireNonNull(listener, "listener");
            return onEventDispatchThread(() ->
                    SettingsManager.userSettings().getDisabledJava().subscribe(target));
        }

        /// Returns whether the user settings file is writable.
        @Override
        public boolean isWritable() {
            return !SettingsManager.isUserSettingsReadOnly();
        }

        /// Starts a local Java discovery refresh.
        @Override
        public void refreshLocalRuntimes() {
            JavaManager.refresh();
        }

        /// Returns the existing stopped Java-manager registration task.
        ///
        /// @param binary executable candidate
        /// @return stopped registration task
        @Override
        public Task<JavaRuntime> addLocalRuntime(Path binary) {
            return JavaManager.getAddJavaTask(binary);
        }

        /// Probes one configured path as a real Java executable on the calling background task thread.
        ///
        /// @param configuredPath original disabled path text
        /// @return available or invalid inspection result
        /// @throws InterruptedException if Java discovery initialization is interrupted
        @Override
        public DisabledJavaRuntimeEntry inspectDisabledRuntime(String configuredPath) throws InterruptedException {
            String originalPath = Objects.requireNonNull(configuredPath, "configuredPath");
            try {
                JavaRuntime runtime = JavaManager.getJava(Path.of(originalPath));
                return DisabledJavaRuntimeEntry.available(originalPath, runtime.getBinary());
            } catch (InvalidPathException | IOException | SecurityException ignored) {
                return DisabledJavaRuntimeEntry.invalid(originalPath);
            }
        }

        /// Writes the disabled path, removes a matching explicit user path, and removes the live runtime.
        ///
        /// @param runtime unmanaged runtime to disable
        /// @throws InterruptedException if registry initialization is interrupted
        @Override
        public void disableLocalRuntime(JavaRuntime runtime) throws InterruptedException {
            UserSettings userSettings = SettingsManager.userSettings();
            String configuredPath = runtime.getBinary().toString();
            userSettings.getDisabledJava().add(configuredPath);
            userSettings.getUserJava().remove(configuredPath);
            JavaManager.removeJava(runtime);
        }

        /// Returns the existing stopped Java-manager uninstall task.
        ///
        /// @param runtime managed runtime to uninstall
        /// @return stopped uninstall task
        @Override
        public Task<@Nullable Void> uninstallManagedRuntime(JavaRuntime runtime) {
            return JavaManager.getUninstallJavaTask(runtime);
        }

        /// Removes an exact disabled path from user settings.
        ///
        /// @param configuredPath original disabled path text
        /// @return whether the disabled set changed
        @Override
        public boolean removeDisabledRuntime(String configuredPath) {
            return SettingsManager.userSettings().getDisabledJava().remove(configuredPath);
        }

        /// Runs a non-null value operation on the Swing event-dispatch thread and returns its result.
        ///
        /// @param operation non-null value operation confined to the EDT
        /// @param <T> result type
        /// @return operation result
        private static <T extends Object> T onEventDispatchThread(Supplier<T> operation) {
            Supplier<T> target = Objects.requireNonNull(operation, "operation");
            AtomicReference<@Nullable T> result = new AtomicReference<>();
            EdtDispatcher.executeAndWait(() -> result.set(target.get()));
            return Objects.requireNonNull(result.get(), "EDT operation returned null");
        }
    }
}
