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
package space.minecraftstl.xyml.ui.swing.page.settings;

import kala.compress.archivers.ArchiveEntry;
import kala.compress.archivers.tar.TarArchiveEntry;
import kala.compress.archivers.tar.TarArchiveInputStream;
import kala.compress.archivers.zip.UnixStat;
import kala.compress.archivers.zip.ZipArchiveEntry;
import kala.compress.archivers.zip.ZipArchiveOutputStream;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.GameJavaVersion;
import space.minecraftstl.xyml.java.JavaInfo;
import space.minecraftstl.xyml.java.JavaManager;
import space.minecraftstl.xyml.java.JavaRuntime;
import space.minecraftstl.xyml.ui.swing.page.settings.JavaManagerRuntimeAcquisitionService.ArchiveLimits;
import space.minecraftstl.xyml.ui.swing.page.settings.JavaRuntimeArchiveSupport.ArchiveFingerprint;
import space.minecraftstl.xyml.ui.swing.page.settings.JavaRuntimeArchiveSupport.ArchiveResourceBudget;
import space.minecraftstl.xyml.ui.swing.page.settings.JavaRuntimeArchiveSupport.ArchiveWriteBudget;
import space.minecraftstl.xyml.ui.swing.page.settings.JavaRuntimeArchiveSupport.JavaHomeLocation;
import space.minecraftstl.xyml.ui.swing.page.settings.JavaRuntimeArchiveSupport.LimitedOutputStream;
import space.minecraftstl.xyml.ui.swing.page.settings.JavaRuntimeArchiveSupport.OpenedArchive;
import space.minecraftstl.xyml.ui.swing.page.settings.JavaRuntimeArchiveSupport.TarPreflightBudget;
import space.minecraftstl.xyml.ui.swing.page.settings.JavaRuntimeArchiveSupport.ZipPreflightBudget;
import space.minecraftstl.xyml.setting.DownloadProviders;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.DigestUtils;
import space.minecraftstl.xyml.util.platform.OperatingSystem;
import space.minecraftstl.xyml.util.platform.Platform;
import space.minecraftstl.xyml.util.platform.UnsupportedPlatformException;
import space.minecraftstl.xyml.util.tree.ArchiveFileTree;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static space.minecraftstl.xyml.ui.swing.page.settings.JavaManagerRuntimeAcquisitionService.NORMALIZED_ARCHIVE_ROOT;
import static space.minecraftstl.xyml.ui.swing.page.settings.JavaManagerRuntimeAcquisitionService.archiveSuffix;
import static space.minecraftstl.xyml.ui.swing.page.settings.JavaManagerRuntimeAcquisitionService.isWindowsDeviceName;
import static space.minecraftstl.xyml.ui.swing.page.settings.JavaManagerRuntimeAcquisitionService.requireSameArchiveIdentity;
import static space.minecraftstl.xyml.ui.swing.page.settings.JavaManagerRuntimeAcquisitionService.requireSameJavaInfo;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Production backend delegating to established Java manager APIs behind a safe archive staging boundary.
///
/// Package visibility permits focused archive-security tests without exposing the backend as public API.
@NotNullByDefault
final class JavaRuntimeAcquisitionProcessBackend implements JavaRuntimeAcquisitionBackend {
    /// Maximum decoded archive entry path or symbolic-link target accepted after bounded metadata parsing.
    private static final int MAXIMUM_ARCHIVE_TEXT_CHARACTERS = 4_096;

    /// Resource ceilings enforced for every archive operation.
    private final ArchiveLimits archiveLimits;

    /// Creates the production backend with the standard archive resource ceilings.
    JavaRuntimeAcquisitionProcessBackend() {
        this(JavaManagerRuntimeAcquisitionService.DEFAULT_ARCHIVE_LIMITS);
    }

    /// Creates a production-equivalent backend with injectable limits for focused tests.
    ///
    /// @param archiveLimits resource ceilings
    JavaRuntimeAcquisitionProcessBackend(ArchiveLimits archiveLimits) {
        this.archiveLimits = Objects.requireNonNull(archiveLimits, "archiveLimits");
    }

    /// Returns the current system platform.
    ///
    /// @return current system platform
    @Override
    public Platform currentPlatform() {
        return Platform.SYSTEM_PLATFORM;
    }

    /// Returns the launcher-maintained Mojang component matrix only for the exact system platform.
    ///
    /// @param platform target platform
    /// @return immutable supported component list
    @Override
    public @Unmodifiable List<GameJavaVersion> supportedMojangVersions(Platform platform) {
        return Platform.SYSTEM_PLATFORM.equals(platform)
                ? List.copyOf(GameJavaVersion.getSupportedVersions(platform))
                : List.of();
    }

    /// Reads local Mojang manifest and directory markers after an acquisition task starts.
    ///
    /// @param platform target platform
    /// @param version Mojang component
    /// @return whether the component has local state
    @Override
    public boolean isMojangRuntimeInstalled(Platform platform, GameJavaVersion version) {
        return JavaManager.REPOSITORY.isInstalled(platform, version)
                || Files.exists(JavaManager.REPOSITORY.getJavaDir(platform, version));
    }

    /// Creates the established Mojang download task only after the outer acquisition task starts.
    ///
    /// @param platform target platform
    /// @param version selected Mojang component
    /// @return stopped Java manager download task
    @Override
    public Task<JavaRuntime> downloadMojangRuntime(Platform platform, GameJavaVersion version) {
        return JavaManager.getDownloadJavaTask(
                DownloadProviders.getDownloadProvider(),
                platform,
                version);
    }

    /// Opens and validates an archive with stable hashing and cooperative cancellation.
    ///
    /// @param archiveFile local archive path
    /// @param cancellationCheck cooperative cancellation callback
    /// @return immutable archive inspection
    /// @throws IOException when the path is missing, unstable, unsafe, or invalid
    /// @throws UnsupportedPlatformException when the runtime cannot execute on this host
    @Override
    public LocalJavaArchiveInspection inspectLocalArchive(
            Path archiveFile,
            CancellationCheck cancellationCheck) throws IOException, UnsupportedPlatformException {
        LocalJavaArchiveInspection inspection = inspectLocalArchiveLayout(archiveFile, cancellationCheck);
        if (!JavaManager.isCompatible(inspection.javaInfo().getPlatform())) {
            throw new UnsupportedPlatformException(inspection.javaInfo().getPlatform().toString());
        }
        return inspection;
    }

