// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.addon.mod;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.game.DefaultGameRepository;
import space.minecraftstl.xyml.game.GameInstanceID;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /// A snapshot-bound manager does not follow later repository root changes.
    ///
    /// @param temporaryDirectory temporary repository and captured-directory roots
    @Test
    public void retainsCapturedDirectory(@TempDir Path temporaryDirectory) {
        DefaultGameRepository repository = new DefaultGameRepository(temporaryDirectory.resolve("first-repository"));
        Path capturedDirectory = temporaryDirectory.resolve("captured-mods").toAbsolutePath().normalize();
        ModManager manager = new ModManager(repository, new GameInstanceID("instance"), capturedDirectory);

        repository.setBaseDirectory(temporaryDirectory.resolve("second-repository"));

        assertEquals(capturedDirectory, manager.getDirectory());
    }
}
