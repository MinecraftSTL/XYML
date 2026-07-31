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
package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.ui.swing.page.settings.JavaManagerRuntimeAcquisitionService.ArchiveLimits;
import space.minecraftstl.xyml.ui.swing.page.settings.JavaRuntimeAcquisitionBackend.CancellationCheck;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/// Applies fixed-memory TAR and ZIP container checks before third-party parsers allocate archive metadata.
///
/// The regular archive preflight validates logical paths and streamed contents. This earlier boundary inspects only
/// physical container records so attacker-controlled GNU/PAX payloads or ZIP central directories cannot consume an
/// unbounded heap before those logical budgets become active.
@NotNullByDefault
final class JavaRuntimeContainerPreflight {
    /// TAR physical record size in bytes.
    private static final int TAR_RECORD_BYTES = 512;

    /// Offset of the TAR size field within one physical header.
    private static final int TAR_SIZE_OFFSET = 124;

    /// Width of the TAR size field.
    private static final int TAR_SIZE_BYTES = 12;

    /// Offset of the TAR type flag within one physical header.
    private static final int TAR_TYPE_OFFSET = 156;

    /// Maximum metadata payload accepted from one GNU or PAX pseudo-entry.
    private static final long MAXIMUM_TAR_METADATA_ENTRY_BYTES = 64L * 1024L;

    /// Maximum cumulative GNU and PAX metadata payload accepted from one TAR.
    private static final long MAXIMUM_TAR_METADATA_BYTES = 1024L * 1024L;

    /// Maximum consecutive pseudo-entries accepted before one logical TAR entry.
    private static final int MAXIMUM_CONSECUTIVE_TAR_METADATA_ENTRIES = 8;

    /// Maximum additional physical metadata headers beyond the configured logical-entry ceiling.
    private static final int MAXIMUM_ADDITIONAL_TAR_METADATA_ENTRIES = 4_096;

    /// Sparse PAX key prefix rejected before Kala can construct attacker-sized sparse maps.
    private static final byte @Unmodifiable [] GNU_SPARSE_PREFIX =
            "GNU.sparse.".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    /// Minimum size of a classic ZIP end-of-central-directory record.
    private static final int ZIP_EOCD_BYTES = 22;

    /// Maximum classic ZIP comment size and therefore maximum EOCD search distance.
    private static final int ZIP_MAXIMUM_COMMENT_BYTES = 65_535;

    /// ZIP end-of-central-directory signature in little-endian form.
    private static final int ZIP_EOCD_SIGNATURE = 0x06054b50;

    /// ZIP64 end-of-central-directory locator signature in little-endian form.
    private static final int ZIP64_LOCATOR_SIGNATURE = 0x07064b50;

    /// ZIP64 end-of-central-directory signature in little-endian form.
    private static final int ZIP64_EOCD_SIGNATURE = 0x06064b50;

    /// ZIP central-file-header signature in little-endian form.
    private static final int ZIP_CENTRAL_ENTRY_SIGNATURE = 0x02014b50;

    /// Fixed size of one ZIP central-file header before variable metadata.
    private static final int ZIP_CENTRAL_ENTRY_BYTES = 46;

    /// Maximum central directory read by the JDK ZIP implementation after this preflight.
    private static final long MAXIMUM_ZIP_CENTRAL_DIRECTORY_BYTES = 64L * 1024L * 1024L;

    /// Maximum encoded ZIP entry name accepted before the JDK decodes it.
    private static final int MAXIMUM_ZIP_NAME_BYTES = 4_096;

    /// Maximum encoded ZIP entry comment accepted before the JDK decodes it.
    private static final int MAXIMUM_ZIP_ENTRY_COMMENT_BYTES = 4_096;

    /// Prevents construction of the stateless preflight namespace.
    private JavaRuntimeContainerPreflight() {
    }

