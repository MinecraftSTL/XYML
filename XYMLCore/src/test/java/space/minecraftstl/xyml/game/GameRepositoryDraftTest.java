// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.game;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskExecutor;
import space.minecraftstl.xyml.task.TaskListener;
import space.minecraftstl.xyml.task.TaskResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/// Exercises complete draft preparation, conflict, publication and recovery through real temporary files.
@NotNullByDefault
public final class GameRepositoryDraftTest {
    /// Two independently prepared instances are rebased rather than replacing each other's index.
    @Test
    void rebasesIndependentDrafts(@TempDir Path directory) throws IOException {
        DefaultGameRepository repository = new DefaultGameRepository(directory);
        try (GameRepositoryDraft first = repository.openDraft(); GameRepositoryDraft second = repository.openDraft()) {
            first.put(new GameInstanceManifest(new GameInstanceID("first")));
            second.put(new GameInstanceManifest(new GameInstanceID("second")));
            assertEquals(0, repository.getInstanceCount());
            first.commit();
            second.commit();
            assertEquals(2, repository.getInstanceCount());
            assertTrue(Files.isRegularFile(directory.resolve("versions/first/first.json")));
            assertTrue(Files.isRegularFile(directory.resolve("versions/second/second.json")));
        }
    }

    /// Conflicting writes fail before overwriting the first committed version.
    @Test
    void rejectsStaleManifest(@TempDir Path directory) throws IOException {
        DefaultGameRepository repository = new DefaultGameRepository(directory);
        GameInstanceID id = new GameInstanceID("same");
        try (GameRepositoryDraft first = repository.openDraft(); GameRepositoryDraft stale = repository.openDraft()) {
            first.put(new GameInstanceManifest(id).withMainClass("first.Main"));
            stale.put(new GameInstanceManifest(id).withMainClass("stale.Main"));
            first.commit();
            assertThrows(GameRepositoryDraftConflictException.class, stale::commit);
            assertEquals("first.Main", repository.getSnapshot().getInstanceManifest(id).mainClass());
        }
    }

    /// Mutable JSON returned to a caller cannot modify an old or current snapshot.
    @Test
    void preservesUnknownFieldsWithoutAliases(@TempDir Path directory) throws IOException {
        DefaultGameRepository repository = new DefaultGameRepository(directory);
        JsonObject json = new JsonObject();
        json.addProperty("id", "unknown");
        json.addProperty("custom", "retained");
        GameInstanceManifest manifest = GameInstanceManifest.fromJson(json, true);
        try (GameRepositoryDraft draft = repository.openDraft()) {
            draft.put(manifest);
            draft.commit();
        }
        GameRepositorySnapshot snapshot = repository.getSnapshot();
        snapshot.getInstanceManifest(manifest.id()).rawJson().addProperty("custom", "mutated");
        assertEquals("retained", snapshot.getInstanceManifest(manifest.id()).rawJson().get("custom").getAsString());
        assertTrue(Files.readString(repository.getInstanceJson(manifest.id())).contains("retained"));
    }

    /// A late participant failure rolls back every earlier managed file and metadata change.
    @Test
    void rollsBackWholeAttempt(@TempDir Path directory) throws IOException {
        DefaultGameRepository repository = new DefaultGameRepository(directory);
        GameInstanceID id = seed(repository, "original");
        Path json = repository.getInstanceJson(id);
        String before = Files.readString(json);
        Path configuration = repository.getInstanceRoot(id).resolve("options.txt");
        Files.writeString(configuration, "before");
        try (GameRepositoryDraft draft = repository.openDraft()) {
            draft.put(repository.getSnapshot().getInstanceManifest(id).withMainClass("after.Main"));
            Files.writeString(draft.stageFile(configuration), "after");
            draft.enlist(new FailingParticipant());
            assertThrows(IOException.class, draft::commit);
            assertEquals(before, Files.readString(json));
            assertEquals("before", Files.readString(configuration));
            assertNotEquals("after.Main", repository.getInstanceManifest(id).mainClass());
            assertFalse(Files.exists(draft.getStagingDirectory()));
        }
    }

    /// External file changes reject the transaction without restoring over the external writer.
    @Test
    void rejectsChangedTarget(@TempDir Path directory) throws IOException {
        DefaultGameRepository repository = new DefaultGameRepository(directory);
        Path target = directory.resolve("owned.txt");
        Files.writeString(target, "before");
        try (GameRepositoryDraft draft = repository.openDraft()) {
            Files.writeString(draft.stageFile(target), "prepared");
            Files.writeString(target, "external");
            assertThrows(GameRepositoryDraftConflictException.class, draft::commit);
            assertEquals("external", Files.readString(target));
        }
    }

    /// Cancelling or closing an uncommitted draft never materializes instance files.
    @Test
    void abortIsIdempotentAndCommitIsSingleUse(@TempDir Path directory) throws IOException {
        DefaultGameRepository repository = new DefaultGameRepository(directory);
        GameRepositoryDraft draft = repository.openDraft();
        draft.put(new GameInstanceManifest(new GameInstanceID("cancelled")));
        draft.close();
        draft.close();
        draft.abort();
        assertFalse(Files.exists(directory.resolve("versions/cancelled")));
        try (GameRepositoryDraft success = repository.openDraft()) {
            success.put(new GameInstanceManifest(new GameInstanceID("success")));
            success.commit();
            assertFalse(success.requestCancellation());
            assertThrows(IllegalStateException.class, success::commit);
            assertThrows(IllegalStateException.class, success::abort);
        }
    }

