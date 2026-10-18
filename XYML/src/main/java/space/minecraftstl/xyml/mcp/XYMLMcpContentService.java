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
package space.minecraftstl.xyml.mcp;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.addon.mod.LocalModFile;
import space.minecraftstl.xyml.addon.mod.ModManager;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.setting.SettingsManager;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.ui.swing.page.instances.management.RepositoryInstanceLifecycleService;
import space.minecraftstl.xyml.ui.swing.page.instances.management.servers.FileSystemServerCatalogAccess;
import space.minecraftstl.xyml.ui.swing.page.instances.management.servers.ServerCatalogItem;
import space.minecraftstl.xyml.ui.swing.page.instances.management.worlds.WorldDeletionAccess;
import space.minecraftstl.xyml.ui.swing.page.resourcepacks.ResourcePackDeletionAccess;
import space.minecraftstl.xyml.ui.swing.page.schematics.SchematicDeletionAccess;
import space.minecraftstl.xyml.ui.swing.page.shaderpacks.FileSystemShaderPackCatalogAccess;
import space.minecraftstl.xyml.task.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static space.minecraftstl.xyml.mcp.McpDeletionConfirmation.DeletionKind;
import static space.minecraftstl.xyml.mcp.McpDeletionConfirmation.DeletionRequest;

/// Installed-content operations sharing the launcher's captured-root tasks and existing catalog storage boundaries.
@NotNullByDefault
final class XYMLMcpContentService implements XYMLMcpContentOperations {
    /// Owning service retains running-process and instance-path checks.
    private final XYMLMcpService owner;
    /// Repository whose settings resolve the effective content directories.
    private final XYMLGameRepository repository;
    /// Launcher-owned ordinary and permanent-deletion authorization.
    private final McpDeletionConfirmation confirmation;

    /// Creates content capabilities without performing I/O.
    /// @param owner owning MCP service
    /// @param repository installed-instance repository
    /// @param confirmation launcher-owned authorization policy
    XYMLMcpContentService(XYMLMcpService owner, XYMLGameRepository repository, McpDeletionConfirmation confirmation) {
        this.owner = owner;
        this.repository = repository;
        this.confirmation = confirmation;
    }

    /// Lists current managed ResourcePacks without modifying their configuration.
    @Override
    public Task<@Unmodifiable List<@Unmodifiable Map<String, Object>>> listResourcePacks(String instanceId) {
        return list(instanceId, DeletionKind.RESOURCE_PACKS, null);
    }

    /// Deletes selected ResourcePacks after launcher approval, preferring the recycle bin.
    @Override
    public Task<@Unmodifiable Map<String, Object>> removeResourcePacks(
            String instanceId, @Unmodifiable List<String> paths) {
        return remove(instanceId, DeletionKind.RESOURCE_PACKS, paths);
    }

    /// Lists current managed Worlds without modifying their configuration.
    @Override
    public Task<@Unmodifiable List<@Unmodifiable Map<String, Object>>> listWorlds(String instanceId) {
        return list(instanceId, DeletionKind.WORLDS, null);
    }

    /// Deletes selected Worlds after launcher approval, preferring the recycle bin.
    @Override
    public Task<@Unmodifiable Map<String, Object>> removeWorlds(
            String instanceId, @Unmodifiable List<String> paths) {
        return remove(instanceId, DeletionKind.WORLDS, paths);
    }

    /// Lists current managed ShaderPacks without modifying their configuration.
    @Override
    public Task<@Unmodifiable List<@Unmodifiable Map<String, Object>>> listShaderPacks(String instanceId) {
        return list(instanceId, DeletionKind.SHADER_PACKS, null);
    }

    /// Deletes selected ShaderPacks after launcher approval, preferring the recycle bin.
    @Override
    public Task<@Unmodifiable Map<String, Object>> removeShaderPacks(
            String instanceId, @Unmodifiable List<String> paths) {
        return remove(instanceId, DeletionKind.SHADER_PACKS, paths);
    }

    /// Lists folders and supported schematic files in a managed direct-child listing.
    @Override
    public Task<@Unmodifiable List<@Unmodifiable Map<String, Object>>> listSchematics(
            String instanceId, @Nullable String directory) {
        return list(instanceId, DeletionKind.SCHEMATICS, directory);
    }

