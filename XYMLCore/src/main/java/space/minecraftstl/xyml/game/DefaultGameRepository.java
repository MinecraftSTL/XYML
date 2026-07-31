/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.game;

import com.google.gson.JsonParseException;
import space.minecraftstl.xyml.download.MaintainTask;
import space.minecraftstl.xyml.download.game.VersionJsonSaveTask;
import space.minecraftstl.xyml.event.*;
import space.minecraftstl.xyml.game.tlauncher.TLauncherVersion;
import space.minecraftstl.xyml.addon.mod.ModManager;
import space.minecraftstl.xyml.modpack.ModpackConfiguration;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.Lang;
import space.minecraftstl.xyml.util.ToStringBuilder;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.io.FileUtils;
import space.minecraftstl.xyml.util.platform.Platform;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/**
 * An implementation of classic Minecraft game repository.
 *
 * @author huangyuhui
 */
public class DefaultGameRepository implements GameRepository {

    private Path baseDirectory;
    protected Map<String, Version> instances;
    private final ConcurrentHashMap<Path, Optional<String>> gameVersions = new ConcurrentHashMap<>();

    public DefaultGameRepository(Path baseDirectory) {
        this.baseDirectory = baseDirectory;
    }

    public Path getBaseDirectory() {
        return baseDirectory;
    }

    public void setBaseDirectory(Path baseDirectory) {
        this.baseDirectory = baseDirectory;
    }

    @Override
    public boolean hasVersion(String id) {
        return id != null && instances != null && instances.containsKey(id);
    }

    @Override
    public Version getVersion(String id) {
        if (!hasVersion(id))
            throw new VersionNotFoundException("Version manifest '" + id + "' does not exist in installed instances "
                    + (instances == null ? "[]" : instances.keySet()) + ".");
        return instances.get(id);
    }

    @Override
    public int getInstanceCount() {
        return instances == null ? 0 : instances.size();
    }

    @Override
    public Collection<Version> getInstances() {
        return instances == null ? Collections.emptySet() : instances.values();
    }

    @Override
    public Path getLibrariesDirectory(Version version) {
        return getBaseDirectory().resolve("libraries");
    }

    @Override
    public Path getLibraryFile(Version version, Library lib) {
        if ("local".equals(lib.getHint())) {
            if (lib.getFileName() != null) {
                return getVersionRoot(version.getId()).resolve("libraries/" + lib.getFileName());
            }

            return getVersionRoot(version.getId()).resolve("libraries/" + lib.getArtifact().getFileName());
        }

        return getLibrariesDirectory(version).resolve(lib.getPath());
    }

    public Path getArtifactFile(Version version, Artifact artifact) {
        return artifact.getPath(getBaseDirectory().resolve("libraries"));
    }

    @Override
    public Path getRunDirectory(String id) {
        return getBaseDirectory();
    }

    @Override
    public Path getVersionJar(Version version) {
        Version v = version.resolve(this);
        String id = Optional.ofNullable(v.getJar()).orElse(v.getId());
        return getVersionRoot(id).resolve(id + ".jar");
    }

    @Override
    public Optional<String> getGameVersion(Version version) {
        // This implementation may cause multiple flows against the same version entering
        // this function, which is accepted because GameVersion::minecraftVersion should
        // be consistent.
        return gameVersions.computeIfAbsent(getVersionJar(version), versionJar -> {
            Optional<String> gameVersion = GameVersion.minecraftVersion(versionJar);
            if (gameVersion.isEmpty()) {
                LOG.warning("Cannot find out game version of " + version.getId() + ", primary jar: " + versionJar.toString() + ", jar exists: " + Files.exists(versionJar));
            }
            return gameVersion;
        });
    }

    @Override
    public Path getNativeDirectory(String id, Platform platform) {
        return getVersionRoot(id).resolve("natives-" + platform);
    }

    @Override
    public Path getModsDirectory(String id) {
        return getRunDirectory(id).resolve("mods");
    }

    @Override
    public Path getResourcePackDirectory(String id) {
        return getRunDirectory(id).resolve("resourcepacks");
    }

    @Override
    public Path getVersionRoot(String id) {
        return getBaseDirectory().resolve("versions/" + id);
    }

    public Path getVersionJson(String id) {
        return getVersionRoot(id).resolve(id + ".json");
    }

    public Version readVersionJson(String id) throws IOException, JsonParseException {
        return readVersionJson(getVersionJson(id));
    }

