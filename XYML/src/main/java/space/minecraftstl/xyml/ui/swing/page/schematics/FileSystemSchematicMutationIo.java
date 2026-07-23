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
package space.minecraftstl.xyml.ui.swing.page.schematics;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;
import space.minecraftstl.xyml.ui.swing.page.schematics.DefaultSchematicBrowserModel.FileIdentity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static space.minecraftstl.xyml.ui.swing.page.schematics.DefaultSchematicBrowserModel.rootDirectoryMissing;
import static space.minecraftstl.xyml.ui.swing.page.schematics.DefaultSchematicBrowserModel.validateDirectoryChain;
import static space.minecraftstl.xyml.ui.swing.page.schematics.DefaultSchematicBrowserModel.validateRegularFile;

/// Production no-follow import, child creation, and recursive deletion implementation.
@NotNullByDefault
final class FileSystemSchematicMutationIo implements DefaultSchematicBrowserModel.MutationIo {
    /// Prefix reserved for private import staging files.
    private static final String IMPORT_TEMPORARY_PREFIX = ".xyml-litematic-import-";

    /// Prefix reserved for private delete-isolation paths.
    private static final String DELETE_ISOLATION_PREFIX = ".xyml-delete-";

    /// Suffix shared by private mutation artifacts.
    private static final String PRIVATE_ARTIFACT_SUFFIX = ".tmp";

    /// No-op used for post-commit validation that must ignore model cancellation.
    private static final Runnable NO_CANCELLATION_CHECK = () -> {
    };

    /// Production no-op after private temporary-file creation.
    private static final TemporaryFileCheckpoint NO_TEMPORARY_CHECKPOINT = temporary -> {
    };

    /// Production no-op after delete-target isolation.
    private static final DeleteIsolationCheckpoint NO_DELETE_CHECKPOINT = isolated -> {
    };

    /// Immutable lexical and validation boundary.
    private final Path rootDirectory;

    /// Final no-replace move operation, injectable only for deterministic rollback tests.
    private final MoveOperation moveOperation;

    /// Test checkpoint after the owned output handle opens and before source bytes are copied.
    private final TemporaryFileCheckpoint temporaryFileCheckpoint;

    /// Test checkpoint after a delete target is isolated from its stable listed name.
    private final DeleteIsolationCheckpoint deleteIsolationCheckpoint;

    /// Cross-platform identity capture, injectable only to exercise keyless providers.
    private final IdentityCapture identityCapture;

    /// Creates operations rooted at one normalized schematic directory.
    /// @param rootDirectory normalized root boundary
    FileSystemSchematicMutationIo(Path rootDirectory) {
        this(
                rootDirectory,
                (source, destination) -> Files.move(source, destination),
                NO_TEMPORARY_CHECKPOINT,
                NO_DELETE_CHECKPOINT,
                FileIdentity::capture);
    }

    /// Creates operations with an explicit final-move boundary for rollback tests.
    ///
    /// @param rootDirectory normalized root boundary
    /// @param moveOperation no-replace final move operation
    FileSystemSchematicMutationIo(
            Path rootDirectory,
            MoveOperation moveOperation) {
        this(
                rootDirectory,
                moveOperation,
                NO_TEMPORARY_CHECKPOINT,
                NO_DELETE_CHECKPOINT,
                FileIdentity::capture);
    }

    /// Creates operations with explicit transaction checkpoints for deterministic tests.
    ///
    /// @param rootDirectory normalized root boundary
    /// @param moveOperation no-replace final import move operation
    /// @param temporaryFileCheckpoint post-output-open, pre-copy checkpoint
    /// @param deleteIsolationCheckpoint post-delete-isolation checkpoint
    FileSystemSchematicMutationIo(
            Path rootDirectory,
            MoveOperation moveOperation,
            TemporaryFileCheckpoint temporaryFileCheckpoint,
            DeleteIsolationCheckpoint deleteIsolationCheckpoint) {
        this(
                rootDirectory,
                moveOperation,
                temporaryFileCheckpoint,
                deleteIsolationCheckpoint,
                FileIdentity::capture);
    }

