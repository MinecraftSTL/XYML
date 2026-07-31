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
package space.minecraftstl.xyml.game.export;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests exact legacy-whitelist expansion and unsafe selection rejection.
@NotNullByDefault
public final class ModpackExportFileSelectionTest {
    /// Run-directory tree used by file-expansion tests.
    @TempDir
    private Path runDirectory;

    /// Selected files gain ancestor entries and selected directories gain every descendant entry.
    @Test
    public void expandsFilesAndDirectorySubtreesForLiteralLegacyMatching() throws Exception {
        Files.createDirectories(runDirectory.resolve("config"));
        Files.writeString(runDirectory.resolve("config/options.txt"), "options");
        Files.createDirectories(runDirectory.resolve("mods/nested"));
        Files.writeString(runDirectory.resolve("mods/a.jar"), "a");
        Files.writeString(runDirectory.resolve("mods/nested/b.jar"), "b");

        ModpackExportFileSelection selection = ModpackExportFileSelection.of(List.of(
                "config/options.txt",
                "mods"));

        assertEquals(
                List.of(
                        "config",
                        "config/options.txt",
                        "mods",
                        "mods/a.jar",
                        "mods/nested",
                        "mods/nested/b.jar"),
                selection.expand(runDirectory));
    }

    /// Construction rejects an empty list because an empty legacy whitelist means export everything.
    @Test
    public void rejectsEmptySelection() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ModpackExportFileSelection.of(List.of()));
    }

    /// Absolute, traversal, and root-like selections cannot escape or alias the effective run directory.
    @Test
    public void rejectsUnsafeRelativePaths() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ModpackExportFileSelection.of(List.of("../secrets.txt")));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModpackExportFileSelection.of(List.of("/secrets.txt")));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModpackExportFileSelection.of(List.of("C:/secrets.txt")));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModpackExportFileSelection.of(List.of(".")));
    }

    /// Expansion reports a selected path that disappeared instead of falling back to a full export.
    @Test
    public void rejectsMissingSelectedPath() {
        ModpackExportFileSelection selection = ModpackExportFileSelection.of(List.of("missing.txt"));

        assertThrows(NoSuchFileException.class, () -> selection.expand(runDirectory));
    }

    /// Explicit symbolic-link selections fail and directory expansion omits nested links when supported.
    @Test
    public void excludesSymbolicLinksFromSelectionsWhenSupported() throws Exception {
        Path configuration = Files.createDirectories(runDirectory.resolve("config"));
        Files.writeString(configuration.resolve("options.txt"), "options");
        Path externalFile = Files.writeString(runDirectory.resolveSibling("secret.txt"), "secret");
        Path link = configuration.resolve("external-link.txt");
        try {
            Files.createSymbolicLink(link, externalFile);
        } catch (UnsupportedOperationException | SecurityException | IOException unavailable) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + unavailable.getMessage());
            return;
        }

        assertEquals(
                List.of("config", "config/options.txt"),
                ModpackExportFileSelection.of(List.of("config")).expand(runDirectory));
        assertThrows(
                FileSystemException.class,
                () -> ModpackExportFileSelection.of(List.of("config/external-link.txt")).expand(runDirectory));
    }
}
