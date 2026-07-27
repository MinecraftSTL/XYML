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
import space.minecraftstl.xyml.util.platform.Platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
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
/// deletion and proceeds only while the captured directory identity and random ownership token still match.
@NotNullByDefault
final class JavaRuntimeInstallationPublisher {
    /// Manifest update key carrying the random ownership token through the legacy installer result.
    private static final String INSTALL_OWNER_UPDATE_KEY = "xyml.acquisitionOwner";

    /// Prefix for the ownership marker stored inside a staging or published runtime directory.
    private static final String INSTALL_OWNER_MARKER_PREFIX = ".xyml-install-owner-";

    /// Prefix for unpredictable staging directories created below the managed platform root.
    private static final String STAGING_DIRECTORY_PREFIX = ".xyml-java-stage-";

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
                setResult(runtimeFromPublished(published, stagedManifest));
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
        try {
            Files.writeString(
                    markerFile,
                    token,
                    StandardCharsets.US_ASCII,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            BasicFileAttributes markerAttributes = Files.readAttributes(
                    markerFile,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!markerAttributes.isRegularFile() || markerAttributes.isSymbolicLink()) {
                throw new IOException("Managed Java staging marker is not a regular file: " + markerFile);
            }
            OwnedDirectory ownership = new OwnedDirectory(
                    normalizedRoot,
                    stagingDirectory,
                    normalizedTarget,
                    normalizedManifest,
                    markerFile,
                    token,
                    rootAttributes.fileKey(),
                    stagingAttributes.fileKey(),
                    markerAttributes.fileKey(),
                    rootAttributes.creationTime(),
                    stagingAttributes.creationTime(),
                    markerAttributes.creationTime());
            if (!isSameSafePlatformRoot(ownership) || !isOwnedDirectory(ownership)) {
                throw new IOException("Managed Java staging ownership changed during creation");
            }
            return ownership;
        } catch (IOException | RuntimeException failure) {
            cleanupIncompleteStaging(
                    normalizedRoot,
                    stagingDirectory,
                    markerFile,
                    token,
                    rootAttributes.fileKey(),
                    stagingAttributes.fileKey(),
                    rootAttributes.creationTime(),
                    stagingAttributes.creationTime());
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
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
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
            } else {
                restoreQuarantinedPath(quarantine, directory);
                LOG.warning("Leaving replaced Java directory during rollback: " + directory);
            }
        } catch (IOException failure) {
            LOG.warning("Failed to roll back Java runtime directory " + directory, failure);
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

    /// Cleans a staging directory whose marker creation did not complete, using captured file keys only.
    ///
    /// @param platformRoot captured platform root
    /// @param stagingDirectory newly created staging directory
    /// @param markerFile attempted marker path
    /// @param token attempted marker token
    /// @param rootFileKey captured root key
    /// @param stagingFileKey captured staging key
    /// @param rootCreationTime captured root creation timestamp
    /// @param stagingCreationTime captured staging creation timestamp
    private static void cleanupIncompleteStaging(
            Path platformRoot,
            Path stagingDirectory,
            Path markerFile,
            String token,
            @Nullable Object rootFileKey,
            @Nullable Object stagingFileKey,
            FileTime rootCreationTime,
            FileTime stagingCreationTime) {
        try {
            if (Files.exists(markerFile, LinkOption.NOFOLLOW_LINKS)) {
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
                        token,
                        rootFileKey,
                        stagingFileKey,
                        markerAttributes.fileKey(),
                        rootCreationTime,
                        stagingCreationTime,
                        markerAttributes.creationTime());
                cleanupOwnedDirectory(ownership);
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
            Path quarantine = stagingDirectory.resolveSibling(
                    stagingDirectory.getFileName() + ".rollback-" + UUID.randomUUID());
            moveAtomically(stagingDirectory, quarantine);
            BasicFileAttributes quarantinedAttributes = Files.readAttributes(
                    quarantine,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (quarantinedAttributes.isDirectory()
                    && !quarantinedAttributes.isSymbolicLink()
                    && matchesCapturedIdentity(
                            stagingFileKey,
                            stagingCreationTime,
                            quarantinedAttributes)) {
                FileUtils.deleteDirectory(quarantine);
            } else {
                restoreQuarantinedPath(quarantine, stagingDirectory);
            }
        } catch (IOException failure) {
            LOG.warning("Failed to clean incomplete Java staging directory " + stagingDirectory, failure);
        }
    }

    /// Checks directory and marker identity plus the stable random token.
    ///
    /// @param ownership current directory ownership
    /// @return whether the directory still belongs to this task
    private static boolean isOwnedDirectory(OwnedDirectory ownership) {
        Path markerFile = ownership.markerFile();
        try {
            BasicFileAttributes directoryBefore = Files.readAttributes(
                    ownership.directory(),
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            BasicFileAttributes markerBefore = Files.readAttributes(
                    markerFile,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!directoryBefore.isDirectory()
                    || directoryBefore.isSymbolicLink()
                    || !markerBefore.isRegularFile()
                    || markerBefore.isSymbolicLink()
                    || markerBefore.size() != ownership.token().length()
                    || !matchesCapturedIdentity(
                            ownership.directoryFileKey(),
                            ownership.directoryCreationTime(),
                            directoryBefore)
                    || !matchesCapturedIdentity(
                            ownership.markerFileKey(),
                            ownership.markerCreationTime(),
                            markerBefore)) {
                return false;
            }
            String markerToken = Files.readString(markerFile, StandardCharsets.US_ASCII);
            BasicFileAttributes markerAfter = Files.readAttributes(
                    markerFile,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            BasicFileAttributes directoryAfter = Files.readAttributes(
                    ownership.directory(),
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            return ownership.token().equals(markerToken)
                    && sameStableIdentity(markerBefore, markerAfter)
                    && directoryAfter.isDirectory()
                    && matchesCapturedIdentity(
                            ownership.directoryFileKey(),
                            ownership.directoryCreationTime(),
                            directoryAfter);
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
            return matchesCapturedIdentity(
                    ownership.platformRootFileKey(),
                    ownership.platformRootCreationTime(),
                    attributes);
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

    /// Immutable ownership proof for a staging, published, or quarantined runtime directory.
    ///
    /// @param platformRoot managed platform root
    /// @param directory current owned directory path
    /// @param finalDirectory final runtime directory
    /// @param manifestFile final runtime manifest
    /// @param markerFile marker below the current directory
    /// @param token random marker and manifest token
    /// @param platformRootFileKey captured root file key, or null when unsupported
    /// @param directoryFileKey captured directory file key, or null when unsupported
    /// @param markerFileKey captured marker file key, or null when unsupported
    /// @param platformRootCreationTime captured root creation timestamp
    /// @param directoryCreationTime captured directory creation timestamp
    /// @param markerCreationTime captured marker creation timestamp
    @NotNullByDefault
    static record OwnedDirectory(
            Path platformRoot,
            Path directory,
            Path finalDirectory,
            Path manifestFile,
            Path markerFile,
            String token,
            @Nullable Object platformRootFileKey,
            @Nullable Object directoryFileKey,
            @Nullable Object markerFileKey,
            FileTime platformRootCreationTime,
            FileTime directoryCreationTime,
            FileTime markerCreationTime) {
        /// Normalizes paths and requires direct final children and a marker below the current directory.
        OwnedDirectory {
            platformRoot = normalizedPath(platformRoot, "platformRoot");
            directory = normalizedPath(directory, "directory");
            finalDirectory = normalizedPath(finalDirectory, "finalDirectory");
            manifestFile = normalizedPath(manifestFile, "manifestFile");
            markerFile = normalizedPath(markerFile, "markerFile");
            token = Objects.requireNonNull(token, "token");
            platformRootCreationTime = Objects.requireNonNull(
                    platformRootCreationTime,
                    "platformRootCreationTime");
            directoryCreationTime = Objects.requireNonNull(
                    directoryCreationTime,
                    "directoryCreationTime");
            markerCreationTime = Objects.requireNonNull(
                    markerCreationTime,
                    "markerCreationTime");
            if (token.isEmpty()
                    || !platformRoot.equals(finalDirectory.getParent())
                    || !platformRoot.equals(manifestFile.getParent())
                    || !directory.equals(markerFile.getParent())) {
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
                    token,
                    platformRootFileKey,
                    directoryFileKey,
                    markerFileKey,
                    platformRootCreationTime,
                    directoryCreationTime,
                    markerCreationTime);
        }
    }
}
