// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.game;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.modpack.UnsupportedModpackException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies manually assembled modpack directory discovery.
@NotNullByDefault
public final class ModpackHelperTest {

    /// Root and nested `.minecraft` layouts return their exact archive subdirectories.
    ///
    /// @param temporaryDirectory temporary fixture directory
    /// @throws IOException if a fixture cannot be written or inspected
    /// @throws UnsupportedModpackException if a supported fixture is not detected
    @Test
    public void locatesSupportedMinecraftDirectories(@TempDir Path temporaryDirectory)
            throws IOException, UnsupportedModpackException {
        Path rootArchive = createArchive(
                temporaryDirectory.resolve("root.zip"),
                "versions/1.20.1/1.20.1.json");
        Path nestedArchive = createArchive(
                temporaryDirectory.resolve("nested.zip"),
                "pack/.minecraft/versions/1.20.1/1.20.1.json");

        assertEquals("", ModpackHelper.findMinecraftDirectoryInManuallyCreatedModpack("root", rootArchive));
        assertEquals(
                "pack/.minecraft",
                ModpackHelper.findMinecraftDirectoryInManuallyCreatedModpack("nested", nestedArchive));
    }

    /// A similarly shaped directory with another name remains unsupported.
    ///
    /// @param temporaryDirectory temporary fixture directory
    /// @throws IOException if the fixture cannot be written
    @Test
    public void rejectsNonMinecraftDirectories(@TempDir Path temporaryDirectory) throws IOException {
        Path archive = createArchive(
                temporaryDirectory.resolve("unsupported.zip"),
                "pack/game/versions/1.20.1/1.20.1.json");

        assertThrows(
                UnsupportedModpackException.class,
                () -> ModpackHelper.findMinecraftDirectoryInManuallyCreatedModpack("unsupported", archive));
    }

    /// Creates one ZIP containing an empty file at the requested entry path.
    ///
    /// @param archive fixture archive path
    /// @param entryName archive entry path
    /// @return written fixture path
    /// @throws IOException if the fixture cannot be written
    private static Path createArchive(Path archive, String entryName) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry(entryName));
            output.closeEntry();
        }
        return archive;
    }
}