    /// Scans expanded TAR physical records before `TarArchiveInputStream` handles GNU or PAX metadata.
    ///
    /// @param archive expanded TAR path
    /// @param limits configured logical and byte ceilings
    /// @param cancellationCheck cooperative cancellation callback
    /// @throws IOException when metadata, sparse records, counts, or physical offsets are unsafe
    static void preflightTarMetadata(
            Path archive,
            ArchiveLimits limits,
            CancellationCheck cancellationCheck) throws IOException {
        long archiveBytes = java.nio.file.Files.size(archive);
        long metadataEntryLimit = Math.min(
                MAXIMUM_TAR_METADATA_ENTRY_BYTES,
                limits.maxEntryUncompressedBytes());
        long metadataTotalLimit = Math.min(
                MAXIMUM_TAR_METADATA_BYTES,
                limits.maxTotalUncompressedBytes());
        long additionalMetadataEntries = Math.min(
                limits.maxEntries(),
                MAXIMUM_ADDITIONAL_TAR_METADATA_ENTRIES);
        long maximumPhysicalEntries = limits.maxEntries() + additionalMetadataEntries;
        byte[] header = new byte[TAR_RECORD_BYTES];
        long offset = 0L;
        long metadataBytes = 0L;
        long physicalEntries = 0L;
        int consecutiveMetadataEntries = 0;

        try (FileChannel channel = FileChannel.open(archive, StandardOpenOption.READ)) {
            while (offset < archiveBytes) {
                cancellationCheck.checkCancelled();
                if (archiveBytes - offset < TAR_RECORD_BYTES) {
                    throw new EOFException("Truncated Java TAR physical header");
                }
                readFully(channel, offset, ByteBuffer.wrap(header), "Truncated Java TAR physical header");
                if (isZeroBlock(header)) {
                    if (consecutiveMetadataEntries > 0) {
                        throw new IOException("Java TAR metadata is not followed by a logical entry");
                    }
                    return;
                }

                physicalEntries++;
                if (physicalEntries > maximumPhysicalEntries) {
                    throw new IOException("Java TAR contains too many physical metadata records");
                }
                long payloadBytes = parseTarNumber(header, TAR_SIZE_OFFSET, TAR_SIZE_BYTES);
                int type = Byte.toUnsignedInt(header[TAR_TYPE_OFFSET]);
                if (type == 'S') {
                    throw new IOException("GNU sparse Java TAR entries are unsupported");
                }

                boolean metadata = isTarMetadataType(type);
                if (metadata) {
                    consecutiveMetadataEntries++;
                    if (consecutiveMetadataEntries > MAXIMUM_CONSECUTIVE_TAR_METADATA_ENTRIES) {
                        throw new IOException("Java TAR contains too many consecutive metadata records");
                    }
                    if (payloadBytes > metadataEntryLimit
                            || payloadBytes > metadataTotalLimit - metadataBytes) {
                        throw new IOException("Java TAR metadata exceeds its byte limit");
                    }
                    metadataBytes += payloadBytes;
                } else {
                    consecutiveMetadataEntries = 0;
                }

                long payloadOffset = Math.addExact(offset, TAR_RECORD_BYTES);
                long paddedPayloadBytes = roundTarPayloadBytes(payloadBytes);
                long nextOffset;
                try {
                    nextOffset = Math.addExact(payloadOffset, paddedPayloadBytes);
                } catch (ArithmeticException failure) {
                    throw new IOException("Java TAR physical offset overflows", failure);
                }
                if (nextOffset > archiveBytes) {
                    throw new EOFException("Truncated Java TAR payload");
                }
                if (metadata && isPaxMetadataType(type)
                        && containsAscii(
                                channel,
                                payloadOffset,
                                payloadBytes,
                                GNU_SPARSE_PREFIX,
                                cancellationCheck)) {
                    throw new IOException("GNU sparse Java TAR metadata is unsupported");
                }
                offset = nextOffset;
            }
            if (consecutiveMetadataEntries > 0) {
                throw new IOException("Java TAR metadata is not followed by a logical entry");
            }
        } catch (ArithmeticException failure) {
            throw new IOException("Java TAR metadata budget overflows", failure);
        }
    }

