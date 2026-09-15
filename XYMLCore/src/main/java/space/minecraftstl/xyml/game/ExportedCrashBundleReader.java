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
package space.minecraftstl.xyml.game;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.util.platform.OperatingSystem;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/// Strict read-only reader for crash archives produced by the launcher's log exporter.
///
/// The reader validates the complete central directory before reading any diagnostic text, never
/// extracts entries, and bounds both declared and streamed expanded bytes. Unsupported entries are
/// ignored and are never interpreted or executed.
@NotNullByDefault
public final class ExportedCrashBundleReader {
    /// Required launcher-generated filename prefix.
    public static final String FILE_NAME_PREFIX = "minecraft-exported-crash-info-";

    /// Required launcher-generated filename suffix.
    public static final String FILE_NAME_SUFFIX = ".zip";

    /// Maximum number of central-directory entries, including directories and ignored files.
    private static final int MAXIMUM_ENTRY_COUNT = 256;

    /// Maximum expanded bytes accepted from one supported text entry.
    private static final long MAXIMUM_SINGLE_TEXT_BYTES = 16L * 1024L * 1024L;

    /// Maximum expanded bytes accepted across all supported text entries.
    private static final long MAXIMUM_TOTAL_TEXT_BYTES = 64L * 1024L * 1024L;

    /// Maximum ratio of expanded bytes to compressed bytes for a non-empty text entry.
    private static final long MAXIMUM_COMPRESSION_RATIO = 200L;

    /// Buffer used for bounded in-memory text reads.
    private static final int READ_BUFFER_SIZE = 32 * 1024;

    /// Maximum entry count used by this reader instance.
    private final int maximumEntryCount;

    /// Maximum bytes for one supported text entry used by this reader instance.
    private final long maximumSingleTextBytes;

    /// Maximum bytes across supported text entries used by this reader instance.
    private final long maximumTotalTextBytes;

    /// Maximum compression ratio used by this reader instance.
    private final long maximumCompressionRatio;

    /// Creates a reader with the fixed launcher crash-export policy.
    public ExportedCrashBundleReader() {
        this(
                MAXIMUM_ENTRY_COUNT,
                MAXIMUM_SINGLE_TEXT_BYTES,
                MAXIMUM_TOTAL_TEXT_BYTES,
                MAXIMUM_COMPRESSION_RATIO);
    }

    /// Creates a reader with compact policy values for same-package verification.
    ///
    /// @param maximumEntryCount maximum central-directory entries
    /// @param maximumSingleTextBytes maximum expanded bytes for one supported text
    /// @param maximumTotalTextBytes maximum expanded bytes for all supported texts
    /// @param maximumCompressionRatio maximum expanded-to-compressed ratio
    ExportedCrashBundleReader(
            int maximumEntryCount,
            long maximumSingleTextBytes,
            long maximumTotalTextBytes,
            long maximumCompressionRatio) {
        if (maximumEntryCount <= 0) {
            throw new IllegalArgumentException("maximumEntryCount must be positive");
        }
        if (maximumSingleTextBytes <= 0L || maximumTotalTextBytes <= 0L) {
            throw new IllegalArgumentException("text byte limits must be positive");
        }
        if (maximumSingleTextBytes > maximumTotalTextBytes) {
            throw new IllegalArgumentException("maximumSingleTextBytes must not exceed maximumTotalTextBytes");
        }
        if (maximumCompressionRatio <= 0L) {
            throw new IllegalArgumentException("maximumCompressionRatio must be positive");
        }
        this.maximumEntryCount = maximumEntryCount;
        this.maximumSingleTextBytes = maximumSingleTextBytes;
        this.maximumTotalTextBytes = maximumTotalTextBytes;
        this.maximumCompressionRatio = maximumCompressionRatio;
    }

    /// Returns whether a path has the exact launcher crash-export filename shape.
    ///
    /// This lexical check performs no filesystem access and is suitable for drag-route predicates.
    ///
    /// @param archive candidate archive path
    /// @return whether the final filename has the required non-empty generated suffix and ZIP extension
    public static boolean hasSupportedFileName(Path archive) {
        Path fileNamePath = Objects.requireNonNull(archive, "archive").getFileName();
        if (fileNamePath == null) {
            return false;
        }
        String fileName = fileNamePath.toString();
        int minimumLength = FILE_NAME_PREFIX.length() + FILE_NAME_SUFFIX.length() + 1;
        return fileName.length() >= minimumLength
                && fileName.regionMatches(true, 0, FILE_NAME_PREFIX, 0, FILE_NAME_PREFIX.length())
                && fileName.regionMatches(
                        true,
                        fileName.length() - FILE_NAME_SUFFIX.length(),
                        FILE_NAME_SUFFIX,
                        0,
                        FILE_NAME_SUFFIX.length());
    }

