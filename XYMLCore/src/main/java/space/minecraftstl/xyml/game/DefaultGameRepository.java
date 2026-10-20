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
package space.minecraftstl.xyml.game;

import com.google.gson.JsonParseException;
import space.minecraftstl.xyml.addon.mod.ModManager;
import space.minecraftstl.xyml.addon.resourcepack.ResourcePackManager;
import space.minecraftstl.xyml.download.MaintainTask;
import space.minecraftstl.xyml.event.*;
import space.minecraftstl.xyml.modpack.ModpackConfiguration;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.Lang;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.io.DeletionMode;
import space.minecraftstl.xyml.util.io.FileUtils;
import space.minecraftstl.xyml.util.io.TrashMoveException;
import space.minecraftstl.xyml.util.platform.Platform;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.stream.Stream;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Filesystem-backed repository of installed game-instance manifests and shared Minecraft assets.
@NotNullByDefault
public class DefaultGameRepository implements GameRepository {

    private static final GameInstanceManifest CLASSIC_MANIFEST = new GameInstanceManifest(
            new GameInstanceID("Classic"),
            "${auth_player_name} ${auth_session} --workDir ${game_directory}",
            null,
            "net.minecraft.client.Minecraft",
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(
                    classicLibrary("lwjgl"),
                    classicLibrary("jinput"),
                    classicLibrary("lwjgl_util")),
            null,
            null,
            null,
            ReleaseType.UNKNOWN,
            null,
            null,
            0,
            false,
            false,
            null,
            null
    );

    private static Library classicLibrary(String name) {
        return new Library(new Artifact("", "", ""), null,
                new LibrariesDownloadInfo(new LibraryDownloadInfo("bin/" + name + ".jar"), null),
                null, null, null, null, null, null);
    }

    private static boolean hasClassicVersion(Path baseDirectory) {
        Path bin = baseDirectory.resolve("bin");
        return Files.isDirectory(bin)
                && Files.exists(bin.resolve("lwjgl.jar"))
                && Files.exists(bin.resolve("jinput.jar"))
                && Files.exists(bin.resolve("lwjgl_util.jar"));
    }

    /// Serializes final commit phases while allowing unrelated draft preparation.
    private final java.util.concurrent.locks.ReentrantLock draftCommitLock = new java.util.concurrent.locks.ReentrantLock();

    /// In-process recovery quarantine survives repository-root switches.
    private final Set<String> recoveryRequired = ConcurrentHashMap.newKeySet();

    /// Revision advanced by complete metadata publications.
    private long publicationRevision;

    private volatile Status status;
    private volatile boolean loaded;
    private final ConcurrentHashMap<Path, Optional<String>> gameVersions = new ConcurrentHashMap<>();

    public DefaultGameRepository(Path baseDirectory) {
        this.status = new Status(baseDirectory);
    }

    public Path getBaseDirectory() {
        return status.baseDirectory;
    }

    /// Replaces the repository root while excluding an asynchronous refresh that captured the previous root.
    ///
    /// @param baseDirectory replacement repository root
    public synchronized void setBaseDirectory(Path baseDirectory) {
        this.status = new Status(baseDirectory);
        this.loaded = false;
        this.gameVersions.clear();
    }

    public boolean isLoaded() {
        return loaded;
    }

    @Override
    public void refresh() {
        if (EventBus.EVENT_BUS.fireEvent(new RefreshingInstancesEvent(this)) == Event.Result.DENY) {
            return;
        }

        refreshImpl();
        loaded = true;
        EventBus.EVENT_BUS.fireEvent(new RefreshedGameInstancesEvent(this));
    }

    /// Creates a refresh task scoped to the repository root captured at task construction.
    ///
    /// The repository monitor prevents [#setBaseDirectory(Path)] from replacing that root during the refresh. A task
    /// that has already become stale fails before touching the replacement repository instead of using a mismatched
    /// resource declaration.
    ///
    /// @return stopped refresh task occupying the complete captured game directory
    @Override
    public Task<Void> refreshAsync() {
        Path expectedDirectory = getBaseDirectory().toAbsolutePath().normalize();
        return Task.runAsync(() -> {
            synchronized (this) {
                if (!expectedDirectory.equals(getBaseDirectory().toAbsolutePath().normalize())) {
                    throw new IllegalStateException("Game repository root changed before refresh execution");
                }
                refresh();
            }
        }).setResources(TaskResource.gameDirectory(expectedDirectory));
    }

