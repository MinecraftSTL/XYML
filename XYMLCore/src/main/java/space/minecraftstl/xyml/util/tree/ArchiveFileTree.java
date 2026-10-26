// SPDX-FileCopyrightText: Copyright (C) 2024 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.util.tree;

import kala.compress.archivers.ArchiveEntry;
import kala.compress.archivers.zip.ZipArchiveReader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.*;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// @author Glavo
public abstract class ArchiveFileTree<R, E extends ArchiveEntry> implements Closeable {

    public static ArchiveFileTree<?, ?> open(Path file) throws IOException {
        Path namePath = file.getFileName();
        if (namePath == null) {
            throw new IOException(file + " is not a valid archive file");
        }

        String name = namePath.toString();
        if (name.endsWith(".jar") || name.endsWith(".zip")) {
            return new ZipFileTree(new ZipArchiveReader(file));
        } else if (name.endsWith(".tar") || name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
            return TarFileTree.open(file);
        } else {
            throw new IOException(file + " is not a valid archive file");
        }
    }

    protected final R reader;
    /// Closed wrappers never initiate new reads, even when their reader is borrowed.
    private volatile boolean closed;

    public ArchiveFileTree(R reader) {
        this.reader = reader;
    }

    public R getReader() {
        return reader;
    }

    /// Returns the fully constructed hierarchy, propagating deferred validation failures.
    public abstract Dir<E> getRoot() throws IOException;

    /// Rejects every new operation after wrapper closure.
    protected final void requireOpen() throws IOException {
        if (closed) throw new IOException("Archive tree is closed");
    }

    /// Atomically closes this wrapper once without claiming a borrowed reader.
    protected final synchronized boolean markClosed() {
        if (closed) return false;
        closed = true;
        return true;
    }

    public @Nullable E getEntry(@NotNull String entryPath) throws IOException {
        requireOpen();
        Dir<E> root = getRoot();
        Dir<E> dir = root;
        if (entryPath.indexOf('/') < 0) {
            return dir.getFiles().get(entryPath);
        } else {
            String[] path = entryPath.split("/");
            if (path.length == 0)
                return root.getEntry();

            for (int i = 0; i < path.length - 1; i++) {
                String item = path[i];
                if (item.isEmpty())
                    continue;
                dir = dir.getSubDirs().get(item);
                if (dir == null)
                    return null;
            }

            String fileName = path[path.length - 1];
            return dir.getFiles().get(fileName);
        }
    }

    public @Nullable Dir<E> getDirectory(@NotNull String dirPath) throws IOException {
        requireOpen();
        Dir<E> dir = getRoot();
        if (dirPath.isEmpty()) {
            return dir;
        }
        String[] path = dirPath.split("/");
        for (String item : path) {
            if (item.isEmpty())
                continue;
            dir = dir.getSubDirs().get(item);
            if (dir == null)
                return null;
        }
        return dir;
    }

    /// Adds one validated entry, allocating directory collections only when they become non-empty.
    protected static <E extends ArchiveEntry> void addEntry(Dir<E> root, E entry) throws IOException {
        String name = ArchiveEntryIndex.normalize(entry.getName(), false);
        Dir<E> directory = root;
        int start = 0;
        while (start < name.length()) {
            int end = name.indexOf('/', start);
            if (end < 0) end = name.length();
            String part = name.substring(start, end);
            boolean last = end == name.length();
            if (last && !entry.isDirectory()) {
                if (directory.subDirs.containsKey(part)) throw new IOException("Archive file/directory conflict");
                if (directory.files.containsKey(part)) throw new IOException("Duplicate archive file");
                if (directory.files.isEmpty()) directory.files = new HashMap<>();
                directory.files.put(part, entry);
                return;
            }
            if (directory.files.containsKey(part)) throw new IOException("Archive file/directory conflict");
            if (directory.subDirs.isEmpty()) directory.subDirs = new HashMap<>();
            final String fullName = name.substring(0, end);
            directory = directory.subDirs.computeIfAbsent(part, value -> new Dir<>(value, fullName));
            start = end + 1;
        }
        if (entry.isDirectory() && directory.entry == null) directory.entry = entry;
    }

