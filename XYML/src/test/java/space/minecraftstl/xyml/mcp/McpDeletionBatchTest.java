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
package space.minecraftstl.xyml.mcp;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.util.io.DeletionMode;
import space.minecraftstl.xyml.util.io.TrashMoveException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Deterministic deletion-policy tests never use the host recycle bin or delete user data.
@NotNullByDefault
final class McpDeletionBatchTest {
    /// Cancellation of the ordinary dialog leaves every file untouched.
    @Test
    void deniedBatchHasNoSideEffects(@TempDir Path root) throws Exception {
        Path file = Files.writeString(root.resolve("one"), "one");
        AtomicInteger mutations = new AtomicInteger();
        Map<String, Object> result = batch(root, List.of(file), request -> false)
                .run((path, mode) -> mutations.incrementAndGet(), () -> { }, true);
        assertEquals(false, result.get("approved"));
        assertEquals(List.of(file.toString()), result.get("remaining"));
        assertEquals(0, mutations.get());
        assertTrue(Files.exists(file));
    }

    /// Every category uses recycle-bin mode first, including existing instance and mod operations.
    @Test
    void everyFileCategoryPrefersRecycleBin(@TempDir Path root) throws Exception {
        for (McpDeletionConfirmation.DeletionKind kind : McpDeletionConfirmation.DeletionKind.values()) {
            if (kind == McpDeletionConfirmation.DeletionKind.SERVERS) {
                continue;
            }
            Path file = Files.writeString(root.resolve(kind.name()), "fixture");
            McpDeletionBatch batch = new McpDeletionBatch(root, List.of(file),
                    new McpDeletionConfirmation.DeletionRequest(kind, new GameInstanceID("demo"), 1), request -> true);
            Map<String, Object> result = batch.run((path, mode) -> {
                assertEquals(DeletionMode.RECYCLE_BIN_FIRST, mode);
                Files.move(path, root.resolve(kind.name() + ".recycled"));
            }, () -> { }, true);
            assertEquals(true, result.get("completed"));
            assertEquals(List.of(file.toString()), result.get("removed"));
        }
    }

    /// A denied permanent retry retains failed targets and reports successful recycle-bin moves.
    @Test
    void deniedFallbackRetainsPartialSuccess(@TempDir Path root) throws Exception {
        Path one = Files.writeString(root.resolve("one"), "one");
        Path two = Files.writeString(root.resolve("two"), "two");
        List<String> calls = new ArrayList<>();
        Map<String, Object> result = batch(root, List.of(one, two), policy(false, new AtomicInteger()))
                .run((path, mode) -> {
                    calls.add(path + ":" + mode);
                    if (path.equals(two)) {
                        throw new TrashMoveException(List.of(two));
                    }
                    Files.move(one, root.resolve("recycled"));
                }, () -> { }, true);
        assertEquals(List.of(one.toString()), result.get("removed"));
        assertEquals(List.of(two.toString()), result.get("remaining"));
        assertEquals(false, result.get("completed"));
        assertEquals(2, calls.size());
        assertTrue(Files.exists(two));
    }

    /// Only actual recycle-bin refusals are eligible for a permanent retry.
    @Test
    void retriesOnlyTrashFailuresAndPreservesOtherErrors(@TempDir Path root) throws Exception {
        Path trashFailure = Files.writeString(root.resolve("trash"), "one");
        Path ioFailure = Files.writeString(root.resolve("io"), "two");
        AtomicInteger fallbackDialogs = new AtomicInteger();
        List<Path> permanent = new ArrayList<>();
        Map<String, Object> result = batch(root, List.of(trashFailure, ioFailure), policy(true, fallbackDialogs))
                .run((path, mode) -> {
                    if (mode == DeletionMode.PERMANENT) {
                        permanent.add(path);
                        Files.delete(path);
                    } else if (path.equals(trashFailure)) {
                        throw new TrashMoveException(List.of(path));
                    } else {
                        throw new IOException("disk failure");
                    }
                }, () -> { }, true);
        assertEquals(1, fallbackDialogs.get());
        assertEquals(List.of(trashFailure), permanent);
        assertEquals(List.of(trashFailure.toString()), result.get("removed"));
        assertEquals(List.of(ioFailure.toString()), result.get("remaining"));
        assertEquals(1, ((List<?>) result.get("failures")).size());
    }