    /// Creates operations with explicit transaction checkpoints and identity capture for tests.
    ///
    /// @param rootDirectory normalized root boundary
    /// @param moveOperation no-replace final import move operation
    /// @param temporaryFileCheckpoint post-output-open, pre-copy checkpoint
    /// @param deleteIsolationCheckpoint post-delete-isolation checkpoint
    /// @param identityCapture no-follow cross-platform identity capture
    FileSystemSchematicMutationIo(
            Path rootDirectory,
            MoveOperation moveOperation,
            TemporaryFileCheckpoint temporaryFileCheckpoint,
            DeleteIsolationCheckpoint deleteIsolationCheckpoint,
            IdentityCapture identityCapture) {
        this.rootDirectory = Objects.requireNonNull(rootDirectory, "rootDirectory")
                .toAbsolutePath().normalize();
        this.moveOperation = Objects.requireNonNull(moveOperation, "moveOperation");
        this.temporaryFileCheckpoint = Objects.requireNonNull(
                temporaryFileCheckpoint, "temporaryFileCheckpoint");
        this.deleteIsolationCheckpoint = Objects.requireNonNull(
                deleteIsolationCheckpoint, "deleteIsolationCheckpoint");
        this.identityCapture = Objects.requireNonNull(identityCapture, "identityCapture");
    }

