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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.java.JavaInfo;
import space.minecraftstl.xyml.java.JavaRuntime;
import space.minecraftstl.xyml.java.JavaRuntimeSnapshot;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.collection.CollectionChangeListener;
import space.minecraftstl.xyml.observable.collection.ObservableCollections;
import space.minecraftstl.xyml.observable.collection.ObservableSet;
import space.minecraftstl.xyml.observable.collection.SetChange;
import space.minecraftstl.xyml.observable.property.ObservableValue;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.platform.Platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies Java-runtime lifecycle policy independently from process-wide settings initialization.
@NotNullByDefault
final class JavaManagerRuntimeManagementServiceTest {
    /// Direct executor used by deterministic fake backend tasks.
    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    /// Per-test filesystem root used to distinguish valid and invalid disabled paths.
    @TempDir
    private @Nullable Path temporaryDirectory;

    /// Leaves initial disabled entries unchecked and performs no filesystem or Java inspection while taking a snapshot.
    @Test
    void snapshotLeavesDisabledPathsUncheckedWithoutInspection() throws IOException {
        Path executable = Files.createFile(temporaryDirectory().resolve("unchecked-java.exe"));
        FakeBackend backend = new FakeBackend(true);
        backend.disabledJavaPaths.add(executable.toString());
        JavaManagerRuntimeManagementService service = new JavaManagerRuntimeManagementService(backend);

        JavaRuntimeManagementSnapshot snapshot = service.snapshot();

        DisabledJavaRuntimeEntry entry = snapshot.disabledRuntimes().get(0);
        assertAll(
                () -> assertEquals(executable.toString(), entry.configuredPath()),
                () -> assertEquals(DisabledJavaRuntimeEntry.Status.UNCHECKED, entry.status()),
                () -> assertNull(entry.resolvedBinary()),
                () -> assertEquals(0, backend.inspectionRequests.get()));
    }

    /// Inspects only the explicitly selected entry after the stopped task starts.
    @Test
    void inspectsSelectedDisabledRuntimeLazily() throws IOException {
        Path executable = Files.createFile(temporaryDirectory().resolve("inspect-java.exe"));
        String configuredPath = executable.toString();
        FakeBackend backend = new FakeBackend(true);
        backend.disabledJavaPaths.add(configuredPath);
        backend.inspectionResult = DisabledJavaRuntimeEntry.available(
                configuredPath,
                executable.toRealPath());
        JavaManagerRuntimeManagementService service = new JavaManagerRuntimeManagementService(backend);
        DisabledJavaRuntimeEntry unchecked = service.snapshot().disabledRuntimes().get(0);

        Task<DisabledJavaRuntimeEntry> inspectionTask = service.inspectDisabledRuntime(unchecked);

        assertAll(
                () -> assertEquals(Task.TaskState.READY, inspectionTask.getState()),
                () -> assertEquals(0, backend.inspectionRequests.get()));
        assertTrue(inspectionTask.test());
        DisabledJavaRuntimeEntry inspected = Objects.requireNonNull(
                inspectionTask.getResult(),
                "inspection result");
        assertAll(
                () -> assertEquals(1, backend.inspectionRequests.get()),
                () -> assertEquals(DisabledJavaRuntimeEntry.Status.AVAILABLE, inspected.status()),
                () -> assertEquals(executable.toRealPath(), inspected.resolvedBinary()));
    }

