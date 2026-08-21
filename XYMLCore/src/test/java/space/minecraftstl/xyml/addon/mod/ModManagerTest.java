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
package space.minecraftstl.xyml.addon.mod;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies supported mod archive extensions and metadata probes.
@NotNullByDefault
public final class ModManagerTest {

    /// ZIP-named Fabric archives are accepted as mod files.
    ///
    /// @param temporaryDirectory temporary fixture directory
    /// @throws IOException if the ZIP fixture cannot be written
    @Test
    public void acceptsZipModArchives(@TempDir Path temporaryDirectory) throws IOException {
        Path modArchive = temporaryDirectory.resolve("example.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(modArchive))) {
            output.putNextEntry(new ZipEntry("fabric.mod.json"));
            output.write("{}".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        assertTrue(ModManager.isFileNameMod(modArchive));
        assertTrue(ModManager.isFileMod(modArchive));
    }
}