    public Version readVersionJson(Path file) throws IOException, JsonParseException {
        String jsonText = Files.readString(file);
        try {
            // Try TLauncher version json format
            return JsonUtils.fromNonNullJson(jsonText, TLauncherVersion.class).toVersion();
        } catch (JsonParseException ignored) {
        }

        try {
            // Try official version json format
            return JsonUtils.fromNonNullJson(jsonText, Version.class);
        } catch (JsonParseException ignored) {
        }

        LOG.warning("Cannot parse version json: " + file + "\n" + jsonText);
        throw new JsonParseException("Version json incorrect");
    }

    @Override
    public boolean renameInstance(String from, String to) {
        if (EventBus.EVENT_BUS.fireEvent(new RenameInstanceEvent(this, from, to)) == Event.Result.DENY)
            return false;

        try {
            Version fromVersion = getVersion(from);
            Path fromDir = getVersionRoot(from);
            Path toDir = getVersionRoot(to);
            Files.move(fromDir, toDir);

            Path fromJson = toDir.resolve(from + ".json");
            Path fromJar = toDir.resolve(from + ".jar");
            Path toJson = toDir.resolve(to + ".json");
            Path toJar = toDir.resolve(to + ".jar");

            boolean hasJarFile = Files.exists(fromJar);

            try {
                Files.move(fromJson, toJson);
                if (hasJarFile) Files.move(fromJar, toJar);
            } catch (IOException e) {
                // recovery
                Lang.ignoringException(() -> Files.move(toJson, fromJson));
                if (hasJarFile) Lang.ignoringException(() -> Files.move(toJar, fromJar));
                Lang.ignoringException(() -> Files.move(toDir, fromDir));
                throw e;
            }

            if (fromVersion.getId().equals(fromVersion.getJar()))
                fromVersion = fromVersion.setJar(null);
            JsonUtils.writeToJsonFile(toJson, fromVersion.setId(to));

            // fix inheritsFrom of versions that inherits from version [from].
            for (Version version : getInstances()) {
                if (from.equals(version.getInheritsFrom())) {
                    Path targetPath = getVersionJson(version.getId());
                    Files.createDirectories(targetPath.getParent());
                    JsonUtils.writeToJsonFile(targetPath, version.setInheritsFrom(to));
                }
            }
            return true;
        } catch (IOException | JsonParseException | VersionNotFoundException | InvalidPathException e) {
            LOG.warning("Unable to rename version " + from + " to " + to, e);
            return false;
        }
    }

    public boolean removeInstanceFromDisk(String id) {
        if (EventBus.EVENT_BUS.fireEvent(new RemoveInstanceEvent(this, id)) == Event.Result.DENY)
            return false;
        if (instances == null || !instances.containsKey(id))
            return FileUtils.deleteDirectoryQuietly(getVersionRoot(id));
        Path file = getVersionRoot(id);
        if (Files.notExists(file))
            return true;
        // test if no file in this version directory is occupied.
        Path removedFile = file.toAbsolutePath().resolveSibling(FileUtils.getName(file) + "_removed");
        try {
            Files.move(file, removedFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.warning("Failed to rename file " + file, e);
            return false;
        }

        try {
            instances.remove(id);

            if (FileUtils.moveToTrash(removedFile)) {
                return true;
            }

            // Remove JSON files first to ensure XYML will not recognize this folder as a valid game version.

            for (Path path : FileUtils.listFilesByExtension(removedFile, "json")) {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    LOG.warning("Failed to delete file " + path, e);
                }
            }

            // remove the version from version list regardless of whether the directory was removed successfully or not.
            try {
                FileUtils.deleteDirectory(removedFile);
            } catch (IOException e) {
                LOG.warning("Unable to remove instance folder: " + file, e);
            }
            return true;
        } finally {
            refreshInstancesAsync().start();
        }
    }

