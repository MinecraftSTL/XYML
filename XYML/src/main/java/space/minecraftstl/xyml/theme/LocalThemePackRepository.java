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
package space.minecraftstl.xyml.theme;

import kala.compress.archivers.zip.AsiExtraField;
import kala.compress.archivers.zip.UnixStat;
import kala.compress.archivers.zip.ZipArchiveEntry;
import kala.compress.archivers.zip.ZipArchiveReader;
import kala.compress.archivers.zip.ZipExtraField;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/// Strict local repository for safely imported, unpacked theme packs.
///
/// Public methods perform no filesystem work before scheduling onto the caller-owned executor. The supplied executor
/// must queue work onto a non-EDT worker thread. Imports validate every central-directory entry and all referenced
/// image pixels before atomically publishing an immutable new installation; existing installations are never
/// overwritten implicitly.
@NotNullByDefault
public final class LocalThemePackRepository {
    /// Root manifest filename.
    public static final String MANIFEST_ENTRY = "manifest.json";

    /// Prefix for unpublished same-filesystem installation directories.
    private static final String STAGING_PREFIX = ".xyml-theme-stage-";

    /// Maximum encoded ZIP structure overhead tolerated beyond the expanded-content ceiling.
    private static final long MAXIMUM_ARCHIVE_OVERHEAD_BYTES = 16L * 1024L * 1024L;

    /// Normalized local repository root.
    private final Path repositoryRoot;

    /// Resource ceilings for archives and installed packs.
    private final ThemePackArchiveLimits limits;

    /// Creates a repository with launcher-default limits.
    ///
    /// @param repositoryRoot local theme-pack directory
    public LocalThemePackRepository(Path repositoryRoot) {
        this(repositoryRoot, ThemePackArchiveLimits.launcherDefaults());
    }

