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
package space.minecraftstl.xyml.ui.swing.page.instances.management.addonupdates;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.addon.LocalAddonFile;
import space.minecraftstl.xyml.addon.LocalAddonManager;
import space.minecraftstl.xyml.addon.RemoteAddon;
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.download.VersionList;
import space.minecraftstl.xyml.task.FileDownloadTask;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskExecutor;
import space.minecraftstl.xyml.task.TaskListener;
import space.minecraftstl.xyml.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies staged exact-object update application and cancellation-safe local-state transitions.
@NotNullByDefault
final class RepositoryAddonUpdateApplicationServiceTest {
    /// Direct task-body executor retained by deterministic tests.
    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    /// Per-test real filesystem root.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies model creation retains the exact Core object and an immutable presentation snapshot.
    @Test
    void updateItemRetainsExactCoreUpdateAndStablePresentation() throws IOException {
        FakeLocalAddonFile local = localFile("model/example.jar", false, true);
        LocalAddonFile.AddonUpdate update = update(
                local,
                "1.0.0",
                "1.1.0",
                "replacement.jar",
                true);
        AddonUpdateItem item = AddonUpdateItem.from(
                update,
                URI.create("https://example.invalid/project"));

        Path originalPath = local.getFile();
        local.setOld(true);

        assertAll(
                () -> assertSame(update, item.update()),
                () -> assertEquals("example.jar", item.fileName()),
                () -> assertEquals(originalPath, item.localFile()),
                () -> assertEquals("1.0.0", item.currentVersion()),
                () -> assertEquals("1.1.0", item.targetVersion()),
                () -> assertEquals(RemoteAddon.Source.MODRINTH, item.source()));
    }

    /// Ensures task construction performs only read-only preflight and does not create staging or mutate files.
    @Test
    void createsStoppedTaskWithoutApplyingSelection() throws IOException {
        FakeLocalAddonFile local = localFile("stopped/example.jar", false, false);
        AddonUpdateItem item = item(local, "replacement.jar", true);
        RecordingDownloadTaskFactory downloads = new RecordingDownloadTaskFactory(null);
        RepositoryAddonUpdateApplicationService service = service(downloads);

        Task<AddonUpdateApplicationResult> task = service.applyUpdates(List.of(item));

        assertAll(
                () -> assertEquals(Task.TaskState.READY, task.getState()),
                () -> assertTrue(local.events().isEmpty()),
                () -> assertTrue(downloads.requests().isEmpty()),
                () -> assertTrue(stagingFiles(local.getFile().getParent()).isEmpty()),
                () -> assertTrue(Files.exists(local.getFile())));
    }

    /// Applies active and disabled selections through `.part` staging and configured old-file retention rules.
    @Test
    void publishesValidatedStagingWithRemoteAndOriginalFileNames() throws IOException {
        FakeLocalAddonFile replaced = localFile("success/original.jar", false, false);
        FakeLocalAddonFile disabled = localFile("success/disabled.zip", true, true);
        AddonUpdateItem remoteNamed = item(replaced, "remote-name.jar", true);
        AddonUpdateItem originalNamed = item(disabled, "ignored-remote.zip", false);
        RecordingDownloadTaskFactory downloads = new RecordingDownloadTaskFactory(null);
        RepositoryAddonUpdateApplicationService service = service(downloads);

        Task<AddonUpdateApplicationResult> task = service.applyUpdates(
                List.of(remoteNamed, originalNamed));
        assertTrue(task.executor().test());
        AddonUpdateApplicationResult result = Objects.requireNonNull(task.getResult(), "task result");

        Path directory = temporaryDirectory.resolve("success").toAbsolutePath().normalize();
        Path remoteDestination = directory.resolve("remote-name.jar");
        Path disabledDestination = directory.resolve("disabled.zip.disabled");
        assertAll(
                () -> assertEquals(List.of(remoteNamed, originalNamed), result.successfulUpdates()),
                () -> assertTrue(result.failures().isEmpty()),
                () -> assertEquals(List.of("old:true", "delete"), replaced.events()),
                () -> assertEquals(List.of("old:true"), disabled.events()),
                () -> assertEquals("replacement", Files.readString(remoteDestination)),
                () -> assertEquals("replacement", Files.readString(disabledDestination)),
                () -> assertFalse(Files.exists(directory.resolve("original.jar.old"))),
                () -> assertTrue(Files.exists(directory.resolve("disabled.zip.old"))),
                () -> assertTrue(stagingFiles(directory).isEmpty()),
                () -> assertTrue(downloads.containsValidationPath(remoteDestination)),
                () -> assertTrue(downloads.containsValidationPath(disabledDestination)),
                () -> assertTrue(downloads.requests().stream().allMatch(request ->
                        request.stagingPath().getFileName().toString().endsWith(".part"))),
                () -> assertTrue(downloads.requests().stream().allMatch(request ->
                        !request.stagingPath().equals(request.validationPath()))));
    }

