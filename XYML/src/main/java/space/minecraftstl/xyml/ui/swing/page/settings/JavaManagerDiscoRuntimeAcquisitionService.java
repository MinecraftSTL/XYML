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
import space.minecraftstl.xyml.download.java.JavaPackageType;
import space.minecraftstl.xyml.download.java.disco.DiscoJavaDistribution;
import space.minecraftstl.xyml.download.java.disco.DiscoJavaRemoteVersion;
import space.minecraftstl.xyml.download.java.disco.DiscoRemoteFileInfo;
import space.minecraftstl.xyml.download.java.disco.DiscoResult;
import space.minecraftstl.xyml.java.JavaInfo;
import space.minecraftstl.xyml.java.JavaRuntime;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.platform.OperatingSystem;
import space.minecraftstl.xyml.util.platform.Platform;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;

/// Implements lazy Disco discovery and routes verified downloads through the managed Java installation boundary.
///
/// Network tasks are constructed only from inside the returned task's execution path. Remote package metadata is
/// treated as untrusted: URI schemes, package identity, checksum algorithm, checksum width, selected platform, Java
/// major version, and JavaFX flags are validated before archive bytes can reach the existing bounded preflight.
@NotNullByDefault
public final class JavaManagerDiscoRuntimeAcquisitionService
        implements DiscoJavaRuntimeAcquisitionService {
    /// Maximum metadata URI length accepted from a Disco response.
    private static final int MAXIMUM_URI_CHARACTERS = 8_192;

    /// Maximum decoded bytes accepted for one Disco package-information response.
    private static final long MAXIMUM_PACKAGE_INFORMATION_RESPONSE_BYTES = 1024L * 1024L;

    /// Maximum decoded bytes and characters accepted for one checksum-file response.
    private static final long MAXIMUM_CHECKSUM_RESPONSE_BYTES = 8L * 1024L;

    /// Backend owning Disco network tasks and the managed archive pipeline.
    private final DiscoJavaRuntimeAcquisitionBackend backend;

    /// Successful immutable directories cached for this service session by exact platform and distribution.
    private final Map<CatalogKey, VersionCatalog> versionCatalogCache = new HashMap<>();

    /// Produces one value while receiving the current task's cooperative cancellation callback.
    @FunctionalInterface
    @NotNullByDefault
    private interface CancellableValueOperation<T> {
        /// Performs one cancellable backend operation.
        ///
        /// @param cancellationCheck current task cancellation callback
        /// @return produced value
        /// @throws Exception when the operation fails
        T execute(JavaRuntimeAcquisitionBackend.CancellationCheck cancellationCheck) throws Exception;
    }

    /// Produces one stopped dependency task after the owning composed task starts.
    @FunctionalInterface
    @NotNullByDefault
    private interface DeferredTaskOperation<T> {
        /// Creates the stopped dependency task.
        ///
        /// @return stopped dependency task
        /// @throws Exception when validating or constructing the dependency fails
        Task<T> create() throws Exception;
    }

    /// Executes one synchronous archive stage while exposing task cancellation to its bounded loops.
    @NotNullByDefault
    private static final class CancellableValueTask<T> extends Task<T> {
        /// Backend operation executed by this task.
        private final CancellableValueOperation<T> operation;

        /// Creates a named cancellable value task.
        ///
        /// @param name task display name
        /// @param operation backend operation
        private CancellableValueTask(String name, CancellableValueOperation<T> operation) {
            this.operation = Objects.requireNonNull(operation, "operation");
            setName(name);
        }

        /// Executes the operation and stores its non-null result.
        @Override
        public void execute() throws Exception {
            setResult(Objects.requireNonNull(
                    operation.execute(this::requireNotCancelled),
                    "cancellable operation returned no result"));
        }

        /// Throws when the attached executor has received cancellation.
        private void requireNotCancelled() {
            if (isCancelled()) {
                throw new CancellationException("Cancelled by user");
            }
        }
    }

    /// Dynamically creates one dependency and guarantees idempotent cleanup after dependency processing.
    @NotNullByDefault
    private static final class CleanupComposedTask<T> extends Task<T> {
        /// Deferred dependency construction operation.
        private final DeferredTaskOperation<T> operation;

        /// Idempotent acquisition-owned temporary cleanup.
        private final Runnable cleanup;

        /// Dynamically created dependency, or null before task execution.
        private @Nullable Task<T> dependency;

        /// Creates a named composed task with unconditional post-dependency cleanup.
        ///
        /// @param name task display name
        /// @param operation deferred dependency construction
        /// @param cleanup idempotent temporary cleanup
        private CleanupComposedTask(
                String name,
                DeferredTaskOperation<T> operation,
                Runnable cleanup) {
            this.operation = Objects.requireNonNull(operation, "operation");
            this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
            setName(name);
        }

        /// Constructs the dependency and mirrors its eventual result.
        @Override
        public void execute() throws Exception {
            try {
                dependency = Objects.requireNonNull(
                        operation.create(),
                        "deferred operation returned no task");
                dependency.storeTo(this::setResult);
            } catch (Exception | Error failure) {
                cleanup.run();
                throw failure;
            }
        }

        /// Returns the dynamically created dependency after execution.
        ///
        /// @return immutable empty or singleton dependency collection
        @Override
        public @Unmodifiable Collection<Task<?>> getDependencies() {
            return dependency == null
                    ? Collections.emptySet()
                    : Collections.singleton(dependency);
        }

        /// Requests cleanup after dependency success, failure, or cancellation.
        ///
        /// @return always true
        @Override
        public boolean doPostExecute() {
            return true;
        }

        /// Deletes all temporary archive paths still owned by this acquisition chain.
        @Override
        public void postExecute() {
            cleanup.run();
        }
    }

    /// Normalized checksum metadata passed to the bounded archive download task.
    ///
    /// @param algorithm normalized JCA algorithm name
    /// @param hexadecimal expected lowercase hexadecimal digest
    @NotNullByDefault
    private record VerifiedChecksum(String algorithm, String hexadecimal) {
        /// Rejects absent normalized checksum data.
        private VerifiedChecksum {
            algorithm = Objects.requireNonNull(algorithm, "algorithm");
            hexadecimal = Objects.requireNonNull(hexadecimal, "hexadecimal");
        }
    }

    /// Validated package metadata paired with its normalized direct download URI.
    ///
    /// @param metadata sole package-information result
    /// @param downloadUri normalized HTTP or HTTPS download URI
    @NotNullByDefault
    private record VerifiedFileInformation(
            DiscoRemoteFileInfo metadata,
            String downloadUri) {
        /// Rejects absent validated file information.
        private VerifiedFileInformation {
            metadata = Objects.requireNonNull(metadata, "metadata");
            downloadUri = Objects.requireNonNull(downloadUri, "downloadUri");
        }
    }

    /// Direct archive location paired with its verified checksum metadata.
    ///
    /// @param downloadUri normalized HTTP or HTTPS download URI
    /// @param checksum verified checksum metadata
    @NotNullByDefault
    private record VerifiedDownload(
            String downloadUri,
            VerifiedChecksum checksum) {
        /// Rejects absent verified download data.
        private VerifiedDownload {
            downloadUri = Objects.requireNonNull(downloadUri, "downloadUri");
            checksum = Objects.requireNonNull(checksum, "checksum");
        }
    }

    /// Cache identity for one exact Disco package directory.
    ///
    /// @param platform exact target platform
    /// @param distribution selected distribution
    @NotNullByDefault
    private record CatalogKey(
            Platform platform,
            DiscoJavaDistribution distribution) {
        /// Rejects absent cache identity data.
        private CatalogKey {
            platform = Objects.requireNonNull(platform, "platform");
            distribution = Objects.requireNonNull(distribution, "distribution");
        }
    }

    /// Deeply immutable successful Disco directory reused across package-type choices.
    ///
    /// @param versions immutable package and Java-major directory
    @NotNullByDefault
    private record VersionCatalog(
            @Unmodifiable Map<JavaPackageType,
                    @Unmodifiable Map<Integer, DiscoJavaRemoteVersion>> versions) {
        /// Rejects absent catalog data.
        private VersionCatalog {
            Objects.requireNonNull(versions, "versions");
        }

        /// Deeply snapshots every package map before it can enter the session cache.
        ///
        /// @param versions mutable or immutable package directory returned by Core
        /// @return deeply immutable catalog
        private static VersionCatalog copyOf(
                Map<JavaPackageType, ? extends Map<Integer, DiscoJavaRemoteVersion>> versions) {
            Objects.requireNonNull(versions, "versions");
            EnumMap<JavaPackageType, @Unmodifiable Map<Integer, DiscoJavaRemoteVersion>> snapshot =
                    new EnumMap<>(JavaPackageType.class);
            for (Map.Entry<JavaPackageType, ? extends Map<Integer, DiscoJavaRemoteVersion>> entry
                    : versions.entrySet()) {
                JavaPackageType packageType = Objects.requireNonNull(entry.getKey(), "catalog package type");
                Map<Integer, DiscoJavaRemoteVersion> packageVersions = Objects.requireNonNull(
                        entry.getValue(),
                        "catalog package versions");
                snapshot.put(
                        packageType,
                        Collections.unmodifiableMap(new TreeMap<>(packageVersions)));
            }
            return new VersionCatalog(Collections.unmodifiableMap(snapshot));
        }

        /// Returns one package directory or an immutable empty map when the package is unavailable.
        ///
        /// @param packageType selected package type
        /// @return immutable Java-major directory
        private @Unmodifiable Map<Integer, DiscoJavaRemoteVersion> packageVersions(
                JavaPackageType packageType) {
            return versions.getOrDefault(packageType, Map.of());
        }
    }

    /// Downloaded and normalized temporary archives owned by one acquisition chain.
    @NotNullByDefault
    private static final class TemporaryArchiveTracker {
        /// Managed download task, or null before its construction.
        private @Nullable Task<Path> downloadTask;

        /// Managed downloaded archive, or null before download completion.
        private @Nullable Path downloadedArchive;

        /// Normalized install archive, or null before preparation completion.
        private @Nullable Path preparedArchive;

        /// Records the managed download task before it can start.
        ///
        /// @param task stopped managed download task
        private synchronized void downloading(Task<Path> task) {
            downloadTask = Objects.requireNonNull(task, "task");
        }

        /// Records the managed downloaded archive.
        ///
        /// @param archive managed downloaded archive
        private synchronized void downloaded(Path archive) {
            downloadedArchive = Objects.requireNonNull(archive, "archive");
        }

        /// Records the normalized install archive.
        ///
        /// @param archive normalized install archive
        private synchronized void prepared(Path archive) {
            preparedArchive = Objects.requireNonNull(archive, "archive");
        }

        /// Best-effort deletes both temporary archives without deleting the same path twice.
        ///
        /// @param backend backend owning temporary archive cleanup
        private synchronized void cleanup(DiscoJavaRuntimeAcquisitionBackend backend) {
            @Nullable Path prepared = preparedArchive;
            @Nullable Path downloaded = downloadedArchive;
            @Nullable Task<Path> currentDownloadTask = downloadTask;
            if (downloaded == null && currentDownloadTask != null) {
                downloaded = currentDownloadTask.getResult();
            }
            preparedArchive = null;
            downloadedArchive = null;
            downloadTask = null;
            if (prepared != null) {
                backend.deleteTemporaryArchive(prepared);
            }
            if (downloaded != null && !downloaded.equals(prepared)) {
                backend.deleteTemporaryArchive(downloaded);
            }
        }
    }

    /// Creates the production service backed by the configured download provider and managed Java repository.
    public JavaManagerDiscoRuntimeAcquisitionService() {
        this(new DiscoJavaRuntimeAcquisitionProcessBackend());
    }

    /// Creates a service around an injected backend for deterministic tests.
    ///
    /// @param backend acquisition backend
    JavaManagerDiscoRuntimeAcquisitionService(DiscoJavaRuntimeAcquisitionBackend backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    /// Returns the exact platform supplied by the local backend.
    ///
    /// @return current system platform
    @Override
    public Platform platform() {
        return backend.currentPlatform();
    }

    /// Filters the static Disco distribution matrix for the current platform without performing network access.
    ///
    /// @return immutable supported distribution list
    @Override
    public @Unmodifiable List<DiscoJavaDistribution> supportedDistributions() {
        Platform currentPlatform = backend.currentPlatform();
        return List.of(DiscoJavaDistribution.values()).stream()
                .filter(distribution -> distribution.isSupport(currentPlatform))
                .toList();
    }

    /// Returns only JDK and JRE choices, excluding both JavaFX-bundled package variants.
    ///
    /// @param distribution explicitly selected distribution
    /// @return immutable non-JavaFX package type list
    @Override
    public @Unmodifiable List<JavaPackageType> supportedPackageTypes(
            DiscoJavaDistribution distribution) {
        DiscoJavaDistribution selectedDistribution = Objects.requireNonNull(distribution, "distribution");
        Platform currentPlatform = backend.currentPlatform();
        requireSupportedDistribution(currentPlatform, selectedDistribution);
        return selectedDistribution.getSupportedPackageTypes().stream()
                .filter(packageType -> !packageType.isJavaFXBundled())
                .sorted((left, right) -> Integer.compare(left.ordinal(), right.ordinal()))
                .toList();
    }

    /// Creates a deferred Disco fetch and applies the legacy LTS, latest-major, and Java 16 retention policy.
    ///
    /// @param distribution explicitly selected distribution
    /// @param packageType explicitly selected non-JavaFX package type
    /// @return stopped task yielding an immutable newest-first version list
    @Override
    public Task<@Unmodifiable List<DiscoJavaRemoteVersion>> loadVersions(
            DiscoJavaDistribution distribution,
            JavaPackageType packageType) {
        DiscoJavaDistribution selectedDistribution = Objects.requireNonNull(distribution, "distribution");
        JavaPackageType selectedPackageType = Objects.requireNonNull(packageType, "packageType");
        return Task.composeAsync("Load third-party Java versions", () -> {
            Platform currentPlatform = backend.currentPlatform();
            requireSupportedSelection(currentPlatform, selectedDistribution, selectedPackageType);
            CatalogKey catalogKey = new CatalogKey(currentPlatform, selectedDistribution);
            @Nullable VersionCatalog cachedCatalog = cachedCatalog(catalogKey);
            if (cachedCatalog != null) {
                return Task.completed(filterVersions(
                        cachedCatalog.packageVersions(selectedPackageType),
                        currentPlatform,
                        selectedDistribution,
                        selectedPackageType));
            }
            return backend.fetchVersions(selectedDistribution, currentPlatform)
                    .thenApplyAsync(versions -> {
                        VersionCatalog catalog = cacheSuccessfulCatalog(
                                catalogKey,
                                VersionCatalog.copyOf(Objects.requireNonNull(
                                        versions,
                                        "Disco version result")));
                        return filterVersions(
                                catalog.packageVersions(selectedPackageType),
                                currentPlatform,
                                selectedDistribution,
                                selectedPackageType);
                    });
        });
    }

    /// Derives the prior launcher's stable distribution-version-package naming convention without selecting it.
    ///
    /// @param distribution explicitly selected distribution
    /// @param packageType explicitly selected non-JavaFX package type
    /// @param version explicitly selected remote version
    /// @return deterministic suggested managed-runtime name
    @Override
    public String suggestedInstallName(
            DiscoJavaDistribution distribution,
            JavaPackageType packageType,
            DiscoJavaRemoteVersion version) {
        DiscoJavaDistribution selectedDistribution = Objects.requireNonNull(distribution, "distribution");
        JavaPackageType selectedPackageType = Objects.requireNonNull(packageType, "packageType");
        DiscoJavaRemoteVersion selectedVersion = Objects.requireNonNull(version, "version");
        requireNonJavaFxPackageType(selectedDistribution, selectedPackageType);
        requireVersionPackageIdentity(selectedDistribution, selectedPackageType, selectedVersion);

        String javaVersion = Objects.requireNonNull(selectedVersion.getJavaVersion(), "version.javaVersion");
        int buildIndex = javaVersion.indexOf('+');
        if (buildIndex > 0) {
            javaVersion = javaVersion.substring(0, buildIndex);
        }
        String portableVersion = javaVersion.replaceAll("[^a-zA-Z0-9._-]", "-");
        return selectedDistribution.getApiParameter()
                + "-" + portableVersion
                + "-" + selectedPackageType.name().toLowerCase(Locale.ROOT);
    }

    /// Applies the shared managed-runtime name rules before the UI enables installation.
    ///
    /// @param name proposed managed-runtime name
    /// @return exact validation status
    @Override
    public JavaRuntimeInstallNameStatus validateInstallName(String name) {
        return backend.validateInstallName(
                backend.currentPlatform(),
                Objects.requireNonNull(name, "name"));
    }

    /// Builds a fully deferred package-metadata, checksum, download, archive-preflight, and publication chain.
    ///
    /// @param distribution explicitly selected distribution
    /// @param packageType explicitly selected non-JavaFX package type
    /// @param version explicitly selected remote version
    /// @param installName validated managed-runtime name chosen by the user
    /// @return stopped acquisition task
    @Override
    public Task<JavaRuntime> install(
            DiscoJavaDistribution distribution,
            JavaPackageType packageType,
            DiscoJavaRemoteVersion version,
            String installName) {
        DiscoJavaDistribution selectedDistribution = Objects.requireNonNull(distribution, "distribution");
        JavaPackageType selectedPackageType = Objects.requireNonNull(packageType, "packageType");
        DiscoJavaRemoteVersion selectedVersion = Objects.requireNonNull(version, "version");
        String selectedInstallName = Objects.requireNonNull(installName, "installName");
        TemporaryArchiveTracker tracker = new TemporaryArchiveTracker();

        return new CleanupComposedTask<>("Install third-party Java runtime", () -> {
            Platform currentPlatform = backend.currentPlatform();
            requireSupportedSelection(currentPlatform, selectedDistribution, selectedPackageType);
            requireSelectedVersion(
                    currentPlatform,
                    selectedDistribution,
                    selectedPackageType,
                    selectedVersion);

            String packageInformationUri = requireHttpUri(
                    Objects.requireNonNull(selectedVersion.getLinks(), "version.links").pkgInfoUri(),
                    "package information URI");
            Task<JavaRuntime> pipeline = backend.fetchText(
                            packageInformationUri,
                            MAXIMUM_PACKAGE_INFORMATION_RESPONSE_BYTES)
                    .thenApplyAsync(json -> parseFileInformation(
                            Objects.requireNonNull(json, "package information response"),
                            selectedVersion))
                    .thenComposeAsync(fileInformation -> checksumTask(
                            Objects.requireNonNull(fileInformation, "fileInformation")))
                    .thenComposeAsync(download -> downloadTask(
                            selectedVersion,
                            Objects.requireNonNull(download, "download"),
                            tracker))
                    .thenComposeAsync(downloadedArchive -> prepareAndInstallTask(
                            currentPlatform,
                            selectedVersion,
                            selectedInstallName,
                            Objects.requireNonNull(downloadedArchive, "downloadedArchive"),
                            tracker));

            return pipeline;
        }, () -> tracker.cleanup(backend));
    }

    /// Creates the direct or checksum-URI resolution task after package metadata has been validated.
    ///
    /// @param fileInformation validated package metadata and download URI
    /// @return stopped checksum resolution task
    /// @throws IOException when checksum metadata is absent or unsupported
    private Task<VerifiedDownload> checksumTask(
            VerifiedFileInformation fileInformation) throws IOException {
        DiscoRemoteFileInfo metadata = fileInformation.metadata();
        String algorithm = normalizeChecksumAlgorithm(metadata.checksumType());
        @Nullable String directChecksum = metadata.checksum();
        if (directChecksum != null && !directChecksum.isBlank()) {
            return Task.completed(new VerifiedDownload(
                    fileInformation.downloadUri(),
                    verifyChecksumToken(algorithm, directChecksum)));
        }

        String checksumUri = requireHttpUri(metadata.checksumUri(), "checksum URI");
        return backend.fetchText(checksumUri, MAXIMUM_CHECKSUM_RESPONSE_BYTES)
                .thenApplyAsync(response -> new VerifiedDownload(
                        fileInformation.downloadUri(),
                        verifyChecksumToken(
                                algorithm,
                                firstChecksumToken(Objects.requireNonNull(
                                        response,
                                        "checksum response")))));
    }

    /// Creates the bounded download task and records its successful managed temporary result.
    ///
    /// @param version explicitly selected remote version
    /// @param download verified download URI and checksum metadata
    /// @param tracker acquisition-owned temporary archive tracker
    /// @return stopped download and tracking task
    private Task<Path> downloadTask(
            DiscoJavaRemoteVersion version,
            VerifiedDownload download,
            TemporaryArchiveTracker tracker) {
        String archiveSuffix = "." + Objects.requireNonNull(version.getArchiveType(), "version.archiveType");
        Task<Path> task = backend.downloadArchive(
                download.downloadUri(),
                archiveSuffix,
                download.checksum().algorithm(),
                download.checksum().hexadecimal());
        tracker.downloading(task);
        return task
                .thenApplyAsync(path -> {
                    Path archive = Objects.requireNonNull(path, "downloaded archive");
                    tracker.downloaded(archive);
                    return archive;
                });
    }

    /// Creates the cancellable archive preflight and normalized publication task.
    ///
    /// @param expectedPlatform selected target platform
    /// @param version selected Disco version
    /// @param installName proposed managed-runtime name
    /// @param downloadedArchive managed downloaded archive
    /// @param tracker acquisition-owned temporary archive tracker
    /// @return stopped preparation and installation task
    private Task<JavaRuntime> prepareAndInstallTask(
            Platform expectedPlatform,
            DiscoJavaRemoteVersion version,
            String installName,
            Path downloadedArchive,
            TemporaryArchiveTracker tracker) {
        @Unmodifiable Map<String, Object> updateMetadata = Map.of(
                "type", "disco",
                "info", version);
        return new CancellableValueTask<>("Validate third-party Java archive", cancellationCheck -> {
            LocalJavaArchiveInspection downloadedInspection = backend.inspectArchive(
                    downloadedArchive,
                    cancellationCheck);
            requireDownloadedRuntimeIdentity(expectedPlatform, version, downloadedInspection.javaInfo());
            LocalJavaArchiveInspection preparedInspection = backend.prepareInstallArchive(
                    downloadedInspection,
                    cancellationCheck);
            tracker.prepared(preparedInspection.archiveFile());
            requireDownloadedRuntimeIdentity(expectedPlatform, version, preparedInspection.javaInfo());
            requireValidInstallName(
                    backend.validateInstallName(expectedPlatform, installName),
                    installName);
            return preparedInspection;
        }).thenComposeAsync(preparedInspection -> backend.installArchive(
                Objects.requireNonNull(preparedInspection, "preparedInspection"),
                installName,
                updateMetadata));
    }

    /// Parses one exact Disco package-information result and validates its selected package identity.
    ///
    /// @param json package-information JSON
    /// @param version selected remote version
    /// @return the sole validated file-information record and normalized direct download URI
    /// @throws IOException when the response is malformed, ambiguous, or inconsistent
    private VerifiedFileInformation parseFileInformation(
            String json,
            DiscoJavaRemoteVersion version) throws IOException {
        DiscoResult<DiscoRemoteFileInfo> result;
        try {
            result = JsonUtils.fromNonNullJson(
                    json,
                    DiscoResult.typeOf(DiscoRemoteFileInfo.class));
        } catch (RuntimeException failure) {
            throw new IOException("Invalid Disco package information", failure);
        }
        @Nullable List<DiscoRemoteFileInfo> fileInformation = result.getResult();
        if (fileInformation == null || fileInformation.size() != 1) {
            throw new IOException("Disco package information must contain exactly one result");
        }
        DiscoRemoteFileInfo information = Objects.requireNonNull(
                fileInformation.get(0),
                "Disco package information result");
        @Nullable String expectedFileName = version.getFileName();
        @Nullable String actualFileName = information.fileName();
        if (isBlank(actualFileName)
                || (expectedFileName != null
                && !expectedFileName.isBlank()
                && !expectedFileName.equals(actualFileName))) {
            throw new IOException("Disco package filename does not match the selected version");
        }
        String downloadUri = requireHttpUri(information.directDownloadUri(), "download URI");
        normalizeChecksumAlgorithm(information.checksumType());
        return new VerifiedFileInformation(information, downloadUri);
    }

    /// Applies the prior UI's one-version-per-major retention policy to a selected non-JavaFX package map.
    ///
    /// @param versions raw Core Disco version map
    /// @param platform selected platform
    /// @param distribution selected distribution
    /// @param packageType selected package type
    /// @return immutable newest-first filtered version list
    private static @Unmodifiable List<DiscoJavaRemoteVersion> filterVersions(
            @Unmodifiable Map<Integer, DiscoJavaRemoteVersion> selectedVersions,
            Platform platform,
            DiscoJavaDistribution distribution,
            JavaPackageType packageType) {
        if (selectedVersions.isEmpty()) {
            return List.of();
        }

        List<DiscoJavaRemoteVersion> validVersions = new ArrayList<>();
        for (DiscoJavaRemoteVersion version : selectedVersions.values()) {
            if (version != null && isSelectedVersion(platform, distribution, packageType, version)) {
                validVersions.add(version);
            }
        }
        if (validVersions.isEmpty()) {
            return List.of();
        }

        int latestMajor = validVersions.stream()
                .mapToInt(DiscoJavaRemoteVersion::getJdkVersion)
                .max()
                .orElseThrow();
        return validVersions.stream()
                .filter(version -> version.isLTS()
                        || version.getJdkVersion() == latestMajor
                        || version.getJdkVersion() == 16)
                .sorted((left, right) -> Integer.compare(right.getJdkVersion(), left.getJdkVersion()))
                .toList();
    }

    /// Returns a successful immutable catalog cached during this service session.
    ///
    /// @param key exact platform and distribution key
    /// @return cached catalog, or null before a successful fetch
    private synchronized @Nullable VersionCatalog cachedCatalog(CatalogKey key) {
        return versionCatalogCache.get(key);
    }

    /// Stores one successful immutable catalog while preserving a prior winner from a concurrent request.
    ///
    /// Failed fetches never reach this method and therefore remain retryable.
    ///
    /// @param key exact platform and distribution key
    /// @param catalog successful immutable directory
    /// @return cached catalog selected for subsequent package filtering
    private synchronized VersionCatalog cacheSuccessfulCatalog(
            CatalogKey key,
            VersionCatalog catalog) {
        return versionCatalogCache.computeIfAbsent(key, ignored -> catalog);
    }

    /// Requires a selected distribution and package type to be available for the platform.
    ///
    /// @param platform current platform
    /// @param distribution selected distribution
    /// @param packageType selected package type
    private static void requireSupportedSelection(
            Platform platform,
            DiscoJavaDistribution distribution,
            JavaPackageType packageType) {
        requireSupportedDistribution(platform, distribution);
        requireNonJavaFxPackageType(distribution, packageType);
    }

    /// Requires one distribution to support the exact selected platform.
    ///
    /// @param platform current platform
    /// @param distribution selected distribution
    private static void requireSupportedDistribution(
            Platform platform,
            DiscoJavaDistribution distribution) {
        if (!distribution.isSupport(platform)) {
            throw new IllegalArgumentException(
                    distribution.getDisplayName() + " does not support " + platform);
        }
    }

    /// Requires a package type to be supported and not bundle JavaFX.
    ///
    /// @param distribution selected distribution
    /// @param packageType selected package type
    private static void requireNonJavaFxPackageType(
            DiscoJavaDistribution distribution,
            JavaPackageType packageType) {
        if (packageType.isJavaFXBundled()) {
            throw new IllegalArgumentException("JavaFX-bundled Java packages are not supported");
        }
        if (!distribution.getSupportedPackageTypes().contains(packageType)) {
            throw new IllegalArgumentException(
                    distribution.getDisplayName() + " does not provide " + packageType.getDisplayName());
        }
    }

    /// Requires one version to match the explicit distribution, package, platform, and archive selection.
    ///
    /// @param platform selected platform
    /// @param distribution selected distribution
    /// @param packageType selected package type
    /// @param version selected remote version
    private static void requireSelectedVersion(
            Platform platform,
            DiscoJavaDistribution distribution,
            JavaPackageType packageType,
            DiscoJavaRemoteVersion version) {
        if (!isSelectedVersion(platform, distribution, packageType, version)) {
            throw new IllegalArgumentException("Disco version does not match the explicit selection");
        }
    }

    /// Tests one version against all explicit selection dimensions and the JavaFX-free product policy.
    ///
    /// @param platform selected platform
    /// @param distribution selected distribution
    /// @param packageType selected package type
    /// @param version candidate remote version
    /// @return whether the version is a valid exact selection
    private static boolean isSelectedVersion(
            Platform platform,
            DiscoJavaDistribution distribution,
            JavaPackageType packageType,
            DiscoJavaRemoteVersion version) {
        if (version.isJavaFXBundled()
                || !version.isDirectlyDownloadable()
                || version.getJdkVersion() != version.getMajorVersion()
                || !distribution.testVersion(version)) {
            return false;
        }
        if (!expectedArchiveType(platform).equals(version.getArchiveType())
                || !platform.getOperatingSystem().getCheckedName().equals(version.getOperatingSystem())
                || !platform.getArchitecture().getCheckedName().equals(version.getArchitecture())) {
            return false;
        }
        if (platform.getOperatingSystem() == OperatingSystem.LINUX
                && !"glibc".equals(version.getLibCType())) {
            return false;
        }
        return packageType.isJDK()
                ? "jdk".equals(version.getPackageType())
                : "jre".equals(version.getPackageType());
    }

    /// Requires a version to match only its distribution and package identity for deterministic name derivation.
    ///
    /// @param distribution selected distribution
    /// @param packageType selected package type
    /// @param version selected remote version
    private static void requireVersionPackageIdentity(
            DiscoJavaDistribution distribution,
            JavaPackageType packageType,
            DiscoJavaRemoteVersion version) {
        boolean packageMatches = packageType.isJDK()
                ? "jdk".equals(version.getPackageType())
                : "jre".equals(version.getPackageType());
        if (version.isJavaFXBundled()
                || !distribution.testVersion(version)
                || !packageMatches) {
            throw new IllegalArgumentException("Disco version does not match the explicit package selection");
        }
    }

    /// Returns the archive type requested from Disco for one operating system.
    ///
    /// @param platform selected platform
    /// @return `zip` on Windows and `tar.gz` elsewhere
    private static String expectedArchiveType(Platform platform) {
        return platform.getOperatingSystem() == OperatingSystem.WINDOWS ? "zip" : "tar.gz";
    }

    /// Requires the inspected runtime to retain the selected platform and Java feature version.
    ///
    /// @param expectedPlatform selected platform
    /// @param version selected remote version
    /// @param javaInfo inspected archive Java metadata
    /// @throws IOException when downloaded contents do not match the selected package
    private static void requireDownloadedRuntimeIdentity(
            Platform expectedPlatform,
            DiscoJavaRemoteVersion version,
            JavaInfo javaInfo) throws IOException {
        if (!expectedPlatform.equals(javaInfo.getPlatform())) {
            throw new IOException(
                    "Downloaded Java platform mismatch: expected " + expectedPlatform
                            + " but got " + javaInfo.getPlatform());
        }
        if (javaInfo.getParsedVersion() != version.getJdkVersion()) {
            throw new IOException(
                    "Downloaded Java version mismatch: expected " + version.getJdkVersion()
                            + " but got " + javaInfo.getVersion());
        }
    }

    /// Converts one name validation result into a stable installation failure.
    ///
    /// @param status exact name status
    /// @param name proposed managed-runtime name
    /// @throws IOException when the name cannot be installed
    private static void requireValidInstallName(
            JavaRuntimeInstallNameStatus status,
            String name) throws IOException {
        if (status == JavaRuntimeInstallNameStatus.VALID) {
            return;
        }
        throw new IOException("Invalid managed Java runtime name " + name + ": " + status);
    }

    /// Normalizes and whitelists the only checksum algorithms accepted from Disco metadata.
    ///
    /// @param rawAlgorithm raw checksum type, or null when metadata omitted it
    /// @return normalized JCA algorithm name
    /// @throws IOException when the algorithm is absent or unsupported
    private static String normalizeChecksumAlgorithm(
            @Nullable String rawAlgorithm) throws IOException {
        if (isBlank(rawAlgorithm)) {
            throw new IOException("Disco package metadata has no checksum type");
        }
        return switch (rawAlgorithm.trim().toLowerCase(Locale.ROOT)) {
            case "sha1", "sha-1" -> "SHA-1";
            case "sha256", "sha-256" -> "SHA-256";
            case "md5" -> "MD5";
            default -> throw new IOException("Unsupported Disco checksum type: " + rawAlgorithm);
        };
    }

    /// Validates exact hexadecimal width and normalizes one checksum token.
    ///
    /// @param algorithm normalized JCA algorithm
    /// @param rawChecksum raw checksum token
    /// @return immutable verified checksum metadata
    /// @throws IOException when the checksum has an invalid width or character
    private static VerifiedChecksum verifyChecksumToken(
            String algorithm,
            String rawChecksum) throws IOException {
        String checksum = rawChecksum.trim().toLowerCase(Locale.ROOT);
        int expectedCharacters = switch (algorithm) {
            case "MD5" -> 32;
            case "SHA-1" -> 40;
            case "SHA-256" -> 64;
            default -> throw new IOException("Unsupported normalized checksum algorithm: " + algorithm);
        };
        if (checksum.length() != expectedCharacters) {
            throw new IOException("Invalid " + algorithm + " checksum length");
        }
        for (int index = 0; index < checksum.length(); index++) {
            char character = checksum.charAt(index);
            boolean hexadecimal = character >= '0' && character <= '9'
                    || character >= 'a' && character <= 'f';
            if (!hexadecimal) {
                throw new IOException("Invalid " + algorithm + " checksum character");
            }
        }
        return new VerifiedChecksum(algorithm, checksum);
    }

    /// Extracts the leading checksum token from a bounded checksum-file response.
    ///
    /// @param response raw checksum response
    /// @return leading token
    /// @throws IOException when the response is blank or exceeds its metadata ceiling
    private static String firstChecksumToken(String response) throws IOException {
        if (response.length() > MAXIMUM_CHECKSUM_RESPONSE_BYTES) {
            throw new IOException("Disco checksum response exceeds its character limit");
        }
        String normalized = response.trim();
        if (normalized.isEmpty()) {
            throw new IOException("Disco checksum response is empty");
        }
        int end = 0;
        while (end < normalized.length() && !Character.isWhitespace(normalized.charAt(end))) {
            end++;
        }
        return normalized.substring(0, end);
    }

    /// Requires one HTTP or HTTPS URI and returns its normalized text.
    ///
    /// @param rawUri raw URI, or null when remote metadata omitted it
    /// @param description diagnostic URI role
    /// @return normalized URI string
    private static String requireHttpUri(
            @Nullable String rawUri,
            String description) throws IOException {
        if (rawUri == null || rawUri.isBlank() || rawUri.length() > MAXIMUM_URI_CHARACTERS) {
            throw new IOException("Invalid Disco " + description);
        }
        String candidate = rawUri.trim();
        URI uri;
        try {
            uri = URI.create(candidate);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid Disco " + description, failure);
        }
        @Nullable String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || uri.getHost() == null) {
            throw new IOException("Disco " + description + " must use HTTP or HTTPS");
        }
        return uri.toString();
    }

    /// Returns whether optional remote metadata is null, empty, or whitespace-only.
    ///
    /// @param value optional remote text
    /// @return whether the value has no non-whitespace characters
    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
