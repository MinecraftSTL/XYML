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
package space.minecraftstl.xyml.modpack.server;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.download.LibraryAnalyzer;
import space.minecraftstl.xyml.game.DefaultGameRepository;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.modpack.ModAdviser;
import space.minecraftstl.xyml.modpack.Modpack;
import space.minecraftstl.xyml.modpack.ModpackConfiguration;
import space.minecraftstl.xyml.modpack.ModpackExportInfo;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.DigestUtils;
import space.minecraftstl.xyml.util.StringUtils;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.io.Zipper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static space.minecraftstl.xyml.download.LibraryAnalyzer.LibraryType.*;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Exports one installed instance as an XYML server-modpack archive.
@NotNullByDefault
public class ServerModpackExportTask extends Task<Void> {
    /// Repository containing the exported instance and its version manifests.
    private final DefaultGameRepository repository;
    private final GameInstanceID instanceId;
    private final ModpackExportInfo exportInfo;

    /// Destination archive path.
    private final Path modpackFile;

    public ServerModpackExportTask(DefaultGameRepository repository, GameInstanceID instanceId, ModpackExportInfo exportInfo, Path modpackFile) {
        this.repository = repository;
        this.instanceId = instanceId;
        this.exportInfo = exportInfo.validate();
        this.modpackFile = modpackFile;

        onDone().register(event -> {
            if (event.isFailed()) {
                try {
                    Files.deleteIfExists(modpackFile);
                } catch (IOException e) {
                    LOG.warning("Failed to delete modpack file: " + modpackFile, e);
                }
            }
        });
    }

    /// Writes selected instance files and the generated server manifest to the destination archive.
    ///
    /// A failed task removes the partial destination archive through the completion listener installed
    /// by the constructor.
    ///
    /// @throws Exception if instance inspection, hashing, manifest serialization, or archive writing fails
    @Override
    public void execute() throws Exception {
        ArrayList<String> blackList = new ArrayList<>(ModAdviser.MODPACK_BLACK_LIST);
        blackList.add(instanceId + ".jar");
        blackList.add(instanceId + ".json");
        LOG.info("Compressing game files without some files in blacklist, including files or directories: usernamecache.json, asm, logs, backups, versions, assets, usercache.json, libraries, crash-reports, launcher_profiles.json, NVIDIA, TCNodeTracker");
        try (Zipper zip = new Zipper(modpackFile)) {
            Path runDirectory = repository.getRunDirectory(instanceId);
            List<ModpackConfiguration.FileInformation> files = new ArrayList<>();
            zip.putDirectory(runDirectory, "overrides", path -> {
                if (Modpack.acceptFile(path, blackList, exportInfo.getWhitelist())) {
                    Path file = runDirectory.resolve(path);
                    if (Files.isRegularFile(file)) {
                        String relativePath = runDirectory.relativize(file).normalize().toString().replace(File.separatorChar, '/');
                        files.add(new ModpackConfiguration.FileInformation(relativePath, DigestUtils.digestToString("SHA-1", file)));
                    }
                    return true;
                } else {
                    return false;
                }
            });

            String gameVersion = repository.getGameVersion(instanceId)
                    .orElseThrow(() -> new IOException("Cannot parse the version of " + instanceId));
            LibraryAnalyzer analyzer = LibraryAnalyzer.analyze(repository.getResolvedInstanceManifest(instanceId), gameVersion);
            List<ServerModpackManifest.Addon> addons = new ArrayList<>();
            addons.add(new ServerModpackManifest.Addon(MINECRAFT.getPatchId(), gameVersion));
            analyzer.getVersion(FORGE).ifPresent(forgeVersion ->
                    addons.add(new ServerModpackManifest.Addon(FORGE.getPatchId(), forgeVersion)));
            analyzer.getVersion(NEO_FORGE).ifPresent(neoForgeVersion ->
                    addons.add(new ServerModpackManifest.Addon(NEO_FORGE.getPatchId(), neoForgeVersion)));
            analyzer.getVersion(LITELOADER).ifPresent(liteLoaderVersion ->
                    addons.add(new ServerModpackManifest.Addon(LITELOADER.getPatchId(), liteLoaderVersion)));
            analyzer.getVersion(OPTIFINE).ifPresent(optifineVersion ->
                    addons.add(new ServerModpackManifest.Addon(OPTIFINE.getPatchId(), optifineVersion)));
            analyzer.getVersion(FABRIC).ifPresent(fabricVersion ->
                    addons.add(new ServerModpackManifest.Addon(FABRIC.getPatchId(), fabricVersion)));
            analyzer.getVersion(QUILT).ifPresent(quiltVersion ->
                    addons.add(new ServerModpackManifest.Addon(QUILT.getPatchId(), quiltVersion)));
            ServerModpackManifest manifest = new ServerModpackManifest(exportInfo.getName(), exportInfo.getAuthor(), exportInfo.getVersion(), exportInfo.getDescription(), StringUtils.removeSuffix(exportInfo.getFileApi(), "/"), files, addons);
            zip.putTextFile(JsonUtils.GSON.toJson(manifest), "server-manifest.json");
        }
    }

    /// Metadata fields and export switches exposed for the server-modpack format.
    public static final ModpackExportInfo.Options OPTION = new ModpackExportInfo.Options()
            .requireAuthor()
            .requireFileApi(false);
}