    /// Validates and reads supported diagnostic texts without extracting the archive.
    ///
    /// @param archive local launcher crash-export ZIP
    /// @return immutable unique diagnostic texts with all original entry sources
    /// @throws IOException when the source is malformed, unsafe, outside the policy, or contains no diagnostic text
    public ExportedCrashBundle read(Path archive) throws IOException {
        Path normalizedArchive = requireRegularArchive(archive);
        try (ZipFile zipFile = new ZipFile(normalizedArchive.toFile(), StandardCharsets.UTF_8)) {
            @Unmodifiable List<PlannedEntry> plannedEntries = planEntries(zipFile);
            return new ExportedCrashBundle(normalizedArchive, readTexts(zipFile, plannedEntries));
        }
    }

    /// Requires the canonical filename and a non-symbolic regular filesystem file.
    ///
    /// @param archive source path
    /// @return normalized absolute path
    /// @throws IOException when the source is absent, symbolic, non-regular, or renamed
    private static Path requireRegularArchive(Path archive) throws IOException {
        Path normalizedArchive = Objects.requireNonNull(archive, "archive").toAbsolutePath().normalize();
        if (!hasSupportedFileName(normalizedArchive)) {
            throw new IOException("Crash export filename is not supported: " + normalizedArchive.getFileName());
        }
        BasicFileAttributes attributes = Files.readAttributes(
                normalizedArchive,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
            throw new IOException("Crash export must be a non-symbolic regular file: " + normalizedArchive);
        }
        return normalizedArchive;
    }

    /// Validates the full central directory and plans supported entries in deterministic priority order.
    ///
    /// @param zipFile open source archive
    /// @return immutable supported-entry plan
    /// @throws IOException when any entry is unsafe or the archive exceeds resource limits
    private @Unmodifiable List<PlannedEntry> planEntries(ZipFile zipFile) throws IOException {
        List<PlannedEntry> plannedEntries = new ArrayList<>();
        Set<String> supportedNames = new HashSet<>();
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        int entryCount = 0;
        long declaredTotal = 0L;
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            entryCount++;
            if (entryCount > maximumEntryCount) {
                throw new IOException("Crash export exceeds its entry-count limit");
            }

            String normalizedName = normalizeEntryName(entry.getName());
            if (entry.isDirectory()) {
                continue;
            }
            @Nullable EntryClassification classification = classifyEntry(normalizedName);
            if (classification == null) {
                continue;
            }

            String identity = normalizedName.toLowerCase(Locale.ROOT);
            if (!supportedNames.add(identity)) {
                throw new IOException("Crash export contains duplicate diagnostic entry: " + normalizedName);
            }
            long declaredSize = requireDeclaredSize(entry);
            if (declaredSize > maximumSingleTextBytes) {
                throw new IOException("Crash export diagnostic entry exceeds its size limit: " + normalizedName);
            }
            requireSafeCompressionRatio(entry, declaredSize);
            declaredTotal = checkedTotal(declaredTotal, declaredSize, maximumTotalTextBytes);
            plannedEntries.add(new PlannedEntry(entry, normalizedName, classification));
        }
        plannedEntries.sort(Comparator
                .comparingInt((PlannedEntry entry) -> entry.classification().priority())
                .thenComparing(PlannedEntry::normalizedName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(PlannedEntry::normalizedName));
        return List.copyOf(plannedEntries);
    }

