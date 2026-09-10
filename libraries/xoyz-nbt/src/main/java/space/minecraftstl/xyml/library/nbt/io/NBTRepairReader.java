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
// Added by MinecraftSTL in 2026 for bounded tolerant XoyzNBT reads.
package space.minecraftstl.xyml.library.nbt.io;

import net.jpountz.lz4.LZ4BlockInputStream;
import space.minecraftstl.xyml.library.nbt.internal.input.InputSource;
import space.minecraftstl.xyml.library.nbt.internal.input.NBTInput;
import space.minecraftstl.xyml.library.nbt.internal.input.RawDataReader;
import space.minecraftstl.xyml.library.nbt.tag.ByteArrayTag;
import space.minecraftstl.xyml.library.nbt.tag.ByteTag;
import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;
import space.minecraftstl.xyml.library.nbt.tag.DoubleTag;
import space.minecraftstl.xyml.library.nbt.tag.FloatTag;
import space.minecraftstl.xyml.library.nbt.tag.IntArrayTag;
import space.minecraftstl.xyml.library.nbt.tag.IntTag;
import space.minecraftstl.xyml.library.nbt.tag.ListTag;
import space.minecraftstl.xyml.library.nbt.tag.LongArrayTag;
import space.minecraftstl.xyml.library.nbt.tag.LongTag;
import space.minecraftstl.xyml.library.nbt.tag.ShortTag;
import space.minecraftstl.xyml.library.nbt.tag.StringTag;
import space.minecraftstl.xyml.library.nbt.tag.Tag;
import space.minecraftstl.xyml.library.nbt.tag.TagType;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.Adler32;
import java.util.zip.DataFormatException;
import java.util.zip.CRC32;
import java.util.zip.Inflater;

/// Best-effort standalone NBT reader used only after strict decoding fails.
///
/// This class intentionally never weakens the strict codec. It decodes the outer envelope into a
/// bounded byte array first, then tries the strict raw NBT parser before falling back to the
/// recovery parser. Every discarded byte or guessed value is represented by an [NBTReadIssue].
@NotNullByDefault
final class NBTRepairReader {
    /// Reads one standalone tag, preserving its detected outer compression class.
    ///
    /// @param encoded complete or damaged encoded bytes
    /// @param expectedClass expected root class
    /// @param codec codec whose edition controls byte order and string decoding
    /// @param limits defensive allocation limits
    /// @param <T> root type
    /// @return detached recovered root and report
    /// @throws IOException when no trustworthy root can be recovered or a limit is exceeded
    static <T extends Tag> NBTReadResult<T> read(byte[] encoded, Class<T> expectedClass,
                                                  NBTCodec codec, NBTReadLimits limits) throws IOException {
        byte[] source = Objects.requireNonNull(encoded, "encoded");
        Class<T> rootClass = Objects.requireNonNull(expectedClass, "expectedClass");
        NBTCodec selectedCodec = Objects.requireNonNull(codec, "codec");
        NBTReadLimits selectedLimits = Objects.requireNonNull(limits, "limits");
        if ((long) source.length > selectedLimits.maxEncodedBytes()) {
            throw new IOException("NBT input exceeds the encoded read limit");
        }

        NBTFileEncoding declared = detect(source);
        List<NBTReadIssue> issues = new ArrayList<>();

        // The declared envelope is authoritative while it is complete. Do not probe alternate
        // algorithms for an already-valid file: a valid raw payload can coincidentally begin
        // with bytes that look like another compression header.
        byte @Nullable [] declaredPayload = null;
        try {
            declaredPayload = decodeStrictEnvelope(source, declared, selectedLimits);
        } catch (IOException | RuntimeException ignored) {
            // Continue with the bounded alternate-candidate probe below.
        }
        if (declaredPayload != null) {
            try {
                T strictRoot = parseStrict(declaredPayload, selectedCodec, rootClass, selectedLimits);
                selectedLimits.newDocumentBudget().consume(declaredPayload.length);
                return new NBTReadResult<>(strictRoot, NBTReadReport.clean(declared));
            } catch (IOException | RuntimeException strictBodyFailure) {
                issues.add(issue(NBTReadIssue.Severity.RECOVERED, "STRICT_PARSE_FAILED", "",
                        "声明的压缩算法解码成功，但 NBT 严格读取失败：" + message(strictBodyFailure)));
            }
        }

        // A complete strict candidate is the only trustworthy way to identify a damaged or
        // mislabeled envelope. Every candidate owns a fresh budget so probing rejected formats
        // cannot consume the selected document's cumulative allowance.
        List<StrictCandidate<T>> strictCandidates = new ArrayList<>();
        for (NBTFileEncoding candidateEncoding : candidateEncodings()) {
            try {
                byte[] raw = decodeStrictEnvelope(source, candidateEncoding, selectedLimits);
                try {
                    strictCandidates.add(new StrictCandidate<>(candidateEncoding,
                            raw, parseStrict(raw, selectedCodec, rootClass, selectedLimits)));
                } catch (IOException | RuntimeException ignored) {
                    // The envelope is complete, but its NBT body may need tolerant recovery.
                }
            } catch (IOException | RuntimeException ignored) {
                // A candidate which cannot be completely decoded is not eligible for selection.
            }
        }

        if (strictCandidates.size() > 1) {
            throw new IOException("Ambiguous NBT compression envelope; refusing to guess");
        }
        if (strictCandidates.size() == 1) {
            StrictCandidate<T> candidate = strictCandidates.get(0);
            selectedLimits.newDocumentBudget().consume(candidate.bytes().length);
            if (candidate.encoding() != declared) {
                issues.add(issue(NBTReadIssue.Severity.RECOVERED, "ENCODING_RECOVERED", "",
                        "已从完整字节范围确认存储压缩算法为 " + candidate.encoding()));
            }
            return new NBTReadResult<>(candidate.root(), new NBTReadReport(candidate.encoding(), false, issues));
        }

        // No unique complete candidate was found. Only the declared algorithm may now be
        // recovered tolerantly; arbitrary byte-offset scanning is deliberately forbidden. When
        // its envelope was complete but its body was malformed, reuse that bounded payload so a
        // damaged TAG_End or field can still be repaired without decoding a second time.
        if (declaredPayload != null) {
            selectedLimits.newDocumentBudget().consume(declaredPayload.length);
            return recoverPayload(declaredPayload, declared, selectedCodec, rootClass, selectedLimits, issues);
        }
        NBTReadLimits.Budget budget = selectedLimits.newDocumentBudget();
        DecodedPayload tolerant = decode(source, declared, selectedLimits, budget, issues);
        return recoverPayload(tolerant.bytes(), declared, selectedCodec, rootClass, selectedLimits, issues);
    }

