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
package space.minecraftstl.xyml.task;

import com.google.gson.reflect.TypeToken;
import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.io.NetworkUtils;
import space.minecraftstl.xyml.util.io.UrlResponseInfo;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/// Fetches UTF-compatible text and supports pure asynchronous JSON decoding continuations.
///
/// ETag and content-cache writes are serialized by the configured cache repository, so the fetch itself uses a shared
/// cache-operation resource rather than the conservative global fallback.
///
/// @author huangyuhui
@NotNullByDefault
public final class GetTask extends FetchTask<String> {

    /// Creates a text fetch from one URI string.
    ///
    /// @param uri source URI string
    public GetTask(String uri) {
        this(NetworkUtils.toURI(uri));
    }

    /// Creates a text fetch from one URI.
    ///
    /// @param url source URI
    public GetTask(URI url) {
        this(List.of(url));
        setName(url.toString());
    }

    /// Creates a text fetch from ordered candidate URIs.
    ///
    /// @param url immutable candidate URI list
    public GetTask(List<URI> url) {
        super(url);
        setName(url.get(0).toString());
        useCacheOperationResource();
    }

    /// Enables the existing ETag cache for text responses.
    ///
    /// @return ETag-aware fetch policy
    @Override
    protected EnumCheckETag shouldCheckETag() {
        return EnumCheckETag.CHECK_E_TAG;
    }

    /// Reads a cached text result using the established default charset behavior.
    ///
    /// @param cachedFile cached response file
    /// @throws IOException if the cached file cannot be read
    @Override
    protected void useCachedResult(Path cachedFile) throws IOException {
        setResult(Files.readString(cachedFile));
    }

    /// Creates the in-memory response context and persists successful ETag text through the cache repository.
    ///
    /// @param response response metadata, or null for a non-HTTP source
    /// @param checkETag whether successful text should update the validator cache
    /// @param bmclapiHash ignored mirror checksum metadata
    /// @return in-memory text response context
    @Override
    protected Context getContext(@Nullable UrlResponseInfo response, boolean checkETag, @Nullable String bmclapiHash) {
        long length = -1;
        if (response != null)
            length = response.headers().firstValueAsLong("content-length").orElse(-1L);
        final var baos = new ByteArrayOutputStream(length <= 0 ? 8192 : (int) length);

        return new Context() {
            @Override
            public void reset() throws IOException {
                baos.reset();
            }

            @Override
            public void write(byte[] buffer, int offset, int len) {
                baos.write(buffer, offset, len);
            }

            @Override
            public void close() throws IOException {
                if (!isSuccess()) return;

                Charset charset = StandardCharsets.UTF_8;
                if (response != null)
                    charset = NetworkUtils.getCharsetFromContentType(response.headers().firstValue("content-type").orElse(null));

                String result = baos.toString(charset);
                setResult(result);

                if (checkETag) {
                    repository.cacheText(response, result);
                }
            }
        };
    }

    /// Creates a pure JSON-decoding continuation for one concrete result class.
    ///
    /// @param type decoded result class
    /// @param <T> decoded result type
    /// @return orchestration continuation that decodes this fetch result
    public <T> Task<T> thenGetJsonAsync(Class<T> type) {
        return thenGetJsonAsync(TypeToken.get(type));
    }

    /// Creates a pure JSON-decoding continuation for one generic result token.
    ///
    /// @param type decoded result token
    /// @param <T> decoded result type
    /// @return orchestration continuation that decodes this fetch result
    public <T> Task<T> thenGetJsonAsync(TypeToken<T> type) {
        return thenApplyAsync(jsonString -> JsonUtils.fromNonNullJson(jsonString, type)).asOrchestration();
    }
}
