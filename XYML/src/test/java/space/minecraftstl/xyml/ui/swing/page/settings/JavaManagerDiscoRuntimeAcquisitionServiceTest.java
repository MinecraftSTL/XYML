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

import kala.compress.archivers.zip.UnixStat;
import kala.compress.archivers.zip.ZipArchiveEntry;
import kala.compress.archivers.zip.ZipArchiveOutputStream;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.download.java.JavaPackageType;
import space.minecraftstl.xyml.download.java.disco.DiscoJavaDistribution;
import space.minecraftstl.xyml.download.java.disco.DiscoJavaRemoteVersion;
import space.minecraftstl.xyml.download.java.disco.DiscoRemoteFileInfo;
import space.minecraftstl.xyml.java.JavaInfo;
import space.minecraftstl.xyml.java.JavaManifest;
import space.minecraftstl.xyml.java.JavaRuntime;
import space.minecraftstl.xyml.task.BoundedTextFetchTask;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.DigestUtils;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.platform.OperatingSystem;
import space.minecraftstl.xyml.util.platform.Platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies lazy JavaFX-free Disco discovery, checksum validation, safe pipeline routing, and manifest metadata.
@NotNullByDefault
final class JavaManagerDiscoRuntimeAcquisitionServiceTest {
    /// Deterministic verified SHA-256 used by fake archive inspections.
    private static final String TEST_ARCHIVE_SHA256 = "0".repeat(64);

    /// Expected service ceiling for one Disco package-information response.
    private static final long PACKAGE_INFORMATION_LIMIT_BYTES = 1024L * 1024L;

    /// Expected service ceiling for one checksum-file response.
    private static final long CHECKSUM_LIMIT_BYTES = 8L * 1024L;

    /// Per-test directory used for real download and publication fixtures.
    @TempDir
    private @Nullable Path temporaryDirectory;

    /// Lists supported distributions locally and removes both JavaFX-bundled package variants.
    @Test
    void listsSupportedChoicesWithoutJavaFxPackages() {
        FakeBackend backend = new FakeBackend();
        JavaManagerDiscoRuntimeAcquisitionService service =
                new JavaManagerDiscoRuntimeAcquisitionService(backend);

        @Unmodifiable List<DiscoJavaDistribution> distributions = service.supportedDistributions();
        @Unmodifiable List<JavaPackageType> packageTypes =
                service.supportedPackageTypes(DiscoJavaDistribution.LIBERICA);

        assertAll(
                () -> assertTrue(distributions.contains(DiscoJavaDistribution.LIBERICA)),
                () -> assertEquals(List.of(JavaPackageType.JRE, JavaPackageType.JDK), packageTypes),
                () -> assertThrows(
                        UnsupportedOperationException.class,
                        () -> packageTypes.add(JavaPackageType.JDKFX)),
                () -> assertEquals(0, backend.fetchVersionRequests.get()),
                () -> assertEquals(0, backend.fetchTextRequests.size()));
    }

    /// Defers the Core Disco request until task start and preserves the former LTS/latest/16 descending policy.
    @Test
    void loadsFilteredVersionsOnlyAfterTaskStarts() {
        FakeBackend backend = new FakeBackend();
        backend.versions.get(JavaPackageType.JDK).put(11, remoteVersion(11, false, false));
        backend.versions.get(JavaPackageType.JDK).put(16, remoteVersion(16, false, false));
        backend.versions.get(JavaPackageType.JDK).put(17, remoteVersion(17, true, false));
        backend.versions.get(JavaPackageType.JDK).put(18, remoteVersion(18, false, false));
        backend.versions.get(JavaPackageType.JDK).put(21, remoteVersion(21, false, false));
        backend.versions.get(JavaPackageType.JDK).put(25, remoteVersion(25, false, true));
        JavaManagerDiscoRuntimeAcquisitionService service =
                new JavaManagerDiscoRuntimeAcquisitionService(backend);

        Task<@Unmodifiable List<DiscoJavaRemoteVersion>> task = service.loadVersions(
                DiscoJavaDistribution.LIBERICA,
                JavaPackageType.JDK);

        assertAll(
                () -> assertEquals(Task.TaskState.READY, task.getState()),
                () -> assertEquals(0, backend.fetchVersionRequests.get()));
        assertTrue(task.test(), () -> "Version fetch failed: " + task.getException());
        @Unmodifiable List<DiscoJavaRemoteVersion> result = Objects.requireNonNull(task.getResult());
        assertAll(
                () -> assertEquals(List.of(21, 17, 16), result.stream()
                        .map(DiscoJavaRemoteVersion::getJdkVersion)
                        .toList()),
                () -> assertEquals(1, backend.fetchVersionRequests.get()),
                () -> assertThrows(
                        UnsupportedOperationException.class,
                        () -> result.add(remoteVersion(8, true, false))));
    }