    /// Validates ZIP EOCD, ZIP64 metadata, entry count, and central-directory size before `ZipFile` construction.
    ///
    /// @param archive ZIP path
    /// @param limits configured entry and temporary-byte ceilings
    /// @param cancellationCheck cooperative cancellation callback
    /// @throws IOException when the central directory is absent, split, oversized, inconsistent, or malformed
    static void preflightZipCentralDirectory(
            Path archive,
            ArchiveLimits limits,
            CancellationCheck cancellationCheck) throws IOException {
        long archiveBytes = java.nio.file.Files.size(archive);
        if (archiveBytes < ZIP_EOCD_BYTES) {
            throw new IOException("Java ZIP has no end-of-central-directory record");
        }
        int tailBytes = (int) Math.min(
                archiveBytes,
                ZIP_EOCD_BYTES + (long) ZIP_MAXIMUM_COMMENT_BYTES);
        ByteBuffer tail = littleEndianBuffer(tailBytes);

        try (FileChannel channel = FileChannel.open(archive, StandardOpenOption.READ)) {
            cancellationCheck.checkCancelled();
            long tailOffset = archiveBytes - tailBytes;
            readFully(channel, tailOffset, tail, "Truncated Java ZIP end record");
            int eocdIndex = findEocd(tail);
            if (eocdIndex < 0) {
                throw new IOException("Java ZIP has no valid end-of-central-directory record");
            }
            long eocdOffset = tailOffset + eocdIndex;
            ZipDirectory directory = readZipDirectory(channel, tail, eocdIndex, eocdOffset);
            validateZipDirectory(directory, archiveBytes, limits);
            scanZipCentralEntries(channel, directory, limits, cancellationCheck);
        } catch (ArithmeticException failure) {
            throw new IOException("Java ZIP central-directory offset overflows", failure);
        }
    }

    /// Reads classic or ZIP64 central-directory coordinates without allocating variable metadata.
    ///
    /// @param channel open ZIP channel
    /// @param tail fixed-size EOCD search buffer
    /// @param eocdIndex EOCD index within the tail buffer
    /// @param eocdOffset absolute EOCD offset
    /// @return immutable central-directory coordinates
    /// @throws IOException when disk or ZIP64 metadata is malformed
    private static ZipDirectory readZipDirectory(
            FileChannel channel,
            ByteBuffer tail,
            int eocdIndex,
            long eocdOffset) throws IOException {
        int diskNumber = unsignedShort(tail, eocdIndex + 4);
        int centralDisk = unsignedShort(tail, eocdIndex + 6);
        int entriesOnDisk = unsignedShort(tail, eocdIndex + 8);
        int totalEntries = unsignedShort(tail, eocdIndex + 10);
        long centralBytes = unsignedInt(tail, eocdIndex + 12);
        long centralOffset = unsignedInt(tail, eocdIndex + 16);
        boolean zip64 = entriesOnDisk == 0xffff
                || totalEntries == 0xffff
                || centralBytes == 0xffff_ffffL
                || centralOffset == 0xffff_ffffL;
        if (!zip64) {
            requireSingleZipDisk(diskNumber, centralDisk, entriesOnDisk, totalEntries);
            return new ZipDirectory(totalEntries, centralOffset, centralBytes, eocdOffset);
        }

        long locatorOffset = eocdOffset - 20L;
        if (locatorOffset < 0L) {
            throw new IOException("Java ZIP64 locator is missing");
        }
        ByteBuffer locator = littleEndianBuffer(20);
        readFully(channel, locatorOffset, locator, "Truncated Java ZIP64 locator");
        if (locator.getInt(0) != ZIP64_LOCATOR_SIGNATURE
                || locator.getInt(4) != 0
                || locator.getInt(16) != 1) {
            throw new IOException("Split or malformed Java ZIP64 archive");
        }
        long zip64Offset = locator.getLong(8);
        if (zip64Offset < 0L || zip64Offset > locatorOffset - 56L) {
            throw new IOException("Java ZIP64 end record has an invalid offset");
        }
        ByteBuffer zip64Record = littleEndianBuffer(56);
        readFully(channel, zip64Offset, zip64Record, "Truncated Java ZIP64 end record");
        long recordBytes = zip64Record.getLong(4);
        long zip64End = Math.addExact(zip64Offset, Math.addExact(12L, recordBytes));
        if (zip64Record.getInt(0) != ZIP64_EOCD_SIGNATURE
                || recordBytes < 44L
                || zip64End > locatorOffset
                || zip64Record.getInt(16) != 0
                || zip64Record.getInt(20) != 0) {
            throw new IOException("Split or malformed Java ZIP64 end record");
        }
        long entriesOnZip64Disk = zip64Record.getLong(24);
        long zip64Entries = zip64Record.getLong(32);
        long zip64CentralBytes = zip64Record.getLong(40);
        long zip64CentralOffset = zip64Record.getLong(48);
        if (entriesOnZip64Disk < 0L
                || zip64Entries < 0L
                || zip64CentralBytes < 0L
                || zip64CentralOffset < 0L
                || entriesOnZip64Disk != zip64Entries) {
            throw new IOException("Java ZIP64 central-directory values are invalid");
        }
        return new ZipDirectory(
                zip64Entries,
                zip64CentralOffset,
                zip64CentralBytes,
                zip64Offset);
    }

