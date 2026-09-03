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
import space.minecraftstl.xyml.library.nbt.tag.Tag;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
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

    @FunctionalInterface
    private interface CompressorFactory {
        /// Wraps an output stream with one standalone compression format.
        OutputStream create(OutputStream output) throws IOException;
    }
}