    /// Reuses one successful platform-distribution catalog when the package choice changes.
    @Test
    void cachesSuccessfulCatalogAcrossPackageTypes() {
        FakeBackend backend = new FakeBackend();
        backend.versions.get(JavaPackageType.JDK).put(21, remoteVersion(21, true, false));
        backend.versions.get(JavaPackageType.JRE).put(
                17,
                remoteVersion(17, true, false, JavaPackageType.JRE));
        JavaManagerDiscoRuntimeAcquisitionService service =
                new JavaManagerDiscoRuntimeAcquisitionService(backend);

        Task<@Unmodifiable List<DiscoJavaRemoteVersion>> jdkTask = service.loadVersions(
                DiscoJavaDistribution.LIBERICA,
                JavaPackageType.JDK);
        assertTrue(jdkTask.test(), () -> "JDK catalog fetch failed: " + jdkTask.getException());
        backend.versions.get(JavaPackageType.JRE).clear();
        backend.versions.get(JavaPackageType.JRE).put(
                19,
                remoteVersion(19, false, false, JavaPackageType.JRE));

        Task<@Unmodifiable List<DiscoJavaRemoteVersion>> jreTask = service.loadVersions(
                DiscoJavaDistribution.LIBERICA,
                JavaPackageType.JRE);
        assertAll(
                () -> assertEquals(Task.TaskState.READY, jreTask.getState()),
                () -> assertEquals(1, backend.fetchVersionRequests.get()));
        assertTrue(jreTask.test(), () -> "Cached JRE filtering failed: " + jreTask.getException());
        assertAll(
                () -> assertEquals(List.of(17), Objects.requireNonNull(jreTask.getResult()).stream()
                        .map(DiscoJavaRemoteVersion::getJdkVersion)
                        .toList()),
                () -> assertEquals(1, backend.fetchVersionRequests.get()));
    }

    /// Leaves a failed catalog request uncached so a later explicit retry can fetch again.
    @Test
    void retriesCatalogAfterFailedFetch() {
        FakeBackend backend = new FakeBackend();
        backend.fetchVersionsFailure = new IOException("simulated directory failure");
        JavaManagerDiscoRuntimeAcquisitionService service =
                new JavaManagerDiscoRuntimeAcquisitionService(backend);

        Task<@Unmodifiable List<DiscoJavaRemoteVersion>> failedTask = service.loadVersions(
                DiscoJavaDistribution.LIBERICA,
                JavaPackageType.JDK);
        assertFalse(failedTask.test());
        assertAll(
                () -> assertInstanceOf(IOException.class, failedTask.getException()),
                () -> assertEquals(1, backend.fetchVersionRequests.get()));

        backend.fetchVersionsFailure = null;
        backend.versions.get(JavaPackageType.JDK).put(21, remoteVersion(21, true, false));
        Task<@Unmodifiable List<DiscoJavaRemoteVersion>> retryTask = service.loadVersions(
                DiscoJavaDistribution.LIBERICA,
                JavaPackageType.JDK);

        assertTrue(retryTask.test(), () -> "Catalog retry failed: " + retryTask.getException());
        assertAll(
                () -> assertEquals(List.of(21), Objects.requireNonNull(retryTask.getResult()).stream()
                        .map(DiscoJavaRemoteVersion::getJdkVersion)
                        .toList()),
                () -> assertEquals(2, backend.fetchVersionRequests.get()));
    }

