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
import kala.compress.archivers.tar.TarArchiveOutputStream;
import kala.compress.archivers.zip.UnixStat;
import kala.compress.archivers.zip.ZipArchiveEntry;
import kala.compress.archivers.zip.ZipArchiveOutputStream;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.game.GameJavaVersion;
import space.minecraftstl.xyml.java.JavaInfo;
import space.minecraftstl.xyml.java.JavaManifest;
import space.minecraftstl.xyml.java.JavaRuntime;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskExecutor;
import space.minecraftstl.xyml.util.platform.OperatingSystem;
import space.minecraftstl.xyml.util.platform.Platform;
import space.minecraftstl.xyml.util.platform.UnsupportedPlatformException;
import space.minecraftstl.xyml.util.tree.ArchiveFileTree;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import static space.minecraftstl.xyml.util.gson.JsonUtils.fromJsonFile;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Verifies lazy Java acquisition, strict managed paths, safe archive normalization, and cleanup behavior.
@NotNullByDefault
final class JavaManagerRuntimeAcquisitionServiceTest {
    /// Canonical deterministic SHA-256 used by fake verified inspections.
    private static final String TEST_SHA256 = "0".repeat(64);

    /// Per-test directory used for real ZIP inspection and normalization fixtures.
    @TempDir
    private @Nullable Path temporaryDirectory;

    /// Keeps capability and installation-marker reads stopped until the caller starts the snapshot task.
    @Test
    void loadsMojangInstallationStateOnlyAfterTaskStarts() {
        FakeBackend backend = new FakeBackend();
        backend.supportedVersions.add(GameJavaVersion.JAVA_17);
        backend.supportedVersions.add(GameJavaVersion.JAVA_21);
        backend.installedMojangVersions.add(GameJavaVersion.JAVA_17);
        JavaManagerRuntimeAcquisitionService service = new JavaManagerRuntimeAcquisitionService(backend);

        Task<JavaRuntimeAcquisitionSnapshot> task = service.loadSnapshot();

        assertAll(
                () -> assertEquals(Task.TaskState.READY, task.getState()),
                () -> assertEquals(0, backend.currentPlatformReads.get()),
                () -> assertEquals(0, backend.mojangPresenceChecks.get()),
                () -> assertEquals(0, backend.downloadTaskRequests.get()));
        assertTrue(task.test());
        JavaRuntimeAcquisitionSnapshot snapshot = Objects.requireNonNull(task.getResult(), "snapshot result");
        assertAll(
                () -> assertEquals(Platform.SYSTEM_PLATFORM, snapshot.platform()),
                () -> assertEquals(
                        List.of(
                                new MojangJavaRuntimeOption(GameJavaVersion.JAVA_17, true),
                                new MojangJavaRuntimeOption(GameJavaVersion.JAVA_21, false)),
                        snapshot.mojangRuntimes()),
                () -> assertThrows(
                        UnsupportedOperationException.class,
                        () -> snapshot.mojangRuntimes().add(
                                new MojangJavaRuntimeOption(GameJavaVersion.JAVA_25, false))),
                () -> assertEquals(1, backend.currentPlatformReads.get()),
                () -> assertEquals(2, backend.mojangPresenceChecks.get()),
                () -> assertEquals(0, backend.downloadTaskRequests.get()));
    }

