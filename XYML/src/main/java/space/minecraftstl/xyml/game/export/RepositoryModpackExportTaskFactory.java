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
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.modpack.ModpackExportInfo;
import space.minecraftstl.xyml.modpack.mcbbs.McbbsModpackExportTask;
import space.minecraftstl.xyml.modpack.modrinth.ModrinthModpackExportTask;
import space.minecraftstl.xyml.modpack.multimc.MultiMCInstanceConfiguration;
import space.minecraftstl.xyml.modpack.multimc.MultiMCModpackExportTask;
import space.minecraftstl.xyml.modpack.server.ServerModpackExportTask;
import space.minecraftstl.xyml.setting.GameSettings;
import space.minecraftstl.xyml.setting.GameWindowType;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.Lang;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/// Adapts immutable Swing export requests to the four existing repository-backed core exporters.
///
/// Export data is always read from [XYMLGameRepository#getRunDirectory(GameInstanceID)]. Each core task writes
/// a temporary archive beside the requested destination. A completed archive is published through a
/// no-replace hard link when supported, with a same-directory no-replace move as the compatibility path.
@NotNullByDefault
public final class RepositoryModpackExportTaskFactory implements ModpackExportTaskFactory {
    /// Resolves the effective run directory captured by the production repository.
    private final RunDirectoryResolver runDirectoryResolver;

    /// Creates the format-specific core task after request validation.
    private final CoreExportTaskCreator coreTaskCreator;

    /// Creates a factory backed by one game repository.
    ///
    /// @param repository repository containing the exported instance
    public RepositoryModpackExportTaskFactory(XYMLGameRepository repository) {
        Objects.requireNonNull(repository, "repository");
        this.runDirectoryResolver = repository::getRunDirectory;
        this.coreTaskCreator = new RepositoryCoreExportTaskCreator(repository);
    }

    /// Creates a factory with test-controlled repository seams.
    ///
    /// @param runDirectoryResolver resolver for the exact effective instance run directory
    /// @param coreTaskCreator creator for one format-specific stopped core task
    RepositoryModpackExportTaskFactory(
            RunDirectoryResolver runDirectoryResolver,
            CoreExportTaskCreator coreTaskCreator) {
        this.runDirectoryResolver = Objects.requireNonNull(runDirectoryResolver, "runDirectoryResolver");
        this.coreTaskCreator = Objects.requireNonNull(coreTaskCreator, "coreTaskCreator");
    }