    /// Rejects a JavaFX-bundled package choice before constructing any remote task.
    @Test
    void rejectsJavaFxPackageBeforeFetchingVersions() {
        FakeBackend backend = new FakeBackend();
        JavaManagerDiscoRuntimeAcquisitionService service =
                new JavaManagerDiscoRuntimeAcquisitionService(backend);

        Task<@Unmodifiable List<DiscoJavaRemoteVersion>> task = service.loadVersions(
                DiscoJavaDistribution.LIBERICA,
                JavaPackageType.JDKFX);

        assertFalse(task.test());
        assertAll(
                () -> assertInstanceOf(IllegalArgumentException.class, task.getException()),
                () -> assertEquals(0, backend.fetchVersionRequests.get()));
    }

    /// Exposes current-platform name validation so the UI can keep install disabled before network work starts.
    @Test
    void validatesInstallNameWithoutNetworkAccess() {
        FakeBackend backend = new FakeBackend();
        backend.nameStatus = JavaRuntimeInstallNameStatus.ALREADY_INSTALLED;
        JavaManagerDiscoRuntimeAcquisitionService service =
                new JavaManagerDiscoRuntimeAcquisitionService(backend);

        JavaRuntimeInstallNameStatus status = service.validateInstallName("liberica-21-jdk");

        assertAll(
                () -> assertEquals(JavaRuntimeInstallNameStatus.ALREADY_INSTALLED, status),
                () -> assertEquals(backend.platform, backend.validatedPlatform),
                () -> assertEquals("liberica-21-jdk", backend.validatedName),
                () -> assertEquals(0, backend.fetchTextRequests.size()),
                () -> assertEquals(0, backend.downloadRequests.get()));
    }

    /// Routes a directly checksummed package through inspection, normalization, installation, and exact cleanup.
    @Test
    void installsDirectChecksumWithDiscoManifestMetadata() {
        FakeBackend backend = new FakeBackend();
        DiscoJavaRemoteVersion version = remoteVersion(21, true, false);
        backend.textResponses.put(
                version.getLinks().pkgInfoUri(),
                packageInformationJson(
                        version,
                        "sha256",
                        "a".repeat(64),
                        ""));
        JavaManagerDiscoRuntimeAcquisitionService service =
                new JavaManagerDiscoRuntimeAcquisitionService(backend);

        Task<JavaRuntime> task = service.install(
                DiscoJavaDistribution.LIBERICA,
                JavaPackageType.JDK,
                version,
                "liberica-21-jdk");

        assertAll(
                () -> assertEquals(Task.TaskState.READY, task.getState()),
                () -> assertEquals(0, backend.fetchTextRequests.size()),
                () -> assertEquals(0, backend.downloadRequests.get()));
        assertTrue(task.test(), () -> "Disco install failed: " + task.getException());
        assertAll(
                () -> assertSame(backend.runtime, task.getResult()),
                () -> assertEquals(
                        List.of(new TextRequest(
                                version.getLinks().pkgInfoUri(),
                                PACKAGE_INFORMATION_LIMIT_BYTES)),
                        backend.fetchTextRequests),
                () -> assertEquals(version.getLinks().pkgDownloadRedirect(), backend.downloadUri),
                () -> assertEquals(".zip", backend.downloadSuffix),
                () -> assertEquals("SHA-256", backend.downloadAlgorithm),
                () -> assertEquals("a".repeat(64), backend.downloadChecksum),
                () -> assertEquals(1, backend.inspectRequests.get()),
                () -> assertEquals(1, backend.prepareRequests.get()),
                () -> assertEquals(1, backend.installRequests.get()),
                () -> assertEquals("disco", backend.installUpdate.get("type")),
                () -> assertSame(version, backend.installUpdate.get("info")),
                () -> assertEquals(
                        List.of(backend.preparedArchive, backend.downloadedArchive),
                        backend.deletedArchives));
    }

