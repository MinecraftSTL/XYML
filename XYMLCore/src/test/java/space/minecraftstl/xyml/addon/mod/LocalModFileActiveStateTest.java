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
import space.minecraftstl.xyml.addon.LocalAddonFile;
import space.minecraftstl.xyml.game.DefaultGameRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies toolkit-neutral active-state transitions for local mod files.
@NotNullByDefault
final class LocalModFileActiveStateTest {
    /// Temporary file-system root for real enable, disable, and archive operations.
    @TempDir
    private Path temporaryDirectory;

    /// Current files rename only when requested state changes and can return to their original path.
    @Test
    void transitionsCurrentFileWithoutJavaFxPropertyState() throws IOException {
        Path enabledPath = Files.write(temporaryDirectory.resolve("fixture.jar"), new byte[]{1});
        LocalModFile file = createFile(enabledPath);

        assertTrue(file.isActive());

        file.setActive(false);
        Path disabledPath = temporaryDirectory.resolve("fixture.jar.disabled");
        assertFalse(file.isActive());
        assertEquals(disabledPath, file.getFile());
        assertTrue(Files.exists(disabledPath));
        assertFalse(Files.exists(enabledPath));

        file.setActive(false);
        assertEquals(disabledPath, file.getFile());

        file.setActive(true);
        assertTrue(file.isActive());
        assertEquals(enabledPath, file.getFile());
        assertTrue(Files.exists(enabledPath));
        assertFalse(Files.exists(disabledPath));
    }

    /// Disabled files initialize inactive and archived files retain state without being renamed.
    @Test
    void initializesFromPathAndDoesNotRenameArchivedFile() throws IOException {
        Path disabledPath = Files.write(temporaryDirectory.resolve("disabled.jar.disabled"), new byte[]{2});
        LocalModFile disabled = createFile(disabledPath);
        assertFalse(disabled.isActive());

        disabled.setActive(true);
        assertTrue(disabled.isActive());
        assertEquals(temporaryDirectory.resolve("disabled.jar"), disabled.getFile());

        Path archivedPath = Files.write(temporaryDirectory.resolve("archived.jar.old"), new byte[]{3});
        LocalModFile archived = createFile(archivedPath);
        assertTrue(archived.isActive());

        archived.setActive(false);
        assertFalse(archived.isActive());
        assertEquals(archivedPath, archived.getFile());
        assertTrue(Files.exists(archivedPath));
    }

    /// Creates one local mod model attached to a real manager.
    ///
    /// @param path current fixture path
    /// @return local mod model
    private LocalModFile createFile(Path path) {
        ModManager manager = new ModManager(new DefaultGameRepository(temporaryDirectory), "instance");
        String id = path.getFileName().toString();
        LocalMod mod = manager.getLocalMod(id, ModLoaderType.UNKNOWN);
        return new LocalModFile(manager, mod, path, id, new LocalAddonFile.Description("fixture"));
    }
}