    /// Reloads the index while excluding detached snapshot capture during publication.
    protected synchronized void refreshImpl() {
        Status newStatus = new Status(status.baseDirectory);

        if (hasClassicVersion(newStatus.baseDirectory)) {
            GameInstanceID id = CLASSIC_MANIFEST.id();
            newStatus.instances.put(id, new InstanceHolder(newStatus, id, CLASSIC_MANIFEST));
        }

        Path versionsDir = newStatus.baseDirectory.resolve("versions");
        if (Files.isDirectory(versionsDir)) {
            try (Stream<Path> stream = Files.list(versionsDir)) {
                stream.parallel().filter(Files::isDirectory).flatMap(dir -> {
                    GameInstanceID id;
                    try {
                        id = new GameInstanceID(FileUtils.getName(dir));
                    } catch (IllegalArgumentException e) {
                        LOG.warning("Ignoring instance folder with invalid id " + dir, e);
                        return Stream.empty();
                    }

                    Path json = dir.resolve(id + ".json");

                    if (Files.notExists(json)) {
                        List<Path> jsons = FileUtils.listFilesByExtension(dir, "json");
                        if (jsons.size() == 1) {
                            LOG.info("Renaming json file " + jsons.get(0) + " to " + json);

                            try {
                                Files.move(jsons.get(0), json);
                            } catch (IOException e) {
                                LOG.warning("Cannot rename json file, ignoring instance " + id, e);
                                return Stream.empty();
                            }

                            Path jar = dir.resolve(FileUtils.getNameWithoutExtension(jsons.get(0)) + ".jar");
                            if (Files.exists(jar)) {
                                try {
                                    Files.move(jar, dir.resolve(id + ".jar"));
                                } catch (IOException e) {
                                    LOG.warning("Cannot rename jar file, ignoring instance " + id, e);
                                    return Stream.empty();
                                }
                            }
                        } else {
                            LOG.info("No available json file found, ignoring instance " + id);
                            return Stream.empty();
                        }
                    }

                    GameInstanceManifest manifest;
                    try {
                        manifest = readInstanceManifest(json);
                    } catch (Exception e) {
                        LOG.warning("Malformed instance manifest " + id, e);
                        if (EventBus.EVENT_BUS.fireEvent(new GameJsonParseFailedEvent(this, json, id)) != Event.Result.ALLOW) {
                            return Stream.empty();
                        }

                        try {
                            manifest = readInstanceManifest(json);
                        } catch (Exception e2) {
                            LOG.error("User-corrected instance manifest is still malformed", e2);
                            return Stream.empty();
                        }
                    }

                    if (!id.equals(manifest.id())) {
                        try {
                            moveInstanceFiles(newStatus.baseDirectory, id, manifest.id());
                        } catch (IOException e) {
                            LOG.warning("Ignoring instance " + manifest.id()
                                    + " because instance id does not match folder name " + id
                                    + ", and we cannot correct it.", e);
                            return Stream.empty();
                        }
                    }

                    return Stream.of(manifest);
                }).forEachOrdered(it -> newStatus.instances.put(
                        it.id(),
                        new InstanceHolder(newStatus, it.id(), it)));
            } catch (IOException e) {
                LOG.warning("Failed to load instances from " + versionsDir, e);
            }
        }

        Map<GameInstanceID, InstanceHolder> loadedInstances = new TreeMap<>();
        for (InstanceHolder holder : newStatus.instances.values()) {
            try {
                GameInstanceManifest resolved = newStatus.resolve(holder.manifest, new HashSet<>()).launchManifest();
                if (CompatibilityRule.appliesToCurrentEnvironment(resolved.compatibilityRules())) {
                    loadedInstances.put(holder.id, holder);
                }
            } catch (NoSuchGameInstanceException e) {
                LOG.warning("Ignoring instance " + holder.id + " because it inherits from a nonexistent instance.");
            }
        }

        newStatus.instances.clear();
        newStatus.instances.putAll(loadedInstances);
        gameVersions.clear();
        this.status = newStatus;
    }

    private static GameInstanceManifest readInstanceManifest(Path json) throws IOException, JsonParseException {
        GameInstanceManifest manifest = JsonUtils.fromJsonFile(json, GameInstanceManifest.class);
        if (manifest == null) {
            throw new JsonParseException("Manifest is null");
        }
        return manifest;
    }

    private static void moveInstanceFiles(Path baseDirectory, GameInstanceID from, GameInstanceID to) throws IOException {
        Path versionsDir = baseDirectory.resolve("versions");
        Path fromDir = versionsDir.resolve(from.id());
        Path toDir = versionsDir.resolve(to.id());
        Files.move(fromDir, toDir);

        Path fromJson = toDir.resolve(from + ".json");
        Path fromJar = toDir.resolve(from + ".jar");
        Path toJson = toDir.resolve(to + ".json");
        Path toJar = toDir.resolve(to + ".jar");

        boolean hasJarFile = Files.exists(fromJar);

        try {
            Files.move(fromJson, toJson);
            if (hasJarFile) {
                Files.move(fromJar, toJar);
            }
        } catch (IOException e) {
            Lang.ignoringException(() -> Files.move(toJson, fromJson));
            if (hasJarFile) {
                Lang.ignoringException(() -> Files.move(toJar, fromJar));
            }
            Lang.ignoringException(() -> Files.move(toDir, fromDir));
            throw e;
        }
    }

