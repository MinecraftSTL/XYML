// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.util.tree;

import kala.compress.archivers.ArchiveEntry;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/// Validates normalized archive names without constructing directory nodes.
/// Only non-canonical file aliases are retained after validation; directory and collision sets are temporary.
@NotNullByDefault
final class ArchiveEntryIndex<E extends ArchiveEntry> {
    /// Bound for one entry path before creating substrings.
    private static final int MAX_NAME_LENGTH = 4096;
    /// Bound for archive index work and temporary collision metadata.
    private static final int MAX_ENTRIES = 1_000_000;
    /// Borrowed reader entries indexed only under their non-canonical normalized file names.
    private final @Unmodifiable Map<String, E> aliases;

    /// Publishes a completed alias index after every path passed validation.
    private ArchiveEntryIndex(Map<String, E> aliases) {
        this.aliases = Map.copyOf(aliases);
    }

    /// Validates one complete archive before allowing even a point lookup.
    static <E extends ArchiveEntry> ArchiveEntryIndex<E> validate(Iterable<E> entries) throws IOException {
        Set<String> files = new HashSet<>();
        Set<String> directories = new HashSet<>();
        Map<String, E> aliases = new HashMap<>();
        int count = 0;
        for (E entry : entries) {
            if (++count > MAX_ENTRIES) throw new IOException("Archive entry limit exceeded");
            String raw = entry.getName();
            String name = normalize(raw, false);
            int separator = name.indexOf('/');
            while (separator >= 0) {
                String parent = name.substring(0, separator);
                if (files.contains(parent)) throw new IOException("Archive file/directory conflict");
                directories.add(parent);
                separator = name.indexOf('/', separator + 1);
            }
            if (entry.isDirectory()) {
                if (files.contains(name)) throw new IOException("Archive file/directory conflict");
                directories.add(name);
            } else {
                if (name.isEmpty() || directories.contains(name)) throw new IOException("Invalid archive file path");
                if (!files.add(name)) throw new IOException("Duplicate archive file");
                if (!name.equals(raw)) aliases.put(name, entry);
            }
        }
        return new ArchiveEntryIndex<>(aliases);
    }

    /// Validates and builds in one pass when a full hierarchy is requested first.
    static <E extends ArchiveEntry> ArchiveEntryIndex<E> buildTree(Iterable<E> entries, ArchiveFileTree.Dir<E> root)
            throws IOException {
        Map<String, E> aliases = new HashMap<>();
        int count = 0;
        for (E entry : entries) {
            if (++count > MAX_ENTRIES) throw new IOException("Archive entry limit exceeded");
            ArchiveFileTree.addEntry(root, entry);
            String name = normalize(entry.getName(), false);
            if (!entry.isDirectory() && !name.equals(entry.getName())) aliases.put(name, entry);
        }
        return new ArchiveEntryIndex<>(aliases);
    }

    /// Resolves a validated normalized alias without allocating the hierarchy.
    @Nullable E alias(String name) {
        return aliases.get(name);
    }

    /// Normalizes safe dot components; leading slashes are accepted only for virtual-root queries.
    static String normalize(String name, boolean query) throws IOException {
        if (name.length() > MAX_NAME_LENGTH || name.indexOf('\\') >= 0 || name.indexOf((char) 0) >= 0) {
            throw new IOException("Invalid archive path");
        }
        String source = query ? name.replaceFirst("^/+", "") : name;
        if (!query && source.startsWith("/")) throw new IOException("Absolute archive entry path");
        StringBuilder normalized = new StringBuilder();
        int start = 0;
        int components = 0;
        while (start < source.length()) {
            int end = source.indexOf('/', start);
            if (end < 0) end = source.length();
            String part = source.substring(start, end);
            if (query && part.isEmpty()) {
                start = end + 1;
                continue;
            }
            if (++components > 256 || part.isEmpty() || part.equals("..") || part.indexOf(':') >= 0) {
                throw new IOException("Invalid archive path component");
            }
            if (!part.equals(".")) {
                if (normalized.length() > 0) normalized.append('/');
                normalized.append(part);
            }
            start = end + 1;
        }
        return normalized.toString();
    }
}