    /// Opens and validates an archive layout while proving that source bytes remain stable throughout parsing.
    ///
    /// @param archiveFile local archive path
    /// @param cancellationCheck cooperative cancellation callback
    /// @return immutable structural archive inspection
    /// @throws IOException when the path is missing, unstable, unsafe, or invalid
    LocalJavaArchiveInspection inspectLocalArchiveLayout(
            Path archiveFile,
            CancellationCheck cancellationCheck) throws IOException {
        Path normalizedArchive = archiveFile.toAbsolutePath().normalize();
        BasicFileAttributes attributesBefore = readArchiveAttributes(normalizedArchive);
        if (!attributesBefore.isRegularFile()) {
            throw new IOException("Java archive is not a regular file: " + normalizedArchive);
        }
        requireArchiveSourceSize(attributesBefore.size(), normalizedArchive);
        ArchiveFingerprint fingerprintBefore = fingerprintArchive(normalizedArchive, cancellationCheck);

        JavaHomeLocation<?> javaHome;
        try (OpenedArchive openedArchive = openArchive(normalizedArchive, cancellationCheck)) {
            ArchiveFileTree<?, ?> tree = openedArchive.tree();
            validateArchiveEntries(tree, fingerprintBefore.size(), cancellationCheck);
            javaHome = locateAndValidateJavaHome(tree);
        }

        ArchiveFingerprint fingerprintAfter = fingerprintArchive(normalizedArchive, cancellationCheck);
        BasicFileAttributes attributesAfter = readArchiveAttributes(normalizedArchive);
        requireStableArchive(
                normalizedArchive,
                attributesBefore,
                fingerprintBefore,
                attributesAfter,
                fingerprintAfter);
        return inspectionOf(normalizedArchive, javaHome, fingerprintAfter);
    }

    /// Copies one source through a bounded loop that observes cooperative cancellation.
    ///
    /// @param archiveFile user-selected source archive
    /// @param cancellationCheck cooperative cancellation callback
    /// @return controlled temporary copy
    /// @throws IOException when the source is missing, exceeds limits, or copying fails
    @Override
    public Path copyToManagedTemporaryArchive(
            Path archiveFile,
            CancellationCheck cancellationCheck) throws IOException {
        @Nullable String suffix = archiveSuffix(archiveFile);
        if (suffix == null) {
            throw new IOException("Unsupported Java archive: " + archiveFile);
        }
        Path source = archiveFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new IOException("Java archive is not a regular file: " + source);
        }
        requireArchiveSourceSize(Files.size(source), source);

