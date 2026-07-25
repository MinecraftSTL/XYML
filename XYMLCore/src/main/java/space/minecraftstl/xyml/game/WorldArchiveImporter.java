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

import kala.compress.archivers.zip.AsiExtraField;
import kala.compress.archivers.zip.UnixStat;
import kala.compress.archivers.zip.ZipArchiveEntry;
import kala.compress.archivers.zip.ZipArchiveReader;
import kala.compress.archivers.zip.ZipExtraField;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.util.io.CompressingUtils;
import space.minecraftstl.xyml.util.io.FileUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/// Strict offline importer for local or already-downloaded Minecraft world ZIP archives.
///
/// The importer validates the complete central directory before creating a staging directory,
/// extracts with independent streamed byte accounting, validates the staged world through [World],
/// and publishes only the completed directory with an atomic move. It never follows or creates
/// links and never overwrites an existing world.
@NotNullByDefault
public final class WorldArchiveImporter {
    /// Prefix used for temporary directories under the target `saves` directory.
    private static final String STAGING_PREFIX = ".xyml-world-stage-";

    /// Buffer size for bounded streaming extraction.
    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    /// Archive resource policy applied during planning and extraction.
    private final WorldArchiveImportLimits limits;

    /// Creates an importer using launcher-default resource ceilings.
    public WorldArchiveImporter() {
        this(WorldArchiveImportLimits.launcherDefaults());
    }