    @Override
    public boolean hasInstance(GameInstanceID instanceId) {
        return status.instances.containsKey(instanceId);
    }

    @Override
    public GameInstanceManifest getInstanceManifest(GameInstanceID instanceId) throws NoSuchGameInstanceException {
        InstanceHolder instanceHolder = status.instances.get(instanceId);
        if (instanceHolder == null) {
            throw new NoSuchGameInstanceException(instanceId);
        }
        return instanceHolder.manifest;
    }

    @Override
    public GameInstanceManifest.Resolved getResolvedInstanceManifest(GameInstanceID instanceId) throws NoSuchGameInstanceException {
        checkDraftInstanceAvailable(instanceId);
        Status currentStatus = status;

        InstanceHolder instanceHolder = currentStatus.instances.get(instanceId);
        if (instanceHolder == null) {
            throw new NoSuchGameInstanceException(instanceId);
        }

        GameInstanceManifest.Resolved resolvedManifest = instanceHolder.resolvedManifest;
        if (resolvedManifest == null) {
            resolvedManifest = currentStatus.resolve(instanceHolder.manifest, new HashSet<>());
            instanceHolder.resolvedManifest = resolvedManifest;
        }
        return resolvedManifest;
    }

    @Override
    public int getInstanceCount() {
        return status.instances.size();
    }

    @Override
    public Path getInstanceRoot(GameInstanceID instanceId) {
        return getBaseDirectory().resolve("versions").resolve(instanceId.id());
    }

    @Override
    public Collection<GameInstanceManifest> getInstanceManifests() {
        return status.instances.values().stream().map(i -> i.manifest).toList();
    }

    @Override
    public Path getLibrariesDirectory(GameInstanceManifest manifest) {
        return getBaseDirectory().resolve("libraries");
    }

    @Override
    public Path getLibraryFile(GameInstanceManifest manifest, Library lib) {
        if ("local".equals(lib.hint())) {
            if (lib.filename() != null) {
                return getInstanceRoot(manifest.id()).resolve("libraries/" + lib.filename());
            }

            return getInstanceRoot(manifest.id()).resolve("libraries/" + lib.artifact().getFileName());
        }

        return getLibrariesDirectory(manifest).resolve(lib.getPath());
    }

    public Path getArtifactFile(GameInstanceManifest manifest, Artifact artifact) {
        return artifact.getPath(getBaseDirectory().resolve("libraries"));
    }

    @Override
    public Path getRunDirectory(GameInstanceID instanceId) {
        return getBaseDirectory();
    }

    @Override
    public Path getInstanceJar(GameInstanceID instanceId) throws NoSuchGameInstanceException {
        return getInstanceJar(getResolvedInstanceManifest(instanceId).launchManifest());
    }

    @Override
    public Path getInstanceJar(GameInstanceManifest manifest) {
        GameInstanceManifest resolved = this.resolve(manifest).launchManifest();
        GameInstanceID id = Optional.ofNullable(resolved.jar()).orElse(resolved.id());
        return getInstanceRoot(id).resolve(id + ".jar");
    }

    /// Renames files and publishes their references while excluding detached snapshot capture.
    @Override
    public synchronized boolean renameInstance(GameInstanceID from, GameInstanceID to) {
        if (EventBus.EVENT_BUS.fireEvent(new RenameInstanceEvent(this, from, to)) == Event.Result.DENY) {
            return false;
        }

        try {
            Status currentStatus = status;
            InstanceHolder fromHolder = currentStatus.instances.get(from);
            if (fromHolder == null) {
                throw new NoSuchGameInstanceException(from);
            }

            moveInstanceFiles(currentStatus.baseDirectory, from, to);

            GameInstanceManifest renamedManifest = fromHolder.manifest;
            if (from.equals(renamedManifest.jar())) {
                renamedManifest = renamedManifest.withJar(null);
            }
            renamedManifest = renamedManifest.withId(to);
            JsonUtils.writeToJsonFile(getInstanceJson(to), renamedManifest);

            Map<GameInstanceID, GameInstanceManifest> updatedChildren = new TreeMap<>();

            for (InstanceHolder holder : currentStatus.instances.values()) {
                GameInstanceManifest manifest = holder.manifest;
                if (from.equals(manifest.inheritsFrom())) {
                    GameInstanceManifest updatedManifest = manifest.withInheritsFrom(to);
                    Path targetPath = getInstanceJson(updatedManifest.id());
                    Files.createDirectories(targetPath.getParent());
                    JsonUtils.writeToJsonFile(targetPath, updatedManifest);
                    updatedChildren.put(updatedManifest.id(), updatedManifest);
                }
            }

            currentStatus.instances.put(to, new InstanceHolder(currentStatus, to, renamedManifest));
            updatedChildren.forEach((id, manifest) ->
                    currentStatus.instances.put(id, new InstanceHolder(currentStatus, id, manifest)));
            currentStatus.instances.remove(from);
            gameVersions.clear();
            return true;
        } catch (IOException | JsonParseException | NoSuchGameInstanceException | InvalidPathException e) {
            LOG.warning("Unable to rename instance " + from + " to " + to, e);
            return false;
        }
    }