    /// Rejects write-dependent tasks at execution time without constructing backend operations or changing settings.
    @Test
    void rejectsReadOnlyAddRestoreAndRemovalWithoutEarlyMutation() throws IOException {
        Path executable = Files.createFile(temporaryDirectory().resolve("java.exe"));
        String missingPath = temporaryDirectory().resolve("missing-java.exe").toString();
        FakeBackend backend = new FakeBackend(false);
        backend.disabledJavaPaths.add(executable.toString());
        backend.disabledJavaPaths.add(missingPath);
        JavaManagerRuntimeManagementService service = new JavaManagerRuntimeManagementService(backend);
        JavaRuntimeManagementSnapshot snapshot = service.snapshot();
        DisabledJavaRuntimeEntry validEntry = DisabledJavaRuntimeEntry.available(
                executable.toString(),
                executable.toRealPath());
        DisabledJavaRuntimeEntry removableEntry = DisabledJavaRuntimeEntry.unchecked(missingPath);

        Task<JavaRuntime> addTask = service.addLocalRuntime(executable);
        Task<JavaRuntime> restoreTask = service.restoreDisabledRuntime(validEntry);
        Task<@Nullable Void> removeTask = service.removeDisabledRuntime(removableEntry);

        assertAll(
                () -> assertFalse(snapshot.writable()),
                () -> assertEquals(Task.TaskState.READY, addTask.getState()),
                () -> assertEquals(Task.TaskState.READY, restoreTask.getState()),
                () -> assertEquals(Task.TaskState.READY, removeTask.getState()),
                () -> assertEquals(0, backend.addTaskRequests.get()),
                () -> assertTrue(snapshot.disabledRuntimes().stream().allMatch(entry ->
                        entry.status() == DisabledJavaRuntimeEntry.Status.UNCHECKED)),
                () -> assertEquals(List.of(executable.toString(), missingPath),
                        List.copyOf(backend.disabledJavaPaths)));

        assertAll(
                () -> assertFalse(addTask.test()),
                () -> assertFalse(restoreTask.test()),
                () -> assertFalse(removeTask.test()),
                () -> assertInstanceOf(IllegalStateException.class, addTask.getException()),
                () -> assertInstanceOf(IllegalStateException.class, restoreTask.getException()),
                () -> assertInstanceOf(IllegalStateException.class, removeTask.getException()),
                () -> assertEquals(0, backend.addTaskRequests.get()),
                () -> assertEquals(List.of(executable.toString(), missingPath),
                        List.copyOf(backend.disabledJavaPaths)));
    }

    /// Disables an unmanaged runtime only when the returned task runs and rejects the managed-runtime action.
    @Test
    void disablesOnlyUnmanagedRuntimeWhenTaskRuns() {
        FakeBackend backend = new FakeBackend(true);
        JavaManagerRuntimeManagementService service = new JavaManagerRuntimeManagementService(backend);
        JavaRuntime unmanaged = runtime(Path.of("C:/test/java/bin/java.exe"), false);
        JavaRuntime managed = runtime(Path.of("C:/test/managed/bin/java.exe"), true);
        backend.userJavaPaths.add(unmanaged.getBinary().toString());

        Task<@Nullable Void> disableTask = service.disableLocalRuntime(unmanaged);

        assertAll(
                () -> assertEquals(Task.TaskState.READY, disableTask.getState()),
                () -> assertTrue(backend.disabledJavaPaths.isEmpty()),
                () -> assertTrue(backend.userJavaPaths.contains(unmanaged.getBinary().toString())),
                () -> assertTrue(backend.removedRuntimes.isEmpty()));

        assertTrue(disableTask.test());
        assertAll(
                () -> assertTrue(backend.disabledJavaPaths.contains(unmanaged.getBinary().toString())),
                () -> assertFalse(backend.userJavaPaths.contains(unmanaged.getBinary().toString())),
                () -> assertEquals(List.of(unmanaged), backend.removedRuntimes));

        Task<@Nullable Void> managedDisable = service.disableLocalRuntime(managed);
        assertFalse(managedDisable.test());
        assertAll(
                () -> assertInstanceOf(IllegalArgumentException.class, managedDisable.getException()),
                () -> assertEquals(List.of(unmanaged), backend.removedRuntimes),
                () -> assertFalse(backend.disabledJavaPaths.contains(managed.getBinary().toString())));
    }

