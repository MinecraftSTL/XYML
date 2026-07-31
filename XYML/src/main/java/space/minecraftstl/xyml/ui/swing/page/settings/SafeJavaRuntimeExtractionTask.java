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
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.java.JavaInfo;
import space.minecraftstl.xyml.java.JavaLocalFiles;
import space.minecraftstl.xyml.java.JavaManifest;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.DigestUtils;
import space.minecraftstl.xyml.util.tree.ArchiveFileTree;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Extracts a validated Java archive without following or overwriting staging filesystem entries.
///
/// The task is intentionally private to the Swing acquisition pipeline. It preserves the legacy Java manifest
/// format while enforcing the publisher's ownership proof before and after every filesystem mutation. All archive
/// directories and files are created exactly once, and regular files are opened with both `CREATE_NEW` and
/// `NOFOLLOW_LINKS`, so a planted file, hard link, or symbolic link is rejected instead of truncated.
@NotNullByDefault
final class SafeJavaRuntimeExtractionTask extends Task<JavaManifest> {
    /// Buffer size used while copying one archive entry.
    private static final int COPY_BUFFER_SIZE = 8192;

    /// Publisher ownership proof for the extraction root.
    private final JavaRuntimeInstallationPublisher.OwnedDirectory ownership;

    /// Controlled normalized archive read by this task.
    private final Path archiveFile;

    /// Platform-root identity captured immediately after staging reservation.
    private final EntryIdentity platformRootIdentity;

    /// Staging-directory identity captured immediately after staging reservation.
    private final EntryIdentity stagingDirectoryIdentity;

    /// Ownership-marker identity captured immediately after staging reservation.
    private final EntryIdentity markerIdentity;

    /// Immutable manifest update metadata.
    private final @Unmodifiable Map<String, Object> update;

    /// Manifest entries produced by successful filesystem mutations.
    private final Map<String, JavaLocalFiles.Local> files = new LinkedHashMap<>();

    /// Archive-relative path segments for the current recursive entry.
    private final List<String> nameStack = new ArrayList<>();

    /// Identities of descendant directories created by this task.
    private final Map<Path, EntryIdentity> createdDirectories = new LinkedHashMap<>();

    /// Reusable byte buffer for streamed archive contents.
    private final byte[] buffer = new byte[COPY_BUFFER_SIZE];

    /// SHA-1 digest required by the existing managed Java manifest schema.
    private final MessageDigest messageDigest = DigestUtils.getDigest("SHA-1");

    /// Creates a stopped extractor bound to one exact staging ownership proof.
    ///
    /// @param ownership current owned staging directory
    /// @param update immutable manifest metadata
    /// @param archiveFile controlled normalized archive
    /// @throws IOException when initial ownership cannot be captured safely
    SafeJavaRuntimeExtractionTask(
            JavaRuntimeInstallationPublisher.OwnedDirectory ownership,
            @Unmodifiable Map<String, Object> update,
            Path archiveFile) throws IOException {
        this.ownership = Objects.requireNonNull(ownership, "ownership");
        this.update = Map.copyOf(Objects.requireNonNull(update, "update"));
        this.archiveFile = Objects.requireNonNull(archiveFile, "archiveFile")
                .toAbsolutePath()
                .normalize();
        JavaRuntimeInstallationPublisher.requireOwnedExtractionDirectory(ownership);
        platformRootIdentity = EntryIdentity.capture(requireDirectory(ownership.platformRoot()));
        stagingDirectoryIdentity = EntryIdentity.capture(requireDirectory(ownership.directory()));
        markerIdentity = EntryIdentity.capture(requireRegularFile(ownership.markerFile()));
        requireExtractionOwnership();
        setName("Extract managed Java runtime into owned staging");
    }

    /// Revalidates ownership, extracts the single Java Home root, and returns its managed-runtime manifest.
    @Override
    public void execute() throws Exception {
        requireExtractionOwnership();
        requireControlledArchive();
        try (ArchiveFileTree<?, ?> tree = ArchiveFileTree.open(archiveFile)) {
            extractTree(tree);
        }
        requireExtractionOwnership();
    }