    /// Recovers one already bounded payload after strict object parsing failed.
    private static <T extends Tag> NBTReadResult<T> recoverPayload(byte[] payload, NBTFileEncoding encoding,
                                                                    NBTCodec codec, Class<T> rootClass,
                                                                    NBTReadLimits limits,
                                                                    List<NBTReadIssue> issues) throws IOException {
        Cursor cursor = new Cursor(payload, codec.getEdition().byteOrder());
        ParseContext context = new ParseContext(limits, issues);
        @Nullable Tag recovered;
        try {
            recovered = readNamedTag(cursor, "", context, codec.getEdition());
        } catch (ParseFailure failure) {
            issues.add(issue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS, "ROOT_UNREADABLE", "",
                    failure.getMessage()));
            recovered = null;
        }
        if (recovered == null) {
            throw new IOException("Tolerant NBT read could not recover a root tag");
        }
        if (cursor.remaining() > 0) {
            boolean zeroPadding = cursor.remainingAreZero();
            NBTReadIssue.Severity severity = zeroPadding
                    ? NBTReadIssue.Severity.RECOVERED
                    : NBTReadIssue.Severity.PARTIAL_DATA_LOSS;
            String code = zeroPadding ? "TRAILING_ZERO_PADDING" : "TRAILING_BYTES";
            issues.add(issue(severity, code, "", zeroPadding
                    ? "根标签后的零填充已忽略"
                    : "根标签后的附加字节无法可靠归属，已保留主体内容"));
        }
        T typed;
        try {
            typed = rootClass.cast(recovered);
        } catch (ClassCastException exception) {
            throw new IOException("Recovered NBT root has an unexpected type", exception);
        }
        NBTReadReport report = new NBTReadReport(encoding, false, issues);
        return new NBTReadResult<>(typed, report);
    }

    /// Returns every supported standalone envelope in a deterministic order.
    ///
    /// This method is reached only after the header-declared strict path has failed. Trying all
    /// four bounded decoders is intentional: a damaged or mislabeled header must not make the
    /// reader guess from a magic prefix. A candidate is accepted only after complete envelope
    /// validation and strict NBT consumption; multiple accepted candidates are rejected by the
    /// caller as ambiguous.
    private static @Unmodifiable List<NBTFileEncoding> candidateEncodings() {
        return List.of(NBTFileEncoding.RAW, NBTFileEncoding.GZIP, NBTFileEncoding.ZLIB, NBTFileEncoding.LZ4);
    }

    /// Decodes one envelope completely and verifies its compression trailer where applicable.
    private static byte[] decodeStrictEnvelope(byte[] encoded, NBTFileEncoding encoding,
                                               NBTReadLimits limits) throws IOException {
        if (encoding == NBTFileEncoding.RAW) {
            if ((long) encoded.length > limits.maxDecompressedBytes()) {
                throw new IOException("Uncompressed NBT payload exceeds the read limit");
            }
            return encoded.clone();
        }
        return switch (encoding) {
            case GZIP -> NBTCodec.decodeGzipStrict(encoded,
                    Math.toIntExact(Math.min(limits.maxDecompressedBytes(), Integer.MAX_VALUE)));
            case ZLIB -> decodeZlibStrict(encoded, limits);
            case LZ4 -> decodeLz4Strict(encoded, limits);
            case RAW, REGION -> throw new IOException("Invalid standalone candidate: " + encoding);
        };
    }

    /// Strictly decodes a zlib stream under the configured payload limit.
    private static byte[] decodeZlibStrict(byte[] encoded, NBTReadLimits limits) throws IOException {
        if (encoded.length < 2 || !isZlibHeader(encoded[0], encoded[1])) {
            throw new IOException("Invalid ZLIB header");
        }
        if ((Byte.toUnsignedInt(encoded[1]) & 0x20) != 0) {
            throw new IOException("ZLIB preset dictionaries are not supported");
        }
        long maximum = Math.min(limits.maxDecompressedBytes(), Integer.MAX_VALUE);
        Inflater inflater = new Inflater();
        ByteArrayOutputStream output = new ByteArrayOutputStream(initialCapacity(encoded.length));
        try {
            inflater.setInput(encoded);
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int count;
                try {
                    count = inflater.inflate(buffer);
                } catch (DataFormatException exception) {
                    throw new IOException("Invalid ZLIB payload", exception);
                }
                if (count > 0) {
                    if ((long) output.size() + count > maximum) {
                        throw new IOException("ZLIB payload is too large after decompression");
                    }
                    output.write(buffer, 0, count);
                } else if (inflater.needsDictionary() || inflater.needsInput()) {
                    throw new IOException("Truncated ZLIB payload");
                } else {
                    throw new IOException("ZLIB decompressor made no progress");
                }
            }
            if (inflater.getRemaining() != 0) {
                throw new IOException("Trailing data after ZLIB payload");
            }
            return output.toByteArray();
        } finally {
            inflater.end();
        }
    }

    /// Strictly decodes an LZ4 block stream under the configured payload limit.
    private static byte[] decodeLz4Strict(byte[] encoded, NBTReadLimits limits) throws IOException {
        long maximum = Math.min(limits.maxDecompressedBytes(), Integer.MAX_VALUE);
        ByteArrayInputStream source = new ByteArrayInputStream(encoded);
        ByteArrayOutputStream output = new ByteArrayOutputStream(initialCapacity(encoded.length));
        try (InputStream input = new LZ4BlockInputStream(source)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count == 0) {
                    final int single;
                    try {
                        single = input.read();
                    } catch (IOException exception) {
                        throw new IOException("Invalid LZ4 payload", exception);
                    }
                    if (single < 0) {
                        break;
                    }
                    if (output.size() >= maximum) {
                        throw new IOException("LZ4 payload is too large after decompression");
                    }
                    output.write(single);
                    continue;
                }
                if ((long) output.size() + count > maximum) {
                    throw new IOException("LZ4 payload is too large after decompression");
                }
                output.write(buffer, 0, count);
            }
            if (source.available() != 0) {
                throw new IOException("Trailing data after LZ4 payload");
            }
            return output.toByteArray();
        } catch (LinkageError error) {
            throw new IOException("LZ4 support is unavailable", error);
        }
    }

    /// Recovers one region slot whose compression marker is known but whose payload is damaged.
    ///
    /// Region frames do not carry a standalone envelope, therefore the caller supplies the
    /// marker-derived compression type. The returned report always identifies the REGION profile;
    /// the caller may add a slot path to each issue before aggregating it into a file report.
    ///
    /// @param compressed compressed bytes from the inline frame or external companion
    /// @param compression marker-derived compression type
    /// @param limits defensive allocation limits
    /// @return recovered compound root and diagnostics
    /// @throws IOException if decompression yields no recoverable root or a limit is exceeded
    static NBTReadResult<CompoundTag> readRegionPayload(byte[] compressed,
                                                         NBTRegionFile.CompressionType compression,
                                                         NBTReadLimits limits) throws IOException {
        NBTReadLimits selectedLimits = Objects.requireNonNull(limits, "limits");
        return readRegionPayload(compressed, compression, selectedLimits, selectedLimits.newDocumentBudget());
    }

    /// Recovers a region payload using a caller-owned cumulative document budget.
    ///
    /// @param compressed compressed bytes from the inline frame or external companion
    /// @param compression marker-derived compression type
    /// @param limits defensive allocation limits
    /// @param budget cumulative decompressed budget shared by the containing document
    /// @return recovered compound root and diagnostics
    /// @throws IOException if decompression yields no recoverable root or a limit is exceeded
    static NBTReadResult<CompoundTag> readRegionPayload(byte[] compressed,
                                                         NBTRegionFile.CompressionType compression,
                                                         NBTReadLimits limits,
                                                         NBTReadLimits.Budget budget) throws IOException {
        byte[] source = Objects.requireNonNull(compressed, "compressed");
        NBTRegionFile.CompressionType selectedCompression = Objects.requireNonNull(compression, "compression");
        NBTReadLimits selectedLimits = Objects.requireNonNull(limits, "limits");
        NBTReadLimits.Budget selectedBudget = Objects.requireNonNull(budget, "budget");
        if ((long) source.length > selectedLimits.maxEncodedBytes()) {
            throw new IOException("Region chunk payload exceeds the encoded read limit");
        }

        List<NBTReadIssue> issues = new ArrayList<>();
        byte[] raw = decodeRegion(source, selectedCompression, selectedLimits, selectedBudget, issues);
        NBTCodec codec = NBTCodec.of();
        try {
            CompoundTag strictRoot = parseStrict(raw, codec, CompoundTag.class, selectedLimits);
            NBTReadReport report = issues.isEmpty()
                    ? NBTReadReport.clean(NBTFileEncoding.REGION)
                    : new NBTReadReport(NBTFileEncoding.REGION, false, issues);
            return new NBTReadResult<>(strictRoot, report);
        } catch (IOException | RuntimeException strictFailure) {
            issues.add(issue(NBTReadIssue.Severity.RECOVERED, "STRICT_SLOT_PARSE_FAILED", "",
                    "槽位严格读取失败，已尝试有限度恢复：" + message(strictFailure)));
        }

        Cursor cursor = new Cursor(raw, codec.getEdition().byteOrder());
        ParseContext context = new ParseContext(selectedLimits, issues);
        @Nullable Tag recovered;
        try {
            recovered = readNamedTag(cursor, "", context, codec.getEdition());
        } catch (ParseFailure failure) {
            issues.add(issue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS, "SLOT_ROOT_UNREADABLE", "",
                    failure.getMessage()));
            recovered = null;
        }
        if (!(recovered instanceof CompoundTag compound)) {
            throw new IOException("Tolerant region read could not recover a compound root");
        }
        if (cursor.remaining() > 0) {
            boolean zeroPadding = cursor.remainingAreZero();
            NBTReadIssue.Severity severity = zeroPadding
                    ? NBTReadIssue.Severity.RECOVERED
                    : NBTReadIssue.Severity.PARTIAL_DATA_LOSS;
            String code = zeroPadding ? "SLOT_TRAILING_ZERO_PADDING" : "SLOT_TRAILING_BYTES";
            issues.add(issue(severity, code, "", zeroPadding
                    ? "槽位根标签后的零填充已忽略"
                    : "槽位根标签后的附加字节无法可靠归属，已保留主体内容"));
        }
        return new NBTReadResult<>(compound,
                new NBTReadReport(NBTFileEncoding.REGION, false, issues));
    }

    /// Decodes one region payload while retaining partial output on a damaged stream.
    private static byte[] decodeRegion(byte[] encoded, NBTRegionFile.CompressionType compression,
                                       NBTReadLimits limits, NBTReadLimits.Budget budget,
                                       List<NBTReadIssue> issues) throws IOException {
        return switch (compression) {
            case UNCOMPRESSED -> {
                if ((long) encoded.length > limits.maxDecompressedBytes()) {
                    throw new IOException("Uncompressed region payload exceeds the read limit");
                }
                budget.consume(encoded.length);
                yield encoded.clone();
            }
            case GZIP -> inflateGzip(encoded, limits, budget, issues);
            case ZLIB -> inflateZlib(encoded, limits, budget, issues);
            case LZ4 -> inflateLz4(encoded, limits, budget, issues);
        };
    }

    /// Detects a known envelope without treating a raw TAG_String as zlib.
    private static NBTFileEncoding detect(byte[] encoded) {
        try {
            return NBTFileEncoding.detectStandalone(encoded);
        } catch (IOException ignored) {
            // A preset-dictionary zlib stream is still a zlib profile for reporting/recovery.
            return encoded.length >= 2 && isZlibHeader(encoded[0], encoded[1])
                    ? NBTFileEncoding.ZLIB
                    : NBTFileEncoding.RAW;
        }
    }

    /// Returns whether a CMF/FLG pair is a legal zlib header.
    private static boolean isZlibHeader(byte cmfByte, byte flagsByte) {
        int cmf = Byte.toUnsignedInt(cmfByte);
        int flags = Byte.toUnsignedInt(flagsByte);
        return (cmf & 0x0F) == 8
                && (cmf >>> 4) <= 7
                && ((cmf << 8) | flags) % 31 == 0;
    }

    /// Parses already-decoded bytes with the edition-aware strict NBT reader.
    ///
    /// Keeping this path separate from the outer-envelope detector prevents a raw payload whose
    /// first two bytes happen to resemble zlib from being decompressed a second time.
    private static <T extends Tag> T parseStrict(byte[] raw, NBTCodec codec, Class<T> expectedClass,
                                                  NBTReadLimits limits) throws IOException {
        validateStrictStructure(raw, codec.getEdition().byteOrder(), limits);
        try (RawDataReader reader = new RawDataReader(
                new InputSource.OfByteBuffer(raw), codec.getEdition())) {
            @Nullable Tag tag = NBTInput.readTag(reader);
            if (tag == null) {
                throw new IOException("Unexpected TAG_END");
            }
            reader.requireExhausted();
            try {
                return expectedClass.cast(tag);
            } catch (ClassCastException exception) {
                throw new IOException("Unexpected recovered tag type", exception);
            }
        }
    }

    /// Checks the complete NBT wire structure before constructing tag objects.
    ///
    /// The library's historical parser is intentionally allocation-oriented and does not expose
    /// node/depth limits. Recovery candidates therefore pass through this small structural scanner
    /// first. It consumes exactly one named root, validates list homogeneity and all signed lengths,
    /// and rejects trailing bytes without allocating arrays or strings.
    private static void validateStrictStructure(byte[] raw, ByteOrder order, NBTReadLimits limits)
            throws IOException {
        StrictStructureScanner scanner = new StrictStructureScanner(raw, order, limits);
        try {
            scanner.readRoot();
            if (scanner.remaining() != 0) {
                throw new ParseFailure("Trailing data after NBT tag");
            }
        } catch (ParseFailure failure) {
            throw new IOException(failure.getMessage(), failure);
        }
    }

    /// Decodes a damaged outer envelope while enforcing the decompressed-byte limit.
    private static DecodedPayload decode(byte[] encoded, NBTFileEncoding encoding,
                                         NBTReadLimits limits, NBTReadLimits.Budget budget,
                                         List<NBTReadIssue> issues) throws IOException {
        return switch (encoding) {
            case RAW, REGION -> {
                if ((long) encoded.length > limits.maxDecompressedBytes()) {
                    throw new IOException("Uncompressed NBT payload exceeds the read limit");
                }
                budget.consume(encoded.length);
                yield new DecodedPayload(encoded.clone());
            }
            case GZIP -> new DecodedPayload(inflateGzip(encoded, limits, budget, issues));
            case ZLIB -> new DecodedPayload(inflateZlib(encoded, limits, budget, issues));
            case LZ4 -> new DecodedPayload(inflateLz4(encoded, limits, budget, issues));
        };
    }

    /// Recovers bytes from a GZIP member without requiring its footer checksum.
    private static byte[] inflateGzip(byte[] encoded, NBTReadLimits limits,
                                      NBTReadLimits.Budget budget,
                                      List<NBTReadIssue> issues) throws IOException {
        int start = gzipPayloadStart(encoded, issues);
        InflatedPayload inflated = inflateWithMetadata(encoded, true, start, encoded.length - start,
                limits, budget, issues);
        byte[] raw = inflated.bytes();
        int remaining = inflated.remaining();
        if (!inflated.finished()) {
            issues.add(issue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS, "GZIP_STREAM_INCOMPLETE", "",
                    "GZIP 压缩流未完整结束，已保留已解压内容"));
        } else if (remaining < 8) {
            issues.add(issue(NBTReadIssue.Severity.RECOVERED, "GZIP_FOOTER_MISSING", "",
                    "GZIP 尾部校验信息缺失，已保留完整可解压内容"));
        } else {
            CRC32 checksum = new CRC32();
            checksum.update(raw);
            int footer = inflated.footerOffset();
            long expectedChecksum = littleUnsignedInt(encoded, footer);
            long expectedSize = littleUnsignedInt(encoded, footer + 4);
            if (checksum.getValue() != expectedChecksum
                    || (raw.length & 0xFFFF_FFFFL) != expectedSize) {
                issues.add(issue(NBTReadIssue.Severity.RECOVERED, "GZIP_FOOTER_INVALID", "",
                        "GZIP 尾部校验失败，但主体已完整解压；保存时将重写校验信息"));
            }
            if (remaining > 8) {
                int paddingStart = footer + 8;
                if (allZero(encoded, paddingStart, encoded.length)) {
                    issues.add(issue(NBTReadIssue.Severity.RECOVERED, "GZIP_ZERO_PADDING", "",
                            "GZIP 成员后存在零填充，已忽略"));
                } else {
                    issues.add(issue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS, "GZIP_TRAILING_BYTES", "",
                            "GZIP 成员后存在无法归属的附加字节，已保留主体内容"));
                }
            }
        }
        return raw;
    }

    /// Recovers bytes from a zlib stream and validates its Adler-32 trailer when present.
    private static byte[] inflateZlib(byte[] encoded, NBTReadLimits limits,
                                      NBTReadLimits.Budget budget,
                                      List<NBTReadIssue> issues) throws IOException {
        if (encoded.length < 2 || !isZlibHeader(encoded[0], encoded[1])) {
            throw new IOException("Invalid ZLIB header");
        }
        InflatedPayload inflated = inflateWithMetadata(encoded, false, 0, encoded.length,
                limits, budget, issues);
        int remaining = inflated.remaining();
        if (!inflated.finished() || remaining < 4) {
            issues.add(issue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS, "ZLIB_FOOTER_MISSING", "",
                    "ZLIB 尾部 Adler-32 校验信息缺失，已保留可解压内容"));
            return inflated.bytes();
        }
        int footer = inflated.footerOffset();
        long expected = ((encoded[footer] & 0xFFL) << 24)
                | ((encoded[footer + 1] & 0xFFL) << 16)
                | ((encoded[footer + 2] & 0xFFL) << 8)
                | (encoded[footer + 3] & 0xFFL);
        Adler32 checksum = new Adler32();
        checksum.update(inflated.bytes());
        if (checksum.getValue() != expected) {
            issues.add(issue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS, "ZLIB_FOOTER_INVALID", "",
                    "ZLIB 尾部 Adler-32 校验失败，已保留可解压内容"));
        }
        if (remaining > 4) {
            int paddingStart = footer + 4;
            if (allZero(encoded, paddingStart, encoded.length)) {
                issues.add(issue(NBTReadIssue.Severity.RECOVERED, "ZLIB_ZERO_PADDING", "",
                        "ZLIB 成员后存在零填充，已忽略"));
            } else {
                issues.add(issue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS, "ZLIB_TRAILING_BYTES", "",
                        "ZLIB 成员后存在无法归属的附加字节，已保留主体内容"));
            }
        }
        return inflated.bytes();
    }

    /// Inflates one bounded stream and retains the exact unconsumed suffix offset.
    private static InflatedPayload inflateWithMetadata(byte[] encoded, boolean nowrap, int offset, int length,
                                                       NBTReadLimits limits, NBTReadLimits.Budget budget,
                                                       List<NBTReadIssue> issues) throws IOException {
        if (offset < 0 || length < 0 || offset > encoded.length - length) {
            throw new IOException("Compressed NBT payload boundary is invalid");
        }
        long maximum = limits.maxDecompressedBytes();
        if (maximum > Integer.MAX_VALUE) {
            maximum = Integer.MAX_VALUE;
        }
        Inflater inflater = new Inflater(nowrap);
        ByteArrayOutputStream output = new ByteArrayOutputStream(initialCapacity(length));
        byte[] buffer = new byte[8192];
        boolean finished = false;
        try {
            inflater.setInput(encoded, offset, length);
            while (!inflater.finished()) {
                int count;
                try {
                    count = inflater.inflate(buffer);
                } catch (DataFormatException exception) {
                    issues.add(issue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS, "COMPRESSED_DATA_INVALID", "",
                            "压缩数据损坏，保留已解压内容"));
                    break;
                }
                if (count > 0) {
                    if ((long) output.size() + count > maximum) {
                        throw new IOException("Decompressed NBT payload exceeds the read limit");
                    }
                    budget.consume(count);
                    output.write(buffer, 0, count);
                } else if (inflater.needsDictionary() || inflater.needsInput()) {
                    issues.add(issue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS, "COMPRESSED_DATA_TRUNCATED", "",
                            "压缩流提前结束，保留已解压内容"));
                    break;
                } else {
                    issues.add(issue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS, "COMPRESSED_DATA_NO_PROGRESS", "",
                            "压缩流无法继续解码，保留已解压内容"));
                    break;
                }
            }
            finished = inflater.finished();
            int remaining = inflater.getRemaining();
            int footerOffset = offset + length - remaining;
            return new InflatedPayload(output.toByteArray(), remaining, footerOffset, finished);
        } finally {
            inflater.end();
        }
    }

    /// Recovers bytes from the optional lz4-java block stream.
    private static byte[] inflateLz4(byte[] encoded, NBTReadLimits limits,
                                     NBTReadLimits.Budget budget,
                                     List<NBTReadIssue> issues) throws IOException {
        long maximum = Math.min(limits.maxDecompressedBytes(), Integer.MAX_VALUE);
        ByteArrayOutputStream output = new ByteArrayOutputStream(initialCapacity(encoded.length));
        ByteArrayInputStream source = new ByteArrayInputStream(encoded);
        try (InputStream input = new LZ4BlockInputStream(source)) {
            byte[] buffer = new byte[8192];
            while (true) {
                int count;
                try {
                    count = input.read(buffer);
                } catch (IOException exception) {
                    issues.add(issue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS, "LZ4_DATA_INVALID", "",
                            "LZ4 数据损坏，保留已解压内容"));
                    break;
                }
                if (count < 0) {
                    break;
                }
                if (count == 0) {
                    int single = input.read();
                    if (single < 0) {
                        break;
                    }
                    if ((long) output.size() + 1L > maximum) {
                        throw new IOException("Decompressed NBT payload exceeds the read limit");
                    }
                    budget.consume(1L);
                    output.write(single);
                    continue;
                }
                if ((long) output.size() + count > maximum) {
                    throw new IOException("Decompressed NBT payload exceeds the read limit");
                }
                budget.consume(count);
                output.write(buffer, 0, count);
            }
            int remaining = source.available();
            if (remaining != 0) {
                int paddingStart = encoded.length - remaining;
                if (allZero(encoded, paddingStart, encoded.length)) {
                    issues.add(issue(NBTReadIssue.Severity.RECOVERED, "LZ4_ZERO_PADDING", "",
                            "LZ4 成员后存在零填充，已忽略"));
                } else {
                    issues.add(issue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS, "COMPRESSED_TRAILING_BYTES", "",
                            "LZ4 流末尾包含无法归属的附加字节，已保留主体内容但无法确认附加数据"));
                }
            }
        } catch (LinkageError error) {
            throw new IOException("LZ4 support is unavailable", error);
        }
        return output.toByteArray();
    }

    /// Parses the fixed GZIP header and returns the deflate payload offset.
    private static int gzipPayloadStart(byte[] encoded, List<NBTReadIssue> issues) throws IOException {
        if (encoded.length < 10 || (encoded[0] & 0xFF) != 0x1F || (encoded[1] & 0xFF) != 0x8B
                || (encoded[2] & 0xFF) != 8) {
            throw new IOException("Invalid GZIP header");
        }
        int flags = encoded[3] & 0xFF;
        if ((flags & 0xE0) != 0) {
            throw new IOException("Invalid GZIP flags");
        }
        int position = 10;
        if ((flags & 0x04) != 0) {
            if (position > encoded.length - 2) {
                throw new IOException("Truncated GZIP extra field");
            }
            int length = (encoded[position] & 0xFF) | ((encoded[position + 1] & 0xFF) << 8);
            position += 2;
            if (position > encoded.length - length) {
                throw new IOException("Truncated GZIP extra field");
            }
            position += length;
        }
        if ((flags & 0x08) != 0) {
            position = skipZeroStrict(encoded, position);
        }
        if ((flags & 0x10) != 0) {
            position = skipZeroStrict(encoded, position);
        }
        if ((flags & 0x02) != 0) {
            if (position > encoded.length - 2) {
                throw new IOException("Truncated GZIP header checksum");
            }
            int checksumOffset = position;
            int expected = (encoded[position] & 0xFF) | ((encoded[position + 1] & 0xFF) << 8);
            CRC32 checksum = new CRC32();
            checksum.update(encoded, 0, checksumOffset);
            if (((int) checksum.getValue() & 0xFFFF) != expected) {
                issues.add(issue(NBTReadIssue.Severity.RECOVERED, "GZIP_HEADER_CHECKSUM_INVALID", "",
                        "GZIP 头部校验失败，但主体仍可读取；保存时将重写校验信息"));
            }
            position += 2;
        }
        if (position > encoded.length) {
            throw new IOException("Truncated GZIP header");
        }
        return position;
    }

    /// Skips one zero-terminated GZIP field while retaining a trustworthy boundary.
    private static int skipZeroStrict(byte[] encoded, int position) throws IOException {
        while (position < encoded.length && encoded[position] != 0) {
            position++;
        }
        if (position >= encoded.length) {
            throw new IOException("Truncated GZIP header field");
        }
        return position + 1;
    }

    /// Reads one little-endian unsigned 32-bit footer value.
    private static long littleUnsignedInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFFL)
                | ((bytes[offset + 1] & 0xFFL) << 8)
                | ((bytes[offset + 2] & 0xFFL) << 16)
                | ((bytes[offset + 3] & 0xFFL) << 24);
    }

    /// Returns whether a bounded byte range consists only of zero padding.
    private static boolean allZero(byte[] bytes, int start, int end) {
        for (int index = start; index < end; index++) {
            if (bytes[index] != 0) {
                return false;
            }
        }
        return true;
    }

    /// Reads one named tag from the recovery cursor.
    private static @Nullable Tag readNamedTag(Cursor cursor, String path, ParseContext context,
                                              MinecraftEdition edition) throws ParseFailure {
        int typeId = cursor.readUnsignedByte();
        if (typeId == 0) {
            return null;
        }
        TagType<?> type = TagType.getById((byte) typeId);
        if (type == null) {
            throw new ParseFailure("未知的标签类型 " + typeId);
        }
        String name = readString(cursor, path, context, edition);
        return readPayload(cursor, typeId, name, pathName(path, name), context, edition);
    }

    /// Reads one unnamed list element or named tag payload.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Tag readPayload(Cursor cursor, int typeId, String name, String path,
                                   ParseContext context, MinecraftEdition edition) throws ParseFailure {
        context.enter(path);
        try {
            return switch (typeId) {
                case 1 -> new ByteTag(cursor.readByte()).setName(name);
                case 2 -> new ShortTag(cursor.readShort()).setName(name);
                case 3 -> new IntTag(cursor.readInt()).setName(name);
                case 4 -> new LongTag(cursor.readLong()).setName(name);
                case 5 -> new FloatTag(cursor.readFloat()).setName(name);
                case 6 -> new DoubleTag(cursor.readDouble()).setName(name);
                case 7 -> new ByteArrayTag(readBytes(cursor, path, context)).setName(name);
                case 8 -> new StringTag(readString(cursor, path, context, edition)).setName(name);
                case 9 -> readList(cursor, name, path, context, edition);
                case 10 -> readCompound(cursor, name, path, context, edition);
                case 11 -> new IntArrayTag(readInts(cursor, path, context)).setName(name);
                case 12 -> new LongArrayTag(readLongs(cursor, path, context)).setName(name);
                default -> throw new ParseFailure("未知的标签类型 " + typeId);
            };
        } finally {
            context.leave();
        }
    }

    /// Recovers a compound, stopping at the first child that cannot be reconstructed.
    private static CompoundTag readCompound(Cursor cursor, String name, String path,
                                            ParseContext context, MinecraftEdition edition) throws ParseFailure {
        CompoundTag compound = new CompoundTag().setName(name);
        while (cursor.remaining() > 0) {
            if (context.hasBoundaryUncertainty()) {
                return compound;
            }
            int nextType = cursor.peekUnsignedByte();
            if (nextType == 0) {
                cursor.readUnsignedByte();
                return compound;
            }
            int before = cursor.position();
            try {
                Tag child = readNamedTag(cursor, path, context, edition);
                if (child != null) {
                    if (compound.get(child.getName()) != null) {
                        context.issue(NBTReadIssue.Severity.RECOVERED, "DUPLICATE_NAME", path,
                                "重复名称已按最后出现的值保留");
                    }
                    compound.addTag(child);
                }
                if (context.hasBoundaryUncertainty()) {
                    return compound;
                }
            } catch (ParseFailure failure) {
                if (cursor.position() == before) {
                    throw failure;
                }
                context.issue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS, "COMPOUND_CHILD_TRUNCATED", path,
                        "复合标签的后续子项无法读取，已保留前面的子项");
                context.markBoundaryUncertain();
                return compound;
            }
        }
        context.issue(NBTReadIssue.Severity.RECOVERED, "COMPOUND_END_MISSING", path,
                "复合标签缺少结束标记");
        return compound;
    }

    /// Recovers a homogeneous list, retaining the prefix that fits the limits and input.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ListTag<?> readList(Cursor cursor, String name, String path,
                                       ParseContext context, MinecraftEdition edition) throws ParseFailure {
        int elementTypeId = cursor.readUnsignedByte();
        @Nullable TagType<?> elementType = elementTypeId == 0 ? null : TagType.getById((byte) elementTypeId);
        if (elementTypeId != 0 && elementType == null) {
            throw new ParseFailure("列表元素类型无效: " + elementTypeId);
        }
        int declared = cursor.readInt();
        if (declared < 0) {
            context.issue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS, "NEGATIVE_LIST_LENGTH", path,
                    "列表长度为负，按空列表恢复");
            declared = 0;
        }
        long bounded = Math.min((long) declared, context.limits().maxArrayLength());
        if (bounded < declared) {
            context.issue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS, "LIST_LIMIT", path,
                    "列表长度超过读取限额，无法可靠确定后续字段边界");
            context.markBoundaryUncertain();
            throw new ParseFailure("列表长度超过读取限额");
        }
        ListTag list = new ListTag(elementType);
        list.setName(name);
        if (elementType == null && declared != 0) {
            context.issue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS, "LIST_END_WITH_VALUES", path,
                    "列表声明为 TAG_END 但包含元素，已保留为空列表");
            context.markBoundaryUncertain();
            return list;
        }
        for (long index = 0L; index < bounded; index++) {
            try {
                Tag child = readPayload(cursor, elementTypeId, "", path + "[" + index + "]", context, edition);
                list.addTag(child);
            } catch (ParseFailure failure) {
                context.issue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS, "LIST_TRUNCATED", path,
                        "列表在第 " + index + " 项处截断，已保留前缀");
                context.markBoundaryUncertain();
                break;
            }
            if (context.hasBoundaryUncertainty()) {
                break;
            }
        }
        return list;
    }

    /// Reads a bounded byte array payload.
    private static byte[] readBytes(Cursor cursor, String path, ParseContext context) throws ParseFailure {
        int declared = readLength(cursor, path, context);
        int count = boundedCount(declared, 1, cursor.remaining(), path, context);
        byte[] result = cursor.readBytes(count);
        if (count < declared) {
            context.issue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS, "BYTE_ARRAY_TRUNCATED", path,
                    "字节数组已截断");
            context.markBoundaryUncertain();
        }
        return result;
    }

    /// Reads a bounded int array payload.
    private static int[] readInts(Cursor cursor, String path, ParseContext context) throws ParseFailure {
        int declared = readLength(cursor, path, context);
        int count = boundedCount(declared, Integer.BYTES, cursor.remaining(), path, context);
        int[] result = new int[count];
        for (int index = 0; index < count; index++) {
            result[index] = cursor.readInt();
        }
        if (count < declared) {
            context.issue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS, "INT_ARRAY_TRUNCATED", path,
                    "整数数组已截断");
            context.markBoundaryUncertain();
        }
        return result;
    }

    /// Reads a bounded long array payload.
    private static long[] readLongs(Cursor cursor, String path, ParseContext context) throws ParseFailure {
        int declared = readLength(cursor, path, context);
        int count = boundedCount(declared, Long.BYTES, cursor.remaining(), path, context);
        long[] result = new long[count];
        for (int index = 0; index < count; index++) {
            result[index] = cursor.readLong();
        }
        if (count < declared) {
            context.issue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS, "LONG_ARRAY_TRUNCATED", path,
                    "长整数数组已截断");
            context.markBoundaryUncertain();
        }
        return result;
    }

    /// Reads and validates one signed array length.
    private static int readLength(Cursor cursor, String path, ParseContext context) throws ParseFailure {
        int length = cursor.readInt();
        if (length < 0) {
            context.issue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS, "NEGATIVE_ARRAY_LENGTH", path,
                    "数组长度为负，按空数组恢复");
            return 0;
        }
        return length;
    }

    /// Applies logical and byte-availability bounds without integer overflow.
    private static int boundedCount(int declared, int elementBytes, int remaining,
                                    String path, ParseContext context) throws ParseFailure {
        if ((long) declared > context.limits().maxArrayLength()
                || (long) declared * elementBytes > context.limits().maxArrayBytes()) {
            context.issue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS, "ARRAY_LIMIT", path,
                    "数组长度超过读取限额，无法可靠确定后续字段边界");
            throw new ParseFailure("数组长度超过读取限额");
        }
        long byBytes = remaining / (long) elementBytes;
        long count = Math.min(declared, Math.min(context.limits().maxArrayLength(),
                byBytes));
        return (int) Math.min(count, Integer.MAX_VALUE);
    }

    /// Reads a bounded NBT string with replacement fallback for malformed UTF-8.
    private static String readString(Cursor cursor, String path, ParseContext context,
                                      MinecraftEdition edition) throws ParseFailure {
        int declared = cursor.readUnsignedShort();
        if ((long) declared > context.limits().maxStringBytes()) {
            context.issue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS, "STRING_LIMIT", path,
                    "字符串长度超过读取限额，无法可靠确定后续字段边界");
            throw new ParseFailure("字符串长度超过读取限额");
        }
        int count = (int) Math.min((long) declared, context.limits().maxStringBytes());
        count = Math.min(count, cursor.remaining());
        byte[] bytes = cursor.readBytes(count);
        if (count < declared) {
            context.issue(NBTReadIssue.Severity.PARTIAL_DATA_LOSS, "STRING_TRUNCATED", path,
                    "字符串长度超过读取限额或文件剩余长度，已截断");
            context.markBoundaryUncertain();
        }
        String decoded = edition == MinecraftEdition.JAVA_EDITION
                ? decodeModifiedUtf8Lenient(bytes)
                : decodeUtf8Lenient(bytes);
        if (decoded.indexOf('\uFFFD') >= 0) {
            context.issue(NBTReadIssue.Severity.RECOVERED, "STRING_ENCODING_REPLACED", path,
                    "字符串包含非法 " + (edition == MinecraftEdition.JAVA_EDITION ? "modified UTF-8" : "UTF-8")
                            + "，已使用替换字符");
        }
        return decoded;
    }

    /// Decodes standard UTF-8 while replacing malformed sequences.
    private static String decodeUtf8Lenient(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            // REPLACE is required above; this is only a defensive fallback for an unusual decoder.
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /// Decodes Java Edition's modified UTF-8 representation without scanning beyond its field.
    private static String decodeModifiedUtf8Lenient(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length);
        int position = 0;
        while (position < bytes.length) {
            int first = Byte.toUnsignedInt(bytes[position++]);
            if (first <= 0x7F) {
                if (first == 0) {
                    result.append('\uFFFD');
                } else {
                    result.append((char) first);
                }
            } else if ((first & 0xE0) == 0xC0 && position < bytes.length) {
                int second = Byte.toUnsignedInt(bytes[position]);
                if ((second & 0xC0) == 0x80 && !(first == 0xC0 && second != 0x80) && first != 0xC1) {
                    position++;
                    result.append((char) (((first & 0x1F) << 6) | (second & 0x3F)));
                } else {
                    result.append('\uFFFD');
                }
            } else if ((first & 0xF0) == 0xE0 && position + 1 < bytes.length) {
                int second = Byte.toUnsignedInt(bytes[position]);
                int third = Byte.toUnsignedInt(bytes[position + 1]);
                if ((second & 0xC0) == 0x80 && (third & 0xC0) == 0x80
                        && !(first == 0xE0 && second < 0xA0)) {
                    position += 2;
                    result.append((char) (((first & 0x0F) << 12)
                            | ((second & 0x3F) << 6) | (third & 0x3F)));
                } else {
                    result.append('\uFFFD');
                }
            } else {
                result.append('\uFFFD');
            }
        }
        return result.toString();
    }

    /// Joins a parent path and child name for diagnostics.
    private static String pathName(String parent, String name) {
        if (name.isEmpty()) {
            return parent;
        }
        return parent.isEmpty() ? name : parent + "." + name;
    }

    /// Creates one issue without exposing a mutable builder.
    private static NBTReadIssue issue(NBTReadIssue.Severity severity, String code,
                                      String path, String message) {
        return new NBTReadIssue(severity, code, path, message);
    }

    /// Keeps diagnostics stable when an implementation exception has no message.
    private static String message(Throwable exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    /// Chooses a small non-overflowing decompression buffer hint.
    private static int initialCapacity(int encodedLength) {
        return (int) Math.min((long) encodedLength * 2L, 8192L);
    }

    /// Bounded inflater output together with the exact suffix left after the deflate member.
    ///
    /// @param bytes decompressed prefix
    /// @param remaining encoded bytes not consumed by the inflater
    /// @param footerOffset absolute offset of the first unconsumed byte
    /// @param finished whether the inflater reached a complete deflate stream
    @NotNullByDefault
    private record InflatedPayload(
            byte @Unmodifiable [] bytes,
            int remaining,
            int footerOffset,
            boolean finished) {
        private InflatedPayload {
            bytes = bytes.clone();
        }

        @Override
        public byte @Unmodifiable [] bytes() {
            return bytes.clone();
        }
    }

    /// One envelope candidate whose NBT root was consumed strictly.
    @NotNullByDefault
    private record StrictCandidate<T extends Tag>(
            NBTFileEncoding encoding,
            byte @Unmodifiable [] bytes,
            T root) {
        private StrictCandidate {
            bytes = bytes.clone();
        }

        @Override
        public byte @Unmodifiable [] bytes() {
            return bytes.clone();
        }
    }

    /// Immutable decompression output wrapper.
    @NotNullByDefault
    private record DecodedPayload(byte @Unmodifiable [] bytes) {
        private DecodedPayload {
            bytes = bytes.clone();
        }

        @Override
        public byte @Unmodifiable [] bytes() {
            return bytes.clone();
        }
    }

    /// Mutable parser budget shared by one recursive recovery operation.
    @NotNullByDefault
    private static final class ParseContext {
        private final NBTReadLimits limits;
        private final List<NBTReadIssue> issues;
        private long nodes;
        private long depth;
        /// Whether a recovered child no longer has a trustworthy byte boundary.
        private boolean boundaryUncertain;

        private ParseContext(NBTReadLimits limits, List<NBTReadIssue> issues) {
            this.limits = limits;
            this.issues = issues;
        }

        private NBTReadLimits limits() {
            return limits;
        }

        /// Marks the current recovery cursor as unsafe for speculative sibling parsing.
        private void markBoundaryUncertain() {
            boundaryUncertain = true;
        }

        /// Returns whether a child recovery consumed an uncertain prefix.
        private boolean hasBoundaryUncertainty() {
            return boundaryUncertain;
        }

        private void enter(String path) throws ParseFailure {
            if (nodes >= limits.maxNodes()) {
                throw new ParseFailure("标签数量超过读取限额");
            }
            if (depth >= limits.maxDepth()) {
                throw new ParseFailure("标签嵌套深度超过读取限额");
            }
            nodes++;
            depth++;
        }

        private void leave() {
            depth--;
        }

        private void issue(NBTReadIssue.Severity severity, String code, String path, String message) {
            issues.add(new NBTReadIssue(severity, code, path, message));
        }
    }

    /// Allocation-free strict wire-format scanner used before the legacy object parser.
    @NotNullByDefault
    private static final class StrictStructureScanner {
        private final Cursor cursor;
        private final NBTReadLimits limits;
        private long nodes;

        private StrictStructureScanner(byte[] bytes, ByteOrder order, NBTReadLimits limits) {
            this.cursor = new Cursor(bytes, order);
            this.limits = Objects.requireNonNull(limits, "limits");
        }

        private int remaining() {
            return cursor.remaining();
        }

        private void readRoot() throws ParseFailure {
            int typeId = cursor.readUnsignedByte();
            if (typeId == 0 || TagType.getById((byte) typeId) == null) {
                throw new ParseFailure("Invalid root tag type: " + typeId);
            }
            readString();
            readPayload(typeId, 1L);
        }

        private void readPayload(int typeId, long depth) throws ParseFailure {
            TagType<?> type = TagType.getById((byte) typeId);
            if (type == null) {
                throw new ParseFailure("Invalid tag type: " + typeId);
            }
            if (nodes >= limits.maxNodes()) {
                throw new ParseFailure("Tag count exceeds the read limit");
            }
            if (depth > limits.maxDepth()) {
                throw new ParseFailure("Tag nesting depth exceeds the read limit");
            }
            nodes++;
            switch (typeId) {
                case 1 -> cursor.skip(Byte.BYTES);
                case 2 -> cursor.skip(Short.BYTES);
                case 3, 5 -> cursor.skip(Integer.BYTES);
                case 4, 6 -> cursor.skip(Long.BYTES);
                case 7 -> readArray(Byte.BYTES);
                case 8 -> readString();
                case 9 -> readList(depth);
                case 10 -> readCompound(depth);
                case 11 -> readArray(Integer.BYTES);
                case 12 -> readArray(Long.BYTES);
                default -> throw new ParseFailure("Invalid tag type: " + typeId);
            }
        }

        private void readCompound(long depth) throws ParseFailure {
            while (cursor.remaining() > 0) {
                int typeId = cursor.readUnsignedByte();
                if (typeId == 0) {
                    return;
                }
                if (TagType.getById((byte) typeId) == null) {
                    throw new ParseFailure("Invalid compound child tag type: " + typeId);
                }
                readString();
                readPayload(typeId, depth + 1L);
            }
            throw new ParseFailure("Compound tag is missing TAG_End");
        }

        private void readList(long depth) throws ParseFailure {
            int elementType = cursor.readUnsignedByte();
            if (elementType != 0 && TagType.getById((byte) elementType) == null) {
                throw new ParseFailure("Invalid list element type: " + elementType);
            }
            int length = cursor.readInt();
            if (length < 0) {
                throw new ParseFailure("Negative list length");
            }
            if ((long) length > limits.maxArrayLength()) {
                throw new ParseFailure("List length exceeds the read limit");
            }
            if (elementType == 0 && length != 0) {
                throw new ParseFailure("TAG_End list has a non-zero length");
            }
            for (int index = 0; index < length; index++) {
                readPayload(elementType, depth + 1L);
            }
        }

        private void readArray(int elementBytes) throws ParseFailure {
            int length = cursor.readInt();
            if (length < 0) {
                throw new ParseFailure("Negative array length");
            }
            if ((long) length > limits.maxArrayLength()) {
                throw new ParseFailure("Array length exceeds the read limit");
            }
            long bytes;
            try {
                bytes = Math.multiplyExact((long) length, elementBytes);
            } catch (ArithmeticException exception) {
                throw new ParseFailure("Array byte length overflows");
            }
            if (bytes > limits.maxArrayBytes()) {
                throw new ParseFailure("Array byte length exceeds the read limit");
            }
            if (bytes > Integer.MAX_VALUE) {
                throw new ParseFailure("Array byte length exceeds the addressable input range");
            }
            cursor.skip((int) bytes);
        }

        private void readString() throws ParseFailure {
            int length = cursor.readUnsignedShort();
            if ((long) length > limits.maxStringBytes()) {
                throw new ParseFailure("String length exceeds the read limit");
            }
            cursor.skip(length);
        }
    }

    /// Cursor which never performs an unchecked array access.
    @NotNullByDefault
    private static final class Cursor {
        private final byte[] bytes;
        private final ByteOrder order;
        private int position;

        private Cursor(byte[] bytes, ByteOrder order) {
            this.bytes = Objects.requireNonNull(bytes, "bytes");
            this.order = Objects.requireNonNull(order, "order");
        }

        private int position() {
            return position;
        }

        private int remaining() {
            return bytes.length - position;
        }

        /// Returns whether every unread byte is confirmed zero padding.
        private boolean remainingAreZero() {
            for (int index = position; index < bytes.length; index++) {
                if (bytes[index] != 0) {
                    return false;
                }
            }
            return true;
        }

        private int peekUnsignedByte() throws ParseFailure {
            require(1);
            return bytes[position] & 0xFF;
        }

        private int readUnsignedByte() throws ParseFailure {
            require(1);
            return bytes[position++] & 0xFF;
        }

        private byte readByte() throws ParseFailure {
            return (byte) readUnsignedByte();
        }

        private int readUnsignedShort() throws ParseFailure {
            if (order == ByteOrder.BIG_ENDIAN) {
                return (readUnsignedByte() << 8) | readUnsignedByte();
            }
            return readUnsignedByte() | (readUnsignedByte() << 8);
        }

        private short readShort() throws ParseFailure {
            return (short) readUnsignedShort();
        }

        private int readInt() throws ParseFailure {
            int first = readUnsignedByte();
            int second = readUnsignedByte();
            int third = readUnsignedByte();
            int fourth = readUnsignedByte();
            return order == ByteOrder.BIG_ENDIAN
                    ? (first << 24) | (second << 16) | (third << 8) | fourth
                    : first | (second << 8) | (third << 16) | (fourth << 24);
        }

        private long readLong() throws ParseFailure {
            long result = 0L;
            if (order == ByteOrder.BIG_ENDIAN) {
                for (int index = 0; index < Long.BYTES; index++) {
                    result = (result << 8) | readUnsignedByte();
                }
            } else {
                for (int index = 0; index < Long.BYTES; index++) {
                    result |= (long) readUnsignedByte() << (index * 8);
                }
            }
            return result;
        }

        private float readFloat() throws ParseFailure {
            return Float.intBitsToFloat(readInt());
        }

        private double readDouble() throws ParseFailure {
            return Double.longBitsToDouble(readLong());
        }

        private byte[] readBytes(int length) throws ParseFailure {
            if (length < 0) {
                throw new ParseFailure("负的字节长度");
            }
            require(length);
            byte[] result = java.util.Arrays.copyOfRange(bytes, position, position + length);
            position += length;
            return result;
        }

        private void skip(int length) throws ParseFailure {
            if (length < 0) {
                throw new ParseFailure("Negative skip length");
            }
            require(length);
            position += length;
        }

        private void require(int length) throws ParseFailure {
            if (length < 0 || position > bytes.length - length) {
                throw new ParseFailure("NBT 数据在位置 " + position + " 处提前结束");
            }
        }
    }

    /// Checked parser failure with a user-safe message.
    @NotNullByDefault
    private static final class ParseFailure extends Exception {
        private ParseFailure(String message) {
            super(message);
        }
    }

    private NBTRepairReader() {
    }
}
