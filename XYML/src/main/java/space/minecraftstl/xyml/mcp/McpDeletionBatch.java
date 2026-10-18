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
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.util.io.DeletionMode;
import space.minecraftstl.xyml.util.io.TrashMoveException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/// Serial recycle-bin-first deletion with category confirmation and separately authorized permanent retries.
@NotNullByDefault
final class McpDeletionBatch {
    /// Original identities captured before any dialog opens.
    private final @Unmodifiable List<McpPathSnapshot> targets;
    /// Category and instance presented to the user.
    private final McpDeletionConfirmation.DeletionRequest request;
    /// Launcher-owned authorization boundary.
    private final McpDeletionConfirmation confirmation;

    /// Captures every target before allowing any destructive operation.
    /// @param root managed directory
    /// @param paths normalized independent targets
    /// @param request category and count
    /// @param confirmation interactive authorization boundary
    /// @throws IOException when any target is invalid
    McpDeletionBatch(Path root, List<Path> paths, McpDeletionConfirmation.DeletionRequest request,
                     McpDeletionConfirmation confirmation) throws IOException {
        List<McpPathSnapshot> captured = new ArrayList<>();
        for (Path path : paths) {
            captured.add(McpPathSnapshot.capture(root, path));
        }
        targets = List.copyOf(captured);
        this.request = request;
        this.confirmation = confirmation;
    }

    /// Runs one batch, retaining partial progress and never downgrading an unrelated I/O error.
    /// @param mutation existing storage-specific single-target deletion
    /// @param checkpoint cancellation and instance-freshness check
    /// @param askOrdinary whether the ordinary category confirmation has not already been obtained
    /// @return immutable approval, removed, remaining, completion, and failure details
    /// @throws Exception when preparation, cancellation, or authorization fails
    @Unmodifiable Map<String, Object> run(Mutation mutation, Runnable checkpoint, boolean askOrdinary) throws Exception {
        checkpoint.run();
        if (askOrdinary && !XYMLMcpService.withSettingsSaveBarrier(() -> confirmation.confirm(request))) {
            return result(false, List.of(), targets, List.of());
        }
        checkpoint.run();
        for (McpPathSnapshot target : targets) {
            target.verify();
        }
        List<String> removed = new ArrayList<>();
        List<McpPathSnapshot> retryable = new ArrayList<>();
        List<McpPathSnapshot> remaining = new ArrayList<>();
        List<Map<String, Object>> failures = new ArrayList<>();
        for (McpPathSnapshot target : targets) {
            checkpoint.run();
            try {
                target.verify();
                mutation.delete(target.path(), DeletionMode.RECYCLE_BIN_FIRST);
                removed.add(target.path().toString());
            } catch (CancellationException failure) {
                throw failure;
            } catch (IOException | RuntimeException failure) {
                if (Files.notExists(target.path(), LinkOption.NOFOLLOW_LINKS)) {
                    removed.add(target.path().toString());
                    failures.add(failure(target.path(), failure));
                } else if (pureTrashFailure(failure)) {
                    retryable.add(target);
                } else {
                    remaining.add(target);
                    failures.add(failure(target.path(), failure));
                }
            }
        }
        if (!retryable.isEmpty()) {
            checkpoint.run();
            McpDeletionConfirmation.DeletionRequest retry = new McpDeletionConfirmation.DeletionRequest(
                    request.kind(), request.instanceId(), retryable.size());
            boolean approved = confirmation.confirmPermanent(retry);
            checkpoint.run();
            for (McpPathSnapshot target : retryable) {
                checkpoint.run();
                if (!approved) {
                    remaining.add(target);
                    continue;
                }
                try {
                    target.verify();
                    mutation.delete(target.path(), DeletionMode.PERMANENT);
                    removed.add(target.path().toString());
                } catch (CancellationException failure) {
                    throw failure;
                } catch (IOException | RuntimeException failure) {
                    if (Files.notExists(target.path(), LinkOption.NOFOLLOW_LINKS)) {
                        removed.add(target.path().toString());
                    } else {
                        remaining.add(target);
                    }
                    failures.add(failure(target.path(), failure));
                }
            }
        }
        return result(true, removed, remaining, failures);
    }

    /// Distinguishes actual recycle-bin refusal from aggregated unrelated storage failures.
    /// @param failure observed failure
    /// @return whether every constituent failure is a recycle-bin failure
    private static boolean pureTrashFailure(Throwable failure) {
        if (!(failure instanceof TrashMoveException)) {
            return false;
        }
        if (failure.getCause() != null && !pureTrashFailure(failure.getCause())) {
            return false;
        }
        for (Throwable suppressed : failure.getSuppressed()) {
            if (!pureTrashFailure(suppressed)) {
                return false;
            }
        }
        return true;
    }

    /// Creates a JSON-compatible error retaining the affected target.
    /// @param path affected target
    /// @param failure underlying storage error
    /// @return immutable failure entry
    private static @Unmodifiable Map<String, Object> failure(Path path, Exception failure) {
        return Map.of("path", path.toString(), "error", failure.toString());
    }

    /// Freezes the explicit partial-success outcome.
    /// @param approved ordinary approval
    /// @param removed targets actually removed
    /// @param remaining unresolved original targets
    /// @param failures storage failures, excluding user cancellation
    /// @return immutable result
    private static @Unmodifiable Map<String, Object> result(boolean approved, List<String> removed,
            List<McpPathSnapshot> remaining, List<Map<String, Object>> failures) {
        return Map.of("approved", approved, "removed", List.copyOf(removed),
                "remaining", remaining.stream().map(target -> target.path().toString()).toList(),
                "completed", approved && remaining.isEmpty() && failures.isEmpty(), "failures", List.copyOf(failures));
    }

    /// Existing storage operation receiving a target and explicit mode.
    @NotNullByDefault
    @FunctionalInterface
    interface Mutation {
        /// Deletes one target through its existing storage boundary.
        /// @param path captured target
        /// @param mode authorized deletion mode
        /// @throws IOException when mutation fails
        void delete(Path path, DeletionMode mode) throws IOException;
    }
}
