// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.game;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.gson.JsonUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;

/// Stages isolated manifest and file changes for one root operation.
/// Drafts prepare independently. Commit is serialized by the repository, verifies its read set,
/// and rebases the write set over the newest committed snapshot. Shared caches are not rolled back.
/// Callers must declare affected TaskResource paths; this class does not replace task ownership.
@NotNullByDefault
public final class GameRepositoryDraft implements AutoCloseable {
    /// Explicit mutation and terminal states.
    @NotNullByDefault
    public enum State {
        /// Changes may be staged.
        OPEN,
        /// Prepared changes are being applied or rolled back.
        COMMITTING,
        /// Changes and their snapshot were published.
        COMMITTED,
        /// Uncommitted changes were discarded.
        ABORTED,
        /// Commit failed; recovery data may be retained.
        FAILED
    }

    /// Owning repository and its existing publication lock.
    private final DefaultGameRepository repository;

    /// Immutable base used for conflict detection.
    private final GameRepositorySnapshot baseSnapshot;

    /// Private staging directory; never contains shared cache ownership.
    private final Path stagingDirectory;

    /// Captured root identity excludes later replacement of the repository directory.
    private final @Nullable Object rootKey;

    /// Identity used to reject cleanup after private staging ownership changes.
    private final @Nullable Object stagingKey;

    /// Creation timestamp is an ownership fallback on providers without file keys.
    private final java.nio.file.attribute.FileTime stagingCreation;

    /// Read dependencies including expected absence.
    private final Set<GameInstanceID> readIds = new TreeSet<>();

    /// Immutable serialized staged manifests.
    private final Map<GameInstanceID, String> manifests = new TreeMap<>();

    /// Removed manifest identifiers.
    private final Set<GameInstanceID> removedIds = new TreeSet<>();

    /// Ordered target-file replacements or deletions.
    private final Map<Path, FileChange> files = new LinkedHashMap<>();

    /// Owner-controlled state participants, in application order.
    private final List<GameRepositoryDraftParticipant> participants = new ArrayList<>();

    /// State visible to terminal callbacks and cancellation requests.
    private volatile State state = State.OPEN;

    /// Cancellation is arbitrated with final snapshot publication.
    private boolean cancellationRequested;

    /// Interruption observed during rollback is restored after best-effort recovery.
    private boolean cleanupInterrupted;

    /// Post-commit cleanup failure does not undo a committed outcome.
    private volatile @Nullable IOException cleanupFailure;