    /// Removes files and recursively removes selected folders without following links.
    @Override
    public Task<@Unmodifiable Map<String, Object>> removeSchematics(
            String instanceId, @Unmodifiable List<String> paths) {
        return remove(instanceId, DeletionKind.SCHEMATICS, paths);
    }

    /// Lists saved servers with the exact revision required for a later deletion.
    @Override
    public Task<@Unmodifiable Map<String, Object>> listServers(String instanceId) {
        return contentTask(instanceId, DeletionKind.SERVERS, false, (context, checkpoint) -> {
            FileSystemServerCatalogAccess access = serverAccess(context);
            List<ServerCatalogItem> entries = access.read();
            List<Map<String, Object>> items = new ArrayList<>();
            for (int index = 0; index < entries.size(); index++) {
                ServerCatalogItem entry = entries.get(index);
                items.add(Map.of("index", index, "name", entry.name(), "address", entry.address()));
            }
            return Map.of("items", List.copyOf(items), "revision", access.revision());
        });
    }

    /// Removes selected saved entries without changing other NBT data or contacting remote servers.
    @Override
    public Task<@Unmodifiable Map<String, Object>> removeServers(
            String instanceId, @Unmodifiable List<Integer> indices, String revision) {
        Set<Integer> selected = Set.copyOf(indices);
        Objects.requireNonNull(revision, "revision");
        return contentTask(instanceId, DeletionKind.SERVERS, true, (context, checkpoint) -> {
            FileSystemServerCatalogAccess access = serverAccess(context);
            List<ServerCatalogItem> entries = access.read();
            if (!access.revision().equals(revision)) {
                throw new IOException("Server list changed; call list_servers again before deleting");
            }
            for (int index : selected) {
                if (index < 0 || index >= entries.size()) {
                    throw new IllegalArgumentException("Server index is out of range: " + index);
                }
            }
            if (selected.isEmpty()) {
                return Map.of("approved", true, "removed", List.of());
            }
            Path file = context.root().resolve("servers.dat");
            McpPathSnapshot snapshot = McpPathSnapshot.capture(context.root(), file);
            boolean approved = XYMLMcpService.withSettingsSaveBarrier(() -> confirmation.confirm(
                    new DeletionRequest(DeletionKind.SERVERS, context.id(), selected.size())));
            if (!approved) {
                return Map.of("approved", false, "removed", List.of());
            }
            checkpoint.run();
            snapshot.verify();
            List<ServerCatalogItem> retained = new ArrayList<>();
            for (int index = 0; index < entries.size(); index++) {
                if (!selected.contains(index)) {
                    retained.add(entries.get(index));
                }
            }
            access.writeIfUnchanged(entries, retained);
            return Map.of("approved", true, "removed", selected.stream().sorted().toList());
        });
    }

    /// Removes mods using the same managed-mod selection and explicit-mode ModManager APIs.
    /// @param instanceId installed instance
    /// @param paths exact managed paths
    /// @return unstarted task with explicit partial-success output
    Task<@Unmodifiable Map<String, Object>> removeMods(String instanceId, @Unmodifiable List<String> paths) {
        return remove(instanceId, DeletionKind.MODS, paths);
    }

    /// Captures instance identity before the ordinary confirmation opens.
    /// @param id installed instance identifier
    /// @return prepared recycle-bin-first batch
    /// @throws IOException when the installed directory is unsafe
    McpDeletionBatch instanceDeletionBatch(GameInstanceID id) throws IOException {
        Path target = repository.getInstanceRoot(id).toAbsolutePath().normalize();
        return new McpDeletionBatch(Objects.requireNonNull(target.getParent()), List.of(target),
                DeletionRequest.instance(id), confirmation);
    }