    /// Delegates managed uninstallation lazily and rejects unmanaged runtimes before asking the backend for a task.
    @Test
    void uninstallsOnlyManagedRuntimes() {
        FakeBackend backend = new FakeBackend(true);
        JavaManagerRuntimeManagementService service = new JavaManagerRuntimeManagementService(backend);
        JavaRuntime managed = runtime(Path.of("C:/test/managed/bin/java.exe"), true);
        JavaRuntime unmanaged = runtime(Path.of("C:/test/local/bin/java.exe"), false);

        Task<@Nullable Void> managedTask = service.uninstallManagedRuntime(managed);
        assertAll(
                () -> assertEquals(Task.TaskState.READY, managedTask.getState()),
                () -> assertEquals(0, backend.uninstallTaskRequests.get()),
                () -> assertTrue(backend.uninstalledRuntimes.isEmpty()));

        assertTrue(managedTask.test());
        assertAll(
                () -> assertEquals(1, backend.uninstallTaskRequests.get()),
                () -> assertEquals(List.of(managed), backend.uninstalledRuntimes));

        Task<@Nullable Void> unmanagedTask = service.uninstallManagedRuntime(unmanaged);
        assertFalse(unmanagedTask.test());
        assertAll(
                () -> assertInstanceOf(IllegalArgumentException.class, unmanagedTask.getException()),
                () -> assertEquals(1, backend.uninstallTaskRequests.get()),
                () -> assertEquals(List.of(managed), backend.uninstalledRuntimes));
    }

    /// Leaves the disabled record untouched when executable validation fails during restoration.
    @Test
    void failedRestoreRetainsDisabledRecord() throws IOException {
        Path executable = Files.createFile(temporaryDirectory().resolve("restore-java.exe"));
        FakeBackend backend = new FakeBackend(true);
        backend.disabledJavaPaths.add(executable.toString());
        backend.addFailure = new IOException("validation failed");
        JavaManagerRuntimeManagementService service = new JavaManagerRuntimeManagementService(backend);
        DisabledJavaRuntimeEntry entry = DisabledJavaRuntimeEntry.available(
                executable.toString(),
                executable.toRealPath());

        Task<JavaRuntime> restoreTask = service.restoreDisabledRuntime(entry);

        assertAll(
                () -> assertEquals(Task.TaskState.READY, restoreTask.getState()),
                () -> assertEquals(0, backend.addTaskRequests.get()),
                () -> assertTrue(backend.disabledJavaPaths.contains(executable.toString())));

        assertFalse(restoreTask.test());
        assertAll(
                () -> assertInstanceOf(IOException.class, restoreTask.getException()),
                () -> assertEquals(1, backend.addTaskRequests.get()),
                () -> assertEquals(1, backend.addTaskExecutions.get()),
                () -> assertTrue(backend.disabledJavaPaths.contains(executable.toString())));

        Task<@Nullable Void> forcedRemoval = service.removeDisabledRuntime(entry);
        assertTrue(forcedRemoval.test());
        assertFalse(backend.disabledJavaPaths.contains(executable.toString()));
    }

    /// Forcibly removes the exact selected record even when its configured file still exists.
    @Test
    void forceRemovesExistingDisabledEntry() throws IOException {
        Path executable = Files.createFile(temporaryDirectory().resolve("existing-java.exe"));
        String configuredPath = executable.toString();
        FakeBackend backend = new FakeBackend(true);
        backend.disabledJavaPaths.add(configuredPath);
        JavaManagerRuntimeManagementService service = new JavaManagerRuntimeManagementService(backend);
        DisabledJavaRuntimeEntry uncheckedEntry = service.snapshot().disabledRuntimes().get(0);

        Task<@Nullable Void> removalTask = service.removeDisabledRuntime(uncheckedEntry);
        assertAll(
                () -> assertEquals(Task.TaskState.READY, removalTask.getState()),
                () -> assertTrue(Files.exists(executable)),
                () -> assertTrue(backend.disabledJavaPaths.contains(configuredPath)));
        assertTrue(removalTask.test());
        assertAll(
                () -> assertTrue(Files.exists(executable)),
                () -> assertTrue(backend.disabledJavaPaths.isEmpty()),
                () -> assertEquals(List.of(configuredPath), backend.removedDisabledPaths));
    }