    /// Creates an importer with explicit resource ceilings.
    ///
    /// @param limits archive resource policy
    public WorldArchiveImporter(WorldArchiveImportLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /// Validates and atomically imports one ZIP archive into a direct child of `saves`.
    ///
    /// @param archive local ZIP archive
    /// @param savesDirectory target instance saves directory
    /// @param targetName final directory and stored level name
    /// @return immutable published import details
    /// @throws IOException when the source is unsafe, malformed, exceeds limits, conflicts, or cannot be published
    public WorldArchiveImportResult importArchive(
            Path archive,
            Path savesDirectory,
            String targetName) throws IOException {
        Path normalizedArchive = requireRegularArchive(archive);
        Path normalizedSavesDirectory = Objects.requireNonNull(savesDirectory, "savesDirectory")
                .toAbsolutePath()
                .normalize();
        String normalizedTargetName = requireTargetName(targetName);
        Path destination = requireDirectDestination(normalizedSavesDirectory, normalizedTargetName);

        try (ZipArchiveReader reader = CompressingUtils.openZipFileWithPossibleEncoding(normalizedArchive, null)) {
            @Unmodifiable List<PlannedEntry> plan = planArchive(reader);
            Files.createDirectories(normalizedSavesDirectory);
            rejectSymbolicLink(normalizedSavesDirectory, "saves directory");
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileAlreadyExistsException(destination.toString());
            }

            Path stagingDirectory = Files.createTempDirectory(normalizedSavesDirectory, STAGING_PREFIX)
                    .toAbsolutePath()
                    .normalize();
            boolean published = false;
            @Nullable Throwable failure = null;
            try {
                ExtractionMetrics metrics = extract(reader, stagingDirectory, plan);
                validateAndRenameWorld(stagingDirectory, normalizedTargetName);
                rejectSymbolicLink(normalizedSavesDirectory, "saves directory");
                publishAtomically(stagingDirectory, destination);
                published = true;
                return new WorldArchiveImportResult(destination, metrics.fileCount(), metrics.expandedBytes());
            } catch (IOException | RuntimeException | Error thrown) {
                failure = thrown;
                throw thrown;
            } finally {
                if (!published) {
                    try {
                        FileUtils.deleteDirectory(stagingDirectory);
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

    /// Reads and validates every central-directory entry without writing archive data.
    ///
    /// @param reader open reader for the normalized regular ZIP source
    /// @return immutable relative extraction plan with the single world root stripped
    /// @throws IOException when the archive layout or any entry is unsafe
    private @Unmodifiable List<PlannedEntry> planArchive(ZipArchiveReader reader) throws IOException {
        List<RawEntry> entries = new ArrayList<>();
        long declaredTotal = 0L;
        for (ZipArchiveEntry entry : reader.getEntries()) {
            if (entries.size() >= limits.maximumEntryCount()) {
                throw new IOException("World archive exceeds the entry-count limit");
            }
            if (!reader.canReadEntryData(entry)) {
                throw new IOException("World archive contains an unsupported entry: " + entry.getName());
            }
            rejectLinkOrSpecialEntry(entry);
            @Unmodifiable List<String> segments = normalizeEntryName(entry.getName());
            long declaredSize = entry.isDirectory() ? 0L : requireDeclaredSize(entry);
            if (declaredSize > limits.maximumSingleFileBytes()) {
                throw new IOException("World archive entry exceeds the single-file limit: " + entry.getName());
            }
            declaredTotal = checkedTotal(declaredTotal, declaredSize, limits.maximumExpandedBytes());
            entries.add(new RawEntry(entry, segments, entry.isDirectory()));
        }
        if (entries.isEmpty()) {
            throw new IOException("World archive is empty");
        }
        return normalizeWorldRoot(entries);
    }

    /// Strips an optional single enclosing root and verifies supported root world metadata.
    ///
    /// @param rawEntries validated central-directory entries
    /// @return immutable extraction plan
    /// @throws IOException when the archive mixes roots or lacks a regular root metadata file
    private static @Unmodifiable List<PlannedEntry> normalizeWorldRoot(
            @Unmodifiable List<RawEntry> rawEntries) throws IOException {
        boolean rootLevelData = rawEntries.stream().anyMatch(entry -> isWorldMetadataFile(entry.segments()));
        @Nullable String enclosingRoot = rootLevelData ? null : findSingleEnclosingRoot(rawEntries);
        List<PlannedEntry> plannedEntries = new ArrayList<>(rawEntries.size());
        Map<String, PlannedEntry> normalizedPaths = new HashMap<>();
        int levelDataCount = 0;
        int specialLevelDataCount = 0;
        for (RawEntry rawEntry : rawEntries) {
            @Unmodifiable List<String> relativeSegments = stripRoot(rawEntry.segments(), enclosingRoot);
            if (relativeSegments.isEmpty()) {
                if (!rawEntry.directory()) {
                    throw new IOException("World archive root entry is not a directory");
                }
                continue;
            }
            String normalizedKey = String.join("/", relativeSegments).toLowerCase(Locale.ROOT);
            PlannedEntry plannedEntry = new PlannedEntry(rawEntry.entry(), relativeSegments, rawEntry.directory());
            @Nullable PlannedEntry duplicate = normalizedPaths.putIfAbsent(normalizedKey, plannedEntry);
            if (duplicate != null) {
                throw new IOException("World archive contains a duplicate normalized path: " + normalizedKey);
            }
            rejectParentFileConflict(normalizedPaths, relativeSegments);
            rejectChildConflict(normalizedPaths, normalizedKey, plannedEntry);
            if (isWorldMetadataFile(relativeSegments)) {
                if (rawEntry.directory()) {
                    throw new IOException("World archive metadata is not a regular file");
                }
                if (isRegularLevelData(relativeSegments)) {
                    levelDataCount++;
                } else {
                    specialLevelDataCount++;
                }
            }
            plannedEntries.add(plannedEntry);
        }
        if (levelDataCount != 1 && !(levelDataCount == 0 && specialLevelDataCount == 1)) {
            throw new IOException("World archive must contain one root level.dat or special_level.dat");
        }
        return List.copyOf(plannedEntries);
    }

    /// Extracts a validated plan while enforcing actual streamed byte ceilings.
    ///
    /// @param reader open reader used for the validated central-directory plan
    /// @param stagingDirectory unique empty staging directory
    /// @param plan immutable validated extraction plan
    /// @return actual extraction metrics
    /// @throws IOException when data changes, exceeds limits, or cannot be written
    private ExtractionMetrics extract(
            ZipArchiveReader reader,
            Path stagingDirectory,
            @Unmodifiable List<PlannedEntry> plan) throws IOException {
        int fileCount = 0;
        long expandedBytes = 0L;
        for (PlannedEntry plannedEntry : plan) {
            Path destination = resolveSegments(stagingDirectory, plannedEntry.relativeSegments());
            if (plannedEntry.directory()) {
                createDirectoriesWithoutLinks(stagingDirectory, destination);
                continue;
            }
            createDirectoriesWithoutLinks(stagingDirectory, Objects.requireNonNull(destination.getParent()));
            long writtenForEntry;
            try (InputStream input = Objects.requireNonNull(
                    reader.getInputStream(plannedEntry.entry()),
                    "ZIP entry input stream");
                    OutputStream output = Files.newOutputStream(
                            destination,
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE)) {
                writtenForEntry = copyBounded(input, output, expandedBytes);
            }
            expandedBytes = checkedTotal(
                    expandedBytes,
                    writtenForEntry,
                    limits.maximumExpandedBytes());
            fileCount++;
        }
        return new ExtractionMetrics(fileCount, expandedBytes);
    }

    /// Copies one regular entry while bounding both per-file and archive totals.
    ///
    /// @param input decompressed ZIP entry stream
    /// @param output new staging file stream
    /// @param previousTotal expanded bytes written before this entry
    /// @return expanded bytes written for this entry
    /// @throws IOException when an actual size limit is exceeded or stream I/O fails
    private long copyBounded(InputStream input, OutputStream output, long previousTotal) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        long entryBytes = 0L;
        while (true) {
            int read = input.read(buffer);
            if (read < 0) {
                return entryBytes;
            }
            entryBytes = checkedTotal(entryBytes, read, limits.maximumSingleFileBytes());
            checkedTotal(previousTotal, entryBytes, limits.maximumExpandedBytes());
            output.write(buffer, 0, read);
        }
    }

    /// Reopens staged NBT through Core and writes the user-confirmed level name before publication.
    ///
    /// @param stagingDirectory fully extracted temporary world directory
    /// @param targetName confirmed target name
    /// @throws IOException when required world metadata is invalid or cannot be updated
    private static void validateAndRenameWorld(Path stagingDirectory, String targetName) throws IOException {
        World stagedWorld = new World(stagingDirectory);
        stagedWorld.setWorldName(targetName);
        if (!targetName.equals(new World(stagingDirectory).getWorldName())) {
            throw new IOException("Failed to persist the imported world name");
        }
    }

    /// Moves the completed stage into place without a copy fallback or overwrite.
    ///
    /// @param stagingDirectory completed staging directory
    /// @param destination absent direct child of `saves`
    /// @throws IOException when atomic publication is unsupported, conflicts, or fails
    private static void publishAtomically(Path stagingDirectory, Path destination) throws IOException {
        try {
            Files.move(stagingDirectory, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("The saves filesystem does not support atomic world publication", exception);
        }
    }

    /// Rejects Unix links, ASi links, devices, sockets, and other non-file entry types.
    ///
    /// @param entry central-directory entry
    /// @throws IOException when the entry is a link or special filesystem object
    private static void rejectLinkOrSpecialEntry(ZipArchiveEntry entry) throws IOException {
        if (entry.isUnixSymlink()) {
            throw new IOException("World archive contains a symbolic link: " + entry.getName());
        }
        for (ZipExtraField field : entry.getExtraFields(true)) {
            if (field instanceof AsiExtraField asiExtraField && asiExtraField.isLink()) {
                throw new IOException("World archive contains an ASi link: " + entry.getName());
            }
        }
        int unixMode = entry.getUnixMode();
        int unixType = unixMode & UnixStat.FILE_TYPE_FLAG;
        if (unixType != 0 && unixType != UnixStat.FILE_FLAG && unixType != UnixStat.DIR_FLAG) {
            throw new IOException("World archive contains a special entry: " + entry.getName());
        }
        if (entry.isDirectory() && unixType == UnixStat.FILE_FLAG) {
            throw new IOException("World archive entry type conflicts with its path: " + entry.getName());
        }
        if (!entry.isDirectory() && unixType == UnixStat.DIR_FLAG) {
            throw new IOException("World archive entry type conflicts with its path: " + entry.getName());
        }
    }

    /// Parses a ZIP entry path with platform-independent traversal and absolute-path rejection.
    ///
    /// @param entryName decoded archive entry name
    /// @return immutable non-empty normalized path segments
    /// @throws IOException when the path is absolute, malformed, empty, or traverses upward
    private static @Unmodifiable List<String> normalizeEntryName(String entryName) throws IOException {
        String checkedName = Objects.requireNonNull(entryName, "entryName");
        if (checkedName.indexOf('\0') >= 0 || checkedName.isBlank()) {
            throw new IOException("World archive contains an invalid empty path");
        }
        String portableName = checkedName.replace('\\', '/');
        if (portableName.startsWith("/") || portableName.startsWith("//") || isDriveAbsolute(portableName)) {
            throw new IOException("World archive contains an absolute path: " + checkedName);
        }
        String[] rawSegments = portableName.split("/", -1);
        List<String> normalizedSegments = new ArrayList<>(rawSegments.length);
        for (int index = 0; index < rawSegments.length; index++) {
            String segment = rawSegments[index];
            if (segment.isEmpty() && index == rawSegments.length - 1) {
                continue;
            }
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IOException("World archive contains a dangerous path: " + checkedName);
            }
            if (segment.indexOf(':') >= 0) {
                throw new IOException("World archive path contains a forbidden colon: " + checkedName);
            }
            normalizedSegments.add(segment);
        }
        if (normalizedSegments.isEmpty()) {
            throw new IOException("World archive contains an empty root path");
        }
        return List.copyOf(normalizedSegments);
    }

    /// Finds a single enclosing root segment shared by every archive entry.
    ///
    /// @param rawEntries complete validated central-directory entries
    /// @return shared root segment
    /// @throws IOException when the archive contains multiple top-level roots
    private static String findSingleEnclosingRoot(@Unmodifiable List<RawEntry> rawEntries) throws IOException {
        String root = rawEntries.get(0).segments().get(0);
        for (RawEntry entry : rawEntries) {
            if (!root.equalsIgnoreCase(entry.segments().get(0))) {
                throw new IOException("World archive contains multiple top-level roots");
            }
        }
        return root;
    }

    /// Removes the single enclosing root from one path when present.
    ///
    /// @param segments normalized archive path segments
    /// @param root optional enclosing root
    /// @return immutable relative path segments
    private static @Unmodifiable List<String> stripRoot(
            @Unmodifiable List<String> segments,
            @Nullable String root) {
        if (root == null) {
            return segments;
        }
        return List.copyOf(segments.subList(1, segments.size()));
    }

    /// Rejects a later child path whose earlier parent entry was declared as a regular file.
    ///
    /// @param normalizedPaths lower-case planned paths already observed
    /// @param segments current path segments
    /// @throws IOException when a parent file conflicts with this entry
    private static void rejectParentFileConflict(
            Map<String, PlannedEntry> normalizedPaths,
            @Unmodifiable List<String> segments) throws IOException {
        for (int length = 1; length < segments.size(); length++) {
            String parentKey = String.join("/", segments.subList(0, length)).toLowerCase(Locale.ROOT);
            @Nullable PlannedEntry parent = normalizedPaths.get(parentKey);
            if (parent != null && !parent.directory()) {
                throw new IOException("World archive contains a child beneath a regular file: " + parentKey);
            }
        }
    }

    /// Rejects a regular entry that was declared after one of its descendants.
    ///
    /// @param normalizedPaths lower-case planned paths including the current entry
    /// @param normalizedKey current lower-case path
    /// @param current current planned entry
    /// @throws IOException when a regular file is also the parent of an existing entry
    private static void rejectChildConflict(
            Map<String, PlannedEntry> normalizedPaths,
            String normalizedKey,
            PlannedEntry current) throws IOException {
        if (current.directory()) {
            return;
        }
        String childPrefix = normalizedKey + "/";
        for (String candidate : normalizedPaths.keySet()) {
            if (candidate.startsWith(childPrefix)) {
                throw new IOException("World archive contains a child beneath a regular file: " + normalizedKey);
            }
        }
    }

    /// Creates staging parents one segment at a time without following pre-existing links.
    ///
    /// @param stagingDirectory extraction root
    /// @param directory requested root or descendant directory
    /// @throws IOException when a segment is a link, file, or cannot be created
    private static void createDirectoriesWithoutLinks(Path stagingDirectory, Path directory) throws IOException {
        Path normalizedDirectory = directory.toAbsolutePath().normalize();
        if (!normalizedDirectory.startsWith(stagingDirectory)) {
            throw new IOException("World archive path escapes the staging directory");
        }
        Path current = stagingDirectory;
        Path relative = stagingDirectory.relativize(normalizedDirectory);
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                rejectSymbolicLink(current, "staging path");
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("World archive path conflicts with a regular file: " + current);
                }
            } else {
                Files.createDirectory(current);
            }
        }
    }

    /// Resolves validated path segments below an extraction root.
    ///
    /// @param root normalized staging root
    /// @param segments normalized archive-relative path segments
    /// @return normalized descendant path
    /// @throws IOException when resolution unexpectedly escapes the root
    private static Path resolveSegments(Path root, @Unmodifiable List<String> segments) throws IOException {
        Path resolved = root;
        for (String segment : segments) {
            resolved = resolved.resolve(segment);
        }
        Path normalized = resolved.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new IOException("World archive path escapes the staging directory");
        }
        return normalized;
    }