    /// Moves an approved instance using a pre-confirmation identity and executor-owned cancellation.
    /// @param id approved instance identifier
    /// @param batch identity captured before confirmation
    /// @param checkpoint executor-owned cancellation check
    /// @return whether the directory was actually removed
    /// @throws Exception when validation or deletion fails
    boolean deleteInstanceFiles(GameInstanceID id, McpDeletionBatch batch, Runnable checkpoint) throws Exception {
        RepositoryInstanceLifecycleService lifecycle = new RepositoryInstanceLifecycleService(repository);
        Map<String, Object> result = batch.run((path, mode) -> lifecycle.deleteWithoutRefresh(id, mode),
                checkpoint, false);
        if (result.get("failures") instanceof List<?> failures && !failures.isEmpty()) {
            throw new IOException("Instance deletion failed: " + failures);
        }
        return Boolean.TRUE.equals(result.get("completed"));
    }

    /// Creates a shallow metadata listing scoped to one resolved instance directory.
    /// @param instanceId installed instance
    /// @param kind managed content kind
    /// @param rawDirectory optional schematic subdirectory
    /// @return unstarted immutable listing task
    private Task<@Unmodifiable List<@Unmodifiable Map<String, Object>>> list(
            String instanceId, DeletionKind kind, @Nullable String rawDirectory) {
        return contentTask(instanceId, kind, false, (context, checkpoint) -> {
            Path directory = rawDirectory == null ? context.root() : absolutePath(rawDirectory);
            if (!directory.equals(context.root())) {
                McpPathSnapshot.requireOwned(context.root(), directory);
            }
            ManagedAccess access = access(context);
            List<Map<String, Object>> result = new ArrayList<>();
            for (Path path : access.scan().paths(directory)) {
                checkpoint.run();
                McpPathSnapshot snapshot = McpPathSnapshot.capture(context.root(), path);
                result.add(Map.of("path", path.toString(), "display_name", path.getFileName().toString(),
                        "directory", snapshot.directory()));
            }
            return List.copyOf(result);
        });
    }

    /// Validates all requested selections before asking for one batch approval.
    /// @param instanceId installed instance
    /// @param kind deletion category
    /// @param rawPaths client-supplied absolute paths
    /// @return unstarted mutation task
    private Task<@Unmodifiable Map<String, Object>> remove(
            String instanceId, DeletionKind kind, List<String> rawPaths) {
        List<Path> requested = rawPaths.stream().map(XYMLMcpContentService::absolutePath).distinct().toList();
        return contentTask(instanceId, kind, true, (context, checkpoint) -> {
            if (requested.isEmpty()) {
                return Map.of("approved", true, "removed", List.of(), "remaining", List.of(),
                        "completed", true, "failures", List.of());
            }
            ManagedAccess access = access(context);
            List<Path> independent = requested.stream()
                    .filter(path -> kind != DeletionKind.SCHEMATICS || requested.stream()
                            .noneMatch(parent -> !parent.equals(path) && path.startsWith(parent)))
                    .sorted(Comparator.comparing(Path::toString)).toList();
            for (Path path : requested) {
                McpPathSnapshot.requireOwned(context.root(), path);
                access.prepare().validate(path);
            }
            McpDeletionBatch batch = new McpDeletionBatch(context.root(), independent,
                    new DeletionRequest(kind, context.id(), independent.size()), confirmation);
            return batch.run(access.mutation(), checkpoint, true);
        });
    }