    /// Parses Java metadata and extracts the only allowed top-level directory.
    ///
    /// @param tree opened normalized archive tree
    /// @param <F> archive format type
    /// @param <E> archive entry type
    /// @throws IOException when the archive or staging filesystem changes unsafely
    private <F, E extends ArchiveEntry> void extractTree(ArchiveFileTree<F, E> tree) throws IOException {
        JavaInfo info = JavaInfo.fromArchive(tree);
        ArchiveFileTree.Dir<E> javaHome = tree.getRoot().getSubDirs().values().iterator().next();
        copyDirectoryContents(tree, javaHome, ownership.directory());
        requireAllCreatedDirectories();
        setResult(new JavaManifest(info, update, Map.copyOf(files)));
    }

    /// Recursively copies one archive directory into a directory already created and owned by this task.
    ///
    /// @param tree opened archive tree
    /// @param directory archive directory
    /// @param destination existing owned destination directory
    /// @param <F> archive format type
    /// @param <E> archive entry type
    /// @throws IOException when an entry collides, escapes, changes identity, or cannot be copied
    private <F, E extends ArchiveEntry> void copyDirectoryContents(
            ArchiveFileTree<F, E> tree,
            ArchiveFileTree.Dir<E> directory,
            Path destination) throws IOException {
        requireNotCancelled();
        requireCreatedDirectoryChain(destination);

        for (Map.Entry<String, E> pair : directory.getFiles().entrySet()) {
            String name = pair.getKey();
            Path child = resolveDirectChild(destination, name);
            nameStack.add(name);
            try {
                copyEntry(tree, pair.getValue(), child);
            } finally {
                nameStack.remove(nameStack.size() - 1);
            }
        }

        for (Map.Entry<String, ArchiveFileTree.Dir<E>> pair : directory.getSubDirs().entrySet()) {
            String name = pair.getKey();
            Path child = resolveDirectChild(destination, name);
            nameStack.add(name);
            try {
                createOwnedDirectory(child);
                files.put(manifestPath(), new JavaLocalFiles.LocalDirectory());
                copyDirectoryContents(tree, pair.getValue(), child);
            } finally {
                nameStack.remove(nameStack.size() - 1);
            }
        }
        requireCreatedDirectoryChain(destination);
    }

    /// Copies one regular file or creates one contained relative symbolic link without replacing an existing entry.
    ///
    /// @param tree opened archive tree
    /// @param entry archive entry
    /// @param destination absent direct child destination
    /// @param <F> archive format type
    /// @param <E> archive entry type
    /// @throws IOException when the destination collides, links outside staging, or changes during creation
    private <F, E extends ArchiveEntry> void copyEntry(
            ArchiveFileTree<F, E> tree,
            E entry,
            Path destination) throws IOException {
        requireNotCancelled();
        requireCreatedDirectoryChain(Objects.requireNonNull(destination.getParent(), "destination parent"));
        if (tree.isLink(entry)) {
            createContainedSymbolicLink(tree, entry, destination);
            return;
        }
        copyRegularFile(tree, entry, destination);
    }

