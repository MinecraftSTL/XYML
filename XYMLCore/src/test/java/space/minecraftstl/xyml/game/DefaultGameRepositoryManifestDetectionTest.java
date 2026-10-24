/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2025 huangyuhui <huanghongxun2008@126.com> and contributors
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies discovery among unrelated JSON files without replacing XYML's repository storage rules.
@NotNullByDefault
public final class DefaultGameRepositoryManifestDetectionTest {
    /// Finds a valid candidate after malformed and unrelated files and keeps the existing rename contract.
    ///
    /// @param root isolated game repository root
    /// @throws IOException if test files cannot be written or inspected
    @Test
    public void selectsManifestAndMovesMatchingJar(@TempDir Path root) throws IOException {
        Path directory = Files.createDirectories(root.resolve("versions/example"));
        Files.writeString(directory.resolve("a-malformed.json"), "{");
        Files.writeString(directory.resolve("b-settings.json"), "{\"id\":\"settings\"}");
        Files.writeString(directory.resolve("c-instance.json"),
                "{\"id\":\"example\",\"mainClass\":\"net.minecraft.client.main.Main\"}");
        Files.write(directory.resolve("c-instance.jar"), new byte[] {1, 2, 3});
        DefaultGameRepository repository = new DefaultGameRepository(root);
        repository.refresh();
        assertAll(
                () -> assertTrue(repository.hasInstance(new GameInstanceID("example"))),
                () -> assertTrue(Files.exists(directory.resolve("example.json"))),
                () -> assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(directory.resolve("example.jar"))),
                () -> assertTrue(Files.exists(directory.resolve("b-settings.json"))),
                () -> assertFalse(Files.exists(directory.resolve("c-instance.json"))));
    }

    /// Refuses unrelated JSON files and null documents instead of inventing an instance.
    ///
    /// @param root isolated game repository root
    /// @throws IOException if test files cannot be written
    @Test
    public void ignoresDirectoryWithoutUsableManifest(@TempDir Path root) throws IOException {
        Path directory = Files.createDirectories(root.resolve("versions/example"));
        Files.writeString(directory.resolve("settings.json"), "{\"id\":\"settings\"}");
        Files.writeString(directory.resolve("empty.json"), "null");
        DefaultGameRepository repository = new DefaultGameRepository(root);
        repository.refresh();
        assertFalse(repository.hasInstance(new GameInstanceID("example")));
        assertTrue(Files.notExists(directory.resolve("example.json")));
    }

    /// Chooses the first valid candidate by file name without consuming later candidates.
    ///
    /// @param root isolated game repository root
    /// @throws IOException if test files cannot be written or inspected
    @Test
    public void selectionUsesStableFileNameOrder(@TempDir Path root) throws IOException {
        Path directory = Files.createDirectories(root.resolve("versions/example"));
        String first = "{\"id\":\"example\",\"mainClass\":\"example.First\"}";
        String second = "{\"id\":\"example\",\"mainClass\":\"example.Second\"}";
        Files.writeString(directory.resolve("z-instance.json"), second);
        Files.writeString(directory.resolve("a-instance.json"), first);
        new DefaultGameRepository(root).refresh();
        assertEquals(first, Files.readString(directory.resolve("example.json")));
        assertEquals(second, Files.readString(directory.resolve("z-instance.json")));
    }

    /// Keeps a conventional manifest authoritative over other matching candidates.
    ///
    /// @param root isolated game repository root
    /// @throws IOException if test files cannot be written or inspected
    @Test
    public void conventionalManifestKeepsPriority(@TempDir Path root) throws IOException {
        Path directory = Files.createDirectories(root.resolve("versions/example"));
        String conventional = "{\"id\":\"example\",\"mainClass\":\"example.Conventional\"}";
        Files.writeString(directory.resolve("example.json"), conventional);
        Files.writeString(directory.resolve("a-instance.json"),
                "{\"id\":\"example\",\"mainClass\":\"example.Other\"}");
        new DefaultGameRepository(root).refresh();
        assertEquals(conventional, Files.readString(directory.resolve("example.json")));
        assertTrue(Files.exists(directory.resolve("a-instance.json")));
    }
}