    /// Keeps the source untouched and removes staging when a download fails after writing replacement bytes.
    @Test
    void failedDownloadRemovesStagingWithoutArchivingSource() throws IOException {
        FakeLocalAddonFile local = localFile("failure/example.jar", false, false);
        Path sourcePath = local.getFile();
        Path finalPath = sourcePath.resolveSibling("replacement.jar");
        AddonUpdateItem item = item(local, "replacement.jar", true);
        RecordingDownloadTaskFactory downloads = new RecordingDownloadTaskFactory(finalPath);
        RepositoryAddonUpdateApplicationService service = service(downloads);

        Task<AddonUpdateApplicationResult> task = service.applyUpdates(List.of(item));
        assertTrue(task.executor().test());
        AddonUpdateApplicationResult result = Objects.requireNonNull(task.getResult(), "task result");

        assertAll(
                () -> assertTrue(result.successfulUpdates().isEmpty()),
                () -> assertEquals(1, result.failures().size()),
                () -> assertTrue(result.failures().get(0).detail().contains("simulated download failure")),
                () -> assertTrue(local.events().isEmpty()),
                () -> assertEquals("original", Files.readString(sourcePath)),
                () -> assertFalse(Files.exists(finalPath)),
                () -> assertFalse(Files.exists(sourcePath.resolveSibling("example.jar.old"))),
                () -> assertTrue(stagingFiles(sourcePath.getParent()).isEmpty()));
    }

    /// Rejects path traversal as a value-based failure before staging or local mutation.
    @Test
    void rejectsRemoteFileNameOutsideManagedDirectory() throws IOException {
        FakeLocalAddonFile local = localFile("unsafe/example.jar", false, false);
        AddonUpdateItem item = item(local, "../outside.jar", true);
        RecordingDownloadTaskFactory downloads = new RecordingDownloadTaskFactory(null);
        RepositoryAddonUpdateApplicationService service = service(downloads);

        Task<AddonUpdateApplicationResult> task = service.applyUpdates(List.of(item));
        assertTrue(task.executor().test());
        AddonUpdateApplicationResult result = Objects.requireNonNull(task.getResult(), "task result");

        assertAll(
                () -> assertTrue(result.successfulUpdates().isEmpty()),
                () -> assertEquals(1, result.failures().size()),
                () -> assertTrue(result.failures().get(0).detail().contains("escapes its managed directory")),
                () -> assertTrue(local.events().isEmpty()),
                () -> assertTrue(downloads.requests().isEmpty()),
                () -> assertTrue(stagingFiles(local.getFile().getParent()).isEmpty()));
    }

