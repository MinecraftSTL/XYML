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
// Added by MinecraftSTL in 2026 for strict standalone codec coverage.
package space.minecraftstl.xyml.library.nbt.io;

import net.jpountz.lz4.LZ4BlockOutputStream;
import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;
import space.minecraftstl.xyml.library.nbt.tag.StringTag;
import space.minecraftstl.xyml.library.nbt.tag.Tag;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies strict compressed-stream completion and trailing-data rejection.
@NotNullByDefault
public final class StrictNBTCodecTest {
    /// Ensures RAW, GZIP, ZLIB, and LZ4 standalone streams are all detected and fully consumed.
    @Test
    void readsEveryStandaloneEncoding() throws IOException {
        Tag expected = sampleTag();
        byte[] raw = NBTCodec.of().writeTagToByteArray(expected);

        assertEquals(expected, NBTCodec.of().readTag(raw));
        assertEquals(expected, NBTCodec.of().readTag(compress(raw, GZIPOutputStream::new)));
        assertEquals(expected, NBTCodec.of().readTag(compress(raw, DeflaterOutputStream::new)));
        assertEquals(expected, NBTCodec.of().readTag(compress(raw, LZ4BlockOutputStream::new)));
    }

    /// Ensures standalone readers reject bytes after either raw or compressed payloads.
    @Test
    void rejectsTrailingBytes() throws IOException {
        byte[] raw = NBTCodec.of().writeTagToByteArray(sampleTag());
        byte[] gzip = compress(raw, GZIPOutputStream::new);
        byte[] zlib = compress(raw, DeflaterOutputStream::new);
        byte[] lz4 = compress(raw, LZ4BlockOutputStream::new);

        assertThrows(IOException.class, () -> NBTCodec.of().readTag(append(raw, (byte) 1)));
        assertThrows(IOException.class, () -> NBTCodec.of().readTag(append(gzip, (byte) 1)));
        assertThrows(IOException.class, () -> NBTCodec.of().readTag(append(zlib, (byte) 1)));
        assertThrows(IOException.class, () -> NBTCodec.of().readTag(append(lz4, (byte) 1)));
    }

    /// Ensures incomplete streams and an invalid GZIP footer are rejected.
    @Test
    void rejectsIncompleteOrInvalidCompressedStreams() throws IOException {
        byte[] raw = NBTCodec.of().writeTagToByteArray(sampleTag());
        byte[] gzip = compress(raw, GZIPOutputStream::new);
        byte[] zlib = compress(raw, DeflaterOutputStream::new);

        assertThrows(IOException.class, () -> NBTCodec.of().readTag(Arrays.copyOf(gzip, gzip.length - 2)));
        assertThrows(IOException.class, () -> NBTCodec.of().readTag(Arrays.copyOf(zlib, zlib.length - 1)));
        gzip[gzip.length - 5] ^= 0x40;
        assertThrows(IOException.class, () -> NBTCodec.of().readTag(gzip));
    }

    /// Ensures an optional GZIP header checksum is accepted only when it matches the complete header.
    @Test
    void validatesGzipHeaderChecksum() throws IOException {
        Tag expected = sampleTag();
        byte[] raw = NBTCodec.of().writeTagToByteArray(expected);
        byte[] checked = addGzipHeaderChecksum(compress(raw, GZIPOutputStream::new));

        assertEquals(expected, NBTCodec.of().readTag(checked));
        checked[10] ^= 1;
        assertThrows(IOException.class, () -> NBTCodec.of().readTag(checked));
    }

    /// Ensures a raw TAG_String root is not mistaken for a zlib stream by standalone auto-detection.
    @Test
    void rawStringRootIsNotMistakenForZlib() throws IOException {
        Tag root = new StringTag("text");
        byte[] encoded = NBTCodec.of().writeTagToByteArray(root);

        assertEquals(NBTFileEncoding.RAW, NBTFileEncoding.detectStandalone(encoded));
        assertEquals(root, NBTCodec.of().readTag(encoded));
    }

    private static CompoundTag sampleTag() {
        return new CompoundTag()
                .addString("name", "strict")
                .addIntArray("values", new int[]{1, 2, 3});
    }

    private static byte[] compress(byte[] raw, CompressorFactory factory) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (OutputStream compressed = factory.create(output)) {
            compressed.write(raw);
        }
        return output.toByteArray();
    }

    private static byte[] append(byte[] input, byte value) {
        byte[] result = Arrays.copyOf(input, input.length + 1);
        result[input.length] = value;
        return result;
    }

    /// Adds the optional little-endian FHCRC field to a basic ten-byte GZIP header.
    ///
    /// @param gzip complete GZIP member with a basic header
    /// @return equivalent member with a valid header checksum
    private static byte[] addGzipHeaderChecksum(byte[] gzip) {
        byte[] result = new byte[gzip.length + 2];
        System.arraycopy(gzip, 0, result, 0, 10);
        result[3] |= 0x02;
        CRC32 checksum = new CRC32();
        checksum.update(result, 0, 10);
        int checksumValue = (int) checksum.getValue();
        result[10] = (byte) checksumValue;
        result[11] = (byte) (checksumValue >>> 8);
        System.arraycopy(gzip, 10, result, 12, gzip.length - 10);
        return result;
    }

    @FunctionalInterface
    private interface CompressorFactory {
        /// Wraps an output stream with one standalone compression format.
        OutputStream create(OutputStream output) throws IOException;
    }
}