    /// Creates a deferred task that validates, exports, and atomically publishes one archive.
    ///
    /// @param request immutable export request
    /// @return stopped export task yielding the final archive path
    @Override
    public Task<Path> create(ModpackExportRequest request) {
        ModpackExportRequest requestSnapshot = Objects.requireNonNull(request, "request");
        return new Task<>() {
            /// Runs the format exporter against a sibling temporary file and publishes only a complete archive.
            @Override
            public void execute() throws Exception {
                Path output = requestSnapshot.outputFile();
                Path parent = Objects.requireNonNull(output.getParent(), "output parent");
                Files.createDirectories(parent);
                if (Files.exists(output)) {
                    throw new FileAlreadyExistsException(output.toString());
                }

                GameInstanceID instanceId = new GameInstanceID(requestSnapshot.instanceId());
                Path runDirectory = Objects.requireNonNull(
                        runDirectoryResolver.resolve(instanceId),
                        "run directory");
                @Unmodifiable List<String> whitelist =
                        requestSnapshot.fileSelection().expand(runDirectory);
                if (whitelist.isEmpty()) {
                    throw new IllegalArgumentException("At least one export file must be selected");
                }

                Path temporary = Files.createTempFile(
                        parent,
                        "." + output.getFileName() + "-",
                        ".part");
                try {
                    ModpackExportInfo exportInfo = toCoreExportInfo(
                            requestSnapshot.metadata(),
                            whitelist,
                            requestSnapshot.format());
                    coreTaskCreator.create(
                                    requestSnapshot.format(),
                                    instanceId,
                                    exportInfo,
                                    whitelist,
                                    temporary)
                            .run();
                    publishWithoutReplacement(temporary, output);
                    setResult(output);
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
        };
    }

    /// Copies immutable request metadata into the mutable core exporter DTO.
    ///
    /// Modrinth remote-file discovery is unconditionally disabled so normal export never performs
    /// network lookups. Launcher bundling is also unconditionally disabled because jpackage output
    /// cannot be represented by the removed single-JAR wrapper format.
    ///
    /// @param metadata immutable export metadata
    /// @param whitelist expanded exact exporter whitelist
    /// @param format destination archive format
    /// @return isolated mutable DTO used by one core task only
    static ModpackExportInfo toCoreExportInfo(
            ModpackExportMetadata metadata,
            @Unmodifiable List<String> whitelist,
            ModpackExportFormat format) {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(whitelist, "whitelist");
        Objects.requireNonNull(format, "format");
        if (whitelist.isEmpty()) {
            throw new IllegalArgumentException("At least one export file must be selected");
        }

        ModpackExportInfo exportInfo = new ModpackExportInfo()
                .setWhitelist(List.copyOf(whitelist))
                .setName(metadata.name())
                .setAuthor(metadata.author())
                .setVersion(metadata.version())
                .setDescription(metadata.description())
                .setFileApi(metadata.fileApi())
                .setUrl(metadata.url())
                .setForceUpdate(metadata.forceUpdate())
                .setPackWithLauncher(false)
                .setMinMemory(metadata.minMemory())
                .setSupportedJavaVersions(List.copyOf(metadata.supportedJavaVersions()))
                .setLaunchArguments(metadata.launchArguments())
                .setJavaArguments(metadata.javaArguments())
                .setAuthlibInjectorServer(metadata.authlibInjectorServer())
                .setOrigins(List.copyOf(metadata.origins()));
        boolean offlineModrinthExport = format == ModpackExportFormat.MODRINTH;
        exportInfo.setNoCreateRemoteFiles(offlineModrinthExport);
        exportInfo.setSkipCurseForgeRemoteFiles(offlineModrinthExport);
        return exportInfo;
    }

    /// Publishes one complete sibling temporary file without replacing an existing destination.
    ///
    /// @param temporary complete temporary archive
    /// @param output absent final archive path
    /// @throws IOException when publication fails or the destination already exists
    private static void publishWithoutReplacement(Path temporary, Path output) throws IOException {
        try {
            Files.createLink(output, temporary);
        } catch (FileAlreadyExistsException exception) {
            throw exception;
        } catch (UnsupportedOperationException | FileSystemException unsupportedLink) {
            // A same-directory move without REPLACE_EXISTING retains conflict protection on filesystems
            // that cannot create hard links, while avoiding cross-device copies or partial publication.
            Files.move(temporary, output);
        }
    }

    /// Resolves the exact effective run directory for one instance.
    @FunctionalInterface
    @NotNullByDefault
    interface RunDirectoryResolver {
        /// Returns the directory whose selected contents will be exported.
        ///
        /// @param instanceId selected repository instance
        /// @return effective run directory
        Path resolve(GameInstanceID instanceId);
    }

    /// Creates one stopped core export task.
    @FunctionalInterface
    @NotNullByDefault
    interface CoreExportTaskCreator {
        /// Creates the format-specific task after file selection has been expanded.
        ///
        /// @param format destination archive format
        /// @param instanceId selected repository instance
        /// @param exportInfo isolated mutable export metadata
        /// @param whitelist immutable expanded exact whitelist
        /// @param temporaryOutput sibling temporary archive
        /// @return stopped format-specific core task
        Task<?> create(
                ModpackExportFormat format,
                GameInstanceID instanceId,
                ModpackExportInfo exportInfo,
                @Unmodifiable List<String> whitelist,
                Path temporaryOutput);
    }

    /// Maps requests to the repository-backed core exporters retained after JavaFX removal.
    @NotNullByDefault
    private static final class RepositoryCoreExportTaskCreator implements CoreExportTaskCreator {
        /// Repository supplying version metadata, effective settings, and selected files.
        private final XYMLGameRepository repository;

        /// Creates one repository-backed task creator.
        ///
        /// @param repository repository containing the exported instance
        private RepositoryCoreExportTaskCreator(XYMLGameRepository repository) {
            this.repository = Objects.requireNonNull(repository, "repository");
        }

        /// Maps the request to its existing concrete exporter.
        ///
        /// @param format destination archive format
        /// @param instanceId selected repository instance
        /// @param exportInfo isolated mutable export metadata
        /// @param whitelist immutable expanded exact whitelist
        /// @param temporaryOutput sibling temporary archive
        /// @return stopped format-specific core task
        @Override
        public Task<?> create(
                ModpackExportFormat format,
                GameInstanceID instanceId,
                ModpackExportInfo exportInfo,
                @Unmodifiable List<String> whitelist,
                Path temporaryOutput) {
            return switch (format) {
                case MCBBS -> new McbbsModpackExportTask(
                        repository,
                        instanceId,
                        exportInfo,
                        temporaryOutput);
                case MULTIMC -> new MultiMCModpackExportTask(
                        repository,
                        instanceId,
                        whitelist,
                        createMultiMCConfiguration(instanceId, exportInfo),
                        temporaryOutput);
                case SERVER -> new ServerModpackExportTask(
                        repository,
                        instanceId,
                        exportInfo,
                        temporaryOutput);
                case MODRINTH -> new ModrinthModpackExportTask(
                        repository,
                        instanceId,
                        exportInfo,
                        temporaryOutput);
            };
        }

        /// Creates the MultiMC instance configuration from effective instance settings.
        ///
        /// @param instanceId exported repository instance
        /// @param exportInfo isolated mutable export metadata
        /// @return MultiMC configuration written beside the manifest
        private MultiMCInstanceConfiguration createMultiMCConfiguration(
                GameInstanceID instanceId,
                ModpackExportInfo exportInfo) {
            GameSettings.Effective setting = repository.getEffectiveGameSettings(instanceId);
            return new MultiMCInstanceConfiguration(
                    "OneSix",
                    exportInfo.getName() + "-" + exportInfo.getVersion(),
                    null,
                    Lang.toIntOrNull(setting.getInheritable(GameSettings::permSizeProperty)),
                    setting.getInheritable(GameSettings::commandWrapperProperty),
                    setting.getInheritable(GameSettings::preLaunchCommandProperty),
                    null,
                    exportInfo.getDescription(),
                    null,
                    exportInfo.getJavaArguments(),
                    setting.getInheritable(GameSettings::windowTypeProperty) == GameWindowType.FULLSCREEN,
                    setting.getWidth(),
                    setting.getHeight(),
                    null,
                    exportInfo.getMinMemory(),
                    setting.getInheritable(GameSettings::showLogsProperty),
                    true,
                    false,
                    true,
                    false,
                    true,
                    true,
                    true,
                    true,
                    null);
        }
    }
}