    /// Removes an instance from disk and schedules a repository refresh after an attempted directory deletion.
    ///
    /// @param id instance identifier to remove
    /// @return whether the instance was absent or its directory was moved out of the repository
    public boolean removeInstanceFromDisk(GameInstanceID id) {
        return removeInstanceFromDisk(id, true);
    }

    /// Removes an instance from disk without scheduling a repository refresh.
    ///
    /// This variant lets a caller protect the precise disk mutation separately from the broader repository refresh.
    /// The caller is responsible for refreshing the repository after releasing any narrower instance resources.
    ///
    /// @param id instance identifier to remove
    /// @return whether the instance was absent or its directory was moved out of the repository
    public boolean removeInstanceFromDiskWithoutRefresh(GameInstanceID id) {
        return removeInstanceFromDisk(id, false);
    }

    /// Moves one instance directory to the recycle bin without scheduling a repository refresh.
    ///
    /// @param id instance identifier to remove
    /// @throws IOException when the event is denied, the path cannot be moved, or an I/O failure occurs
    public void removeInstanceToTrashWithoutRefresh(GameInstanceID id) throws IOException {
        removeInstanceWithMode(id, DeletionMode.RECYCLE_BIN_FIRST);
    }

    /// Permanently removes one instance directory without scheduling a repository refresh.
    ///
    /// @param id instance identifier to remove
    /// @throws IOException when the event is denied or the path cannot be removed
    public void removeInstancePermanentlyWithoutRefresh(GameInstanceID id) throws IOException {
        removeInstanceWithMode(id, DeletionMode.PERMANENT);
    }

    /// Removes one instance through a caller-selected recycle-bin or permanent mode.
    ///
    /// @param id instance identifier to remove
    /// @param mode requested deletion behavior
    /// @throws IOException when the event is denied or the selected operation fails
    private synchronized void removeInstanceWithMode(GameInstanceID id, DeletionMode mode) throws IOException {
        if (EventBus.EVENT_BUS.fireEvent(new RemoveInstanceEvent(this, id)) == Event.Result.DENY) {
            throw new IOException("Instance removal was denied");
        }

        Path file = getInstanceRoot(id);
        if (Files.notExists(file)) {
            status.instances.remove(id);
            return;
        }

        if (mode == DeletionMode.RECYCLE_BIN_FIRST) {
            if (!FileUtils.moveToTrash(file)) {
                if (Files.exists(file)) {
                    throw new TrashMoveException(List.of(file));
                }
                status.instances.remove(id);
                return;
            }
            status.instances.remove(id);
            return;
        }

        Path removedFile = file.toAbsolutePath().resolveSibling(FileUtils.getName(file) + "_removed");
        Files.move(file, removedFile, StandardCopyOption.REPLACE_EXISTING);
        status.instances.remove(id);
        FileUtils.forceDelete(removedFile);
    }

    /// Removes one instance and optionally preserves the legacy asynchronous refresh side effect.
    ///
    /// @param id instance identifier to remove
    /// @param refreshAfterDeletion whether an asynchronous refresh should follow a directory deletion attempt
    /// @return whether the instance was absent or its directory was moved out of the repository
    private synchronized boolean removeInstanceFromDisk(GameInstanceID id, boolean refreshAfterDeletion) {
        if (EventBus.EVENT_BUS.fireEvent(new RemoveInstanceEvent(this, id)) == Event.Result.DENY) {
            return false;
        }

        Status currentStatus = status;
        currentStatus.instances.remove(id);

        Path file = getInstanceRoot(id);
        if (Files.notExists(file)) {
            return true;
        }

        Path removedFile = file.toAbsolutePath().resolveSibling(FileUtils.getName(file) + "_removed");
        try {
            Files.move(file, removedFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.warning("Unable to remove instance folder: " + file, e);
            return false;
        }

        try {
            if (FileUtils.moveToTrash(removedFile)) {
                return true;
            }

            for (Path path : FileUtils.listFilesByExtension(removedFile, "json")) {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    LOG.warning("Failed to delete file " + path, e);
                }
            }

            try {
                FileUtils.deleteDirectory(removedFile);
            } catch (IOException e) {
                LOG.warning("Unable to remove instance folder: " + file, e);
            }
            return true;
        } finally {
            if (refreshAfterDeletion) {
                refreshAsync().start();
            }
        }
    }