        Path temporaryArchive = Files.createTempFile("xyml-java-source-", suffix);
        try (InputStream input = Files.newInputStream(source);
             OutputStream output = Files.newOutputStream(
                     temporaryArchive,
                     StandardOpenOption.TRUNCATE_EXISTING)) {
            copyBounded(
                    input,
                    output,
                    Math.min(archiveLimits.maxArchiveBytes(), archiveLimits.maxTemporaryArchiveBytes()),
                    cancellationCheck,
                    "Controlled Java archive copy exceeds its byte limit");
            return temporaryArchive;
        } catch (IOException failure) {
            Files.deleteIfExists(temporaryArchive);
            throw failure;
        } catch (RuntimeException failure) {
            Files.deleteIfExists(temporaryArchive);
            throw failure;
        }
    }

    /// Creates a stopped checksummed download into a bounded random temporary archive.
    ///
    /// @param uris immutable ordered download candidates
    /// @param archiveSuffix parser-significant `.zip` or `.tar.gz` suffix
    /// @param checksumAlgorithm normalized JCA checksum algorithm
    /// @param checksum expected lowercase hexadecimal checksum
    /// @return stopped managed archive download task
    Task<Path> downloadManagedTemporaryArchive(
            @Unmodifiable List<URI> uris,
            String archiveSuffix,
            String checksumAlgorithm,
            String checksum) {
        return new ManagedJavaArchiveDownloadTask(
                uris,
                archiveSuffix,
                checksumAlgorithm,
                checksum,
                Math.min(
                        archiveLimits.maxArchiveBytes(),
                        archiveLimits.maxTemporaryArchiveBytes()));
    }

    /// Revalidates and repacks one controlled source with stable hashing and cooperative cancellation.
    ///
    /// @param inspection inspection of a controlled source copy
    /// @param cancellationCheck cooperative cancellation callback
    /// @return inspection of the normalized install archive
    /// @throws IOException when the source changed, exceeds limits, or repacking fails
    /// @throws UnsupportedPlatformException when the normalized runtime cannot execute on this host
    @Override
    public LocalJavaArchiveInspection prepareInstallArchive(
            LocalJavaArchiveInspection inspection,
            CancellationCheck cancellationCheck) throws IOException, UnsupportedPlatformException {
        requireSameArchiveIdentity(
                inspection,
                inspectLocalArchiveLayout(inspection.archiveFile(), cancellationCheck));
        Path normalizedArchive = Files.createTempFile("xyml-java-install-", ".zip");
        boolean succeeded = false;
        BasicFileAttributes attributesBefore = readArchiveAttributes(inspection.archiveFile());
        ArchiveFingerprint fingerprintBefore = fingerprintArchive(
                inspection.archiveFile(),
                cancellationCheck);
        try (OpenedArchive openedArchive = openArchive(inspection.archiveFile(), cancellationCheck)) {
            ArchiveFileTree<?, ?> tree = openedArchive.tree();
            LocalJavaArchiveInspection normalizedInspection = prepareInstallArchiveFromTree(
                    inspection,
                    tree,
                    normalizedArchive,
                    fingerprintBefore,
                    cancellationCheck);
            ArchiveFingerprint fingerprintAfter = fingerprintArchive(
                    inspection.archiveFile(),
                    cancellationCheck);
            BasicFileAttributes attributesAfter = readArchiveAttributes(inspection.archiveFile());
            requireStableArchive(
                    inspection.archiveFile(),
                    attributesBefore,
                    fingerprintBefore,
                    attributesAfter,
                    fingerprintAfter);
            succeeded = true;
            return normalizedInspection;
        } finally {
            if (!succeeded) {
                Files.deleteIfExists(normalizedArchive);
            }
        }
    }

    /// Revalidates and repacks one controlled source while preserving its captured archive entry type.
    ///
    /// @param inspection expected controlled-source inspection
    /// @param tree opened controlled source tree
    /// @param normalizedArchive target normalized ZIP
    /// @param sourceFingerprint source fingerprint captured immediately before opening the tree
    /// @param cancellationCheck cooperative cancellation callback
    /// @return inspection of the normalized ZIP
    /// @throws IOException when revalidation or repacking fails
    private <F, E extends ArchiveEntry> LocalJavaArchiveInspection prepareInstallArchiveFromTree(
            LocalJavaArchiveInspection inspection,
            ArchiveFileTree<F, E> tree,
            Path normalizedArchive,
            ArchiveFingerprint sourceFingerprint,
            CancellationCheck cancellationCheck) throws IOException, UnsupportedPlatformException {
        validateArchiveEntries(tree, sourceFingerprint.size(), cancellationCheck);
        JavaHomeLocation<E> javaHome = locateAndValidateJavaHome(tree);
        requireSameArchiveIdentity(
                inspection,
                inspectionOf(inspection.archiveFile(), javaHome, sourceFingerprint));
        writeNormalizedJavaHome(tree, javaHome, normalizedArchive, cancellationCheck);
        LocalJavaArchiveInspection normalizedInspection = inspectLocalArchiveLayout(
                normalizedArchive,
                cancellationCheck);
        requireSameJavaInfo(inspection.javaInfo(), normalizedInspection.javaInfo());
        return normalizedInspection;
    }

    /// Best-effort removes a task-owned temporary archive and logs cleanup failures.
    ///
    /// @param archiveFile temporary archive to delete
    @Override
    public void deleteManagedTemporaryArchive(Path archiveFile) {
        try {
            Files.deleteIfExists(archiveFile);
        } catch (IOException failure) {
            LOG.warning("Failed to delete temporary Java archive " + archiveFile, failure);
        }
    }

    /// Returns the managed repository root for one platform without filesystem access.
    ///
    /// @param platform target platform
    /// @return normalized absolute platform root
    @Override
    public Path managedPlatformRoot(Platform platform) {
        return JavaManager.REPOSITORY.getPlatformRoot(platform).toAbsolutePath().normalize();
    }

    /// Reads both local manifest and directory markers for a named runtime.
    ///
    /// @param platform target platform
    /// @param name managed-runtime name
    /// @return whether either target already exists
    @Override
    public boolean isNamedRuntimeInstalled(Platform platform, String name) {
        return Files.exists(
                JavaManager.REPOSITORY.getManifestFile(platform, name),
                LinkOption.NOFOLLOW_LINKS)
                || Files.exists(
                JavaManager.REPOSITORY.getJavaDir(platform, name),
                LinkOption.NOFOLLOW_LINKS);
    }

    /// Creates a task that extracts into an owned staging directory before atomic publication.
    ///
    /// @param inspection normalized controlled install archive
    /// @param name validated managed-runtime name
    /// @return stopped guarded staging and publication task
    /// @throws IOException when final target paths are already occupied or escape their platform root
    @Override
    public Task<JavaRuntime> installLocalArchive(
            LocalJavaArchiveInspection inspection,
            String name) throws IOException {
        return installArchive(inspection, name, Map.of());
    }

    /// Creates a safe atomic installation task carrying immutable caller-owned update metadata.
    ///
    /// @param inspection normalized controlled install archive
    /// @param name validated managed-runtime name
    /// @param updateMetadata immutable manifest update metadata
    /// @return stopped guarded staging and publication task
    /// @throws IOException when final target paths are already occupied or escape their platform root
    Task<JavaRuntime> installArchive(
            LocalJavaArchiveInspection inspection,
            String name,
            @Unmodifiable Map<String, Object> updateMetadata) throws IOException {
        Platform platform = inspection.javaInfo().getPlatform();
        Path platformRoot = managedPlatformRoot(platform);
        Path targetDirectory = platformRoot.resolve(name).normalize();
        Path manifestFile = JavaManager.REPOSITORY.getManifestFile(platform, name)
                .toAbsolutePath().normalize();
        if (!platformRoot.equals(targetDirectory.getParent())) {
            throw new IOException("Managed Java target escapes its platform root: " + targetDirectory);
        }
        if (Files.exists(targetDirectory, LinkOption.NOFOLLOW_LINKS)
                || Files.exists(manifestFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(name);
        }

        return JavaRuntimeInstallationPublisher.createInstallTask(
                platform,
                name,
                inspection.archiveFile(),
                platformRoot,
                targetDirectory,
                manifestFile,
                updateMetadata);
    }

    /// Converts a located Java Home into a public immutable inspection.
    ///
    /// @param archiveFile inspected archive path
    /// @param javaHome validated Java Home location
    /// @return immutable inspection
    private static LocalJavaArchiveInspection inspectionOf(
            Path archiveFile,
            JavaHomeLocation<?> javaHome,
            ArchiveFingerprint fingerprint) {
        return new LocalJavaArchiveInspection(
                archiveFile,
                javaHome.path().get(0),
                String.join("/", javaHome.path()),
                javaHome.javaInfo(),
                fingerprint.size(),
                fingerprint.sha256());
    }

    /// Reads basic source attributes while following the selected regular file itself.
    ///
    /// @param archiveFile archive path
    /// @return basic file attributes
    /// @throws IOException when attributes cannot be read
    private static BasicFileAttributes readArchiveAttributes(Path archiveFile) throws IOException {
        return Files.readAttributes(archiveFile, BasicFileAttributes.class);
    }

    /// Rejects a source archive whose compressed bytes exceed the configured ceiling.
    ///
    /// @param sourceSize source archive byte length
    /// @param archiveFile archive path used in diagnostics
    /// @throws IOException when the source is empty or too large
    private void requireArchiveSourceSize(long sourceSize, Path archiveFile) throws IOException {
        if (sourceSize <= 0L || sourceSize > archiveLimits.maxArchiveBytes()) {
            throw new IOException(
                    "Java archive size is outside the permitted range: " + archiveFile + " (" + sourceSize + ")");
        }
    }

    /// Computes a bounded SHA-256 while observing task cancellation between input chunks.
    ///
    /// @param archiveFile archive path
    /// @param cancellationCheck cooperative cancellation callback
    /// @return exact byte length and canonical SHA-256
    /// @throws IOException when hashing fails or the source exceeds its ceiling
    private ArchiveFingerprint fingerprintArchive(
            Path archiveFile,
            CancellationCheck cancellationCheck) throws IOException {
        MessageDigest digest = DigestUtils.getDigest("SHA-256");
        byte[] buffer = new byte[8192];
        long size = 0L;
        try (InputStream input = Files.newInputStream(archiveFile)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                cancellationCheck.checkCancelled();
                if (read > archiveLimits.maxArchiveBytes() - size) {
                    throw new IOException("Java archive exceeds its source byte limit: " + archiveFile);
                }
                digest.update(buffer, 0, read);
                size += read;
            }
        }
        cancellationCheck.checkCancelled();
        requireArchiveSourceSize(size, archiveFile);
        return new ArchiveFingerprint(size, HexFormat.of().formatHex(digest.digest()));
    }

    /// Requires two complete reads and their surrounding file attributes to describe one stable archive.
    ///
    /// @param archiveFile archive path
    /// @param attributesBefore attributes captured before parsing
    /// @param fingerprintBefore first complete fingerprint
    /// @param attributesAfter attributes captured after parsing
    /// @param fingerprintAfter second complete fingerprint
    /// @throws IOException when any observable identity or byte content changed
    private static void requireStableArchive(
            Path archiveFile,
            BasicFileAttributes attributesBefore,
            ArchiveFingerprint fingerprintBefore,
            BasicFileAttributes attributesAfter,
            ArchiveFingerprint fingerprintAfter) throws IOException {
        if (!attributesAfter.isRegularFile()
                || attributesBefore.size() != attributesAfter.size()
                || !attributesBefore.lastModifiedTime().equals(attributesAfter.lastModifiedTime())
                || !attributesBefore.creationTime().equals(attributesAfter.creationTime())
                || !Objects.equals(attributesBefore.fileKey(), attributesAfter.fileKey())
                || attributesBefore.size() != fingerprintBefore.size()
                || attributesAfter.size() != fingerprintAfter.size()
                || !fingerprintBefore.equals(fingerprintAfter)) {
            throw new IOException("Java archive changed during inspection: " + archiveFile);
        }
    }

    /// Copies one stream with a hard output-byte ceiling and cooperative cancellation checks.
    ///
    /// @param input source stream
    /// @param output target stream
    /// @param maximumBytes maximum bytes permitted
    /// @param cancellationCheck cooperative cancellation callback
    /// @param limitMessage diagnostic used when the ceiling is crossed
    /// @return bytes written
    /// @throws IOException when copying fails or exceeds the ceiling
    private static long copyBounded(
            InputStream input,
            OutputStream output,
            long maximumBytes,
            CancellationCheck cancellationCheck,
            String limitMessage) throws IOException {
        byte[] buffer = new byte[8192];
        long written = 0L;
        int read;
        while ((read = input.read(buffer)) != -1) {
            cancellationCheck.checkCancelled();
            if (read > maximumBytes - written) {
                throw new IOException(limitMessage);
            }
            output.write(buffer, 0, read);
            written += read;
        }
        cancellationCheck.checkCancelled();
        return written;
    }

    /// Opens ZIP files directly and expands gzip-compressed TAR files through a bounded owned temporary file.
    ///
    /// @param archiveFile stable source archive
    /// @param cancellationCheck cooperative cancellation callback
    /// @return opened archive tree and optional owned expanded TAR
    /// @throws IOException when opening or bounded expansion fails
    private OpenedArchive openArchive(
            Path archiveFile,
            CancellationCheck cancellationCheck) throws IOException {
        @Nullable String suffix = archiveSuffix(archiveFile);
        if (".zip".equals(suffix)) {
            preflightZip(archiveFile, cancellationCheck);
            cancellationCheck.checkCancelled();
            String fileName = Objects.requireNonNull(
                    archiveFile.getFileName(),
                    "archiveFile file name").toString();
            if (fileName.endsWith(".zip")) {
                return new OpenedArchive(ArchiveFileTree.open(archiveFile), null);
            }

            Path normalizedZip = Files.createTempFile("xyml-java-case-normalized-", ".zip");
            try {
                try (InputStream input = Files.newInputStream(archiveFile);
                     OutputStream output = Files.newOutputStream(
                             normalizedZip,
                             StandardOpenOption.TRUNCATE_EXISTING)) {
                    copyBounded(
                            input,
                            output,
                            Math.min(
                                    archiveLimits.maxArchiveBytes(),
                                    archiveLimits.maxTemporaryArchiveBytes()),
                            cancellationCheck,
                            "Case-normalized Java ZIP exceeds its temporary byte limit");
                }
                return new OpenedArchive(ArchiveFileTree.open(normalizedZip), normalizedZip);
            } catch (IOException | RuntimeException failure) {
                Files.deleteIfExists(normalizedZip);
                throw failure;
            }
        }
        if (!".tar.gz".equals(suffix)) {
            throw new IOException("Unsupported Java archive: " + archiveFile);
        }

        long sourceBytes = Files.size(archiveFile);
        requireArchiveSourceSize(sourceBytes, archiveFile);
        long expandedLimit = archiveLimits.expandedTemporaryLimit(sourceBytes);
        Path expandedTar = Files.createTempFile("xyml-java-expanded-", ".tar");
        try {
            try (InputStream input = new GZIPInputStream(Files.newInputStream(archiveFile));
                 OutputStream output = Files.newOutputStream(
                         expandedTar,
                         StandardOpenOption.TRUNCATE_EXISTING)) {
                copyBounded(
                        input,
                        output,
                        expandedLimit,
                        cancellationCheck,
                        "Expanded Java TAR exceeds its temporary or compression-ratio limit");
            }
            cancellationCheck.checkCancelled();
            preflightTar(expandedTar, sourceBytes, cancellationCheck);
            cancellationCheck.checkCancelled();
            return new OpenedArchive(ArchiveFileTree.open(expandedTar), expandedTar);
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(expandedTar);
            throw failure;
        }
    }

    /// Preflights expanded TAR physical metadata and logical entries before Kala builds an in-memory tree.
    ///
    /// Every entry is cancellation-checked, counted, path-indexed, target-checked, and fully streamed through
    /// declared-size, actual-size, cumulative-byte, and compression-ratio budgets.
    ///
    /// @param expandedTar bounded expanded TAR
    /// @param sourceArchiveBytes compressed .tar.gz byte length
    /// @param cancellationCheck cooperative cancellation callback
    /// @throws IOException when an entry path, link target, size, count, or compression ratio is unsafe
    private void preflightTar(
            Path expandedTar,
            long sourceArchiveBytes,
            CancellationCheck cancellationCheck) throws IOException {
        JavaRuntimeContainerPreflight.preflightTarMetadata(
                expandedTar,
                archiveLimits,
                cancellationCheck);
        TarPreflightBudget budget = new TarPreflightBudget(archiveLimits, sourceArchiveBytes);
        Map<String, Boolean> indexedPaths = new HashMap<>();
        try (TarArchiveInputStream input = new TarArchiveInputStream(Files.newInputStream(expandedTar))) {
            while (true) {
                cancellationCheck.checkCancelled();
                @Nullable TarArchiveEntry entry = input.getNextEntry();
                if (entry == null) {
                    break;
                }
                cancellationCheck.checkCancelled();
                budget.registerEntry(entry);
                @Unmodifiable List<String> segments = parseSafeArchiveEntryName(
                        entry.getName(),
                        entry.isDirectory());
                indexArchivePath(indexedPaths, segments, entry.isDirectory());
                if (entry.isSymbolicLink()) {
                    normalizeContainedLinkTarget(
                            segments,
                            Objects.requireNonNull(entry.getLinkName(), "TAR symbolic-link target"),
                            1);
                } else if (entry.isLink()) {
                    parseSafeArchiveEntryName(
                            Objects.requireNonNull(entry.getLinkName(), "TAR hard-link target"),
                            false);
                }
                if (entry.isDirectory()) {
                    budget.recordActualBytes(entry, 0L);
                } else {
                    long actualBytes = copyBounded(
                            input,
                            OutputStream.nullOutputStream(),
                            budget.maximumRemainingEntryBytes(),
                            cancellationCheck,
                            "Java TAR entry exceeds its uncompressed byte limit: " + entry.getName());
                    budget.recordActualBytes(entry, actualBytes);
                }
            }
        }
        budget.requireComplete();
    }

    /// Bounds ZIP central metadata before constructing `ZipFile`, then streams every logical entry.
    ///
    /// Every entry is counted, path-checked, size-checked, and streamed through the actual-byte budget first.
    ///
    /// @param archiveFile source ZIP
    /// @param cancellationCheck cooperative cancellation callback
    /// @throws IOException when central metadata or actual entry contents violate a ceiling
    private void preflightZip(
            Path archiveFile,
            CancellationCheck cancellationCheck) throws IOException {
        long sourceBytes = Files.size(archiveFile);
        requireArchiveSourceSize(sourceBytes, archiveFile);
        JavaRuntimeContainerPreflight.preflightZipCentralDirectory(
                archiveFile,
                archiveLimits,
                cancellationCheck);
        ZipPreflightBudget budget = new ZipPreflightBudget(archiveLimits, sourceBytes);
        Map<String, Boolean> indexedPaths = new HashMap<>();
        try (ZipFile zipFile = new ZipFile(archiveFile.toFile())) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                cancellationCheck.checkCancelled();
                ZipEntry entry = entries.nextElement();
                @Unmodifiable List<String> segments = parseSafeArchiveEntryName(
                        entry.getName(),
                        entry.isDirectory());
                indexArchivePath(indexedPaths, segments, entry.isDirectory());
                budget.registerEntry(entry);
                if (!entry.isDirectory()) {
                    long actualBytes;
                    try (InputStream input = zipFile.getInputStream(entry)) {
                        actualBytes = copyBounded(
                                input,
                                OutputStream.nullOutputStream(),
                                budget.maximumRemainingEntryBytes(),
                                cancellationCheck,
                                "Java ZIP entry exceeds its uncompressed byte limit: " + entry.getName());
                    }
                    budget.recordActualBytes(entry, actualBytes);
                }
            }
        }
        budget.requireComplete();
    }

    /// Validates every explicit archive entry before metadata or contents are trusted.
    ///
    /// @param tree opened archive tree
    /// @throws IOException when an entry path, collision, or symbolic link is unsafe
    private <F, E extends ArchiveEntry> void validateArchiveEntries(
            ArchiveFileTree<F, E> tree,
            long sourceArchiveBytes,
            CancellationCheck cancellationCheck) throws IOException {
        Map<String, Boolean> indexedPaths = new HashMap<>();
        ArchiveResourceBudget budget = new ArchiveResourceBudget(archiveLimits, sourceArchiveBytes);
        validateArchiveDirectory(tree, tree.getRoot(), indexedPaths, budget, cancellationCheck);
        budget.requireComplete();
    }

    /// Recursively validates explicit files and directories in one archive tree directory.
    ///
    /// @param tree opened archive tree
    /// @param directory current archive directory
    /// @param indexedPaths normalized collision index
    /// @throws IOException when one entry is unsafe
    private static <F, E extends ArchiveEntry> void validateArchiveDirectory(
            ArchiveFileTree<F, E> tree,
            ArchiveFileTree.Dir<E> directory,
            Map<String, Boolean> indexedPaths,
            ArchiveResourceBudget budget,
            CancellationCheck cancellationCheck) throws IOException {
        for (E entry : directory.getFiles().values()) {
            validateArchiveEntry(tree, entry, indexedPaths, budget, cancellationCheck);
        }
        for (ArchiveFileTree.Dir<E> child : directory.getSubDirs().values()) {
            @Nullable E entry = child.getEntry();
            if (entry != null) {
                validateArchiveEntry(tree, entry, indexedPaths, budget, cancellationCheck);
            }
            validateArchiveDirectory(tree, child, indexedPaths, budget, cancellationCheck);
        }
    }

    /// Validates one raw entry path, normalized collisions, and a contained link target when present.
    ///
    /// @param tree opened archive tree
    /// @param entry explicit archive entry
    /// @param indexedPaths normalized collision index
    /// @throws IOException when the entry is unsafe
    private static <F, E extends ArchiveEntry> void validateArchiveEntry(
            ArchiveFileTree<F, E> tree,
            E entry,
            Map<String, Boolean> indexedPaths,
            ArchiveResourceBudget budget,
            CancellationCheck cancellationCheck) throws IOException {
        cancellationCheck.checkCancelled();
        budget.registerEntry(entry);
        @Unmodifiable List<String> segments = parseSafeArchiveEntryName(entry.getName(), entry.isDirectory());
        indexArchivePath(indexedPaths, segments, entry.isDirectory());
        if (tree.isLink(entry)) {
            normalizeContainedLinkTarget(segments, tree.getLink(entry), 1);
            budget.recordActualBytes(entry, entry.getSize());
        } else if (!entry.isDirectory()) {
            long actualBytes;
            try (InputStream input = tree.getInputStream(entry)) {
                actualBytes = copyBounded(
                        input,
                        OutputStream.nullOutputStream(),
                        budget.maximumRemainingEntryBytes(),
                        cancellationCheck,
                        "Java archive entry exceeds its uncompressed byte limit: " + entry.getName());
            }
            budget.recordActualBytes(entry, actualBytes);
        }
    }

    /// Parses a slash-separated relative archive entry and rejects alternate separators and ambiguous segments.
    ///
    /// @param rawName raw archive entry name
    /// @param directory whether the entry is a directory
    /// @return immutable safe path segments
    /// @throws IOException when the path is absolute, ambiguous, or non-portable
    private static @Unmodifiable List<String> parseSafeArchiveEntryName(
            String rawName,
            boolean directory) throws IOException {
        if (rawName.isEmpty()
                || rawName.length() > MAXIMUM_ARCHIVE_TEXT_CHARACTERS
                || rawName.indexOf('\\') >= 0) {
            throw new IOException("Unsafe archive entry path: " + rawName);
        }
        String normalized = rawName;
        if (directory && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()
                || normalized.startsWith("/")
                || hasDrivePrefix(normalized)) {
            throw new IOException("Absolute archive entry path: " + rawName);
        }

        String[] rawSegments = normalized.split("/", -1);
        List<String> segments = new ArrayList<>(rawSegments.length);
        for (String segment : rawSegments) {
            validatePortablePathSegment(segment, rawName);
            segments.add(segment);
        }
        return List.copyOf(segments);
    }

    /// Adds one normalized archive path to a case-aware collision and file-parent index.
    ///
    /// @param indexedPaths normalized path types, where `true` denotes a directory
    /// @param segments safe entry segments
    /// @param directory whether the entry is a directory
    /// @throws IOException when files and directories collide after normalization
    private static void indexArchivePath(
            Map<String, Boolean> indexedPaths,
            List<String> segments,
            boolean directory) throws IOException {
        for (int depth = 1; depth < segments.size(); depth++) {
            String parentKey = archivePathKey(segments.subList(0, depth));
            @Nullable Boolean parentType = indexedPaths.get(parentKey);
            if (Boolean.FALSE.equals(parentType)) {
                throw new IOException("Archive file is also a parent directory: " + parentKey);
            }
            indexedPaths.putIfAbsent(parentKey, true);
        }

        String key = archivePathKey(segments);
        @Nullable Boolean previousType = indexedPaths.get(key);
        if (directory) {
            if (Boolean.FALSE.equals(previousType)) {
                throw new IOException("Archive file and directory collide: " + key);
            }
            indexedPaths.putIfAbsent(key, true);
        } else {
            if (previousType != null) {
                throw new IOException("Duplicate or colliding archive file: " + key);
            }
            indexedPaths.put(key, false);
        }
    }

    /// Produces a collision key appropriate for the current platform's common filesystem semantics.
    ///
    /// @param segments normalized entry segments
    /// @return slash-separated collision key
    private static String archivePathKey(List<String> segments) {
        String key = String.join("/", segments);
        return OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS
                || OperatingSystem.CURRENT_OS == OperatingSystem.MACOS
                ? key.toLowerCase(Locale.ROOT)
                : key;
    }

    /// Resolves a relative symbolic-link target lexically and requires it to remain below a minimum prefix depth.
    ///
    /// Backslashes are treated as separators for containment and rewritten as slashes for the normalized ZIP.
    ///
    /// @param entrySegments full path of the symbolic-link entry
    /// @param rawTarget raw symbolic-link target
    /// @param minimumDepth root or Java Home prefix depth that may not be crossed
    /// @return slash-normalized relative target
    /// @throws IOException when the target is absolute, ambiguous, or escapes the required prefix
    private static String normalizeContainedLinkTarget(
            List<String> entrySegments,
            String rawTarget,
            int minimumDepth) throws IOException {
        String normalizedTarget = Objects.requireNonNull(rawTarget, "rawTarget").replace('\\', '/');
        if (normalizedTarget.isEmpty()
                || normalizedTarget.length() > MAXIMUM_ARCHIVE_TEXT_CHARACTERS
                || normalizedTarget.startsWith("/")
                || hasDrivePrefix(normalizedTarget)) {
            throw new IOException("Unsafe symbolic-link target: " + rawTarget);
        }

        List<String> resolved = new ArrayList<>(entrySegments.subList(0, entrySegments.size() - 1));
        String[] targetSegments = normalizedTarget.split("/", -1);
        for (String segment : targetSegments) {
            if (segment.isEmpty()) {
                throw new IOException("Ambiguous symbolic-link target: " + rawTarget);
            }
            if (segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (resolved.size() <= minimumDepth) {
                    throw new IOException("Symbolic link escapes Java archive root: " + rawTarget);
                }
                resolved.remove(resolved.size() - 1);
            } else {
                validatePortablePathSegment(segment, rawTarget);
                resolved.add(segment);
            }
        }
        if (resolved.size() < minimumDepth) {
            throw new IOException("Symbolic link escapes Java archive root: " + rawTarget);
        }
        return normalizedTarget;
    }

    /// Resolves every Java Home symbolic link through the complete in-archive link graph.
    ///
    /// @param tree validated archive tree
    /// @param javaHome located Java Home
    /// @throws IOException when a link is cyclic, absolute, or resolves outside Java Home
    private static <F, E extends ArchiveEntry> void validateJavaHomeSymlinkGraph(
            ArchiveFileTree<F, E> tree,
            JavaHomeLocation<E> javaHome) throws IOException {
        Map<String, ArchiveSymlink> links = new HashMap<>();
        collectJavaHomeSymlinks(
                tree,
                javaHome.directory(),
                javaHome.path(),
                List.of(),
                links);
        for (ArchiveSymlink link : links.values()) {
            resolveArchiveSymlink(link, javaHome.path().size(), links, new HashSet<>());
        }
    }

    /// Recursively collects symbolic links below one Java Home directory.
    ///
    /// @param tree opened archive tree
    /// @param directory current Java Home subtree directory
    /// @param javaHomePath full Java Home prefix
    /// @param relativePath current path below Java Home
    /// @param links destination graph indexed by filesystem-aware archive keys
    /// @throws IOException when a link target cannot be read
    private static <F, E extends ArchiveEntry> void collectJavaHomeSymlinks(
            ArchiveFileTree<F, E> tree,
            ArchiveFileTree.Dir<E> directory,
            List<String> javaHomePath,
            List<String> relativePath,
            Map<String, ArchiveSymlink> links) throws IOException {
        for (Map.Entry<String, E> file : directory.getFiles().entrySet()) {
            if (tree.isLink(file.getValue())) {
                @Unmodifiable List<String> path = appendSegments(
                        javaHomePath,
                        appendSegment(relativePath, file.getKey()));
                links.put(
                        archivePathKey(path),
                        new ArchiveSymlink(path, tree.getLink(file.getValue())));
            }
        }
        for (Map.Entry<String, ArchiveFileTree.Dir<E>> child : directory.getSubDirs().entrySet()) {
            collectJavaHomeSymlinks(
                    tree,
                    child.getValue(),
                    javaHomePath,
                    appendSegment(relativePath, child.getKey()),
                    links);
        }
    }

    /// Resolves one link recursively while tracking the active graph path for cycle detection.
    ///
    /// @param link symbolic link to resolve
    /// @param javaHomeDepth immutable Java Home prefix depth
    /// @param links complete Java Home link graph
    /// @param activeLinks active recursion keys
    /// @return immutable final resolved path
    /// @throws IOException when resolution cycles or escapes Java Home
    private static @Unmodifiable List<String> resolveArchiveSymlink(
            ArchiveSymlink link,
            int javaHomeDepth,
            Map<String, ArchiveSymlink> links,
            Set<String> activeLinks) throws IOException {
        String linkKey = archivePathKey(link.path());
        if (!activeLinks.add(linkKey)) {
            throw new IOException("Cyclic Java archive symbolic link: " + String.join("/", link.path()));
        }
        try {
            return resolveArchiveLinkTarget(
                    link.path().subList(0, link.path().size() - 1),
                    link.target(),
                    javaHomeDepth,
                    links,
                    activeLinks);
        } finally {
            activeLinks.remove(linkKey);
        }
    }

    /// Resolves a relative target segment by segment, expanding every encountered archive link.
    ///
    /// @param parentPath containing directory of the current link
    /// @param rawTarget raw symbolic-link target
    /// @param javaHomeDepth immutable Java Home prefix depth
    /// @param links complete Java Home link graph
    /// @param activeLinks active recursion keys
    /// @return immutable final resolved path
    /// @throws IOException when the target is ambiguous, cyclic, absolute, or escaping
    private static @Unmodifiable List<String> resolveArchiveLinkTarget(
            List<String> parentPath,
            String rawTarget,
            int javaHomeDepth,
            Map<String, ArchiveSymlink> links,
            Set<String> activeLinks) throws IOException {
        String normalizedTarget = Objects.requireNonNull(rawTarget, "rawTarget").replace('\\', '/');
        if (normalizedTarget.isEmpty()
                || normalizedTarget.length() > MAXIMUM_ARCHIVE_TEXT_CHARACTERS
                || normalizedTarget.startsWith("/")
                || hasDrivePrefix(normalizedTarget)) {
            throw new IOException("Unsafe symbolic-link target: " + rawTarget);
        }

        List<String> resolved = new ArrayList<>(parentPath);
        for (String segment : normalizedTarget.split("/", -1)) {
            if (segment.isEmpty()) {
                throw new IOException("Ambiguous symbolic-link target: " + rawTarget);
            }
            if (segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (resolved.size() <= javaHomeDepth) {
                    throw new IOException("Symbolic link escapes Java Home: " + rawTarget);
                }
                resolved.remove(resolved.size() - 1);
                continue;
            }

            validatePortablePathSegment(segment, rawTarget);
            resolved.add(segment);
            @Nullable ArchiveSymlink nestedLink = links.get(archivePathKey(resolved));
            if (nestedLink != null) {
                resolved = new ArrayList<>(resolveArchiveSymlink(
                        nestedLink,
                        javaHomeDepth,
                        links,
                        activeLinks));
            }
        }
        if (resolved.size() < javaHomeDepth) {
            throw new IOException("Symbolic link escapes Java Home: " + rawTarget);
        }
        return List.copyOf(resolved);
    }

    /// Rejects one empty, traversal, trailing-dot, device, alternate-stream, or NUL path segment.
    ///
    /// @param segment candidate segment
    /// @param source full source path used in diagnostics
    /// @throws IOException when the segment is unsafe or non-portable
    private static void validatePortablePathSegment(String segment, String source) throws IOException {
        if (segment.isEmpty()
                || segment.equals(".")
                || segment.equals("..")
                || segment.endsWith(".")
                || segment.endsWith(" ")
                || segment.indexOf('\0') >= 0
                || segment.indexOf(':') >= 0
                || isWindowsDeviceName(segment)) {
            throw new IOException("Unsafe archive path segment in " + source + ": " + segment);
        }
    }

    /// Returns whether a path begins with an ASCII drive designator such as `C:`.
    ///
    /// @param path slash-normalized path
    /// @return whether the path has a drive prefix
    private static boolean hasDrivePrefix(String path) {
        return path.length() >= 2
                && ((path.charAt(0) >= 'A' && path.charAt(0) <= 'Z')
                || (path.charAt(0) >= 'a' && path.charAt(0) <= 'z'))
                && path.charAt(1) == ':';
    }

    /// Locates one Java Home and validates its complete symbolic-link graph using the same generic capture.
    ///
    /// @param tree validated archive tree
    /// @return located and graph-validated Java Home
    /// @throws IOException when Java Home or link graph validation fails
    private static <F, E extends ArchiveEntry> JavaHomeLocation<E> locateAndValidateJavaHome(
            ArchiveFileTree<F, E> tree) throws IOException {
        JavaHomeLocation<E> javaHome = locateJavaHome(tree);
        validateJavaHomeSymlinkGraph(tree, javaHome);
        return javaHome;
    }

    /// Locates either a direct archive-root Java Home or a macOS `Contents/Home` Java Home.
    ///
    /// @param tree validated archive tree
    /// @return located Java Home and metadata
    /// @throws IOException when the archive does not contain exactly one supported Java Home
    private static <F, E extends ArchiveEntry> JavaHomeLocation<E> locateJavaHome(
            ArchiveFileTree<F, E> tree) throws IOException {
        if (tree.getRoot().getSubDirs().size() != 1 || !tree.getRoot().getFiles().isEmpty()) {
            throw new IOException("Java archive must contain one top-level directory");
        }

        Map.Entry<String, ArchiveFileTree.Dir<E>> rootEntry = tree.getRoot()
                .getSubDirs()
                .entrySet()
                .iterator()
                .next();
        String rootName = rootEntry.getKey();
        ArchiveFileTree.Dir<E> rootDirectory = rootEntry.getValue();
        @Nullable JavaInfo directInfo = readJavaHomeInfo(tree, rootDirectory);
        if (directInfo != null) {
            return new JavaHomeLocation<>(rootDirectory, List.of(rootName), directInfo);
        }

        @Nullable ArchiveFileTree.Dir<E> contentsDirectory = rootDirectory.getSubDirs().get("Contents");
        @Nullable ArchiveFileTree.Dir<E> homeDirectory = contentsDirectory == null
                ? null
                : contentsDirectory.getSubDirs().get("Home");
        if (homeDirectory == null) {
            throw new IOException("Java archive is missing a Java Home release file");
        }
        @Nullable JavaInfo bundleInfo = readJavaHomeInfo(tree, homeDirectory);
        if (bundleInfo == null || bundleInfo.getPlatform().getOperatingSystem() != OperatingSystem.MACOS) {
            throw new IOException("Contents/Home does not contain a macOS Java runtime");
        }
        return new JavaHomeLocation<>(
                homeDirectory,
                List.of(rootName, "Contents", "Home"),
                bundleInfo);
    }

    /// Reads release metadata and verifies the platform-specific Java executable for one candidate Java Home.
    ///
    /// @param tree opened archive tree
    /// @param javaHome candidate Java Home directory
    /// @return Java metadata, or `null` when the directory has no release file
    /// @throws IOException when present metadata or executable layout is invalid
    private static <F, E extends ArchiveEntry> @Nullable JavaInfo readJavaHomeInfo(
            ArchiveFileTree<F, E> tree,
            ArchiveFileTree.Dir<E> javaHome) throws IOException {
        @Nullable E releaseEntry = javaHome.getFiles().get("release");
        if (releaseEntry == null) {
            return null;
        }
        JavaInfo javaInfo;
        try (var reader = tree.getBufferedReader(releaseEntry)) {
            javaInfo = JavaInfo.fromReleaseFile(reader);
        }

        @Nullable ArchiveFileTree.Dir<E> binDirectory = javaHome.getSubDirs().get("bin");
        String executableName = javaInfo.getPlatform().getOperatingSystem().getJavaExecutable();
        if (binDirectory == null || binDirectory.getFiles().get(executableName) == null) {
            throw new IOException("Java archive is missing bin/" + executableName);
        }
        return javaInfo;
    }

    /// Writes a normalized ZIP containing only one validated Java Home subtree.
    ///
    /// @param tree opened controlled source archive
    /// @param javaHome located Java Home subtree
    /// @param targetArchive target normalized ZIP
    /// @param cancellationCheck cooperative cancellation callback
    /// @throws IOException when contents cannot be copied safely
    private <F, E extends ArchiveEntry> void writeNormalizedJavaHome(
            ArchiveFileTree<F, E> tree,
            JavaHomeLocation<E> javaHome,
            Path targetArchive,
            CancellationCheck cancellationCheck) throws IOException {
        ArchiveWriteBudget budget = new ArchiveWriteBudget(archiveLimits);
        try (ZipArchiveOutputStream output = new ZipArchiveOutputStream(
                new LimitedOutputStream(
                        Files.newOutputStream(targetArchive, StandardOpenOption.TRUNCATE_EXISTING),
                        archiveLimits.maxTemporaryArchiveBytes(),
                        cancellationCheck))) {
            writeDirectoryEntry(output, NORMALIZED_ARCHIVE_ROOT + "/");
            writeNormalizedDirectory(
                    tree,
                    javaHome.directory(),
                    javaHome.path(),
                    List.of(),
                    output,
                    budget,
                    cancellationCheck);
        }
    }

    /// Recursively copies files, links, executable bits, and child directories below one Java Home.
    ///
    /// @param tree opened controlled source archive
    /// @param directory current source directory
    /// @param javaHomePath full archive Java Home prefix
    /// @param relativePath path below the Java Home
    /// @param output normalized ZIP output
    /// @param budget uncompressed output-byte budget
    /// @param cancellationCheck cooperative cancellation callback
    /// @throws IOException when a source entry cannot be copied safely
    private static <F, E extends ArchiveEntry> void writeNormalizedDirectory(
            ArchiveFileTree<F, E> tree,
            ArchiveFileTree.Dir<E> directory,
            List<String> javaHomePath,
            List<String> relativePath,
            ZipArchiveOutputStream output,
            ArchiveWriteBudget budget,
            CancellationCheck cancellationCheck) throws IOException {
        for (Map.Entry<String, E> fileEntry : directory.getFiles().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList()) {
            cancellationCheck.checkCancelled();
            String fileName = fileEntry.getKey();
            E sourceEntry = fileEntry.getValue();
            List<String> relativeFilePath = appendSegment(relativePath, fileName);
            List<String> fullFilePath = appendSegments(javaHomePath, relativeFilePath);
            String outputName = NORMALIZED_ARCHIVE_ROOT + "/" + String.join("/", relativeFilePath);
            ZipArchiveEntry outputEntry = new ZipArchiveEntry(outputName);
            if (tree.isLink(sourceEntry)) {
                String target = normalizeContainedLinkTarget(
                        fullFilePath,
                        tree.getLink(sourceEntry),
                        javaHomePath.size());
                byte[] targetBytes = target.getBytes(StandardCharsets.UTF_8);
                budget.recordOutputBytes(targetBytes.length, outputName);
                outputEntry.setUnixMode(UnixStat.LINK_FLAG | UnixStat.DEFAULT_LINK_PERM);
                output.putArchiveEntry(outputEntry);
                output.write(targetBytes);
            } else {
                budget.requireEntryFits(sourceEntry.getSize(), outputName);
                outputEntry.setUnixMode(UnixStat.FILE_FLAG
                        | (tree.isExecutable(sourceEntry) ? 0755 : UnixStat.DEFAULT_FILE_PERM));
                output.putArchiveEntry(outputEntry);
                long copiedBytes;
                try (InputStream input = tree.getInputStream(sourceEntry)) {
                    copiedBytes = copyBounded(
                            input,
                            output,
                            budget.maximumRemainingEntryBytes(),
                            cancellationCheck,
                            "Normalized Java entry exceeds its uncompressed byte limit: " + outputName);
                }
                budget.recordSourceEntry(sourceEntry, copiedBytes, outputName);
            }
            output.closeArchiveEntry();
        }

        for (Map.Entry<String, ArchiveFileTree.Dir<E>> childEntry : directory.getSubDirs().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList()) {
            cancellationCheck.checkCancelled();
            List<String> childPath = appendSegment(relativePath, childEntry.getKey());
            writeDirectoryEntry(
                    output,
                    NORMALIZED_ARCHIVE_ROOT + "/" + String.join("/", childPath) + "/");
            writeNormalizedDirectory(
                    tree,
                    childEntry.getValue(),
                    javaHomePath,
                    childPath,
                    output,
                    budget,
                    cancellationCheck);
        }
    }

    /// Writes one executable-metadata-preserving UNIX directory entry to a normalized ZIP.
    ///
    /// @param output normalized ZIP output
    /// @param name slash-terminated entry name
    /// @throws IOException when the entry cannot be written
    private static void writeDirectoryEntry(
            ZipArchiveOutputStream output,
            String name) throws IOException {
        ZipArchiveEntry entry = new ZipArchiveEntry(name);
        entry.setUnixMode(UnixStat.DIR_FLAG | UnixStat.DEFAULT_DIR_PERM);
        output.putArchiveEntry(entry);
        output.closeArchiveEntry();
    }

    /// Appends one segment to an immutable path list.
    ///
    /// @param path existing segments
    /// @param segment segment to append
    /// @return immutable appended path
    private static @Unmodifiable List<String> appendSegment(
            List<String> path,
            String segment) {
        List<String> result = new ArrayList<>(path.size() + 1);
        result.addAll(path);
        result.add(segment);
        return List.copyOf(result);
    }

    /// Concatenates two immutable path lists.
    ///
    /// @param prefix leading segments
    /// @param suffix trailing segments
    /// @return immutable combined path
    private static @Unmodifiable List<String> appendSegments(
            List<String> prefix,
            List<String> suffix) {
        List<String> result = new ArrayList<>(prefix.size() + suffix.size());
        result.addAll(prefix);
        result.addAll(suffix);
        return List.copyOf(result);
    }

    /// One symbolic-link node in the Java Home archive graph.
    ///
    /// @param path immutable full archive path of the link
    /// @param target raw relative target stored by the archive
    @NotNullByDefault
    private record ArchiveSymlink(
            @Unmodifiable List<String> path,
            String target) {
        /// Defensively snapshots link data.
        private ArchiveSymlink {
            path = List.copyOf(Objects.requireNonNull(path, "path"));
            if (path.isEmpty()) {
                throw new IllegalArgumentException("path must not be empty");
            }
            target = Objects.requireNonNull(target, "target");
        }
    }

}
