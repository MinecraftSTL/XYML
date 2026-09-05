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
// Added by MinecraftSTL in 2026 for safe XoyzNBT file editing.
package space.minecraftstl.xyml.library.nbt.io;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/// Describes the complete on-disk envelope preserved by an [NBTFile] session.
@NotNullByDefault
public enum NBTFileEncoding {
    /// An uncompressed standalone NBT tag.
    RAW,

    /// A standalone NBT tag wrapped in a GZIP member.
    GZIP,

    /// A standalone NBT tag wrapped in a zlib stream.
    ZLIB,

    /// A standalone NBT tag wrapped in the lz4-java block-stream format.
    LZ4,

    /// A Java Edition region container with per-chunk compression.
    REGION;

    /// ASCII magic emitted by lz4-java's block-stream encoder.
    private static final byte @Unmodifiable [] LZ4_MAGIC =
            "LZ4Block".getBytes(StandardCharsets.US_ASCII);

    /// Detects the exact envelope of a standalone encoded tag.
    ///
    /// The result describes only the outer envelope. The caller must still parse the complete
    /// payload to reject invalid headers, checksums, truncation, or trailing bytes.
    ///
    /// @param encoded complete encoded bytes
    /// @return detected standalone envelope
    /// @throws IOException if the prefix claims a known but unsupported compressed variant
    public static NBTFileEncoding detectStandalone(byte[] encoded) throws IOException {
        Objects.requireNonNull(encoded, "encoded");
        if (startsWith(encoded, 0x1F, 0x8B)) {
            return GZIP;
        }
        if (startsWith(encoded, LZ4_MAGIC)) {
            return LZ4;
        }
        if (encoded.length >= 2 && Byte.toUnsignedInt(encoded[0]) == 0x78) {
            int flags = Byte.toUnsignedInt(encoded[1]);
            if (((0x78 << 8) | flags) % 31 == 0) {
                if ((flags & 0x20) != 0) {
                    throw new IOException("Preset-dictionary zlib streams are not supported");
                }
                return ZLIB;
            }
        }
        return RAW;
    }

    /// Returns whether the byte array begins with all supplied unsigned byte values.
    ///
    /// @param encoded encoded bytes
    /// @param prefix unsigned byte values
    /// @return whether the prefix matches
    private static boolean startsWith(byte[] encoded, int... prefix) {
        if (encoded.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (Byte.toUnsignedInt(encoded[index]) != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    /// Returns whether the byte array begins with the supplied byte prefix.
    ///
    /// @param encoded encoded bytes
    /// @param prefix byte prefix
    /// @return whether the prefix matches
    private static boolean startsWith(byte[] encoded, byte[] prefix) {
        if (encoded.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (encoded[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }
}
