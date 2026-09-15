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