    public abstract InputStream getInputStream(E entry) throws IOException;

    public @NotNull InputStream getInputStream(String entryPath) throws IOException {
        E entry = getEntry(entryPath);
        if (entry == null)
            throw new FileNotFoundException("Entry not found: " + entryPath);
        return getInputStream(entry);
    }

    public BufferedReader getBufferedReader(@NotNull E entry) throws IOException {
        return new BufferedReader(new InputStreamReader(getInputStream(entry), StandardCharsets.UTF_8));
    }

    public @NotNull BufferedReader getBufferedReader(String entryPath) throws IOException {
        E entry = getEntry(entryPath);
        if (entry == null)
            throw new FileNotFoundException("Entry not found: " + entryPath);
        return getBufferedReader(entry);
    }

    public byte[] readBinaryEntry(@NotNull E entry) throws IOException {
        try (InputStream input = getInputStream(entry)) {
            return input.readAllBytes();
        }
    }

    public String readTextEntry(@NotNull String entryPath) throws IOException {
        E entry = getEntry(entryPath);
        if (entry == null)
            throw new FileNotFoundException("Entry not found: " + entryPath);
        return readTextEntry(entry);
    }

    public String readTextEntry(@NotNull E entry) throws IOException {
        return new String(readBinaryEntry(entry), StandardCharsets.UTF_8);
    }

    protected void copyAttributes(@NotNull E source, @NotNull Path targetFile) throws IOException {
        FileTime lastModifiedTime = source.getLastModifiedTime();
        if (lastModifiedTime != null)
            Files.setLastModifiedTime(targetFile, lastModifiedTime);
    }

    public void extractTo(@NotNull String entryPath, @NotNull Path targetFile) throws IOException {
        E entry = getEntry(entryPath);
        if (entry == null)
            throw new FileNotFoundException("Entry not found: " + entryPath);

        extractTo(entry, targetFile);
    }

    public void extractTo(@NotNull E entry, @NotNull Path targetFile) throws IOException {
        try (InputStream input = getInputStream(entry)) {
            Files.copy(input, targetFile, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            copyAttributes(entry, targetFile);
        } catch (Throwable e) {
            LOG.warning("Failed to copy attributes to " + targetFile, e);
        }
    }

    public abstract boolean isLink(E entry);

    public abstract String getLink(E entry) throws IOException;

    public abstract boolean isExecutable(E entry);

    @Override
    public abstract void close() throws IOException;

    /// Read-only metadata view of one fully constructed directory.
    @NotNullByDefault
    public static final class Dir<E extends ArchiveEntry> {
        private final String name;
        private final String fullName;
        /// Borrowed explicit directory entry, or null for an implicit directory.
        private @Nullable E entry;

        /// Owner-mutated children while building; empty directories allocate no HashMap.
        Map<String, Dir<E>> subDirs = Map.of();
        /// Owner-mutated files while building; published callers receive a read-only view.
        Map<String, E> files = Map.of();

        public Dir(String name, String fullName) {
            this.name = name;
            this.fullName = fullName;
        }

        public boolean isRoot() {
            return name.isEmpty();
        }

        public @NotNull String getName() {
            return name;
        }

        /// Get the normalized full path. Leading `/` and all `.` in the path will be removed.
        public @NotNull String getFullName() {
            return fullName;
        }

        public @Nullable E getEntry() {
            return entry;
        }

        public @NotNull @UnmodifiableView Map<String, Dir<E>> getSubDirs() {
            return subDirs;
        }

        public @NotNull @UnmodifiableView Map<String, E> getFiles() {
            return files;
        }
    }
}