    protected void refreshInstancesImpl() {
        Map<String, Version> instances = new TreeMap<>();

        if (ClassicVersion.hasClassicVersion(getBaseDirectory())) {
            Version version = new ClassicVersion();
            instances.put(version.getId(), version);
        }

        SimpleVersionProvider provider = new SimpleVersionProvider();

        Path versionsDir = getBaseDirectory().resolve("versions");
        if (Files.isDirectory(versionsDir)) {
            try (Stream<Path> stream = Files.list(versionsDir)) {
                stream.parallel().filter(Files::isDirectory).flatMap(dir -> {
                    String id = FileUtils.getName(dir);
                    Path json = dir.resolve(id + ".json");

                    // If user renamed the json file by mistake or created the json file in a wrong name,
                    // we will find the only json and rename it to correct name.
                    if (Files.notExists(json)) {
                        List<Path> jsons = FileUtils.listFilesByExtension(dir, "json");
                        if (jsons.size() == 1) {
                            LOG.info("Renaming json file " + jsons.get(0) + " to " + json);

                            try {
                                Files.move(jsons.get(0), json);
                            } catch (IOException e) {
                                LOG.warning("Cannot rename json file, ignoring version " + id, e);
                                return Stream.empty();
                            }

                            Path jar = dir.resolve(FileUtils.getNameWithoutExtension(jsons.get(0)) + ".jar");
                            if (Files.exists(jar)) {
                                try {
                                    Files.move(jar, dir.resolve(id + ".jar"));
                                } catch (IOException e) {
                                    LOG.warning("Cannot rename jar file, ignoring version " + id, e);
                                    return Stream.empty();
                                }
                            }
                        } else {
                            LOG.info("No available json file found, ignoring version " + id);
                            return Stream.empty();
                        }
                    }

                    Version version;
                    try {
                        version = readVersionJson(json);
                    } catch (Exception e) {
                        LOG.warning("Malformed version json " + id, e);
                        // JsonSyntaxException or IOException or NullPointerException(!!)
                        if (EventBus.EVENT_BUS.fireEvent(new GameJsonParseFailedEvent(this, json, id)) != Event.Result.ALLOW)
                            return Stream.empty();

                        try {
                            version = readVersionJson(json);
                        } catch (Exception e2) {
                            LOG.error("User corrected version json is still malformed", e2);
                            return Stream.empty();
                        }
                    }

                    if (!id.equals(version.getId())) {
                        try {
                            String from = id;
                            String to = version.getId();
                            Path fromDir = getVersionRoot(from);
                            Path toDir = getVersionRoot(to);
                            Files.move(fromDir, toDir);

                            Path fromJson = toDir.resolve(from + ".json");
                            Path fromJar = toDir.resolve(from + ".jar");
                            Path toJson = toDir.resolve(to + ".json");
                            Path toJar = toDir.resolve(to + ".jar");

                            try {
                                Files.move(fromJson, toJson);
                                if (Files.exists(fromJar))
                                    Files.move(fromJar, toJar);
                            } catch (IOException e) {
                                // recovery
                                Lang.ignoringException(() -> Files.move(toJson, fromJson));
                                Lang.ignoringException(() -> Files.move(toJar, fromJar));
                                Lang.ignoringException(() -> Files.move(toDir, fromDir));
                                throw e;
                            }
                        } catch (IOException e) {
                            LOG.warning("Ignoring version " + version.getId() + " because version id does not match folder name " + id + ", and we cannot correct it.", e);
                            return Stream.empty();
                        }
                    }

                    return Stream.of(version);
                }).forEachOrdered(provider::addVersion);
            } catch (IOException e) {
                LOG.warning("Failed to load versions from " + versionsDir, e);
            }
        }

        for (Version version : provider.getVersionMap().values()) {
            try {
                Version resolved = version.resolve(provider);

                if (resolved.appliesToCurrentEnvironment() &&
                        EventBus.EVENT_BUS.fireEvent(new LoadedOneInstanceEvent(this, resolved)) != Event.Result.DENY)
                    instances.put(version.getId(), version);
            } catch (VersionNotFoundException e) {
                LOG.warning("Ignoring version " + version.getId() + " because it inherits from a nonexistent version.");
            }
        }

        this.gameVersions.clear();
        this.instances = instances;
    }

    @Override
    public void refreshInstances() {
        if (EventBus.EVENT_BUS.fireEvent(new RefreshingInstancesEvent(this)) == Event.Result.DENY)
            return;

        refreshInstancesImpl();
        EventBus.EVENT_BUS.fireEvent(new RefreshedInstancesEvent(this));
    }

    @Override
    public AssetIndex getAssetIndex(String version, String assetId) throws IOException {
        try {
            return Objects.requireNonNull(JsonUtils.fromJsonFile(getIndexFile(version, assetId), AssetIndex.class));
        } catch (JsonParseException | NullPointerException e) {
            throw new IOException("Asset index file malformed", e);
        }
    }

    @Override
    public Path getActualAssetDirectory(String version, String assetId) {
        try {
            return reconstructAssets(version, assetId);
        } catch (IOException | JsonParseException e) {
            LOG.error("Unable to reconstruct asset directory", e);
            return getAssetDirectory(version, assetId);
        }
    }

    @Override
    public Path getAssetDirectory(String version, String assetId) {
        return getBaseDirectory().resolve("assets");
    }

    @Override
    public Optional<Path> getAssetObject(String version, String assetId, String name) throws IOException {
        try {
            AssetObject assetObject = getAssetIndex(version, assetId).getObjects().get(name);
            if (assetObject == null) return Optional.empty();
            return Optional.of(getAssetObject(version, assetId, assetObject));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Unrecognized asset object " + name + " in asset " + assetId + " of version " + version, e);
        }
    }

