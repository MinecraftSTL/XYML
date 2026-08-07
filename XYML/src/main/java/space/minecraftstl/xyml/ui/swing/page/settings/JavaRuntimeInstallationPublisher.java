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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.java.JavaManifest;
import space.minecraftstl.xyml.java.JavaRuntime;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.ui.swing.page.settings.JavaManagerRuntimeAcquisitionService.IncompleteInstallCleanup;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.io.FileUtils;
import space.minecraftstl.xyml.util.platform.OperatingSystem;
import space.minecraftstl.xyml.util.platform.Platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Installs Java into an unpredictable owned staging directory and atomically publishes verified results.
///
/// Neither the legacy archive installer nor the JSON writer opens a caller-visible final path. Final runtime and
/// manifest paths are used only as atomic-move destinations, so a symbolic-link or hard-link replacement cannot
/// redirect archive bytes or manifest bytes into an external file. Rollback quarantines a path before recursive
/// deletion and proceeds only while a live directory identity lease plus the random marker's external hard-link
/// lease still identify the original filesystem entries.
@NotNullByDefault
final class JavaRuntimeInstallationPublisher {
    /// Manifest update key carrying the random ownership token through the legacy installer result.
    private static final String INSTALL_OWNER_UPDATE_KEY = "xyml.acquisitionOwner";

    /// Prefix for the ownership marker stored inside a staging or published runtime directory.
    private static final String INSTALL_OWNER_MARKER_PREFIX = ".xyml-install-owner-";

    /// Prefix for unpredictable staging directories created below the managed platform root.
    private static final String STAGING_DIRECTORY_PREFIX = ".xyml-java-stage-";

    /// Prefix for hard-link leases that keep the original ownership-marker inode identifiable across replacement.
    private static final String STAGING_LEASE_PREFIX = ".xyml-java-lease-";

    /// Prefix for manifest files written before publication.
    private static final String STAGING_MANIFEST_PREFIX = ".xyml-java-manifest-";

    /// Maximum serialized managed-runtime manifest size accepted for publication and rollback inspection.
    private static final long MAXIMUM_MANIFEST_BYTES = 64L * 1024L * 1024L;

    /// Prevents construction of the stateless publisher namespace.
    private JavaRuntimeInstallationPublisher() {
    }

    /// Creates a stopped installation task whose extraction phase touches only an owned staging directory.
    ///
    /// @param platform selected runtime platform
    /// @param name validated managed-runtime name
    /// @param archiveFile controlled normalized archive
    /// @param platformRoot managed platform root
    /// @param targetDirectory final runtime directory
    /// @param manifestFile final runtime manifest
    /// @return stopped staging and publication task
    static Task<JavaRuntime> createInstallTask(
            Platform platform,
            String name,
            Path archiveFile,
            Path platformRoot,
            Path targetDirectory,
            Path manifestFile) {
        return createInstallTask(
                platform,
                name,
                archiveFile,
                platformRoot,
                targetDirectory,
                manifestFile,
                Map.of());
    }

    /// Creates a stopped installation task carrying caller-supplied immutable manifest update metadata.
    ///
    /// The publisher adds its private ownership token after rejecting attempts to supply that reserved key.
    ///
    /// @param platform selected runtime platform
    /// @param name validated managed-runtime name
    /// @param archiveFile controlled normalized archive
    /// @param platformRoot managed platform root
    /// @param targetDirectory final runtime directory
    /// @param manifestFile final runtime manifest
    /// @param updateMetadata immutable caller-owned manifest update metadata
    /// @return stopped staging and publication task
    static Task<JavaRuntime> createInstallTask(
            Platform platform,
            String name,
            Path archiveFile,
            Path platformRoot,
            Path targetDirectory,
            Path manifestFile,
            @Unmodifiable Map<String, Object> updateMetadata) {
        return new InstallTask(
                platform,
                name,
                archiveFile,
                platformRoot,
                targetDirectory,
                manifestFile,
                updateMetadata);
    }