    /// Imports every source after complete preflight and rolls back owned partial writes.
    ///
    /// Cleanup compares no-follow type and file identity immediately before deletion. Portable
    /// [Files] APIs cannot make that comparison and deletion one atomic operation, so this is a
    /// best-effort ownership check across the remaining narrow replacement window.
    @Override
    public void importFiles(
            Path currentDirectory,
            @Unmodifiable List<Path> sourceFiles,
            LoadCancellation cancellation) throws IOException {
        if (sourceFiles.isEmpty()) {
            throw new IOException("At least one Litematic source is required");
        }
        @Unmodifiable List<SourcePlan> sources = preflightSources(sourceFiles, cancellation);
        prepareCurrentDirectory(currentDirectory, true, cancellation);
        @Unmodifiable List<ImportPlan> plans = preflightDestinations(
                currentDirectory, sources, cancellation);
        List<StagedImport> staged = new ArrayList<>(plans.size());
        try {
            for (ImportPlan plan : plans) {
                cancellation.throwIfCancelled();
                validateDirectoryChain(
                        rootDirectory, currentDirectory, cancellation::throwIfCancelled);
                validateImportSource(plan.source());
                Path temporary = newImportTemporaryPath(currentDirectory);
                FileIdentity temporaryIdentity;
                try (InputStream input = Files.newInputStream(
                        plan.source(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                     FileChannel output = FileChannel.open(
                             temporary,
                             StandardOpenOption.CREATE_NEW,
                             StandardOpenOption.WRITE,
                             LinkOption.NOFOLLOW_LINKS)) {
                    staged.add(new StagedImport(plan, temporary, null));
                    BasicFileAttributes temporaryAttributes = Files.readAttributes(
                            temporary, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    temporaryIdentity = identityCapture.capture(temporaryAttributes);
                    staged.set(
                            staged.size() - 1,
                            new StagedImport(plan, temporary, temporaryIdentity));
                    validateSameRegularObject(temporary, temporaryIdentity);
                    temporaryFileCheckpoint.created(temporary);
                    validateSameRegularObject(temporary, temporaryIdentity);
                    input.transferTo(Channels.newOutputStream(output));
                }
                validateImportSource(plan.source());
                BasicFileAttributes copiedAttributes = Files.readAttributes(
                        temporary, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!temporaryIdentity.sameObject(copiedAttributes)
                        || !copiedAttributes.isRegularFile()) {
                    throw new IOException(
                            "Import temporary ownership could not be confirmed: " + temporary);
                }
                staged.set(
                        staged.size() - 1,
                        new StagedImport(
                                plan, temporary, identityCapture.capture(copiedAttributes)));
            }

            for (StagedImport stagedImport : staged) {
                cancellation.throwIfCancelled();
                FileIdentity stagedIdentity = requireStagedIdentity(stagedImport);
                validateDirectoryChain(
                        rootDirectory, currentDirectory, cancellation::throwIfCancelled);
                requireAbsent(stagedImport.plan().destination());
                validateOwnedRegularFile(
                        stagedImport.temporary(), stagedIdentity);
                moveOperation.move(
                        stagedImport.temporary(), stagedImport.plan().destination());
                validateOwnedRegularFile(
                        stagedImport.plan().destination(), stagedIdentity);
            }

            validateDirectoryChain(
                    rootDirectory, currentDirectory, cancellation::throwIfCancelled);
            for (StagedImport stagedImport : staged) {
                cancellation.throwIfCancelled();
                FileIdentity stagedIdentity = requireStagedIdentity(stagedImport);
                validateOwnedRegularFile(
                        stagedImport.plan().destination(), stagedIdentity);
            }
        } catch (Throwable failure) {
            rollbackImports(staged, failure);
            rethrowMutationFailure(failure);
        }
    }

    /// Creates one absent direct child and removes it if post-write validation fails.
    @Override
    public void createDirectory(
            Path currentDirectory,
            String directoryName,
            LoadCancellation cancellation) throws IOException {
        prepareCurrentDirectory(currentDirectory, true, cancellation);
        Path target = resolveDirectChild(currentDirectory, directoryName);
        requireAbsent(target);
        boolean created = false;
        @Nullable FileIdentity createdIdentity = null;
        try {
            cancellation.throwIfCancelled();
            Files.createDirectory(target);
            created = true;
            BasicFileAttributes attributes = Files.readAttributes(
                    target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            createdIdentity = identityCapture.capture(attributes);
            validateDirectoryChain(
                    rootDirectory, currentDirectory, cancellation::throwIfCancelled);
            validateOwnedDirectory(target, createdIdentity);
        } catch (Throwable failure) {
            if (created) {
                suppressOwnedPathDelete(target, createdIdentity, failure);
            }
            rethrowMutationFailure(failure);
        }
    }

    /// Isolates and then deletes one validated direct child without following child links.
    ///
    /// Cancellation is honored until an atomic same-directory move removes the stable listed name.
    /// Once isolated, cleanup runs to completion even if the model closes. An I/O failure after that
    /// commit point may leave only a private partially removed isolation path because recursive
    /// deletion cannot be rolled back. The no-follow file-identity checks immediately surrounding
    /// isolation are best effort because portable [Files] APIs cannot conditionally move by file key.
    @Override
    public void delete(
            Path currentDirectory,
            DefaultSchematicBrowserModel.DiscoveredEntry entry,
            LoadCancellation cancellation) throws IOException {
        prepareCurrentDirectory(currentDirectory, false, cancellation);
        Path target = entry.path().toAbsolutePath().normalize();
        if (!Objects.equals(target.getParent(), currentDirectory)
                || !target.equals(currentDirectory.resolve(entry.fileName()).normalize())) {
            throw new IOException("Schematic delete target is not a direct child: " + target);
        }
        BasicFileAttributes attributes = Files.readAttributes(
                target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        @Nullable FileIdentity entryIdentity = entry.identity();
        if (attributes.isSymbolicLink()
                || entry.directory() != attributes.isDirectory()
                || (!entry.directory() && !attributes.isRegularFile())
                || entryIdentity == null
                || !entryIdentity.matchesSnapshot(attributes)) {
            throw new IOException("Schematic delete target changed since scanning: " + target);
        }

        validateDirectoryChain(
                rootDirectory, currentDirectory, cancellation::throwIfCancelled);
        cancellation.throwIfCancelled();
        Path isolated = newDeleteIsolationPath(currentDirectory);
        Files.move(target, isolated, StandardCopyOption.ATOMIC_MOVE);
        try {
            BasicFileAttributes isolatedAttributes = Files.readAttributes(
                    isolated, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (isolatedAttributes.isSymbolicLink()
                    || entry.directory() != isolatedAttributes.isDirectory()
                    || (!entry.directory() && !isolatedAttributes.isRegularFile())
                    || !entryIdentity.matchesSnapshot(isolatedAttributes)) {
                throw new IOException(
                        "Schematic delete target changed while being isolated: " + target);
            }
            validateDirectoryChain(rootDirectory, currentDirectory, NO_CANCELLATION_CHECK);
        } catch (Throwable failure) {
            suppressIsolationRestoreFailure(
                    isolated, target, entryIdentity, failure);
            rethrowMutationFailure(failure);
        }

        @Nullable Throwable failure = null;
        try {
            deleteIsolationCheckpoint.isolated(isolated);
        } catch (Throwable checkpointFailure) {
            failure = checkpointFailure;
        }
        try {
            if (entry.directory()) {
                validateOwnedDirectory(isolated, entryIdentity);
                deleteTreeNoFollow(isolated, entryIdentity);
            } else {
                validateOwnedRegularFile(isolated, entryIdentity);
                Files.delete(isolated);
            }
            validateDirectoryChain(rootDirectory, currentDirectory, NO_CANCELLATION_CHECK);
        } catch (Throwable cleanupFailure) {
            if (failure == null) {
                failure = cleanupFailure;
            } else if (cleanupFailure != failure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
        if (failure != null) {
            rethrowMutationFailure(failure);
        }
    }

    /// Creates a missing root only for import or child creation, then validates every component.
    private void prepareCurrentDirectory(
            Path currentDirectory,
            boolean createMissingRoot,
            LoadCancellation cancellation) throws IOException {
        Path normalizedCurrent = currentDirectory.toAbsolutePath().normalize();
        if (!normalizedCurrent.startsWith(rootDirectory)) {
            throw new IOException(
                    "Schematic path escaped its root directory: " + normalizedCurrent);
        }
        cancellation.throwIfCancelled();
        if (normalizedCurrent.equals(rootDirectory) && rootDirectoryMissing(rootDirectory)) {
            if (!createMissingRoot) {
                throw new NoSuchFileException(rootDirectory.toString());
            }
            Files.createDirectories(rootDirectory);
        }
        validateDirectoryChain(
                rootDirectory, normalizedCurrent, cancellation::throwIfCancelled);
    }

    /// Validates the complete source batch and duplicate names before a missing root is created.
    private @Unmodifiable List<SourcePlan> preflightSources(
            @Unmodifiable List<Path> sourceFiles,
            LoadCancellation cancellation) throws IOException {
        List<SourcePlan> plans = new ArrayList<>(sourceFiles.size());
        Set<Path> names = new LinkedHashSet<>();
        for (Path source : sourceFiles) {
            cancellation.throwIfCancelled();
            validateImportSource(source);
            @Nullable Path sourceName = source.getFileName();
            if (sourceName == null) {
                throw new IOException("Litematic source has no file name: " + source);
            }
            Path nameIdentity = rootDirectory.resolve(sourceName.toString()).normalize();
            if (!names.add(nameIdentity)) {
                throw new FileAlreadyExistsException(sourceName.toString());
            }
            plans.add(new SourcePlan(source, sourceName.toString()));
        }
        return List.copyOf(plans);
    }

    /// Resolves and validates all final destinations before creating any private temporary file.
    private @Unmodifiable List<ImportPlan> preflightDestinations(
            Path currentDirectory,
            @Unmodifiable List<SourcePlan> sources,
            LoadCancellation cancellation) throws IOException {
        List<ImportPlan> plans = new ArrayList<>(sources.size());
        for (SourcePlan source : sources) {
            cancellation.throwIfCancelled();
            Path destination = resolveDirectChild(currentDirectory, source.fileName());
            requireAbsent(destination);
            plans.add(new ImportPlan(source.source(), destination));
        }
        validateDirectoryChain(
                rootDirectory, currentDirectory, cancellation::throwIfCancelled);
        return List.copyOf(plans);
    }

    /// Resolves and verifies one direct child path without file-system access.
    private static Path resolveDirectChild(
            Path currentDirectory,
            String childName) throws IOException {
        final Path target;
        try {
            target = currentDirectory.resolve(childName).toAbsolutePath().normalize();
        } catch (InvalidPathException failure) {
            throw new IOException("Invalid schematic child name: " + childName, failure);
        }
        if (!Objects.equals(target.getParent(), currentDirectory)
                || target.equals(currentDirectory)) {
            throw new IOException("Schematic child is not one direct component: " + childName);
        }
        return target;
    }

    /// Validates one no-follow regular source with the required extension.
    private static void validateImportSource(Path source) throws IOException {
        @Nullable Path fileName = source.getFileName();
        if (fileName == null
                || !fileName.toString().toLowerCase(Locale.ROOT).endsWith(".litematic")) {
            throw new IOException("Schematic import source is not a Litematic file: " + source);
        }
        validateRegularFile(source);
    }

    /// Rejects every existing final component, including a broken symbolic link.
    private static void requireAbsent(Path target) throws FileAlreadyExistsException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(target.toString());
        }
    }

    /// Requires one no-follow regular path to retain an exact captured file-system identity.
    private static void validateOwnedRegularFile(
            Path path,
            FileIdentity expectedIdentity) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()
                || attributes.isSymbolicLink()
                || !expectedIdentity.matchesSnapshot(attributes)) {
            throw new IOException(
                    "Import file ownership could not be confirmed: " + path);
        }
    }

    /// Returns one successfully captured staged identity or fails an inconsistent transaction.
    private static FileIdentity requireStagedIdentity(StagedImport stagedImport) throws IOException {
        @Nullable FileIdentity identity = stagedImport.identity();
        if (identity == null) {
            throw new IOException(
                    "Import temporary identity was not captured: " + stagedImport.temporary());
        }
        return identity;
    }

    /// Requires one regular path to remain the same object while mutable metadata may change.
    private static void validateSameRegularObject(
            Path path,
            FileIdentity expectedIdentity) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!expectedIdentity.sameObject(attributes)) {
            throw new IOException(
                    "Import file ownership could not be confirmed: " + path);
        }
    }

    /// Requires one no-follow directory path to retain an exact captured file-system identity.
    private static void validateOwnedDirectory(
            Path path,
            FileIdentity expectedIdentity) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory()
                || attributes.isSymbolicLink()
                || !expectedIdentity.matchesSnapshot(attributes)) {
            throw new IOException(
                    "Directory ownership could not be confirmed: " + path);
        }
    }