    /// Enforces one-disk classic ZIP counts.
    ///
    /// @param diskNumber EOCD disk number
    /// @param centralDisk central-directory disk number
    /// @param entriesOnDisk entries on the EOCD disk
    /// @param totalEntries total central-directory entries
    /// @throws IOException when the archive spans disks or counts disagree
    private static void requireSingleZipDisk(
            int diskNumber,
            int centralDisk,
            int entriesOnDisk,
            int totalEntries) throws IOException {
        if (diskNumber != 0 || centralDisk != 0 || entriesOnDisk != totalEntries) {
            throw new IOException("Split Java ZIP archives are unsupported");
        }
    }

    /// Enforces configured ZIP count, metadata, and physical-offset ceilings.
    ///
    /// @param directory parsed directory coordinates
    /// @param archiveBytes complete ZIP byte length
    /// @param limits configured resource ceilings
    /// @throws IOException when any coordinate or ceiling is invalid
    private static void validateZipDirectory(
            ZipDirectory directory,
            long archiveBytes,
            ArchiveLimits limits) throws IOException {
        long centralLimit = Math.min(
                MAXIMUM_ZIP_CENTRAL_DIRECTORY_BYTES,
                limits.maxTemporaryArchiveBytes());
        long centralEnd = Math.addExact(directory.offset(), directory.bytes());
        if (directory.entries() > limits.maxEntries()
                || directory.bytes() > centralLimit
                || directory.offset() > archiveBytes
                || centralEnd > directory.boundaryOffset()) {
            throw new IOException("Java ZIP central directory exceeds its resource or offset limit");
        }
    }

    /// Scans fixed ZIP central headers and skips variable metadata without allocating it.
    ///
    /// @param channel open ZIP channel
    /// @param directory validated directory coordinates
    /// @param limits configured entry ceiling
    /// @param cancellationCheck cooperative cancellation callback
    /// @throws IOException when an entry header, name, comment, count, or final size is inconsistent
    private static void scanZipCentralEntries(
            FileChannel channel,
            ZipDirectory directory,
            ArchiveLimits limits,
            CancellationCheck cancellationCheck) throws IOException {
        ByteBuffer header = littleEndianBuffer(ZIP_CENTRAL_ENTRY_BYTES);
        long cursor = directory.offset();
        long centralEnd = Math.addExact(directory.offset(), directory.bytes());
        for (long index = 0L; index < directory.entries(); index++) {
            cancellationCheck.checkCancelled();
            if (index >= limits.maxEntries() || centralEnd - cursor < ZIP_CENTRAL_ENTRY_BYTES) {
                throw new IOException("Java ZIP central-directory entry count is invalid");
            }
            readFully(channel, cursor, header, "Truncated Java ZIP central entry");
            if (header.getInt(0) != ZIP_CENTRAL_ENTRY_SIGNATURE) {
                throw new IOException("Java ZIP central entry has an invalid signature");
            }
            int nameBytes = unsignedShort(header, 28);
            int extraBytes = unsignedShort(header, 30);
            int commentBytes = unsignedShort(header, 32);
            if (nameBytes == 0
                    || nameBytes > MAXIMUM_ZIP_NAME_BYTES
                    || commentBytes > MAXIMUM_ZIP_ENTRY_COMMENT_BYTES) {
                throw new IOException("Java ZIP central entry metadata exceeds its per-entry limit");
            }
            long entryBytes = ZIP_CENTRAL_ENTRY_BYTES
                    + (long) nameBytes
                    + extraBytes
                    + commentBytes;
            cursor = Math.addExact(cursor, entryBytes);
            if (cursor > centralEnd) {
                throw new IOException("Java ZIP central entry exceeds the declared directory");
            }
        }
        if (cursor != centralEnd) {
            throw new IOException("Java ZIP central-directory size does not match its entries");
        }
    }

