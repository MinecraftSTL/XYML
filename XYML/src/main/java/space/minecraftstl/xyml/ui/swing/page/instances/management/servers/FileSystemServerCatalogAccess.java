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
package space.minecraftstl.xyml.ui.swing.page.instances.management.servers;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameRepository;
import space.minecraftstl.xyml.library.nbt.io.NBTFile;
import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;
import space.minecraftstl.xyml.library.nbt.tag.ListTag;
import space.minecraftstl.xyml.library.nbt.tag.Tag;
import space.minecraftstl.xyml.library.nbt.tag.TagType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/// Shared content-revision-checked storage for a Minecraft servers.dat list.
@NotNullByDefault
public final class FileSystemServerCatalogAccess implements ServerCatalogPanel.RevisionAwareServerCatalogAccess {
    /// Serializes revision checks and publication across all UI and MCP storage objects.
    private static final Object WRITE_GATE = new Object();

    /// Exact servers.dat path owned by the selected instance.
    final Path file;
    /// Revision captured by the most recent successful read.
    private @Nullable FileRevision expectedRevision;

    /// Creates storage for one instance's existing server list.
    /// @param repository owning repository
    /// @param instanceId selected instance
    public FileSystemServerCatalogAccess(GameRepository repository, GameInstanceID instanceId) {
        file = Objects.requireNonNull(repository, "repository").getRunDirectory(
                Objects.requireNonNull(instanceId, "instanceId")).resolve("servers.dat");
    }

    /// Reads and retains the content revision for a later compare-and-write.
    @Override
    public synchronized @Unmodifiable List<ServerCatalogItem> read() throws IOException {
        FileRevision before = revision(file);
        if (!before.exists()) {
            expectedRevision = before;
            return List.of();
        }
        try (NBTFile<CompoundTag> nbt = NBTFile.openTag(file, TagType.COMPOUND)) {
            CompoundTag root = nbt.getEditor().getRootSnapshot();
            @Nullable Tag rawServers = root.get("servers");
            if (rawServers == null) {
                expectedRevision = before;
                return List.of();
            }
            if (!(rawServers instanceof ListTag<?> servers)) {
                throw new IOException("servers.dat contains a non-list servers tag");
            }
            List<ServerCatalogItem> result = new ArrayList<>();
            for (Tag rawServer : servers) {
                if (!(rawServer instanceof CompoundTag server)) {
                    throw new IOException("servers.dat contains a non-compound server entry");
                }
                String name = server.getStringOrEmpty("name");
                String address = server.getStringOrEmpty("ip");
                if (name.isBlank() || address.isBlank()) {
                    throw new IOException("servers.dat contains a blank server field");
                }
                result.add(new ServerCatalogItem(name, address, server));
            }
            FileRevision after = revision(file);
            if (!before.equals(after)) {
                throw new IOException("servers.dat changed while it was being read");
            }
            expectedRevision = after;
            return List.copyOf(result);
        }
    }

    /// Publishes entries only if the previously read source is unchanged.
    @Override
    public synchronized void write(List<ServerCatalogItem> servers) throws IOException {
        if (expectedRevision == null) {
            read();
        }
        writeIfUnchanged(List.of(), servers);
    }

    /// Publishes an updated list while retaining unknown root and entry NBT fields.
    @Override
    public synchronized void writeIfUnchanged(
            List<ServerCatalogItem> expectedServers,
            List<ServerCatalogItem> servers) throws IOException {
        synchronized (WRITE_GATE) {
            FileRevision expected = Objects.requireNonNull(expectedRevision, "expectedRevision");
            requireRevision(expected);
            @Nullable Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (NBTFile<CompoundTag> nbt = Files.isRegularFile(file)
                    ? NBTFile.openTag(file, TagType.COMPOUND)
                    : NBTFile.createTag(file)) {
                CompoundTag root = nbt.getEditor().getRootSnapshot();
                ListTag<CompoundTag> rawServers = new ListTag<>(TagType.COMPOUND).setName("servers");
                for (ServerCatalogItem server : servers) {
                    rawServers.addTag(server.toTag());
                }
                if (root.get("servers") == null) {
                    root.addTag(rawServers);
                } else {
                    root.replaceTag("servers", rawServers);
                }
                try {
                    nbt.getEditor().replaceContent(nbt.getEditor().rootNode(), root);
                } catch (space.minecraftstl.xyml.library.nbt.edit.NBTEditException failure) {
                    throw new IOException("Failed to update servers.dat", failure);
                }
                requireRevision(expected);
                nbt.save();
            }
            expectedRevision = revision(file);
        }
    }

    /// Returns the revision captured by the preceding read, not a newly observed source.
    /// @return opaque content revision
    public synchronized String revision() {
        FileRevision observed = Objects.requireNonNull(expectedRevision, "Read the server list first");
        return observed.exists() + ":" + observed.size() + ":" + observed.digest();
    }

    /// Rejects publication when the source no longer matches the revision loaded by this access object.
    private void requireRevision(FileRevision expected) throws IOException {
        if (!expected.equals(revision(file))) {
            throw new IOException("servers.dat changed outside XYML; reload before editing");
        }
    }

    /// Captures a content-sensitive revision without following symbolic links.
    private static FileRevision revision(Path file) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException ignored) {
            return FileRevision.absent();
        }
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
            throw new IOException("servers.dat is not a regular file");
        }
        MessageDigest digest = sha256();
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) {
                    digest.update(buffer, 0, count);
                }
            }
        }
        return new FileRevision(true, attributes.size(), HexFormat.of().formatHex(digest.digest()));
    }

    /// Returns the required SHA-256 digest implementation.
    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    /// Immutable content revision used to reject stale full-file writes.
    @NotNullByDefault
    private record FileRevision(boolean exists, long size, String digest) {
        /// Stores the exact source fingerprint.
        private FileRevision {
            Objects.requireNonNull(digest, "digest");
        }

        /// Returns the stable revision for an absent source.
        private static FileRevision absent() {
            return new FileRevision(false, 0L, "");
        }
    }
}