    @Override
    public Optional<String> getGameVersion(GameInstanceManifest manifest) {
        try {
            GameInstanceManifest resolved = resolve(manifest).launchManifest();
            Path instanceJar = getInstanceJar(resolved);
            return gameVersions.computeIfAbsent(instanceJar, jar -> {
                Optional<String> gameVersion = GameVersion.minecraftVersion(jar);
                if (gameVersion.isEmpty()) {
                    LOG.warning("Cannot find out game version of " + manifest.id()
                            + ", primary jar: " + jar
                            + ", jar exists: " + Files.exists(jar));
                }
                return gameVersion;
            });
        } catch (NoSuchGameInstanceException e) {
            return Optional.empty();
        }
    }

    @Override
    public Path getNativeDirectory(GameInstanceID instanceId, Platform platform) {
        return getInstanceRoot(instanceId).resolve("natives-" + platform);
    }

    @Override
    public Path getModsDirectory(GameInstanceID instanceId) {
        return getRunDirectory(instanceId).resolve("mods");
    }

    @Override
    public Path getResourcePackDirectory(GameInstanceID instanceId) {
        return getRunDirectory(instanceId).resolve("resourcepacks");
    }

    public Path getInstanceJson(GameInstanceID instanceId) {
        return getInstanceRoot(instanceId).resolve(instanceId.id() + ".json");
    }

    @Override
    public AssetIndex getAssetIndex(GameInstanceID instanceId, String assetId) throws IOException {
        try {
            return Objects.requireNonNull(JsonUtils.fromJsonFile(getIndexFile(instanceId, assetId), AssetIndex.class));
        } catch (JsonParseException | NullPointerException e) {
            throw new IOException("Asset index file malformed", e);
        }
    }

    @Override
    public Path getActualAssetDirectory(GameInstanceID instanceId, String assetId) {
        try {
            return reconstructAssets(instanceId, assetId);
        } catch (IOException | JsonParseException e) {
            LOG.error("Unable to reconstruct asset directory", e);
            return getAssetDirectory(instanceId, assetId);
        }
    }

    @Override
    public Path getAssetDirectory(GameInstanceID instanceId, String assetId) {
        return getBaseDirectory().resolve("assets");
    }

    @Override
    public Optional<Path> getAssetObject(GameInstanceID instanceId, String assetId, String name) throws IOException {
        try {
            AssetObject assetObject = getAssetIndex(instanceId, assetId).getObjects().get(name);
            if (assetObject == null) return Optional.empty();
            return Optional.of(getAssetObject(instanceId, assetId, assetObject));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Unrecognized asset object " + name + " in asset " + assetId + " of instance " + instanceId, e);
        }
    }

    @Override
    public Path getAssetObject(GameInstanceID instanceId, String assetId, AssetObject obj) {
        return getAssetObject(instanceId, getAssetDirectory(instanceId, assetId), obj);
    }

    public Path getAssetObject(GameInstanceID instanceId, Path assetDir, AssetObject obj) {
        return assetDir.resolve("objects").resolve(obj.getLocation());
    }

    @Override
    public Path getIndexFile(GameInstanceID instanceId, String assetId) {
        return getAssetDirectory(instanceId, assetId).resolve("indexes").resolve(assetId + ".json");
    }

    @Override
    public Path getLoggingObject(GameInstanceID instanceId, String assetId, LoggingInfo loggingInfo) {
        return getAssetDirectory(instanceId, assetId).resolve("log_configs").resolve(loggingInfo.file().getId());
    }

