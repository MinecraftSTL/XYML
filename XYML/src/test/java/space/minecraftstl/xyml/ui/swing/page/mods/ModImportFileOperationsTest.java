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
package space.minecraftstl.xyml.ui.swing.page.mods;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies preflighted Mod import conflict decisions against real files.
@NotNullByDefault
public final class ModImportFileOperationsTest {
    /// Temporary root for isolated source and managed Mod directories.
    @TempDir
    private Path temporaryDirectory;

    /// Replacement writes the new file before removing disabled and archived same-key variants.
    @Test
    public void replacesSameKeyVariantsWithNewFile() throws IOException {
        Path modsDirectory = Files.createDirectories(temporaryDirectory.resolve("mods"));
        Path disabled = Files.write(modsDirectory.resolve("sample.jar.disabled"), new byte[]{1});
        Path archived = Files.write(modsDirectory.resolve("sample.litemod.old"), new byte[]{2});
        Path source = source("sample.jar", new byte[]{9, 8, 7});

        ModImportFileOperations.importMods(
                modsDirectory,
                List.of(disabled),
                List.of(source),
                Map.of(source, ModImportConflictAction.REPLACE),
                new LoadCancellation());

        assertArrayEquals(new byte[]{9, 8, 7}, Files.readAllBytes(modsDirectory.resolve("sample.jar")));
        assertFalse(Files.exists(disabled));
        assertFalse(Files.exists(archived));
    }

    /// Skipping one conflict leaves it unchanged while an uncontested source in the batch is copied.
    @Test
    public void skipsOnlyConflictingSource() throws IOException {
        Path modsDirectory = Files.createDirectories(temporaryDirectory.resolve("mods"));
        Path existing = Files.write(modsDirectory.resolve("sample.jar"), new byte[]{1});
        Path skippedSource = source("sample.jar", new byte[]{2});
        Path copiedSource = source("additional.jar", new byte[]{3});

        ModImportFileOperations.importMods(
                modsDirectory,
                List.of(existing),
                List.of(skippedSource, copiedSource),
                Map.of(skippedSource, ModImportConflictAction.SKIP),
                new LoadCancellation());

        assertArrayEquals(new byte[]{1}, Files.readAllBytes(existing));
        assertArrayEquals(new byte[]{3}, Files.readAllBytes(modsDirectory.resolve("additional.jar")));
    }

    /// Keeping both appends as many trailing base-name hyphens as existing names require.
    @Test
    public void keepsBothWithRepeatedTrailingHyphens() throws IOException {
        Path modsDirectory = Files.createDirectories(temporaryDirectory.resolve("mods"));
        Path existing = Files.write(modsDirectory.resolve("sample.jar"), new byte[]{1});
        Files.write(modsDirectory.resolve("sample-.jar"), new byte[]{2});
        Files.write(modsDirectory.resolve("sample--.jar.disabled"), new byte[]{3});
        Path source = source("sample.jar", new byte[]{4});

        ModImportFileOperations.importMods(
                modsDirectory,
                List.of(existing),
                List.of(source),
                Map.of(source, ModImportConflictAction.KEEP),
                new LoadCancellation());

        assertArrayEquals(new byte[]{1}, Files.readAllBytes(existing));
        assertArrayEquals(new byte[]{4}, Files.readAllBytes(modsDirectory.resolve("sample---.jar")));
    }

    /// A newly discovered unresolved conflict aborts the complete plan before earlier copies start.
    @Test
    public void rejectsUnresolvedConflictBeforeAnyCopy() throws IOException {
        Path modsDirectory = Files.createDirectories(temporaryDirectory.resolve("mods"));
        Path existing = Files.write(modsDirectory.resolve("sample.jar"), new byte[]{1});
        Path freshSource = source("fresh.jar", new byte[]{2});
        Path conflictSource = source("sample.jar", new byte[]{3});

        assertThrows(ModImportConflictException.class, () -> ModImportFileOperations.importMods(
                modsDirectory,
                List.of(existing),
                List.of(freshSource, conflictSource),
                Map.of(),
                new LoadCancellation()));

        assertFalse(Files.exists(modsDirectory.resolve("fresh.jar")));
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(existing));
    }

    /// Creates one regular Mod-named source outside the managed directory.
    ///
    /// @param fileName source file name
    /// @param content deterministic file content
    /// @return created source path
    /// @throws IOException when fixture creation fails
    private Path source(String fileName, byte[] content) throws IOException {
        Path sourceDirectory = Files.createDirectories(temporaryDirectory.resolve("sources"));
        return Files.write(sourceDirectory.resolve(fileName), content);
    }
}