    /// Aggregates containing an unrelated storage error must never offer permanent deletion.
    @Test
    void doesNotDowngradeMixedStorageFailures(@TempDir Path root) throws Exception {
        Path file = Files.writeString(root.resolve("one"), "fixture");
        AtomicInteger fallbackDialogs = new AtomicInteger();
        Map<String, Object> result = batch(root, List.of(file), policy(true, fallbackDialogs)).run((path, mode) -> {
            TrashMoveException failure = new TrashMoveException(List.of(path));
            failure.addSuppressed(new IOException("configuration rollback failed"));
            throw failure;
        }, () -> { }, true);
        assertEquals(0, fallbackDialogs.get());
        assertEquals(List.of(file.toString()), result.get("remaining"));
    }

    /// Object replacement while a dialog is open must not authorize deletion of the replacement.
    @Test
    void rejectsReplacementDuringOrdinaryConfirmation(@TempDir Path root) throws Exception {
        Path file = Files.writeString(root.resolve("one"), "original");
        AtomicInteger mutations = new AtomicInteger();
        McpDeletionBatch batch = batch(root, List.of(file), request -> {
            try {
                Files.move(file, root.resolve("original"));
                Files.writeString(file, "replacement");
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
            return true;
        });
        assertThrows(IOException.class, () -> batch.run((path, mode) -> mutations.incrementAndGet(), () -> { }, true));
        assertEquals(0, mutations.get());
        assertEquals("replacement", Files.readString(file));
    }

    /// A replacement between recycle-bin refusal and fallback approval is also retained.
    @Test
    void rejectsReplacementDuringPermanentConfirmation(@TempDir Path root) throws Exception {
        Path file = Files.writeString(root.resolve("one"), "original");
        AtomicInteger mutations = new AtomicInteger();
        McpDeletionConfirmation confirmation = new McpDeletionConfirmation() {
            /// Approves the ordinary deletion.
            @Override
            public boolean confirm(DeletionRequest request) {
                return true;
            }

            /// Replaces the fixture before approving permanent deletion.
            @Override
            public boolean confirmPermanent(DeletionRequest request) {
                try {
                    Files.move(file, root.resolve("old"));
                    Files.writeString(file, "new");
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
                return true;
            }
        };
        Map<String, Object> result = batch(root, List.of(file), confirmation).run((path, mode) -> {
            mutations.incrementAndGet();
            throw new TrashMoveException(List.of(path));
        }, () -> { }, true);
        assertEquals(1, mutations.get());
        assertEquals(List.of(file.toString()), result.get("remaining"));
        assertEquals("new", Files.readString(file));
    }

    /// Cancellation after a dialog closes prevents every later mutation.
    @Test
    void cancellationAfterApprovalPreventsMutation(@TempDir Path root) throws Exception {
        Path file = Files.writeString(root.resolve("one"), "original");
        AtomicBoolean cancelled = new AtomicBoolean();
        McpDeletionBatch batch = batch(root, List.of(file), request -> {
            cancelled.set(true);
            return true;
        });
        assertThrows(CancellationException.class, () -> batch.run((path, mode) -> Files.delete(path), () -> {
            if (cancelled.get()) {
                throw new CancellationException();
            }
        }, true));
        assertTrue(Files.exists(file));
    }

    /// Root and outside targets are rejected before any user authorization or disk mutation.
    @Test
    void rejectsRootAndOutsideTargets(@TempDir Path root) throws Exception {
        assertThrows(IOException.class, () -> batch(root, List.of(root), request -> true));
        Path outside = Files.createTempFile(root.getParent(), "mcp-outside-", ".tmp");
        try {
            assertThrows(IOException.class, () -> batch(root, List.of(outside), request -> true));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    /// Creates one MODS batch with identities captured at construction.
    /// @param root managed root
    /// @param paths fixture targets
    /// @param confirmation scripted decisions
    /// @return prepared batch
    /// @throws IOException when fixture paths cannot be captured
    private static McpDeletionBatch batch(Path root, List<Path> paths, McpDeletionConfirmation confirmation)
            throws IOException {
        return new McpDeletionBatch(root, paths, McpDeletionConfirmation.DeletionRequest.mods(
                new GameInstanceID("demo"), paths.size()), confirmation);
    }

    /// Creates a policy tracking the independent permanent-deletion decision.
    /// @param permanent permanent-deletion approval
    /// @param fallbackCalls independent authorization counter
    /// @return scripted launcher policy
    private static McpDeletionConfirmation policy(boolean permanent, AtomicInteger fallbackCalls) {
        return new McpDeletionConfirmation() {
            /// Approves ordinary deletion.
            @Override
            public boolean confirm(DeletionRequest request) {
                return true;
            }

            /// Records and returns the explicit permanent-deletion choice.
            @Override
            public boolean confirmPermanent(DeletionRequest request) {
                fallbackCalls.incrementAndGet();
                return permanent;
            }
        };
    }
}