    /// Resolves checksum-file metadata lazily and normalizes the selected MD5 token before download.
    @Test
    void resolvesChecksumUriBeforeDownloading() {
        FakeBackend backend = new FakeBackend();
        DiscoJavaRemoteVersion version = remoteVersion(17, true, false);
        String checksumUri = "https://example.invalid/runtime.md5";
        backend.textResponses.put(
                version.getLinks().pkgInfoUri(),
                packageInformationJson(version, "md5", "", checksumUri));
        backend.textResponses.put(checksumUri, "ABCDEF0123456789ABCDEF0123456789  runtime.zip\n");
        backend.downloadedInspection = inspection(backend.downloadedArchive, 17);
        backend.preparedInspection = inspection(backend.preparedArchive, 17);
        JavaManagerDiscoRuntimeAcquisitionService service =
                new JavaManagerDiscoRuntimeAcquisitionService(backend);

        Task<JavaRuntime> task = service.install(
                DiscoJavaDistribution.LIBERICA,
                JavaPackageType.JDK,
                version,
                "liberica-17-jdk");

        assertTrue(task.test(), () -> "Checksum URI install failed: " + task.getException());
        assertAll(
                () -> assertEquals(
                        List.of(
                                new TextRequest(
                                        version.getLinks().pkgInfoUri(),
                                        PACKAGE_INFORMATION_LIMIT_BYTES),
                                new TextRequest(checksumUri, CHECKSUM_LIMIT_BYTES)),
                        backend.fetchTextRequests),
                () -> assertEquals("MD5", backend.downloadAlgorithm),
                () -> assertEquals("abcdef0123456789abcdef0123456789", backend.downloadChecksum));
    }

    /// Rejects unsupported checksum metadata before archive bytes or temporary paths are requested.
    @Test
    void rejectsUnsupportedChecksumBeforeDownloading() {
        FakeBackend backend = new FakeBackend();
        DiscoJavaRemoteVersion version = remoteVersion(21, true, false);
        backend.textResponses.put(
                version.getLinks().pkgInfoUri(),
                packageInformationJson(version, "sha512", "a".repeat(128), ""));
        JavaManagerDiscoRuntimeAcquisitionService service =
                new JavaManagerDiscoRuntimeAcquisitionService(backend);

        Task<JavaRuntime> task = service.install(
                DiscoJavaDistribution.LIBERICA,
                JavaPackageType.JDK,
                version,
                "liberica-21-jdk");

        assertFalse(task.test());
        assertAll(
                () -> assertInstanceOf(IOException.class, task.getException()),
                () -> assertEquals(0, backend.downloadRequests.get()),
                () -> assertEquals(0, backend.inspectRequests.get()),
                () -> assertTrue(backend.deletedArchives.isEmpty()));
    }

    /// Rejects a downloaded runtime whose release file major differs from the explicit remote version.
    @Test
    void rejectsDownloadedMajorMismatchBeforePreparingOrPublishing() {
        FakeBackend backend = new FakeBackend();
        DiscoJavaRemoteVersion version = remoteVersion(21, true, false);
        backend.textResponses.put(
                version.getLinks().pkgInfoUri(),
                packageInformationJson(version, "sha1", "b".repeat(40), ""));
        backend.downloadedInspection = inspection(backend.downloadedArchive, 17);
        JavaManagerDiscoRuntimeAcquisitionService service =
                new JavaManagerDiscoRuntimeAcquisitionService(backend);

        Task<JavaRuntime> task = service.install(
                DiscoJavaDistribution.LIBERICA,
                JavaPackageType.JDK,
                version,
                "liberica-21-jdk");

        assertFalse(task.test());
        assertAll(
                () -> assertInstanceOf(IOException.class, task.getException()),
                () -> assertEquals(1, backend.inspectRequests.get()),
                () -> assertEquals(0, backend.prepareRequests.get()),
                () -> assertEquals(0, backend.installRequests.get()),
                () -> assertEquals(List.of(backend.downloadedArchive), backend.deletedArchives));
    }

    /// Enforces the byte ceiling while downloading into a task-owned temporary file.
    @Test
    void boundsManagedArchiveDownloadBytes() throws IOException {
        Path directory = Objects.requireNonNull(temporaryDirectory, "temporaryDirectory");
        Path source = directory.resolve("source.zip");
        Files.write(source, "bounded-download".getBytes(StandardCharsets.UTF_8));
        String checksum = DigestUtils.digestToString("SHA-256", source);
        ManagedJavaArchiveDownloadTask task = new ManagedJavaArchiveDownloadTask(
                List.of(source.toUri()),
                ".zip",
                "SHA-256",
                checksum,
                Files.size(source) - 1L);

        assertFalse(task.test());
        assertInstanceOf(Exception.class, task.getException());
    }

