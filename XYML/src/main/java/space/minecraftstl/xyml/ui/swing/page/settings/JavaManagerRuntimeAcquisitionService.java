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
import space.minecraftstl.xyml.game.GameJavaVersion;
import space.minecraftstl.xyml.java.JavaInfo;
import space.minecraftstl.xyml.java.JavaRuntime;
import space.minecraftstl.xyml.java.XYMLJavaRepository;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.platform.Platform;
import space.minecraftstl.xyml.util.platform.UnsupportedPlatformException;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;

/// Adapts the process-wide Java manager to a lazy and path-safe Swing acquisition workflow.
///
/// Mojang metadata is limited to [Platform#SYSTEM_PLATFORM] because the established Mojang platform mapper uses the
/// system architecture. Local archives are copied, structurally revalidated, and rewritten into a controlled ZIP
/// before the legacy installer can observe them. This preserves executable and symbolic-link metadata while removing
/// unchecked archive paths and flattening a macOS `Contents/Home` layout into the repository's Java Home layout.
@NotNullByDefault
public final class JavaManagerRuntimeAcquisitionService implements JavaRuntimeAcquisitionService {
    /// Exact legacy-compatible character set accepted for launcher-managed runtime names.
    private static final Pattern INSTALL_NAME_PATTERN = Pattern.compile("[a-zA-Z0-9.\\-_]+");

    /// Windows device stems rejected on every platform so managed archives remain portable.
    private static final @Unmodifiable Set<String> WINDOWS_DEVICE_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    /// Stable root directory emitted into every normalized install archive.
    static final String NORMALIZED_ARCHIVE_ROOT = "java-home";

    /// Production bounds chosen well above ordinary JDK distributions while preventing unbounded archive work.
    private static final ArchiveLimits DEFAULT_ARCHIVE_LIMITS = new ArchiveLimits(
            2L * 1024L * 1024L * 1024L,
            200_000,
            1024L * 1024L * 1024L,
            8L * 1024L * 1024L * 1024L,
            200.0,
            2L * 1024L * 1024L * 1024L);

    /// Backend owning process-wide Java, repository, archive, and temporary-file access.
    private final JavaRuntimeAcquisitionBackend backend;

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

    /// Produces one follow-up task while receiving the current task's cooperative cancellation callback.
    @FunctionalInterface
    @NotNullByDefault
    private interface CancellableTaskOperation<T> {
        /// Performs cancellable staging and returns its follow-up task.
        ///
        /// @param cancellationCheck current task cancellation callback
        /// @return stopped follow-up task
        /// @throws Exception when staging fails
        StagedTask<T> execute(JavaRuntimeAcquisitionBackend.CancellationCheck cancellationCheck) throws Exception;
    }

    /// Follow-up task plus task-owned staging cleanup that must run after dependency processing.
    ///
    /// @param task stopped follow-up task
    /// @param cleanup idempotent staging cleanup
    @NotNullByDefault
    private record StagedTask<T>(Task<T> task, Runnable cleanup) {
        /// Rejects absent staged-task data.
        private StagedTask {
            task = Objects.requireNonNull(task, "task");
            cleanup = Objects.requireNonNull(cleanup, "cleanup");
        }
    }

    /// Optional contract for a follow-up task that can clean state when the outer task never starts it.
    @NotNullByDefault
    interface IncompleteInstallCleanup {
        /// Cleans installation state unless the full outer chain may commit it.
        ///
        /// @param commit whether dependency and outer cancellation state permit keeping the installation
        void cleanupUnlessCommitted(boolean commit);
    }

    /// Value-producing task that exposes executor cancellation to synchronous backend loops.
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

        /// Executes the backend operation and stores its result.
        @Override
        public void execute() throws Exception {
            setResult(operation.execute(this::requireNotCancelled));
        }

