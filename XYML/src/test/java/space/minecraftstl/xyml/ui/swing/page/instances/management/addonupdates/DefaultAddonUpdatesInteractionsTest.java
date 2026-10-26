// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management.addonupdates;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies exact CSV content and non-destructive update-list export behavior.
@NotNullByDefault
final class DefaultAddonUpdatesInteractionsTest {
    /// Writes the established four-column update-list format and refuses replacement.
    ///
    /// @param temporaryDirectory isolated destination root
    /// @throws IOException when test file access fails
    @Test
    void writesStructuredCsvWithoutReplacingExistingFile(@TempDir Path temporaryDirectory) throws IOException {
        Path destination = temporaryDirectory.resolve("nested").resolve("updates.csv");
        List<AddonUpdateExportRow> rows = List.of(
                new AddonUpdateExportRow("example.jar", "1.0.0", "1.1.0", "Modrinth"),
                new AddonUpdateExportRow("resource.zip", "2", "3", "CurseForge"));

        DefaultAddonUpdatesInteractions.writeUpdateList(destination, rows);

        assertEquals(
                "Source File Name,Current Version,Target Version,Update Source\n"
                        + "example.jar,1.0.0,1.1.0,Modrinth\n"
                        + "resource.zip,2,3,CurseForge\n",
                Files.readString(destination));
        assertThrows(
                IOException.class,
                () -> DefaultAddonUpdatesInteractions.writeUpdateList(destination, rows));
    }
}