    /// Removes the original non-canonical configured string only after canonical runtime registration succeeds.
    @Test
    void successfulRestoreRemovesOriginalNonCanonicalPath() throws IOException {
        Path runtimeDirectory = Files.createDirectories(temporaryDirectory().resolve("runtime"));
        Files.createDirectory(runtimeDirectory.resolve("alias"));
        Path executable = Files.createFile(runtimeDirectory.resolve("java.exe"));
        String configuredPath = runtimeDirectory.resolve("alias/../java.exe").toString();
        Path resolvedBinary = executable.toRealPath();
        FakeBackend backend = new FakeBackend(true);
        backend.disabledJavaPaths.add(configuredPath);
        JavaManagerRuntimeManagementService service = new JavaManagerRuntimeManagementService(backend);
        DisabledJavaRuntimeEntry available = DisabledJavaRuntimeEntry.available(
                configuredPath,
                resolvedBinary);

        Task<JavaRuntime> restoreTask = service.restoreDisabledRuntime(available);

        assertAll(
                () -> assertEquals(Task.TaskState.READY, restoreTask.getState()),
                () -> assertTrue(backend.disabledJavaPaths.contains(configuredPath)),
                () -> assertFalse(configuredPath.equals(resolvedBinary.toString())));
        assertTrue(restoreTask.test());
        assertAll(
                () -> assertFalse(backend.disabledJavaPaths.contains(configuredPath)),
                () -> assertTrue(backend.userJavaPaths.contains(resolvedBinary.toString())),
                () -> assertEquals(List.of(configuredPath), backend.removedDisabledPaths));
    }

    /// Publishes disabled-set changes as merged snapshots and releases both source subscriptions on close.
    @Test
    void subscribesToDisabledSetChangesAndStopsAfterUnsubscribe() {
        FakeBackend backend = new FakeBackend(true);
        JavaManagerRuntimeManagementService service = new JavaManagerRuntimeManagementService(backend);
        List<ValueChange<JavaRuntimeManagementSnapshot>> changes = new ArrayList<>();
        Subscription subscription = service.subscribe(changes::add);
        String configuredPath = temporaryDirectory().resolve("missing-java.exe").toString();

        backend.disabledJavaPaths.add(configuredPath);

        assertEquals(1, changes.size());
        ValueChange<JavaRuntimeManagementSnapshot> change = changes.get(0);
        JavaRuntimeManagementSnapshot previous = Objects.requireNonNull(change.previousValue(), "previous snapshot");
        JavaRuntimeManagementSnapshot current = Objects.requireNonNull(change.currentValue(), "current snapshot");
        assertAll(
                () -> assertTrue(previous.disabledRuntimes().isEmpty()),
                () -> assertEquals(1, current.disabledRuntimes().size()),
                () -> assertEquals(configuredPath, current.disabledRuntimes().get(0).configuredPath()),
                () -> assertEquals(
                        DisabledJavaRuntimeEntry.Status.UNCHECKED,
                        current.disabledRuntimes().get(0).status()),
                () -> assertNull(current.disabledRuntimes().get(0).resolvedBinary()),
                subscription::isSubscribed);

        subscription.unsubscribe();
        backend.disabledJavaPaths.add(temporaryDirectory().resolve("another-java.exe").toString());
        assertAll(
                () -> assertFalse(subscription.isSubscribed()),
                () -> assertEquals(1, changes.size()));
    }

    /// Applies a worker-published set change from the publisher's private cache without rereading the backend set.
    @Test
    void workerSetChangeDoesNotIterateLiveBackendCollection() throws InterruptedException {
        FakeBackend backend = new FakeBackend(true);
        JavaManagerRuntimeManagementService service = new JavaManagerRuntimeManagementService(backend);
        List<ValueChange<JavaRuntimeManagementSnapshot>> changes = new ArrayList<>();
        Subscription subscription = service.subscribe(changes::add);
        int snapshotsAfterSubscription = backend.disabledSnapshotReads.get();
        backend.rejectDisabledSnapshotReads = true;
        String configuredPath = temporaryDirectory().resolve("worker-java.exe").toString();
        AtomicReference<@Nullable Throwable> publicationFailure = new AtomicReference<>();

        Thread publisher = new Thread(() -> {
            try {
                backend.disabledJavaPaths.add(configuredPath);
            } catch (Throwable failure) {
                publicationFailure.set(failure);
            }
        }, "disabled-java-set-publisher");
        publisher.start();
        publisher.join();

        assertAll(
                () -> assertNull(publicationFailure.get()),
                () -> assertEquals(snapshotsAfterSubscription, backend.disabledSnapshotReads.get()),
                () -> assertEquals(1, changes.size()),
                () -> assertEquals(
                        configuredPath,
                        Objects.requireNonNull(changes.get(0).currentValue(), "current snapshot")
                                .disabledRuntimes().get(0).configuredPath()));
        subscription.unsubscribe();
    }