    /// Cancellation requested by validation is observed before applying the prepared manifest.
    @Test
    void cancelsBeforePublication(@TempDir Path directory) throws IOException {
        DefaultGameRepository repository = new DefaultGameRepository(directory);
        try (GameRepositoryDraft draft = repository.openDraft()) {
            draft.put(new GameInstanceManifest(new GameInstanceID("cancelled")));
            draft.requestCancellation();
            assertThrows(CancellationException.class, draft::commit);
            assertFalse(Files.exists(directory.resolve("versions/cancelled")));
        }
    }

    /// The real stopped task entry prepares only after start and publishes before reporting success.
    @Test
    void rootTaskPublishesOnce(@TempDir Path directory) throws Exception {
        DefaultGameRepository repository = new DefaultGameRepository(directory);
        GameInstanceID id = new GameInstanceID("task");
        Task<String> task = repository.runDraftAsync(draft -> Task.supplyAsync(() -> {
            draft.put(new GameInstanceManifest(id));
            assertFalse(repository.hasInstance(id));
            return "prepared";
        }).setResources(TaskResource.repositoryOperation(directory)));
        assertFalse(Files.exists(directory.resolve(".xyml")));
        Completion completion = new Completion(task);
        TaskExecutor executor = task.executor(completion);
        executor.start();
        assertEquals("prepared", completion.result.get(15, TimeUnit.SECONDS));
        assertTrue(repository.hasInstance(id));
    }

    /// A rollback failure preserves original bytes and prevents further use of the affected instance.
    @Test
    void retainsRecoveryBackup(@TempDir Path directory) throws IOException {
        DefaultGameRepository repository = new DefaultGameRepository(directory);
        GameInstanceID id = seed(repository, "retained");
        Path json = repository.getInstanceJson(id);
        String original = Files.readString(json);
        try (GameRepositoryDraft draft = repository.openDraft()) {
            draft.put(repository.getInstanceManifest(id).withMainClass("replacement.Main"));
            draft.enlist(new InterferingParticipant(json));
            IOException failure = assertThrows(IOException.class, draft::commit);
            assertTrue(failure.getSuppressed().length > 0);
            Path backup = draft.getStagingDirectory().resolve("backup/versions/retained/retained.json");
            assertEquals(original, Files.readString(backup));
            assertEquals("external", Files.readString(json));
            assertThrows(IllegalStateException.class, () -> repository.getResolvedInstanceManifest(id));
        }
    }

    /// An unrelated directory appearing under a new identifier is never claimed or overwritten.
    @Test
    void rejectsUnregisteredDirectory(@TempDir Path directory) throws IOException {
        DefaultGameRepository repository = new DefaultGameRepository(directory);
        GameInstanceID id = new GameInstanceID("foreign");
        Path instance = repository.getInstanceRoot(id);
        Files.createDirectories(instance);
        Files.writeString(instance.resolve("user-data"), "retain");
        try (GameRepositoryDraft draft = repository.openDraft()) {
            assertThrows(GameRepositoryDraftConflictException.class, () -> draft.put(new GameInstanceManifest(id)));
            assertEquals("retain", Files.readString(instance.resolve("user-data")));
        }
    }

    /// Replaces an applied file as an external writer, forcing preservation rather than unsafe restore.
    @NotNullByDefault
    private static final class InterferingParticipant implements GameRepositoryDraftParticipant {
        /// Exact fixture target changed after application.
        private final Path target;

        /// Captures only the temporary fixture path.
        private InterferingParticipant(Path target) {
            this.target = target;
        }

        /// This fixture has no validation-side effects.
        @Override
        public void validate() {
        }

        /// Simulates an external modification followed by a failing owner operation.
        @Override
        public void apply() throws IOException {
            Files.writeString(target, "external");
            throw new IOException("injected owner failure");
        }

        /// The fixture does not own the external writer and cannot undo it.
        @Override
        public void rollback() {
        }
    }

    /// Creates one committed fixture through the same draft API used by operations.
    private static GameInstanceID seed(DefaultGameRepository repository, String name) throws IOException {
        GameInstanceID id = new GameInstanceID(name);
        try (GameRepositoryDraft draft = repository.openDraft()) {
            draft.put(new GameInstanceManifest(id));
            draft.commit();
        }
        return id;
    }

    /// Deliberately fails after prior files have been applied.
    @NotNullByDefault
    private static final class FailingParticipant implements GameRepositoryDraftParticipant {
        /// Validation has no side effect.
        @Override
        public void validate() {
        }

        /// Produces an injected persistence failure.
        @Override
        public void apply() throws IOException {
            throw new IOException("injected participant failure");
        }

        /// This fixture owns no mutable settings.
        @Override
        public void rollback() {
        }
    }

    /// Captures the executor's terminal result without timing-based polling.
    @NotNullByDefault
    private static final class Completion extends TaskListener {
        /// Root task whose result is available after success.
        private final Task<String> task;
        /// One terminal outcome awaited with a finite test deadline.
        private final CompletableFuture<String> result = new CompletableFuture<>();
        /// Binds a stopped root task to this listener.
        private Completion(Task<String> task) {
            this.task = task;
        }

        /// Completes only after all executor-managed recovery children stop.
        @Override
        public void onStop(boolean success, TaskExecutor executor) {
            if (success) {
                result.complete(task.getResult());
            } else {
                result.completeExceptionally(new AssertionError("Transaction failed", executor.getException()));
            }
        }
    }
}
