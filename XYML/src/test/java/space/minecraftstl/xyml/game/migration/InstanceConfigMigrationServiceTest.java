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
package space.minecraftstl.xyml.game.migration;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.setting.InstanceConfigMigrationContent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests safe fixed-path instance configuration migration.
@NotNullByDefault
class InstanceConfigMigrationServiceTest {
    /// Temporary isolated filesystem root.
    @TempDir
    private Path temporaryDirectory;

    /// Copies selected file and directory content while ignoring absent categories.
    @Test
    void copiesSelectedContentAndSkipsMissingCategories() throws IOException {
        Path source = Files.createDirectory(temporaryDirectory.resolve("source"));
        Path target = Files.createDirectory(temporaryDirectory.resolve("target"));
        Files.writeString(source.resolve("options.txt"), "source-options");
        Files.createDirectories(source.resolve("config/example"));
        Files.writeString(source.resolve("config/example/value.txt"), "source-config");

        InstanceConfigMigrationResult result = InstanceConfigMigrationService.migrate(
                new InstanceConfigMigrationRequest(
                        source,
                        target,
                        EnumSet.of(
                                InstanceConfigMigrationContent.OPTIONS,
                                InstanceConfigMigrationContent.MOD_CONFIG,
                                InstanceConfigMigrationContent.SERVERS),
                        false));

        assertEquals("source-options", Files.readString(target.resolve("options.txt")));
        assertEquals("source-config", Files.readString(target.resolve("config/example/value.txt")));
        assertFalse(Files.exists(target.resolve("servers.dat")));
        assertEquals(2, result.writtenFiles());
    }

    /// Keeps existing files in automatic mode and remains safe to retry.
    @Test
    void nonReplacingMigrationSkipsConflictsAndCanRetry() throws IOException {
        Path source = Files.createDirectory(temporaryDirectory.resolve("source"));
        Path target = Files.createDirectory(temporaryDirectory.resolve("target"));
        Files.writeString(source.resolve("options.txt"), "source");
        Files.writeString(target.resolve("options.txt"), "target");
        InstanceConfigMigrationRequest request = new InstanceConfigMigrationRequest(
                source,
                target,
                EnumSet.of(InstanceConfigMigrationContent.OPTIONS),
                false);

        InstanceConfigMigrationResult first = InstanceConfigMigrationService.migrate(request);
        InstanceConfigMigrationResult second = InstanceConfigMigrationService.migrate(request);

        assertEquals("target", Files.readString(target.resolve("options.txt")));
        assertEquals(1, first.skippedFiles());
        assertEquals(1, second.skippedFiles());
    }

    /// Replaces conflicting files while retaining target-only directory entries.
    @Test
    void replacingMigrationMergesDirectoriesWithoutDeletingTargetOnlyFiles() throws IOException {
        Path source = Files.createDirectory(temporaryDirectory.resolve("source"));
        Path target = Files.createDirectory(temporaryDirectory.resolve("target"));
        Files.createDirectories(source.resolve("config"));
        Files.createDirectories(target.resolve("config"));
        Files.writeString(source.resolve("config/shared.txt"), "source");
        Files.writeString(target.resolve("config/shared.txt"), "target");
        Files.writeString(target.resolve("config/target-only.txt"), "keep");

        InstanceConfigMigrationResult result = InstanceConfigMigrationService.migrate(
                new InstanceConfigMigrationRequest(
                        source,
                        target,
                        EnumSet.of(InstanceConfigMigrationContent.MOD_CONFIG),
                        true));

        assertEquals("source", Files.readString(target.resolve("config/shared.txt")));
        assertEquals("keep", Files.readString(target.resolve("config/target-only.txt")));
        assertEquals(1, result.counts().get(InstanceConfigMigrationContent.MOD_CONFIG).replaced());
    }

    /// Rejects equal normalized roots before any mutation occurs.
    @Test
    void rejectsSameSourceAndTarget() {
        assertThrows(IllegalArgumentException.class, () -> new InstanceConfigMigrationRequest(
                temporaryDirectory,
                temporaryDirectory.resolve("."),
                EnumSet.allOf(InstanceConfigMigrationContent.class),
                false));
    }

    /// Rejects source symbolic links when the platform permits creating one.
    @Test
    void rejectsSymbolicLinkSource() throws IOException {
        Path source = Files.createDirectory(temporaryDirectory.resolve("source"));
        Path target = Files.createDirectory(temporaryDirectory.resolve("target"));
        Path external = Files.writeString(temporaryDirectory.resolve("external.txt"), "secret");
        try {
            Files.createSymbolicLink(source.resolve("options.txt"), external);
        } catch (UnsupportedOperationException | IOException exception) {
            return;
        }

        IOException failure = assertThrows(IOException.class, () -> InstanceConfigMigrationService.migrate(
                new InstanceConfigMigrationRequest(
                        source,
                        target,
                        EnumSet.of(InstanceConfigMigrationContent.OPTIONS),
                        false)));
        assertTrue(failure.getMessage().contains("regular file"));
    }
}
