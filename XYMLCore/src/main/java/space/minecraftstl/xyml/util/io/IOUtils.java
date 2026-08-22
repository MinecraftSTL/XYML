/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.util.io;

import kala.encdet.EncodingDetector;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

import static java.nio.charset.StandardCharsets.*;
import static space.minecraftstl.xyml.util.platform.OperatingSystem.NATIVE_CHARSET;

/**
 * This utility class consists of some util methods operating on InputStream/OutputStream.
 *
 * @author huangyuhui
 */
public final class IOUtils {

    private IOUtils() {
    }

    public static final int DEFAULT_BUFFER_SIZE = 32 * 1024;

    /// Opens a text file using UTF-8 for modern text and the configured native charset for legacy text.
    ///
    /// The detector samples the file and the returned reader continues from the original file position. The caller
    /// owns the returned reader and therefore the underlying file channel.
    ///
    /// @param file file to open
    /// @return buffered reader using the detected charset
    /// @throws IOException when the file cannot be opened or sampled
    public static BufferedReader newBufferedReaderMaybeNativeEncoding(Path file) throws IOException {
        if (NATIVE_CHARSET == UTF_8) {
            return Files.newBufferedReader(file);
        }

        FileChannel channel = FileChannel.open(file);
        try {
            long oldPosition = channel.position();
            long size = channel.size();
            EncodingDetector detector = EncodingDetector.MODERN_WEB;
            int bufferSize = (int) Math.max(Math.min(size - oldPosition, detector.maxBytes()), 8192L);
            ByteBuffer buffer = ByteBuffer.allocate(bufferSize);

            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // Continue sampling until the detector buffer is full or EOF is reached.
            }

            buffer.flip();
            Charset charset;
            if (buffer.remaining() == 0) {
                charset = UTF_8;
            } else {
                @Nullable EncodingDetector.Encoding encoding = detector.detect(buffer).bestEncoding();
                @Nullable Charset detectedCharset = encoding == null ? null : encoding.approximateCharset();
                charset = detectedCharset != null && (detectedCharset == UTF_8 || detectedCharset == US_ASCII)
                        ? UTF_8
                        : NATIVE_CHARSET;
            }
            channel.position(oldPosition);
            return new BufferedReader(new InputStreamReader(Channels.newInputStream(channel), charset));
        } catch (Throwable failure) {
            closeQuietly(channel, failure);
            throw failure;
        }
    }

    public static byte[] readFully(InputStream stream) throws IOException {
        try (stream) {
            return stream.readAllBytes();
        }
    }

    public static String readFullyAsString(InputStream stream) throws IOException {
        return new String(readFully(stream), UTF_8);
    }

    public static String readFullyAsString(InputStream stream, Charset charset) throws IOException {
        return new String(readFully(stream), charset);
    }

    public static void skipNBytes(InputStream input, long n) throws IOException {
        while (n > 0) {
            long ns = input.skip(n);
            if (ns > 0 && ns <= n)
                n -= ns;
            else if (ns == 0) {
                if (input.read() == -1)
                    throw new EOFException();
                n--;
            } else {
                throw new IOException("Unexpected skip bytes. Expected: " + n + ", Actual: " + ns);
            }
        }
    }

    public static void copyTo(InputStream src, OutputStream dest, byte[] buf) throws IOException {
        while (true) {
            int len = src.read(buf);
            if (len == -1)
                break;
            dest.write(buf, 0, len);
        }
    }

    public static InputStream wrapFromGZip(InputStream inputStream) throws IOException {
        return new GZIPInputStream(inputStream);
    }

    public static void closeQuietly(AutoCloseable closeable) {
        try {
            if (closeable != null)
                closeable.close();
        } catch (Throwable ignored) {
        }
    }

    public static void closeQuietly(AutoCloseable closeable, Throwable exception) {
        try {
            if (closeable != null)
                closeable.close();
        } catch (Throwable e) {
            exception.addSuppressed(e);
        }
    }
}