    /// Applies the configured byte ceiling to actual non-HTTP text bytes, including missing-length responses.
    @Test
    void boundsActualTextResponseBytes() throws IOException {
        Path directory = Objects.requireNonNull(temporaryDirectory, "temporaryDirectory");
        Path source = directory.resolve("response.txt");
        String contents = "bounded-text-response";
        Files.writeString(source, contents, StandardCharsets.UTF_8);
        long exactBytes = Files.size(source);
        BoundedTextFetchTask exactTask = new BoundedTextFetchTask(List.of(source.toUri()), exactBytes);
        BoundedTextFetchTask oversizedTask = new BoundedTextFetchTask(
                List.of(source.toUri()),
                exactBytes - 1L);

        assertTrue(exactTask.test(), () -> "Exact bounded text fetch failed: " + exactTask.getException());
        assertFalse(oversizedTask.test());
        assertAll(
                () -> assertEquals(contents, exactTask.getResult()),
                () -> assertInstanceOf(IOException.class, oversizedTask.getException()));
    }

    /// Persists caller Disco metadata alongside the publisher's private ownership token in a real manifest.
    @Test
    void publisherPersistsDiscoUpdateMetadata() throws IOException {
        Path directory = Objects.requireNonNull(temporaryDirectory, "temporaryDirectory");
        Path platformRoot = Files.createDirectory(directory.resolve("managed"));
        Path archive = directory.resolve("prepared.zip");
        writeNormalizedJavaArchive(archive, Platform.SYSTEM_PLATFORM, 21);
        Path target = platformRoot.resolve("liberica-21-jdk");
        Path manifest = platformRoot.resolve("liberica-21-jdk.json");
        DiscoJavaRemoteVersion version = remoteVersionForPlatform(
                Platform.SYSTEM_PLATFORM,
                21,
                true,
                false);

        Task<JavaRuntime> task = JavaRuntimeInstallationPublisher.createInstallTask(
                Platform.SYSTEM_PLATFORM,
                "liberica-21-jdk",
                archive,
                platformRoot,
                target,
                manifest,
                Map.of("type", "disco", "info", version));

        assertTrue(task.test(), () -> "Publication failed: " + task.getException());
        JavaManifest published = Objects.requireNonNull(
                JsonUtils.fromJsonFile(manifest, JavaManifest.class),
                "published manifest");
        Map<String, Object> update = Objects.requireNonNull(published.update(), "manifest update");
        assertAll(
                () -> assertEquals("disco", update.get("type")),
                () -> assertTrue(update.containsKey("info")),
                () -> assertTrue(update.containsKey("xyml.acquisitionOwner")),
                () -> assertTrue(Files.isDirectory(target)),
                () -> assertTrue(Files.isRegularFile(manifest)));
    }

    /// Serializes one exact package-information response for the selected remote version.
    ///
    /// @param version selected remote version
    /// @param checksumType checksum type metadata
    /// @param checksum optional direct checksum
    /// @param checksumUri optional checksum-file URI
    /// @return package-information JSON
    private static String packageInformationJson(
            DiscoJavaRemoteVersion version,
            String checksumType,
            String checksum,
            String checksumUri) {
        DiscoRemoteFileInfo information = new DiscoRemoteFileInfo(
                version.getFileName(),
                version.getLinks().pkgDownloadRedirect(),
                checksumType,
                checksum,
                checksumUri);
        return JsonUtils.GSON.toJson(Map.of("result", List.of(information)));
    }

    /// Creates one Windows x86-64 Liberica JDK fixture.
    ///
    /// @param major Java feature version
    /// @param lts whether the fixture is long-term supported
    /// @param javaFxBundled whether the fixture bundles JavaFX
    /// @return deterministic remote version
    private static DiscoJavaRemoteVersion remoteVersion(
            int major,
            boolean lts,
            boolean javaFxBundled) {
        return remoteVersion(major, lts, javaFxBundled, JavaPackageType.JDK);
    }

    /// Creates one Windows x86-64 Liberica fixture for an explicit non-JavaFX package type.
    ///
    /// @param major Java feature version
    /// @param lts whether the fixture is long-term supported
    /// @param javaFxBundled whether the fixture bundles JavaFX
    /// @param packageType explicit JDK or JRE package type
    /// @return deterministic remote version
    private static DiscoJavaRemoteVersion remoteVersion(
            int major,
            boolean lts,
            boolean javaFxBundled,
            JavaPackageType packageType) {
        return remoteVersionForPlatform(
                Platform.WINDOWS_X86_64,
                major,
                lts,
                javaFxBundled,
                packageType);
    }

