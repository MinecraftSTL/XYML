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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/// Converts bounded WOFF 1 containers into the original sfnt form accepted by AWT.
@NotNullByDefault
final class WoffFontDecoder {
    /// WOFF 1 signature `wOFF`.
    private static final int WOFF_SIGNATURE = 0x774F4646;

    /// Fixed WOFF header size in bytes.
    private static final int WOFF_HEADER_SIZE = 44;

    /// One WOFF directory record size in bytes.
    private static final int WOFF_DIRECTORY_ENTRY_SIZE = 20;

    /// Fixed sfnt offset-table size in bytes.
    private static final int SFNT_HEADER_SIZE = 12;

    /// One sfnt table record size in bytes.
    private static final int SFNT_DIRECTORY_ENTRY_SIZE = 16;

    /// Upper bound for both compressed input and reconstructed font data.
    private static final int MAX_FONT_BYTES = 128 * 1024 * 1024;

    /// Upper bound preventing pathological table-directory allocation.
    private static final int MAX_TABLES = 4095;

    /// Prevents construction of the stateless decoder.
    private WoffFontDecoder() {
    }

    /// Reads and decodes one bounded WOFF 1 file.
    ///
    /// @param file local WOFF file
    /// @return newly allocated sfnt bytes
    /// @throws IOException when the container is malformed, oversized, or unreadable
    static byte @Unmodifiable [] decode(Path file) throws IOException {
        Path validatedFile = Objects.requireNonNull(file, "file");
        long size = Files.size(validatedFile);
        if (size <= 0 || size > MAX_FONT_BYTES) {
            throw new IOException("WOFF file size is outside the supported range: " + size);
        }
        return decode(Files.readAllBytes(validatedFile));
    }

    /// Decodes one WOFF 1 byte sequence into a newly allocated sfnt byte sequence.
    ///
    /// @param woffData complete WOFF 1 data
    /// @return newly allocated sfnt data
    /// @throws IOException when the container is malformed or oversized
    static byte @Unmodifiable [] decode(byte @Unmodifiable [] woffData) throws IOException {
        byte @Unmodifiable [] source = Objects.requireNonNull(woffData, "woffData");
        if (source.length < WOFF_HEADER_SIZE || source.length > MAX_FONT_BYTES) {
            throw new IOException("WOFF input size is outside the supported range: " + source.length);
        }
        ByteBuffer input = ByteBuffer.wrap(source).order(ByteOrder.BIG_ENDIAN);
        if (input.getInt() != WOFF_SIGNATURE) {
            throw new IOException("Unsupported WOFF signature");
        }
        int flavor = input.getInt();
        long declaredLength = unsignedInt(input.getInt());
        int tableCount = Short.toUnsignedInt(input.getShort());
        int reserved = Short.toUnsignedInt(input.getShort());
        long totalSfntSize = unsignedInt(input.getInt());
        input.position(WOFF_HEADER_SIZE);

        if (declaredLength != source.length) {
            throw new IOException("WOFF declared length does not match the file size");
        }
        if (reserved != 0) {
            throw new IOException("WOFF reserved header field must be zero");
        }
        if (tableCount <= 0 || tableCount > MAX_TABLES) {
            throw new IOException("WOFF table count is outside the supported range: " + tableCount);
        }
        long inputDirectoryEnd = WOFF_HEADER_SIZE + (long) tableCount * WOFF_DIRECTORY_ENTRY_SIZE;
        long minimumSfntSize = SFNT_HEADER_SIZE + (long) tableCount * SFNT_DIRECTORY_ENTRY_SIZE;
        if (inputDirectoryEnd > source.length
                || totalSfntSize < minimumSfntSize
                || totalSfntSize > MAX_FONT_BYTES) {
            throw new IOException("WOFF directory or reconstructed size is invalid");
        }

        List<TableRecord> mutableTables = new ArrayList<>(tableCount);
        int outputOffset = Math.toIntExact(minimumSfntSize);
        for (int index = 0; index < tableCount; index++) {
            int tag = input.getInt();
            long inputOffset = unsignedInt(input.getInt());
            long compressedLength = unsignedInt(input.getInt());
            long originalLength = unsignedInt(input.getInt());
            int checksum = input.getInt();
            validateTable(source.length, inputDirectoryEnd, inputOffset, compressedLength, originalLength);
            long nextOutputOffset = alignFour((long) outputOffset + originalLength);
            if (nextOutputOffset > totalSfntSize || nextOutputOffset > MAX_FONT_BYTES) {
                throw new IOException("WOFF reconstructed table data exceeds its declared size");
            }
            mutableTables.add(new TableRecord(
                    tag,
                    Math.toIntExact(inputOffset),
                    Math.toIntExact(compressedLength),
                    Math.toIntExact(originalLength),
                    checksum,
                    outputOffset));
            outputOffset = Math.toIntExact(nextOutputOffset);
        }
        if (outputOffset != totalSfntSize) {
            throw new IOException("WOFF reconstructed size does not match totalSfntSize");
        }

        @Unmodifiable List<TableRecord> tables = List.copyOf(mutableTables);
        byte @Unmodifiable [] output = new byte[outputOffset];
        ByteBuffer sfnt = ByteBuffer.wrap(output).order(ByteOrder.BIG_ENDIAN);
        writeSfntHeader(sfnt, flavor, tableCount);
        for (TableRecord table : tables) {
            sfnt.putInt(table.tag());
            sfnt.putInt(table.checksum());
            sfnt.putInt(table.outputOffset());
            sfnt.putInt(table.originalLength());
        }
        for (TableRecord table : tables) {
            byte @Unmodifiable [] tableData = readTable(source, table);
            System.arraycopy(tableData, 0, output, table.outputOffset(), tableData.length);
        }
        return output;
    }

