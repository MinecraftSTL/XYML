// SPDX-FileCopyrightText: Copyright (C) 2020 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.upgrade;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import space.minecraftstl.xyml.util.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static java.nio.file.StandardOpenOption.*;
import static space.minecraftstl.xyml.util.Lang.mapOf;
import static space.minecraftstl.xyml.util.Pair.pair;

/// Adds and removes platform launcher headers from XYML artifacts.
///
/// @author yushijinhun
@NotNullByDefault
final class ExecutableHeaderHelper {
    /// Maps output suffixes to their independently built launcher resources.
    private static final @Unmodifiable Map<String, String> SUFFIX_TO_HEADER = Map.copyOf(mapOf(
            pair("exe", "assets/XYMLL.exe"),
            pair("sh", "assets/XYMLL.sh")
    ));

    /// Prevents construction of this static helper.
    private ExecutableHeaderHelper() {
    }

    private static Optional<String> getSuffix(Path file) {
        String filename = file.getFileName().toString();
        int idxDot = filename.lastIndexOf('.');
        if (idxDot < 0) {
            return Optional.empty();
        } else {
            return Optional.of(filename.substring(idxDot + 1));
        }
    }

    private static Optional<byte[]> readHeader(ZipFile zip, String suffix) throws IOException {
        String location = SUFFIX_TO_HEADER.get(suffix);
        if (location != null) {
            ZipEntry entry = zip.getEntry(location);
            if (entry != null && !entry.isDirectory()) {
                try (InputStream in = zip.getInputStream(entry)) {
                    return Optional.of(IOUtils.readFully(in));
                }
            }
        }
        return Optional.empty();
    }

    private static int detectHeaderLength(ZipFile zip, FileChannel channel) throws IOException {
        ByteBuffer buf = channel.map(MapMode.READ_ONLY, 0, channel.size());
        suffixLoop: for (String suffix : SUFFIX_TO_HEADER.keySet()) {
            Optional<byte[]> header = readHeader(zip, suffix);
            if (header.isPresent()) {
                ((Buffer) buf).rewind();
                for (byte b : header.get()) {
                    if (!buf.hasRemaining() || b != buf.get()) {
                        continue suffixLoop;
                    }
                }
                return header.get().length;
            }
        }
        return 0;
    }

    /**
     * Copies the executable and removes its header.
     */
    public static void copyWithoutHeader(Path from, Path to) throws IOException {
        try (
                FileChannel in = FileChannel.open(from, READ);
                FileChannel out = FileChannel.open(to, CREATE, WRITE, TRUNCATE_EXISTING);
                ZipFile zip = new ZipFile(from.toFile())
        ) {
            in.transferTo(detectHeaderLength(zip, in), Long.MAX_VALUE, out);
        }
    }

    /**
     * Copies the executable and appends the header according to the suffix.
     */
    public static void copyWithHeader(Path from, Path to) throws IOException {
        try (
                FileChannel in = FileChannel.open(from, READ);
                FileChannel out = FileChannel.open(to, CREATE, WRITE, TRUNCATE_EXISTING);
                ZipFile zip = new ZipFile(from.toFile())
        ) {
            Optional<String> suffix = getSuffix(to);
            if (suffix.isPresent()) {
                Optional<byte[]> header = readHeader(zip, suffix.get());
                if (header.isPresent()) {
                    out.write(ByteBuffer.wrap(header.get()));
                }
            }

            in.transferTo(detectHeaderLength(zip, in), Long.MAX_VALUE, out);
        }
    }
}