    /// Reads every planned entry with streamed limits and merges exact duplicate content.
    ///
    /// @param zipFile open source archive
    /// @param plannedEntries validated supported entries
    /// @return immutable non-blank unique texts
    /// @throws IOException when streamed data violates the plan or no usable text exists
    private @Unmodifiable List<ExportedCrashBundleText> readTexts(
            ZipFile zipFile,
            @Unmodifiable List<PlannedEntry> plannedEntries) throws IOException {
        Map<String, MutableText> uniqueTexts = new LinkedHashMap<>();
        long actualTotal = 0L;
        for (PlannedEntry plannedEntry : plannedEntries) {
            byte[] bytes = readBounded(zipFile, plannedEntry.entry());
            actualTotal = checkedTotal(actualTotal, bytes.length, maximumTotalTextBytes);
            String content = decodeText(bytes);
            if (content.isBlank()) {
                continue;
            }
            @Nullable MutableText existing = uniqueTexts.get(content);
            if (existing == null) {
                uniqueTexts.put(content, new MutableText(
                        plannedEntry.classification().kind(),
                        content,
                        new ArrayList<>(List.of(plannedEntry.normalizedName()))));
            } else {
                existing.sources().add(plannedEntry.normalizedName());
            }
        }
        if (uniqueTexts.isEmpty()) {
            throw new IOException("Crash export contains no supported non-empty diagnostic text");
        }
        return uniqueTexts.values().stream()
                .map(text -> new ExportedCrashBundleText(text.kind(), text.content(), text.sources()))
                .toList();
    }

