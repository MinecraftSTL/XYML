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
package space.minecraftstl.xyml.modpack.multimc;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.download.LibraryAnalyzer;
import space.minecraftstl.xyml.game.DefaultGameRepository;
import space.minecraftstl.xyml.modpack.ModAdviser;
import space.minecraftstl.xyml.modpack.Modpack;
import space.minecraftstl.xyml.modpack.ModpackExportInfo;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.io.Zipper;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static space.minecraftstl.xyml.download.LibraryAnalyzer.LibraryType.*;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Exports one installed instance as a MultiMC-compatible modpack archive.
@NotNullByDefault
public class MultiMCModpackExportTask extends Task<Void> {
    /// Repository containing the exported instance and its version manifests.
    private final DefaultGameRepository repository;

    /// Target installed instance identifier.
    private final String instanceId;

    /// Immutable exact-path whitelist applied while collecting instance files.
    private final @Unmodifiable List<String> whitelist;

    /// MultiMC instance properties written beside the component manifest.
    private final MultiMCInstanceConfiguration configuration;

    /// Destination archive path.
    private final Path output;

    /// Creates a MultiMC export task for one installed instance.
    ///
    /// The whitelist is copied so later caller mutations cannot change the running export.
    ///
    /// @param repository repository containing the target instance
    /// @param instanceId target installed instance identifier
    /// @param whitelist exact relative paths allowed in the archive
    /// @param configuration MultiMC instance properties to export
    /// @param output destination archive path
    public MultiMCModpackExportTask(
            DefaultGameRepository repository,
            String instanceId,
            List<String> whitelist,
            MultiMCInstanceConfiguration configuration,
            Path output) {
        this.repository = repository;
        this.instanceId = instanceId;
        this.whitelist = List.copyOf(whitelist);
        this.configuration = configuration;
        this.output = output;

        onDone().register(event -> {
            if (event.isFailed()) {
                try {
                    Files.deleteIfExists(output);
                } catch (IOException e) {
                    LOG.warning("Failed to delete modpack file: " + output, e);
                }
            }
        });
    }

    /// Writes selected instance files, the component manifest, and MultiMC instance properties.
    ///
    /// A failed task removes the partial destination archive through the completion listener installed
    /// by the constructor.
    ///
    /// @throws Exception if instance inspection, property serialization, or archive writing fails
    @Override
    public void execute() throws Exception {
        ArrayList<String> blackList = new ArrayList<>(ModAdviser.MODPACK_BLACK_LIST);
        blackList.add(instanceId + ".jar");
        blackList.add(instanceId + ".json");
        LOG.info("Compressing game files without some files in blacklist, including files or directories: usernamecache.json, asm, logs, backups, versions, assets, usercache.json, libraries, crash-reports, launcher_profiles.json, NVIDIA, TCNodeTracker");
        try (Zipper zip = new Zipper(output)) {
            zip.putDirectory(
                    repository.getRunDirectory(instanceId),
                    ".minecraft",
                    path -> Modpack.acceptFile(path, blackList, whitelist));

            String gameVersion = repository.getGameVersion(instanceId)
                    .orElseThrow(() -> new IOException("Cannot parse the game version of instance " + instanceId));
            LibraryAnalyzer analyzer = LibraryAnalyzer.analyze(
                    repository.getResolvedPreservingPatchesVersion(instanceId), gameVersion);
            List<MultiMCManifest.MultiMCManifestComponent> components = new ArrayList<>();
            components.add(new MultiMCManifest.MultiMCManifestComponent(true, false, MultiMCComponents.getComponent(MINECRAFT), gameVersion));

            for (Map.Entry<String, LibraryAnalyzer.LibraryType> pair : MultiMCComponents.getPairs()) {
                if (pair.getValue().isModLoader()) {
                    analyzer.getVersion(pair.getValue()).ifPresent(
                            v -> components.add(new MultiMCManifest.MultiMCManifestComponent(false, false, pair.getKey(), v))
                    );
                }
            }

            MultiMCManifest mmcPack = new MultiMCManifest(1, components);
            zip.putTextFile(JsonUtils.GSON.toJson(mmcPack), "mmc-pack.json");

            StringWriter writer = new StringWriter();
            configuration.toProperties().store(writer, "Auto generated by XYML");
            zip.putTextFile(writer.toString(), "instance.cfg");

            zip.putTextFile("", ".packignore");
        }
    }

    /// Metadata fields exposed for the MultiMC format.
    public static final ModpackExportInfo.Options OPTION = new ModpackExportInfo.Options()
            .requireAuthor()
            .requireMinMemory();
}
