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
package space.minecraftstl.xyml.image;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;

/// Immutable toolkit-neutral encoded image bytes.
///
/// The constructor defensively copies caller data. Consumers receive independent read-only stream
/// cursors, so no UI toolkit type or mutable byte array crosses the application boundary.
@NotNullByDefault
public final class EncodedImage {
    /// Private encoded bytes, never exposed directly or mutated after construction.
    private final byte @Unmodifiable [] bytes;

    /// Creates an encoded image from a non-empty defensive byte copy.
    ///
    /// @param encodedBytes complete encoded image bytes
    public EncodedImage(byte[] encodedBytes) {
        this(encodedBytes, true);
    }

    /// Reads at most one caller-selected safety bound and takes ownership of the resulting bytes.
    ///
    /// The input stream remains caller-owned. One additional byte is requested to distinguish an
    /// exactly bounded image from oversized content without reading the remainder.
    ///
    /// @param input encoded image stream
    /// @param maximumBytes positive maximum encoded byte count
    /// @return immutable encoded image
    /// @throws IOException when reading fails or content exceeds the bound
    public static EncodedImage read(InputStream input, int maximumBytes) throws IOException {
        Objects.requireNonNull(input, "input");
        if (maximumBytes <= 0 || maximumBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "maximumBytes must be positive and less than Integer.MAX_VALUE");
        }
        byte[] encodedBytes = input.readNBytes(maximumBytes + 1);
        if (encodedBytes.length > maximumBytes) {
            throw new IOException("Encoded image exceeds " + maximumBytes + " bytes");
        }
        if (encodedBytes.length == 0) {
            throw new IOException("Encoded image must not be empty");
        }
        return new EncodedImage(encodedBytes, false);
    }

    /// Stores either a defensive copy or bytes exclusively owned by a bounded reader.
    ///
    /// @param encodedBytes complete encoded bytes
    /// @param copy whether caller data must be copied
    private EncodedImage(byte[] encodedBytes, boolean copy) {
        Objects.requireNonNull(encodedBytes, "encodedBytes");
        if (encodedBytes.length == 0) {
            throw new IllegalArgumentException("encodedBytes must not be empty");
        }
        bytes = copy ? encodedBytes.clone() : encodedBytes;
    }

    /// Returns the encoded byte count without copying image data.
    ///
    /// @return positive encoded byte count
    public int byteCount() {
        return bytes.length;
    }

    /// Opens an independent cursor over the immutable encoded bytes.
    ///
    /// Closing the returned in-memory stream has no side effects on this value or another cursor.
    ///
    /// @return new encoded-data stream positioned at its beginning
    public InputStream openStream() {
        return new ByteArrayInputStream(bytes);
    }

    /// Compares encoded byte content.
    ///
    /// @param other candidate value
    /// @return whether both values contain identical encoded bytes
    @Override
    public boolean equals(@Nullable Object other) {
        return this == other
                || other instanceof EncodedImage image && Arrays.equals(bytes, image.bytes);
    }

    /// Returns a content-derived hash code.
    ///
    /// @return encoded byte hash
    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    /// Returns non-sensitive diagnostic text without expanding encoded content.
    ///
    /// @return encoded image byte-count summary
    @Override
    public String toString() {
        return "EncodedImage[byteCount=" + bytes.length + "]";
    }
}