    protected Path reconstructAssets(GameInstanceID instanceId, String assetId) throws IOException, JsonParseException {
        Path assetsDir = getAssetDirectory(instanceId, assetId);
        Path indexFile = getIndexFile(instanceId, assetId);
        Path virtualRoot = assetsDir.resolve("virtual").resolve(assetId);

        if (!Files.isRegularFile(indexFile))
            return assetsDir;

        AssetIndex index = JsonUtils.fromJsonFile(indexFile, AssetIndex.class);

        if (index == null)
            return assetsDir;

        if (index.isVirtual()) {
            Path resourcesDir = getRunDirectory(instanceId).resolve("resources");

            int cnt = 0;
            int tot = index.getObjects().size();
            for (Map.Entry<String, AssetObject> entry : index.getObjects().entrySet()) {
                Path target = virtualRoot.resolve(entry.getKey());
                Path original = getAssetObject(instanceId, assetsDir, entry.getValue());
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

            // If the scale new format existent file is lower than 0.1, use the old format.
            if (cnt * 10 < tot)
                return assetsDir;
            else
                return virtualRoot;
        }

        return assetsDir;
    }

    /// Creates a stopped task that normalizes, persists, and publishes one instance manifest.
    ///
    /// Repository metadata protects the mutable in-memory instance catalog, while the precise instance and library
    /// resources cover the manifest file and compatibility libraries written during maintenance.
    ///
    /// @param instanceManifest manifest to persist
    /// @return stopped task yielding the persisted manifest
    public Task<GameInstanceManifest> saveAsync(GameInstanceManifest instanceManifest) {
        GameInstanceManifest capturedManifest = Objects.requireNonNull(instanceManifest, "instanceManifest");
        Path expectedDirectory = getBaseDirectory().toAbsolutePath().normalize();
        return Task.supplyAsync(() -> {
            synchronized (this) {
                if (!expectedDirectory.equals(getBaseDirectory().toAbsolutePath().normalize())) {
                    throw new IllegalStateException("Game repository root changed before manifest save execution");
                }
                GameInstanceManifest savedManifest = capturedManifest.isResolvedPreservingPatches()
                        ? MaintainTask.maintainPreservingPatches(this, capturedManifest)
                        : capturedManifest;

                Path json = getInstanceJson(savedManifest.id()).toAbsolutePath();
                Files.createDirectories(json.getParent());
                JsonUtils.writeToJsonFile(json, savedManifest);

                Status currentStatus = status;
                currentStatus.instances.put(savedManifest.id(),
                        new InstanceHolder(currentStatus, savedManifest.id(), savedManifest));
                gameVersions.clear();
                return savedManifest;
            }
        }).setResources(
                TaskResource.repositoryMetadata(expectedDirectory),
                TaskResource.gameInstance(expectedDirectory.resolve("versions").resolve(capturedManifest.id().id())),
                TaskResource.gameDirectory(expectedDirectory.resolve("libraries")));
    }

    public Path getModpackConfiguration(GameInstanceID instanceId) {
        return getInstanceRoot(instanceId).resolve("modpack.json");
    }

    @Nullable
    public ModpackConfiguration<?> readModpackConfiguration(GameInstanceID instanceId) throws IOException, NoSuchGameInstanceException {
        if (!hasInstance(instanceId)) throw new NoSuchGameInstanceException(instanceId);
        Path file = getModpackConfiguration(instanceId);
        if (Files.notExists(file)) return null;
        return JsonUtils.fromJsonFile(file, ModpackConfiguration.class);
    }

    public boolean isModpack(GameInstanceID instanceId) {
        return Files.exists(getModpackConfiguration(instanceId));
    }

    public Path getSavesDirectory(GameInstanceID instanceId) {
        return getRunDirectory(instanceId).resolve("saves");
    }

    public Path getBackupsDirectory(GameInstanceID instanceID) {
        return getRunDirectory(instanceID).resolve("backups");
    }

    public Path getSchematicsDirectory(GameInstanceID instanceId) {
        return getRunDirectory(instanceId).resolve("schematics");
    }

    public ModManager getModManager(GameInstanceID instanceId) {
        return new ModManager(this, instanceId);
    }

    public ResourcePackManager getResourcePackManager(GameInstanceID instanceId) {
        return new ResourcePackManager(this, instanceId);
    }

    @Override
    public GameInstanceManifest.Resolved resolve(GameInstanceManifest manifest) throws NoSuchGameInstanceException {
        checkDraftInstanceAvailable(manifest.id());
        return status.resolve(manifest, new HashSet<>());
    }

    /// One safely published repository snapshot whose ordered catalog permits independent per-instance updates.
    @NotNullByDefault
    protected static class Status {
        /// Repository root represented by this snapshot.
        private final Path baseDirectory;

        /// Thread-safe ordered instance catalog used by precise-resource operations on different instances.
        private final Map<GameInstanceID, InstanceHolder> instances = new ConcurrentSkipListMap<>();

        /// Creates an empty snapshot for one repository root.
        ///
        /// @param baseDirectory repository root
        protected Status(Path baseDirectory) {
            this.baseDirectory = baseDirectory;
        }

        private GameInstanceManifest.Resolved resolve(GameInstanceManifest manifest,
                                                      Set<GameInstanceID> resolvedSoFar) throws NoSuchGameInstanceException {
            GameInstanceManifest launchManifest;
            GameInstanceManifest standaloneManifest = manifest.isRoot()
                    ? manifest
                    : addPatches(
                    addPatches(new GameInstanceManifest(manifest.id()), List.of(manifest.toPatch())),
                    manifest.patches());

            if (manifest.inheritsFrom() == null) {
                if (manifest.isRoot()) {
                    // TODO: Breaking change; test instances installed by external installers and other launchers.
                    launchManifest = manifest.patches() != null ? new GameInstanceManifest(manifest.id()).withPatches(manifest.patches()) : manifest;
                } else {
                    launchManifest = manifest;
                }
                launchManifest = launchManifest.withJar(manifest.jar() == null ? manifest.id() : manifest.jar());
            } else {
                // To maximize the compatibility.
                if (!resolvedSoFar.add(manifest.id())) {
                    LOG.warning("Found circular instance dependencies: " + resolvedSoFar);
                    launchManifest = (manifest.jar() == null ? manifest.withJar(manifest.id()) : manifest)
                            .withInheritsFrom(null);
                } else {
                    InstanceHolder parentInstance = instances.get(manifest.inheritsFrom());
                    if (parentInstance == null) {
                        throw new NoSuchGameInstanceException(manifest.inheritsFrom());
                    }

                    // The missing parent instance is expected to be installed before resolution.
                    GameInstanceManifest.Resolved parentResolved = resolve(parentInstance.manifest, resolvedSoFar);
                    launchManifest = manifest.merge(parentResolved.launchManifest());
                    standaloneManifest = addPatches(
                            addPatches(parentResolved.standaloneManifest(), Collections.singleton(manifest.toPatch())),
                            manifest.patches());
                }
            }

            if (manifest.patches() != null && !manifest.patches().isEmpty()) {
                // Assume patches themselves do not have patches recursively.
                List<GameInstancePatch> sortedPatches = manifest.patches().stream()
                        .sorted(Comparator.comparing(GameInstancePatch::getPriority))
                        .toList();
                for (GameInstancePatch patch : sortedPatches) {
                    launchManifest = patch.merge(launchManifest);
                }
            }

            launchManifest = launchManifest.withId(manifest.id()).withPatches(null);
            standaloneManifest = standaloneManifest.withId(manifest.id());
            if (launchManifest.jar() != null) {
                standaloneManifest = standaloneManifest.withJar(launchManifest.jar());
            }

            return new GameInstanceManifest.Resolved(manifest, launchManifest, standaloneManifest);
        }

        private static GameInstanceManifest addPatches(GameInstanceManifest manifest, @Nullable Collection<GameInstancePatch> additional) {
            if (additional == null || additional.isEmpty()) {
                return manifest;
            }

            Set<String> patchIds = new HashSet<>();
            for (GameInstancePatch patch : additional) {
                if (patch.id() != null) {
                    patchIds.add(patch.id());
                }
            }

            List<GameInstancePatch> patches = new ArrayList<>();
            if (manifest.patches() != null) {
                for (GameInstancePatch patch : manifest.patches()) {
                    if (patch.id() == null || !patchIds.contains(patch.id())) {
                        patches.add(patch);
                    }
                }
            }
            patches.addAll(additional);
            return manifest.withPatches(patches);
        }

    }

    /// Captures committed manifests without leaking mutable JSON payloads.
    @Override
    public synchronized GameRepositorySnapshot getSnapshot() {
        Map<GameInstanceID, GameInstanceManifest> values = new TreeMap<>();
        status.instances.forEach((id, holder) -> values.put(id, holder.manifest));
        return new GameRepositorySnapshot(status.baseDirectory, publicationRevision, values);
    }

    /// Opens an independent draft; callers own its terminal close.
    @Override
    public GameRepositoryDraft openDraft() throws IOException {
        return new GameRepositoryDraft(this);
    }

    /// Creates a stopped root transaction and closes it after every terminal task outcome.
    @Override
    public <T> Task<T> runDraftAsync(java.util.function.Function<GameRepositoryDraft, Task<T>> factory) {
        Objects.requireNonNull(factory, "factory");
        Path root = getBaseDirectory().toAbsolutePath().normalize();
        java.util.concurrent.atomic.AtomicReference<@Nullable GameRepositoryDraft> active =
                new java.util.concurrent.atomic.AtomicReference<>();
        Task<T> prepared = Task.composeAsync(() -> {
            GameRepositoryDraft draft = openDraft();
            active.set(draft);
            try {
                return Objects.requireNonNull(factory.apply(draft), "Draft factory task");
            } catch (RuntimeException | Error failure) {
                try {
                    draft.close();
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }).setResources(TaskResource.repositoryOperation(root)).asOrchestration();
        Task<T> committed = prepared.thenComposeAsync(value -> {
            GameRepositoryDraft draft = Objects.requireNonNull(active.get(), "Active draft");
            java.util.List<TaskResource> resources = draft.getCommitResources();
            return Task.supplyAsync(() -> {
                draft.commit();
                return value;
            }).setResources(resources.get(0), resources.subList(1, resources.size()).toArray(TaskResource[]::new));
        }).asOrchestration();
        return committed.whenTerminalWithResources(space.minecraftstl.xyml.task.Schedulers.defaultScheduler(), failure -> {
            @Nullable GameRepositoryDraft draft = active.get();
            if (draft != null) {
                if (failure != null) draft.requestCancellation();
                try {
                    draft.close();
                } catch (IOException cleanup) {
                    if (failure == null) throw cleanup;
                    failure.addSuppressed(cleanup);
                }
            }
        }, TaskResource.repositoryOperation(root)).thenApplyAsync(ignored -> committed.getResult());
    }

    /// Serializes only final application and publication, not independent draft preparation.
    void lockDraftCommit() {
        draftCommitLock.lock();
    }

    /// Releases the commit permit after rollback or publication.
    void unlockDraftCommit() {
        draftCommitLock.unlock();
    }

    /// Blocks a recovery-required instance from further mutation or launch resolution.
    void checkDraftInstanceAvailable(GameInstanceID id) {
        if (recoveryRequired.contains(getBaseDirectory().toAbsolutePath().normalize() + ":" + id.id())) {
            throw new IllegalStateException("Instance requires recovery: " + id.id());
        }
    }

    /// Retains a runtime quarantine when automatic rollback could not restore all owned data.
    void markDraftRecoveryRequired(Set<GameInstanceID> ids) {
        ids.forEach(id -> recoveryRequired.add(getBaseDirectory().toAbsolutePath().normalize() + ":" + id.id()));
    }

    /// Rebases staged metadata over the latest index and publishes it exactly once.
    synchronized GameRepositorySnapshot publishDraft(GameRepositoryDraft draft) throws IOException {
        draft.validatePublication(getSnapshot());
        Status next = new Status(status.baseDirectory);
        status.instances.forEach((id, holder) -> next.instances.put(id, new InstanceHolder(next, id, holder.manifest)));
        draft.removedIds().forEach(next.instances::remove);
        draft.stagedManifests().forEach((id, manifest) -> next.instances.put(id, new InstanceHolder(next, id, manifest)));
        long nextRevision = Math.incrementExact(publicationRevision);
        Map<GameInstanceID, GameInstanceManifest> values = new TreeMap<>();
        next.instances.forEach((id, holder) -> values.put(id, holder.manifest));
        GameRepositorySnapshot published = new GameRepositorySnapshot(next.baseDirectory, nextRevision, values);
        gameVersions.clear();
        publicationRevision = nextRevision;
        status = next;
        return published;
    }

    /// Uses the established resolver against an isolated snapshot rather than a live mutable catalog.
    static GameInstanceManifest.Resolved resolveSnapshot(GameRepositorySnapshot snapshot, GameInstanceManifest manifest,
                                                    Set<GameInstanceID> resolvedSoFar) throws NoSuchGameInstanceException {
        GameInstanceManifest launchManifest;
        GameInstanceManifest standaloneManifest = manifest.isRoot()
                ? manifest
                : Status.addPatches(
                Status.addPatches(new GameInstanceManifest(manifest.id()), List.of(manifest.toPatch())),
                manifest.patches());

        if (manifest.inheritsFrom() == null) {
            if (manifest.isRoot()) {
                // TODO: Breaking change; test instances installed by external installers and other launchers.
                launchManifest = manifest.patches() != null ? new GameInstanceManifest(manifest.id()).withPatches(manifest.patches()) : manifest;
            } else {
                launchManifest = manifest;
            }
            launchManifest = launchManifest.withJar(manifest.jar() == null ? manifest.id() : manifest.jar());
        } else {
            // To maximize the compatibility.
            if (!resolvedSoFar.add(manifest.id())) {
                LOG.warning("Found circular instance dependencies: " + resolvedSoFar);
                launchManifest = (manifest.jar() == null ? manifest.withJar(manifest.id()) : manifest)
                        .withInheritsFrom(null);
            } else {
                @Nullable GameInstanceManifest parentInstance = snapshot.hasInstance(manifest.inheritsFrom())
                        ? snapshot.getInstanceManifest(manifest.inheritsFrom()) : null;
                if (parentInstance == null) {
                    throw new NoSuchGameInstanceException(manifest.inheritsFrom());
                }

                // The missing parent instance is expected to be installed before resolution.
                GameInstanceManifest.Resolved parentResolved = resolveSnapshot(snapshot, parentInstance, resolvedSoFar);
                launchManifest = manifest.merge(parentResolved.launchManifest());
                standaloneManifest = Status.addPatches(
                        Status.addPatches(parentResolved.standaloneManifest(), Collections.singleton(manifest.toPatch())),
                        manifest.patches());
            }
        }

        if (manifest.patches() != null && !manifest.patches().isEmpty()) {
            // Assume patches themselves do not have patches recursively.
            List<GameInstancePatch> sortedPatches = manifest.patches().stream()
                    .sorted(Comparator.comparing(GameInstancePatch::getPriority))
                    .toList();
            for (GameInstancePatch patch : sortedPatches) {
                launchManifest = patch.merge(launchManifest);
            }
        }

        launchManifest = launchManifest.withId(manifest.id()).withPatches(null);
        standaloneManifest = standaloneManifest.withId(manifest.id());
        if (launchManifest.jar() != null) {
            standaloneManifest = standaloneManifest.withJar(launchManifest.jar());
        }

        return new GameInstanceManifest.Resolved(manifest, launchManifest, standaloneManifest);
    }

    protected static class InstanceHolder {
        protected final Status status;
        protected final GameInstanceID id;
        protected final GameInstanceManifest manifest;
        protected @Nullable GameInstanceManifest.Resolved resolvedManifest;
        protected @Nullable GameVersionNumber version;

        protected InstanceHolder(Status status, GameInstanceID id, GameInstanceManifest manifest) {
            this.status = status;
            this.id = id;
            this.manifest = manifest;
        }
    }
}