    /// Creates one deterministic Liberica JDK fixture for an exact platform.
    ///
    /// @param platform fixture platform
    /// @param major Java feature version
    /// @param lts whether the fixture is long-term supported
    /// @param javaFxBundled whether the fixture bundles JavaFX
    /// @return deterministic remote version
    private static DiscoJavaRemoteVersion remoteVersionForPlatform(
            Platform platform,
            int major,
            boolean lts,
            boolean javaFxBundled) {
        return remoteVersionForPlatform(platform, major, lts, javaFxBundled, JavaPackageType.JDK);
    }

    /// Creates one deterministic Liberica fixture for an exact platform and package type.
    ///
    /// @param platform fixture platform
    /// @param major Java feature version
    /// @param lts whether the fixture is long-term supported
    /// @param javaFxBundled whether the fixture bundles JavaFX
    /// @param packageType explicit JDK or JRE package type
    /// @return deterministic remote version
    private static DiscoJavaRemoteVersion remoteVersionForPlatform(
            Platform platform,
            int major,
            boolean lts,
            boolean javaFxBundled,
            JavaPackageType packageType) {
        String archiveType = platform.getOperatingSystem() == OperatingSystem.WINDOWS
                ? "zip"
                : "tar.gz";
        String packageApiName = switch (packageType) {
            case JDK -> "jdk";
            case JRE -> "jre";
            default -> throw new IllegalArgumentException("Unsupported fixture package type " + packageType);
        };
        String fileName = "liberica-" + packageApiName + "-" + major + "." + archiveType;
        return new DiscoJavaRemoteVersion(
                "liberica-" + major,
                archiveType,
                "liberica",
                major,
                major + ".0.2+13",
                major + ".0.2+13",
                major,
                true,
                "ga",
                lts ? "lts" : "sts",
                platform.getOperatingSystem().getCheckedName(),
                platform.getOperatingSystem() == OperatingSystem.LINUX ? "glibc" : "",
                platform.getArchitecture().getCheckedName(),
                "",
                packageApiName,
                javaFxBundled,
                true,
                fileName,
                new DiscoJavaRemoteVersion.Links(
                        "https://example.invalid/packages/" + major,
                        "https://example.invalid/downloads/" + fileName),
                true,
                "yes",
                "",
                "yes",
                "",
                1024L);
    }

    /// Creates a verified fake archive inspection for one Java major.
    ///
    /// @param archive archive path
    /// @param major Java feature version
    /// @return immutable fake inspection
    private static LocalJavaArchiveInspection inspection(Path archive, int major) {
        return new LocalJavaArchiveInspection(
                archive,
                "java-home",
                "java-home",
                new JavaInfo(Platform.WINDOWS_X86_64, major + ".0.2", "BellSoft"),
                1L,
                TEST_ARCHIVE_SHA256);
    }

    /// Writes the minimum normalized archive accepted by the safe extraction publisher.
    ///
    /// @param archive target ZIP
    /// @param platform encoded Java platform
    /// @param major encoded Java feature version
    /// @throws IOException when the fixture cannot be written
    private static void writeNormalizedJavaArchive(
            Path archive,
            Platform platform,
            int major) throws IOException {
        String executable = platform.getOperatingSystem().getJavaExecutable();
        try (ZipArchiveOutputStream output = new ZipArchiveOutputStream(Files.newOutputStream(archive))) {
            writeArchiveEntry(
                    output,
                    "java-home/release",
                    ("OS_NAME=\"" + platform.getOperatingSystem().getCheckedName() + "\"\n"
                            + "OS_ARCH=\"" + platform.getArchitecture().getCheckedName() + "\"\n"
                            + "JAVA_VERSION=\"" + major + ".0.2\"\n"
                            + "IMPLEMENTOR=\"BellSoft\"\n").getBytes(StandardCharsets.UTF_8),
                    UnixStat.FILE_FLAG | UnixStat.DEFAULT_FILE_PERM);
            writeArchiveEntry(
                    output,
                    "java-home/bin/" + executable,
                    new byte[]{0},
                    UnixStat.FILE_FLAG | 0755);
        }
    }