    /// Returns an absent private sibling used to commit deletion by same-directory rename.
    private static Path newDeleteIsolationPath(Path currentDirectory) {
        Path isolated;
        do {
            isolated = currentDirectory.resolve(
                    DELETE_ISOLATION_PREFIX + UUID.randomUUID() + PRIVATE_ARTIFACT_SUFFIX);
        } while (Files.exists(isolated, LinkOption.NOFOLLOW_LINKS));
        return isolated;
    }

    /// Returns one absent random import path for atomic `CREATE_NEW` channel creation.
    private static Path newImportTemporaryPath(Path currentDirectory) {
        Path temporary;
        do {
            temporary = currentDirectory.resolve(
                    IMPORT_TEMPORARY_PREFIX + UUID.randomUUID() + PRIVATE_ARTIFACT_SUFFIX);
        } while (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS));
        return temporary;
    }

    /// Returns whether a file name is one exact launcher-owned mutation artifact.
    ///
    /// Near matches remain visible so ordinary user directories are never hidden merely because
    /// their names share an internal prefix.
    ///
    /// @param fileName direct-child file name
    /// @return whether the name contains a reserved prefix, UUID token, and private suffix
    static boolean isPrivateArtifactName(String fileName) {
        Objects.requireNonNull(fileName, "fileName");
        return hasPrivateArtifactShape(fileName, IMPORT_TEMPORARY_PREFIX)
                || hasPrivateArtifactShape(fileName, DELETE_ISOLATION_PREFIX);
    }

    /// Validates one reserved private name shape without touching the file system.
    ///
    /// @param fileName candidate direct-child name
    /// @param prefix reserved operation prefix
    /// @return whether the middle token is one canonical UUID
    private static boolean hasPrivateArtifactShape(String fileName, String prefix) {
        if (!fileName.startsWith(prefix) || !fileName.endsWith(PRIVATE_ARTIFACT_SUFFIX)) {
            return false;
        }
        int tokenEnd = fileName.length() - PRIVATE_ARTIFACT_SUFFIX.length();
        String token = fileName.substring(prefix.length(), tokenEnd);
        try {
            return UUID.fromString(token).toString().equals(token);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /// Recursively deletes a committed isolation directory without following symbolic links.
    private static void deleteTreeNoFollow(
            Path root,
            FileIdentity expectedRootIdentity) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            /// Validates each traversed directory before descending.
            @Override
            public FileVisitResult preVisitDirectory(
                    Path directory,
                    BasicFileAttributes attributes) throws IOException {
                if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
                    throw new IOException(
                            "Schematic deletion encountered a linked directory: " + directory);
                }
                if (directory.equals(root)
                        && !expectedRootIdentity.matchesSnapshot(attributes)) {
                    throw new IOException(
                            "Delete isolation ownership could not be confirmed: " + root);
                }
                return FileVisitResult.CONTINUE;
            }

            /// Deletes a file or child symbolic link without following it.
            @Override
            public FileVisitResult visitFile(
                    Path file,
                    BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            /// Propagates traversal failures without replacing their identity.
            @Override
            public FileVisitResult visitFileFailed(
                    Path file,
                    IOException failure) throws IOException {
                throw failure;
            }

            /// Deletes one emptied directory after all direct children are handled.
            @Override
            public FileVisitResult postVisitDirectory(
                    Path directory,
                    @Nullable IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                if (directory.equals(root)) {
                    BasicFileAttributes attributes = Files.readAttributes(
                            root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!expectedRootIdentity.sameObject(attributes)) {
                        throw new IOException(
                                "Delete isolation ownership could not be confirmed: " + root);
                    }
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /// Removes only transaction-owned final paths plus still-owned staged paths.
    private static void rollbackImports(
            @Unmodifiable List<StagedImport> staged,
            Throwable failure) {
        for (int index = staged.size() - 1; index >= 0; index--) {
            StagedImport stagedImport = staged.get(index);
            suppressOwnedPathDelete(
                    stagedImport.plan().destination(), stagedImport.identity(), failure);
        }
        for (int index = staged.size() - 1; index >= 0; index--) {
            StagedImport stagedImport = staged.get(index);
            suppressOwnedPathDelete(
                    stagedImport.temporary(), stagedImport.identity(), failure);
        }
    }

    /// Deletes a cleanup path only when no-follow type and identity still prove ownership.
    private static void suppressOwnedPathDelete(
            Path path,
            @Nullable FileIdentity expectedIdentity,
            Throwable failure) {
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (expectedIdentity == null || !expectedIdentity.sameObject(attributes)) {
                failure.addSuppressed(new IOException(
                        "Cleanup was not completed because ownership could not be confirmed: "
                                + path));
                return;
            }
            Files.delete(path);
        } catch (Throwable cleanupFailure) {
            if (cleanupFailure != failure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    /// Restores an isolated but unverified target and suppresses restoration failure.
    private static void suppressIsolationRestoreFailure(
            Path isolated,
            Path target,
            FileIdentity expectedIdentity,
            Throwable failure) {
        try {
            if (!Files.exists(isolated, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    isolated, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!expectedIdentity.matchesSnapshot(attributes)) {
                failure.addSuppressed(new IOException(
                        "Isolation restore was not completed because ownership could not be confirmed: "
                                + isolated));
                return;
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                failure.addSuppressed(new FileAlreadyExistsException(target.toString()));
                return;
            }
            Files.move(isolated, target);
        } catch (Throwable restoreFailure) {
            if (restoreFailure != failure) {
                failure.addSuppressed(restoreFailure);
            }
        }
    }

    /// Rethrows known mutation failures without replacing their identity.
    private static void rethrowMutationFailure(Throwable failure) throws IOException {
        if (failure instanceof IOException ioFailure) {
            throw ioFailure;
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IOException("Unexpected schematic mutation failure", failure);
    }

    /// Moves one private temporary file to one absent final path without replacement.
    @FunctionalInterface
    @NotNullByDefault
    interface MoveOperation {
        /// Moves the source and preserves any failure identity.
        ///
        /// @param source private temporary source
        /// @param destination absent final destination
        /// @throws IOException when the final move fails
        void move(Path source, Path destination) throws IOException;
    }

    /// Captures one cross-platform identity from no-follow basic attributes.
    @FunctionalInterface
    @NotNullByDefault
    interface IdentityCapture {
        /// Creates an immutable identity, optionally omitting provider file keys in tests.
        ///
        /// @param attributes no-follow basic attributes
        /// @return captured identity
        FileIdentity capture(BasicFileAttributes attributes);
    }

    /// Test seam invoked after one owned temporary output handle opens and before copying.
    @FunctionalInterface
    @NotNullByDefault
    interface TemporaryFileCheckpoint {
        /// Observes or replaces one private temporary path while its owned output handle is open.
        ///
        /// @param temporary newly created private temporary file
        /// @throws IOException when deterministic test setup fails
        void created(Path temporary) throws IOException;
    }

    /// Test seam invoked after a delete target commits to its private isolation path.
    @FunctionalInterface
    @NotNullByDefault
    interface DeleteIsolationCheckpoint {
        /// Pauses or observes committed deletion before recursive cleanup begins.
        ///
        /// @param isolated committed private isolation path
        /// @throws IOException when deterministic test setup fails
        void isolated(Path isolated) throws IOException;
    }

    /// One fully preflighted import source and portable destination file name.
    /// @param source normalized no-follow source
    /// @param fileName source file-name component
    @NotNullByDefault
    private record SourcePlan(Path source, String fileName) {
    }

    /// One fully preflighted import source and final destination.
    /// @param source normalized no-follow source
    /// @param destination absent direct child destination
    @NotNullByDefault
    private record ImportPlan(Path source, Path destination) {
    }

    /// One imported source copied into a private temporary destination.
    /// @param plan original source and final destination
    /// @param temporary existing private temporary path
    /// @param identity copied temporary identity, or null when capture failed after creation
    @NotNullByDefault
    private record StagedImport(
            ImportPlan plan,
            Path temporary,
            @Nullable FileIdentity identity) {
    }
}
