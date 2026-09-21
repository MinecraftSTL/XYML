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
package space.minecraftstl.xyml.util.io;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// Tests recycle-bin-first and permanent deletion contracts.
@NotNullByDefault
public final class DeletionModeTest {
    /// Temporary directory used by each test.
    @TempDir
    private Path temporaryDirectory;

    /// Moves every target without invoking permanent deletion.
    @Test
    public void movesEveryTargetInRecycleBinMode() throws IOException {
        Path file = Files.writeString(temporaryDirectory.resolve("mod.jar"), "content");
        Path directory = Files.createDirectory(temporaryDirectory.resolve("world"));
        Path trash = Files.createDirectory(temporaryDirectory.resolve("trash"));
        RecordingTrashOperations operations = new RecordingTrashOperations(trash, true);

        FileUtils.deleteAllWithMode(List.of(file, directory), DeletionMode.RECYCLE_BIN_FIRST, operations);

        assertFalse(Files.exists(file));
        assertFalse(Files.exists(directory));
        assertTrue(Files.exists(trash.resolve("mod.jar")));
        assertTrue(Files.exists(trash.resolve("world")));
    }

    /// Keeps every failed target and reports the exact unresolved paths.
    @Test
    public void reportsEveryRecycleBinFailure() throws IOException {
        Path first = Files.writeString(temporaryDirectory.resolve("first.jar"), "first");
        Path second = Files.writeString(temporaryDirectory.resolve("second.jar"), "second");
        RecordingTrashOperations operations = new RecordingTrashOperations(
                temporaryDirectory.resolve("trash"), false);

        TrashMoveException failure = failDelete(List.of(first, second), operations);

        assertEquals(List.of(first, second), failure.failedPaths());
        assertTrue(Files.exists(first));
        assertTrue(Files.exists(second));
    }

    /// Preserves successful moves while reporting only the failed members of a batch.
    @Test
    public void reportsOnlyFailedBatchMembers() throws IOException {
        Path successful = Files.writeString(temporaryDirectory.resolve("successful.jar"), "ok");
        Path failed = Files.writeString(temporaryDirectory.resolve("failed.jar"), "locked");
        Path trash = Files.createDirectory(temporaryDirectory.resolve("trash"));
        RecordingTrashOperations operations = new RecordingTrashOperations(trash, true, failed);

        TrashMoveException failure = failDelete(List.of(successful, failed), operations);

        assertEquals(List.of(failed), failure.failedPaths());
        assertFalse(Files.exists(successful));
        assertTrue(Files.exists(trash.resolve("successful.jar")));
        assertTrue(Files.exists(failed));
    }

    /// Treats an already absent target as successfully deleted.
    @Test
    public void treatsMissingTargetAsDeleted() throws IOException {
        Path missing = temporaryDirectory.resolve("missing.jar");
        RecordingTrashOperations operations = new RecordingTrashOperations(
                temporaryDirectory.resolve("trash"), false);

        FileUtils.deleteWithMode(missing, DeletionMode.RECYCLE_BIN_FIRST, operations);

        assertEquals(0, operations.moveCalls);
    }

    /// Uses permanent deletion without consulting the recycle-bin implementation.
    @Test
    public void permanentModeNeverCallsTrash() throws IOException {
        Path target = Files.writeString(temporaryDirectory.resolve("target.jar"), "content");
        RecordingTrashOperations operations = new RecordingTrashOperations(
                temporaryDirectory.resolve("trash"), true);

        FileUtils.deleteWithMode(target, DeletionMode.PERMANENT, operations);

        assertFalse(Files.exists(target));
        assertEquals(0, operations.moveCalls);
    }

    /// Captures the recycle-bin failure from one batch operation.
    private static TrashMoveException failDelete(
            List<Path> paths,
            RecordingTrashOperations operations) throws IOException {
        try {
            FileUtils.deleteAllWithMode(paths, DeletionMode.RECYCLE_BIN_FIRST, operations);
        } catch (TrashMoveException failure) {
            return failure;
        }
        fail("Expected TrashMoveException");
        throw new AssertionError("Unreachable");
    }

    /// Records calls and moves accepted paths into a temporary trash directory.
    @NotNullByDefault
    private static final class RecordingTrashOperations implements TrashOperations {
        /// Destination replacing the platform recycle bin.
        private final Path trashDirectory;

        /// Whether moves should normally succeed.
        private final boolean acceptMoves;

        /// Optional target forced to fail.
        private final @Nullable Path refusedPath;

        /// Number of move attempts.
        private int moveCalls;

        /// Creates an operation that accepts or refuses every move.
        ///
        /// @param trashDirectory temporary trash destination
        /// @param acceptMoves whether moves succeed
        private RecordingTrashOperations(Path trashDirectory, boolean acceptMoves) {
            this(trashDirectory, acceptMoves, null);
        }

        /// Creates an operation that refuses one exact target.
        ///
        /// @param trashDirectory temporary trash destination
        /// @param acceptMoves whether moves normally succeed
        /// @param refusedPath target forced to fail, or null
        private RecordingTrashOperations(
                Path trashDirectory,
                boolean acceptMoves,
                @Nullable Path refusedPath) {
            this.trashDirectory = trashDirectory;
            this.acceptMoves = acceptMoves;
            this.refusedPath = refusedPath;
        }

        /// Reports a deterministic supported capability.
        @Override
        public boolean isSupported() {
            return true;
        }

        /// Moves one accepted path or refuses it without touching the original.
        @Override
        public boolean moveToTrash(Path path) {
            moveCalls++;
            if (!acceptMoves || path.equals(refusedPath)) {
                return false;
            }
            try {
                Files.createDirectories(trashDirectory);
                Files.move(path, trashDirectory.resolve(path.getFileName()));
                return true;
            } catch (IOException exception) {
                return false;
            }
        }
    }
}