    /// Streams one regular archive entry into a newly created non-link file and records its digest.
    ///
    /// @param tree opened archive tree
    /// @param entry regular archive entry
    /// @param destination absent file destination
    /// @param <F> archive format type
    /// @param <E> archive entry type
    /// @throws IOException when the destination exists, changes identity, or cannot be written
    private <F, E extends ArchiveEntry> void copyRegularFile(
            ArchiveFileTree<F, E> tree,
            E entry,
            Path destination) throws IOException {
        long size = 0L;
        messageDigest.reset();
        @Nullable EntryIdentity createdIdentity = null;
        try (InputStream input = Objects.requireNonNull(
                tree.getInputStream(entry),
                "archive entry input stream");
                OutputStream output = Files.newOutputStream(
                        destination,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS)) {
            BasicFileAttributes createdAttributes = requireRegularFile(destination);
            createdIdentity = EntryIdentity.capture(createdAttributes);
            while (true) {
                requireNotCancelled();
                int count = input.read(buffer);
                if (count < 0) {
                    break;
                }
                if (count == 0) {
                    continue;
                }
                output.write(buffer, 0, count);
                messageDigest.update(buffer, 0, count);
                size = Math.addExact(size, count);
            }
        } catch (ArithmeticException failure) {
            throw new IOException("Java archive entry size overflow: " + manifestPath(), failure);
        }

        EntryIdentity identity = Objects.requireNonNull(createdIdentity, "created file identity");
        requireSameRegularFile(destination, identity, size);
        requireCreatedDirectoryChain(Objects.requireNonNull(destination.getParent(), "destination parent"));
        if (tree.isExecutable(entry)) {
            setExecutableWithoutFollowingLinks(destination, identity, size);
        }
        requireSameRegularFile(destination, identity, size);
        files.put(
                manifestPath(),
                new JavaLocalFiles.LocalFile(
                        HexFormat.of().formatHex(messageDigest.digest()),
                        size));
    }

    /// Creates one archive-declared link while requiring its lexical target to remain below staging.
    ///
    /// @param tree opened archive tree
    /// @param entry symbolic-link archive entry
    /// @param destination absent link destination
    /// @param <F> archive format type
    /// @param <E> archive entry type
    /// @throws IOException when the target escapes staging, the destination exists, or link creation fails
    private <F, E extends ArchiveEntry> void createContainedSymbolicLink(
            ArchiveFileTree<F, E> tree,
            E entry,
            Path destination) throws IOException {
        String linkTarget = Objects.requireNonNull(tree.getLink(entry), "archive link target");
        Path targetPath;
        try {
            targetPath = destination.getFileSystem().getPath(linkTarget);
        } catch (InvalidPathException failure) {
            throw new IOException("Java archive link has an invalid target: " + linkTarget, failure);
        }
        if (targetPath.isAbsolute()) {
            throw new IOException("Java archive link target is absolute: " + linkTarget);
        }
        Path parent = Objects.requireNonNull(destination.getParent(), "link parent");
        Path resolvedTarget = parent.resolve(targetPath).toAbsolutePath().normalize();
        if (!resolvedTarget.startsWith(ownership.directory())) {
            throw new IOException("Java archive link target escapes staging: " + linkTarget);
        }

        try {
            Files.createSymbolicLink(destination, targetPath);
        } catch (FileAlreadyExistsException failure) {
            throw new IOException("Java archive link collides with an existing entry: " + destination, failure);
        }
        BasicFileAttributes attributes = Files.readAttributes(
                destination,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isSymbolicLink()) {
            throw new IOException("Java archive link was replaced during creation: " + destination);
        }
        requireCreatedDirectoryChain(parent);
        files.put(manifestPath(), new JavaLocalFiles.LocalLink(linkTarget));
    }

    /// Creates one direct child directory and captures its non-link identity.
    ///
    /// @param directory absent direct child directory
    /// @throws IOException when it already exists, is linked, or its parent ownership changed
    private void createOwnedDirectory(Path directory) throws IOException {
        Path parent = Objects.requireNonNull(directory.getParent(), "directory parent");
        requireCreatedDirectoryChain(parent);
        try {
            Files.createDirectory(directory);
        } catch (FileAlreadyExistsException failure) {
            throw new IOException("Java archive directory collides with an existing entry: " + directory, failure);
        }
        BasicFileAttributes attributes = Files.readAttributes(
                directory,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
            throw new IOException("Java archive directory is not a safe directory: " + directory);
        }
        createdDirectories.put(directory, EntryIdentity.capture(attributes));
        requireCreatedDirectoryChain(directory);
    }