        /// Throws when the attached executor has received cancellation.
        private void requireNotCancelled() {
            if (isCancelled()) {
                throw new CancellationException("Cancelled by user");
            }
        }
    }

    /// Dynamically composed task that exposes cancellation during its synchronous staging phase.
    @NotNullByDefault
    private static final class CancellableComposedTask<T> extends Task<T> {
        /// Staging operation that creates the eventual follow-up task.
        private final CancellableTaskOperation<T> operation;

        /// Follow-up task returned after staging, or null before execution.
        private @Nullable Task<T> dependency;

        /// Idempotent staging cleanup, or null before successful staging.
        private @Nullable Runnable stagingCleanup;

        /// Creates a named cancellable composed task.
        ///
        /// @param name task display name
        /// @param operation cancellable staging operation
        private CancellableComposedTask(String name, CancellableTaskOperation<T> operation) {
            this.operation = Objects.requireNonNull(operation, "operation");
            setName(name);
        }

        /// Runs staging and mirrors the eventual follow-up result.
        @Override
        public void execute() throws Exception {
            StagedTask<T> stagedTask = Objects.requireNonNull(
                    operation.execute(this::requireNotCancelled),
                    "cancellable operation returned no staged task");
            dependency = stagedTask.task();
            stagingCleanup = stagedTask.cleanup();
            try {
                requireNotCancelled();
                dependency.storeTo(this::setResult);
            } catch (RuntimeException failure) {
                if (dependency instanceof IncompleteInstallCleanup incompleteInstallCleanup) {
                    incompleteInstallCleanup.cleanupUnlessCommitted(false);
                }
                stagingCleanup.run();
                throw failure;
            }
        }

        /// Returns the staged follow-up task after execution.
        ///
        /// @return immutable empty or singleton dependency collection
        @Override
        public @Unmodifiable Collection<Task<?>> getDependencies() {
            return dependency == null ? Collections.emptySet() : Collections.singleton(dependency);
        }

        /// Requests post-processing so cancellation between staging and dependency startup still cleans owned state.
        ///
        /// @return always true
        @Override
        public boolean doPostExecute() {
            return true;
        }

        /// Cleans incomplete installation ownership and all task-owned staging archives.
        @Override
        public void postExecute() {
            @Nullable Task<T> currentDependency = dependency;
            if (currentDependency instanceof IncompleteInstallCleanup incompleteInstallCleanup) {
                incompleteInstallCleanup.cleanupUnlessCommitted(
                        isDependenciesSucceeded() && !isCancelled());
            }
            @Nullable Runnable currentCleanup = stagingCleanup;
            if (currentCleanup != null) {
                currentCleanup.run();
            }
        }

        /// Throws when the attached executor has received cancellation.
        private void requireNotCancelled() {
            if (isCancelled()) {
                throw new CancellationException("Cancelled by user");
            }
        }
    }

    /// Creates a production service backed by [JavaManager], [DownloadProviders], and the managed repository.
    public JavaManagerRuntimeAcquisitionService() {
        this(new ProcessBackend());
    }

    /// Creates a service around an injected backend for deterministic tests.
    ///
    /// @param backend acquisition backend
    JavaManagerRuntimeAcquisitionService(JavaRuntimeAcquisitionBackend backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    /// Creates a stopped local-read task for current-platform Mojang choices and their installation markers.
    ///
    /// @return stopped immutable snapshot task
    @Override
    public Task<JavaRuntimeAcquisitionSnapshot> loadSnapshot() {
        return Task.supplyAsync("Load Java acquisition options", () -> {
            Platform platform = backend.currentPlatform();
            if (!Platform.SYSTEM_PLATFORM.equals(platform)) {
                return new JavaRuntimeAcquisitionSnapshot(platform, List.of());
            }

            @Unmodifiable List<MojangJavaRuntimeOption> options = backend.supportedMojangVersions(platform).stream()
                    .map(version -> new MojangJavaRuntimeOption(
                            version,
                            backend.isMojangRuntimeInstalled(platform, version)))
                    .toList();
            return new JavaRuntimeAcquisitionSnapshot(platform, options);
        });
    }

    /// Checks the exact supported archive suffixes without accessing the filesystem.
    ///
    /// @param archiveFile candidate local archive path
    /// @return whether the file name ends with `.zip` or `.tar.gz`
    @Override
    public boolean supportsLocalArchive(Path archiveFile) {
        return archiveSuffix(Objects.requireNonNull(archiveFile, "archiveFile")) != null;
    }

    /// Creates a stopped task that resolves one exact supported component before obtaining the real download task.
    ///
    /// @param version selected built-in Mojang runtime version
    /// @return stopped download task
    @Override
    public Task<JavaRuntime> downloadMojangRuntime(GameJavaVersion version) {
        GameJavaVersion requestedVersion = Objects.requireNonNull(version, "version");
        return Task.composeAsync("Download Mojang Java runtime", () -> {
            Platform platform = backend.currentPlatform();
            if (!Platform.SYSTEM_PLATFORM.equals(platform)) {
                throw new UnsupportedPlatformException(
                        "Mojang Java downloads are restricted to the system platform: " + platform);
            }

            @Nullable GameJavaVersion supportedVersion = findSupportedMojangVersion(
                    backend.supportedMojangVersions(platform),
                    requestedVersion);
            if (supportedVersion == null) {
                throw new UnsupportedPlatformException(
                        "Mojang Java " + requestedVersion.majorVersion() + " is unavailable for " + platform);
            }
            if (backend.isMojangRuntimeInstalled(platform, supportedVersion)) {
                throw new FileAlreadyExistsException(supportedVersion.component());
            }
            return backend.downloadMojangRuntime(platform, supportedVersion);
        });
    }

    /// Creates a stopped task that validates the suffix before opening and inspecting the archive.
    ///
    /// @param archiveFile selected local archive
    /// @return stopped archive inspection task
    @Override
    public Task<LocalJavaArchiveInspection> inspectLocalArchive(Path archiveFile) {
        Path selectedArchive = Objects.requireNonNull(archiveFile, "archiveFile");
        return new CancellableValueTask<>("Inspect local Java archive", cancellationCheck -> {
            if (!supportsLocalArchive(selectedArchive)) {
                throw new IllegalArgumentException("Unsupported Java archive: " + selectedArchive);
            }
            return backend.inspectLocalArchive(selectedArchive, cancellationCheck);
        });
    }

    /// Classifies syntax, reserved names, direct-child containment, and current local repository state.
    ///
    /// @param inspection inspected archive metadata
    /// @param name proposed installation name
    /// @return exact validation status
    @Override
    public JavaRuntimeInstallNameStatus validateInstallName(
            LocalJavaArchiveInspection inspection,
            String name) {
        LocalJavaArchiveInspection inspectedArchive = Objects.requireNonNull(inspection, "inspection");
        return validateInstallName(inspectedArchive.javaInfo().getPlatform(), name);
    }

    /// Classifies one managed name for a known platform without requiring archive contents.
    ///
    /// @param platform managed repository platform
    /// @param name proposed installation name
    /// @return exact validation status
    JavaRuntimeInstallNameStatus validateInstallName(Platform platform, String name) {
        Platform selectedPlatform = Objects.requireNonNull(platform, "platform");
        String candidate = Objects.requireNonNull(name, "name");
        if (!INSTALL_NAME_PATTERN.matcher(candidate).matches()) {
            return JavaRuntimeInstallNameStatus.INVALID_CHARACTERS;
        }
        if (candidate.regionMatches(
                true,
                0,
                XYMLJavaRepository.MOJANG_JAVA_PREFIX,
                0,
                XYMLJavaRepository.MOJANG_JAVA_PREFIX.length())) {
            return JavaRuntimeInstallNameStatus.RESERVED_MOJANG_PREFIX;
        }
        if (candidate.equals(".")
                || candidate.equals("..")
                || candidate.endsWith(".")
                || candidate.endsWith(" ")) {
            return JavaRuntimeInstallNameStatus.UNSAFE_PATH;
        }
        if (isWindowsDeviceName(candidate)) {
            return JavaRuntimeInstallNameStatus.RESERVED_PLATFORM_NAME;
        }

        Path platformRoot = backend.managedPlatformRoot(selectedPlatform).toAbsolutePath().normalize();
        Path target;
        try {
            target = platformRoot.resolve(candidate).normalize();
        } catch (InvalidPathException failure) {
            return JavaRuntimeInstallNameStatus.UNSAFE_PATH;
        }
        if (!platformRoot.equals(target.getParent())
                || target.getNameCount() != platformRoot.getNameCount() + 1) {
            return JavaRuntimeInstallNameStatus.UNSAFE_PATH;
        }
        return backend.isNamedRuntimeInstalled(selectedPlatform, candidate)
                ? JavaRuntimeInstallNameStatus.ALREADY_INSTALLED
                : JavaRuntimeInstallNameStatus.VALID;
    }

    /// Creates a stopped copy, reinspection, normalized repack, reserved-target, and installation chain.
    ///
    /// @param inspection previously inspected user archive
    /// @param name proposed managed-runtime name
    /// @return stopped installation task
    @Override
    public Task<JavaRuntime> installLocalArchive(
            LocalJavaArchiveInspection inspection,
            String name) {
        LocalJavaArchiveInspection originalInspection = Objects.requireNonNull(inspection, "inspection");
        String candidate = Objects.requireNonNull(name, "name");
        return new CancellableComposedTask<>("Install local Java archive", cancellationCheck -> {
            requireValidInstallName(validateInstallName(originalInspection, candidate), candidate);
            if (!supportsLocalArchive(originalInspection.archiveFile())) {
                throw new IllegalArgumentException(
                        "Unsupported Java archive: " + originalInspection.archiveFile());
            }
            requireVerifiedArchiveFingerprint(originalInspection);
            cancellationCheck.checkCancelled();

            Path controlledCopy = backend.copyToManagedTemporaryArchive(
                    originalInspection.archiveFile(),
                    cancellationCheck);
            try {
                LocalJavaArchiveInspection copiedInspection = backend.inspectLocalArchive(
                        controlledCopy,
                        cancellationCheck);
                requireSameArchiveIdentity(originalInspection, copiedInspection);

                LocalJavaArchiveInspection preparedInspection = backend.prepareInstallArchive(
                        copiedInspection,
                        cancellationCheck);
                Path preparedArchive = preparedInspection.archiveFile();
                try {
                    requireSameJavaInfo(originalInspection.javaInfo(), preparedInspection.javaInfo());
                    requireValidInstallName(validateInstallName(preparedInspection, candidate), candidate);
                    cancellationCheck.checkCancelled();

                    Task<JavaRuntime> installTask = backend.installLocalArchive(preparedInspection, candidate);
                    return new StagedTask<>(
                            installTask,
                            () -> cleanupTemporaryArchives(controlledCopy, preparedArchive));
                } catch (IOException | RuntimeException failure) {
                    cleanupTemporaryArchives(controlledCopy, preparedArchive);
                    throw failure;
                }
            } catch (IOException | UnsupportedPlatformException | RuntimeException failure) {
                backend.deleteManagedTemporaryArchive(controlledCopy);
                throw failure;
            }
        });
    }

    /// Finds a supported Mojang component by both component identifier and major version.
    ///
    /// [GameJavaVersion#equals(Object)] compares only the major version, so acquisition must perform a stricter
    /// comparison before passing an object to the repository path builder.
    ///
    /// @param supportedVersions canonical supported components
    /// @param requestedVersion caller-supplied component
    /// @return canonical supported component, or `null` when the pair does not match exactly
    private static @Nullable GameJavaVersion findSupportedMojangVersion(
            List<GameJavaVersion> supportedVersions,
            GameJavaVersion requestedVersion) {
        for (GameJavaVersion supportedVersion : supportedVersions) {
            if (supportedVersion.majorVersion() == requestedVersion.majorVersion()
                    && Objects.equals(supportedVersion.component(), requestedVersion.component())) {
                return supportedVersion;
            }
        }
        return null;
    }

    /// Converts one install-name status into the exception expected by a stopped installation task.
    ///
    /// @param status latest validation status
    /// @param name proposed runtime name
    /// @throws IOException when the name collides with existing local state
    private static void requireValidInstallName(
            JavaRuntimeInstallNameStatus status,
            String name) throws IOException {
        switch (status) {
            case VALID -> {
            }
            case INVALID_CHARACTERS -> throw new IllegalArgumentException(
                    "Java runtime name must match [a-zA-Z0-9.\\-_]+");
            case RESERVED_MOJANG_PREFIX -> throw new IllegalArgumentException(
                    "Java runtime name uses the reserved Mojang prefix");
            case RESERVED_PLATFORM_NAME -> throw new IllegalArgumentException(
                    "Java runtime name uses a reserved platform device name");
            case UNSAFE_PATH -> throw new IllegalArgumentException(
                    "Java runtime name does not resolve to one managed child directory");
            case ALREADY_INSTALLED -> throw new FileAlreadyExistsException(name);
        }
    }

    /// Requires the controlled copy to describe the same archive root, Java Home, and Java metadata shown to the user.
    ///
    /// @param expected original user-visible inspection
    /// @param actual controlled-copy inspection
    /// @throws IOException when the source archive changed after inspection
    static void requireSameArchiveIdentity(
            LocalJavaArchiveInspection expected,
            LocalJavaArchiveInspection actual) throws IOException {
        requireVerifiedArchiveFingerprint(expected);
        requireVerifiedArchiveFingerprint(actual);
        if (expected.archiveSize() != actual.archiveSize()
                || !expected.sha256().equals(actual.sha256())) {
            throw new IOException("Java archive contents changed after inspection");
        }
        requireSameJavaInfo(expected.javaInfo(), actual.javaInfo());
        if (!expected.suggestedName().equals(actual.suggestedName())
                || !expected.javaHomeRelativePath().equals(actual.javaHomeRelativePath())) {
            throw new IOException("Java archive layout changed after inspection");
        }
    }

    /// Requires an archive inspection to carry a stable byte length and complete SHA-256.
    ///
    /// @param inspection archive inspection to verify
    /// @throws IOException when the inspection did not originate from stable archive hashing
    private static void requireVerifiedArchiveFingerprint(
            LocalJavaArchiveInspection inspection) throws IOException {
        if (!inspection.hasVerifiedFingerprint()) {
            throw new IOException("Java archive inspection has no verified fingerprint");
        }
    }

    /// Requires two Java metadata objects to describe the same platform, version, and vendor.
    ///
    /// @param expected original Java metadata
    /// @param actual revalidated Java metadata
    /// @throws IOException when the archive contents changed after inspection
    static void requireSameJavaInfo(JavaInfo expected, JavaInfo actual) throws IOException {
        if (!expected.getPlatform().equals(actual.getPlatform())
                || !expected.getVersion().equals(actual.getVersion())
                || !Objects.equals(expected.getVendor(), actual.getVendor())) {
            throw new IOException("Java archive metadata changed after inspection");
        }
    }

    /// Deletes two task-owned temporary archives without deleting the same path twice.
    ///
    /// @param controlledCopy controlled copy of the user archive
    /// @param preparedArchive normalized install archive
    private void cleanupTemporaryArchives(Path controlledCopy, Path preparedArchive) {
        backend.deleteManagedTemporaryArchive(preparedArchive);
        if (!controlledCopy.equals(preparedArchive)) {
            backend.deleteManagedTemporaryArchive(controlledCopy);
        }
    }

    /// Returns a supported archive suffix without touching the filesystem.
    ///
    /// @param archiveFile candidate archive path
    /// @return `.zip`, `.tar.gz`, or `null` for an unsupported file name
    static @Nullable String archiveSuffix(Path archiveFile) {
        @Nullable Path fileName = archiveFile.getFileName();
        if (fileName == null) {
            return null;
        }
        String name = fileName.toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".tar.gz")) {
            return ".tar.gz";
        }
        return name.endsWith(".zip") ? ".zip" : null;
    }

    /// Returns whether a candidate name uses a Windows device stem, including names with extensions.
    ///
    /// @param name candidate managed-runtime name
    /// @return whether the name is platform-reserved
    static boolean isWindowsDeviceName(String name) {
        int extensionIndex = name.indexOf('.');
        String stem = extensionIndex >= 0 ? name.substring(0, extensionIndex) : name;
        return WINDOWS_DEVICE_NAMES.contains(stem.toUpperCase(Locale.ROOT));
    }

    /// Immutable resource ceilings applied before and during local archive processing.
    ///
    /// @param maxArchiveBytes maximum compressed or source archive bytes
    /// @param maxEntries maximum explicit archive entries
    /// @param maxEntryUncompressedBytes maximum actual bytes read from one entry
    /// @param maxTotalUncompressedBytes maximum actual bytes read across all entries
    /// @param maxCompressionRatio maximum uncompressed-to-source byte ratio
    /// @param maxTemporaryArchiveBytes maximum bytes written to any acquisition temporary archive
    @NotNullByDefault
    record ArchiveLimits(
            long maxArchiveBytes,
            int maxEntries,
            long maxEntryUncompressedBytes,
            long maxTotalUncompressedBytes,
            double maxCompressionRatio,
            long maxTemporaryArchiveBytes) {
        /// Rejects non-positive or internally inconsistent archive ceilings.
        ArchiveLimits {
            if (maxArchiveBytes <= 0L
                    || maxEntries <= 0
                    || maxEntryUncompressedBytes <= 0L
                    || maxTotalUncompressedBytes < maxEntryUncompressedBytes
                    || !Double.isFinite(maxCompressionRatio)
                    || maxCompressionRatio < 1.0
                    || maxTemporaryArchiveBytes <= 0L) {
                throw new IllegalArgumentException("Archive limits must be positive and internally consistent");
            }
        }

        /// Computes the bounded expanded-byte ceiling for one source archive.
        ///
        /// @param sourceBytes compressed source byte length
        /// @return maximum permitted expanded temporary bytes
        long expandedTemporaryLimit(long sourceBytes) {
            double ratioBound = sourceBytes * maxCompressionRatio;
            long ratioLimit = ratioBound >= Long.MAX_VALUE
                    ? Long.MAX_VALUE
                    : (long) Math.floor(ratioBound);
            return Math.min(
                    Math.min(maxTotalUncompressedBytes, maxTemporaryArchiveBytes),
                    Math.max(sourceBytes, ratioLimit));
        }
    }

    /// Compatibility entry point preserving the established injected-backend test surface.
    ///
    /// The implementation lives in a package-private class so the service remains focused on task orchestration.
    @NotNullByDefault
    static final class ProcessBackend extends JavaRuntimeAcquisitionProcessBackend {
        /// Creates a production backend with the standard archive resource ceilings.
        ProcessBackend() {
            super(DEFAULT_ARCHIVE_LIMITS);
        }

        /// Creates a production-equivalent backend with injectable limits for focused tests.
        ///
        /// @param archiveLimits resource ceilings
        ProcessBackend(ArchiveLimits archiveLimits) {
            super(archiveLimits);
        }
    }
}