    /// Creates a cancellable inner operation only after settings-derived paths have been resolved.
    /// @param instanceId raw installed instance ID
    /// @param kind content category
    /// @param write whether the operation may mutate content
    /// @param action operation body
    /// @param <T> immutable result type
    /// @return unstarted captured-root task graph
    private <T> Task<T> contentTask(String instanceId, DeletionKind kind, boolean write, Action<T> action) {
        GameInstanceID id = owner.id(instanceId);
        Path repositoryRoot = owner.repositoryDirectory();
        Path instance = XYMLMcpService.instanceDirectory(repositoryRoot, id);
        return Task.<T>composeAsync("Resolve MCP " + kind, () ->
                repository.callWithStableBaseDirectory(repositoryRoot, () -> {
                    owner.requireInstanceDirectory(id, instance);
                    owner.requireInstance(id);
                    Path run = owner.resolvedRunDirectory(id);
                    Path root = root(kind, id, run);
                    Context context = new Context(id, kind, root, run);
                    McpContentTask<T> task = new McpContentTask<>(cancellation ->
                            repository.callWithStableBaseDirectory(repositoryRoot, () -> {
                                Runnable checkpoint = () -> {
                                    cancellation.run();
                                    owner.requireInstanceDirectory(id, instance);
                                    owner.requireInstance(id);
                                    if (!run.equals(repository.getRunDirectory(id).toAbsolutePath().normalize())
                                            || !root.equals(root(kind, id, run))) {
                                        throw new IllegalStateException("Managed content directory changed");
                                    }
                                    if (write) {
                                        owner.requireTrackedLaunchStopped(repositoryRoot, id, "remove content from");
                                    }
                                };
                                checkpoint.run();
                                if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                                    McpPathSnapshot.requireOwned(root, root.resolve(".xyml-ownership-check"));
                                }
                                return action.run(context, checkpoint);
                            }));
                    List<TaskResource> resources = resources(context);
                    return task.setName("MCP " + kind).setExecutor(Schedulers.io())
                            .setResources(resources.get(0), resources.subList(1, resources.size())
                                    .toArray(TaskResource[]::new));
                })).setExecutor(Schedulers.io()).setResources(
                        TaskResource.repositoryMetadata(repositoryRoot),
                        TaskResource.gameInstance(instance),
                        TaskResource.configuration(XYMLMcpService.instanceSettingsFile(repositoryRoot, id)),
                        TaskResource.configuration(SettingsManager.gameSettingsLocation()),
                        TaskResource.configuration(SettingsManager.settingsLocation()))
                .releaseResourcesBeforeDependencies();
    }

    /// Resolves one category from the captured effective instance settings.
    /// @param kind content kind
    /// @param id instance identifier
    /// @param run effective run directory
    /// @return normalized managed root
    private Path root(DeletionKind kind, GameInstanceID id, Path run) {
        return switch (kind) {
            case RESOURCE_PACKS -> repository.getResourcePackDirectory(id).toAbsolutePath().normalize();
            case MODS -> repository.getModsDirectory(id).toAbsolutePath().normalize();
            case WORLDS -> run.resolve("saves");
            case SHADER_PACKS -> run.resolve("shaderpacks");
            case SCHEMATICS -> run.resolve("schematics");
            case SERVERS -> run;
            case INSTANCE -> throw new IllegalArgumentException("Use the instance lifecycle API");
        };
    }

    /// Declares both managed content and the exact configuration files changed by its storage boundary.
    /// @param context resolved operation
    /// @return immutable resource declaration
    private @Unmodifiable List<TaskResource> resources(Context context) {
        List<TaskResource> resources = new ArrayList<>();
        resources.add(TaskResource.repositoryOperation(owner.repositoryDirectory()));
        resources.add(TaskResource.gameInstance(repository.getInstanceRoot(context.id())));
        resources.add(TaskResource.gameDirectory(context.root()));
        switch (context.kind()) {
            case RESOURCE_PACKS -> resources.add(TaskResource.configuration(context.run().resolve("options.txt")));
            case SHADER_PACKS -> {
                resources.add(TaskResource.configuration(context.run().resolve("config/iris.properties")));
                resources.add(TaskResource.configuration(context.run().resolve("optionsshaders.txt")));
                resources.add(TaskResource.gameDirectory(repository.getModsDirectory(context.id())).readOnly());
            }
            case WORLDS -> resources.add(TaskResource.worldCatalog(context.root()));
            case SERVERS -> resources.add(TaskResource.nbtFile(context.root().resolve("servers.dat")));
            default -> { }
        }
        return List.copyOf(resources);
    }

    /// Builds a headless adapter to the same production storage boundary used by the corresponding UI.
    /// @param context resolved operation
    /// @return operation-local storage functions
    private ManagedAccess access(Context context) {
        return switch (context.kind()) {
            case RESOURCE_PACKS -> {
                ResourcePackDeletionAccess packs = new ResourcePackDeletionAccess(repository, context.id());
                yield new ManagedAccess(directory -> packs.paths(), path -> requireListed(packs.paths(), path), packs::delete);
            }
            case WORLDS -> {
                WorldDeletionAccess worlds = new WorldDeletionAccess(repository, context.id());
                yield new ManagedAccess(directory -> worlds.paths(), path -> {
                    requireListed(worlds.paths(), path);
                    worlds.validate(path);
                }, worlds::delete);
            }
            case SHADER_PACKS -> {
                FileSystemShaderPackCatalogAccess shaders = new FileSystemShaderPackCatalogAccess(repository, context.id());
                yield new ManagedAccess(directory -> shaders.loadIndex(),
                        path -> requireListed(shaders.loadIndex(), path), shaders::delete);
            }
            case SCHEMATICS -> {
                SchematicDeletionAccess schematics = new SchematicDeletionAccess(context.root());
                yield new ManagedAccess(schematics::paths, schematics::capture, schematics::delete);
            }
            case MODS -> {
                ModManager manager = new ModManager(repository, context.id(), context.root());
                yield new ManagedAccess(directory -> List.of(), path ->
                        XYMLMcpService.selectedManagedMods(manager, context.root(), List.of(path)), (path, mode) -> {
                    try {
                        LocalModFile file = XYMLMcpService.selectedManagedMods(
                                manager, context.root(), List.of(path)).get(path);
                        manager.removeMods(mode, Objects.requireNonNull(file));
                    } finally {
                        manager.invalidateCache();
                    }
                });
            }
            default -> throw new IllegalArgumentException("No file catalog for " + context.kind());
        };
    }

    /// Rejects files not belonging to the current catalog, even when their lexical path is inside its root.
    /// @param listed current managed paths
    /// @param path requested path
    /// @throws IOException when the target is unsupported or stale
    private static void requireListed(List<Path> listed, Path path) throws IOException {
        if (!listed.contains(path)) {
            throw new IOException("Target is not in the current managed catalog: " + path);
        }
    }

    /// Constructs the shared server storage only after the run-directory path has been checked.
    /// @param context captured server-list context
    /// @return revision-aware NBT storage
    private FileSystemServerCatalogAccess serverAccess(Context context) {
        return new FileSystemServerCatalogAccess(repository, context.id());
    }

    /// Requires explicit absolute client paths rather than resolving relative paths against launcher process state.
    /// @param raw client-supplied path
    /// @return normalized absolute path
    private static Path absolutePath(String raw) {
        Path path = Path.of(raw);
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("Expected an absolute managed path: " + raw);
        }
        return path.normalize();
    }

    /// Stable resolved content directory and category.
    /// @param id installed instance
    /// @param kind operation category
    /// @param root managed content boundary
    /// @param run captured effective run directory
    @NotNullByDefault
    private record Context(GameInstanceID id, DeletionKind kind, Path root, Path run) {
        /// Validates captured operation components.
        private Context {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(run, "run");
        }
    }

    /// Existing storage functions used by one operation, without changing the catalog's public model.
    /// @param scan direct-child listing
    /// @param prepare pre-confirmation target validation or identity capture
    /// @param mutation storage-specific deletion
    @NotNullByDefault
    private record ManagedAccess(Scan scan, Prepare prepare, McpDeletionBatch.Mutation mutation) {
        /// Requires all existing storage functions.
        private ManagedAccess {
            Objects.requireNonNull(scan, "scan");
            Objects.requireNonNull(prepare, "prepare");
            Objects.requireNonNull(mutation, "mutation");
        }
    }

    /// Blocking direct-child catalog scan.
    @NotNullByDefault
    @FunctionalInterface
    private interface Scan {
        /// Lists one supported managed directory.
        /// @param directory exact managed directory
        /// @return immutable direct-child paths
        /// @throws IOException when storage access fails
        @Unmodifiable List<Path> paths(Path directory) throws IOException;
    }

    /// Existing storage-specific target validation.
    @NotNullByDefault
    @FunctionalInterface
    private interface Prepare {
        /// Prepares one target before any deletion confirmation.
        /// @param path managed target
        /// @throws IOException when the target is invalid
        void validate(Path path) throws IOException;
    }

    /// One resolved content operation.
    /// @param <T> immutable result type
    @NotNullByDefault
    @FunctionalInterface
    private interface Action<T> {
        /// Executes with captured paths and executor-owned cancellation.
        /// @param context stable instance context
        /// @param checkpoint cancellation and freshness guard
        /// @return immutable result
        /// @throws Exception when execution fails
        T run(Context context, Runnable checkpoint) throws Exception;
    }
}