    /// Validates one WOFF table's source and reconstructed bounds.
    ///
    /// @param inputLength complete WOFF length
    /// @param directoryEnd first legal table-data offset
    /// @param inputOffset table-data offset
    /// @param compressedLength stored table length
    /// @param originalLength reconstructed table length
    /// @throws IOException when any field violates WOFF constraints
    private static void validateTable(
            int inputLength,
            long directoryEnd,
            long inputOffset,
            long compressedLength,
            long originalLength) throws IOException {
        if (inputOffset < directoryEnd
                || compressedLength <= 0
                || originalLength <= 0
                || compressedLength > originalLength
                || inputOffset + compressedLength > inputLength) {
            throw new IOException("WOFF table bounds are invalid");
        }
    }

    /// Writes the standard sfnt offset table.
    ///
    /// @param output target sfnt buffer
    /// @param flavor original sfnt flavor
    /// @param tableCount table count
    private static void writeSfntHeader(ByteBuffer output, int flavor, int tableCount) {
        int maximumPowerOfTwo = Integer.highestOneBit(tableCount);
        int searchRange = maximumPowerOfTwo * SFNT_DIRECTORY_ENTRY_SIZE;
        int entrySelector = Integer.numberOfTrailingZeros(maximumPowerOfTwo);
        int rangeShift = tableCount * SFNT_DIRECTORY_ENTRY_SIZE - searchRange;
        output.putInt(flavor);
        output.putShort((short) tableCount);
        output.putShort((short) searchRange);
        output.putShort((short) entrySelector);
        output.putShort((short) rangeShift);
    }

    /// Reads or inflates one WOFF table to its exact original length.
    ///
    /// @param source complete WOFF data
    /// @param table validated table record
    /// @return newly allocated original table bytes
    /// @throws IOException when zlib data is malformed or has the wrong length
    private static byte @Unmodifiable [] readTable(
            byte @Unmodifiable [] source,
            TableRecord table) throws IOException {
        byte @Unmodifiable [] output = new byte[table.originalLength()];
        if (table.compressedLength() == table.originalLength()) {
            System.arraycopy(source, table.inputOffset(), output, 0, output.length);
            return output;
        }

        Inflater inflater = new Inflater();
        try {
            inflater.setInput(source, table.inputOffset(), table.compressedLength());
            int written = 0;
            while (!inflater.finished() && written < output.length) {
                int count = inflater.inflate(output, written, output.length - written);
                if (count == 0) {
                    if (inflater.needsDictionary() || inflater.needsInput()) {
                        break;
                    }
                    throw new IOException("WOFF table inflater made no progress");
                }
                written += count;
            }
            if (!inflater.finished() || written != output.length || inflater.getRemaining() != 0) {
                throw new IOException("WOFF table decompressed length is invalid");
            }
            return output;
        } catch (DataFormatException failure) {
            throw new IOException("WOFF table contains invalid zlib data", failure);
        } finally {
            inflater.end();
        }
    }

    /// Converts one unsigned 32-bit value to a positive long.
    ///
    /// @param value signed Java representation
    /// @return unsigned value
    private static long unsignedInt(int value) {
        return Integer.toUnsignedLong(value);
    }

    /// Rounds one non-negative length up to a four-byte boundary.
    ///
    /// @param value byte length
    /// @return aligned length
    private static long alignFour(long value) {
        return (value + 3L) & ~3L;
    }

    /// Validated WOFF table metadata plus its reconstructed sfnt offset.
    ///
    /// @param tag four-byte table tag
    /// @param inputOffset source WOFF offset
    /// @param compressedLength stored source length
    /// @param originalLength reconstructed table length
    /// @param checksum original sfnt table checksum
    /// @param outputOffset reconstructed sfnt offset
    @NotNullByDefault
    private record TableRecord(
            int tag,
            int inputOffset,
            int compressedLength,
            int originalLength,
            int checksum,
            int outputOffset) {
    }
}