    /// Creates a repository with explicit resource ceilings.
    ///
    /// @param repositoryRoot local theme-pack directory
    /// @param limits resource ceilings
    public LocalThemePackRepository(Path repositoryRoot, ThemePackArchiveLimits limits) {
        this.repositoryRoot = Objects.requireNonNull(repositoryRoot, "repositoryRoot").toAbsolutePath().normalize();
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /// Safely imports one local zip-compatible theme pack without replacing an existing installation.
    ///
    /// @param archive local archive path
    /// @param executor caller-owned non-EDT worker executor
    /// @return completion stage containing the published installation
    public CompletionStage<InstalledThemePack> importArchive(Path archive, Executor executor) {
        Path source = Objects.requireNonNull(archive, "archive").toAbsolutePath().normalize();
        return submit(executor, () -> importBlocking(source));
    }

    /// Loads all installed packs after revalidating manifests, resource limits, links, and referenced images.
    ///
    /// @param executor caller-owned non-EDT worker executor
    /// @return completion stage containing immutable installations sorted by package ID
    public CompletionStage<@Unmodifiable List<InstalledThemePack>> listInstalled(Executor executor) {
        return submit(executor, this::listBlocking);
    }

    /// Finds and revalidates one installed package.
    ///
    /// @param packageId package identifier
    /// @param executor caller-owned non-EDT worker executor
    /// @return completion stage containing the installation, or `null` when absent
    public CompletionStage<@Nullable InstalledThemePack> findInstalled(String packageId, Executor executor) {
        String id = ThemePackManifest.requirePackageId(packageId);
        return submit(executor, () -> {
            if (!Files.exists(repositoryRoot, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            requireDirectDirectory(repositoryRoot, "theme-pack repository");
            Path directory = directChild(id);
            return Files.exists(directory, LinkOption.NOFOLLOW_LINKS) ? loadInstalled(directory) : null;
        });
    }

    /// Deletes one exact installed package after revalidating its identity and complete contents.
    ///
    /// The expected path is part of the authorization check: a stale UI item cannot delete a newly installed
    /// package that reused the same ID at another location. The tree walk never follows symbolic links.
    ///
    /// @param packageId expected package identifier
    /// @param expectedDirectory exact installation directory observed by the caller
    /// @param executor caller-owned non-EDT worker executor
    /// @return completion stage resolved after the package directory is absent
    public CompletionStage<@Nullable Void> deleteInstalled(
            String packageId,
            Path expectedDirectory,
            Executor executor) {
        String id = ThemePackManifest.requirePackageId(packageId);
        Path expected = Objects.requireNonNull(expectedDirectory, "expectedDirectory")
                .toAbsolutePath()
                .normalize();
        return submit(executor, () -> {
            deleteBlocking(id, expected);
            return null;
        });
    }

    /// Performs one exact validated package deletion on the scheduled worker.
    ///
    /// @param packageId expected package identifier
    /// @param expectedDirectory expected normalized installation directory
    /// @throws IOException when the repository changed or the package is unsafe
    private void deleteBlocking(String packageId, Path expectedDirectory) throws IOException {
        if (!Files.exists(repositoryRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new java.nio.file.NoSuchFileException(expectedDirectory.toString());
        }
        requireDirectDirectory(repositoryRoot, "theme-pack repository");
        Path directory = directChild(packageId);
        if (!directory.equals(expectedDirectory)) {
            throw new IOException("Theme-pack installation path is stale: " + expectedDirectory);
        }
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new java.nio.file.NoSuchFileException(directory.toString());
        }
        InstalledThemePack installed = loadInstalled(directory);
        if (!packageId.equals(installed.manifest().id()) || !expectedDirectory.equals(installed.directory())) {
            throw new IOException("Theme-pack installation identity changed before deletion");
        }
        ThemePackIoSupport.deleteTree(directory);
    }

    /// Performs one complete archive import on the scheduled worker.
    private InstalledThemePack importBlocking(Path archive) throws IOException {
        requireDirectRegularFile(archive, "theme-pack archive");
        long maximumArchiveBytes = limits.maximumExpandedBytes() > Long.MAX_VALUE - MAXIMUM_ARCHIVE_OVERHEAD_BYTES
                ? Long.MAX_VALUE
                : limits.maximumExpandedBytes() + MAXIMUM_ARCHIVE_OVERHEAD_BYTES;
        long archiveBytes = Files.size(archive);
        if (archiveBytes <= 0L || archiveBytes > maximumArchiveBytes) {
            throw new IOException("Theme-pack archive exceeds its encoded-size limit");
        }
        prepareRepositoryRoot();
        try (ZipArchiveReader reader = new ZipArchiveReader(archive, StandardCharsets.UTF_8)) {
            @Unmodifiable List<PlannedEntry> plan = planArchive(reader);
            PlannedEntry manifestEntry = plan.stream()
                    .filter(entry -> MANIFEST_ENTRY.equals(entry.entryName()))
                    .findFirst()
                    .orElseThrow(() -> new IOException("Theme-pack archive is missing manifest.json"));
            byte[] manifestBytes;
            try (InputStream input = Objects.requireNonNull(
                    reader.getInputStream(manifestEntry.entry()),
                    "manifest input")) {
                manifestBytes = ThemePackIoSupport.readBounded(input, limits.maximumManifestBytes());
            }
            ThemePackManifest manifest = ThemePackIoSupport.parseManifest(manifestBytes);
            if (BuiltinThemePackCatalog.PACK_IDS.contains(manifest.id())) {
                throw new FileAlreadyExistsException("Built-in theme-pack ID is reserved: " + manifest.id());
            }
            validateManifestReferences(manifest, plan);

            Path destination = directChild(manifest.id());
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileAlreadyExistsException(destination.toString());
            }
            Path staging = Files.createTempDirectory(repositoryRoot, STAGING_PREFIX).toAbsolutePath().normalize();
            boolean published = false;
            @Nullable Throwable failure = null;
            try {
                extract(reader, staging, plan);
                ThemePackManifest stagedManifest = readManifest(staging.resolve(MANIFEST_ENTRY));
                if (!manifest.equals(stagedManifest)) {
                    throw new IOException("Theme-pack manifest changed while the archive was being extracted");
                }
                InstalledThemePack staged = new InstalledThemePack(staging, stagedManifest);
                InstalledDirectoryMetrics stagedMetrics = inspectInstalledDirectory(staging);
                if (stagedMetrics.manifestCount() != 1) {
                    throw new IOException("Staged theme pack must contain exactly one manifest.json");
                }
                validateInstalledReferences(staged, stagedMetrics.files());
                validateReferencedImages(staged);
                publishAtomically(staging, destination);
                published = true;
                return new InstalledThemePack(destination, stagedManifest);
            } catch (IOException | RuntimeException | Error thrown) {
                failure = thrown;
                throw thrown;
            } finally {
                if (!published) {
                    try {
                        ThemePackIoSupport.deleteTree(staging);
                    } catch (IOException cleanupFailure) {
                        if (failure != null) {
                            failure.addSuppressed(cleanupFailure);
                        } else {
                            throw cleanupFailure;
                        }
                    }
                }
            }
        }
    }

    /// Validates all central-directory entries and produces an immutable extraction plan.
    private @Unmodifiable List<PlannedEntry> planArchive(ZipArchiveReader reader) throws IOException {
        List<PlannedEntry> plan = new ArrayList<>();
        Map<String, PlannedEntry> normalizedPaths = new LinkedHashMap<>();
        long declaredTotal = 0L;
        int manifestCount = 0;
        for (ZipArchiveEntry entry : reader.getEntries()) {
            if (plan.size() >= limits.maximumEntryCount()) {
                throw new IOException("Theme-pack archive exceeds the entry-count limit");
            }
            if (!reader.canReadEntryData(entry)) {
                throw new IOException("Theme-pack archive contains an unsupported entry: " + entry.getName());
            }
            rejectLinkOrSpecialEntry(entry);
            @Unmodifiable List<String> segments = ThemePackIoSupport.normalizeArchiveEntry(entry.getName());
            String entryName = String.join("/", segments);
            requireAllowedEntry(entryName);
            long declaredSize = entry.isDirectory() ? 0L : requireDeclaredSize(entry);
            long entryLimit = MANIFEST_ENTRY.equals(entryName)
                    ? limits.maximumManifestBytes()
                    : limits.maximumSingleAssetBytes();
            if (declaredSize > entryLimit) {
                throw new IOException("Theme-pack archive entry exceeds its size limit: " + entryName);
            }
            declaredTotal = ThemePackIoSupport.checkedTotal(
                    declaredTotal,
                    declaredSize,
                    limits.maximumExpandedBytes());
            PlannedEntry planned = new PlannedEntry(entry, segments, entryName, entry.isDirectory(), declaredSize);
            String key = entryName.toLowerCase(Locale.ROOT);
            if (normalizedPaths.putIfAbsent(key, planned) != null) {
                throw new IOException("Theme-pack archive contains a duplicate normalized path: " + entryName);
            }
            rejectPathConflicts(normalizedPaths, planned, key);
            if (MANIFEST_ENTRY.equals(entryName) && !entry.isDirectory()) {
                manifestCount++;
            }
            plan.add(planned);
        }
        if (manifestCount != 1) {
            throw new IOException("Theme-pack archive must contain exactly one root manifest.json");
        }
        return List.copyOf(plan);
    }

    /// Rejects undeclared top-level content to keep the installed format auditable.
    private static void requireAllowedEntry(String entryName) throws IOException {
        if (!MANIFEST_ENTRY.equals(entryName)
                && !"assets".equals(entryName)
                && !entryName.startsWith("assets/")) {
            throw new IOException("Theme-pack archive entry is outside manifest.json and assets/: " + entryName);
        }
    }

    /// Rejects duplicate parent/file interpretations after inserting one planned path.
    private static void rejectPathConflicts(
            Map<String, PlannedEntry> paths,
            PlannedEntry current,
            String currentKey) throws IOException {
        for (int length = 1; length < current.segments().size(); length++) {
            String parent = String.join("/", current.segments().subList(0, length)).toLowerCase(Locale.ROOT);
            @Nullable PlannedEntry plannedParent = paths.get(parent);
            if (plannedParent != null && !plannedParent.directory()) {
                throw new IOException("Theme-pack archive contains a child beneath a file: " + parent);
            }
        }
        if (!current.directory()) {
            String prefix = currentKey + "/";
            for (String candidate : paths.keySet()) {
                if (!candidate.equals(currentKey) && candidate.startsWith(prefix)) {
                    throw new IOException("Theme-pack archive file conflicts with a child path: " + currentKey);
                }
            }
        }
    }

    /// Ensures every manifest asset reference exists as an exact regular archive entry.
    private static void validateManifestReferences(
            ThemePackManifest manifest,
            @Unmodifiable List<PlannedEntry> plan) throws IOException {
        Map<String, PlannedEntry> files = new HashMap<>();
        for (PlannedEntry entry : plan) {
            if (!entry.directory()) {
                files.put(entry.entryName(), entry);
            }
        }
        for (String reference : manifest.referencedAssets()) {
            if (!files.containsKey(reference)) {
                throw new IOException("Theme-pack manifest references a missing asset: " + reference);
            }
        }
    }

    /// Extracts a validated plan with actual byte accounting and no-follow directory creation.
    private void extract(
            ZipArchiveReader reader,
            Path staging,
            @Unmodifiable List<PlannedEntry> plan) throws IOException {
        long total = 0L;
        for (PlannedEntry planned : plan) {
            Path destination = ThemePackIoSupport.resolveContained(staging, planned.segments());
            if (planned.directory()) {
                ThemePackIoSupport.createDirectoriesWithoutLinks(staging, destination);
                continue;
            }
            ThemePackIoSupport.createDirectoriesWithoutLinks(
                    staging,
                    Objects.requireNonNull(destination.getParent(), "destination parent"));
            long entryLimit = MANIFEST_ENTRY.equals(planned.entryName())
                    ? limits.maximumManifestBytes()
                    : limits.maximumSingleAssetBytes();
            long written;
            try (InputStream input = Objects.requireNonNull(
                    reader.getInputStream(planned.entry()),
                    "archive entry input");
                 OutputStream output = Files.newOutputStream(
                         destination,
                         StandardOpenOption.CREATE_NEW,
                         StandardOpenOption.WRITE)) {
                written = ThemePackIoSupport.copyBounded(input, output, entryLimit);
            }
            if (written != planned.declaredSize()) {
                throw new IOException("Theme-pack entry size differs from its central directory: "
                        + planned.entryName());
            }
            total = ThemePackIoSupport.checkedTotal(total, written, limits.maximumExpandedBytes());
        }
    }

    /// Lists and strictly reloads direct installed package directories.
    private @Unmodifiable List<InstalledThemePack> listBlocking() throws IOException {
        if (!Files.exists(repositoryRoot, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        requireDirectDirectory(repositoryRoot, "theme-pack repository");
        @Unmodifiable List<Path> directories;
        try (var stream = Files.list(repositoryRoot)) {
            directories = stream
                    .filter(path -> !path.getFileName().toString().startsWith(STAGING_PREFIX))
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        List<InstalledThemePack> installed = new ArrayList<>(directories.size());
        for (Path directory : directories) {
            installed.add(loadInstalled(directory));
        }
        installed.sort(Comparator.comparing(pack -> pack.manifest().id()));
        return List.copyOf(installed);
    }

    /// Reloads and fully validates one unpacked installation.
    private InstalledThemePack loadInstalled(Path directory) throws IOException {
        requireDirectDirectory(directory, "installed theme pack");
        InstalledDirectoryMetrics metrics = inspectInstalledDirectory(directory);
        if (metrics.manifestCount() != 1) {
            throw new IOException("Installed theme pack must contain exactly one manifest.json: " + directory);
        }
        ThemePackManifest manifest = readManifest(directory.resolve(MANIFEST_ENTRY));
        if (!directory.getFileName().toString().equals(manifest.id())) {
            throw new IOException("Installed theme-pack directory does not match manifest ID: " + directory);
        }
        InstalledThemePack installed = new InstalledThemePack(directory, manifest);
        validateInstalledReferences(installed, metrics.files());
        validateReferencedImages(installed);
        return installed;
    }

    /// Walks an installed directory without following links and reapplies archive resource ceilings.
    private InstalledDirectoryMetrics inspectInstalledDirectory(Path directory) throws IOException {
        InstalledDirectoryVisitor visitor = new InstalledDirectoryVisitor(directory, limits);
        Files.walkFileTree(directory, visitor);
        return visitor.metrics();
    }

    /// Reads and parses one no-follow installed manifest under the manifest byte ceiling.
    private ThemePackManifest readManifest(Path manifestFile) throws IOException {
        requireDirectRegularFile(manifestFile, "installed theme-pack manifest");
        byte[] bytes;
        try (InputStream input = Files.newInputStream(
                manifestFile,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS)) {
            bytes = ThemePackIoSupport.readBounded(input, limits.maximumManifestBytes());
        }
        return ThemePackIoSupport.parseManifest(bytes);
    }

    /// Verifies every parsed reference names an exact installed regular file.
    private static void validateInstalledReferences(
            InstalledThemePack installed,
            @Unmodifiable List<String> files) throws IOException {
        for (String reference : installed.manifest().referencedAssets()) {
            if (!files.contains(reference)) {
                throw new IOException("Installed theme pack is missing referenced asset: " + reference);
            }
        }
    }

    /// Fully decodes every referenced package icon and background under image limits.
    private void validateReferencedImages(InstalledThemePack installed) throws IOException {
        for (String reference : installed.manifest().referencedAssets()) {
            ThemePackIoSupport.validateImage(installed.asset(reference), limits);
        }
    }

    /// Creates or verifies the repository root without accepting a linked root.
    private void prepareRepositoryRoot() throws IOException {
        ThemePackIoSupport.createAbsoluteDirectoriesWithoutLinks(repositoryRoot);
        requireDirectDirectory(repositoryRoot, "theme-pack repository");
    }

    /// Resolves one validated package ID as an exact direct repository child.
    private Path directChild(String packageId) throws IOException {
        Path destination = repositoryRoot.resolve(packageId).toAbsolutePath().normalize();
        if (!Objects.equals(destination.getParent(), repositoryRoot)) {
            throw new IOException("Theme-pack destination is not a direct repository child");
        }
        return destination;
    }

    /// Publishes a fully validated staging directory without fallback or overwrite.
    private static void publishAtomically(Path staging, Path destination) throws IOException {
        try {
            Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("Theme-pack repository does not support atomic publication", exception);
        }
    }

    /// Rejects Unix links, ASi links, devices, sockets, and conflicting entry types.
    private static void rejectLinkOrSpecialEntry(ZipArchiveEntry entry) throws IOException {
        if (entry.isUnixSymlink()) {
            throw new IOException("Theme-pack archive contains a symbolic link: " + entry.getName());
        }
        for (ZipExtraField field : entry.getExtraFields(true)) {
            if (field instanceof AsiExtraField asi && asi.isLink()) {
                throw new IOException("Theme-pack archive contains an ASi link: " + entry.getName());
            }
        }
        int unixType = entry.getUnixMode() & UnixStat.FILE_TYPE_FLAG;
        if (unixType != 0 && unixType != UnixStat.FILE_FLAG && unixType != UnixStat.DIR_FLAG) {
            throw new IOException("Theme-pack archive contains a special entry: " + entry.getName());
        }
        if ((entry.isDirectory() && unixType == UnixStat.FILE_FLAG)
                || (!entry.isDirectory() && unixType == UnixStat.DIR_FLAG)) {
            throw new IOException("Theme-pack archive entry type conflicts with its path: " + entry.getName());
        }
    }

    /// Returns one known non-negative declared expanded size.
    private static long requireDeclaredSize(ZipArchiveEntry entry) throws IOException {
        long size = entry.getSize();
        if (size < 0L) {
            throw new IOException("Theme-pack entry has no declared expanded size: " + entry.getName());
        }
        return size;
    }

    /// Requires a direct regular file without following a symbolic link.
    private static void requireDirectRegularFile(Path path, String label) throws IOException {
        ThemePackIoSupport.requireNoSymbolicPath(path, false, label);
    }

    /// Requires a direct directory without following a symbolic link.
    private static void requireDirectDirectory(Path path, String label) throws IOException {
        ThemePackIoSupport.requireNoSymbolicPath(path, true, label);
    }

    /// Schedules one potentially blocking operation without running it on the calling thread first.
    private static <T> CompletionStage<T> submit(Executor executor, IoOperation<T> operation) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(operation, "operation");
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    ThemePackIoSupport.requireBackgroundThread();
                    future.complete(operation.run());
                } catch (Throwable failure) {
                    future.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException failure) {
            future.completeExceptionally(failure);
        }
        return future;
    }

    /// One validated archive entry retained while its archive reader remains open.
    ///
    /// @param entry central-directory entry
    /// @param segments normalized path segments
    /// @param entryName normalized portable path
    /// @param directory whether the entry is a directory
    /// @param declaredSize declared expanded size
    @NotNullByDefault
    private record PlannedEntry(
            ZipArchiveEntry entry,
            @Unmodifiable List<String> segments,
            String entryName,
            boolean directory,
            long declaredSize) {
        /// Defensively copies normalized segments.
        private PlannedEntry {
            Objects.requireNonNull(entry, "entry");
            segments = List.copyOf(segments);
            Objects.requireNonNull(entryName, "entryName");
        }
    }

    /// Revalidated installed-directory inventory.
    ///
    /// @param files immutable portable regular-file paths
    /// @param manifestCount root manifest count
    /// @param expandedBytes total regular-file bytes
    @NotNullByDefault
    private record InstalledDirectoryMetrics(
            @Unmodifiable List<String> files,
            int manifestCount,
            long expandedBytes) {
        /// Defensively copies the inventory.
        private InstalledDirectoryMetrics {
            files = List.copyOf(files);
            if (manifestCount < 0 || expandedBytes < 0L) {
                throw new IllegalArgumentException("Invalid installed theme-pack metrics");
            }
        }
    }

    /// Streaming no-follow visitor that reapplies entry and byte ceilings to an unpacked installation.
    @NotNullByDefault
    private static final class InstalledDirectoryVisitor extends SimpleFileVisitor<Path> {
        /// Normalized installation root.
        private final Path root;

        /// Validation ceilings.
        private final ThemePackArchiveLimits limits;

        /// Portable regular-file entries discovered so far.
        private final List<String> files = new ArrayList<>();

        /// Descendant entry count including directories.
        private int entryCount;

        /// Root manifest count.
        private int manifestCount;

        /// Aggregate regular-file bytes.
        private long expandedBytes;

        /// Creates a streaming validator.
        ///
        /// @param root installation root
        /// @param limits resource ceilings
        private InstalledDirectoryVisitor(Path root, ThemePackArchiveLimits limits) {
            this.root = root;
            this.limits = limits;
        }

        /// Validates every descendant directory before traversal.
        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
            if (directory.equals(root)) {
                return FileVisitResult.CONTINUE;
            }
            countEntry();
            validatePath(directory);
            if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
                throw new IOException("Installed theme pack contains an unsafe directory: " + directory);
            }
            return FileVisitResult.CONTINUE;
        }

        /// Validates one regular file or rejects a link and special entry.
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
            countEntry();
            String entryName = validatePath(file);
            if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
                throw new IOException("Installed theme pack contains a link or special entry: " + file);
            }
            long size = attributes.size();
            long fileLimit = MANIFEST_ENTRY.equals(entryName)
                    ? limits.maximumManifestBytes()
                    : limits.maximumSingleAssetBytes();
            if (size <= 0L || size > fileLimit) {
                throw new IOException("Installed theme-pack entry exceeds its size limit: " + entryName);
            }
            expandedBytes = ThemePackIoSupport.checkedTotal(
                    expandedBytes,
                    size,
                    limits.maximumExpandedBytes());
            files.add(entryName);
            if (MANIFEST_ENTRY.equals(entryName)) {
                manifestCount++;
            }
            return FileVisitResult.CONTINUE;
        }

        /// Returns the immutable validated inventory.
        ///
        /// @return directory metrics
        private InstalledDirectoryMetrics metrics() {
            return new InstalledDirectoryMetrics(files, manifestCount, expandedBytes);
        }

        /// Increments and enforces the complete descendant entry count.
        private void countEntry() throws IOException {
            entryCount++;
            if (entryCount > limits.maximumEntryCount()) {
                throw new IOException("Installed theme pack exceeds the entry-count limit");
            }
        }

        /// Converts one descendant into a validated portable path.
        private String validatePath(Path path) throws IOException {
            String entryName = root.relativize(path).toString().replace('\\', '/');
            requireAllowedEntry(entryName);
            return entryName;
        }
    }

    /// Checked operation executed only by the caller-owned worker executor.
    @FunctionalInterface
    @NotNullByDefault
    private interface IoOperation<T> {
        /// Performs the scheduled operation.
        ///
        /// @return operation result
        /// @throws Exception when validation or I/O fails
        T run() throws Exception;
    }
}