    /// Deferred task that extracts to staging, publishes atomically, and retains proof for outer-chain rollback.
    @NotNullByDefault
    private static final class InstallTask extends Task<JavaRuntime>
            implements IncompleteInstallCleanup {
        /// Runtime target platform.
        private final Platform platform;

        /// Validated managed runtime name used for diagnostics.
        private final String name;

        /// Controlled normalized archive read only by the legacy staging installer.
        private final Path archiveFile;

        /// Expected managed platform root.
        private final Path platformRoot;

        /// Final runtime directory used only as an atomic-move destination.
        private final Path targetDirectory;

        /// Final manifest used only as an atomic-move destination.
        private final Path manifestFile;

        /// Immutable caller-supplied update metadata excluding the publisher's private ownership key.
        private final @Unmodifiable Map<String, Object> updateMetadata;

        /// Current staging or published directory ownership, or null before task execution.
        private @Nullable OwnedDirectory ownership;

        /// Safe archive extractor targeting the random staging directory, or null before task execution.
        private @Nullable SafeJavaRuntimeExtractionTask stagingInstaller;

        /// Creates a deferred task without reading the archive or touching repository paths.
        ///
        /// @param platform selected runtime platform
        /// @param name validated managed-runtime name
        /// @param archiveFile controlled normalized archive
        /// @param platformRoot managed platform root
        /// @param targetDirectory final runtime directory
        /// @param manifestFile final runtime manifest
        /// @param updateMetadata immutable caller-owned manifest update metadata
        private InstallTask(
                Platform platform,
                String name,
                Path archiveFile,
                Path platformRoot,
                Path targetDirectory,
                Path manifestFile,
                @Unmodifiable Map<String, Object> updateMetadata) {
            this.platform = Objects.requireNonNull(platform, "platform");
            this.name = Objects.requireNonNull(name, "name");
            this.archiveFile = normalizedPath(archiveFile, "archiveFile");
            this.platformRoot = normalizedPath(platformRoot, "platformRoot");
            this.targetDirectory = normalizedPath(targetDirectory, "targetDirectory");
            this.manifestFile = normalizedPath(manifestFile, "manifestFile");
            this.updateMetadata = Map.copyOf(Objects.requireNonNull(updateMetadata, "updateMetadata"));
            if (this.updateMetadata.containsKey(INSTALL_OWNER_UPDATE_KEY)) {
                throw new IllegalArgumentException(
                        "Manifest update metadata uses reserved key " + INSTALL_OWNER_UPDATE_KEY);
            }
            setName("Install managed Java runtime " + name);
        }

        /// Creates the owned staging directory and the stopped legacy extraction task.
        @Override
        public void execute() throws Exception {
            requireDirectFinalChildren(platformRoot, targetDirectory, manifestFile);
            OwnedDirectory staged = createOwnedStagingDirectory(
                    platformRoot,
                    targetDirectory,
                    manifestFile);
            ownership = staged;
            try {
                Map<String, Object> ownedUpdate = new java.util.LinkedHashMap<>(updateMetadata);
                ownedUpdate.put(INSTALL_OWNER_UPDATE_KEY, staged.token());
                stagingInstaller = new SafeJavaRuntimeExtractionTask(
                        staged,
                        Map.copyOf(ownedUpdate),
                        archiveFile);
            } catch (IOException | RuntimeException failure) {
                cleanupOwnedDirectory(staged);
                throw failure;
            }
        }

        /// Returns the staging-only legacy installer after task execution.
        ///
        /// @return immutable empty or singleton dependency collection
        @Override
        public @Unmodifiable Collection<Task<?>> getDependencies() {
            return stagingInstaller == null
                    ? Collections.emptySet()
                    : Collections.singleton(stagingInstaller);
        }

        /// Requests publication and cleanup after the staging dependency terminates.
        ///
        /// @return always true
        @Override
        public boolean doPostExecute() {
            return true;
        }

        /// Publishes a successful staging result without opening either final path for writing.
        @Override
        public void postExecute() throws IOException {
            @Nullable OwnedDirectory currentOwnership = ownership;
            @Nullable SafeJavaRuntimeExtractionTask currentInstaller = stagingInstaller;
            @Nullable JavaManifest stagedManifest = currentInstaller == null
                    ? null
                    : currentInstaller.getResult();
            boolean stagedSuccessfully = isDependenciesSucceeded()
                    && !isCancelled()
                    && currentOwnership != null
                    && stagedManifest != null;
            boolean committed = false;
            try {
                if (!stagedSuccessfully) {
                    return;
                }
                if (!platform.equals(stagedManifest.info().getPlatform())) {
                    throw new IOException(
                            "Platform is mismatch: expected " + platform
                                    + " but got " + stagedManifest.info().getPlatform());
                }

                currentInstaller.requireExtractionOwnership();
                OwnedDirectory published = moveOwnedDirectoryToFinal(currentOwnership);
                ownership = published;
                if (isCancelled()) {
                    throw new IOException("Managed Java installation was cancelled before manifest publication");
                }
                writeOwnedManifest(published, stagedManifest);
                if (isCancelled()) {
                    throw new IOException("Managed Java installation was cancelled before commit");
                }
                JavaRuntime runtime = runtimeFromPublished(published, stagedManifest);
                releaseOwnershipLease(published);
                published.directoryIdentityLease().close();
                setResult(runtime);
                committed = true;
            } finally {
                if (!committed) {
                    cleanupCurrentState();
                }
            }
        }

        /// Removes only state still proven to belong to this task when the outer task chain cannot commit.
        ///
        /// @param commit whether dependency and outer cancellation state permit keeping the installation
        @Override
        public void cleanupUnlessCommitted(boolean commit) {
            if (!commit) {
                cleanupCurrentState();
            }
        }

        /// Performs idempotent manifest and directory cleanup for the current ownership location.
        private void cleanupCurrentState() {
            @Nullable OwnedDirectory currentOwnership = ownership;
            if (currentOwnership == null) {
                return;
            }
            cleanupOwnedManifest(currentOwnership);
            cleanupOwnedDirectory(currentOwnership);
        }
    }