    /// Creates private staging only after a root task starts.
    GameRepositoryDraft(DefaultGameRepository repository) throws IOException {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.baseSnapshot = repository.getSnapshot();
        Path root = baseSnapshot.getBaseDirectory();
        Files.createDirectories(root);
        rootKey = Files.readAttributes(root, BasicFileAttributes.class).fileKey();
        Path parent = root.resolve(".xyml/repository-drafts");
        rejectLinks(root, parent);
        Files.createDirectories(parent);
        stagingDirectory = Files.createTempDirectory(parent, "draft-");
        BasicFileAttributes stagingAttributes = Files.readAttributes(stagingDirectory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        stagingKey = stagingAttributes.fileKey();
        stagingCreation = stagingAttributes.creationTime();
    }

    /// Returns the repository without transferring lifecycle ownership.
    public DefaultGameRepository getRepository() {
        return repository;
    }

    /// Returns the terminal state without exposing mutation collections.
    public State getState() {
        return state;
    }

    /// Returns the private directory retained if rollback needs manual recovery.
    public Path getStagingDirectory() {
        return stagingDirectory;
    }

    /// Returns a cleanup failure after successful publication, if any.
    public @Nullable IOException getCleanupFailure() {
        return cleanupFailure;
    }

    /// Records a read and returns an isolated working manifest.
    public synchronized GameInstanceManifest getInstanceManifest(GameInstanceID id) {
        requireOpen();
        readIds.add(id);
        if (removedIds.contains(id)) {
            throw new NoSuchGameInstanceException(id);
        }
        @Nullable String staged = manifests.get(id);
        return staged == null ? baseSnapshot.getInstanceManifest(id)
                : JsonUtils.fromNonNullJson(staged, GameInstanceManifest.class);
    }

    /// Returns a working snapshot while tracking all inherited resolution dependencies.
    public synchronized GameInstanceManifest.Resolved resolve(GameInstanceManifest manifest) {
        requireOpen();
        readIds.add(manifest.id());
        repository.checkDraftInstanceAvailable(manifest.id());
        GameInstanceManifest current = manifest;
        Set<GameInstanceID> visited = new TreeSet<>();
        while (current.inheritsFrom() != null && visited.add(current.id())) {
            current = getInstanceManifest(current.inheritsFrom());
        }
        Map<GameInstanceID, GameInstanceManifest> values = new TreeMap<>();
        for (GameInstanceManifest value : baseSnapshot.getInstanceManifests()) {
            if (!removedIds.contains(value.id())) {
                values.put(value.id(), value);
            }
        }
        manifests.forEach((id, json) -> values.put(id, JsonUtils.fromNonNullJson(json, GameInstanceManifest.class)));
        return new GameRepositorySnapshot(baseSnapshot.getBaseDirectory(), baseSnapshot.getRevision(), values)
                .resolve(manifest);
    }

    /// Stages a manifest with the local JSON adapter, preserving unknown fields.
    public synchronized void put(GameInstanceManifest manifest) throws IOException {
        requireOpen();
        GameInstanceID id = manifest.id();
        repository.checkDraftInstanceAvailable(id);
        readIds.add(id);
        if (!baseSnapshot.hasInstance(id) && !manifests.containsKey(id)
                && Files.exists(repository.getInstanceRoot(id), LinkOption.NOFOLLOW_LINKS)) {
            throw new GameRepositoryDraftConflictException("Unregistered instance directory exists");
        }
        String payload = JsonUtils.GSON.toJson(manifest);
        Path staged = stageFile(repository.getInstanceJson(id));
        Files.writeString(staged, payload);
        manifests.put(id, payload);
        removedIds.remove(id);
    }

    /// Returns an owned output path; callers must finish and close writes before commit.
    public synchronized Path stageFile(Path target) throws IOException {
        requireOpen();
        Path checked = checkedTarget(target);
        @Nullable FileChange existing = files.get(checked);
        if (existing != null && existing.staged() != null) {
            return existing.staged();
        }
        Path staged = stagingDirectory.resolve("prepared").resolve(baseSnapshot.getBaseDirectory().relativize(checked));
        Files.createDirectories(staged.getParent());
        files.put(checked, new FileChange(checked, staged, FileStamp.capture(checked)));
        return staged;
    }

    /// Copies a completed client JAR into owned staging, never retaining a cache-file lease implicitly.
    public synchronized void putPrimaryJar(GameInstanceID id, Path source) throws IOException {
        requireOpen();
        readIds.add(id);
        Path input = source.toAbsolutePath().normalize();
        FileStamp before = FileStamp.capture(input);
        if (!before.exists() || before.directory()) {
            throw new IOException("Client JAR source is not a regular file");
        }
        Path output = stageFile(repository.getInstanceRoot(id).resolve(id.id() + ".jar"));
        Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
        if (!before.equals(FileStamp.capture(input)) || !before.digest().equals(FileStamp.capture(output).digest())) {
            throw new IOException("Client JAR source changed while staging");
        }
    }

    /// Stages one exact managed file removal without deleting its committed contents.
    public synchronized void removeFile(Path target) throws IOException {
        requireOpen();
        Path checked = checkedTarget(target);
        FileStamp original = files.containsKey(checked) ? files.get(checked).expected() : FileStamp.capture(checked);
        files.put(checked, new FileChange(checked, null, original));
    }

    /// Removes an index entry; filesystem changes must be staged explicitly by its owner.
    public synchronized void remove(GameInstanceID id) {
        requireOpen();
        repository.checkDraftInstanceAvailable(id);
        readIds.add(id);
        manifests.remove(id);
        removedIds.add(id);
    }

    /// Joins owner-managed state to this transaction; duplicate participant objects are rejected.
    public synchronized void enlist(GameRepositoryDraftParticipant participant) {
        requireOpen();
        Objects.requireNonNull(participant, "participant");
        if (participants.stream().anyMatch(existing -> existing == participant)) {
            throw new IllegalArgumentException("Participant already enlisted");
        }
        participants.add(participant);
    }

    /// Requests cancellation before the final publication point.
    /// @return false if publication already won the cancellation race
    public synchronized boolean requestCancellation() {
        if (state == State.COMMITTED || state == State.ABORTED || state == State.FAILED) {
            return false;
        }
        cancellationRequested = true;
        return true;
    }

    /// Returns an immutable declaration for the independently resourced commit continuation.
    public synchronized @Unmodifiable List<TaskResource> getCommitResources() {
        List<TaskResource> resources = new ArrayList<>();
        resources.add(TaskResource.repositoryMetadata(baseSnapshot.getBaseDirectory()));
        readIds.forEach(id -> resources.add(TaskResource.gameInstance(repository.getInstanceRoot(id))));
        files.keySet().forEach(path -> resources.add(TaskResource.downloadTarget(path)));
        return List.copyOf(resources);
    }

    /// Validates, applies and publishes once; restores attempted changes on every Exception or Error.
    /// Cleanup failure after publication is available separately through getCleanupFailure().
    public GameRepositorySnapshot commit() throws IOException {
        synchronized (this) {
            requireOpen();
            checkCancellation();
            state = State.COMMITTING;
        }
        List<AppliedFile> applied = new ArrayList<>();
        List<GameRepositoryDraftParticipant> attempted = new ArrayList<>();
        List<Path> createdDirectories = new ArrayList<>();
        boolean locked = false;
        try {
            repository.lockDraftCommit();
            locked = true;
            checkCancellation();
            validateReadSet();
            for (GameRepositoryDraftParticipant participant : participants) {
                participant.validate();
            }
            for (FileChange change : files.values()) {
                checkCancellation();
                applyFile(change, applied, createdDirectories);
            }
            for (GameRepositoryDraftParticipant participant : participants) {
                checkCancellation();
                attempted.add(participant);
                participant.apply();
            }
            synchronized (this) {
                checkCancellation();
                GameRepositorySnapshot published = repository.publishDraft(this);
                state = State.COMMITTED;
                try {
                    deleteOwnedStaging();
                } catch (IOException failure) {
                    cleanupFailure = failure;
                }
                return published;
            }
        } catch (Throwable failure) {
            boolean interrupted = Thread.interrupted();
            boolean restored = rollback(attempted, applied, createdDirectories, failure);
            state = State.FAILED;
            if (restored) {
                try {
                    deleteOwnedStaging();
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            } else {
                repository.markDraftRecoveryRequired(readIds);
            }
            if (interrupted || cleanupInterrupted || failure instanceof InterruptedException
                    || failure instanceof java.io.InterruptedIOException) {
                Thread.currentThread().interrupt();
            }
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure instanceof IOException io) {
                throw io;
            }
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException("Draft commit failed", failure);
        } finally {
            if (locked) repository.unlockDraftCommit();
        }
    }

    /// Discards an open draft; it cannot undo a committed or in-progress transaction.
    public synchronized void abort() throws IOException {
        if (state == State.ABORTED || state == State.FAILED) {
            return;
        }
        if (state != State.OPEN) throw new IllegalStateException("Draft cannot be aborted: " + state);
        state = State.ABORTED;
        deleteOwnedStaging();
    }

    /// Aborts an uncommitted open draft; terminal closes are idempotent and retain recovery backups.
    @Override
    public synchronized void close() throws IOException {
        if (state == State.OPEN) {
            abort();
        } else if (state == State.COMMITTING) {
            throw new IllegalStateException("Draft commit is still in progress");
        }
    }

    /// Verifies captured dependencies and file identities before changing any committed file.
    private void validateReadSet() throws IOException {
        GameRepositorySnapshot current = repository.getSnapshot();
        if (!baseSnapshot.getBaseDirectory().equals(current.getBaseDirectory())
                || !Objects.equals(rootKey, Files.readAttributes(current.getBaseDirectory(), BasicFileAttributes.class).fileKey())) {
            throw new GameRepositoryDraftConflictException("Repository root changed");
        }
        for (GameInstanceID id : readIds) {
            repository.checkDraftInstanceAvailable(id);
            if (!Objects.equals(baseSnapshot.payload(id), current.payload(id))) {
                throw new GameRepositoryDraftConflictException("Instance changed: " + id.id());
            }
        }
        for (Map.Entry<GameInstanceID, String> manifest : manifests.entrySet()) {
            @Nullable FileChange output = files.get(repository.getInstanceJson(manifest.getKey()).toAbsolutePath().normalize());
            if (output == null || output.staged() == null
                    || !manifest.getValue().equals(Files.readString(output.staged()))) {
                throw new IOException("Prepared manifest changed or is incomplete");
            }
            if (!baseSnapshot.hasInstance(manifest.getKey())
                    && Files.exists(repository.getInstanceRoot(manifest.getKey()), LinkOption.NOFOLLOW_LINKS)) {
                throw new GameRepositoryDraftConflictException("New instance root appeared during preparation");
            }
        }
        for (FileChange change : files.values()) {
            checkedTarget(change.target());
            if (!change.expected().equals(FileStamp.capture(change.target()))) {
                throw new GameRepositoryDraftConflictException("Managed target changed");
            }
            if (change.staged() != null && !Files.isRegularFile(change.staged(), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Staged output is incomplete");
            }
        }
    }

    /// Rechecks metadata immediately before publication; file changes remain owned by the commit lease.
    synchronized void validatePublication(GameRepositorySnapshot current) throws IOException {
        if (!baseSnapshot.getBaseDirectory().equals(current.getBaseDirectory())) {
            throw new GameRepositoryDraftConflictException("Repository root changed before publication");
        }
        for (GameInstanceID id : readIds) {
            if (!Objects.equals(baseSnapshot.payload(id), current.payload(id))) {
                throw new GameRepositoryDraftConflictException("Instance changed before publication: " + id.id());
            }
        }
    }

    /// Returns independently decoded staged manifests for one rebased publication.
    synchronized @Unmodifiable Map<GameInstanceID, GameInstanceManifest> stagedManifests() {
        Map<GameInstanceID, GameInstanceManifest> copy = new TreeMap<>();
        manifests.forEach((id, json) -> copy.put(id, JsonUtils.fromNonNullJson(json, GameInstanceManifest.class)));
        return Collections.unmodifiableMap(copy);
    }

    /// Returns immutable identifiers removed by this draft.
    synchronized @Unmodifiable Set<GameInstanceID> removedIds() {
        return Collections.unmodifiableSet(new TreeSet<>(removedIds));
    }

    /// Applies one prepared file while preserving the original relative filename in recovery storage.
    private void applyFile(FileChange change, List<AppliedFile> applied, List<Path> createdDirectories) throws IOException {
        Path target = change.target();
        if (!change.expected().equals(FileStamp.capture(target))) {
            throw new GameRepositoryDraftConflictException("Target changed before application");
        }
        createParents(target.getParent(), createdDirectories);
        @Nullable Path backup = null;
        if (change.expected().exists()) {
            backup = stagingDirectory.resolve("backup").resolve(baseSnapshot.getBaseDirectory().relativize(target));
            Files.createDirectories(backup.getParent());
            move(target, backup);
        }
        AppliedFile record = new AppliedFile(target, backup);
        applied.add(record);
        if (change.staged() != null) {
            move(change.staged(), target);
            record.written = FileStamp.capture(target);
        }
    }

    /// Restores participants and files in reverse order without deleting the sole recovery copy.
    private boolean rollback(List<GameRepositoryDraftParticipant> attempted, List<AppliedFile> applied,
                             List<Path> createdDirectories, Throwable original) {
        boolean restored = true;
        for (int i = attempted.size() - 1; i >= 0; i--) {
            try {
                attempted.get(i).rollback();
            } catch (Throwable failure) {
                cleanupInterrupted |= Thread.interrupted() || failure instanceof InterruptedException
                        || failure instanceof java.io.InterruptedIOException;
                if (failure != original) original.addSuppressed(failure);
                restored = false;
            }
        }
        for (int i = applied.size() - 1; i >= 0; i--) {
            AppliedFile record = applied.get(i);
            try {
                if (Files.exists(record.target, LinkOption.NOFOLLOW_LINKS)) {
                    if (record.written == null || !record.written.equals(FileStamp.capture(record.target))) {
                        throw new IOException("Target changed after draft write; recovery backup retained");
                    }
                    Files.delete(record.target);
                }
                if (record.backup != null) move(record.backup, record.target);
            } catch (Throwable failure) {
                cleanupInterrupted |= Thread.interrupted() || failure instanceof InterruptedException
                        || failure instanceof java.io.InterruptedIOException;
                if (failure != original) original.addSuppressed(failure);
                restored = false;
            }
        }
        for (int i = createdDirectories.size() - 1; i >= 0; i--) {
            try {
                Files.delete(createdDirectories.get(i));
            } catch (IOException failure) {
                original.addSuppressed(failure);
                restored = false;
            }
        }
        return restored;
    }

    /// Creates only absent ancestors and remembers exactly which directories this draft owns.
    private void createParents(Path directory, List<Path> created) throws IOException {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        createParents(Objects.requireNonNull(directory.getParent()), created);
        Files.createDirectory(directory);
        created.add(directory);
    }

    /// Rejects targets outside this repository or inside its private transaction namespace.
    private Path checkedTarget(Path target) throws IOException {
        Path path = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
        Path root = baseSnapshot.getBaseDirectory();
        if (path.equals(root) || !path.startsWith(root) || path.startsWith(root.resolve(".xyml/repository-drafts"))) {
            throw new IOException("Target is outside the managed repository");
        }
        rejectLinks(root, path);
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("A file target cannot replace a directory");
        }
        return path;
    }

    /// Rejects every symbolic-link component beneath the selected repository root.
    private static void rejectLinks(Path root, Path path) throws IOException {
        Path current = root;
        for (Path component : root.relativize(path)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) throw new IOException("Managed path contains a symbolic link");
        }
    }