    /// Locates the last EOCD whose declared comment reaches the physical end of the ZIP.
    ///
    /// @param tail fixed-size ZIP tail buffer
    /// @return EOCD index, or `-1` when absent
    private static int findEocd(ByteBuffer tail) {
        for (int index = tail.limit() - ZIP_EOCD_BYTES; index >= 0; index--) {
            if (tail.getInt(index) == ZIP_EOCD_SIGNATURE) {
                int commentBytes = unsignedShort(tail, index + 20);
                if (index + ZIP_EOCD_BYTES + commentBytes == tail.limit()) {
                    return index;
                }
            }
        }
        return -1;
    }

    /// Returns whether one TAR type flag denotes GNU, PAX, or Solaris metadata consumed before a logical entry.
    ///
    /// @param type unsigned TAR type flag
    /// @return whether the record is parser metadata
    private static boolean isTarMetadataType(int type) {
        return type == 'L' || type == 'K' || type == 'x' || type == 'g' || type == 'X';
    }

    /// Returns whether a TAR metadata payload may contain PAX key-value records.
    ///
    /// @param type unsigned TAR type flag
    /// @return whether the payload is PAX-style metadata
    private static boolean isPaxMetadataType(int type) {
        return type == 'x' || type == 'g' || type == 'X';
    }

    /// Parses one non-negative TAR octal or base-256 number without allocating text.
    ///
    /// @param header physical TAR header
    /// @param offset numeric field offset
    /// @param length numeric field width
    /// @return parsed non-negative value
    /// @throws IOException when the field is negative, malformed, or overflows
    private static long parseTarNumber(
            byte @Unmodifiable [] header,
            int offset,
            int length) throws IOException {
        int first = Byte.toUnsignedInt(header[offset]);
        if ((first & 0x80) != 0) {
            if ((first & 0x40) != 0) {
                throw new IOException("Negative Java TAR size is unsupported");
            }
            long value = first & 0x3f;
            for (int index = offset + 1; index < offset + length; index++) {
                if (value > (Long.MAX_VALUE >>> 8)) {
                    throw new IOException("Java TAR size overflows");
                }
                value = (value << 8) | Byte.toUnsignedInt(header[index]);
            }
            return value;
        }

        long value = 0L;
        boolean foundDigit = false;
        boolean foundTerminator = false;
        for (int index = offset; index < offset + length; index++) {
            int current = Byte.toUnsignedInt(header[index]);
            if (current == 0 || current == ' ') {
                if (foundDigit) {
                    foundTerminator = true;
                }
                continue;
            }
            if (foundTerminator || current < '0' || current > '7') {
                throw new IOException("Java TAR size field is malformed");
            }
            foundDigit = true;
            if (value > (Long.MAX_VALUE - (current - '0')) / 8L) {
                throw new IOException("Java TAR size overflows");
            }
            value = value * 8L + current - '0';
        }
        return value;
    }

    /// Rounds one TAR payload to its next complete physical record.
    ///
    /// @param payloadBytes declared payload bytes
    /// @return padded payload bytes
    /// @throws IOException when rounding overflows
    private static long roundTarPayloadBytes(long payloadBytes) throws IOException {
        if (payloadBytes < 0L || payloadBytes > Long.MAX_VALUE - (TAR_RECORD_BYTES - 1L)) {
            throw new IOException("Java TAR payload size overflows");
        }
        return ((payloadBytes + TAR_RECORD_BYTES - 1L) / TAR_RECORD_BYTES) * TAR_RECORD_BYTES;
    }