    /// Treats Mojang component plus major version as identity despite major-only GameJavaVersion equality.
    @Test
    void snapshotsDistinguishComponentsSharingOneMajorVersion() {
        MojangJavaRuntimeOption alpha = new MojangJavaRuntimeOption(
                new GameJavaVersion("component-alpha", 21),
                false);
        MojangJavaRuntimeOption beta = new MojangJavaRuntimeOption(
                new GameJavaVersion("component-beta", 21),
                true);

        JavaRuntimeAcquisitionSnapshot snapshot = new JavaRuntimeAcquisitionSnapshot(
                Platform.SYSTEM_PLATFORM,
                List.of(alpha, beta));

        assertAll(
                () -> assertEquals(2, snapshot.mojangRuntimes().size()),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new JavaRuntimeAcquisitionSnapshot(
                                Platform.SYSTEM_PLATFORM,
                                List.of(alpha, new MojangJavaRuntimeOption(alpha.version(), true)))));
    }

    /// Restricts Mojang downloads to the exact system platform and exact canonical component identifier.
    @Test
    void downloadsOnlyCanonicalSystemPlatformMojangRuntimeAfterStartup() {
        FakeBackend backend = new FakeBackend();
        backend.supportedVersions.add(GameJavaVersion.JAVA_17);
        JavaManagerRuntimeAcquisitionService service = new JavaManagerRuntimeAcquisitionService(backend);

        Task<JavaRuntime> validTask = service.downloadMojangRuntime(GameJavaVersion.JAVA_17);
        Task<JavaRuntime> forgedTask = service.downloadMojangRuntime(
                new GameJavaVersion("forged-runtime", 17));

        assertAll(
                () -> assertEquals(Task.TaskState.READY, validTask.getState()),
                () -> assertEquals(Task.TaskState.READY, forgedTask.getState()),
                () -> assertEquals(0, backend.downloadTaskRequests.get()));
        assertTrue(validTask.test());
        assertFalse(forgedTask.test());
        assertAll(
                () -> assertEquals(1, backend.downloadTaskRequests.get()),
                () -> assertEquals(GameJavaVersion.JAVA_17, backend.lastDownloadedVersion),
                () -> assertEquals(backend.runtime, validTask.getResult()),
                () -> assertInstanceOf(UnsupportedPlatformException.class, forgedTask.getException()));

        backend.installedMojangVersions.add(GameJavaVersion.JAVA_17);
        Task<JavaRuntime> installedTask = service.downloadMojangRuntime(GameJavaVersion.JAVA_17);
        assertFalse(installedTask.test());
        assertInstanceOf(FileAlreadyExistsException.class, installedTask.getException());
        assertEquals(1, backend.downloadTaskRequests.get());

        backend.platform = differentPlatform();
        Task<JavaRuntime> crossPlatformTask = service.downloadMojangRuntime(GameJavaVersion.JAVA_17);
        Task<JavaRuntimeAcquisitionSnapshot> crossPlatformSnapshot = service.loadSnapshot();
        assertAll(
                () -> assertFalse(crossPlatformTask.test()),
                () -> assertInstanceOf(UnsupportedPlatformException.class, crossPlatformTask.getException()),
                () -> assertTrue(crossPlatformSnapshot.test()),
                () -> assertTrue(Objects.requireNonNull(
                        crossPlatformSnapshot.getResult(),
                        "cross-platform snapshot").mojangRuntimes().isEmpty()),
                () -> assertEquals(1, backend.downloadTaskRequests.get()));
    }

    /// Recognizes only promised archive suffixes and defers backend inspection until task startup.
    @Test
    void recognizesAndInspectsSupportedArchivesLazily() {
        FakeBackend backend = new FakeBackend();
        JavaManagerRuntimeAcquisitionService service = new JavaManagerRuntimeAcquisitionService(backend);
        Path archive = Path.of("runtime.zip");
        backend.directInspection = inspection(archive, "runtime", "runtime", backend.javaInfo);

        Task<LocalJavaArchiveInspection> inspectionTask = service.inspectLocalArchive(archive);

        assertAll(
                () -> assertTrue(service.supportsLocalArchive(Path.of("jdk.zip"))),
                () -> assertTrue(service.supportsLocalArchive(Path.of("jdk.tar.gz"))),
                () -> assertFalse(service.supportsLocalArchive(Path.of("jdk.tar"))),
                () -> assertFalse(service.supportsLocalArchive(Path.of("jdk.tgz"))),
                () -> assertTrue(service.supportsLocalArchive(Path.of("jdk.ZIP"))),
                () -> assertTrue(service.supportsLocalArchive(Path.of("jdk.TAR.GZ"))),
                () -> assertEquals(Task.TaskState.READY, inspectionTask.getState()),
                () -> assertEquals(0, backend.archiveInspectionRequests.get()));
        assertTrue(inspectionTask.test());
        assertAll(
                () -> assertEquals(1, backend.archiveInspectionRequests.get()),
                () -> assertEquals(backend.directInspection, inspectionTask.getResult()));

        Task<LocalJavaArchiveInspection> unsupportedTask = service.inspectLocalArchive(Path.of("runtime.jar"));
        assertAll(
                () -> assertEquals(Task.TaskState.READY, unsupportedTask.getState()),
                () -> assertFalse(unsupportedTask.test()),
                () -> assertInstanceOf(IllegalArgumentException.class, unsupportedTask.getException()),
                () -> assertEquals(1, backend.archiveInspectionRequests.get()));
    }

    /// Preserves a regular `bin/java` executable bit and a contained relative symbolic link while normalizing a ZIP.
    @Test
    void normalizesDirectJavaHomeWithExecutableAndRelativeSymlink() throws Exception {
        Path archive = temporaryDirectory().resolve("direct-java.zip");
        writeDirectJavaZip(
                archive,
                Platform.SYSTEM_PLATFORM,
                new LinkSpec("jdk-test/bin/java-link", "../lib/real"),
                null);
        JavaManagerRuntimeAcquisitionService.ProcessBackend backend =
                new JavaManagerRuntimeAcquisitionService.ProcessBackend();
        LocalJavaArchiveInspection inspection = backend.inspectLocalArchive(archive);

        LocalJavaArchiveInspection prepared = backend.prepareInstallArchive(inspection);
        try (ArchiveFileTree<?, ?> tree = ArchiveFileTree.open(prepared.archiveFile())) {
            assertAll(
                    () -> assertEquals("jdk-test", inspection.suggestedName()),
                    () -> assertEquals("jdk-test", inspection.javaHomeRelativePath()),
                    () -> assertEquals("java-home", prepared.suggestedName()),
                    () -> assertEquals("java-home", prepared.javaHomeRelativePath()));
            assertPreparedExecutableAndLink(tree);
        } finally {
            backend.deleteManagedTemporaryArchive(prepared.archiveFile());
        }
    }

    /// Locates a macOS `Contents/Home` runtime on any host and flattens it into an installable Java Home ZIP.
    @Test
    void normalizesMacOsContentsHomeLayoutOnAnyHost() throws Exception {
        Path archive = temporaryDirectory().resolve("mac-java.zip");
        writeMacJavaZip(archive, Platform.MACOS_X86_64);
        JavaManagerRuntimeAcquisitionService.ProcessBackend backend =
                new JavaManagerRuntimeAcquisitionService.ProcessBackend();
        LocalJavaArchiveInspection inspection = backend.inspectLocalArchiveLayout(archive);

        LocalJavaArchiveInspection prepared = backend.prepareInstallArchive(inspection);
        try (ArchiveFileTree<?, ?> tree = ArchiveFileTree.open(prepared.archiveFile())) {
            assertAll(
                    () -> assertEquals("jdk-test.jdk", inspection.suggestedName()),
                    () -> assertEquals(
                            "jdk-test.jdk/Contents/Home",
                            inspection.javaHomeRelativePath()),
                    () -> assertEquals(Platform.MACOS_X86_64, inspection.javaInfo().getPlatform()),
                    () -> assertEquals("java-home", prepared.javaHomeRelativePath()));
            assertPreparedExecutable(tree, "java-home/bin/java");
        } finally {
            backend.deleteManagedTemporaryArchive(prepared.archiveFile());
        }
    }

    /// Rejects raw entry traversal, alternate separators, empty segments, drive forms, and absolute paths.
    @Test
    void rejectsUnsafeArchiveEntryPaths() throws IOException {
        JavaManagerRuntimeAcquisitionService.ProcessBackend backend =
                new JavaManagerRuntimeAcquisitionService.ProcessBackend();
        List<String> unsafeEntries = List.of(
                "jdk-test/bin/..\\..\\evil",
                "jdk-test/bin//evil",
                "jdk-test/bin/../evil",
                "jdk-test/bin/C:/evil",
                "/absolute");

        for (int index = 0; index < unsafeEntries.size(); index++) {
            Path archive = temporaryDirectory().resolve("unsafe-entry-" + index + ".zip");
            writeDirectJavaZip(
                    archive,
                    Platform.SYSTEM_PLATFORM,
                    null,
                    unsafeEntries.get(index));
            assertThrows(IOException.class, () -> backend.inspectLocalArchiveLayout(archive));
        }
    }

    /// Rejects symbolic links that are relative textually but normalize outside the archive top-level directory.
    @Test
    void rejectsEscapingArchiveSymbolicLink() throws IOException {
        Path archive = temporaryDirectory().resolve("escaping-link.zip");
        writeDirectJavaZip(
                archive,
                Platform.SYSTEM_PLATFORM,
                new LinkSpec("jdk-test/lib/escape", "../../outside"),
                null);
        JavaManagerRuntimeAcquisitionService.ProcessBackend backend =
                new JavaManagerRuntimeAcquisitionService.ProcessBackend();

        assertThrows(IOException.class, () -> backend.inspectLocalArchiveLayout(archive));
    }

    /// Rejects chained symbolic links whose lexical targets look contained but resolve outside Java Home, and cycles.
    @Test
    void rejectsEscapingAndCyclicJavaHomeSymlinkGraphs() throws IOException {
        JavaManagerRuntimeAcquisitionService.ProcessBackend backend =
                new JavaManagerRuntimeAcquisitionService.ProcessBackend();
        Path chainedArchive = temporaryDirectory().resolve("chained-link.zip");
        Path cyclicArchive = temporaryDirectory().resolve("cyclic-link.zip");
        writeChainedSymlinkJavaZip(chainedArchive, false);
        writeChainedSymlinkJavaZip(cyclicArchive, true);

        assertAll(
                () -> assertThrows(IOException.class, () -> backend.inspectLocalArchiveLayout(chainedArchive)),
                () -> assertThrows(IOException.class, () -> backend.inspectLocalArchiveLayout(cyclicArchive)));
    }

    /// Enforces entry-count, compression-ratio, and bounded gzip-expanded TAR limits before in-memory tree creation.
    @Test
    void rejectsZipAndTarResourceBombsWithInjectableLimits() throws IOException {
        Path ordinaryZip = temporaryDirectory().resolve("entry-limit.zip");
        writeDirectJavaZip(ordinaryZip, Platform.SYSTEM_PLATFORM, null, null);
        JavaManagerRuntimeAcquisitionService.ProcessBackend entryLimitedBackend =
                new JavaManagerRuntimeAcquisitionService.ProcessBackend(
                        new JavaManagerRuntimeAcquisitionService.ArchiveLimits(
                                1024L * 1024L,
                                2,
                                1024L * 1024L,
                                2L * 1024L * 1024L,
                                1000.0,
                                1024L * 1024L));

        Path compressedZip = temporaryDirectory().resolve("ratio-limit.zip");
        writeDirectJavaZip(
                compressedZip,
                Platform.SYSTEM_PLATFORM,
                null,
                null,
                new byte[8192]);
        JavaManagerRuntimeAcquisitionService.ProcessBackend ratioLimitedBackend =
                new JavaManagerRuntimeAcquisitionService.ProcessBackend(
                        new JavaManagerRuntimeAcquisitionService.ArchiveLimits(
                                1024L * 1024L,
                                16,
                                16L * 1024L,
                                64L * 1024L,
                                2.0,
                                1024L * 1024L));

        Path compressedTar = temporaryDirectory().resolve("expanded-limit.tar.gz");
        writeGzipTarPayload(compressedTar, new byte[8192]);
        JavaManagerRuntimeAcquisitionService.ProcessBackend tarLimitedBackend =
                new JavaManagerRuntimeAcquisitionService.ProcessBackend(
                        new JavaManagerRuntimeAcquisitionService.ArchiveLimits(
                                1024L * 1024L,
                                16,
                                16L * 1024L,
                                64L * 1024L,
                                1000.0,
                                1024L));

        assertAll(
                () -> assertThrows(
                        IOException.class,
                        () -> entryLimitedBackend.inspectLocalArchiveLayout(ordinaryZip)),
                () -> assertThrows(
                        IOException.class,
                        () -> ratioLimitedBackend.inspectLocalArchiveLayout(compressedZip)),
                () -> assertThrows(
                        IOException.class,
                () -> tarLimitedBackend.inspectLocalArchiveLayout(compressedTar)));
    }

    /// Rejects an expanded TAR entry bomb through streaming preflight before Kala receives the temporary TAR.
    @Test
    void rejectsExpandedTarEntryBombBeforeTreeConstruction() throws IOException {
        Path compressedTar = temporaryDirectory().resolve("entry-bomb.tar.gz");
        int expandedBytes = writeGzipTarEntries(compressedTar, 4);
        long temporaryByteLimit = expandedBytes + 1024L;
        JavaManagerRuntimeAcquisitionService.ProcessBackend backend =
                new JavaManagerRuntimeAcquisitionService.ProcessBackend(
                        new JavaManagerRuntimeAcquisitionService.ArchiveLimits(
                                1024L * 1024L,
                                3,
                                1024L,
                                64L * 1024L,
                                1000.0,
                                temporaryByteLimit));

        IOException failure = assertThrows(
                IOException.class,
                () -> backend.inspectLocalArchiveLayout(compressedTar));

        assertAll(
                () -> assertTrue(expandedBytes < temporaryByteLimit),
                () -> assertEquals("Java TAR contains too many entries", failure.getMessage()));
    }

    /// Opens supported archive suffixes case-insensitively even though Kala's filename dispatch is case-sensitive.
    @Test
    void inspectsUppercaseZipSuffixThroughControlledNormalization() throws Exception {
        Path archive = temporaryDirectory().resolve("runtime.ZIP");
        writeDirectJavaZip(archive, Platform.SYSTEM_PLATFORM, null, null);
        JavaManagerRuntimeAcquisitionService.ProcessBackend backend =
                new JavaManagerRuntimeAcquisitionService.ProcessBackend();

        LocalJavaArchiveInspection inspection = backend.inspectLocalArchive(archive);

        assertEquals("jdk-test", inspection.suggestedName());
    }

    /// Publishes a complete runtime and manifest only after staging extraction succeeds.
    @Test
    void publishesStagedRuntimeAndManifestWithoutTemporaryResidue() throws Exception {
        Path archive = temporaryDirectory().resolve("publisher.zip");
        writeDirectJavaZip(archive, Platform.SYSTEM_PLATFORM, null, null);
        Path platformRoot = temporaryDirectory().resolve("published-platform");
        Files.createDirectories(platformRoot);
        Path targetDirectory = platformRoot.resolve("runtime");
        Path manifestFile = platformRoot.resolve("runtime.json");
        Task<JavaRuntime> task = JavaRuntimeInstallationPublisher.createInstallTask(
                Platform.SYSTEM_PLATFORM,
                "runtime",
                archive,
                platformRoot,
                targetDirectory,
                manifestFile);

        assertTrue(task.test(), () -> "Publication failed: " + task.getException());

        JavaRuntime runtime = Objects.requireNonNull(task.getResult(), "published runtime");
        JavaManifest manifest = Objects.requireNonNull(
                fromJsonFile(manifestFile, JavaManifest.class),
                "published manifest");
        try (var children = Files.list(platformRoot)) {
            assertAll(
                    () -> assertTrue(runtime.getBinary().startsWith(targetDirectory)),
                    () -> assertEquals(Platform.SYSTEM_PLATFORM, manifest.info().getPlatform()),
                    () -> assertTrue(Files.isDirectory(targetDirectory)),
                    () -> assertTrue(Files.isRegularFile(manifestFile)),
                    () -> assertFalse(children
                            .map(path -> path.getFileName().toString())
                            .anyMatch(name -> name.startsWith(".xyml-java-stage-")
                                    || name.startsWith(".xyml-java-manifest-"))));
        }
    }

    /// Rejects a staging directory replaced after reservation before any archive entry can be created or truncated.
    @Test
    void rejectsReplacedStagingDirectoryBeforeExtraction() throws Exception {
        Path archive = temporaryDirectory().resolve("replaced-staging.zip");
        writeDirectJavaZip(archive, Platform.SYSTEM_PLATFORM, null, null);
        Path platformRoot = temporaryDirectory().resolve("replaced-staging-platform");
        Files.createDirectories(platformRoot);
        Task<JavaRuntime> publication = JavaRuntimeInstallationPublisher.createInstallTask(
                Platform.SYSTEM_PLATFORM,
                "runtime",
                archive,
                platformRoot,
                platformRoot.resolve("runtime"),
                platformRoot.resolve("runtime.json"));
        publication.execute();
        Task<?> extractor = publication.getDependencies().iterator().next();
        Path stagingDirectory;
        try (var children = Files.list(platformRoot)) {
            stagingDirectory = children
                    .filter(path -> path.getFileName().toString().startsWith(".xyml-java-stage-"))
                    .findFirst()
                    .orElseThrow();
        }
        Path markerFile;
        try (var children = Files.list(stagingDirectory)) {
            markerFile = children
                    .filter(path -> path.getFileName().toString().startsWith(".xyml-install-owner-"))
                    .findFirst()
                    .orElseThrow();
        }
        String copiedToken = Files.readString(markerFile);
        Files.delete(markerFile);
        Files.delete(stagingDirectory);
        Files.createDirectory(stagingDirectory);
        Files.writeString(markerFile, copiedToken);
        Path foreignRelease = Files.writeString(
                stagingDirectory.resolve("release"),
                "foreign-release");

        assertFalse(extractor.test());
        publication.postExecute();

        assertAll(
                () -> assertEquals("foreign-release", Files.readString(foreignRelease)),
                () -> assertTrue(Files.isDirectory(stagingDirectory)),
                () -> assertFalse(Files.exists(
                        stagingDirectory.resolve("bin"),
                        LinkOption.NOFOLLOW_LINKS)),
                () -> assertInstanceOf(IOException.class, extractor.getException()));
    }

    /// Rejects an existing staging file without truncating it even while the staging ownership remains valid.
    @Test
    void rejectsExistingStagingFileWithoutTruncation() throws Exception {
        Path archive = temporaryDirectory().resolve("existing-entry.zip");
        writeDirectJavaZip(archive, Platform.SYSTEM_PLATFORM, null, null);
        Path platformRoot = temporaryDirectory().resolve("existing-entry-platform");
        Files.createDirectories(platformRoot);
        JavaRuntimeInstallationPublisher.OwnedDirectory staging =
                JavaRuntimeInstallationPublisher.createOwnedStagingDirectory(
                        platformRoot,
                        platformRoot.resolve("runtime"),
                        platformRoot.resolve("runtime.json"));
        Path existingRelease = Files.writeString(
                staging.directory().resolve("release"),
                "existing-release");
        SafeJavaRuntimeExtractionTask extractor = new SafeJavaRuntimeExtractionTask(
                staging,
                Map.of("xyml.acquisitionOwner", staging.token()),
                archive);
        try {
            assertFalse(extractor.test());

            assertAll(
                    () -> assertEquals("existing-release", Files.readString(existingRelease)),
                    () -> assertInstanceOf(IOException.class, extractor.getException()));
        } finally {
            JavaRuntimeInstallationPublisher.cleanupOwnedDirectory(staging);
        }
    }

    /// Rejects a planted filesystem link without following it or changing its external target.
    ///
    /// The fixture prefers a symbolic link, falls back to a hard link where Windows link privileges are restricted,
    /// and finally retains deterministic no-truncation coverage with a regular pre-existing file.
    @Test
    void rejectsExistingStagingLinkWithoutFollowingOrTruncatingTarget() throws Exception {
        Path archive = temporaryDirectory().resolve("linked-entry.zip");
        writeDirectJavaZip(archive, Platform.SYSTEM_PLATFORM, null, null);
        Path platformRoot = temporaryDirectory().resolve("linked-entry-platform");
        Files.createDirectories(platformRoot);
        JavaRuntimeInstallationPublisher.OwnedDirectory staging =
                JavaRuntimeInstallationPublisher.createOwnedStagingDirectory(
                        platformRoot,
                        platformRoot.resolve("runtime"),
                        platformRoot.resolve("runtime.json"));
        Path externalRelease = Files.writeString(
                temporaryDirectory().resolve("external-release"),
                "external-release");
        Path stagedRelease = staging.directory().resolve("release");
        boolean linkedDestination;
        try {
            Files.createSymbolicLink(stagedRelease, externalRelease);
            linkedDestination = true;
        } catch (IOException | SecurityException | UnsupportedOperationException failure) {
            try {
                Files.createLink(stagedRelease, externalRelease);
                linkedDestination = true;
            } catch (IOException | SecurityException | UnsupportedOperationException linkFailure) {
                Files.writeString(stagedRelease, "staged-release");
                linkedDestination = false;
            }
        }

        SafeJavaRuntimeExtractionTask extractor = new SafeJavaRuntimeExtractionTask(
                staging,
                Map.of("xyml.acquisitionOwner", staging.token()),
                archive);
        try {
            assertFalse(extractor.test());

            String expectedStagedContents = linkedDestination
                    ? "external-release"
                    : "staged-release";
            assertAll(
                    () -> assertEquals("external-release", Files.readString(externalRelease)),
                    () -> assertEquals(expectedStagedContents, Files.readString(stagedRelease)),
                    () -> assertInstanceOf(IOException.class, extractor.getException()));
        } finally {
            JavaRuntimeInstallationPublisher.cleanupOwnedDirectory(staging);
        }
    }

    /// Rejects a final manifest hard link inserted after staging and never writes through to its external inode.
    @Test
    void rejectsManifestHardLinkInsertedAfterStaging() throws Exception {
        Path platformRoot = temporaryDirectory().resolve("linked-platform");
        Files.createDirectories(platformRoot);
        Path targetDirectory = platformRoot.resolve("runtime");
        Path manifestFile = platformRoot.resolve("runtime.json");
        JavaRuntimeInstallationPublisher.OwnedDirectory staging =
                JavaRuntimeInstallationPublisher.createOwnedStagingDirectory(
                        platformRoot,
                        targetDirectory,
                        manifestFile);
        Path externalFile = Files.writeString(
                temporaryDirectory().resolve("external-manifest.json"),
                "external");
        try {
            Files.createLink(manifestFile, externalFile);
        } catch (IOException | SecurityException | UnsupportedOperationException failure) {
            JavaRuntimeInstallationPublisher.cleanupOwnedDirectory(staging);
            assumeTrue(false, "Hard links are unavailable: " + failure.getMessage());
        }

        try {
            assertAll(
                    () -> assertThrows(
                            FileAlreadyExistsException.class,
                            () -> JavaRuntimeInstallationPublisher.moveOwnedDirectoryToFinal(staging)),
                    () -> assertEquals("external", Files.readString(externalFile)),
                    () -> assertEquals("external", Files.readString(manifestFile)),
                    () -> assertFalse(Files.exists(targetDirectory, LinkOption.NOFOLLOW_LINKS)),
                    () -> assertTrue(Files.isDirectory(staging.directory())));
        } finally {
            JavaRuntimeInstallationPublisher.cleanupOwnedDirectory(staging);
        }
    }

    /// Preserves replaced staging directories while deleting a directory that retains exact ownership proof.
    @Test
    void rollsBackOnlyProvenOwnedTargets() throws Exception {
        Path platformRoot = temporaryDirectory().resolve("owned-platform");
        Files.createDirectories(platformRoot);

        Path targetDirectory = platformRoot.resolve("runtime");
        Path manifestFile = platformRoot.resolve("runtime.json");
        JavaRuntimeInstallationPublisher.OwnedDirectory reservation =
                JavaRuntimeInstallationPublisher.createOwnedStagingDirectory(
                        platformRoot,
                        targetDirectory,
                        manifestFile);
        Files.delete(reservation.markerFile());
        Files.delete(reservation.directory());
        Files.createDirectory(reservation.directory());
        Path foreignFile = Files.writeString(reservation.directory().resolve("foreign.txt"), "foreign");
        Files.writeString(manifestFile, "{}");

        JavaRuntimeInstallationPublisher.cleanupOwnedManifest(reservation);
        JavaRuntimeInstallationPublisher.cleanupOwnedDirectory(reservation);

        Path ownedTarget = platformRoot.resolve("owned-runtime");
        JavaRuntimeInstallationPublisher.OwnedDirectory ownedReservation =
                JavaRuntimeInstallationPublisher.createOwnedStagingDirectory(
                        platformRoot,
                        ownedTarget,
                        platformRoot.resolve("owned-runtime.json"));
        Files.writeString(ownedReservation.directory().resolve("owned.txt"), "owned");
        JavaRuntimeInstallationPublisher.cleanupOwnedDirectory(ownedReservation);

        assertAll(
                () -> assertTrue(Files.exists(foreignFile)),
                () -> assertTrue(Files.exists(manifestFile)),
                () -> assertFalse(Files.exists(
                        ownedReservation.directory(),
                        LinkOption.NOFOLLOW_LINKS)));
    }

    /// Rejects traversal aliases, case-insensitive Mojang prefixes, device names, tail dots, and existing targets.
    @Test
    void validatesManagedInstallNamesAndDirectChildContainment() {
        FakeBackend backend = new FakeBackend();
        JavaManagerRuntimeAcquisitionService service = new JavaManagerRuntimeAcquisitionService(backend);
        LocalJavaArchiveInspection inspection = inspection(
                Path.of("runtime.zip"),
                "runtime",
                "runtime",
                backend.javaInfo);
        backend.installedNames.add("existing");

        assertAll(
                () -> assertEquals(
                        JavaRuntimeInstallNameStatus.VALID,
                        service.validateInstallName(inspection, "Temurin_21.0-1")),
                () -> assertEquals(
                        JavaRuntimeInstallNameStatus.INVALID_CHARACTERS,
                        service.validateInstallName(inspection, "bad name")),
                () -> assertEquals(
                        JavaRuntimeInstallNameStatus.RESERVED_MOJANG_PREFIX,
                        service.validateInstallName(inspection, "MOJANG-custom")),
                () -> assertEquals(
                        JavaRuntimeInstallNameStatus.RESERVED_PLATFORM_NAME,
                        service.validateInstallName(inspection, "CON.txt")),
                () -> assertEquals(
                        JavaRuntimeInstallNameStatus.RESERVED_PLATFORM_NAME,
                        service.validateInstallName(inspection, "lpt9.log")),
                () -> assertEquals(
                        JavaRuntimeInstallNameStatus.UNSAFE_PATH,
                        service.validateInstallName(inspection, ".")),
                () -> assertEquals(
                        JavaRuntimeInstallNameStatus.UNSAFE_PATH,
                        service.validateInstallName(inspection, "..")),
                () -> assertEquals(
                        JavaRuntimeInstallNameStatus.UNSAFE_PATH,
                        service.validateInstallName(inspection, "jdk.")),
                () -> assertEquals(
                        JavaRuntimeInstallNameStatus.ALREADY_INSTALLED,
                        service.validateInstallName(inspection, "existing")));
    }

    /// Copies, reinspects, prepares, installs, and cleans both temporary archives only after task startup.
    @Test
    void installsThroughControlledArchivesAndCleansSuccessAndFailure() {
        FakeBackend backend = new FakeBackend();
        JavaManagerRuntimeAcquisitionService service = new JavaManagerRuntimeAcquisitionService(backend);
        LocalJavaArchiveInspection original = inspection(
                Path.of("runtime.zip"),
                "runtime",
                "runtime",
                backend.javaInfo);

        Task<JavaRuntime> successTask = service.installLocalArchive(original, "runtime");

        assertAll(
                () -> assertEquals(Task.TaskState.READY, successTask.getState()),
                () -> assertEquals(0, backend.copyRequests.get()),
                () -> assertEquals(0, backend.prepareRequests.get()),
                () -> assertEquals(0, backend.installTaskRequests.get()));
        assertTrue(successTask.test());
        assertAll(
                () -> assertEquals(1, backend.copyRequests.get()),
                () -> assertEquals(1, backend.prepareRequests.get()),
                () -> assertEquals(1, backend.installTaskRequests.get()),
                () -> assertEquals(backend.preparedInspection, backend.lastInstalledInspection),
                () -> assertEquals("runtime", backend.lastInstalledName),
                () -> assertEquals(backend.runtime, successTask.getResult()),
                () -> assertTrue(backend.deletedArchives.contains(backend.controlledCopy)),
                () -> assertTrue(backend.deletedArchives.contains(backend.preparedArchive)));

        backend.deletedArchives.clear();
        backend.installFailure = new IOException("installation failed");
        Task<JavaRuntime> failureTask = service.installLocalArchive(original, "runtime-failure");
        assertFalse(failureTask.test());
        assertAll(
                () -> assertInstanceOf(IOException.class, failureTask.getException()),
                () -> assertTrue(backend.deletedArchives.contains(backend.controlledCopy)),
                () -> assertTrue(backend.deletedArchives.contains(backend.preparedArchive)));

        backend.installFailure = null;
        backend.installedNames.add("late-duplicate");
        int copiesBeforeDuplicate = backend.copyRequests.get();
        Task<JavaRuntime> duplicateTask = service.installLocalArchive(original, "late-duplicate");
        assertFalse(duplicateTask.test());
        assertAll(
                () -> assertInstanceOf(FileAlreadyExistsException.class, duplicateTask.getException()),
                () -> assertEquals(copiesBeforeDuplicate, backend.copyRequests.get()));
    }

    /// Cleans staged archives and reserved-install ownership when cancellation races dependency startup.
    @Test
    void cancellationAfterStagingCleansBeforeInstallDependencyRuns() throws Exception {
        FakeBackend backend = new FakeBackend();
        JavaManagerRuntimeAcquisitionService service = new JavaManagerRuntimeAcquisitionService(backend);
        LocalJavaArchiveInspection original = inspection(
                Path.of("runtime.zip"),
                "runtime",
                "runtime",
                backend.javaInfo);
        CountDownLatch stagingEntered = new CountDownLatch(1);
        CountDownLatch releaseStaging = new CountDownLatch(1);
        CleanupAwareFakeInstallTask installTask = new CleanupAwareFakeInstallTask(backend.runtime);
        backend.installTaskCreationEntered = stagingEntered;
        backend.installTaskCreationRelease = releaseStaging;
        backend.cleanupAwareInstallTask = installTask;

        Task<JavaRuntime> task = service.installLocalArchive(original, "runtime");
        TaskExecutor executor = task.executor();
        CompletableFuture<Boolean> completion = CompletableFuture.supplyAsync(executor::test);
        assertTrue(stagingEntered.await(5L, TimeUnit.SECONDS));

        executor.cancel();
        releaseStaging.countDown();

        assertFalse(completion.get(5L, TimeUnit.SECONDS));
        assertAll(
                () -> assertEquals(0, installTask.executeRequests.get()),
                () -> assertEquals(1, installTask.cleanupRequests.get()),
                () -> assertTrue(backend.deletedArchives.contains(backend.controlledCopy)),
                () -> assertTrue(backend.deletedArchives.contains(backend.preparedArchive)));
    }

    /// Rejects a source whose controlled copy no longer matches the original user-visible inspection.
    @Test
    void rejectsChangedArchiveBeforePreparationAndCleansControlledCopy() {
        FakeBackend backend = new FakeBackend();
        JavaManagerRuntimeAcquisitionService service = new JavaManagerRuntimeAcquisitionService(backend);
        LocalJavaArchiveInspection original = inspection(
                Path.of("runtime.zip"),
                "runtime",
                "runtime",
                backend.javaInfo);
        backend.copiedInspection = inspection(
                backend.controlledCopy,
                "runtime",
                "runtime",
                new JavaInfo(Platform.SYSTEM_PLATFORM, "17.0.12", "Changed Vendor"));

        Task<JavaRuntime> task = service.installLocalArchive(original, "runtime");

        assertFalse(task.test());
        assertAll(
                () -> assertInstanceOf(IOException.class, task.getException()),
                () -> assertEquals(1, backend.copyRequests.get()),
                () -> assertEquals(0, backend.prepareRequests.get()),
                () -> assertEquals(0, backend.installTaskRequests.get()),
                () -> assertEquals(List.of(backend.controlledCopy), backend.deletedArchives));
    }

    /// Rejects same-layout and same-release replacement when ordinary payload bytes changed after inspection.
    @Test
    void rejectsSameMetadataContentReplacementAndCleansControlledCopy() throws Exception {
        Path archive = temporaryDirectory().resolve("same-metadata.zip");
        JavaManagerRuntimeAcquisitionService.ProcessBackend processBackend =
                new JavaManagerRuntimeAcquisitionService.ProcessBackend();
        writeDirectJavaZip(
                archive,
                Platform.SYSTEM_PLATFORM,
                null,
                null,
                new byte[]{1});
        LocalJavaArchiveInspection original = processBackend.inspectLocalArchive(archive);

        writeDirectJavaZip(
                archive,
                Platform.SYSTEM_PLATFORM,
                null,
                null,
                new byte[]{9});
        LocalJavaArchiveInspection replacement = processBackend.inspectLocalArchive(archive);

        FakeBackend backend = new FakeBackend();
        backend.copiedInspection = new LocalJavaArchiveInspection(
                backend.controlledCopy,
                replacement.suggestedName(),
                replacement.javaHomeRelativePath(),
                replacement.javaInfo(),
                replacement.archiveSize(),
                replacement.sha256());
        JavaManagerRuntimeAcquisitionService service = new JavaManagerRuntimeAcquisitionService(backend);

        Task<JavaRuntime> task = service.installLocalArchive(original, "runtime");

        assertFalse(task.test());
        assertAll(
                () -> assertEquals(original.suggestedName(), replacement.suggestedName()),
                () -> assertEquals(original.javaHomeRelativePath(), replacement.javaHomeRelativePath()),
                () -> assertEquals(original.javaInfo().getPlatform(), replacement.javaInfo().getPlatform()),
                () -> assertEquals(original.javaInfo().getVersion(), replacement.javaInfo().getVersion()),
                () -> assertEquals(original.javaInfo().getVendor(), replacement.javaInfo().getVendor()),
                () -> assertEquals(original.archiveSize(), replacement.archiveSize()),
                () -> assertNotEquals(original.sha256(), replacement.sha256()),
                () -> assertInstanceOf(IOException.class, task.getException()),
                () -> assertEquals(0, backend.prepareRequests.get()),
                () -> assertEquals(0, backend.installTaskRequests.get()),
                () -> assertEquals(List.of(backend.controlledCopy), backend.deletedArchives));
    }

    /// Returns the non-null temporary directory injected by JUnit.
    ///
    /// @return test temporary directory
    private Path temporaryDirectory() {
        return Objects.requireNonNull(temporaryDirectory, "temporaryDirectory");
    }

    /// Returns a platform that differs from the process system platform.
    ///
    /// @return deterministic different platform
    private static Platform differentPlatform() {
        for (Platform candidate : List.of(
                Platform.WINDOWS_X86_64,
                Platform.LINUX_X86_64,
                Platform.MACOS_X86_64,
                Platform.UNKNOWN)) {
            if (!candidate.equals(Platform.SYSTEM_PLATFORM)) {
                return candidate;
            }
        }
        throw new AssertionError("No distinct platform constant available");
    }

    /// Creates normalized immutable fake inspection data.
    ///
    /// @param archive archive path
    /// @param suggestedName suggested managed name
    /// @param javaHomeRelativePath archive Java Home path
    /// @param javaInfo Java metadata
    /// @return immutable inspection
    private static LocalJavaArchiveInspection inspection(
            Path archive,
            String suggestedName,
            String javaHomeRelativePath,
            JavaInfo javaInfo) {
        return new LocalJavaArchiveInspection(
                archive,
                suggestedName,
                javaHomeRelativePath,
                javaInfo,
                1L,
                TEST_SHA256);
    }

    /// Writes a direct-root Java Home ZIP with optional symbolic-link and unsafe-entry fixtures.
    ///
    /// @param archive target ZIP
    /// @param platform release-file platform
    /// @param link optional symbolic-link entry
    /// @param unsafeEntry optional extra raw path used for rejection tests
    /// @throws IOException when the fixture cannot be written
    private static void writeDirectJavaZip(
            Path archive,
            Platform platform,
            @Nullable LinkSpec link,
            @Nullable String unsafeEntry) throws IOException {
        writeDirectJavaZip(archive, platform, link, unsafeEntry, new byte[]{1});
    }

    /// Writes a direct-root Java Home ZIP with caller-selected ordinary file contents.
    ///
    /// @param archive target ZIP
    /// @param platform release-file platform
    /// @param link optional symbolic-link entry
    /// @param unsafeEntry optional extra raw path used for rejection tests
    /// @param ordinaryContents ordinary `lib/real` file contents
    /// @throws IOException when the fixture cannot be written
    private static void writeDirectJavaZip(
            Path archive,
            Platform platform,
            @Nullable LinkSpec link,
            @Nullable String unsafeEntry,
            byte @Unmodifiable [] ordinaryContents) throws IOException {
        try (ZipArchiveOutputStream output = new ZipArchiveOutputStream(Files.newOutputStream(archive))) {
            writeArchiveFile(
                    output,
                    "jdk-test/release",
                    releaseContents(platform),
                    UnixStat.FILE_FLAG | UnixStat.DEFAULT_FILE_PERM);
            writeArchiveFile(
                    output,
                    "jdk-test/bin/" + platform.getOperatingSystem().getJavaExecutable(),
                    new byte[]{0},
                    UnixStat.FILE_FLAG | 0755);
            writeArchiveFile(
                    output,
                    "jdk-test/lib/real",
                    ordinaryContents,
                    UnixStat.FILE_FLAG | UnixStat.DEFAULT_FILE_PERM);
            if (link != null) {
                writeArchiveFile(
                        output,
                        link.entryName(),
                        link.target().getBytes(StandardCharsets.UTF_8),
                        UnixStat.LINK_FLAG | UnixStat.DEFAULT_LINK_PERM);
            }
            if (unsafeEntry != null) {
                writeArchiveFile(
                        output,
                        unsafeEntry,
                        new byte[]{2},
                        UnixStat.FILE_FLAG | UnixStat.DEFAULT_FILE_PERM);
            }
        }
    }

    /// Writes a Java Home whose executable participates in either a chained escape or a direct link cycle.
    ///
    /// @param archive target ZIP
    /// @param cyclic whether to write a cycle instead of the chained escape fixture
    /// @throws IOException when the fixture cannot be written
    private static void writeChainedSymlinkJavaZip(Path archive, boolean cyclic) throws IOException {
        String executable = Platform.SYSTEM_PLATFORM.getOperatingSystem().getJavaExecutable();
        try (ZipArchiveOutputStream output = new ZipArchiveOutputStream(Files.newOutputStream(archive))) {
            writeArchiveFile(
                    output,
                    "jdk-test/release",
                    releaseContents(Platform.SYSTEM_PLATFORM),
                    UnixStat.FILE_FLAG | UnixStat.DEFAULT_FILE_PERM);
            if (cyclic) {
                writeArchiveFile(
                        output,
                        "jdk-test/bin/" + executable,
                        "loop".getBytes(StandardCharsets.UTF_8),
                        UnixStat.LINK_FLAG | UnixStat.DEFAULT_LINK_PERM);
                writeArchiveFile(
                        output,
                        "jdk-test/bin/loop",
                        executable.getBytes(StandardCharsets.UTF_8),
                        UnixStat.LINK_FLAG | UnixStat.DEFAULT_LINK_PERM);
            } else {
                writeArchiveFile(
                        output,
                        "jdk-test/bin/hop",
                        "..".getBytes(StandardCharsets.UTF_8),
                        UnixStat.LINK_FLAG | UnixStat.DEFAULT_LINK_PERM);
                writeArchiveFile(
                        output,
                        "jdk-test/bin/" + executable,
                        "hop/../../evil".getBytes(StandardCharsets.UTF_8),
                        UnixStat.LINK_FLAG | UnixStat.DEFAULT_LINK_PERM);
            }
        }
    }

    /// Writes one gzip-compressed TAR payload used to exercise bounded temporary expansion.
    ///
    /// @param archive target `.tar.gz`
    /// @param payload uncompressed payload bytes
    /// @throws IOException when the fixture cannot be written
    private static void writeGzipTarPayload(
            Path archive,
            byte @Unmodifiable [] payload) throws IOException {
        try (TarArchiveOutputStream output = new TarArchiveOutputStream(
                new GZIPOutputStream(Files.newOutputStream(archive)))) {
            TarArchiveEntry entry = new TarArchiveEntry("payload.bin");
            entry.setSize(payload.length);
            output.putArchiveEntry(entry);
            output.write(payload);
            output.closeArchiveEntry();
        }
    }

    /// Writes a gzip-compressed TAR containing a deterministic count of empty regular files.
    ///
    /// @param archive target .tar.gz
    /// @param entryCount regular-file entries to emit
    /// @return exact uncompressed TAR byte length
    /// @throws IOException when the fixture cannot be written
    private static int writeGzipTarEntries(Path archive, int entryCount) throws IOException {
        ByteArrayOutputStream expandedTar = new ByteArrayOutputStream();
        try (TarArchiveOutputStream output = new TarArchiveOutputStream(expandedTar)) {
            for (int index = 0; index < entryCount; index++) {
                TarArchiveEntry entry = new TarArchiveEntry("entry-" + index + ".txt");
                entry.setSize(0L);
                output.putArchiveEntry(entry);
                output.closeArchiveEntry();
            }
        }
        byte[] expandedBytes = expandedTar.toByteArray();
        try (GZIPOutputStream output = new GZIPOutputStream(Files.newOutputStream(archive))) {
            output.write(expandedBytes);
        }
        return expandedBytes.length;
    }

    /// Writes a macOS bundle ZIP whose Java Home is nested below `Contents/Home`.
    ///
    /// @param archive target ZIP
    /// @param platform macOS release-file platform
    /// @throws IOException when the fixture cannot be written
    private static void writeMacJavaZip(Path archive, Platform platform) throws IOException {
        try (ZipArchiveOutputStream output = new ZipArchiveOutputStream(Files.newOutputStream(archive))) {
            writeArchiveFile(
                    output,
                    "jdk-test.jdk/Contents/Home/release",
                    releaseContents(platform),
                    UnixStat.FILE_FLAG | UnixStat.DEFAULT_FILE_PERM);
            writeArchiveFile(
                    output,
                    "jdk-test.jdk/Contents/Home/bin/java",
                    new byte[]{0},
                    UnixStat.FILE_FLAG | 0755);
        }
    }

    /// Encodes the minimum release-file fields required by [JavaInfo#fromReleaseFile].
    ///
    /// @param platform platform encoded in the release file
    /// @return UTF-8 release-file bytes
    private static byte[] releaseContents(Platform platform) {
        return ("OS_NAME=\"" + platform.getOperatingSystem().getCheckedName() + "\"\n"
                + "OS_ARCH=\"" + platform.getArchitecture().getCheckedName() + "\"\n"
                + "JAVA_VERSION=\"21.0.2\"\n"
                + "IMPLEMENTOR=\"Test Vendor\"\n").getBytes(StandardCharsets.UTF_8);
    }

    /// Writes one Kala ZIP entry with explicit UNIX file-type and permission bits.
    ///
    /// @param output destination archive
    /// @param name raw entry name
    /// @param contents entry contents or symbolic-link target bytes
    /// @param unixMode UNIX type and permission bits
    /// @throws IOException when the entry cannot be written
    private static void writeArchiveFile(
            ZipArchiveOutputStream output,
            String name,
            byte[] contents,
            int unixMode) throws IOException {
        ZipArchiveEntry entry = new ZipArchiveEntry(name);
        entry.setUnixMode(unixMode);
        output.putArchiveEntry(entry);
        output.write(contents);
        output.closeArchiveEntry();
    }

    /// Asserts executable and contained relative-link metadata in a normalized archive.
    ///
    /// @param tree normalized archive tree
    /// @throws IOException when symbolic-link metadata cannot be read
    private static <F, E extends ArchiveEntry> void assertPreparedExecutableAndLink(
            ArchiveFileTree<F, E> tree) throws IOException {
        assertPreparedExecutable(tree, "java-home/bin/" + OperatingSystem.CURRENT_OS.getJavaExecutable());
        E linkEntry = Objects.requireNonNull(
                tree.getEntry("java-home/bin/java-link"),
                "normalized symbolic link");
        assertAll(
                () -> assertTrue(tree.isLink(linkEntry)),
                () -> assertEquals("../lib/real", tree.getLink(linkEntry)));
    }

    /// Asserts that one normalized archive entry retains executable metadata.
    ///
    /// @param tree normalized archive tree
    /// @param entryPath expected executable path
    private static <F, E extends ArchiveEntry> void assertPreparedExecutable(
            ArchiveFileTree<F, E> tree,
            String entryPath) {
        E javaEntry = Objects.requireNonNull(tree.getEntry(entryPath), "normalized Java executable");
        assertTrue(tree.isExecutable(javaEntry));
    }

    /// Symbolic-link fixture stored in a Kala ZIP.
    ///
    /// @param entryName raw symbolic-link archive path
    /// @param target raw symbolic-link target text
    @NotNullByDefault
    private record LinkSpec(String entryName, String target) {
        /// Rejects absent symbolic-link fixture data.
        private LinkSpec {
            entryName = Objects.requireNonNull(entryName, "entryName");
            target = Objects.requireNonNull(target, "target");
        }
    }

    /// Fake install task exposing deterministic ownership cleanup without touching the real managed repository.
    @NotNullByDefault
    private static final class CleanupAwareFakeInstallTask extends Task<JavaRuntime>
            implements JavaManagerRuntimeAcquisitionService.IncompleteInstallCleanup {
        /// Runtime produced if the dependency unexpectedly executes.
        private final JavaRuntime runtime;

        /// Number of task-body executions.
        private final AtomicInteger executeRequests = new AtomicInteger();

        /// Number of non-commit ownership cleanup calls.
        private final AtomicInteger cleanupRequests = new AtomicInteger();

        /// Creates a cleanup-aware fake install task.
        ///
        /// @param runtime fake runtime result
        private CleanupAwareFakeInstallTask(JavaRuntime runtime) {
            this.runtime = Objects.requireNonNull(runtime, "runtime");
        }

        /// Records unexpected execution and produces the fake runtime.
        @Override
        public void execute() {
            executeRequests.incrementAndGet();
            setResult(runtime);
        }

        /// Records only rollback requests, ignoring successful commits.
        ///
        /// @param commit whether the full outer chain permits keeping installation state
        @Override
        public void cleanupUnlessCommitted(boolean commit) {
            if (!commit) {
                cleanupRequests.incrementAndGet();
            }
        }
    }

    /// Deterministic backend that records every lazy acquisition stage and temporary-file cleanup.
    @NotNullByDefault
    private static final class FakeBackend implements JavaRuntimeAcquisitionBackend {
        /// Platform returned to the service.
        private Platform platform = Platform.SYSTEM_PLATFORM;

        /// Java metadata used by fake archive and runtime results.
        private final JavaInfo javaInfo = new JavaInfo(
                Platform.SYSTEM_PLATFORM,
                "21.0.2",
                "Test Vendor");

        /// Runtime returned by fake download and install tasks.
        private final JavaRuntime runtime = JavaRuntime.of(
                Path.of("managed-java/bin/" + OperatingSystem.CURRENT_OS.getJavaExecutable()),
                javaInfo,
                true);

        /// Supported Mojang versions in display order.
        private final List<GameJavaVersion> supportedVersions = new ArrayList<>();

        /// Mojang components reported as present locally.
        private final Set<GameJavaVersion> installedMojangVersions = new HashSet<>();

        /// Named local archive installations reported as present.
        private final Set<String> installedNames = new HashSet<>();

        /// System-controlled source copy returned after task startup.
        private final Path controlledCopy = Path.of("controlled-source.zip").toAbsolutePath().normalize();

        /// Normalized install archive returned after preparation.
        private final Path preparedArchive = Path.of("prepared-install.zip").toAbsolutePath().normalize();

        /// Managed platform root used for path-containment validation.
        private final Path managedRoot = Path.of("managed-java-root").toAbsolutePath().normalize();

        /// Number of current-platform reads.
        private final AtomicInteger currentPlatformReads = new AtomicInteger();

        /// Number of local Mojang presence checks.
        private final AtomicInteger mojangPresenceChecks = new AtomicInteger();

        /// Number of actual download task requests.
        private final AtomicInteger downloadTaskRequests = new AtomicInteger();

        /// Number of actual archive inspection requests.
        private final AtomicInteger archiveInspectionRequests = new AtomicInteger();

        /// Number of controlled-copy requests.
        private final AtomicInteger copyRequests = new AtomicInteger();

        /// Number of normalized archive preparation requests.
        private final AtomicInteger prepareRequests = new AtomicInteger();

        /// Number of actual install task requests.
        private final AtomicInteger installTaskRequests = new AtomicInteger();

        /// Temporary archives requested for deletion in call order.
        private final List<Path> deletedArchives = new ArrayList<>();

        /// Direct archive inspection returned for non-controlled paths, or null until configured.
        private @Nullable LocalJavaArchiveInspection directInspection;

        /// Inspection returned for the controlled source copy.
        private LocalJavaArchiveInspection copiedInspection;

        /// Inspection returned for the normalized install archive.
        private LocalJavaArchiveInspection preparedInspection;

        /// Optional latch signalled when fake install-task construction begins.
        private @Nullable CountDownLatch installTaskCreationEntered;

        /// Optional latch blocking fake install-task construction until the test releases it.
        private @Nullable CountDownLatch installTaskCreationRelease;

        /// Optional cleanup-aware install dependency returned instead of the ordinary fake task.
        private @Nullable CleanupAwareFakeInstallTask cleanupAwareInstallTask;

        /// Optional fake installation failure.
        private @Nullable Exception installFailure;

        /// Last Mojang component delegated for download, or null before delegation.
        private @Nullable GameJavaVersion lastDownloadedVersion;

        /// Last prepared archive delegated for installation, or null before delegation.
        private @Nullable LocalJavaArchiveInspection lastInstalledInspection;

        /// Last managed name delegated for installation, or null before delegation.
        private @Nullable String lastInstalledName;

        /// Creates an empty fake backend with matching controlled-copy and prepared inspections.
        private FakeBackend() {
            copiedInspection = inspection(controlledCopy, "runtime", "runtime", javaInfo);
            preparedInspection = inspection(
                    preparedArchive,
                    "java-home",
                    "java-home",
                    javaInfo);
        }

        /// Returns the configured fake platform and records the local read.
        ///
        /// @return fake current platform
        @Override
        public Platform currentPlatform() {
            currentPlatformReads.incrementAndGet();
            return platform;
        }

        /// Returns an immutable copy of configured supported Mojang versions.
        ///
        /// @param ignoredPlatform ignored target platform
        /// @return immutable supported versions
        @Override
        public @Unmodifiable List<GameJavaVersion> supportedMojangVersions(Platform ignoredPlatform) {
            return List.copyOf(supportedVersions);
        }

        /// Returns whether the fake local repository contains one Mojang component.
        ///
        /// @param ignoredPlatform ignored target platform
        /// @param version queried Mojang component
        /// @return configured installation state
        @Override
        public boolean isMojangRuntimeInstalled(
                Platform ignoredPlatform,
                GameJavaVersion version) {
            mojangPresenceChecks.incrementAndGet();
            return installedMojangVersions.contains(version);
        }

        /// Records download task construction and returns a stopped successful task.
        ///
        /// @param ignoredPlatform ignored target platform
        /// @param version selected Mojang component
        /// @return stopped fake download task
        @Override
        public Task<JavaRuntime> downloadMojangRuntime(
                Platform ignoredPlatform,
                GameJavaVersion version) {
            downloadTaskRequests.incrementAndGet();
            lastDownloadedVersion = version;
            return Task.supplyAsync(() -> runtime);
        }

        /// Records archive access and returns the configured direct or controlled-copy inspection.
        ///
        /// @param archiveFile requested archive path
        /// @return configured inspection
        /// @throws IOException when no direct inspection was configured
        @Override
        public LocalJavaArchiveInspection inspectLocalArchive(Path archiveFile) throws IOException {
            archiveInspectionRequests.incrementAndGet();
            if (archiveFile.toAbsolutePath().normalize().equals(controlledCopy)) {
                return copiedInspection;
            }
            LocalJavaArchiveInspection result = directInspection;
            if (result == null) {
                throw new IOException("No fake direct inspection configured");
            }
            return result;
        }

        /// Records source copying and returns the system-controlled fake path.
        ///
        /// @param ignoredArchive ignored user source
        /// @return controlled fake source path
        @Override
        public Path copyToManagedTemporaryArchive(Path ignoredArchive) {
            copyRequests.incrementAndGet();
            return controlledCopy;
        }

        /// Records normalized archive preparation and returns the configured prepared inspection.
        ///
        /// @param ignoredInspection ignored controlled-source inspection
        /// @return prepared fake inspection
        @Override
        public LocalJavaArchiveInspection prepareInstallArchive(
                LocalJavaArchiveInspection ignoredInspection) {
            prepareRequests.incrementAndGet();
            return preparedInspection;
        }

        /// Records best-effort temporary archive deletion.
        ///
        /// @param archiveFile deleted fake archive
        @Override
        public void deleteManagedTemporaryArchive(Path archiveFile) {
            deletedArchives.add(archiveFile);
        }

        /// Returns the fake managed platform root.
        ///
        /// @param ignoredPlatform ignored platform
        /// @return fake managed root
        @Override
        public Path managedPlatformRoot(Platform ignoredPlatform) {
            return managedRoot;
        }

        /// Returns whether a fake named runtime is already present.
        ///
        /// @param ignoredPlatform ignored target platform
        /// @param name queried runtime name
        /// @return configured presence state
        @Override
        public boolean isNamedRuntimeInstalled(Platform ignoredPlatform, String name) {
            return installedNames.contains(name);
        }

        /// Records installation task construction and returns a stopped success or configured failure.
        ///
        /// @param inspectedArchive prepared archive selected for installation
        /// @param name validated managed-runtime name
        /// @return stopped fake install task
        @Override
        public Task<JavaRuntime> installLocalArchive(
                LocalJavaArchiveInspection inspectedArchive,
                String name) {
            installTaskRequests.incrementAndGet();
            lastInstalledInspection = inspectedArchive;
            lastInstalledName = name;
            @Nullable CountDownLatch entered = installTaskCreationEntered;
            if (entered != null) {
                entered.countDown();
            }
            @Nullable CountDownLatch release = installTaskCreationRelease;
            if (release != null) {
                try {
                    if (!release.await(5L, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to release fake install staging");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("Fake install staging was interrupted");
                }
            }
            @Nullable CleanupAwareFakeInstallTask cleanupAwareTask = cleanupAwareInstallTask;
            if (cleanupAwareTask != null) {
                return cleanupAwareTask;
            }
            @Nullable Exception failure = installFailure;
            return Task.supplyAsync(() -> {
                if (failure != null) {
                    throw failure;
                }
                return runtime;
            });
        }
    }
}