    /// Deletes only this draft's checked private subtree, deepest entries first without following links.
    private void deleteOwnedStaging() throws IOException {
        Path parent = baseSnapshot.getBaseDirectory().resolve(".xyml/repository-drafts");
        if (!stagingDirectory.getParent().equals(parent) || Files.isSymbolicLink(parent)
                || Files.isSymbolicLink(stagingDirectory)) throw new IOException("Staging ownership changed");
        if (!Files.exists(stagingDirectory, LinkOption.NOFOLLOW_LINKS)) return;
        rejectLinks(baseSnapshot.getBaseDirectory(), stagingDirectory);
        BasicFileAttributes current = Files.readAttributes(stagingDirectory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!Objects.equals(stagingKey, current.fileKey())
                || stagingKey == null && !stagingCreation.equals(current.creationTime())
                || !stagingDirectory.toRealPath().startsWith(baseSnapshot.getBaseDirectory().toRealPath())) {
            throw new IOException("Staging identity changed; contents retained");
        }
        try (var stream = Files.walk(stagingDirectory)) {
            for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    /// Moves without replacement; targets must be absent so a late external file is never overwritten.
    private static void move(Path source, Path target) throws IOException {
        Files.move(source, target);
    }

    /// Rejects use outside the mutation state.
    private void requireOpen() {
        if (state != State.OPEN) throw new IllegalStateException("Draft is not open: " + state);
        checkCancellation();
    }

    /// Observes explicit cancellation and the worker's interruption without clearing it.
    private synchronized void checkCancellation() {
        if (cancellationRequested || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Draft cancelled before publication");
        }
    }

    /// Prepared exact-file action and its captured precondition.
    @NotNullByDefault
    private record FileChange(Path target, @Nullable Path staged, FileStamp expected) {
    }

    /// Rollback ownership recorded immediately after moving an original file into backup.
    @NotNullByDefault
    private static final class AppliedFile {
        /// Managed destination.
        private final Path target;
        /// Original content, or null for a new file.
        private final @Nullable Path backup;
        /// Identity of the file installed by this draft, or null before installation.
        private @Nullable FileStamp written;
        /// Captures recovery locations before attempting replacement.
        private AppliedFile(Path target, @Nullable Path backup) {
            this.target = target;
            this.backup = backup;
        }
    }

    /// File identity and content used to detect external replacement or mutation.
    @NotNullByDefault
    private record FileStamp(boolean exists, boolean directory, @Nullable Object key, long size,
                             long modified, String digest) {
        /// Captures absence or no-follow attributes and a regular file's SHA-256.
        private static FileStamp capture(Path path) throws IOException {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                return new FileStamp(false, false, null, 0L, 0L, "");
            }
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() && !attributes.isRegularFile()) {
                throw new IOException("Managed path is not a regular file or directory");
            }
            return new FileStamp(true, attributes.isDirectory(), attributes.fileKey(), attributes.size(),
                    attributes.lastModifiedTime().toMillis(), attributes.isDirectory() ? "" : hash(path, attributes.size()));
        }

        /// Hashes a bounded-buffer stream without retaining the file bytes in memory.
        private static String hash(Path path, long expectedSize) throws IOException {
            final MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException failure) {
                throw new AssertionError("SHA-256 unavailable", failure);
            }
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                long remaining = expectedSize;
                while (remaining > 0) {
                    if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Hash interrupted");
                    int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (count <= 0) throw new IOException("File changed or hash made no progress");
                    digest.update(buffer, 0, count);
                    remaining -= count;
                }
                if (input.read() >= 0) throw new IOException("File grew while hashing");
            }
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