    /// Validates an existing regular local ZIP source without following links.
    ///
    /// @param archive candidate archive
    /// @return normalized local path
    /// @throws IOException when the source is missing, not regular, or symbolic
    private static Path requireRegularArchive(Path archive) throws IOException {
        Path normalizedArchive = Objects.requireNonNull(archive, "archive").toAbsolutePath().normalize();
        rejectSymbolicLink(normalizedArchive, "world archive");
        if (!Files.isRegularFile(normalizedArchive, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("World archive is not a regular file: " + normalizedArchive);
        }
        return normalizedArchive;
    }

    /// Validates a safe direct-child target directory name.
    ///
    /// @param targetName requested directory and stored level name
    /// @return unchanged validated name
    /// @throws IOException when blank, reserved, or path-like
    private static String requireTargetName(String targetName) throws IOException {
        String checkedName = Objects.requireNonNull(targetName, "targetName");
        if (checkedName.isBlank() || ".".equals(checkedName) || "..".equals(checkedName)) {
            throw new IOException("World target name must not be blank or reserved");
        }
        if (checkedName.indexOf('/') >= 0 || checkedName.indexOf('\\') >= 0 || checkedName.indexOf(':') >= 0) {
            throw new IOException("World target name must be one directory segment");
        }
        return checkedName;
    }

    /// Resolves a target and proves it remains a direct child of `saves`.
    ///
    /// @param savesDirectory normalized saves directory
    /// @param targetName validated single segment
    /// @return normalized direct-child destination
    /// @throws IOException when the platform rejects or rewrites the target path
    private static Path requireDirectDestination(Path savesDirectory, String targetName) throws IOException {
        try {
            Path destination = savesDirectory.resolve(targetName).toAbsolutePath().normalize();
            if (!Objects.equals(destination.getParent(), savesDirectory)) {
                throw new IOException("World target must remain directly under saves");
            }
            return destination;
        } catch (InvalidPathException exception) {
            throw new IOException("World target name is not valid on this platform", exception);
        }
    }

    /// Rejects a symbolic filesystem path without following it.
    ///
    /// @param path path to inspect
    /// @param label diagnostic label
    /// @throws IOException when the path itself is symbolic
    private static void rejectSymbolicLink(Path path, String label) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException(label + " must not be a symbolic link: " + path);
        }
    }

    /// Requires a known non-negative declared uncompressed size.
    ///
    /// @param entry regular archive entry
    /// @return declared expanded size
    /// @throws IOException when the central directory omits or corrupts the size
    private static long requireDeclaredSize(ZipArchiveEntry entry) throws IOException {
        long declaredSize = entry.getSize();
        if (declaredSize < 0L) {
            throw new IOException("World archive entry has no declared expanded size: " + entry.getName());
        }
        return declaredSize;
    }

    /// Adds a non-negative amount without overflow and enforces an inclusive ceiling.
    ///
    /// @param current current byte count
    /// @param additional non-negative bytes to add
    /// @param limit inclusive maximum
    /// @return checked total
    /// @throws IOException when invalid, overflowing, or over the ceiling
    private static long checkedTotal(long current, long additional, long limit) throws IOException {
        if (current < 0L || additional < 0L || current > limit - additional) {
            throw new IOException("World archive exceeds its expanded-size limit");
        }
        return current + additional;
    }

    /// Recognizes one root world metadata path after root normalization.
    ///
    /// @param segments normalized path segments
    /// @return true only for one supported root-level metadata filename
    private static boolean isWorldMetadataFile(@Unmodifiable List<String> segments) {
        return segments.size() == 1
                && ("level.dat".equalsIgnoreCase(segments.get(0))
                || "special_level.dat".equalsIgnoreCase(segments.get(0)));
    }

    /// Recognizes the standard root `level.dat` metadata filename.
    ///
    /// @param segments normalized path segments
    /// @return true only for one root-level standard metadata filename
    private static boolean isRegularLevelData(@Unmodifiable List<String> segments) {
        return segments.size() == 1 && "level.dat".equalsIgnoreCase(segments.get(0));
    }

    /// Recognizes Windows drive-prefixed archive paths independently of the host platform.
    ///
    /// @param portableName slash-normalized archive path
    /// @return true for names such as `C:/world/level.dat`
    private static boolean isDriveAbsolute(String portableName) {
        return portableName.length() >= 3
                && Character.isLetter(portableName.charAt(0))
                && portableName.charAt(1) == ':'
                && portableName.charAt(2) == '/';
    }

    /// Validated original central-directory entry and path segments.
    ///
    /// @param entry source entry owned by its open archive reader
    /// @param segments immutable normalized source path segments
    /// @param directory whether the source entry represents a directory
    private record RawEntry(
            ZipArchiveEntry entry,
            @Unmodifiable List<String> segments,
            boolean directory) {
        /// Retains the validated central-directory values.
        private RawEntry {
            Objects.requireNonNull(entry, "entry");
            segments = List.copyOf(Objects.requireNonNull(segments, "segments"));
        }
    }

    /// Validated source entry and root-stripped extraction path.
    ///
    /// @param entry source entry identifier retained across reader reopen
    /// @param relativeSegments immutable path below the staged world root
    /// @param directory whether this entry represents a directory
    private record PlannedEntry(
            ZipArchiveEntry entry,
            @Unmodifiable List<String> relativeSegments,
            boolean directory) {
        /// Retains immutable planned path segments.
        private PlannedEntry {
            Objects.requireNonNull(entry, "entry");
            relativeSegments = List.copyOf(Objects.requireNonNull(relativeSegments, "relativeSegments"));
        }
    }

    /// Actual streamed extraction counters.
    ///
    /// @param fileCount regular files written
    /// @param expandedBytes total expanded bytes written
    private record ExtractionMetrics(int fileCount, long expandedBytes) {
        /// Retains the internally produced non-negative counters.
        private ExtractionMetrics {
            if (fileCount < 0 || expandedBytes < 0L) {
                throw new IllegalArgumentException("Extraction metrics must not be negative");
            }
        }
    }
}
