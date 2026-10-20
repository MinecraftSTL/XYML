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
package space.minecraftstl.xyml.ui.swing.page.schematics;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;
import space.minecraftstl.xyml.util.io.DeletionMode;
import space.minecraftstl.xyml.util.io.TrashMoveException;
import space.minecraftstl.xyml.util.io.TrashOperations;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the recycle-bin branch of schematic mutation IO.
@NotNullByDefault
final class FileSystemSchematicMutationIoRecycleTest {
    /// Temporary test root.
    @TempDir
    private Path temporaryDirectory;

    /// Recycle-bin mode moves the original child without creating an isolation path.
    @Test
    void movesOriginalChildToTrashWithoutIsolation() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.resolve("schematics"));
        Path file = Files.writeString(root.resolve("item.litematic"), "fixture");
        Path trash = temporaryDirectory.resolve("trash");
        FileSystemSchematicMutationIo io = mutationIo(root, new RecordingTrashOperations(trash, false));

        io.delete(root, entry(file), DeletionMode.RECYCLE_BIN_FIRST, new LoadCancellation());

        assertFalse(Files.exists(file));
        assertTrue(Files.exists(trash.resolve("item.litematic")));
    }

    /// Refused recycle-bin movement leaves the original child intact.
    @Test
    void preservesOriginalChildWhenTrashRefusesMove() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.resolve("failed-schematics"));
        Path file = Files.writeString(root.resolve("item.litematic"), "fixture");
        FileSystemSchematicMutationIo io = mutationIo(
                root,
                new RecordingTrashOperations(temporaryDirectory.resolve("trash"), true));

        TrashMoveException failure = assertThrows(
                TrashMoveException.class,
                () -> io.delete(root, entry(file), DeletionMode.RECYCLE_BIN_FIRST, new LoadCancellation()));

        assertEquals(file.toAbsolutePath().normalize(), failure.failedPaths().get(0));
        assertTrue(Files.exists(file));
    }

    /// Creates one mutation IO with a deterministic recycle-bin boundary.
    ///
    /// @param root normalized root
    /// @param trash recycle-bin substitute
    /// @return production mutation IO with test boundaries
    private static FileSystemSchematicMutationIo mutationIo(Path root, TrashOperations trash) {
        return new FileSystemSchematicMutationIo(
                root,
                (source, destination) -> Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE),
                temporary -> { },
                isolated -> { },
                DefaultSchematicBrowserModel.FileIdentity::capture,
                trash);
    }

    /// Creates the exact stable descriptor for one regular file.
    ///
    /// @param file regular file
    /// @return matching discovered entry
    /// @throws IOException when attributes cannot be read
    private static DefaultSchematicBrowserModel.DiscoveredEntry entry(Path file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                file,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        return new DefaultSchematicBrowserModel.DiscoveredEntry(
                file.toAbsolutePath().normalize(),
                file.getFileName().toString(),
                false,
                DefaultSchematicBrowserModel.FileIdentity.capture(attributes));
    }

    /// Moves accepted files into a temporary trash directory or refuses every move.
    @NotNullByDefault
    private static final class RecordingTrashOperations implements TrashOperations {
        /// Temporary trash destination.
        private final Path trashDirectory;

        /// Whether every move should fail.
        private final boolean refuseMoves;

        /// Creates one deterministic recycle-bin substitute.
        ///
        /// @param trashDirectory temporary trash destination
        /// @param refuseMoves whether all moves should fail
        private RecordingTrashOperations(Path trashDirectory, boolean refuseMoves) {
            this.trashDirectory = trashDirectory;
            this.refuseMoves = refuseMoves;
        }

        /// Reports a supported recycle-bin boundary.
        @Override
        public boolean isSupported() {
            return true;
        }

        /// Moves one file unless configured to refuse all moves.
        @Override
        public boolean moveToTrash(Path path) {
            if (refuseMoves) {
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