    /// Requires the publisher root plus every task-created directory leading to one destination.
    ///
    /// @param directory owned extraction root or a task-created descendant
    /// @throws IOException when a directory is untracked, linked, replaced, or outside staging
    private void requireCreatedDirectoryChain(Path directory) throws IOException {
        requireExtractionOwnership();
        Path normalized = directory.toAbsolutePath().normalize();
        Path root = ownership.directory();
        if (!normalized.startsWith(root)) {
            throw new IOException("Java extraction path escapes staging: " + normalized);
        }
        if (normalized.equals(root)) {
            return;
        }
        Path current = root;
        for (Path segment : root.relativize(normalized)) {
            current = current.resolve(segment);
            @Nullable EntryIdentity identity = createdDirectories.get(current);
            if (identity == null) {
                throw new IOException("Java extraction directory was not created by this task: " + current);
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    current,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory()
                    || attributes.isSymbolicLink()
                    || !identity.matches(attributes)) {
                throw new IOException("Java extraction directory ownership changed: " + current);
            }
        }
    }

    /// Revalidates every descendant directory created by this task before returning a manifest.
    ///
    /// @throws IOException when any tracked directory was replaced
    private void requireAllCreatedDirectories() throws IOException {
        requireExtractionOwnership();
        for (Path directory : createdDirectories.keySet()) {
            requireCreatedDirectoryChain(directory);
        }
    }

    /// Revalidates both the publisher token proof and the identities captured before dependency execution.
    ///
    /// @throws IOException when the platform root, staging directory, or ownership marker was replaced
    void requireExtractionOwnership() throws IOException {
        JavaRuntimeInstallationPublisher.requireOwnedExtractionDirectory(ownership);
        requireSameDirectory(ownership.platformRoot(), platformRootIdentity);
        requireSameDirectory(ownership.directory(), stagingDirectoryIdentity);
        requireSameRegularFile(
                ownership.markerFile(),
                markerIdentity,
                ownership.token().length());
    }

    /// Resolves one archive-tree map key as an exact direct child of its parent.
    ///
    /// @param parent safe owned parent
    /// @param name archive-tree entry name
    /// @return normalized direct child path
    /// @throws IOException when the entry name is empty, path-like, or escaping
    private Path resolveDirectChild(Path parent, String name) throws IOException {
        if (name.isEmpty()
                || ".".equals(name)
                || "..".equals(name)
                || name.indexOf('/') >= 0
                || name.indexOf('\\') >= 0
                || name.indexOf('\0') >= 0) {
            throw new IOException("Java archive contains an unsafe entry name: " + name);
        }
        Path child;
        try {
            child = parent.resolve(name).toAbsolutePath().normalize();
        } catch (InvalidPathException failure) {
            throw new IOException("Java archive contains an invalid entry name: " + name, failure);
        }
        if (!child.startsWith(ownership.directory())) {
            throw new IOException("Java archive entry escapes staging: " + name);
        }
        if (!parent.equals(child.getParent())) {
            throw new IOException("Java archive entry is not a direct child: " + name);
        }
        return child;
    }