    /// Rejects duplicate objects, colliding destinations, selected sources, and generated `.old` archives.
    @Test
    void rejectsUnsafeBatchPathRelationships() throws IOException {
        FakeLocalAddonFile firstLocal = localFile("batch/first.jar", false, false);
        FakeLocalAddonFile secondLocal = localFile("batch/second.jar", false, false);
        RepositoryAddonUpdateApplicationService service = service(
                new RecordingDownloadTaskFactory(null));

        AddonUpdateItem duplicateFirst = item(firstLocal, "first-new.jar", true);
        AddonUpdateItem duplicateSecond = item(firstLocal, "second-new.jar", true);
        AddonUpdateItem sameDestinationFirst = item(firstLocal, "same.jar", true);
        AddonUpdateItem sameDestinationSecond = item(secondLocal, "same.jar", true);
        AddonUpdateItem anotherSource = item(firstLocal, "second.jar", true);
        AddonUpdateItem ownArchive = item(firstLocal, "first.jar.old", true);

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> service.applyUpdates(List.of(duplicateFirst, duplicateSecond))),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> service.applyUpdates(List.of(
                                sameDestinationFirst,
                                sameDestinationSecond))),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> service.applyUpdates(List.of(
                                anotherSource,
                                item(secondLocal, "second-new.jar", true)))),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> service.applyUpdates(List.of(ownArchive))));
    }

    /// Rejects an occupied non-self target while allowing the selected source as its own destination.
    @Test
    void preflightRejectsExistingTargetsButAllowsOwnSource() throws IOException {
        FakeLocalAddonFile local = localFile("existing/source.jar", false, false);
        Path occupied = local.getFile().resolveSibling("occupied.jar");
        Files.writeString(occupied, "competitor");
        RepositoryAddonUpdateApplicationService service = service(
                new RecordingDownloadTaskFactory(null));

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> service.applyUpdates(List.of(item(local, "occupied.jar", true)))),
                () -> assertEquals(
                        Task.TaskState.READY,
                        service.applyUpdates(List.of(item(local, "ignored.jar", false))).getState()),
                () -> assertEquals("competitor", Files.readString(occupied)));
    }

    /// Rechecks final-path occupancy at execution time and preserves a competitor created after preflight.
    @Test
    void runtimeRecheckPreservesDestinationCreatedAfterTaskConstruction() throws IOException {
        FakeLocalAddonFile local = localFile("toctou/source.jar", false, false);
        Path sourcePath = local.getFile();
        Path destination = sourcePath.resolveSibling("replacement.jar");
        AddonUpdateItem item = item(local, "replacement.jar", true);
        RepositoryAddonUpdateApplicationService service = service(
                new RecordingDownloadTaskFactory(null));
        Task<AddonUpdateApplicationResult> task = service.applyUpdates(List.of(item));

        Files.writeString(destination, "competitor");
        assertTrue(task.executor().test());
        AddonUpdateApplicationResult result = Objects.requireNonNull(task.getResult(), "task result");

        assertAll(
                () -> assertTrue(result.successfulUpdates().isEmpty()),
                () -> assertTrue(result.failures().get(0).detail().contains("became occupied")),
                () -> assertEquals("competitor", Files.readString(destination)),
                () -> assertEquals("original", Files.readString(sourcePath)),
                () -> assertTrue(local.events().isEmpty()),
                () -> assertTrue(stagingFiles(sourcePath.getParent()).isEmpty()));
    }

    /// Preserves a competitor created during archiving because publication was never confirmed.
    @Test
    void failedPublicationNeverDeletesCompetingDestination() throws IOException {
        FakeLocalAddonFile local = localFile("publish-race/source.jar", false, false);
        Path sourcePath = local.getFile();
        Path destination = sourcePath.resolveSibling("replacement.jar");
        local.setCreateAfterArchive(destination);
        AddonUpdateItem item = item(local, "replacement.jar", true);
        RepositoryAddonUpdateApplicationService service = service(
                new RecordingDownloadTaskFactory(null));

        Task<AddonUpdateApplicationResult> task = service.applyUpdates(List.of(item));
        assertTrue(task.executor().test());
        AddonUpdateApplicationResult result = Objects.requireNonNull(task.getResult(), "task result");

        assertAll(
                () -> assertTrue(result.successfulUpdates().isEmpty()),
                () -> assertTrue(result.failures().get(0).detail().contains("during publication")),
                () -> assertEquals("competitor", Files.readString(destination)),
                () -> assertEquals("original", Files.readString(sourcePath)),
                () -> assertFalse(Files.exists(sourcePath.resolveSibling("source.jar.old"))),
                () -> assertTrue(stagingFiles(sourcePath.getParent()).isEmpty()));
    }

    /// Deletes this operation's renamed published target before restoring the archived source after failure.
    @Test
    void postPublicationFailureDeletesOwnRenamedDestinationBeforeRestoringSource() throws IOException {
        FakeLocalAddonFile local = localFile("published-rollback/source.jar", false, false);
        Path sourcePath = local.getFile();
        Path destination = sourcePath.resolveSibling("replacement.jar");
        local.setFailKeepOldFiles(true);
        RepositoryAddonUpdateApplicationService service = service(
                new RecordingDownloadTaskFactory(null));

        Task<AddonUpdateApplicationResult> task = service.applyUpdates(
                List.of(item(local, "replacement.jar", true)));
        assertTrue(task.executor().test());
        AddonUpdateApplicationResult result = Objects.requireNonNull(task.getResult(), "task result");

        assertAll(
                () -> assertTrue(result.successfulUpdates().isEmpty()),
                () -> assertTrue(result.failures().get(0).detail().contains(
                        "simulated post-publication failure")),
                () -> assertEquals(sourcePath, local.getFile()),
                () -> assertEquals("original", Files.readString(sourcePath)),
                () -> assertFalse(Files.exists(destination)),
                () -> assertFalse(Files.exists(sourcePath.resolveSibling("source.jar.old"))),
                () -> assertEquals(List.of("old:true", "old:false"), local.events()),
                () -> assertTrue(stagingFiles(sourcePath.getParent()).isEmpty()));
    }

    /// Restores an archive moved before a simulated local-manager exception left the object path unchanged.
    @Test
    void partialArchivingFailureMovesArchiveBackWithoutReplacement() throws IOException {
        FakeLocalAddonFile local = localFile("partial-archive/source.jar", false, false);
        Path sourcePath = local.getFile();
        local.setFailAfterArchiveMove(true);
        AddonUpdateItem item = item(local, "replacement.jar", true);
        RepositoryAddonUpdateApplicationService service = service(
                new RecordingDownloadTaskFactory(null));

        Task<AddonUpdateApplicationResult> task = service.applyUpdates(List.of(item));
        assertTrue(task.executor().test());
        AddonUpdateApplicationResult result = Objects.requireNonNull(task.getResult(), "task result");

        assertAll(
                () -> assertTrue(result.successfulUpdates().isEmpty()),
                () -> assertTrue(result.failures().get(0).detail().contains("simulated partial archive failure")),
                () -> assertEquals(sourcePath, local.getFile()),
                () -> assertEquals("original", Files.readString(sourcePath)),
                () -> assertFalse(Files.exists(sourcePath.resolveSibling("source.jar.old"))),
                () -> assertFalse(Files.exists(sourcePath.resolveSibling("replacement.jar"))),
                () -> assertTrue(stagingFiles(sourcePath.getParent()).isEmpty()));
    }

    /// Cancels after staged bytes are written and verifies root failure cleanup runs before stop notification.
    @Test
    void cancellationRemovesStagingAndLeavesOriginalUntouched() throws Exception {
        FakeLocalAddonFile local = localFile("cancel/source.jar", false, false);
        Path sourcePath = local.getFile();
        Path destination = sourcePath.resolveSibling("replacement.jar");
        BlockingDownloadTaskFactory downloads = new BlockingDownloadTaskFactory();
        RepositoryAddonUpdateApplicationService service = service(downloads);
        Task<AddonUpdateApplicationResult> task = service.applyUpdates(
                List.of(item(local, "replacement.jar", true)));
        StopListener stopListener = new StopListener();
        TaskExecutor executor = task.executor(stopListener);

        executor.start();
        assertTrue(downloads.awaitWritten());
        executor.cancel();
        downloads.release();
        assertTrue(stopListener.awaitStopped());

        assertAll(
                () -> assertFalse(stopListener.succeeded()),
                () -> assertEquals("original", Files.readString(sourcePath)),
                () -> assertFalse(Files.exists(destination)),
                () -> assertFalse(Files.exists(sourcePath.resolveSibling("source.jar.old"))),
                () -> assertTrue(local.events().isEmpty()),
                () -> assertTrue(stagingFiles(sourcePath.getParent()).isEmpty()));
    }

    /// Rechecks cancellation after waiting for another service instance to release the shared commit lock.
    @Test
    void cancellationWhileWaitingForCommitLockLeavesSourceUntouched() throws Exception {
        CountDownLatch firstArchiveEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstArchive = new CountDownLatch(1);
        CountDownLatch secondBeforeCommitLock = new CountDownLatch(1);
        CountDownLatch releaseSecondCommitAttempt = new CountDownLatch(1);
        CountDownLatch secondCommitAttempting = new CountDownLatch(1);
        FakeLocalAddonFile first = localFile("lock-cancel/first.jar", false, false);
        FakeLocalAddonFile second = localFile("lock-cancel/second.jar", false, false);
        Path secondSource = second.getFile();
        Path secondDestination = secondSource.resolveSibling("second-new.jar");
        first.blockArchiveTransition(firstArchiveEntered, releaseFirstArchive);

        Task<AddonUpdateApplicationResult> firstTask = service(
                new RecordingDownloadTaskFactory(null)).applyUpdates(
                        List.of(item(first, "first-new.jar", true)));
        Task<AddonUpdateApplicationResult> secondTask = service(
                new RecordingDownloadTaskFactory(null),
                () -> {
                    secondBeforeCommitLock.countDown();
                    awaitLatch(releaseSecondCommitAttempt, "release second commit attempt");
                    secondCommitAttempting.countDown();
                }).applyUpdates(
                        List.of(item(second, "second-new.jar", true)));
        StopListener firstStop = new StopListener();
        StopListener secondStop = new StopListener();
        TaskExecutor firstExecutor = firstTask.executor(firstStop);
        TaskExecutor secondExecutor = secondTask.executor(secondStop);

        secondExecutor.start();
        try {
            assertTrue(secondBeforeCommitLock.await(5, TimeUnit.SECONDS));
            firstExecutor.start();
            assertTrue(firstArchiveEntered.await(5, TimeUnit.SECONDS));
            releaseSecondCommitAttempt.countDown();
            assertTrue(secondCommitAttempting.await(5, TimeUnit.SECONDS));
            secondExecutor.cancel();
        } finally {
            releaseSecondCommitAttempt.countDown();
            releaseFirstArchive.countDown();
        }
        assertTrue(firstStop.awaitStopped());
        assertTrue(secondStop.awaitStopped());

        assertAll(
                () -> assertTrue(firstStop.succeeded()),
                () -> assertFalse(secondStop.succeeded()),
                () -> assertEquals(secondSource, second.getFile()),
                () -> assertEquals("original", Files.readString(secondSource)),
                () -> assertFalse(Files.exists(secondDestination)),
                () -> assertFalse(Files.exists(secondSource.resolveSibling("second.jar.old"))),
                () -> assertTrue(second.events().isEmpty()),
                () -> assertTrue(stagingFiles(secondSource.getParent()).isEmpty()));
    }

    /// Confirms staged downloads overlap while every local state transition remains globally serialized.
    @Test
    void downloadsRemainParallelWhileLocalMutationsAreSerialized() throws Exception {
        StateMutationProbe probe = new StateMutationProbe();
        FakeLocalAddonFile first = localFile("parallel/first.jar", false, false, probe);
        FakeLocalAddonFile second = localFile("parallel/second.jar", false, false, probe);
        ParallelDownloadTaskFactory downloads = new ParallelDownloadTaskFactory(2);
        RepositoryAddonUpdateApplicationService service = service(downloads);
        Task<AddonUpdateApplicationResult> task = service.applyUpdates(List.of(
                item(first, "first-new.jar", true),
                item(second, "second-new.jar", true)));
        StopListener stopListener = new StopListener();
        TaskExecutor executor = task.executor(stopListener);

        executor.start();
        assertTrue(downloads.awaitAllStarted());
        downloads.release();
        assertTrue(stopListener.awaitStopped());

        assertAll(
                () -> assertTrue(stopListener.succeeded()),
                () -> assertEquals(1, probe.maximumConcurrentMutations()),
                () -> assertTrue(Files.exists(first.getFile().resolveSibling("first-new.jar"))),
                () -> assertTrue(Files.exists(second.getFile().resolveSibling("second-new.jar"))));
    }

    /// Creates a deterministic service with a mirror-aware fake provider.
    ///
    /// @param downloads staged download factory
    /// @return service with direct task-body execution
    private static RepositoryAddonUpdateApplicationService service(
            RepositoryAddonUpdateApplicationService.DownloadTaskFactory downloads) {
        return new RepositoryAddonUpdateApplicationService(
                new FakeDownloadProvider(),
                DIRECT_EXECUTOR,
                downloads);
    }

    /// Creates a deterministic service with an exact pre-commit-lock observer.
    ///
    /// @param downloads staged download factory
    /// @param beforeCommitLock observer invoked immediately before the service waits for its commit lock
    /// @return service with direct task-body execution and deterministic commit observation
    private static RepositoryAddonUpdateApplicationService service(
            RepositoryAddonUpdateApplicationService.DownloadTaskFactory downloads,
            Runnable beforeCommitLock) {
        return new RepositoryAddonUpdateApplicationService(
                new FakeDownloadProvider(),
                DIRECT_EXECUTOR,
                downloads,
                beforeCommitLock);
    }

    /// Waits for a deterministic test signal and converts interruption or timeout to an unchecked failure.
    ///
    /// @param latch signal awaited by a task callback
    /// @param description concise timeout context
    /// @throws IllegalStateException when the signal times out or the thread is interrupted
    private static void awaitLatch(CountDownLatch latch, String description) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to " + description);
            }
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to " + description, interruption);
        }
    }

    /// Creates one exact model item for a fake local add-on.
    ///
    /// @param local local add-on object
    /// @param remoteFileName remote artifact file name
    /// @param useRemoteFileName whether the destination uses the remote artifact name
    /// @return exact update item
    private static AddonUpdateItem item(
            FakeLocalAddonFile local,
            String remoteFileName,
            boolean useRemoteFileName) {
        return AddonUpdateItem.from(
                update(local, "1.0.0", "2.0.0", remoteFileName, useRemoteFileName),
                null);
    }

    /// Creates one exact Core update record.
    ///
    /// @param local exact local add-on
    /// @param currentVersion installed version text
    /// @param targetVersion replacement version text
    /// @param remoteFileName replacement artifact name
    /// @param useRemoteFileName whether to replace the original file name
    /// @return exact Core update record
    private static LocalAddonFile.AddonUpdate update(
            FakeLocalAddonFile local,
            String currentVersion,
            String targetVersion,
            String remoteFileName,
            boolean useRemoteFileName) {
        RemoteAddon.IVersion source = () -> RemoteAddon.Source.MODRINTH;
        return new LocalAddonFile.AddonUpdate(
                local,
                version(source, "current", currentVersion, "current.jar"),
                version(source, "target", targetVersion, remoteFileName),
                useRemoteFileName);
    }

    /// Creates one minimal remote add-on version for service-only tests.
    ///
    /// @param source exact remote source object
    /// @param name progress display name
    /// @param version version text
    /// @param fileName remote artifact file name
    /// @return exact remote version
    private static RemoteAddon.Version version(
            RemoteAddon.IVersion source,
            String name,
            String version,
            String fileName) {
        return new RemoteAddon.Version(
                source,
                "project-id",
                name,
                version,
                "",
                Instant.EPOCH,
                RemoteAddon.VersionType.Release,
                new RemoteAddon.File(
                        Map.of(),
                        "https://origin.invalid/files/target.jar",
                        fileName),
                List.of(),
                List.of("1.21.1"),
                List.of());
    }

    /// Creates one real file-backed fake local add-on.
    ///
    /// @param relativePath relative source path
    /// @param disabled whether the source uses the disabled suffix
    /// @param keepOldFiles whether successful publication retains the archive
    /// @return file-backed fake local add-on
    /// @throws IOException when the source cannot be created
    private FakeLocalAddonFile localFile(
            String relativePath,
            boolean disabled,
            boolean keepOldFiles) throws IOException {
        return localFile(relativePath, disabled, keepOldFiles, new StateMutationProbe());
    }

    /// Creates one real file-backed fake with an explicit shared mutation probe.
    ///
    /// @param relativePath relative source path
    /// @param disabled whether the source uses the disabled suffix
    /// @param keepOldFiles whether successful publication retains the archive
    /// @param mutationProbe shared state mutation probe
    /// @return file-backed fake local add-on
    /// @throws IOException when the source cannot be created
    private FakeLocalAddonFile localFile(
            String relativePath,
            boolean disabled,
            boolean keepOldFiles,
            StateMutationProbe mutationProbe) throws IOException {
        Path sourcePath = temporaryDirectory.resolve(relativePath).toAbsolutePath().normalize();
        if (disabled) {
            sourcePath = sourcePath.resolveSibling(StringUtils.addSuffix(
                    sourcePath.getFileName().toString(),
                    LocalAddonManager.DISABLED_EXTENSION));
        }
        Files.createDirectories(Objects.requireNonNull(sourcePath.getParent(), "source parent"));
        Files.writeString(sourcePath, "original");
        return new FakeLocalAddonFile(sourcePath, keepOldFiles, mutationProbe);
    }

    /// Returns service-owned staging files currently present in one add-on directory.
    ///
    /// @param directory add-on directory
    /// @return immutable staging-file snapshot
    /// @throws IOException when the directory cannot be listed
    private static @Unmodifiable List<Path> stagingFiles(Path directory) throws IOException {
        try (var stream = Files.list(directory)) {
            return stream.filter(path -> path.getFileName().toString().startsWith(".xyml-addon-update-"))
                    .toList();
        }
    }

    /// Mutable file-backed local add-on fake with optional publication-race and partial-archive hooks.
    @NotNullByDefault
    private static final class FakeLocalAddonFile extends LocalAddonFile {
        /// Current simulated local path.
        private Path file;

        /// Stable display name captured from the original non-state file name.
        private final String fileName;

        /// Whether successful publication retains the archived original.
        private final boolean keepOldFiles;

        /// Shared state mutation probe.
        private final StateMutationProbe mutationProbe;

        /// Ordered local state transitions.
        private final List<String> events = new CopyOnWriteArrayList<>();

        /// Destination to occupy immediately after archiving, or `null` when disabled.
        private @Nullable Path createAfterArchive;

        /// Whether `setOld(true)` fails after moving the source but before updating the object path.
        private boolean failAfterArchiveMove;

        /// Whether archive-retention lookup fails after replacement publication.
        private boolean failKeepOldFiles;

        /// Signal emitted after `setOld(true)` enters while the shared service lock is held.
        private @Nullable CountDownLatch archiveEntered;

        /// Signal that releases a blocked `setOld(true)` transition.
        private @Nullable CountDownLatch archiveRelease;

        /// Creates one file-backed local add-on fake.
        ///
        /// @param file initial source path
        /// @param keepOldFiles whether successful publication keeps the archive
        /// @param mutationProbe shared state mutation probe
        private FakeLocalAddonFile(
                Path file,
                boolean keepOldFiles,
                StateMutationProbe mutationProbe) {
            this.file = Objects.requireNonNull(file, "file");
            this.fileName = StringUtils.removeSuffix(
                    this.file.getFileName().toString(),
                    LocalAddonManager.DISABLED_EXTENSION,
                    LocalAddonManager.OLD_EXTENSION);
            this.keepOldFiles = keepOldFiles;
            this.mutationProbe = Objects.requireNonNull(mutationProbe, "mutationProbe");
        }

        /// Returns the current simulated path.
        ///
        /// @return current path
        @Override
        public Path getFile() {
            return file;
        }

        /// Returns the stable local display name.
        ///
        /// @return stable display name
        @Override
        public String getFileName() {
            return fileName;
        }

        /// Applies the disabled suffix through a real no-overwrite move.
        ///
        /// @throws IOException when the simulated move fails
        @Override
        public void markDisabled() throws IOException {
            mutationProbe.enter();
            try {
                events.add("disabled");
                Path target = file.resolveSibling(StringUtils.addSuffix(
                        file.getFileName().toString(),
                        LocalAddonManager.DISABLED_EXTENSION));
                if (!target.equals(file) && Files.exists(file)) {
                    Files.move(file, target);
                }
                file = target;
            } finally {
                mutationProbe.exit();
            }
        }

        /// Applies or removes the old-file suffix through a real no-overwrite move.
        ///
        /// @param old whether the source becomes archived
        /// @throws IOException when the simulated move or configured partial failure occurs
        @Override
        public void setOld(boolean old) throws IOException {
            mutationProbe.enter();
            try {
                events.add("old:" + old);
                @Nullable CountDownLatch exactArchiveEntered = archiveEntered;
                @Nullable CountDownLatch exactArchiveRelease = archiveRelease;
                if (old && exactArchiveEntered != null && exactArchiveRelease != null) {
                    exactArchiveEntered.countDown();
                    try {
                        if (!exactArchiveRelease.await(5, TimeUnit.SECONDS)) {
                            throw new IOException("Timed out waiting to release archived source transition");
                        }
                    } catch (InterruptedException interruption) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while waiting to archive source", interruption);
                    }
                }
                String currentName = file.getFileName().toString();
                String targetName = old
                        ? StringUtils.addSuffix(
                                StringUtils.removeSuffix(
                                        currentName,
                                        LocalAddonManager.DISABLED_EXTENSION),
                                LocalAddonManager.OLD_EXTENSION)
                        : StringUtils.removeSuffix(currentName, LocalAddonManager.OLD_EXTENSION);
                Path target = file.resolveSibling(targetName);
                if (!target.equals(file) && Files.exists(file)) {
                    Files.move(file, target);
                }
                if (old && createAfterArchive != null) {
                    Files.writeString(createAfterArchive, "competitor");
                }
                if (old && failAfterArchiveMove) {
                    throw new IOException("simulated partial archive failure");
                }
                file = target;
            } finally {
                mutationProbe.exit();
            }
        }

        /// Returns configured archive retention behavior.
        ///
        /// @return configured retention behavior
        /// @throws IllegalStateException when post-publication failure simulation is enabled
        @Override
        public boolean keepOldFiles() {
            if (failKeepOldFiles) {
                throw new IllegalStateException("simulated post-publication failure");
            }
            return keepOldFiles;
        }

        /// Deletes the current archived source path.
        ///
        /// @throws IOException when deletion fails
        @Override
        public void delete() throws IOException {
            mutationProbe.enter();
            try {
                events.add("delete");
                Files.deleteIfExists(file);
            } finally {
                mutationProbe.exit();
            }
        }

        /// Returns no update because tests construct exact update records directly.
        ///
        /// @param downloadProvider unused provider
        /// @param gameVersion unused game version
        /// @param source unused source
        /// @return always `null`
        @Override
        public @Nullable AddonUpdate checkUpdates(
                DownloadProvider downloadProvider,
                String gameVersion,
                RemoteAddon.Source source) {
            return null;
        }

        /// Configures one destination competitor created during `setOld(true)`.
        ///
        /// @param destination competitor destination
        private void setCreateAfterArchive(Path destination) {
            createAfterArchive = Objects.requireNonNull(destination, "destination");
        }

        /// Configures a partial archive failure after the filesystem move.
        ///
        /// @param failAfterArchiveMove whether the failure is enabled
        private void setFailAfterArchiveMove(boolean failAfterArchiveMove) {
            this.failAfterArchiveMove = failAfterArchiveMove;
        }

        /// Configures an archive-retention lookup failure after the replacement was published.
        ///
        /// @param failKeepOldFiles whether the failure is enabled
        private void setFailKeepOldFiles(boolean failKeepOldFiles) {
            this.failKeepOldFiles = failKeepOldFiles;
        }

        /// Blocks `setOld(true)` after it enters while the service owns the shared state lock.
        ///
        /// @param archiveEntered signal emitted after entering the archive transition
        /// @param archiveRelease signal that releases the archive transition
        private void blockArchiveTransition(
                CountDownLatch archiveEntered,
                CountDownLatch archiveRelease) {
            this.archiveEntered = Objects.requireNonNull(archiveEntered, "archiveEntered");
            this.archiveRelease = Objects.requireNonNull(archiveRelease, "archiveRelease");
        }

        /// Returns an immutable snapshot of recorded state transitions.
        ///
        /// @return transition snapshot
        private @Unmodifiable List<String> events() {
            return List.copyOf(events);
        }
    }

    /// Tracks maximum concurrent local-state transitions across multiple fake add-ons.
    @NotNullByDefault
    private static final class StateMutationProbe {
        /// Current local-state mutation count.
        private final AtomicInteger activeMutations = new AtomicInteger();

        /// Maximum observed concurrent local-state mutation count.
        private final AtomicInteger maximumConcurrentMutations = new AtomicInteger();

        /// Enters one local-state mutation and widens the race window for concurrency assertions.
        ///
        /// @throws IOException when interrupted during the deterministic delay
        private void enter() throws IOException {
            int active = activeMutations.incrementAndGet();
            maximumConcurrentMutations.accumulateAndGet(active, Math::max);
            try {
                Thread.sleep(20L);
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while simulating local mutation", interruption);
            }
        }

        /// Leaves one local-state mutation.
        private void exit() {
            activeMutations.decrementAndGet();
        }

        /// Returns maximum observed concurrent mutations.
        ///
        /// @return maximum concurrent mutation count
        private int maximumConcurrentMutations() {
            return maximumConcurrentMutations.get();
        }
    }

    /// Deterministic provider that preserves mirror-first and origin-second candidates.
    @NotNullByDefault
    private static final class FakeDownloadProvider implements DownloadProvider {
        /// Returns no game version list endpoints because the service does not query metadata.
        ///
        /// @return empty endpoint list
        @Override
        public @Unmodifiable List<URI> getVersionListURLs() {
            return List.of();
        }

        /// Returns no asset candidates because the service downloads only selected add-ons.
        ///
        /// @param assetObjectLocation ignored asset location
        /// @return empty candidate list
        @Override
        public @Unmodifiable List<URI> getAssetObjectCandidates(String assetObjectLocation) {
            return List.of();
        }

        /// Rewrites the origin host to the deterministic mirror host.
        ///
        /// @param baseURL original URL
        /// @return mirror URL
        @Override
        public String injectURL(String baseURL) {
            return Objects.requireNonNull(baseURL, "baseURL")
                    .replace("origin.invalid", "mirror.invalid");
        }

        /// Returns mirror-first and origin-second candidates.
        ///
        /// @param baseURL original remote artifact URL
        /// @return immutable candidate list
        @Override
        public @Unmodifiable List<URI> injectURLWithCandidates(String baseURL) {
            return List.of(URI.create(injectURL(baseURL)), URI.create(baseURL));
        }

        /// Rejects metadata version-list access outside this service's scope.
        ///
        /// @param id requested version list identifier
        /// @return never returns normally
        /// @throws IllegalArgumentException always
        @Override
        public VersionList<?> getVersionListById(String id) {
            throw new IllegalArgumentException("Unsupported test version list: " + id);
        }

        /// Returns a small deterministic download concurrency.
        ///
        /// @return two concurrent downloads
        @Override
        public int getConcurrency() {
            return 2;
        }
    }

    /// Recording staged download factory with optional validation-path-specific failure.
    @NotNullByDefault
    private static final class RecordingDownloadTaskFactory
            implements RepositoryAddonUpdateApplicationService.DownloadTaskFactory {
        /// Final validation path whose staged write fails, or `null` for success.
        private final @Nullable Path failedValidationPath;

        /// Thread-safe captured staged download requests.
        private final List<DownloadRequest> requests = new CopyOnWriteArrayList<>();

        /// Creates one recording factory.
        ///
        /// @param failedValidationPath final path whose download fails, or `null`
        private RecordingDownloadTaskFactory(@Nullable Path failedValidationPath) {
            this.failedValidationPath = failedValidationPath == null
                    ? null
                    : failedValidationPath.toAbsolutePath().normalize();
        }

        /// Captures one staged request and writes deterministic replacement bytes.
        ///
        /// @param candidates ordered provider candidates
        /// @param stagingPath unique owned staging destination
        /// @param validationPath final structural validation path
        /// @param integrityCheck optional checksum
        /// @param downloadName progress display name
        /// @return stopped deterministic staged download task
        @Override
        public Task<@Nullable Void> create(
                @Unmodifiable List<URI> candidates,
                Path stagingPath,
                Path validationPath,
                @Nullable FileDownloadTask.IntegrityCheck integrityCheck,
                String downloadName) {
            DownloadRequest request = new DownloadRequest(
                    candidates,
                    stagingPath,
                    validationPath,
                    integrityCheck,
                    downloadName);
            requests.add(request);
            return Task.runAsync(DIRECT_EXECUTOR, () -> {
                Files.writeString(request.stagingPath(), "replacement");
                if (failedValidationPath != null
                        && failedValidationPath.equals(request.validationPath())) {
                    throw new IOException("simulated download failure");
                }
            });
        }

        /// Returns whether one request used the exact final validation path.
        ///
        /// @param validationPath expected final validation path
        /// @return whether a matching request exists
        private boolean containsValidationPath(Path validationPath) {
            Path normalized = validationPath.toAbsolutePath().normalize();
            return requests.stream().anyMatch(request -> request.validationPath().equals(normalized));
        }

        /// Returns an immutable captured request snapshot.
        ///
        /// @return request snapshot
        private @Unmodifiable List<DownloadRequest> requests() {
            return List.copyOf(requests);
        }
    }

    /// Blocking staged download factory used to reproduce cancellation after bytes are written.
    @NotNullByDefault
    private static final class BlockingDownloadTaskFactory
            implements RepositoryAddonUpdateApplicationService.DownloadTaskFactory {
        /// Signals that replacement bytes reached staging.
        private final CountDownLatch written = new CountDownLatch(1);

        /// Releases the blocked fake download.
        private final CountDownLatch release = new CountDownLatch(1);

        /// Writes staging, signals the test, and waits for cancellation to be requested.
        ///
        /// @param candidates ignored candidates
        /// @param stagingPath unique staging destination
        /// @param validationPath ignored final path
        /// @param integrityCheck ignored checksum
        /// @param downloadName ignored display name
        /// @return stopped blocking staged download task
        @Override
        public Task<@Nullable Void> create(
                @Unmodifiable List<URI> candidates,
                Path stagingPath,
                Path validationPath,
                @Nullable FileDownloadTask.IntegrityCheck integrityCheck,
                String downloadName) {
            return Task.runAsync(DIRECT_EXECUTOR, () -> {
                Files.writeString(stagingPath, "replacement");
                written.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out waiting to release staged download");
                }
            });
        }

        /// Waits until staging contains replacement bytes.
        ///
        /// @return whether staging was written before timeout
        /// @throws InterruptedException when interrupted while waiting
        private boolean awaitWritten() throws InterruptedException {
            return written.await(5, TimeUnit.SECONDS);
        }

        /// Releases the blocked staged download.
        private void release() {
            release.countDown();
        }
    }

    /// Parallel staged download factory that blocks every request until all siblings have started.
    @NotNullByDefault
    private static final class ParallelDownloadTaskFactory
            implements RepositoryAddonUpdateApplicationService.DownloadTaskFactory {
        /// Counts sibling downloads that reached their task bodies.
        private final CountDownLatch started;

        /// Releases every blocked sibling download.
        private final CountDownLatch release = new CountDownLatch(1);

        /// Creates one expected-concurrency factory.
        ///
        /// @param expectedDownloads sibling download count
        private ParallelDownloadTaskFactory(int expectedDownloads) {
            started = new CountDownLatch(expectedDownloads);
        }

        /// Writes staging and waits until the test confirms every sibling started.
        ///
        /// @param candidates ignored candidates
        /// @param stagingPath unique staging destination
        /// @param validationPath ignored final path
        /// @param integrityCheck ignored checksum
        /// @param downloadName ignored display name
        /// @return stopped blocking staged download task
        @Override
        public Task<@Nullable Void> create(
                @Unmodifiable List<URI> candidates,
                Path stagingPath,
                Path validationPath,
                @Nullable FileDownloadTask.IntegrityCheck integrityCheck,
                String downloadName) {
            return Task.runAsync(DIRECT_EXECUTOR, () -> {
                Files.writeString(stagingPath, "replacement");
                started.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out waiting to release parallel downloads");
                }
            });
        }

        /// Waits until every sibling download task body has started.
        ///
        /// @return whether all siblings started before timeout
        /// @throws InterruptedException when interrupted while waiting
        private boolean awaitAllStarted() throws InterruptedException {
            return started.await(5, TimeUnit.SECONDS);
        }

        /// Releases every blocked sibling download.
        private void release() {
            release.countDown();
        }
    }

    /// Captures one task-chain terminal result for asynchronous cancellation and concurrency tests.
    @NotNullByDefault
    private static final class StopListener extends TaskListener {
        /// Terminal stop signal.
        private final CountDownLatch stopped = new CountDownLatch(1);

        /// Whether the task chain reported success.
        private final AtomicBoolean succeeded = new AtomicBoolean();

        /// Records terminal chain state.
        ///
        /// @param success whether the chain succeeded
        /// @param executor completed executor
        @Override
        public void onStop(boolean success, TaskExecutor executor) {
            succeeded.set(success);
            stopped.countDown();
        }

        /// Waits for terminal task-chain notification.
        ///
        /// @return whether stop notification arrived before timeout
        /// @throws InterruptedException when interrupted while waiting
        private boolean awaitStopped() throws InterruptedException {
            return stopped.await(5, TimeUnit.SECONDS);
        }

        /// Returns captured terminal success state.
        ///
        /// @return whether the task chain succeeded
        private boolean succeeded() {
            return succeeded.get();
        }
    }

    /// Immutable captured staged download request.
    ///
    /// @param candidates exact provider candidates
    /// @param stagingPath exact staging destination
    /// @param validationPath exact final structural validation path
    /// @param integrityCheck optional artifact checksum
    /// @param downloadName progress display name
    @NotNullByDefault
    private record DownloadRequest(
            @Unmodifiable List<URI> candidates,
            Path stagingPath,
            Path validationPath,
            @Nullable FileDownloadTask.IntegrityCheck integrityCheck,
            String downloadName) {
        /// Validates and snapshots captured staged download request data.
        private DownloadRequest {
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
            stagingPath = Objects.requireNonNull(stagingPath, "stagingPath")
                    .toAbsolutePath().normalize();
            validationPath = Objects.requireNonNull(validationPath, "validationPath")
                    .toAbsolutePath().normalize();
            downloadName = Objects.requireNonNull(downloadName, "downloadName");
        }
    }
}
