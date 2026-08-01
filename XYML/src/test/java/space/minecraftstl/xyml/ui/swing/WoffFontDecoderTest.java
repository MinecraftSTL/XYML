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
package space.minecraftstl.xyml.ui.swing;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies bounded WOFF 1 reconstruction independently from platform font availability.
@NotNullByDefault
final class WoffFontDecoderTest {
    /// WOFF 1 signature.
    private static final int WOFF_SIGNATURE = 0x774F4646;

    /// TrueType sfnt flavor.
    private static final int TRUE_TYPE_FLAVOR = 0x00010000;

    /// Uncompressed first test table tag `name`.
    private static final int NAME_TAG = 0x6E616D65;

    /// Compressed second test table tag `test`.
    private static final int TEST_TAG = 0x74657374;

    /// Reconstructs uncompressed and zlib-compressed tables with correct sfnt directory offsets.
    @Test
    void reconstructsSfntTables() throws IOException {
        byte @Unmodifiable [] firstTable = {1, 2, 3, 4};
        byte[] mutableSecondTable = new byte[64];
        Arrays.fill(mutableSecondTable, (byte) 0x5A);
        byte @Unmodifiable [] secondTable = mutableSecondTable.clone();
        byte @Unmodifiable [] woff = createWoff(firstTable, secondTable);

        byte @Unmodifiable [] sfnt = WoffFontDecoder.decode(woff);
        ByteBuffer header = ByteBuffer.wrap(sfnt).order(ByteOrder.BIG_ENDIAN);

        assertEquals(TRUE_TYPE_FLAVOR, header.getInt());
        assertEquals(2, Short.toUnsignedInt(header.getShort()));
        assertEquals(32, Short.toUnsignedInt(header.getShort()));
        assertEquals(1, Short.toUnsignedInt(header.getShort()));
        assertEquals(0, Short.toUnsignedInt(header.getShort()));

        assertEquals(NAME_TAG, header.getInt());
        assertEquals(0x11111111, header.getInt());
        assertEquals(44, header.getInt());
        assertEquals(firstTable.length, header.getInt());
        assertEquals(TEST_TAG, header.getInt());
        assertEquals(0x22222222, header.getInt());
        assertEquals(48, header.getInt());
        assertEquals(secondTable.length, header.getInt());
        assertArrayEquals(firstTable, Arrays.copyOfRange(sfnt, 44, 48));
        assertArrayEquals(secondTable, Arrays.copyOfRange(sfnt, 48, 112));
    }

    /// Rejects non-WOFF data before allocating reconstructed table buffers.
    @Test
    void rejectsInvalidSignature() throws IOException {
        byte @Unmodifiable [] valid = createWoff(new byte[]{1, 2, 3, 4}, new byte[64]);
        byte[] invalid = valid.clone();
        invalid[0] = 0;

        assertThrows(IOException.class, () -> WoffFontDecoder.decode(invalid));
    }

    /// Builds one valid two-table WOFF container for deterministic decoder tests.
    ///
    /// @param firstTable uncompressed table bytes
    /// @param secondTable compressed table bytes
    /// @return complete WOFF bytes
    private static byte @Unmodifiable [] createWoff(
            byte @Unmodifiable [] firstTable,
            byte @Unmodifiable [] secondTable) {
        byte @Unmodifiable [] compressedSecond = compress(secondTable);
        int directoryEnd = 44 + 2 * 20;
        int firstOffset = directoryEnd;
        int secondOffset = alignFour(firstOffset + firstTable.length);
        int woffLength = alignFour(secondOffset + compressedSecond.length);
        int sfntLength = 12 + 2 * 16 + alignFour(firstTable.length) + alignFour(secondTable.length);
        ByteBuffer output = ByteBuffer.allocate(woffLength).order(ByteOrder.BIG_ENDIAN);

        output.putInt(WOFF_SIGNATURE);
        output.putInt(TRUE_TYPE_FLAVOR);
        output.putInt(woffLength);
        output.putShort((short) 2);
        output.putShort((short) 0);
        output.putInt(sfntLength);
        output.putShort((short) 1);
        output.putShort((short) 0);
        output.putInt(0);
        output.putInt(0);
        output.putInt(0);
        output.putInt(0);
        output.putInt(0);

        writeTableRecord(output, NAME_TAG, firstOffset, firstTable.length, firstTable.length, 0x11111111);
        writeTableRecord(
                output,
                TEST_TAG,
                secondOffset,
                compressedSecond.length,
                secondTable.length,
                0x22222222);
        output.position(firstOffset);
        output.put(firstTable);
        output.position(secondOffset);
        output.put(compressedSecond);
        return output.array();
    }

    /// Writes one WOFF directory entry.
    ///
    /// @param output target buffer
    /// @param tag table tag
    /// @param offset stored data offset
    /// @param compressedLength stored data length
    /// @param originalLength original table length
    /// @param checksum original table checksum
    private static void writeTableRecord(
            ByteBuffer output,
            int tag,
            int offset,
            int compressedLength,
            int originalLength,
            int checksum) {
        output.putInt(tag);
        output.putInt(offset);
        output.putInt(compressedLength);
        output.putInt(originalLength);
        output.putInt(checksum);
    }

    /// Compresses one table with the zlib framing required by WOFF 1.
    ///
    /// @param input original table bytes
    /// @return compressed bytes
    private static byte @Unmodifiable [] compress(byte @Unmodifiable [] input) {
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(input);
            deflater.finish();
            byte[] buffer = new byte[64];
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /// Rounds one byte length up to a four-byte boundary.
    ///
    /// @param value byte length
    /// @return aligned byte length
    private static int alignFour(int value) {
        return (value + 3) & ~3;
    }
}
