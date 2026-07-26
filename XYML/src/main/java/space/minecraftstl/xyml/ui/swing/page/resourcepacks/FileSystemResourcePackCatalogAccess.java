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
package space.minecraftstl.xyml.ui.swing.page.resourcepacks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import kala.encdet.EncodingDetector;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.addon.LocalAddonFile;
import space.minecraftstl.xyml.addon.resourcepack.ResourcePackFile;
import space.minecraftstl.xyml.addon.resourcepack.ResourcePackManager;
import space.minecraftstl.xyml.game.GameRepository;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;
import space.minecraftstl.xyml.util.StringUtils;
import space.minecraftstl.xyml.util.io.FileUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;

/// Serialized file-system access for one managed resource-pack directory.
///
/// A process-wide gate serializes shallow scans, viewport parsing, encoding-preserving options persistence,
/// staging imports, and deletion, including repositories that alias storage through links or mounts.
@NotNullByDefault
final class FileSystemResourcePackCatalogAccess implements ResourcePackCatalogAccess {
    /// Deterministic exact file-name order for completed shallow indices.
    private static final Comparator<Path> PATH_ORDER = Comparator.comparing(
            FileSystemResourcePackCatalogAccess::fileName);

    /// Process-wide gate avoiding both lexical-path aliases and cross-model storage races.
    private static final Object OPERATION_GATE = new Object();

    /// Manager bound to one stable repository instance.
    private final ResourcePackManager manager;

    /// Direct resource-pack directory used by the shallow index.
    private final Path directory;

    /// Instance options file persisted with its detected source encoding.
    private final Path optionsFile;

    /// Test hook invoked inside the shared gate after staging creation and before source copying.
    private final Runnable beforeStagingCopy;

    /// Creates a manager adapter without starting any I/O.
    ///
    /// @param repository repository containing the managed instance
    /// @param instanceId stable non-blank repository instance identifier
    FileSystemResourcePackCatalogAccess(GameRepository repository, String instanceId) {
        this(repository, instanceId, () -> { });
    }

    /// Creates an adapter with a deterministic pre-copy test hook.
    ///
    /// @param repository repository containing the managed instance
    /// @param instanceId stable non-blank repository instance identifier
    /// @param beforeStagingCopy hook after private staging creation and before copying
    FileSystemResourcePackCatalogAccess(
            GameRepository repository,
            String instanceId,
            Runnable beforeStagingCopy) {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(instanceId, "instanceId");
        if (instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId must not be blank");
        }
        manager = new ResourcePackManager(repository, instanceId);
        directory = manager.getDirectory().toAbsolutePath().normalize();
        optionsFile = repository.getRunDirectory(instanceId)
                .resolve("options.txt")
                .toAbsolutePath()
                .normalize();
        this.beforeStagingCopy = Objects.requireNonNull(
                beforeStagingCopy,
                "beforeStagingCopy");
    }

    /// Enumerates only supported direct-child shapes and sorts by exact file name.
    ///
    /// @param cancellation cooperative index cancellation
    /// @return shallow exact path index
    /// @throws IOException when directory enumeration fails
    @Override
    public ResourcePackCatalogIndex loadIndex(LoadCancellation cancellation) throws IOException {
        Objects.requireNonNull(cancellation, "cancellation");
        synchronized (OPERATION_GATE) {
            cancellation.throwIfCancelled();
            return loadIndexLocked(cancellation);
        }
    }