    /// Creates one unpredictable direct-child staging directory with a random ownership marker.
    ///
    /// Final paths are checked after the staging directory exists so a late collision is rejected before publication.
    ///
    /// @param platformRoot managed platform root
    /// @param targetDirectory final runtime directory
    /// @param manifestFile final runtime manifest
    /// @return immutable staging ownership proof
    /// @throws IOException when the root, final paths, staging directory, or marker is unsafe
    static OwnedDirectory createOwnedStagingDirectory(
            Path platformRoot,
            Path targetDirectory,
            Path manifestFile) throws IOException {
        Path normalizedRoot = normalizedPath(platformRoot, "platformRoot");
        Path normalizedTarget = normalizedPath(targetDirectory, "targetDirectory");
        Path normalizedManifest = normalizedPath(manifestFile, "manifestFile");
        requireDirectFinalChildren(normalizedRoot, normalizedTarget, normalizedManifest);
        requireExistingPathWithoutSymbolicLinks(normalizedRoot);
        Files.createDirectories(normalizedRoot);
        BasicFileAttributes rootAttributes = requireSafeDirectory(normalizedRoot);
        requireExistingPathWithoutSymbolicLinks(normalizedRoot);
        requireFinalPathsAbsent(normalizedTarget, normalizedManifest);

        Path stagingDirectory = Files.createTempDirectory(normalizedRoot, STAGING_DIRECTORY_PREFIX)
                .toAbsolutePath()
                .normalize();
        BasicFileAttributes stagingAttributes = Files.readAttributes(
                stagingDirectory,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!stagingAttributes.isDirectory() || stagingAttributes.isSymbolicLink()) {
            throw new IOException("Managed Java staging path is not a directory: " + stagingDirectory);
        }

        String token = UUID.randomUUID().toString();
        Path markerFile = stagingDirectory.resolve(INSTALL_OWNER_MARKER_PREFIX + token);
        @Nullable DirectoryIdentityLease directoryIdentityLease = null;
        @Nullable Path markerLeaseFile = null;
        @Nullable OwnedDirectory ownership = null;
        try {
            directoryIdentityLease = DirectoryIdentityLease.open(
                    stagingDirectory,
                    CapturedIdentity.capture(stagingAttributes));
            Files.writeString(
                    markerFile,
                    token,
                    StandardCharsets.US_ASCII,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            markerLeaseFile = createOwnershipLease(normalizedRoot, markerFile);
            BasicFileAttributes markerAttributes = Files.readAttributes(
                    markerFile,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!markerAttributes.isRegularFile() || markerAttributes.isSymbolicLink()) {
                throw new IOException("Managed Java staging marker is not a regular file: " + markerFile);
            }
            ownership = new OwnedDirectory(
                    normalizedRoot,
                    stagingDirectory,
                    normalizedTarget,
                    normalizedManifest,
                    markerFile,
                    markerLeaseFile,
                    token,
                    CapturedIdentity.capture(rootAttributes),
                    CapturedIdentity.capture(stagingAttributes),
                    CapturedIdentity.capture(markerAttributes),
                    directoryIdentityLease);
            if (!isSameSafePlatformRoot(ownership) || !isOwnedDirectory(ownership)) {
                throw new IOException("Managed Java staging ownership changed during creation");
            }
            return ownership;
        } catch (IOException | RuntimeException failure) {
            if (ownership != null) {
                cleanupOwnedDirectory(ownership);
            } else {
                cleanupIncompleteStaging(
                        normalizedRoot,
                        stagingDirectory,
                        markerFile,
                        markerLeaseFile,
                        directoryIdentityLease,
                        token,
                        rootAttributes.fileKey(),
                        stagingAttributes.fileKey(),
                        rootAttributes.creationTime(),
                        stagingAttributes.creationTime());
            }
            throw failure;
        }
    }

    /// Atomically moves an owned staging directory to its final path after checking both final names again.
    ///
    /// The move never follows or opens a final-path symbolic link. A raced replacement can at most make publication
    /// fail or be replaced as a directory entry; archive bytes are already complete in the staging directory.
    ///
    /// @param staging current staging ownership
    /// @return ownership proof relocated to the final directory
    /// @throws IOException when final state collides, ownership changes, or atomic movement is unavailable
    static OwnedDirectory moveOwnedDirectoryToFinal(OwnedDirectory staging) throws IOException {
        OwnedDirectory current = Objects.requireNonNull(staging, "staging");
        if (!isSameSafePlatformRoot(current) || !isOwnedDirectory(current)) {
            throw new IOException("Managed Java staging ownership changed before publication");
        }
        requireFinalPathsAbsent(current.finalDirectory(), current.manifestFile());

        moveAtomically(current.directory(), current.finalDirectory());
        OwnedDirectory published = current.atDirectory(current.finalDirectory());
        if (!isSameSafePlatformRoot(published) || !isOwnedDirectory(published)) {
            cleanupOwnedDirectory(published);
            throw new IOException("Managed Java directory ownership changed during publication");
        }
        return published;
    }

    /// Serializes one owned manifest to an unpredictable file and atomically publishes it beside the runtime.
    ///
    /// @param ownership published runtime ownership
    /// @param manifest staging installer result containing the matching ownership token
    /// @throws IOException when serialization, ownership validation, or atomic movement fails
    private static void writeOwnedManifest(
            OwnedDirectory ownership,
            JavaManifest manifest) throws IOException {
        if (!isSameSafePlatformRoot(ownership) || !isOwnedDirectory(ownership)) {
            throw new IOException("Managed Java directory ownership changed before manifest publication");
        }
        requireManifestToken(manifest, ownership.token());
        requireFinalManifestAbsent(ownership.manifestFile());

        String json = JsonUtils.GSON.toJson(manifest);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_MANIFEST_BYTES) {
            throw new IOException("Managed Java manifest exceeds its byte limit");
        }
        Path temporaryManifest = createManifestTemporaryFile(ownership.platformRoot(), json);
        boolean moved = false;
        try {
            requireFinalManifestAbsent(ownership.manifestFile());
            moveAtomically(temporaryManifest, ownership.manifestFile());
            moved = true;
            if (!isOwnedManifest(ownership, ownership.manifestFile())) {
                cleanupOwnedManifest(ownership);
                throw new IOException("Managed Java manifest ownership changed during publication");
            }
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporaryManifest);
            }
        }
    }

    /// Creates a managed runtime only after both published paths retain exact ownership.
    ///
    /// @param ownership published runtime ownership
    /// @param manifest verified staging result
    /// @return managed runtime rooted in the published directory
    /// @throws IOException when the executable or publication ownership is invalid
    private static JavaRuntime runtimeFromPublished(
            OwnedDirectory ownership,
            JavaManifest manifest) throws IOException {
        if (!isSameSafePlatformRoot(ownership)
                || !isOwnedDirectory(ownership)
                || !isOwnedManifest(ownership, ownership.manifestFile())) {
            throw new IOException("Managed Java publication ownership changed before commit");
        }
        Path realRoot = ownership.finalDirectory().toRealPath();
        Path binary = ownership.finalDirectory()
                .resolve("bin")
                .resolve(ownership.finalDirectory().getFileSystem().getPath(
                        manifest.info().getPlatform().getOperatingSystem().getJavaExecutable()))
                .toRealPath();
        if (!binary.startsWith(realRoot) || !Files.isRegularFile(binary)) {
            throw new IOException("Managed Java executable escapes its published directory: " + binary);
        }
        return JavaRuntime.of(binary, manifest.info(), true);
    }

    /// Creates an unpredictable hard-link lease to the original ownership marker.
    ///
    /// Keeping a second link outside staging prevents inode reuse after a directory replacement. Providers that do
    /// not support hard links cannot supply the proof required for safe rollback, so installation fails closed.
    ///
    /// @param platformRoot managed platform root
    /// @param markerFile newly created ownership marker
    /// @return new direct-child lease path linked to the marker
    /// @throws IOException when a lease cannot be created and verified
    private static Path createOwnershipLease(
            Path platformRoot,
            Path markerFile) throws IOException {
        for (int attempt = 0; attempt < 16; attempt++) {
            Path candidate = platformRoot.resolve(STAGING_LEASE_PREFIX + UUID.randomUUID());
            try {
                Files.createLink(candidate, markerFile);
            } catch (FileAlreadyExistsException ignored) {
                continue;
            } catch (SecurityException | UnsupportedOperationException failure) {
                throw new IOException("Managed Java staging requires hard-link ownership leases", failure);
            }

            boolean retained = false;
            try {
                BasicFileAttributes attributes = Files.readAttributes(
                        candidate,
                        BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isRegularFile()
                        || attributes.isSymbolicLink()
                        || !Files.isSameFile(markerFile, candidate)) {
                    throw new IOException("Managed Java ownership lease does not identify its marker");
                }
                retained = true;
                return candidate;
            } finally {
                if (!retained) {
                    Files.deleteIfExists(candidate);
                }
            }
        }
        throw new IOException("Unable to reserve a managed Java ownership lease");
    }

    /// Removes an ownership lease as the final non-commit operation after publication validation.
    ///
    /// @param ownership fully published runtime ownership
    /// @throws IOException when either linked path changed or atomic lease cleanup fails
    private static void releaseOwnershipLease(OwnedDirectory ownership) throws IOException {
        if (!isSameSafePlatformRoot(ownership) || !isOwnedDirectory(ownership)) {
            throw new IOException("Managed Java ownership changed before lease release");
        }
        Path leaseFile = ownership.markerLeaseFile();
        Path quarantine = leaseFile.resolveSibling(
                leaseFile.getFileName() + ".release-" + UUID.randomUUID());
        moveAtomically(leaseFile, quarantine);
        OwnedDirectory quarantined = ownership.atMarkerLeaseFile(quarantine);
        if (!isSameSafePlatformRoot(quarantined) || !isOwnedDirectory(quarantined)) {
            restoreQuarantinedPath(quarantine, leaseFile);
            throw new IOException("Managed Java ownership changed during lease release");
        }
        try {
            Files.delete(quarantine);
        } catch (IOException failure) {
            restoreQuarantinedPath(quarantine, leaseFile);
            throw failure;
        }
    }

    /// Creates one new manifest file without opening any pre-existing path.
    ///
    /// @param platformRoot managed platform root
    /// @param json serialized manifest
    /// @return newly written unpredictable manifest path
    /// @throws IOException when no new file can be created
    private static Path createManifestTemporaryFile(
            Path platformRoot,
            String json) throws IOException {
        for (int attempt = 0; attempt < 16; attempt++) {
            Path candidate = platformRoot.resolve(
                    STAGING_MANIFEST_PREFIX + UUID.randomUUID() + ".json");
            try {
                Files.writeString(
                        candidate,
                        json,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                BasicFileAttributes attributes = Files.readAttributes(
                        candidate,
                        BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                    Files.deleteIfExists(candidate);
                    throw new IOException("Managed Java temporary manifest is not a regular file");
                }
                return candidate;
            } catch (FileAlreadyExistsException ignored) {
                // Retry with another unpredictable name without touching the collided path.
            }
        }
        throw new IOException("Unable to reserve a managed Java temporary manifest");
    }

    /// Requires a manifest update map to contain the exact staging ownership token.
    ///
    /// @param manifest manifest to validate
    /// @param token expected token
    /// @throws IOException when ownership metadata is absent or different
    private static void requireManifestToken(
            JavaManifest manifest,
            String token) throws IOException {
        @Nullable Map<String, Object> update = manifest.update();
        if (update == null || !token.equals(update.get(INSTALL_OWNER_UPDATE_KEY))) {
            throw new IOException("Managed Java staging manifest lost its ownership token");
        }
    }

    /// Removes a final manifest only when its serialized update token still proves ownership.
    ///
    /// @param ownership current directory ownership
    static void cleanupOwnedManifest(OwnedDirectory ownership) {
        Path manifestFile = ownership.manifestFile();
        if (!Files.exists(manifestFile, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            if (!isManifestOwnershipCandidate(manifestFile)) {
                LOG.warning("Leaving unowned Java manifest during rollback: " + manifestFile);
                return;
            }
            Path quarantine = manifestFile.resolveSibling(
                    manifestFile.getFileName() + ".rollback-" + UUID.randomUUID());
            moveAtomically(manifestFile, quarantine);
            if (isOwnedManifest(ownership, quarantine)) {
                Files.deleteIfExists(quarantine);
            } else {
                restoreQuarantinedPath(quarantine, manifestFile);
                LOG.warning("Leaving replaced Java manifest during rollback: " + manifestFile);
            }
        } catch (IOException failure) {
            LOG.warning("Failed to roll back Java runtime manifest " + manifestFile, failure);
        }
    }

    /// Removes a staging or final directory only after atomic quarantine and repeated ownership validation.
    ///
    /// @param ownership current directory ownership
    static void cleanupOwnedDirectory(OwnedDirectory ownership) {
        Path directory = ownership.directory();
        try {
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            if (!isOwnedDirectory(ownership)) {
                LOG.warning("Leaving unowned Java directory during rollback: " + directory);
                return;
            }
            Path quarantine = directory.resolveSibling(
                    directory.getFileName() + ".rollback-" + UUID.randomUUID());
            moveAtomically(directory, quarantine);
            OwnedDirectory quarantined = ownership.atDirectory(quarantine);
            if (isOwnedDirectory(quarantined)) {
                FileUtils.deleteDirectory(quarantine);
                cleanupDetachedOwnershipLease(quarantined);
            } else {
                restoreQuarantinedPath(quarantine, directory);
                LOG.warning("Leaving replaced Java directory during rollback: " + directory);
            }
        } catch (IOException failure) {
            LOG.warning("Failed to roll back Java runtime directory " + directory, failure);
        } finally {
            ownership.directoryIdentityLease().close();
        }
    }

    /// Removes a detached lease only after its captured inode and token remain stable through atomic quarantine.
    ///
    /// @param ownership ownership whose directory marker was just deleted
    private static void cleanupDetachedOwnershipLease(OwnedDirectory ownership) {
        Path leaseFile = ownership.markerLeaseFile();
        if (!Files.exists(leaseFile, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            if (!isOwnedLeaseFile(ownership, leaseFile)) {
                LOG.warning("Leaving unowned Java ownership lease during rollback: " + leaseFile);
                return;
            }
            Path quarantine = leaseFile.resolveSibling(
                    leaseFile.getFileName() + ".rollback-" + UUID.randomUUID());
            moveAtomically(leaseFile, quarantine);
            if (isOwnedLeaseFile(ownership, quarantine)) {
                Files.deleteIfExists(quarantine);
            } else {
                restoreQuarantinedPath(quarantine, leaseFile);
                LOG.warning("Leaving replaced Java ownership lease during rollback: " + leaseFile);
            }
        } catch (IOException failure) {
            LOG.warning("Failed to roll back Java ownership lease " + leaseFile, failure);
        }
    }

    /// Checks one detached lease against the captured marker identity and random token.
    ///
    /// @param ownership captured ownership proof
    /// @param leaseFile current or quarantined lease path
    /// @return whether the lease still identifies the task-created marker inode
    private static boolean isOwnedLeaseFile(
            OwnedDirectory ownership,
            Path leaseFile) {
        try {
            BasicFileAttributes before = Files.readAttributes(
                    leaseFile,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile()
                    || before.isSymbolicLink()
                    || before.size() != ownership.token().length()
                    || !ownership.markerIdentity().matches(before)) {
                return false;
            }
            String token = Files.readString(leaseFile, StandardCharsets.US_ASCII);
            BasicFileAttributes after = Files.readAttributes(
                    leaseFile,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            return ownership.token().equals(token)
                    && sameStableIdentity(before, after)
                    && ownership.markerIdentity().matches(after);
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    /// Requires the platform root, staging directory, and random marker to retain their captured ownership.
    ///
    /// This package-private boundary is called immediately around every extraction mutation.
    ///
    /// @param ownership current staging ownership proof
    /// @throws IOException when the managed root or staging directory was replaced or linked
    static void requireOwnedExtractionDirectory(OwnedDirectory ownership) throws IOException {
        OwnedDirectory current = Objects.requireNonNull(ownership, "ownership");
        if (!isSameSafePlatformRoot(current) || !isOwnedDirectory(current)) {
            throw new IOException("Managed Java staging ownership changed during extraction");
        }
    }

    /// Cleans a staging directory whose ownership reservation did not complete.
    ///
    /// Recursive cleanup requires a verified hard-link lease. Without one, only an empty marker-free directory can
    /// be removed; ambiguous contents are deliberately retained.
    ///
    /// @param platformRoot captured platform root
    /// @param stagingDirectory newly created staging directory
    /// @param markerFile attempted marker path
    /// @param markerLeaseFile created marker lease, or null when lease creation failed
    /// @param directoryIdentityLease live directory lease, or null when opening it failed
    /// @param token attempted marker token
    /// @param rootFileKey captured root key
    /// @param stagingFileKey captured staging key
    /// @param rootCreationTime captured root creation timestamp
    /// @param stagingCreationTime captured staging creation timestamp
    private static void cleanupIncompleteStaging(
            Path platformRoot,
            Path stagingDirectory,
            Path markerFile,
            @Nullable Path markerLeaseFile,
            @Nullable DirectoryIdentityLease directoryIdentityLease,
            String token,
            @Nullable Object rootFileKey,
            @Nullable Object stagingFileKey,
            FileTime rootCreationTime,
            FileTime stagingCreationTime) {
        try {
            if (markerLeaseFile != null
                    && Files.exists(markerFile, LinkOption.NOFOLLOW_LINKS)
                    && Files.exists(markerLeaseFile, LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes markerAttributes = Files.readAttributes(
                        markerFile,
                        BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                OwnedDirectory ownership = new OwnedDirectory(
                        platformRoot,
                        stagingDirectory,
                        stagingDirectory,
                        platformRoot.resolve(STAGING_MANIFEST_PREFIX + "incomplete"),
                        markerFile,
                        markerLeaseFile,
                        token,
                        new CapturedIdentity(rootFileKey, rootCreationTime),
                        new CapturedIdentity(stagingFileKey, stagingCreationTime),
                        CapturedIdentity.capture(markerAttributes),
                        directoryIdentityLease == null
                                ? DirectoryIdentityLease.unavailable(
                                        new CapturedIdentity(stagingFileKey, stagingCreationTime))
                                : directoryIdentityLease);
                cleanupOwnedDirectory(ownership);
                return;
            }
            if (Files.exists(markerFile, LinkOption.NOFOLLOW_LINKS)) {
                LOG.warning("Leaving Java staging without a verifiable ownership lease: " + stagingDirectory);
                return;
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    stagingDirectory,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory()
                    || attributes.isSymbolicLink()
                    || !matchesCapturedIdentity(
                            stagingFileKey,
                            stagingCreationTime,
                            attributes)) {
                return;
            }
            Files.delete(stagingDirectory);
        } catch (IOException failure) {
            LOG.warning("Failed to clean incomplete Java staging directory " + stagingDirectory, failure);
        } finally {
            if (directoryIdentityLease != null) {
                directoryIdentityLease.close();
            }
        }
    }

    /// Checks directory and marker identity plus the stable random token.
    ///
    /// @param ownership current directory ownership
    /// @return whether the directory still belongs to this task
    private static boolean isOwnedDirectory(OwnedDirectory ownership) {
        Path markerFile = ownership.markerFile();
        Path markerLeaseFile = ownership.markerLeaseFile();
        try {
            if (!ownership.directoryIdentityLease().supports(ownership.directoryIdentity())) {
                return false;
            }
            BasicFileAttributes directoryBefore = Files.readAttributes(
                    ownership.directory(),
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            BasicFileAttributes markerBefore = Files.readAttributes(
                    markerFile,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            BasicFileAttributes markerLeaseBefore = Files.readAttributes(
                    markerLeaseFile,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!directoryBefore.isDirectory()
                    || directoryBefore.isSymbolicLink()
                    || !markerBefore.isRegularFile()
                    || markerBefore.isSymbolicLink()
                    || !markerLeaseBefore.isRegularFile()
                    || markerLeaseBefore.isSymbolicLink()
                    || markerBefore.size() != ownership.token().length()
                    || markerLeaseBefore.size() != ownership.token().length()
                    || !ownership.directoryIdentity().matches(directoryBefore)
                    || !ownership.markerIdentity().matches(markerBefore)
                    || !ownership.markerIdentity().matches(markerLeaseBefore)
                    || !Files.isSameFile(markerFile, markerLeaseFile)) {
                return false;
            }
            String markerToken = Files.readString(markerFile, StandardCharsets.US_ASCII);
            BasicFileAttributes markerAfter = Files.readAttributes(
                    markerFile,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            BasicFileAttributes markerLeaseAfter = Files.readAttributes(
                    markerLeaseFile,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            BasicFileAttributes directoryAfter = Files.readAttributes(
                    ownership.directory(),
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            return ownership.token().equals(markerToken)
                    && sameStableIdentity(markerBefore, markerAfter)
                    && sameStableIdentity(markerLeaseBefore, markerLeaseAfter)
                    && Files.isSameFile(markerFile, markerLeaseFile)
                    && directoryAfter.isDirectory()
                    && ownership.directoryIdentity().matches(directoryAfter);
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    /// Checks a bounded regular manifest's stable identity and embedded ownership token.
    ///
    /// @param ownership current directory ownership
    /// @param manifestFile current or quarantined manifest path
    /// @return whether the manifest belongs to this task
    private static boolean isOwnedManifest(
            OwnedDirectory ownership,
            Path manifestFile) {
        try {
            BasicFileAttributes before = Files.readAttributes(
                    manifestFile,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile()
                    || before.isSymbolicLink()
                    || before.size() > MAXIMUM_MANIFEST_BYTES) {
                return false;
            }
            @Nullable JavaManifest manifest = JsonUtils.fromJsonFile(manifestFile, JavaManifest.class);
            BasicFileAttributes after = Files.readAttributes(
                    manifestFile,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            @Nullable Map<String, Object> update = manifest == null ? null : manifest.update();
            return sameStableIdentity(before, after)
                    && update != null
                    && ownership.token().equals(update.get(INSTALL_OWNER_UPDATE_KEY));
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    /// Checks whether a manifest is a bounded regular file before atomic quarantine.
    ///
    /// @param manifestFile manifest path
    /// @return whether the path is eligible for token validation
    private static boolean isManifestOwnershipCandidate(Path manifestFile) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    manifestFile,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            return attributes.isRegularFile()
                    && !attributes.isSymbolicLink()
                    && attributes.size() <= MAXIMUM_MANIFEST_BYTES;
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    /// Revalidates the captured platform root and every existing ancestor without following links.
    ///
    /// @param ownership current ownership proof
    /// @return whether the same safe platform root remains in place
    private static boolean isSameSafePlatformRoot(OwnedDirectory ownership) {
        try {
            requireExistingPathWithoutSymbolicLinks(ownership.platformRoot());
            BasicFileAttributes attributes = requireSafeDirectory(ownership.platformRoot());
            return ownership.platformRootIdentity().matches(attributes);
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    /// Rejects every existing symbolic-link or non-directory segment leading to one root.
    ///
    /// @param directory directory path
    /// @throws IOException when an existing segment is unsafe
    private static void requireExistingPathWithoutSymbolicLinks(Path directory) throws IOException {
        Path absoluteDirectory = directory.toAbsolutePath().normalize();
        @Nullable Path current = absoluteDirectory.getRoot();
        for (Path segment : absoluteDirectory) {
            current = current == null ? segment : current.resolve(segment);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    current,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
                throw new IOException("Managed Java platform path contains an unsafe segment: " + current);
            }
        }
    }

    /// Reads one platform root without following a symbolic link and requires a directory.
    ///
    /// @param directory managed platform root
    /// @return stable directory attributes
    /// @throws IOException when the root is absent, linked, or not a directory
    private static BasicFileAttributes requireSafeDirectory(Path directory) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                directory,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
            throw new IOException("Managed Java platform root is not a safe directory: " + directory);
        }
        return attributes;
    }

    /// Requires final runtime and manifest paths to be absent without following dangling links.
    ///
    /// @param targetDirectory final runtime directory
    /// @param manifestFile final runtime manifest
    /// @throws FileAlreadyExistsException when either directory entry already exists
    private static void requireFinalPathsAbsent(
            Path targetDirectory,
            Path manifestFile) throws FileAlreadyExistsException {
        if (Files.exists(targetDirectory, LinkOption.NOFOLLOW_LINKS)
                || Files.exists(manifestFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(targetDirectory.getFileName().toString());
        }
    }

    /// Requires the final manifest directory entry to be absent without following dangling links.
    ///
    /// @param manifestFile final runtime manifest
    /// @throws FileAlreadyExistsException when the entry already exists
    private static void requireFinalManifestAbsent(Path manifestFile)
            throws FileAlreadyExistsException {
        if (Files.exists(manifestFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(manifestFile.toString());
        }
    }

    /// Requires both final paths to be direct children of the same managed platform root.
    ///
    /// @param platformRoot managed platform root
    /// @param targetDirectory final runtime directory
    /// @param manifestFile final runtime manifest
    /// @throws IOException when a path escapes or nests below the root
    private static void requireDirectFinalChildren(
            Path platformRoot,
            Path targetDirectory,
            Path manifestFile) throws IOException {
        if (!platformRoot.equals(targetDirectory.getParent())
                || !platformRoot.equals(manifestFile.getParent())) {
            throw new IOException("Managed Java targets must be direct platform-root children");
        }
    }

    /// Atomically moves one same-filesystem path without requesting replacement behavior.
    ///
    /// @param source current path
    /// @param target destination path
    /// @throws IOException when atomic movement is unavailable or fails
    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException failure) {
            throw new IOException("Atomic Java publication is unavailable for " + source, failure);
        }
    }

    /// Restores an unowned quarantined entry only while its original path remains absent.
    ///
    /// @param quarantine quarantined path
    /// @param original original path
    private static void restoreQuarantinedPath(Path quarantine, Path original) {
        if (Files.exists(original, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            moveAtomically(quarantine, original);
        } catch (IOException failure) {
            LOG.warning("Failed to restore unowned quarantined path " + quarantine, failure);
        }
    }

    /// Normalizes and rejects an absent path.
    ///
    /// @param path input path
    /// @param name diagnostic parameter name
    /// @return absolute normalized path
    private static Path normalizedPath(Path path, String name) {
        return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
    }

    /// Compares a captured file key when supplied, otherwise defers to token and content proof.
    ///
    /// @param expected captured file key, or null when unsupported
    /// @param actual current file key, or null when unsupported
    /// @return whether the known key still matches
    private static boolean matchesKnownFileKey(
            @Nullable Object expected,
            @Nullable Object actual) {
        return expected == null || expected.equals(actual);
    }

    /// Compares one captured provider identity with a creation-time fallback for filesystems without file keys.
    ///
    /// @param expectedFileKey captured provider file key, or null when unsupported
    /// @param expectedCreationTime captured creation timestamp
    /// @param actual current no-follow attributes
    /// @return whether the same filesystem entry remains at the path
    private static boolean matchesCapturedIdentity(
            @Nullable Object expectedFileKey,
            FileTime expectedCreationTime,
            BasicFileAttributes actual) {
        return expectedFileKey != null
                ? expectedFileKey.equals(actual.fileKey())
                : expectedCreationTime.equals(actual.creationTime());
    }

    /// Compares stable regular-file identity fields around one ownership read.
    ///
    /// @param before attributes before reading contents
    /// @param after attributes after reading contents
    /// @return whether the same file remained in place
    private static boolean sameStableIdentity(
            BasicFileAttributes before,
            BasicFileAttributes after) {
        return before.isRegularFile()
                && after.isRegularFile()
                && before.size() == after.size()
                && before.lastModifiedTime().equals(after.lastModifiedTime())
                && matchesKnownFileKey(before.fileKey(), after.fileKey());
    }

    /// Keeps a directory inode allocated while Unix providers expose reusable file keys.
    ///
    /// Windows does not need the handle because its creation timestamp distinguishes replacements, and an open
    /// directory stream would prevent the atomic publication move there.
    @NotNullByDefault
    private static final class DirectoryIdentityLease {
        /// Whether the current provider requires an open stream to make its file key non-reusable.
        private final boolean required;

        /// Live directory stream retaining the inode, or null when no stream is required or opening failed.
        private @Nullable DirectoryStream<Path> directoryStream;

        /// Creates one directory identity lease in its current availability state.
        ///
        /// @param required whether an open stream is required
        /// @param directoryStream live stream, or null when unavailable
        private DirectoryIdentityLease(
                boolean required,
                @Nullable DirectoryStream<Path> directoryStream) {
            this.required = required;
            this.directoryStream = directoryStream;
        }

        /// Opens a directory stream when required to prevent provider file-key reuse.
        ///
        /// @param directory newly created staging directory
        /// @param identity captured directory identity
        /// @return live or explicitly unnecessary identity lease
        /// @throws IOException when a required stream cannot be opened
        private static DirectoryIdentityLease open(
                Path directory,
                CapturedIdentity identity) throws IOException {
            if (!requiresOpenStream(identity)) {
                return new DirectoryIdentityLease(false, null);
            }
            return new DirectoryIdentityLease(true, Files.newDirectoryStream(directory));
        }

        /// Creates an unavailable lease for conservative incomplete-staging cleanup.
        ///
        /// @param identity captured directory identity
        /// @return lease that rejects ownership when an open stream was required
        private static DirectoryIdentityLease unavailable(CapturedIdentity identity) {
            return new DirectoryIdentityLease(requiresOpenStream(identity), null);
        }

        /// Checks whether this live state can support the captured provider identity.
        ///
        /// @param identity captured directory identity
        /// @return whether required inode retention remains active
        private synchronized boolean supports(CapturedIdentity identity) {
            boolean currentlyRequired = requiresOpenStream(identity);
            return currentlyRequired == required
                    && (!required || directoryStream != null);
        }

        /// Closes an active directory stream, making later recursive cleanup fail closed.
        private synchronized void close() {
            @Nullable DirectoryStream<Path> currentStream = directoryStream;
            directoryStream = null;
            if (currentStream == null) {
                return;
            }
            try {
                currentStream.close();
            } catch (IOException failure) {
                LOG.warning("Failed to close managed Java directory identity lease", failure);
            }
        }

        /// Determines whether the current operating system needs inode retention for this identity.
        ///
        /// @param identity captured directory identity
        /// @return whether a live directory stream is required
        private static boolean requiresOpenStream(CapturedIdentity identity) {
            return identity.fileKey() != null
                    && OperatingSystem.CURRENT_OS != OperatingSystem.WINDOWS;
        }
    }

    /// Stable filesystem identity captured before an owned path is exposed to later task phases.
    ///
    /// @param fileKey provider file key, or null when unsupported
    /// @param creationTime creation timestamp used when the provider supplies no file key
    @NotNullByDefault
    private record CapturedIdentity(
            @Nullable Object fileKey,
            FileTime creationTime) {
        /// Requires a non-null creation timestamp for the cross-platform fallback.
        private CapturedIdentity {
            creationTime = Objects.requireNonNull(creationTime, "creationTime");
        }

        /// Captures identity from no-follow attributes.
        ///
        /// @param attributes current safe entry attributes
        /// @return immutable identity
        private static CapturedIdentity capture(BasicFileAttributes attributes) {
            return new CapturedIdentity(attributes.fileKey(), attributes.creationTime());
        }

        /// Compares provider identity, falling back to creation time when file keys are unavailable.
        ///
        /// @param attributes current no-follow attributes
        /// @return whether the captured identity still matches
        private boolean matches(BasicFileAttributes attributes) {
            return matchesCapturedIdentity(fileKey, creationTime, attributes);
        }
    }

    /// Immutable ownership proof for a staging, published, or quarantined runtime directory.
    ///
    /// @param platformRoot managed platform root
    /// @param directory current owned directory path
    /// @param finalDirectory final runtime directory
    /// @param manifestFile final runtime manifest
    /// @param markerFile marker below the current directory
    /// @param markerLeaseFile hard-link lease below the platform root
    /// @param token random marker and manifest token
    /// @param platformRootIdentity captured platform-root identity
    /// @param directoryIdentity captured directory identity
    /// @param markerIdentity captured marker and lease inode identity
    /// @param directoryIdentityLease live directory inode-retention lease when required by the provider
    @NotNullByDefault
    static record OwnedDirectory(
            Path platformRoot,
            Path directory,
            Path finalDirectory,
            Path manifestFile,
            Path markerFile,
            Path markerLeaseFile,
            String token,
            CapturedIdentity platformRootIdentity,
            CapturedIdentity directoryIdentity,
            CapturedIdentity markerIdentity,
            DirectoryIdentityLease directoryIdentityLease) {
        /// Normalizes paths and requires direct final children plus marker and lease containment.
        OwnedDirectory {
            platformRoot = normalizedPath(platformRoot, "platformRoot");
            directory = normalizedPath(directory, "directory");
            finalDirectory = normalizedPath(finalDirectory, "finalDirectory");
            manifestFile = normalizedPath(manifestFile, "manifestFile");
            markerFile = normalizedPath(markerFile, "markerFile");
            markerLeaseFile = normalizedPath(markerLeaseFile, "markerLeaseFile");
            token = Objects.requireNonNull(token, "token");
            platformRootIdentity = Objects.requireNonNull(platformRootIdentity, "platformRootIdentity");
            directoryIdentity = Objects.requireNonNull(directoryIdentity, "directoryIdentity");
            markerIdentity = Objects.requireNonNull(markerIdentity, "markerIdentity");
            directoryIdentityLease = Objects.requireNonNull(directoryIdentityLease, "directoryIdentityLease");
            if (token.isEmpty()
                    || !platformRoot.equals(finalDirectory.getParent())
                    || !platformRoot.equals(manifestFile.getParent())
                    || !directory.equals(markerFile.getParent())
                    || !platformRoot.equals(markerLeaseFile.getParent())
                    || markerFile.equals(markerLeaseFile)) {
                throw new IllegalArgumentException("Invalid managed Java ownership proof");
            }
        }

        /// Relocates the directory and marker paths while retaining captured file identity.
        ///
        /// @param replacement replacement current directory
        /// @return relocated ownership proof
        OwnedDirectory atDirectory(Path replacement) {
            Path normalizedReplacement = normalizedPath(replacement, "replacement");
            return new OwnedDirectory(
                    platformRoot,
                    normalizedReplacement,
                    finalDirectory,
                    manifestFile,
                    normalizedReplacement.resolve(markerFile.getFileName()),
                    markerLeaseFile,
                    token,
                    platformRootIdentity,
                    directoryIdentity,
                    markerIdentity,
                    directoryIdentityLease);
        }

        /// Relocates the lease path while retaining captured marker identity.
        ///
        /// @param replacement replacement current lease file
        /// @return ownership proof using the relocated lease
        OwnedDirectory atMarkerLeaseFile(Path replacement) {
            return new OwnedDirectory(
                    platformRoot,
                    directory,
                    finalDirectory,
                    manifestFile,
                    markerFile,
                    replacement,
                    token,
                    platformRootIdentity,
                    directoryIdentity,
                    markerIdentity,
                    directoryIdentityLease);
        }
    }
}