    @Override
    public Path getAssetObject(String version, String assetId, AssetObject obj) {
        return getAssetObject(version, getAssetDirectory(version, assetId), obj);
    }

    public Path getAssetObject(String version, Path assetDir, AssetObject obj) {
        return assetDir.resolve("objects").resolve(obj.getLocation());
    }

    @Override
    public Path getIndexFile(String version, String assetId) {
        return getAssetDirectory(version, assetId).resolve("indexes").resolve(assetId + ".json");
    }

    @Override
    public Path getLoggingObject(String version, String assetId, LoggingInfo loggingInfo) {
        return getAssetDirectory(version, assetId).resolve("log_configs").resolve(loggingInfo.file().getId());
    }

    protected Path reconstructAssets(String version, String assetId) throws IOException, JsonParseException {
        Path assetsDir = getAssetDirectory(version, assetId);
        Path indexFile = getIndexFile(version, assetId);
        Path virtualRoot = assetsDir.resolve("virtual").resolve(assetId);

        if (!Files.isRegularFile(indexFile))
            return assetsDir;

        AssetIndex index = JsonUtils.fromJsonFile(indexFile, AssetIndex.class);

        if (index == null)
            return assetsDir;

        if (index.isVirtual()) {
            Path resourcesDir = getRunDirectory(version).resolve("resources");

            int cnt = 0;
            int tot = index.getObjects().size();
            for (Map.Entry<String, AssetObject> entry : index.getObjects().entrySet()) {
                Path target = virtualRoot.resolve(entry.getKey());
                Path original = getAssetObject(version, assetsDir, entry.getValue());
                if (Files.exists(original)) {
                    cnt++;
                    if (!Files.isRegularFile(target))
                        FileUtils.copyFile(original, target);

                    if (index.needMapToResources()) {
                        target = resourcesDir.resolve(entry.getKey());
                        if (!Files.isRegularFile(target))
                            FileUtils.copyFile(original, target);
                    }
                }
            }

            // If the scale new format existent file is lower then 0.1, use the old format.
            if (cnt * 10 < tot)
                return assetsDir;
            else
                return virtualRoot;
        }

        return assetsDir;
    }

    public Task<Version> saveAsync(Version version) {
        this.gameVersions.remove(getVersionJar(version));
        if (version.isResolvedPreservingPatches()) {
            return new VersionJsonSaveTask(this, MaintainTask.maintainPreservingPatches(this, version));
        } else {
            return new VersionJsonSaveTask(this, version);
        }
    }

    public boolean isLoaded() {
        return instances != null;
    }

    /// Returns the modpack configuration path for an installed instance.
    ///
    /// @param instanceId target installed instance identifier
    /// @return path to the instance's `modpack.json`
    public Path getModpackConfiguration(String instanceId) {
        return getVersionRoot(instanceId).resolve("modpack.json");
    }

    /// Reads the modpack configuration for an installed instance.
    ///
    /// @param instanceId target installed instance identifier
    /// @return modpack configuration object, or `null` if the instance is not a modpack
    /// @throws VersionNotFoundException if the instance does not exist
    /// @throws IOException if an I/O error occurs
    @Nullable
    public ModpackConfiguration<?> readModpackConfiguration(String instanceId) throws IOException, VersionNotFoundException {
        if (!hasVersion(instanceId)) throw new VersionNotFoundException(instanceId);
        Path file = getModpackConfiguration(instanceId);
        if (Files.notExists(file)) return null;
        return JsonUtils.fromJsonFile(file, ModpackConfiguration.class);
    }

    /// Returns whether an installed instance has modpack configuration metadata.
    ///
    /// @param instanceId target installed instance identifier
    /// @return whether the instance contains `modpack.json`
    public boolean isModpack(String instanceId) {
        return Files.exists(getModpackConfiguration(instanceId));
    }

    /// Creates a mod manager scoped to one installed instance.
    ///
    /// @param instanceId target installed instance identifier
    /// @return mod manager bound to the instance
    public ModManager getModManager(String instanceId) {
        return new ModManager(this, instanceId);
    }

    public Path getSavesDirectory(String id) {
        return getRunDirectory(id).resolve("saves");
    }

    public Path getBackupsDirectory(String id) {
        return getRunDirectory(id).resolve("backups");
    }

    public Path getSchematicsDirectory(String id) {
        return getRunDirectory(id).resolve("schematics");
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("instances", instances == null ? null : instances.keySet())
                .append("baseDirectory", baseDirectory)
                .toString();
    }
}