    /// Handles a worker runtime publication without rereading or iterating the disabled backend collection.
    @Test
    void workerRuntimePublicationDoesNotReadDisabledBackendCollection() throws InterruptedException {
        FakeBackend backend = new FakeBackend(true);
        backend.disabledJavaPaths.add(temporaryDirectory().resolve("cached-java.exe").toString());
        JavaManagerRuntimeManagementService service = new JavaManagerRuntimeManagementService(backend);
        List<ValueChange<JavaRuntimeManagementSnapshot>> changes = new ArrayList<>();
        Subscription subscription = service.subscribe(changes::add);
        int snapshotsAfterSubscription = backend.disabledSnapshotReads.get();
        backend.rejectDisabledSnapshotReads = true;
        AtomicReference<@Nullable Throwable> publicationFailure = new AtomicReference<>();

        Thread publisher = new Thread(() -> {
            try {
                backend.runtimeSnapshots.publishCurrent();
            } catch (Throwable failure) {
                publicationFailure.set(failure);
            }
        }, "java-runtime-snapshot-publisher");
        publisher.start();
        publisher.join();

        assertAll(
                () -> assertNull(publicationFailure.get()),
                () -> assertEquals(snapshotsAfterSubscription, backend.disabledSnapshotReads.get()),
                () -> assertTrue(changes.isEmpty()));
        subscription.unsubscribe();
    }

