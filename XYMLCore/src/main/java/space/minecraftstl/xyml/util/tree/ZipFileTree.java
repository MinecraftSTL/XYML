/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2024 huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.util.tree;

import kala.compress.archivers.zip.ZipArchiveEntry;
import kala.compress.archivers.zip.ZipArchiveReader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/// Lazily validates and indexes ZIP entries while keeping point queries free of directory-tree allocation.
@NotNullByDefault
public final class ZipFileTree extends ArchiveFileTree<ZipArchiveReader, ZipArchiveEntry> {
    /// Whether this wrapper owns and closes the underlying reader.
    private final boolean closeReader;

    /// Underlying-reader cleanup may be retried after an I/O failure.
    private boolean readerReleased;

    /// Fully published hierarchy, never a partially initialized root.
    private @Nullable Dir<ZipArchiveEntry> root;

    /// Validation and canonical-alias metadata created only on first access.
    private @Nullable ArchiveEntryIndex<ZipArchiveEntry> index;

    /// Opens an owning wrapper without materializing a directory tree.
    public ZipFileTree(ZipArchiveReader file) throws IOException {
        this(file, true);
    }

    /// Opens a wrapper with explicit underlying-reader ownership.
    public ZipFileTree(ZipArchiveReader file, boolean closeReader) throws IOException {
        super(file);
        this.closeReader = closeReader;
    }

    /// Validates all names and resolves one file directly without constructing directory nodes.
    @Override
    public synchronized @Nullable ZipArchiveEntry getEntry(@NotNull String entryPath) throws IOException {
        requireOpen();
        if (index == null) index = ArchiveEntryIndex.validate(reader.getEntries());
        String name = ArchiveEntryIndex.normalize(entryPath, true);
        @Nullable ZipArchiveEntry entry = reader.getEntry(name);
        if (entry == null) entry = index.alias(name);
        return entry == null || entry.isDirectory() ? null : entry;
    }

    /// Builds once into a local root and publishes only after strict validation succeeds.
    @Override
    public synchronized Dir<ZipArchiveEntry> getRoot() throws IOException {
        requireOpen();
        if (root == null) {
            Dir<ZipArchiveEntry> built = new Dir<>("", "");
            ArchiveEntryIndex<ZipArchiveEntry> validated = ArchiveEntryIndex.buildTree(reader.getEntries(), built);
            index = validated;
            root = built;
        }
        return root;
    }

    /// Exposes materialization state for deterministic package-local regression tests.
    synchronized boolean isTreeMaterialized() {
        return root != null;
    }

    /// Closes the wrapper once; borrowed readers remain owned by their original caller.
    @Override
    public synchronized void close() throws IOException {
        markClosed();
        if (closeReader && !readerReleased) {
            reader.close();
            readerReleased = true;
        }
    }

    @Override
    @SuppressWarnings("OctalInteger")
    protected void copyAttributes(@NotNull ZipArchiveEntry source, @NotNull Path targetFile) throws IOException {
        BasicFileAttributeView targetView = Files.getFileAttributeView(targetFile, PosixFileAttributeView.class);

        // target might not support posix even if source does
        if (targetView == null)
            targetView = Files.getFileAttributeView(targetFile, BasicFileAttributeView.class);

        if (targetView == null)
            return;

        targetView.setTimes(
                source.getLastModifiedTime(),
                source.getLastAccessTime(),
                source.getCreationTime()
        );

        int unixMode = source.getUnixMode();
        if (unixMode != 0 && targetView instanceof PosixFileAttributeView posixView) {
            Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);

            // Owner permissions
            if ((unixMode & 0400) != 0) permissions.add(PosixFilePermission.OWNER_READ);
            if ((unixMode & 0200) != 0) permissions.add(PosixFilePermission.OWNER_WRITE);
            if ((unixMode & 0100) != 0) permissions.add(PosixFilePermission.OWNER_EXECUTE);

            // Group permissions
            if ((unixMode & 0040) != 0) permissions.add(PosixFilePermission.GROUP_READ);
            if ((unixMode & 0020) != 0) permissions.add(PosixFilePermission.GROUP_WRITE);
            if ((unixMode & 0010) != 0) permissions.add(PosixFilePermission.GROUP_EXECUTE);

            // Others permissions
            if ((unixMode & 0004) != 0) permissions.add(PosixFilePermission.OTHERS_READ);
            if ((unixMode & 0002) != 0) permissions.add(PosixFilePermission.OTHERS_WRITE);
            if ((unixMode & 0001) != 0) permissions.add(PosixFilePermission.OTHERS_EXECUTE);

            posixView.setPermissions(permissions);
        }
    }

    @Override
    public InputStream getInputStream(ZipArchiveEntry entry) throws IOException {
        requireOpen();
        return getReader().getInputStream(entry);
    }

    @Override
    public boolean isLink(ZipArchiveEntry entry) {
        return entry.isUnixSymlink();
    }

    @Override
    public String getLink(ZipArchiveEntry entry) throws IOException {
        requireOpen();
        return getReader().getUnixSymlink(entry);
    }

    @Override
    public boolean isExecutable(ZipArchiveEntry entry) {
        return !entry.isDirectory() && !entry.isUnixSymlink() && (entry.getUnixMode() & 0b1000000) != 0;
    }
}
