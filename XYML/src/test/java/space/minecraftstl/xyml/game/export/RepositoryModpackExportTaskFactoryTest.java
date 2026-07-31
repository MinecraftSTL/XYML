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
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.modpack.ModpackExportInfo;
import space.minecraftstl.xyml.task.Task;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests request adaptation, safe publication, failure cleanup, and offline Modrinth defaults.
@NotNullByDefault
public final class RepositoryModpackExportTaskFactoryTest {
    /// Temporary run and destination root used by export factory tests.
    @TempDir
    private Path root;

    /// Every supported format reaches the core adapter with the effective run-directory whitelist.
    @Test
    public void adaptsEveryFormatAndPublishesCompleteArchive() throws Exception {
        Path runDirectory = Files.createDirectories(root.resolve("effective-run"));
        Files.createDirectories(runDirectory.resolve("config"));
        Files.writeString(runDirectory.resolve("config/options.txt"), "options");
        RecordingCoreTaskCreator creator = new RecordingCoreTaskCreator(false);
        RepositoryModpackExportTaskFactory factory = new RepositoryModpackExportTaskFactory(
                instanceId -> {
                    assertEquals(new GameInstanceID("instance"), instanceId);
                    return runDirectory;
                },
                creator);

        for (ModpackExportFormat format : ModpackExportFormat.values()) {
            Path output = root.resolve(format.name().toLowerCase(Locale.ROOT) + format.fileSuffix());
            ModpackExportRequest request = request(format, output);

            Path result = factory.create(request).run();

            assertEquals(output.toAbsolutePath().normalize(), result);
            assertEquals("archive-" + format, Files.readString(output));
            assertEquals(format, creator.lastFormat);
            assertEquals(List.of("config", "config/options.txt"), creator.lastWhitelist);
            assertFalse(creator.lastInfo.isPackWithLauncher());
            assertEquals(format == ModpackExportFormat.MODRINTH, creator.lastInfo.isNoCreateRemoteFiles());
            assertEquals(format == ModpackExportFormat.MODRINTH, creator.lastInfo.isSkipCurseForgeRemoteFiles());
        }
        assertEquals(ModpackExportFormat.values().length, creator.executionCount.get());
    }

    /// Existing output is rejected before the core task runs and its bytes remain unchanged.
    @Test
    public void refusesToReplaceExistingOutput() throws Exception {
        Path runDirectory = Files.createDirectories(root.resolve("run"));
        Files.writeString(runDirectory.resolve("options.txt"), "options");
        Path output = root.resolve("existing.zip");
        Files.writeString(output, "existing");
        RecordingCoreTaskCreator creator = new RecordingCoreTaskCreator(false);
        RepositoryModpackExportTaskFactory factory = new RepositoryModpackExportTaskFactory(
                ignoredInstance -> runDirectory,
                creator);

        assertThrows(FileAlreadyExistsException.class, () -> factory.create(request(
                ModpackExportFormat.MCBBS,
                output,
                "options.txt")).run());
        assertEquals("existing", Files.readString(output));
        assertEquals(0, creator.executionCount.get());
    }

    /// A failed core export leaves no final archive and removes its sibling partial file.
    @Test
    public void deletesTemporaryArchiveAfterCoreFailure() throws Exception {
        Path runDirectory = Files.createDirectories(root.resolve("run"));
        Files.writeString(runDirectory.resolve("options.txt"), "options");
        Path output = root.resolve("failed.zip");
        RecordingCoreTaskCreator creator = new RecordingCoreTaskCreator(true);
        RepositoryModpackExportTaskFactory factory = new RepositoryModpackExportTaskFactory(
                ignoredInstance -> runDirectory,
                creator);

        assertThrows(IOException.class, () -> factory.create(request(
                ModpackExportFormat.SERVER,
                output,
                "options.txt")).run());
        assertFalse(Files.exists(output));
        try (var children = Files.list(root)) {
            assertTrue(children.noneMatch(path -> path.getFileName().toString().endsWith(".part")));
        }
    }

    /// Direct DTO conversion rejects the dangerous empty whitelist and forces offline Modrinth flags.
    @Test
    public void coreInfoRejectsEmptyWhitelistAndForcesOfflineModrinth() {
        ModpackExportMetadata metadata = ModpackExportMetadata.minimal("Pack", "1.0");

        assertThrows(
                IllegalArgumentException.class,
                () -> RepositoryModpackExportTaskFactory.toCoreExportInfo(
                        metadata,
                        List.of(),
                        ModpackExportFormat.MCBBS));

        ModpackExportInfo info = RepositoryModpackExportTaskFactory.toCoreExportInfo(
                metadata,
                List.of("options.txt"),
                ModpackExportFormat.MODRINTH);
        assertTrue(info.isNoCreateRemoteFiles());
        assertTrue(info.isSkipCurseForgeRemoteFiles());
        assertFalse(info.isPackWithLauncher());
    }

    /// Creates a request selecting the nested configuration file used by the primary test.
    ///
    /// @param format requested export format
    /// @param output final archive path
    /// @return immutable export request
    private ModpackExportRequest request(ModpackExportFormat format, Path output) {
        return request(format, output, "config/options.txt");
    }

    /// Creates a request selecting one relative run-directory path.
    ///
    /// @param format requested export format
    /// @param output final archive path
    /// @param selectedPath selected relative file
    /// @return immutable export request
    private ModpackExportRequest request(
            ModpackExportFormat format,
            Path output,
            String selectedPath) {
        return new ModpackExportRequest(
                format,
                new GameInstanceID("instance"),
                ModpackExportMetadata.minimal("Pack", "1.0"),
                ModpackExportFileSelection.of(List.of(selectedPath)),
                output);
    }

    /// Records adapter inputs and simulates one core exporter writing its temporary archive.
    @NotNullByDefault
    private static final class RecordingCoreTaskCreator
            implements RepositoryModpackExportTaskFactory.CoreExportTaskCreator {
        /// Whether the simulated core task should fail after writing partial bytes.
        private final boolean fail;

        /// Number of simulated core task executions.
        private final AtomicInteger executionCount = new AtomicInteger();

        /// Last requested export format.
        private ModpackExportFormat lastFormat = ModpackExportFormat.MCBBS;

        /// Last isolated mutable legacy metadata instance.
        private ModpackExportInfo lastInfo = new ModpackExportInfo();

        /// Last immutable expanded whitelist.
        private @Unmodifiable List<String> lastWhitelist = List.of();

        /// Creates a recording creator with optional failure injection.
        ///
        /// @param fail whether the simulated core task fails
        private RecordingCoreTaskCreator(boolean fail) {
            this.fail = fail;
        }

        /// Records inputs and returns one stopped file-writing task.
        ///
        /// @param format destination archive format
        /// @param instanceId selected repository instance
        /// @param exportInfo isolated mutable legacy metadata
        /// @param whitelist immutable expanded exact whitelist
        /// @param temporaryOutput sibling temporary archive
        /// @return stopped simulated core task
        @Override
        public Task<?> create(
                ModpackExportFormat format,
                GameInstanceID instanceId,
                ModpackExportInfo exportInfo,
                @Unmodifiable List<String> whitelist,
                Path temporaryOutput) {
            assertEquals(new GameInstanceID("instance"), instanceId);
            lastFormat = format;
            lastInfo = exportInfo;
            lastWhitelist = List.copyOf(whitelist);
            return Task.runAsync(Runnable::run, () -> {
                executionCount.incrementAndGet();
                Files.writeString(temporaryOutput, "archive-" + format);
                if (fail) {
                    throw new IOException("simulated export failure");
                }
            });
        }
    }
}
