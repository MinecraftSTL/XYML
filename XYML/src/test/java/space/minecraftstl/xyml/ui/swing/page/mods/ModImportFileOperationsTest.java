// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /// A failed staged publish preserves the old target and variants, cleans staging, and can be retried.
    @Test
    public void preservesTargetWhenPublishFailsAndRetrySucceeds() throws IOException {
        Path modsDirectory = Files.createDirectories(temporaryDirectory.resolve("mods"));
        Path targetDirectory = Files.createDirectories(modsDirectory.resolve("sample.jar"));
        Path held = Files.writeString(targetDirectory.resolve("held.txt"), "held");
        Path disabled = Files.write(modsDirectory.resolve("sample.jar.disabled"), new byte[]{1});
        Path source = source("sample.jar", new byte[]{9, 8, 7});

        assertThrows(IOException.class, () -> ModImportFileOperations.importMods(
                modsDirectory,
                List.of(disabled),
                List.of(source),
                Map.of(source, ModImportConflictAction.REPLACE),
                new LoadCancellation()));

        assertTrue(Files.isDirectory(targetDirectory));
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(disabled));
        try (Stream<Path> entries = Files.list(modsDirectory)) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString()
                    .startsWith(".xyml-mod-import-")));
        }

        Files.delete(held);
        Files.delete(targetDirectory);
        ModImportFileOperations.importMods(
                modsDirectory,
                List.of(disabled),
                List.of(source),
                Map.of(source, ModImportConflictAction.REPLACE),
                new LoadCancellation());

        assertArrayEquals(new byte[]{9, 8, 7}, Files.readAllBytes(modsDirectory.resolve("sample.jar")));
        assertFalse(Files.exists(disabled));
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
