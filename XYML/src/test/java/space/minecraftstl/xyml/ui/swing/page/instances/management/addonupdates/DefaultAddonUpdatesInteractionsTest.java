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