    /// Requires the controlled archive path itself to be a non-link regular file.
    ///
    /// @throws IOException when the archive was removed or replaced with a link or special entry
    private void requireControlledArchive() throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                archiveFile,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
            throw new IOException("Managed Java install archive is not a safe regular file: " + archiveFile);
        }
    }

    /// Reads and requires one non-link regular destination file.
    ///
    /// @param file destination file
    /// @return current attributes
    /// @throws IOException when the destination is absent, linked, or not regular
    private static BasicFileAttributes requireRegularFile(Path file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                file,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
            throw new IOException("Java extraction target is not a safe regular file: " + file);
        }
        return attributes;
    }

    /// Reads and requires one non-link directory.
    ///
    /// @param directory owned directory
    /// @return current attributes
    /// @throws IOException when the path is absent, linked, or not a directory
    private static BasicFileAttributes requireDirectory(Path directory) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                directory,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
            throw new IOException("Java extraction root is not a safe directory: " + directory);
        }
        return attributes;
    }

    /// Requires one owned directory to retain its captured provider identity.
    ///
    /// @param directory owned directory
    /// @param identity captured identity
    /// @throws IOException when the path was replaced, linked, or changed type
    private static void requireSameDirectory(
            Path directory,
            EntryIdentity identity) throws IOException {
        BasicFileAttributes attributes = requireDirectory(directory);
        if (!identity.matches(attributes)) {
            throw new IOException("Java extraction directory ownership changed: " + directory);
        }
    }

    /// Requires one newly created file to retain its identity and exact streamed size.
    ///
    /// @param file destination file
    /// @param identity captured identity immediately after creation
    /// @param size expected bytes written
    /// @throws IOException when the path was replaced, linked, or has a different size
    private static void requireSameRegularFile(
            Path file,
            EntryIdentity identity,
            long size) throws IOException {
        BasicFileAttributes attributes = requireRegularFile(file);
        if (!identity.matches(attributes) || attributes.size() != size) {
            throw new IOException("Java extraction target changed during write: " + file);
        }
    }

    /// Adds the owner execute bit through a no-follow POSIX view when the destination filesystem supports it.
    ///
    /// Windows and other non-POSIX filesystems intentionally require no executable-bit mutation.
    ///
    /// @param file newly created executable file
    /// @param identity captured file identity
    /// @param size expected file size
    /// @throws IOException when identity changes or permission mutation fails
    private static void setExecutableWithoutFollowingLinks(
            Path file,
            EntryIdentity identity,
            long size) throws IOException {
        requireSameRegularFile(file, identity, size);
        @Nullable PosixFileAttributeView view = Files.getFileAttributeView(
                file,
                PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            return;
        }
        Set<PosixFilePermission> currentPermissions = view.readAttributes().permissions();
        if (!currentPermissions.contains(PosixFilePermission.OWNER_EXECUTE)) {
            Set<PosixFilePermission> updatedPermissions = EnumSet.copyOf(currentPermissions);
            updatedPermissions.add(PosixFilePermission.OWNER_EXECUTE);
            view.setPermissions(updatedPermissions);
        }
        requireSameRegularFile(file, identity, size);
    }

    /// Returns the slash-separated manifest path for the current recursion stack.
    ///
    /// @return non-empty archive-relative manifest path
    private String manifestPath() {
        return String.join("/", nameStack);
    }

    /// Stops extraction promptly when the task executor or worker thread is cancelled.
    ///
    /// @throws IOException when cancellation is observed
    private void requireNotCancelled() throws IOException {
        if (isCancelled()) {
            throw new IOException("Managed Java extraction was cancelled");
        }
    }

    /// Stable identity captured for one directory or newly created regular file.
    ///
    /// @param fileKey provider file key, or null when unsupported
    /// @param creationTime creation timestamp used when the provider supplies no file key
    @NotNullByDefault
    private record EntryIdentity(
            @Nullable Object fileKey,
            FileTime creationTime) {
        /// Requires a non-null creation timestamp for the cross-platform fallback.
        private EntryIdentity {
            creationTime = Objects.requireNonNull(creationTime, "creationTime");
        }

        /// Captures identity from no-follow attributes.
        ///
        /// @param attributes current safe entry attributes
        /// @return immutable identity
        private static EntryIdentity capture(BasicFileAttributes attributes) {
            return new EntryIdentity(attributes.fileKey(), attributes.creationTime());
        }

        /// Compares provider identity, falling back to the stable creation timestamp when necessary.
        ///
        /// @param attributes current no-follow attributes
        /// @return whether the same filesystem entry remains at the path
        private boolean matches(BasicFileAttributes attributes) {
            return fileKey != null
                    ? fileKey.equals(attributes.fileKey())
                    : creationTime.equals(attributes.creationTime());
        }
    }
}
