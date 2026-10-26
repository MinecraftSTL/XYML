// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.gradle.cache;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies promotion, validation, and installation of run-library snapshots.
@NotNullByDefault
final class RunLibraryCacheTest {
    /// Temporary filesystem root for each test.
    @TempDir
    private Path temporaryDirectory;

    /// Promotes a complete snapshot and resolves immutable copied artifacts.
    @Test
    void promotesAndResolvesCompleteSnapshot() throws IOException {
        Path nbt = artifact("xoyz-nbt.jar", "nbt");
        Path mcp = artifact("xoyz-mcp.jar", "mcp");
        Path cache = temporaryDirectory.resolve("cache");

        RunLibraryCache.promote(cache, "1.0.0", Map.of("xoyz-nbt", nbt, "xoyz-mcp", mcp));

        Map<String, Path> resolved = RunLibraryCache.resolve(cache, List.of("xoyz-nbt", "xoyz-mcp"));
        assertEquals("nbt", Files.readString(resolved.get("xoyz-nbt")));
        assertEquals("mcp", Files.readString(resolved.get("xoyz-mcp")));
    }

    /// Rejects the entire snapshot after one copied artifact is modified.
    @Test
    void rejectsTamperedSnapshot() throws IOException {
        Path cache = temporaryDirectory.resolve("cache");
        RunLibraryCache.promote(cache, "1.0.0", Map.of(
                "xoyz-nbt", artifact("xoyz-nbt.jar", "nbt"),
                "xoyz-mcp", artifact("xoyz-mcp.jar", "mcp")));
        Path cachedMcp = RunLibraryCache.resolve(cache, List.of("xoyz-nbt", "xoyz-mcp")).get("xoyz-mcp");

        Files.writeString(cachedMcp, "changed");

        assertTrue(RunLibraryCache.resolve(cache, List.of("xoyz-nbt", "xoyz-mcp")).isEmpty());
    }

    /// Installs a branch-build snapshot into a separate checkout cache.
    @Test
    void installsValidatedBranchSnapshot() throws IOException {
        Path source = temporaryDirectory.resolve("source");
        RunLibraryCache.promote(source, "1.0.0", Map.of(
                "xoyz-nbt", artifact("xoyz-nbt.jar", "nbt"),
                "xoyz-mcp", artifact("xoyz-mcp.jar", "mcp")));
        Path destination = temporaryDirectory.resolve("destination");

        RunLibraryCache.install(source, destination, List.of("xoyz-nbt", "xoyz-mcp"));

        Map<String, Path> resolved = RunLibraryCache.resolve(
                destination, List.of("xoyz-nbt", "xoyz-mcp"));
        assertEquals("nbt", Files.readString(resolved.get("xoyz-nbt")));
        assertEquals("mcp", Files.readString(resolved.get("xoyz-mcp")));
    }

    /// Keeps the last successful snapshot when a later promotion cannot copy every artifact.
    @Test
    void retainsPreviousSnapshotAfterFailedPromotion() throws IOException {
        Path cache = temporaryDirectory.resolve("cache");
        RunLibraryCache.promote(cache, "1.0.0", Map.of(
                "xoyz-nbt", artifact("xoyz-nbt.jar", "nbt"),
                "xoyz-mcp", artifact("xoyz-mcp.jar", "mcp")));

        assertThrows(IOException.class, () -> RunLibraryCache.promote(cache, "1.0.1", Map.of(
                "xoyz-nbt", artifact("new-xoyz-nbt.jar", "new-nbt"),
                "xoyz-mcp", temporaryDirectory.resolve("missing.jar"))));

        Map<String, Path> resolved = RunLibraryCache.resolve(cache, List.of("xoyz-nbt", "xoyz-mcp"));
        assertEquals("nbt", Files.readString(resolved.get("xoyz-nbt")));
        assertEquals("mcp", Files.readString(resolved.get("xoyz-mcp")));
    }

    /// Creates one dummy library artifact.
    ///
    /// @param name artifact file name
    /// @param contents artifact contents
    /// @return created artifact path
    /// @throws IOException when the artifact cannot be written
    private Path artifact(String name, String contents) throws IOException {
        Path artifacts = temporaryDirectory.resolve("artifacts");
        Files.createDirectories(artifacts);
        return Files.writeString(artifacts.resolve(name), contents);
    }
}
