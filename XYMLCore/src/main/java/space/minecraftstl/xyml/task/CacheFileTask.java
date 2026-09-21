/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2025 huangyuhui <huanghongxun2008@126.com> and contributors
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

import org.glavo.url.WebURL;
import space.minecraftstl.xyml.util.CacheRepository;
import space.minecraftstl.xyml.util.io.NetworkUtils;
import space.minecraftstl.xyml.util.io.UrlResponseInfo;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Downloads one HTTP resource directly into an internally synchronized cache repository.
///
/// @author Glavo
@NotNullByDefault
public final class CacheFileTask extends FetchTask<Path> {

    /// Creates a cache fetch from one URL string.
    ///
    /// @param url HTTP source URL string
    public CacheFileTask(String url) {
        this(WebURL.parse(url));
    }

    /// Creates a cache fetch from one HTTP URL.
    ///
    /// @param url HTTP source URL
    public CacheFileTask(WebURL url) {
        super(List.of(url));
        setName(url.toString());
        useCacheOperationResource();

        if (!NetworkUtils.isHttpUri(url))
            throw new IllegalArgumentException(url.toString());
    }

    /// Creates a cache fetch from ordered HTTP candidate URLs.
    ///
    /// @param urls candidate HTTP URLs
    public CacheFileTask(List<WebURL> urls) {
        super(urls);
        setName(urls.get(0).toString());
        useCacheOperationResource();

        if (!urls.stream().allMatch(NetworkUtils::isHttpUri))
            throw new IllegalArgumentException(urls.toString());
    }

    @Override
    protected EnumCheckETag shouldCheckETag() {
        // Check cache
        for (WebURL url : urls) {
            try {
                setResult(repository.getCachedRemoteFile(url, true));
                LOG.info("Using cached file for " + NetworkUtils.dropQuery(url));
                return EnumCheckETag.CACHED;
            } catch (CacheRepository.CacheExpiredException e) {
                LOG.info("Cache expired for " + NetworkUtils.dropQuery(url));
            } catch (IOException ignored) {
            }
        }
        return EnumCheckETag.CHECK_E_TAG;
    }

    @Override
    protected void useCachedResult(Path cache) {
        setResult(cache);
    }

    @Override
    protected Context getContext(@Nullable UrlResponseInfo response, boolean checkETag, @Nullable String bmclapiHash) throws IOException {
        assert checkETag;
        assert response != null;

        return new Context() {
            private final Path temp = Files.createTempFile("xyml-download-", null);
            private final FileChannel fileOutput = FileChannel.open(temp,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.CREATE);

            @Override
            public void reset() throws IOException {
                fileOutput.truncate(0L);
            }

            @Override
            public void write(byte[] buffer, int offset, int len) throws IOException {
                ByteBuffer byteBuffer = ByteBuffer.wrap(buffer, offset, len);
                while (byteBuffer.hasRemaining()) {
                    //noinspection ResultOfMethodCallIgnored
                    fileOutput.write(byteBuffer);
                }
            }

            @Override
            public void close() throws IOException {
                try {
                    fileOutput.close();
                } catch (IOException e) {
                    LOG.warning("Failed to close file: " + temp, e);
                    deleteTempFile();
                    throw e;
                }

                if (!isSuccess()) {
                    deleteTempFile();
                    return;
                }

                try {
                    setResult(repository.cacheRemoteFile(response, temp));
                } finally {
                    deleteTempFile();
                }
            }

            private void deleteTempFile() {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    LOG.warning("Failed to delete file: " + temp, e);
                }
            }
        };
    }
}