    /// Searches one bounded metadata payload for an ASCII marker with a fixed-size buffer.
    ///
    /// @param channel open TAR channel
    /// @param offset payload offset
    /// @param bytes payload byte length
    /// @param pattern non-empty ASCII marker
    /// @param cancellationCheck cooperative cancellation callback
    /// @return whether the marker occurs
    /// @throws IOException when the payload cannot be read fully
    private static boolean containsAscii(
            FileChannel channel,
            long offset,
            long bytes,
            byte @Unmodifiable [] pattern,
            CancellationCheck cancellationCheck) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(8 * 1024);
        long cursor = offset;
        long remaining = bytes;
        int matched = 0;
        while (remaining > 0L) {
            cancellationCheck.checkCancelled();
            int requested = (int) Math.min(buffer.capacity(), remaining);
            buffer.clear();
            buffer.limit(requested);
            readFully(channel, cursor, buffer, "Truncated Java TAR metadata payload");
            for (int index = 0; index < buffer.limit(); index++) {
                byte current = buffer.get(index);
                if (current == pattern[matched]) {
                    matched++;
                    if (matched == pattern.length) {
                        return true;
                    }
                } else {
                    matched = current == pattern[0] ? 1 : 0;
                }
            }
            cursor += requested;
            remaining -= requested;
        }
        return false;
    }

    /// Reads exactly one fixed-size buffer from an absolute file offset.
    ///
    /// @param channel open file channel
    /// @param offset absolute read offset
    /// @param target cleared or reusable destination buffer
    /// @param truncatedMessage error text used for premature EOF
    /// @throws IOException when the requested bytes are unavailable
    private static void readFully(
            FileChannel channel,
            long offset,
            ByteBuffer target,
            String truncatedMessage) throws IOException {
        target.position(0);
        channel.position(offset);
        while (target.hasRemaining()) {
            int read = channel.read(target);
            if (read < 0) {
                throw new EOFException(truncatedMessage);
            }
        }
        target.flip();
    }

    /// Returns whether a physical TAR record contains only zero bytes.
    ///
    /// @param record 512-byte physical TAR record
    /// @return whether every byte is zero
    private static boolean isZeroBlock(byte @Unmodifiable [] record) {
        for (byte value : record) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    /// Creates one little-endian heap buffer for fixed-size ZIP metadata.
    ///
    /// @param bytes buffer capacity
    /// @return empty little-endian buffer
    private static ByteBuffer littleEndianBuffer(int bytes) {
        return ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    /// Reads an unsigned little-endian ZIP short.
    ///
    /// @param buffer little-endian ZIP buffer
    /// @param offset field offset
    /// @return unsigned 16-bit value
    private static int unsignedShort(ByteBuffer buffer, int offset) {
        return Short.toUnsignedInt(buffer.getShort(offset));
    }

    /// Reads an unsigned little-endian ZIP integer.
    ///
    /// @param buffer little-endian ZIP buffer
    /// @param offset field offset
    /// @return unsigned 32-bit value
    private static long unsignedInt(ByteBuffer buffer, int offset) {
        return Integer.toUnsignedLong(buffer.getInt(offset));
    }

    /// Immutable coordinates and counts for one bounded ZIP central directory.
    ///
    /// @param entries declared central entry count
    /// @param offset absolute central-directory offset
    /// @param bytes declared central-directory bytes
    /// @param boundaryOffset first end-record offset after the central directory
    @NotNullByDefault
    private record ZipDirectory(
            long entries,
            long offset,
            long bytes,
            long boundaryOffset) {
        /// Rejects negative or absent ZIP directory coordinates.
        private ZipDirectory {
            if (entries < 0L || offset < 0L || bytes < 0L || boundaryOffset < 0L) {
                throw new IllegalArgumentException("ZIP central-directory coordinates must be non-negative");
            }
        }
    }
}
