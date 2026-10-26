// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.mcp;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Objects;

/// No-follow ownership and object-identity snapshot captured before a destructive confirmation.
/// @param root managed directory boundary
/// @param path strict descendant selected for deletion
/// @param canonicalRoot resolved identity of the configured managed root
/// @param key provider identity, or null for keyless providers
/// @param created creation timestamp
/// @param modified modification timestamp
/// @param size captured size
/// @param directory whether the target is a directory
@NotNullByDefault
record McpPathSnapshot(
        Path root, Path path, Path canonicalRoot, @Nullable Object key,
        FileTime created, FileTime modified, long size, boolean directory) {
    /// Validates immutable snapshot components.
    McpPathSnapshot {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(canonicalRoot, "canonicalRoot");
        Objects.requireNonNull(created, "created");
        Objects.requireNonNull(modified, "modified");
    }

    /// Captures a real file or directory strictly below a no-follow managed boundary.
    /// @param root managed root
    /// @param path normalized target
    /// @return immutable identity
    /// @throws IOException when ownership or source attributes are invalid
    static McpPathSnapshot capture(Path root, Path path) throws IOException {
        requireOwned(root, path);
        BasicFileAttributes attributes = attributes(path);
        if (!attributes.isDirectory() && !attributes.isRegularFile()) {
            throw new IOException("Target is not a regular file or directory: " + path);
        }
        return new McpPathSnapshot(root, path, root.toRealPath(), attributes.fileKey(), attributes.creationTime(),
                attributes.lastModifiedTime(), attributes.size(), attributes.isDirectory());
    }

    /// Rejects replacement, modification, or a newly introduced link before a deletion attempt.
    /// @throws IOException when the original target is no longer current
    void verify() throws IOException {
        requireOwned(root, path);
        if (!canonicalRoot.equals(root.toRealPath())) {
            throw new IOException("Managed directory alias changed while awaiting approval: " + root);
        }
        BasicFileAttributes current = attributes(path);
        if (!Objects.equals(key, current.fileKey()) || !created.equals(current.creationTime())
                || directory != current.isDirectory()
                || (!directory && (!modified.equals(current.lastModifiedTime()) || size != current.size()))
                || (key == null && (!modified.equals(current.lastModifiedTime()) || size != current.size()))) {
            throw new IOException("Deletion target changed while awaiting approval: " + path);
        }
    }

    /// Rejects root deletion and descendant links while retaining the configured root's filesystem aliases.
    /// @param root absolute managed boundary
    /// @param path absolute candidate
    /// @throws IOException when the path is outside the boundary or traverses a link
    static void requireOwned(Path root, Path path) throws IOException {
        if (!root.isAbsolute() || !path.isAbsolute() || path.equals(root) || !path.startsWith(root)) {
            throw new IOException("Target is outside the managed directory: " + path);
        }
        // The explicitly configured root may itself have aliases; descendants must not introduce a new link.
        requireDirectory(root.toRealPath());
        @Nullable Path parent = path.getParent();
        while (parent != null && !parent.equals(root)) {
            requireDirectory(parent);
            parent = parent.getParent();
        }
        if (Files.isSymbolicLink(path)) {
            throw new IOException("Symbolic-link targets are not allowed: " + path);
        }
    }

    /// Requires a real directory rather than following a directory link.
    /// @param path directory component
    /// @throws IOException when the component is missing or is not a real directory
    private static void requireDirectory(Path path) throws IOException {
        BasicFileAttributes attributes = attributes(path);
        if (!attributes.isDirectory()) {
            throw new IOException("Managed path component is not a directory: " + path);
        }
    }

    /// Reads attributes without following a symbolic link.
    /// @param path path to inspect
    /// @return no-follow attributes
    /// @throws IOException when the path is inaccessible or is a symbolic link
    private static BasicFileAttributes attributes(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink()) {
            throw new IOException("Symbolic-link paths are not allowed: " + path);
        }
        return attributes;
    }
}