    /// Reads one diagnostic entry into memory while enforcing the streamed single-entry limit.
    ///
    /// @param zipFile open source archive
    /// @param entry validated text entry
    /// @return exact expanded bytes
    /// @throws IOException when decompression fails or emits unexpected bytes
    private byte[] readBounded(ZipFile zipFile, ZipEntry entry) throws IOException {
        int initialCapacity = (int) Math.min(entry.getSize(), READ_BUFFER_SIZE);
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(initialCapacity, 0));
        byte[] buffer = new byte[READ_BUFFER_SIZE];
        long count = 0L;
        try (InputStream input = zipFile.getInputStream(entry)) {
            while (true) {
                int read = input.read(buffer);
                if (read < 0) {
                    break;
                }
                count = checkedTotal(count, read, maximumSingleTextBytes);
                output.write(buffer, 0, read);
            }
        }
        if (count != entry.getSize()) {
            throw new IOException("Crash export diagnostic entry size changed while reading: " + entry.getName());
        }
        return output.toByteArray();
    }

    /// Decodes exporter text as UTF-8, falling back to the local native charset for legacy `putLines` entries.
    ///
    /// @param bytes expanded entry bytes
    /// @return decoded diagnostic text
    private static String decodeText(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException ignored) {
            return new String(bytes, OperatingSystem.NATIVE_CHARSET);
        }
    }

    /// Normalizes separators and rejects absolute, ambiguous, or traversal entry names.
    ///
    /// @param rawName original central-directory name
    /// @return slash-normalized safe logical name
    /// @throws IOException when the name is unsafe or ambiguous
    private static String normalizeEntryName(String rawName) throws IOException {
        if (rawName.indexOf('\0') >= 0) {
            throw new IOException("Crash export contains an entry name with NUL");
        }
        String normalizedName = rawName.replace('\\', '/');
        if (normalizedName.isEmpty()
                || normalizedName.startsWith("/")
                || isDriveAbsolute(normalizedName)) {
            throw new IOException("Crash export contains an absolute or empty entry name: " + rawName);
        }
        String pathName = normalizedName.endsWith("/")
                ? normalizedName.substring(0, normalizedName.length() - 1)
                : normalizedName;
        if (pathName.isEmpty()) {
            throw new IOException("Crash export contains an empty root entry name");
        }
        for (String segment : pathName.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IOException("Crash export contains an ambiguous or traversal entry name: " + rawName);
            }
        }
        return normalizedName;
    }

    /// Classifies one safe regular entry against the diagnostic allowlist.
    ///
    /// @param normalizedName slash-normalized entry name
    /// @return supported classification, or null for an ignored entry
    private static @Nullable EntryClassification classifyEntry(String normalizedName) {
        String lowerName = normalizedName.toLowerCase(Locale.ROOT);
        String[] segments = lowerName.split("/");
        if (segments.length == 2
                && "crash-reports".equals(segments[0])
                && segments[1].endsWith(".txt")) {
            return EntryClassification.CRASH_REPORT;
        }
        if (segments.length == 1 && "minecraft.log".equals(segments[0])) {
            return EntryClassification.MINECRAFT_LOG;
        }
        if (segments.length == 2
                && ("logs".equals(segments[0]) || "liteconfig".equals(segments[0]))
                && segments[1].endsWith(".log")) {
            return EntryClassification.NESTED_LOG;
        }
        if (segments.length == 1 && segments[0].endsWith(".log")) {
            return EntryClassification.ROOT_LOG;
        }
        return null;
    }

    /// Requires a known non-negative expanded size from the central directory.
    ///
    /// @param entry supported text entry
    /// @return declared expanded size
    /// @throws IOException when the size is unavailable
    private static long requireDeclaredSize(ZipEntry entry) throws IOException {
        long declaredSize = entry.getSize();
        if (declaredSize < 0L) {
            throw new IOException("Crash export diagnostic entry has no declared size: " + entry.getName());
        }
        return declaredSize;
    }

    /// Rejects suspiciously high declared expansion ratios before decompression.
    ///
    /// @param entry supported text entry
    /// @param expandedSize declared expanded bytes
    /// @throws IOException when compressed size is unavailable or the ratio is excessive
    private void requireSafeCompressionRatio(ZipEntry entry, long expandedSize) throws IOException {
        if (expandedSize == 0L) {
            return;
        }
        long compressedSize = entry.getCompressedSize();
        if (compressedSize <= 0L
                || (compressedSize <= Long.MAX_VALUE / maximumCompressionRatio
                && expandedSize > compressedSize * maximumCompressionRatio)) {
            throw new IOException("Crash export diagnostic entry exceeds its compression-ratio limit: "
                    + entry.getName());
        }
    }

    /// Adds a non-negative byte amount without overflow and enforces an inclusive ceiling.
    ///
    /// @param current current accumulated bytes
    /// @param additional bytes to add
    /// @param limit inclusive ceiling
    /// @return checked total
    /// @throws IOException when the total is invalid, overflowing, or excessive
    private static long checkedTotal(long current, long additional, long limit) throws IOException {
        if (current < 0L || additional < 0L || current > limit - additional) {
            throw new IOException("Crash export exceeds its expanded diagnostic-text limit");
        }
        return current + additional;
    }

    /// Recognizes Windows drive-prefixed paths independently of the current host platform.
    ///
    /// @param normalizedName slash-normalized entry name
    /// @return whether the entry begins with a drive-root prefix
    private static boolean isDriveAbsolute(String normalizedName) {
        return normalizedName.length() >= 3
                && Character.isLetter(normalizedName.charAt(0))
                && normalizedName.charAt(1) == ':'
                && normalizedName.charAt(2) == '/';
    }

    /// Internal allowlist classification and analysis priority.
    @NotNullByDefault
    private enum EntryClassification {
        /// Dedicated crash reports are analyzed first.
        CRASH_REPORT(0, ExportedCrashBundleText.Kind.CRASH_REPORT),

        /// The captured Minecraft process log follows dedicated reports.
        MINECRAFT_LOG(1, ExportedCrashBundleText.Kind.LOG),

        /// Logs retained in their original well-known directories follow the main log.
        NESTED_LOG(2, ExportedCrashBundleText.Kind.LOG),

        /// Other root logs are analyzed last.
        ROOT_LOG(3, ExportedCrashBundleText.Kind.LOG);

        /// Deterministic analysis-order group.
        private final int priority;

        /// Public semantic text kind.
        private final ExportedCrashBundleText.Kind kind;

        /// Creates one internal allowlist classification.
        ///
        /// @param priority deterministic order group
        /// @param kind exposed semantic text kind
        EntryClassification(int priority, ExportedCrashBundleText.Kind kind) {
            this.priority = priority;
            this.kind = kind;
        }

        /// Returns the deterministic analysis-order group.
        ///
        /// @return priority where lower values are analyzed first
        private int priority() {
            return priority;
        }

        /// Returns the exposed semantic text kind.
        ///
        /// @return crash report or log kind
        private ExportedCrashBundleText.Kind kind() {
            return kind;
        }
    }

    /// Validated supported central-directory entry.
    ///
    /// @param entry source ZIP entry
    /// @param normalizedName safe slash-normalized source name
    /// @param classification allowlist classification and order
    private record PlannedEntry(
            ZipEntry entry,
            String normalizedName,
            EntryClassification classification) {
        /// Retains non-null validated central-directory values.
        private PlannedEntry {
            Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(normalizedName, "normalizedName");
            Objects.requireNonNull(classification, "classification");
        }
    }

    /// Mutable content accumulator used only while merging identical texts.
    ///
    /// @param kind semantic kind of the highest-priority source
    /// @param content exact decoded diagnostic text
    /// @param sources mutable source-name accumulator
    private record MutableText(
            ExportedCrashBundleText.Kind kind,
            String content,
            List<String> sources) {
        /// Retains internally owned merge values.
        private MutableText {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(sources, "sources");
        }
    }
}