    /// Writes one explicit UNIX-mode ZIP entry.
    ///
    /// @param output destination ZIP
    /// @param name entry name
    /// @param contents entry bytes
    /// @param unixMode UNIX type and permission bits
    /// @throws IOException when the entry cannot be written
    private static void writeArchiveEntry(
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

    /// Records one bounded metadata request.
    ///
    /// @param uri exact request URI
    /// @param maximumBytes configured response ceiling
    @NotNullByDefault
    private record TextRequest(String uri, long maximumBytes) {
        /// Rejects absent URI data.
        private TextRequest {
            uri = Objects.requireNonNull(uri, "uri");
        }
    }

    /// Deterministic backend recording lazy service boundaries without filesystem or network side effects.
    @NotNullByDefault
    private static final class FakeBackend implements DiscoJavaRuntimeAcquisitionBackend {
        /// Fixed supported platform.
        private final Platform platform = Platform.WINDOWS_X86_64;

        /// Mutable per-package fake Core version maps.
        private final EnumMap<JavaPackageType, TreeMap<Integer, DiscoJavaRemoteVersion>> versions =
                new EnumMap<>(JavaPackageType.class);

        /// Fake text response indexed by exact URI.
        private final Map<String, String> textResponses = new java.util.HashMap<>();

        /// Managed downloaded archive returned by the fake download task.
        private final Path downloadedArchive = Path.of("fake-disco-download.zip").toAbsolutePath().normalize();

        /// Normalized archive returned by fake preparation.
        private final Path preparedArchive = Path.of("fake-disco-prepared.zip").toAbsolutePath().normalize();

        /// Runtime returned by fake safe publication.
        private final JavaRuntime runtime = JavaRuntime.of(
                Path.of("fake-managed/bin/java.exe").toAbsolutePath().normalize(),
                new JavaInfo(platform, "21.0.2", "BellSoft"),
                true);

        /// Number of version task construction requests.
        private final AtomicInteger fetchVersionRequests = new AtomicInteger();

        /// Text task construction requests in call order.
        private final List<TextRequest> fetchTextRequests = new ArrayList<>();

        /// Optional version-directory failure returned by the next fetch task.
        private @Nullable IOException fetchVersionsFailure;

        /// Number of archive download task construction requests.
        private final AtomicInteger downloadRequests = new AtomicInteger();

        /// Number of archive inspection requests.
        private final AtomicInteger inspectRequests = new AtomicInteger();

        /// Number of normalized archive preparation requests.
        private final AtomicInteger prepareRequests = new AtomicInteger();

        /// Number of safe publication task construction requests.
        private final AtomicInteger installRequests = new AtomicInteger();

        /// Temporary archive cleanup requests in call order.
        private final List<Path> deletedArchives = new ArrayList<>();

        /// Downloaded archive inspection returned by the fake backend.
        private LocalJavaArchiveInspection downloadedInspection = inspection(downloadedArchive, 21);

        /// Prepared archive inspection returned by the fake backend.
        private LocalJavaArchiveInspection preparedInspection = inspection(preparedArchive, 21);

        /// Configured name validation result.
        private JavaRuntimeInstallNameStatus nameStatus = JavaRuntimeInstallNameStatus.VALID;

        /// Platform used by the most recent name validation request, or null before validation.
        private @Nullable Platform validatedPlatform;

        /// Name used by the most recent validation request, or null before validation.
        private @Nullable String validatedName;

        /// Download URI recorded at task construction, or null before construction.
        private @Nullable String downloadUri;

        /// Archive suffix recorded at task construction, or null before construction.
        private @Nullable String downloadSuffix;

        /// Checksum algorithm recorded at task construction, or null before construction.
        private @Nullable String downloadAlgorithm;

        /// Expected checksum recorded at task construction, or null before construction.
        private @Nullable String downloadChecksum;

        /// Manifest update metadata recorded at install task construction.
        private @Unmodifiable Map<String, Object> installUpdate = Map.of();

        /// Creates package maps for every enum value.
        private FakeBackend() {
            for (JavaPackageType packageType : JavaPackageType.values()) {
                versions.put(packageType, new TreeMap<>());
            }
        }

        /// Returns the fixed test platform.
        ///
        /// @return Windows x86-64
        @Override
        public Platform currentPlatform() {
            return platform;
        }

        /// Records lazy version task construction and returns a defensive map copy.
        ///
        /// @param ignoredDistribution ignored selected distribution
        /// @param ignoredPlatform ignored platform
        /// @return stopped fake version task
        @Override
        public Task<EnumMap<JavaPackageType, TreeMap<Integer, DiscoJavaRemoteVersion>>> fetchVersions(
                DiscoJavaDistribution ignoredDistribution,
                Platform ignoredPlatform) {
            fetchVersionRequests.incrementAndGet();
            EnumMap<JavaPackageType, TreeMap<Integer, DiscoJavaRemoteVersion>> copy =
                    new EnumMap<>(JavaPackageType.class);
            versions.forEach((packageType, values) -> copy.put(packageType, new TreeMap<>(values)));
            @Nullable IOException failure = fetchVersionsFailure;
            return Task.supplyAsync(() -> {
                if (failure != null) {
                    throw failure;
                }
                return copy;
            });
        }

        /// Records a text request and returns its configured response.
        ///
        /// @param uri requested URI
        /// @param maximumBytes configured positive response ceiling
        /// @return stopped fake text task
        @Override
        public Task<String> fetchText(String uri, long maximumBytes) {
            fetchTextRequests.add(new TextRequest(uri, maximumBytes));
            return Task.supplyAsync(() -> {
                @Nullable String response = textResponses.get(uri);
                if (response == null) {
                    throw new IOException("No fake response for " + uri);
                }
                return response;
            });
        }

        /// Records verified download metadata and returns the managed fake path.
        ///
        /// @param uri requested archive URI
        /// @param archiveSuffix parser-significant suffix
        /// @param checksumAlgorithm normalized checksum algorithm
        /// @param checksum expected checksum
        /// @return stopped successful fake download task
        @Override
        public Task<Path> downloadArchive(
                String uri,
                String archiveSuffix,
                String checksumAlgorithm,
                String checksum) {
            downloadRequests.incrementAndGet();
            downloadUri = uri;
            downloadSuffix = archiveSuffix;
            downloadAlgorithm = checksumAlgorithm;
            downloadChecksum = checksum;
            return Task.completed(downloadedArchive);
        }

        /// Returns the configured downloaded inspection and records the request.
        ///
        /// @param archiveFile inspected archive
        /// @param cancellationCheck cancellation callback
        /// @return configured downloaded inspection
        @Override
        public LocalJavaArchiveInspection inspectArchive(
                Path archiveFile,
                JavaRuntimeAcquisitionBackend.CancellationCheck cancellationCheck) {
            cancellationCheck.checkCancelled();
            inspectRequests.incrementAndGet();
            assertEquals(downloadedArchive, archiveFile);
            return downloadedInspection;
        }

        /// Returns the configured normalized inspection and records the request.
        ///
        /// @param inspection source inspection
        /// @param cancellationCheck cancellation callback
        /// @return configured normalized inspection
        @Override
        public LocalJavaArchiveInspection prepareInstallArchive(
                LocalJavaArchiveInspection inspection,
                JavaRuntimeAcquisitionBackend.CancellationCheck cancellationCheck) {
            cancellationCheck.checkCancelled();
            prepareRequests.incrementAndGet();
            assertSame(downloadedInspection, inspection);
            return preparedInspection;
        }

        /// Records local name validation and returns its configured status.
        ///
        /// @param platform validated platform
        /// @param name validated name
        /// @return configured name status
        @Override
        public JavaRuntimeInstallNameStatus validateInstallName(Platform platform, String name) {
            validatedPlatform = platform;
            validatedName = name;
            return nameStatus;
        }

        /// Records immutable update metadata and returns a stopped successful publication task.
        ///
        /// @param inspection normalized archive inspection
        /// @param name managed-runtime name
        /// @param updateMetadata immutable manifest update metadata
        /// @return stopped fake publication task
        @Override
        public Task<JavaRuntime> installArchive(
                LocalJavaArchiveInspection inspection,
                String name,
                @Unmodifiable Map<String, Object> updateMetadata) {
            installRequests.incrementAndGet();
            assertSame(preparedInspection, inspection);
            assertFalse(name.isBlank());
            installUpdate = Map.copyOf(updateMetadata);
            return Task.completed(runtime);
        }

        /// Records temporary archive cleanup.
        ///
        /// @param archiveFile deleted temporary archive
        @Override
        public void deleteTemporaryArchive(Path archiveFile) {
            deletedArchives.add(archiveFile);
        }
    }
}
