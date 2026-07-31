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
package space.minecraftstl.xyml.nbt;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/// Immutable encoded-content fingerprint used to reject stale NBT saves.
@NotNullByDefault
final class NBTSourceFingerprint {
    /// Read buffer size used while hashing without retaining the entire file.
    private static final int HASH_BUFFER_BYTES = 64 * 1024;

    /// Exact encoded source size.
    private final long size;

    /// SHA-256 digest of the encoded file bytes.
    private final byte @Unmodifiable [] digest;

    /// Creates an immutable fingerprint from one stable read.
    ///
    /// @param size exact encoded size
    /// @param digest SHA-256 digest bytes
    private NBTSourceFingerprint(long size, byte @Unmodifiable [] digest) {
        this.size = size;
        this.digest = digest.clone();
    }

    /// Captures one stable regular-file fingerprint without following symbolic links.
    ///
    /// Attributes are read before and after hashing. A concurrent replacement or modification is
    /// rejected instead of producing a fingerprint for an indeterminate byte sequence.
    ///
    /// @param file normalized source path
    /// @return stable immutable fingerprint
    /// @throws IOException when the path is not a regular non-link file, hashing fails, or the file changes
    static NBTSourceFingerprint capture(Path file) throws IOException {
        BasicFileAttributes before = readRegularFileAttributes(file);
        byte @Unmodifiable [] digest = digest(file);
        BasicFileAttributes after = readRegularFileAttributes(file);
        if (!sameAttributes(before, after)) {
            throw new IOException("NBT file changed while it was being read: " + file);
        }
        return new NBTSourceFingerprint(after.size(), digest);
    }

    /// Compares full encoded content and stable filesystem attributes.
    ///
    /// @param other candidate fingerprint
    /// @return whether both captures identify the same unchanged source
    boolean sameAs(NBTSourceFingerprint other) {
        return size == other.size
                && MessageDigest.isEqual(digest, other.digest);
    }

    /// Reads attributes while rejecting directories, special files, and symbolic links.
    ///
    /// @param file source path
    /// @return regular-file attributes
    /// @throws IOException when the path is not a regular non-link file
    private static BasicFileAttributes readRegularFileAttributes(Path file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                file,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
            throw new IOException("NBT source is not a regular non-link file: " + file);
        }
        return attributes;
    }

    /// Streams one file through SHA-256 without retaining its encoded content in memory.
    ///
    /// @param file source path
    /// @return new digest array
    /// @throws IOException when source bytes cannot be read
    private static byte @Unmodifiable [] digest(Path file) throws IOException {
        MessageDigest digest = newSha256();
        byte[] buffer = new byte[HASH_BUFFER_BYTES];
        try (InputStream input = Files.newInputStream(file)) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) {
                    digest.update(buffer, 0, count);
                }
            }
        }
        return digest.digest();
    }

    /// Creates the mandatory JDK SHA-256 digest implementation.
    ///
    /// @return fresh digest
    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new AssertionError("The Java runtime does not provide SHA-256", failure);
        }
    }

    /// Compares attributes captured around one digest pass.
    ///
    /// @param first pre-hash attributes
    /// @param second post-hash attributes
    /// @return whether the provider reports the same file state
    private static boolean sameAttributes(BasicFileAttributes first, BasicFileAttributes second) {
        return first.size() == second.size()
                && first.lastModifiedTime().equals(second.lastModifiedTime())
                && Objects.equals(first.fileKey(), second.fileKey());
    }
}
