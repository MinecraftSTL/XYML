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
package space.minecraftstl.xyml.task;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.util.io.UrlResponseInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/// Fetches text from ordered URIs while bounding both advertised and actually decoded response bytes.
///
/// HTTP content length is rejected before allocation when it exceeds the configured ceiling. Every decoded write is
/// counted independently so chunked, compressed, missing-length, incorrect-length, and non-HTTP responses cannot
/// exceed the same limit. Cached text is streamed through the identical actual-byte check.
@NotNullByDefault
public final class BoundedTextFetchTask extends FetchTask<String> {
    /// Small initial buffer that avoids allocating an attacker-advertised response length up front.
    private static final int INITIAL_BUFFER_BYTES = 8_192;

    /// Maximum decoded response bytes accepted from network or cache.
    private final long maximumBytes;

    /// Creates a stopped bounded UTF-compatible text fetch task.
    ///
    /// @param uris immutable ordered candidate URIs
    /// @param maximumBytes positive decoded response byte ceiling
    public BoundedTextFetchTask(
            @Unmodifiable List<URI> uris,
            long maximumBytes) {
        super(List.copyOf(Objects.requireNonNull(uris, "uris")));
        if (maximumBytes <= 0L) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
        this.maximumBytes = maximumBytes;
        setName(this.uris.get(0).toString());
    }

    /// Enables existing HTTP validator caching while keeping cache reads bounded.
    ///
    /// @return ETag-aware fetch policy
    @Override
    protected EnumCheckETag shouldCheckETag() {
        return EnumCheckETag.CHECK_E_TAG;
    }

    /// Streams cached UTF-8 text through the same actual-byte ceiling as a network response.
    ///
    /// @param cachedFile cached response file
    /// @throws IOException when the cache entry exceeds the byte ceiling or cannot be read
    @Override
    protected void useCachedResult(Path cachedFile) throws IOException {
        try (InputStream input = Files.newInputStream(cachedFile)) {
            setResult(new String(readBounded(input), StandardCharsets.UTF_8));
        }
    }

    /// Rejects an oversized advertised response and creates a bounded in-memory transfer context.
    ///
    /// @param response response metadata, or null for a non-HTTP candidate
    /// @param checkETag whether successful text should update HTTP validator cache
    /// @param bmclapiHash ignored mirror checksum metadata
    /// @return fresh bounded text context
    /// @throws IOException when the advertised response length exceeds the byte ceiling
    @Override
    protected Context getContext(
            @Nullable UrlResponseInfo response,
            boolean checkETag,
            @Nullable String bmclapiHash) throws IOException {
        if (response != null) {
            long advertisedLength = response.headers()
                    .firstValueAsLong("content-length")
                    .orElse(-1L);
            if (advertisedLength > maximumBytes) {
                throw new IOException("Text response exceeds its byte limit");
            }
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream(
                (int) Math.min(maximumBytes, INITIAL_BUFFER_BYTES));
        return new Context() {
            /// Number of decoded bytes written into the bounded response buffer.
            private long writtenBytes;

            /// Clears response bytes before a full retry.
            @Override
            public void reset() {
                output.reset();
                writtenBytes = 0L;
            }

            /// Appends one decoded range after enforcing the actual-byte ceiling.
            ///
            /// @param buffer source buffer
            /// @param offset first source byte
            /// @param length number of bytes to append
            /// @throws IOException when the response exceeds the configured ceiling
            @Override
            public void write(byte[] buffer, int offset, int length) throws IOException {
                if (length > maximumBytes - writtenBytes) {
                    throw new IOException("Text response exceeds its byte limit");
                }
                output.write(buffer, offset, length);
                writtenBytes += length;
            }

            /// Decodes and stores a complete response and optionally updates the text cache.
            ///
            /// @throws IOException when the response cannot be cached
            @Override
            public void close() throws IOException {
                if (!isSuccess()) {
                    return;
                }
                Charset charset = response == null
                        ? StandardCharsets.UTF_8
                        : space.minecraftstl.xyml.util.io.NetworkUtils.getCharsetFromContentType(
                                response.headers().firstValue("content-type").orElse(null));
                String result = output.toString(charset);
                BoundedTextFetchTask.this.setResult(result);
                if (checkETag && response != null) {
                    repository.cacheText(response, result);
                }
            }
        };
    }

    /// Reads a stream into a bounded byte array for trusted-cache reuse.
    ///
    /// @param input source stream
    /// @return exact response bytes
    /// @throws IOException when the stream exceeds the configured ceiling or cannot be read
    private byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                (int) Math.min(maximumBytes, INITIAL_BUFFER_BYTES));
        byte[] buffer = new byte[INITIAL_BUFFER_BYTES];
        long readBytes = 0L;
        while (true) {
            int count = input.read(buffer);
            if (count < 0) {
                return output.toByteArray();
            }
            if (count > maximumBytes - readBytes) {
                throw new IOException("Cached text response exceeds its byte limit");
            }
            output.write(buffer, 0, count);
            readBytes += count;
        }
    }
}