    /// Enumerates the shallow index while the shared operation gate is held.
    ///
    /// @param cancellation cooperative index cancellation
    /// @return shallow exact path index
    /// @throws IOException when directory enumeration fails
    private ResourcePackCatalogIndex loadIndexLocked(
            LoadCancellation cancellation) throws IOException {
        cancellation.throwIfCancelled();
        if (!ResourcePackManager.isMcVersionSupported(manager.getMinecraftVersion())) {
            return new ResourcePackCatalogIndex(false, List.of());
        }
        if (!Files.isDirectory(directory)) {
            return new ResourcePackCatalogIndex(true, List.of());
        }
        List<Path> paths = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                cancellation.throwIfCancelled();
                Path normalized = child.toAbsolutePath().normalize();
                if (ResourcePackFile.isFileResourcePack(normalized)) {
                    paths.add(normalized);
                }
            }
        }
        cancellation.throwIfCancelled();
        paths.sort(PATH_ORDER);
        return new ResourcePackCatalogIndex(true, paths);
    }

    /// Parses only requested paths and isolates ordinary per-pack I/O failures.
    ///
    /// @param paths exact normalized viewport paths
    /// @param cancellation cooperative viewport cancellation
    /// @return exact rows in identical order
    /// @throws IOException when shared enabled-state access fails
    @Override
    public @Unmodifiable List<ResourcePackCatalogItem> loadItems(
            @Unmodifiable List<Path> paths,
            LoadCancellation cancellation) throws IOException {
        Objects.requireNonNull(paths, "paths");
        Objects.requireNonNull(cancellation, "cancellation");
        synchronized (OPERATION_GATE) {
            cancellation.throwIfCancelled();
            return loadItemsLocked(paths, cancellation);
        }
    }

    /// Parses exact rows while the shared operation gate is held.
    ///
    /// @param paths exact normalized viewport paths
    /// @param cancellation cooperative viewport cancellation
    /// @return exact rows in identical order
    /// @throws IOException when options or pack metadata cannot be read reliably
    private @Unmodifiable List<ResourcePackCatalogItem> loadItemsLocked(
            @Unmodifiable List<Path> paths,
            LoadCancellation cancellation) throws IOException {
        List<@Nullable ResourcePackFile> resolvedFiles = new ArrayList<>(paths.size());
        List<ResourcePackFile> validFiles = new ArrayList<>(paths.size());
        for (Path path : paths) {
            cancellation.throwIfCancelled();
            Path normalized = requireDirectChild(path);
            try {
                @Nullable ResourcePackFile file = ResourcePackFile.fromFile(manager, normalized);
                resolvedFiles.add(file);
                if (file != null) {
                    validFiles.add(file);
                }
            } catch (IOException failure) {
                cancellation.throwIfCancelled();
                resolvedFiles.add(null);
            }
        }

        OptionsState options = readOptionsState();

        List<ResourcePackCatalogItem> items = new ArrayList<>(paths.size());
        for (int index = 0; index < paths.size(); index++) {
            cancellation.throwIfCancelled();
            Path path = paths.get(index);
            @Nullable ResourcePackFile file = resolvedFiles.get(index);
            if (file == null) {
                items.add(invalidItem(path));
                continue;
            }
            @Nullable LocalAddonFile.Description description = file.getDescription();
            items.add(new ResourcePackCatalogItem(
                    path,
                    file.getFileName(),
                    file.getFileNameWithExtension(),
                    description == null ? "" : description.toString(),
                    mapCompatibility(file.getCompatibility()),
                    isEnabled(file, options)));
        }
        cancellation.throwIfCancelled();
        return List.copyOf(items);
    }

    /// Applies one mutation and obtains its actual post-write index under the shared gate.
    ///
    /// @param mutation immutable requested write
    /// @param cancellation cooperative pre-commit cancellation
    /// @param commitPoint callback crossing the non-cancellable side-effect boundary
    /// @return refreshed index and optional mutation failure
    /// @throws IOException when the mandatory post-write index cannot be obtained
    @Override
    public ResourcePackCatalogMutationAccessResult mutateAndLoadIndex(
            ResourcePackCatalogMutationRequest mutation,
            LoadCancellation cancellation,
            Runnable commitPoint) throws IOException {
        Objects.requireNonNull(mutation, "mutation");
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(commitPoint, "commitPoint");
        synchronized (OPERATION_GATE) {
            cancellation.throwIfCancelled();
            @Nullable Throwable mutationFailure = null;
            try {
                applyMutationLocked(mutation, cancellation, commitPoint);
            } catch (CancellationException failure) {
                throw failure;
            } catch (IOException | RuntimeException failure) {
                mutationFailure = failure;
            }

            try {
                ResourcePackCatalogIndex refreshedIndex = loadIndexLocked(cancellation);
                return new ResourcePackCatalogMutationAccessResult(
                        refreshedIndex,
                        mutationFailure);
            } catch (IOException | RuntimeException | Error refreshFailure) {
                if (mutationFailure != null && mutationFailure != refreshFailure) {
                    refreshFailure.addSuppressed(mutationFailure);
                }
                rethrowAccessFailure(refreshFailure);
                throw new AssertionError("Unreachable mutation refresh failure");
            }
        }
    }

    /// Dispatches one concrete mutation while the shared operation gate is held.
    ///
    /// @param mutation immutable requested write
    /// @param cancellation cooperative pre-commit cancellation
    /// @param commitPoint non-cancellable side-effect boundary
    /// @throws IOException when local mutation access fails
    private void applyMutationLocked(
            ResourcePackCatalogMutationRequest mutation,
            LoadCancellation cancellation,
            Runnable commitPoint) throws IOException {
        if (mutation instanceof ResourcePackImportMutation importMutation) {
            importResourcePacksLocked(importMutation, cancellation, commitPoint);
        } else if (mutation instanceof ResourcePackEnabledMutation enabledMutation) {
            setEnabledLocked(enabledMutation, cancellation, commitPoint);
        } else if (mutation instanceof ResourcePackDeleteMutation deleteMutation) {
            deleteLocked(deleteMutation, cancellation, commitPoint);
        } else {
            throw new IllegalArgumentException("Unknown resource-pack mutation: " + mutation);
        }
    }

    /// Imports every source through a private same-filesystem staging directory.
    ///
    /// @param mutation captured multi-source import
    /// @param cancellation cooperative staging cancellation
    /// @param commitPoint callback invoked before the first no-overwrite move
    /// @throws IOException when validation, copying, publication, or cleanup fails
    private void importResourcePacksLocked(
            ResourcePackImportMutation mutation,
            LoadCancellation cancellation,
            Runnable commitPoint) throws IOException {
        boolean directoryEntryExisted = Files.exists(directory, LinkOption.NOFOLLOW_LINKS);
        if (directoryEntryExisted && !Files.isDirectory(directory)) {
            throw new IOException(
                    "Managed resource-pack path is not a directory: " + directory);
        }
        List<ImportTarget> targets = new ArrayList<>(mutation.sources().size());
        Set<Path> uniqueTargets = new HashSet<>();
        for (Path source : mutation.sources()) {
            cancellation.throwIfCancelled();
            if (!ResourcePackFile.isFileResourcePack(source)) {
                throw new IllegalArgumentException(
                        "File '" + source + "' is not a resource pack");
            }
            if (isManagedDirectoryInsideSource(source)) {
                throw new IllegalArgumentException(
                        "Import source contains the managed resource-pack directory: " + source);
            }
            Path fileName = Objects.requireNonNull(
                    source.getFileName(),
                    "resource-pack source has no file name");
            Path target = requireDirectChild(directory.resolve(fileName));
            if (source.equals(target)) {
                throw new IllegalArgumentException(
                        "Resource-pack source already is the managed target: " + source);
            }
            if (!uniqueTargets.add(target)) {
                throw new IllegalArgumentException(
                        "Multiple import sources have the same target: " + target);
            }
            if (Files.exists(target)) {
                throw new java.nio.file.FileAlreadyExistsException(target.toString());
            }
            targets.add(new ImportTarget(source, target));
        }

        Path stagingParent = directoryEntryExisted
                ? directory
                : Objects.requireNonNull(
                        directory.getParent(),
                        "managed resource-pack directory has no parent");
        Files.createDirectories(stagingParent);
        Path stagingDirectory = Files.createTempDirectory(
                stagingParent,
                directoryEntryExisted
                        ? ".xyml-import-"
                        : "." + fileName(directory) + "-import-");
        @Nullable Throwable failure = null;
        try {
            beforeStagingCopy.run();
            cancellation.throwIfCancelled();
            for (ImportTarget target : targets) {
                cancellation.throwIfCancelled();
                Path staged = stagingDirectory.resolve(
                        Objects.requireNonNull(target.target().getFileName(), "target file name"));
                if (Files.isDirectory(target.source())) {
                    copyDirectoryCancellable(target.source(), staged, cancellation);
                } else {
                    copyFileCancellable(target.source(), staged, cancellation);
                }
                target.setStaged(staged);
            }
            cancellation.throwIfCancelled();
            commitPoint.run();
            Files.createDirectories(directory);
            for (ImportTarget target : targets) {
                Path staged = Objects.requireNonNull(target.staged(), "staged import path");
                moveWithoutOverwrite(staged, target.target());
            }
        } catch (IOException | RuntimeException | Error thrown) {
            failure = thrown;
        } finally {
            try {
                if (Files.exists(stagingDirectory)) {
                    FileUtils.deleteDirectory(stagingDirectory);
                }
            } catch (IOException | RuntimeException | Error cleanupFailure) {
                if (failure == null) {
                    failure = cleanupFailure;
                } else if (failure != cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
        }
        if (failure != null) {
            rethrowAccessFailure(failure);
        }
    }

    /// Copies one directory tree while checking cancellation at every visited entry.
    ///
    /// @param source source resource-pack directory
    /// @param target private staging directory
    /// @param cancellation cooperative pre-commit cancellation
    /// @throws IOException when traversal or copying fails
    private static void copyDirectoryCancellable(
            Path source,
            Path target,
            LoadCancellation cancellation) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            /// Creates one corresponding staging directory after checking cancellation.
            @Override
            public FileVisitResult preVisitDirectory(
                    Path directory,
                    BasicFileAttributes attributes) throws IOException {
                cancellation.throwIfCancelled();
                Path relative = source.relativize(directory);
                Files.createDirectories(target.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            /// Copies one regular or symbolic source entry with chunk-level cancellation.
            @Override
            public FileVisitResult visitFile(
                    Path file,
                    BasicFileAttributes attributes) throws IOException {
                cancellation.throwIfCancelled();
                copyFileCancellable(
                        file,
                        target.resolve(source.relativize(file)),
                        cancellation);
                return FileVisitResult.CONTINUE;
            }

            /// Propagates one traversal failure after giving cancellation priority.
            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure)
                    throws IOException {
                cancellation.throwIfCancelled();
                throw failure;
            }
        });
    }

    /// Copies one file into private staging with bounded cancellation latency.
    ///
    /// @param source source file
    /// @param target absent private staging file
    /// @param cancellation cooperative pre-commit cancellation
    /// @throws IOException when file access fails
    private static void copyFileCancellable(
            Path source,
            Path target,
            LoadCancellation cancellation) throws IOException {
        Files.createDirectories(Objects.requireNonNull(target.getParent(), "target parent"));
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = Files.newInputStream(source);
             OutputStream output = Files.newOutputStream(
                     target,
                     StandardOpenOption.CREATE_NEW,
                     StandardOpenOption.WRITE)) {
            while (true) {
                cancellation.throwIfCancelled();
                int read = input.read(buffer);
                if (read < 0) {
                    return;
                }
                cancellation.throwIfCancelled();
                output.write(buffer, 0, read);
            }
        }
    }

    /// Strictly persists one pack's desired enabled state.
    ///
    /// @param mutation stable path and desired state
    /// @param cancellation cooperative pre-commit cancellation
    /// @param commitPoint callback invoked before strict options persistence
    /// @throws IOException when pack or options access fails
    private void setEnabledLocked(
            ResourcePackEnabledMutation mutation,
            LoadCancellation cancellation,
            Runnable commitPoint) throws IOException {
        cancellation.throwIfCancelled();
        OptionsState options = readOptionsState();
        OptionsState replacement;
        if (mutation.enabled()) {
            ResourcePackFile pack = requireResourcePack(mutation.path());
            replacement = withEnabled(options, pack, true);
        } else {
            Path target = requireDirectChild(mutation.path());
            replacement = withDisabled(options, fileName(target));
        }
        if (replacement.equals(options)) {
            return;
        }
        cancellation.throwIfCancelled();
        commitPoint.run();
        saveOptionsState(replacement);
    }

    /// Strictly disables one pack before deleting its direct-child archive or directory.
    ///
    /// @param mutation stable path to delete
    /// @param cancellation cooperative pre-commit cancellation
    /// @param commitPoint callback invoked before persistence or deletion
    /// @throws IOException when options persistence or deletion fails
    private void deleteLocked(
            ResourcePackDeleteMutation mutation,
            LoadCancellation cancellation,
            Runnable commitPoint) throws IOException {
        cancellation.throwIfCancelled();
        Path target = requireDirectChild(mutation.path());
        boolean targetExists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (targetExists && !ResourcePackFile.isFileResourcePack(target)) {
            throw new IllegalArgumentException("Path is no longer a resource pack: " + target);
        }
        OptionsState options = readOptionsState();
        OptionsState disabled = withDisabled(options, fileName(target));
        cancellation.throwIfCancelled();
        commitPoint.run();
        if (!disabled.equals(options)) {
            saveOptionsState(disabled);
        }
        if (Files.isSymbolicLink(target)) {
            Files.deleteIfExists(target);
        } else if (Files.isDirectory(target)) {
            FileUtils.deleteDirectory(target);
        } else {
            Files.deleteIfExists(target);
        }
    }

    /// Resolves one supported direct-child pack at mutation execution time.
    ///
    /// @param path stable normalized target path
    /// @return current concrete pack
    /// @throws IOException when the pack cannot be read
    private ResourcePackFile requireResourcePack(Path path) throws IOException {
        Path normalized = requireDirectChild(path);
        @Nullable ResourcePackFile pack = ResourcePackFile.fromFile(manager, normalized);
        if (pack == null) {
            throw new IllegalArgumentException("Path is no longer a resource pack: " + normalized);
        }
        return pack;
    }

    /// Validates normalized direct ownership by the managed resource-pack directory.
    ///
    /// @param path path to validate
    /// @return normalized direct-child path
    private Path requireDirectChild(Path path) {
        Path normalized = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        if (!directory.equals(normalized.getParent())) {
            throw new IllegalArgumentException(
                    "Resource-pack path is not a direct managed child: " + normalized);
        }
        return normalized;
    }

    /// Detects a source directory that contains the managed root and therefore staging itself.
    ///
    /// @param source normalized import source
    /// @return whether copying it would recursively enter the staging directory
    /// @throws IOException when existing paths cannot be resolved reliably
    private boolean isManagedDirectoryInsideSource(Path source) throws IOException {
        if (!Files.isDirectory(source)) {
            return false;
        }
        Path realSource = source.toRealPath();
        Path comparableDirectory;
        if (Files.exists(directory)) {
            comparableDirectory = directory.toRealPath();
        } else {
            Path parent = Objects.requireNonNull(
                    directory.getParent(),
                    "managed resource-pack directory has no parent");
            comparableDirectory = Files.exists(parent)
                    ? parent.toRealPath().resolve(
                            Objects.requireNonNull(directory.getFileName(), "directory name"))
                    : directory;
        }
        return comparableDirectory.startsWith(realSource);
    }

    /// Reads and parses the two resource-pack option lists without swallowing failures.
    ///
    /// @return immutable line-preserving options state
    /// @throws IOException when input or list JSON is invalid
    private OptionsState readOptionsState() throws IOException {
        OptionsDocument document;
        Charset encoding;
        if (!Files.exists(optionsFile)) {
            document = OptionsDocument.empty();
            encoding = StandardCharsets.UTF_8;
        } else {
            if (!Files.isRegularFile(optionsFile)) {
                throw new IOException("Instance options path is not a regular file: " + optionsFile);
            }
            byte[] bytes = Files.readAllBytes(optionsFile);
            encoding = detectOptionsEncoding(bytes);
            document = OptionsDocument.parse(new String(bytes, encoding));
        }
        return new OptionsState(
                document,
                parsePackList(document.value("resourcePacks"), "resourcePacks"),
                parsePackList(
                        document.value("incompatibleResourcePacks"),
                        "incompatibleResourcePacks"),
                encoding);
    }

    /// Selects a writable Java charset from the detector while treating pure ASCII as UTF-8.
    ///
    /// @param bytes complete options file bytes
    /// @return detected approximate charset or UTF-8 when detection is inconclusive
    private static Charset detectOptionsEncoding(byte[] bytes) {
        @Nullable EncodingDetector.Encoding detected = EncodingDetector.MODERN_WEB.detect(bytes).bestEncoding();
        @Nullable Charset approximate = detected == null ? null : detected.approximateCharset();
        return detected == EncodingDetector.Encoding.ASCII || approximate == null
                ? StandardCharsets.UTF_8
                : approximate;
    }

    /// Parses one strict JSON string list while rejecting malformed or null entries.
    ///
    /// @param json serialized list, or null when the key is absent
    /// @param key diagnostic option key
    /// @return immutable non-null string list
    /// @throws IOException when JSON shape or content is invalid
    private static @Unmodifiable List<String> parsePackList(
            @Nullable String json,
            String key) throws IOException {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!(root instanceof JsonArray array)) {
                throw new IllegalArgumentException("Value is not a JSON array");
            }
            List<String> values = new ArrayList<>(array.size());
            for (JsonElement element : array) {
                if (!(element instanceof JsonPrimitive primitive) || !primitive.isString()) {
                    throw new IllegalArgumentException(
                            "Array contains a non-string element");
                }
                values.add(primitive.getAsString());
            }
            return List.copyOf(values);
        } catch (RuntimeException failure) {
            throw new IOException("Invalid " + key + " value in options.txt", failure);
        }
    }

    /// Returns a copy with one pack enabled or disabled under current version semantics.
    ///
    /// @param source current strict options state
    /// @param pack current concrete pack
    /// @param enabled desired state
    /// @return immutable replacement state
    private OptionsState withEnabled(
            OptionsState source,
            ResourcePackFile pack,
            boolean enabled) {
        List<String> resourcePacks = new ArrayList<>(source.resourcePacks());
        List<String> incompatiblePacks = new ArrayList<>(source.incompatibleResourcePacks());
        String name = pack.getFileNameWithExtension();
        boolean newFormat = ResourcePackManager.isMcVersionSupportsNewOptionsFormat(
                manager.getMinecraftVersion());
        if (enabled) {
            if (!containsPack(resourcePacks, name, newFormat)) {
                resourcePacks.add(newFormat ? "file/" + name : name);
            }
            boolean incompatible = manager.isIncompatible(pack);
            boolean markedIncompatible = containsPack(incompatiblePacks, name, newFormat);
            if (incompatible && !markedIncompatible) {
                incompatiblePacks.add(newFormat ? "file/" + name : name);
            } else if (!incompatible && markedIncompatible) {
                removePack(incompatiblePacks, name);
            }
        } else {
            removePack(resourcePacks, name);
            removePack(incompatiblePacks, name);
        }
        return new OptionsState(source.document(), resourcePacks, incompatiblePacks, source.encoding());
    }

    /// Returns a copy with both option identifiers removed without parsing pack metadata.
    ///
    /// @param source current strict options state
    /// @param fileName exact direct-child file name
    /// @return immutable disabled replacement state
    private static OptionsState withDisabled(OptionsState source, String fileName) {
        List<String> resourcePacks = new ArrayList<>(source.resourcePacks());
        List<String> incompatiblePacks = new ArrayList<>(source.incompatibleResourcePacks());
        removePack(resourcePacks, fileName);
        removePack(incompatiblePacks, fileName);
        return new OptionsState(source.document(), resourcePacks, incompatiblePacks, source.encoding());
    }

    /// Returns whether one pack is semantically enabled by both strict option lists.
    ///
    /// @param pack pack to inspect
    /// @param options parsed strict options state
    /// @return whether Minecraft will enable the pack
    private boolean isEnabled(ResourcePackFile pack, OptionsState options) {
        String name = pack.getFileNameWithExtension();
        boolean newFormat = ResourcePackManager.isMcVersionSupportsNewOptionsFormat(
                manager.getMinecraftVersion());
        if (!containsPack(options.resourcePacks(), name, newFormat)) {
            return false;
        }
        return manager.isIncompatible(pack)
                == containsPack(options.incompatibleResourcePacks(), name, newFormat);
    }

    /// Tests old and current option identifiers according to the owning game version.
    ///
    /// @param values option identifiers
    /// @param name exact pack file name
    /// @param newFormat whether `file/` identifiers are supported
    /// @return whether the list contains this pack
    private static boolean containsPack(
            @Unmodifiable List<String> values,
            String name,
            boolean newFormat) {
        return values.contains(name) || (newFormat && values.contains("file/" + name));
    }

    /// Removes both legacy and current identifiers for one pack.
    ///
    /// @param values mutable option identifiers
    /// @param name exact pack file name
    private static void removePack(List<String> values, String name) {
        values.removeIf(value -> value.equals(name) || value.equals("file/" + name));
    }

    /// Persists both resource-pack lists while preserving every unrelated option line.
    ///
    /// @param state immutable replacement options state
    /// @throws IOException when safe encoding-preserving persistence fails
    private void saveOptionsState(OptionsState state) throws IOException {
        OptionsDocument replacement = state.document()
                .withValue("resourcePacks", StringUtils.serializeStringList(state.resourcePacks()))
                .withValue(
                        "incompatibleResourcePacks",
                        StringUtils.serializeStringList(state.incompatibleResourcePacks()));
        byte[] bytes = replacement.render().getBytes(state.encoding());
        FileUtils.saveSafely(optionsFile, output -> output.write(bytes));
    }

    /// Moves one staged archive or directory without replacing a concurrent target.
    ///
    /// @param staged private staged path
    /// @param target absent direct-child target
    /// @throws IOException when the ordinary no-overwrite move fails
    private static void moveWithoutOverwrite(Path staged, Path target) throws IOException {
        Files.move(staged, target);
    }

    /// Rethrows one access failure without losing its runtime or error type.
    ///
    /// @param failure failure to rethrow
    /// @throws IOException when the failure is checked I/O
    private static void rethrowAccessFailure(Throwable failure) throws IOException {
        if (failure instanceof IOException ioFailure) {
            throw ioFailure;
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IOException("Unexpected resource-pack access failure", failure);
    }

    /// Returns one path's exact final component.
    ///
    /// @param path path to inspect
    /// @return exact final file name
    private static String fileName(Path path) {
        return Objects.requireNonNull(path.getFileName(), "resource-pack path has no file name")
                .toString();
    }

    /// Maps manager compatibility into the toolkit-neutral catalog value.
    ///
    /// @param compatibility manager compatibility
    /// @return catalog compatibility
    private static ResourcePackCompatibility mapCompatibility(
            ResourcePackFile.Compatibility compatibility) {
        return switch (compatibility) {
            case COMPATIBLE -> ResourcePackCompatibility.COMPATIBLE;
            case TOO_NEW -> ResourcePackCompatibility.TOO_NEW;
            case TOO_OLD -> ResourcePackCompatibility.TOO_OLD;
            case INVALID -> ResourcePackCompatibility.INVALID;
            case MISSING_PACK_META -> ResourcePackCompatibility.MISSING_PACK_META;
            case MISSING_GAME_META -> ResourcePackCompatibility.MISSING_GAME_META;
        };
    }

    /// Creates a geometry-preserving invalid row for a disappeared or unreadable candidate.
    ///
    /// @param path indexed candidate path
    /// @return invalid catalog row
    private static ResourcePackCatalogItem invalidItem(Path path) {
        String exactFileName = fileName(path);
        String displayName = exactFileName.toLowerCase(Locale.ROOT).endsWith(".zip")
                ? exactFileName.substring(0, exactFileName.length() - 4)
                : exactFileName;
        return new ResourcePackCatalogItem(
                path,
                displayName,
                exactFileName,
                "",
                ResourcePackCompatibility.INVALID,
                false);
    }

    /// One import source, final target, and mutable private staging path.
    @NotNullByDefault
    private static final class ImportTarget {
        /// Normalized external source path.
        private final Path source;

        /// Normalized managed direct-child target.
        private final Path target;

        /// Private staged copy, or null before staging completes.
        private @Nullable Path staged;

        /// Creates one preflighted import target.
        ///
        /// @param source external source
        /// @param target managed target
        private ImportTarget(Path source, Path target) {
            this.source = Objects.requireNonNull(source, "source");
            this.target = Objects.requireNonNull(target, "target");
        }

        /// Returns the external source.
        ///
        /// @return source path
        private Path source() {
            return source;
        }

        /// Returns the managed target.
        ///
        /// @return target path
        private Path target() {
            return target;
        }

        /// Returns the staged copy when available.
        ///
        /// @return staged path, or null before copying
        private @Nullable Path staged() {
            return staged;
        }

        /// Records the completed staged copy.
        ///
        /// @param staged staged path
        private void setStaged(Path staged) {
            this.staged = Objects.requireNonNull(staged, "staged");
        }
    }

    /// Parsed option document plus immutable semantic resource-pack lists.
    ///
    /// @param document original line-preserving document
    /// @param resourcePacks enabled pack identifiers
    /// @param incompatibleResourcePacks acknowledged incompatible identifiers
    /// @param encoding detected source encoding retained for persistence
    @NotNullByDefault
    private record OptionsState(
            OptionsDocument document,
            @Unmodifiable List<String> resourcePacks,
            @Unmodifiable List<String> incompatibleResourcePacks,
            Charset encoding) {
        /// Freezes semantic option lists.
        private OptionsState {
            Objects.requireNonNull(document, "document");
            resourcePacks = List.copyOf(resourcePacks);
            incompatibleResourcePacks = List.copyOf(incompatibleResourcePacks);
            Objects.requireNonNull(encoding, "encoding");
        }
    }

    /// Decoded options text split into raw lines with exact line terminators.
    ///
    /// @param lines immutable raw option lines
    @NotNullByDefault
    private record OptionsDocument(@Unmodifiable List<OptionLine> lines) {
        /// Freezes the raw line sequence.
        private OptionsDocument {
            lines = List.copyOf(lines);
        }

        /// Creates an empty option document.
        ///
        /// @return empty document
        private static OptionsDocument empty() {
            return new OptionsDocument(List.of());
        }

        /// Parses text without normalizing unknown lines or line terminators.
        ///
        /// @param text complete decoded options text
        /// @return line-preserving document
        private static OptionsDocument parse(String text) {
            Objects.requireNonNull(text, "text");
            if (text.isEmpty()) {
                return empty();
            }
            List<OptionLine> parsed = new ArrayList<>();
            int start = 0;
            for (int index = 0; index < text.length(); index++) {
                char character = text.charAt(index);
                if (character != '\r' && character != '\n') {
                    continue;
                }
                int end = index;
                String terminator;
                if (character == '\r'
                        && index + 1 < text.length()
                        && text.charAt(index + 1) == '\n') {
                    terminator = "\r\n";
                    index++;
                } else {
                    terminator = String.valueOf(character);
                }
                parsed.add(new OptionLine(text.substring(start, end), terminator));
                start = index + 1;
            }
            if (start < text.length()) {
                parsed.add(new OptionLine(text.substring(start), ""));
            }
            return new OptionsDocument(parsed);
        }

        /// Returns the final effective value for one exact option key.
        ///
        /// @param key option key
        /// @return final value, or null when absent
        private @Nullable String value(String key) {
            @Nullable String value = null;
            String prefix = key + ":";
            for (OptionLine line : lines) {
                if (line.content().startsWith(prefix)) {
                    value = line.content().substring(prefix.length());
                }
            }
            return value;
        }

        /// Replaces the final effective key line or appends a new line without reordering others.
        ///
        /// @param key exact option key
        /// @param value serialized option value
        /// @return line-preserving replacement document
        private OptionsDocument withValue(String key, String value) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
            String prefix = key + ":";
            int replacementIndex = -1;
            for (int index = 0; index < lines.size(); index++) {
                if (lines.get(index).content().startsWith(prefix)) {
                    replacementIndex = index;
                }
            }
            List<OptionLine> replacement = new ArrayList<>(lines);
            if (replacementIndex >= 0) {
                OptionLine previous = replacement.get(replacementIndex);
                replacement.set(
                        replacementIndex,
                        new OptionLine(prefix + value, previous.terminator()));
            } else {
                if (!replacement.isEmpty()) {
                    int lastIndex = replacement.size() - 1;
                    OptionLine last = replacement.get(lastIndex);
                    if (last.terminator().isEmpty()) {
                        replacement.set(
                                lastIndex,
                                new OptionLine(last.content(), System.lineSeparator()));
                    }
                }
                replacement.add(new OptionLine(prefix + value, ""));
            }
            return new OptionsDocument(replacement);
        }

        /// Renders the exact current raw line representation.
        ///
        /// @return complete options text
        private String render() {
            StringBuilder builder = new StringBuilder();
            for (OptionLine line : lines) {
                builder.append(line.content()).append(line.terminator());
            }
            return builder.toString();
        }
    }

    /// One raw options line and its exact following line terminator.
    ///
    /// @param content line content without terminator
    /// @param terminator empty, CR, LF, or CRLF terminator
    @NotNullByDefault
    private record OptionLine(String content, String terminator) {
        /// Validates raw line parts.
        private OptionLine {
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(terminator, "terminator");
        }
    }
}