    /// Delivers listeners outside the publisher monitor so a listener-side snapshot wait cannot block a second event.
    @Test
    void listenerSnapshotWaitDoesNotDeadlockConcurrentDisabledEvent() throws InterruptedException {
        FakeBackend backend = new FakeBackend(true);
        JavaManagerRuntimeManagementService service = new JavaManagerRuntimeManagementService(backend);
        AtomicInteger deliveredChanges = new AtomicInteger();
        CountDownLatch listenerSnapshotStarted = new CountDownLatch(1);
        CountDownLatch concurrentEventReturned = new CountDownLatch(1);
        AtomicReference<@Nullable Throwable> firstPublicationFailure = new AtomicReference<>();
        AtomicReference<@Nullable Throwable> secondPublicationFailure = new AtomicReference<>();
        Subscription subscription = service.subscribe(change -> {
            if (deliveredChanges.getAndIncrement() == 0) {
                service.snapshot();
            }
        });
        backend.disabledSnapshotHook = () -> {
            listenerSnapshotStarted.countDown();
            try {
                if (!concurrentEventReturned.await(2, TimeUnit.SECONDS)) {
                    throw new AssertionError("Concurrent disabled event could not return while the listener waited");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for the concurrent disabled event", interrupted);
            }
        };
        String firstPath = temporaryDirectory().resolve("first-disabled-java.exe").toString();
        String secondPath = temporaryDirectory().resolve("second-disabled-java.exe").toString();

        Thread secondPublisher = new Thread(() -> {
            try {
                if (!listenerSnapshotStarted.await(2, TimeUnit.SECONDS)) {
                    throw new AssertionError("Listener did not start its synchronous snapshot read");
                }
                backend.disabledJavaPaths.add(secondPath);
            } catch (Throwable failure) {
                secondPublicationFailure.set(failure);
            } finally {
                concurrentEventReturned.countDown();
            }
        }, "concurrent-disabled-java-publisher");
        Thread firstPublisher = new Thread(() -> {
            try {
                backend.disabledJavaPaths.add(firstPath);
            } catch (Throwable failure) {
                firstPublicationFailure.set(failure);
            }
        }, "listener-snapshot-java-publisher");
        secondPublisher.setDaemon(true);
        firstPublisher.setDaemon(true);
        secondPublisher.start();
        firstPublisher.start();
        firstPublisher.join(3_000L);
        secondPublisher.join(3_000L);

        assertAll(
                () -> assertFalse(firstPublisher.isAlive()),
                () -> assertFalse(secondPublisher.isAlive()),
                () -> assertNull(firstPublicationFailure.get()),
                () -> assertNull(secondPublicationFailure.get()),
                () -> assertEquals(2, deliveredChanges.get()));
        subscription.unsubscribe();
    }

    /// Creates one deterministic Java runtime fixture.
    ///
    /// @param binary executable path
    /// @param managed whether the launcher owns the installation
    /// @return runtime fixture
    private static JavaRuntime runtime(Path binary, boolean managed) {
        return new JavaRuntime(
                binary,
                new JavaInfo(Platform.WINDOWS_X86_64, "17.0.12", "Test Vendor"),
                managed,
                true);
    }

    /// Returns the JUnit-injected temporary directory after the extension initialized the test instance.
    ///
    /// @return non-null temporary directory for the running test
    private Path temporaryDirectory() {
        return Objects.requireNonNull(temporaryDirectory, "temporaryDirectory");
    }

    /// In-memory backend that records task construction, execution, and settings mutations.
    @NotNullByDefault
    private static final class FakeBackend implements JavaRuntimeManagementBackend {
        /// Manually publishable nullable runtime source for cached-state concurrency tests.
        private final ManualRuntimeObservable runtimeSnapshots = new ManualRuntimeObservable();

        /// Live disabled path set exposed to the service.
        private final ObservableSet<String> disabledJavaPaths = ObservableCollections.observableSet();

        /// Explicit user-added paths changed by disable and successful add operations.
        private final ObservableSet<String> userJavaPaths = ObservableCollections.observableSet();

        /// Runtime values removed from the fake live registry.
        private final List<JavaRuntime> removedRuntimes = new ArrayList<>();

        /// Runtime values whose managed uninstall tasks completed.
        private final List<JavaRuntime> uninstalledRuntimes = new ArrayList<>();

        /// Exact configured path strings passed to forced disabled-record removal.
        private final List<String> removedDisabledPaths = new ArrayList<>();

        /// Number of local add tasks requested from the backend.
        private final AtomicInteger addTaskRequests = new AtomicInteger();

        /// Number of local add task bodies executed.
        private final AtomicInteger addTaskExecutions = new AtomicInteger();

        /// Number of managed uninstall tasks requested from the backend.
        private final AtomicInteger uninstallTaskRequests = new AtomicInteger();

        /// Number of immutable disabled-path snapshots requested from the backend.
        private final AtomicInteger disabledSnapshotReads = new AtomicInteger();

        /// Number of selected-path inspections requested from the backend.
        private final AtomicInteger inspectionRequests = new AtomicInteger();

        /// Whether user Java settings are writable.
        private final boolean writable;

        /// Optional executable-validation failure raised by the next fake add task.
        private @Nullable IOException addFailure;

        /// Optional configured inspection result returned by the fake backend.
        private @Nullable DisabledJavaRuntimeEntry inspectionResult;

        /// Whether a backend snapshot read should fail to detect forbidden live-set rereads during publication.
        private volatile boolean rejectDisabledSnapshotReads;

        /// Optional synchronization hook run before copying the fake disabled set.
        private volatile @Nullable Runnable disabledSnapshotHook;

        /// Creates an in-memory backend with fixed write access.
        ///
        /// @param writable whether settings mutations are allowed
        private FakeBackend(boolean writable) {
            this.writable = writable;
        }

        /// Returns the nullable fake runtime snapshot property.
        @Override
        public ObservableValue<JavaRuntimeSnapshot> runtimeSnapshots() {
            return runtimeSnapshots;
        }

        /// Returns an immutable disabled-path copy or fails when a publication must use its private cache.
        @Override
        public @Unmodifiable Set<String> disabledJavaPathsSnapshot() {
            disabledSnapshotReads.incrementAndGet();
            if (rejectDisabledSnapshotReads) {
                throw new IllegalStateException("Disabled paths were reread during cached publication");
            }
            @Nullable Runnable snapshotHook = disabledSnapshotHook;
            if (snapshotHook != null) {
                snapshotHook.run();
            }
            return Set.copyOf(disabledJavaPaths);
        }

        /// Subscribes directly to immutable fake set changes.
        ///
        /// @param listener listener receiving fake disabled-path changes
        /// @return independently removable fake subscription
        @Override
        public Subscription subscribeDisabledJavaPaths(
                CollectionChangeListener<SetChange<String>> listener) {
            return disabledJavaPaths.subscribe(listener);
        }

        /// Returns fixed fake write access.
        @Override
        public boolean isWritable() {
            return writable;
        }

        /// Performs no discovery because runtime scanning is outside these lifecycle tests.
        @Override
        public void refreshLocalRuntimes() {
        }

        /// Creates a stopped fake add task and records construction only when the service task executes.
        ///
        /// @param binary executable passed through the service
        /// @return stopped fake validation and registration task
        @Override
        public Task<JavaRuntime> addLocalRuntime(Path binary) {
            addTaskRequests.incrementAndGet();
            return Task.supplyAsync("Fake Java registration", DIRECT_EXECUTOR, () -> {
                addTaskExecutions.incrementAndGet();
                @Nullable IOException failure = addFailure;
                if (failure != null) {
                    throw failure;
                }
                disabledJavaPaths.remove(binary.toString());
                userJavaPaths.add(binary.toString());
                return runtime(binary, false);
            });
        }

        /// Returns the configured selected-path inspection result without touching the filesystem.
        ///
        /// @param configuredPath original disabled path text
        /// @return configured inspection result, or an invalid entry by default
        @Override
        public DisabledJavaRuntimeEntry inspectDisabledRuntime(String configuredPath) {
            inspectionRequests.incrementAndGet();
            @Nullable DisabledJavaRuntimeEntry result = inspectionResult;
            return result == null ? DisabledJavaRuntimeEntry.invalid(configuredPath) : result;
        }

        /// Applies the fake disabled, user-path, and registry mutations.
        ///
        /// @param runtime unmanaged runtime to disable
        @Override
        public void disableLocalRuntime(JavaRuntime runtime) {
            String configuredPath = runtime.getBinary().toString();
            disabledJavaPaths.add(configuredPath);
            userJavaPaths.remove(configuredPath);
            removedRuntimes.add(runtime);
        }

        /// Creates a stopped fake uninstall task.
        ///
        /// @param runtime managed runtime to uninstall
        /// @return stopped fake uninstall task
        @Override
        public Task<@Nullable Void> uninstallManagedRuntime(JavaRuntime runtime) {
            uninstallTaskRequests.incrementAndGet();
            return Task.runAsync("Fake managed Java uninstall", DIRECT_EXECUTOR,
                    () -> uninstalledRuntimes.add(runtime));
        }

        /// Removes an exact path from the fake disabled set.
        ///
        /// @param configuredPath disabled path text
        /// @return whether the fake set changed
        @Override
        public boolean removeDisabledRuntime(String configuredPath) {
            removedDisabledPaths.add(configuredPath);
            return disabledJavaPaths.remove(configuredPath);
        }
    }

    /// Minimal runtime observable that can publish an unchanged nullable value to exercise subscriber behavior.
    @NotNullByDefault
    private static final class ManualRuntimeObservable implements ObservableValue<JavaRuntimeSnapshot> {
        /// Thread-safe runtime listeners registered by the service.
        private final CopyOnWriteArrayList<ValueChangeListener<JavaRuntimeSnapshot>> listeners =
                new CopyOnWriteArrayList<>();

        /// Current nullable runtime snapshot.
        private @Nullable JavaRuntimeSnapshot value;

        /// Returns the current nullable runtime snapshot.
        @Override
        public @Nullable JavaRuntimeSnapshot getValue() {
            return value;
        }

        /// Registers one runtime listener.
        ///
        /// @param listener listener receiving manual publications
        /// @return independently removable listener subscription
        @Override
        public Subscription subscribe(ValueChangeListener<JavaRuntimeSnapshot> listener) {
            ValueChangeListener<JavaRuntimeSnapshot> target = Objects.requireNonNull(listener, "listener");
            listeners.add(target);
            return Subscription.create(() -> listeners.remove(target));
        }

        /// Publishes the current value even when it remains null, simulating a background registry event.
        private void publishCurrent() {
            ValueChange<JavaRuntimeSnapshot> change = new ValueChange<>(this, value, value);
            for (ValueChangeListener<JavaRuntimeSnapshot> listener : listeners) {
                listener.onChange(change);
            }
        }
    }
}
