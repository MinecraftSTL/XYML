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

import space.minecraftstl.xyml.event.Event;
import space.minecraftstl.xyml.event.EventBus;
import space.minecraftstl.xyml.event.EventManager;
import space.minecraftstl.xyml.util.*;
import space.minecraftstl.xyml.util.io.*;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static space.minecraftstl.xyml.util.Lang.threadPool;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Base task for fetching one resource from ordered mirror URIs with retry, cache, resume, and progress support.
///
/// @param <T> task result type produced by the concrete download context
@NotNullByDefault
public abstract class FetchTask<T> extends Task<T> {

    /// Default number of attempts made for each candidate URI.
    protected static final int DEFAULT_RETRY = 5;

    /// Immutable, ordered candidate URI snapshot.
    protected final @Unmodifiable List<URI> uris;

    /// Maximum attempts for each candidate URI.
    protected int retry = DEFAULT_RETRY;

    /// Repository used for content-addressed and HTTP validator caches.
    protected CacheRepository repository = CacheRepository.getInstance();

    /// Creates a fetch task for one or more ordered candidate URIs.
    ///
    /// @param uris candidate URIs; the first successful source wins
    /// @throws IllegalArgumentException if the list is empty
    public FetchTask(List<URI> uris) {
        Objects.requireNonNull(uris);

        this.uris = List.copyOf(uris);

        if (this.uris.isEmpty())
            throw new IllegalArgumentException("At least one URL is required");

        setExecutor(DOWNLOAD_EXECUTOR);
    }

    /// Changes the number of attempts made for each candidate URI.
    ///
    /// @param retry positive attempt count
    /// @throws IllegalArgumentException if `retry` is not positive
    public void setRetry(int retry) {
        if (retry <= 0)
            throw new IllegalArgumentException("Retry count must be greater than 0");

        this.retry = retry;
    }

    /// Replaces the cache repository used by subsequent execution.
    ///
    /// @param repository cache repository
    public void setCacheRepository(CacheRepository repository) {
        this.repository = repository;
    }

    /// Runs immediately before each network attempt.
    ///
    /// @param uri original candidate URI
    /// @throws IOException if preparation fails
    protected void beforeDownload(URI uri) throws IOException {
    }

    /// Consumes a cache hit without opening a network response.
    ///
    /// @param cachedFile cached file path
    /// @throws IOException if the cached result cannot be consumed
    protected abstract void useCachedResult(Path cachedFile) throws IOException;

    /// Selects the cache validator strategy for this fetch.
    ///
    /// @return ETag strategy, or [EnumCheckETag#CACHED] when execution is already complete
    protected abstract EnumCheckETag shouldCheckETag();

    /// Creates a context for a non-HTTP transfer.
    ///
    /// @return fresh transfer context
    /// @throws IOException if the context cannot be created
    private Context getContext() throws IOException {
        return getContext(null, false, null);
    }

    /// Creates a transfer context for an HTTP response.
    ///
    /// @param response response metadata, or `null` for a non-HTTP transfer
    /// @param checkETag whether the context should persist HTTP validator metadata
    /// @param bmclapiHash optional SHA-1 supplied by a BMCLAPI response
    /// @return fresh transfer context
    /// @throws IOException if the context cannot be created
    protected abstract Context getContext(@Nullable UrlResponseInfo response, boolean checkETag, @Nullable String bmclapiHash) throws IOException;

    /// Tries each candidate URI, aggregating source failures and honoring task cancellation.
    ///
    /// @throws Exception if every candidate source fails
    @Override
    public void execute() throws Exception {
        boolean checkETag;
        switch (shouldCheckETag()) {
            case CHECK_E_TAG -> checkETag = true;
            case NOT_CHECK_E_TAG -> checkETag = false;
            default -> {
                return;
            }
        }

        @Nullable ArrayList<DownloadException> exceptions = null;

        if (SEMAPHORE != null)
            SEMAPHORE.acquire();
        try {
            for (URI uri : uris) {
                try {
                    if (NetworkUtils.isHttpUri(uri))
                        downloadHttp(uri, checkETag);
                    else
                        downloadNotHttp(uri);
                    return;
                } catch (DownloadException e) {
                    if (exceptions == null)
                        exceptions = new ArrayList<>();
                    exceptions.add(e);
                }
            }
        } catch (InterruptedException ignored) {
            // Cancelled
        } finally {
            if (SEMAPHORE != null)
                SEMAPHORE.release();
        }

        if (exceptions != null) {
            DownloadException last = exceptions.remove(exceptions.size() - 1);
            for (DownloadException exception : exceptions) {
                last.addSuppressed(exception);
            }
            throw last;
        }
    }

    /// Tracks validators and byte counts required to resume an interrupted identity-encoded HTTP transfer.
    @NotNullByDefault
    private static final class HttpResumeContext {
        /// Strict parser for RFC-style byte content ranges used by resumed responses.
        private static final Pattern CONTENT_RANGE_PATTERN = Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)");

        /// Creates resumable state only when the initial response has a known identity-encoded length and validator.
        ///
        /// @param response initial successful HTTP response
        /// @return resume state, or `null` when the response cannot be resumed safely
        /// @throws IOException if response headers cannot be interpreted
        static @Nullable FetchTask.HttpResumeContext of(UrlResponseInfo response) throws IOException {
            if (response.responseCode() != HttpURLConnection.HTTP_OK)
                return null;

            boolean acceptRanges = response.headers().firstValue("accept-ranges").orElse("").equalsIgnoreCase("bytes");
            if (!acceptRanges)
                return null;

            var contentEncoding = ContentEncoding.fromHeaders(response.headers());
            if (contentEncoding != ContentEncoding.IDENTITY)
                return null;

            long contentLength = response.headers().firstValueAsLong("content-length").orElse(-1L);
            if (contentLength < 0)
                return null;

            @Nullable String eTag = response.headers().firstValue("etag").orElse(null);
            @Nullable String strongETag = isStrongETag(eTag) ? eTag : null;
            @Nullable String lastModified = response.headers().firstValue("last-modified").orElse(null);
            if (strongETag == null && StringUtils.isBlank(lastModified))
                return null;

            return new HttpResumeContext(response.uri(), contentLength, strongETag, lastModified);
        }

        /// URI of the initial response, used to validate Last-Modified resumes.
        private final URI uri;

        /// Total uncompressed resource length reported by the initial response.
        private final long contentLength;

        /// Strong ETag preferred for `If-Range`, when supplied.
        private final @Nullable String strongETag;

        /// Last-Modified validator used when no strong ETag exists.
        private final @Nullable String lastModified;

        /// Number of uncompressed bytes already written to the current context.
        long countUncompressed;

        /// Creates resume state from validated initial-response metadata.
        ///
        /// @param uri initial response URI
        /// @param contentLength total uncompressed resource length
        /// @param strongETag optional strong ETag
        /// @param lastModified optional Last-Modified validator
        private HttpResumeContext(URI uri, long contentLength, @Nullable String strongETag, @Nullable String lastModified) {
            this.uri = uri;
            this.contentLength = contentLength;
            this.strongETag = strongETag;
            this.lastModified = lastModified;
        }

        /// Tests whether an ETag is present and strong enough for `If-Range`.
        ///
        /// @param eTag optional ETag header value
        /// @return `true` for a nonblank ETag without the weak `W/` prefix
        private static boolean isStrongETag(@Nullable String eTag) {
            return StringUtils.isNotBlank(eTag) && !eTag.regionMatches(true, 0, "W/", 0, 2);
        }

        /// Returns the validator value for an `If-Range` request header.
        ///
        /// @return strong ETag or Last-Modified value
        String ifRange() {
            return strongETag != null ? strongETag : Objects.requireNonNull(lastModified);
        }

        /// Verifies that a partial response continues exactly from the current output position.
        ///
        /// @param statusCode HTTP status code
        /// @param response partial response metadata
        /// @return `true` when the response can be appended to the current context
        /// @throws IOException if response headers cannot be interpreted
        boolean canResume(int statusCode, UrlResponseInfo response) throws IOException {
            if (statusCode != HttpURLConnection.HTTP_PARTIAL)
                return false;

            var contentEncoding = ContentEncoding.fromHeaders(response.headers());
            if (contentEncoding != ContentEncoding.IDENTITY)
                return false;

            long contentLength = response.headers().firstValueAsLong("content-length").orElse(-1L);
            if (this.contentLength != contentLength + this.countUncompressed)
                return false;

            if (strongETag != null) {
                @Nullable String eTag = response.headers().firstValue("etag").orElse(null);
                if (!strongETag.equals(eTag))
                    return false;
            } else {
                if (!uri.equals(response.uri()))
                    return false;

                String lastModified = response.headers().firstValue("last-modified").orElse("");
                if (!Objects.requireNonNull(this.lastModified).equals(lastModified))
                    return false;
            }

            String contentRange = response.headers().firstValue("content-range").orElse("");
            Matcher matcher = CONTENT_RANGE_PATTERN.matcher(contentRange);
            if (!matcher.matches())
                return false;

            try {
                long start = Long.parseLong(matcher.group(1));
                long end = Long.parseLong(matcher.group(2));
                long total = Long.parseLong(matcher.group(3));

                if (start != countUncompressed || end < start || total != this.contentLength)
                    return false;

                return end - start + 1 == contentLength;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        /// Reports whether the current output has a nonempty incomplete prefix.
        ///
        /// @return `true` when a range request can continue the transfer
        boolean hasPartialContent() {
            return countUncompressed > 0 && countUncompressed < contentLength;
        }
    }

    /// Copies one response body into a context while updating progress, resume state, and global speed metrics.
    ///
    /// @param context destination context
    /// @param resume optional HTTP resume state
    /// @param inputStream raw response stream
    /// @param contentLength expected encoded response length, or a negative value when unknown
    /// @param contentEncoding response content encoding
    /// @throws IOException if reading, decoding, writing, or size validation fails
    /// @throws InterruptedException if the task is cancelled
    private void download(
            Context context,
            @Nullable FetchTask.HttpResumeContext resume,
            InputStream inputStream,
            long contentLength,
            ContentEncoding contentEncoding) throws IOException, InterruptedException {
        boolean success = false;
        try (var counter = new CounterInputStream(inputStream);
             var input = contentEncoding.wrap(counter)) {
            long lastDownloaded = 0L;
            byte[] buffer = new byte[IOUtils.DEFAULT_BUFFER_SIZE];
            while (true) {
                if (isCancelled()) break;

                int len = input.read(buffer);
                if (len == -1) break;

                try {
                    context.write(buffer, 0, len);
                } catch (Throwable e) {
                    context.broken = true;
                    throw e;
                }

                if (resume != null)
                    resume.countUncompressed += len;

                if (contentLength >= 0) {
                    // Update progress information per second
                    updateProgress(counter.downloaded, contentLength);
                }

                updateDownloadSpeed(counter.downloaded - lastDownloaded);
                lastDownloaded = counter.downloaded;
            }

            if (isCancelled())
                throw new InterruptedException();

            updateDownloadSpeed(counter.downloaded - lastDownloaded);

            if (contentLength >= 0 && counter.downloaded != contentLength)
                throw new IOException("Unexpected file size: " + counter.downloaded + ", expected: " + contentLength);

            success = true;
        }

        if (success) {
            context.withResult(true);
        }
    }

    /// Downloads one HTTP candidate with validators, redirects, retry, and resumable-transfer handling.
    ///
    /// @param uri original HTTP candidate URI
    /// @param checkETag whether HTTP validator caching is enabled
    /// @throws DownloadException if all attempts fail
    /// @throws InterruptedException if the task is cancelled
    private void downloadHttp(URI uri, boolean checkETag) throws DownloadException, InterruptedException {
        if (checkETag) {
            // Handle cache
            try {
                Path cache = repository.getCachedRemoteFile(uri, true);
                useCachedResult(cache);
                LOG.info("Using cached file for " + NetworkUtils.dropQuery(uri));
                return;
            } catch (IOException ignored) {
            }
        }

        @Nullable Context context = null;
        @Nullable HttpResumeContext resumeContext = null;

        @Nullable ArrayList<Exception> exceptions = null;

        // If loading the cache fails, the cache should not be loaded again.
        boolean useCachedResult = true;
        try {
            for (int retryTime = 0, retryLimit = retry; retryTime < retryLimit; retryTime++) {
                if (isCancelled()) {
                    throw new InterruptedException();
                }

                @Nullable List<URI> redirects = null;
                try {
                    beforeDownload(uri);
                    updateProgress(0);

                    @Nullable HttpURLConnection connection = null;
                    UrlResponseInfo responseInfo;
                    @Nullable String bmclapiHash;
                    int responseCode;

                    URI currentURI = uri;

                    LinkedHashMap<String, String> headers = new LinkedHashMap<>();
                    headers.put("accept-encoding", "gzip");

                    boolean resumeRequested = resumeContext != null && resumeContext.hasPartialContent();
                    if (useCachedResult && checkETag && !resumeRequested)
                        headers.putAll(repository.injectConnection(uri));
                    if (resumeRequested) {
                        headers.put("range", "bytes=" + resumeContext.countUncompressed + "-");
                        headers.put("if-range", resumeContext.ifRange());
                    }

                    do {
                        connection = NetworkUtils.createHttpConnection(currentURI);
                        boolean keepConnection = false;
                        try {
                            headers.forEach(connection::setRequestProperty);
                            responseCode = connection.getResponseCode();
                            responseInfo = UrlResponseInfo.of(connection);

                            bmclapiHash = responseInfo.headers().firstValue("x-bmclapi-hash").orElse(null);
                            if (DigestUtils.isSha1Digest(bmclapiHash)) {
                                Optional<Path> cache = repository.checkExistentFile(null, "SHA-1", bmclapiHash);
                                if (cache.isPresent()) {
                                    useCachedResult(cache.get());
                                    LOG.info("Using cached file for " + NetworkUtils.dropQuery(uri));
                                    return;
                                }
                            }

                            if (responseCode >= 300 && responseCode <= 308 && responseCode != 306 && responseCode != 304) {
                                if (redirects == null) {
                                    redirects = new ArrayList<>();
                                } else if (redirects.size() >= 20) {
                                    throw new IOException("Too much redirects");
                                }

                                @Nullable String location = connection.getHeaderField("Location");
                                if (StringUtils.isBlank(location))
                                    throw new IOException("Redirected to an empty location");

                                URI target = currentURI.resolve(NetworkUtils.encodeLocation(location));
                                redirects.add(target);

                                if (!NetworkUtils.isHttpUri(target))
                                    throw new IOException("Redirected to not http URI: " + target);

                                currentURI = target;
                            } else {
                                keepConnection = true;
                                break;
                            }
                        } finally {
                            if (!keepConnection && connection != null) {
                                closeHttpConnection(connection);
                                connection = null;
                            }
                        }
                    } while (true);

                    @Nullable InputStream inputStream = null;
                    boolean responseBodyConsumed = false;
                    try {
                        if (resumeRequested && responseCode == 416) {
                            resumeContext = null;
                            discardContext(context);
                            context = null;
                            retryLimit++;
                            continue;
                        }

                        if (useCachedResult && responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                            // Handle cache
                            try {
                                Path cache = repository.getCachedRemoteFile(responseInfo.uri(), false);
                                useCachedResult(cache);
                                LOG.info("Using cached file for " + NetworkUtils.dropQuery(uri));
                                return;
                            } catch (CacheRepository.CacheExpiredException e) {
                                LOG.info("Cache expired for " + NetworkUtils.dropQuery(uri));
                            } catch (IOException e) {
                                LOG.warning("Unable to use cached file, redownload " + NetworkUtils.dropQuery(uri), e);
                                repository.removeRemoteEntry(currentURI);
                                useCachedResult = false;
                                // Now we must reconnect the server since 304 may result in empty content,
                                // if we want to redownload the file, we must reconnect the server without etag settings.
                                retryLimit++;
                                continue;
                            }
                        } else if (responseCode / 100 == 4) {
                            throw new FileNotFoundException(uri.toString());
                        } else if (responseCode / 100 != 2) {
                            throw new ResponseCodeException(uri, responseCode);
                        }

                        long contentLength = responseInfo.headers().firstValueAsLong("content-length").orElse(-1L);
                        var contentEncoding = ContentEncoding.fromHeaders(responseInfo.headers());

                        if (context == null) {
                            context = getContext(responseInfo, checkETag, bmclapiHash);
                            resumeContext = HttpResumeContext.of(responseInfo);
                        } else if (resumeRequested) {
                            if (resumeContext.canResume(responseCode, responseInfo)) {
                                // Resume download
                                LOG.info("Resuming " + resumeContext.uri + " from " + resumeContext.countUncompressed);
                            } else {
                                // Failed to resume download, so we will retry from the beginning
                                resumeContext = null;
                                discardContext(context);
                                context = null;
                                retryLimit++;
                                continue;
                            }
                        } else {
                            discardContext(context);
                            context = getContext(responseInfo, checkETag, bmclapiHash);
                            resumeContext = HttpResumeContext.of(responseInfo);
                        }

                        try {
                            inputStream = connection.getInputStream();
                            download(context,
                                    resumeContext, inputStream,
                                    contentLength,
                                    contentEncoding);
                            inputStream = null;
                            responseBodyConsumed = true;
                        } catch (IOException | InterruptedException | RuntimeException | Error e) {
                            if (context.broken) {
                                IOUtils.closeQuietly(context, e);
                                context = null;
                                resumeContext = null;
                            }
                            throw e;
                        }
                        try {
                            context.close();
                        } catch (IOException | RuntimeException | Error e) {
                            context.withResult(false);
                            IOUtils.closeQuietly(context, e);
                            context = null;
                            resumeContext = null;
                            throw e;
                        }
                        context = null;
                        return;
                    } finally {
                        IOUtils.closeQuietly(inputStream);
                        if (connection != null && !responseBodyConsumed)
                            closeHttpConnection(connection);
                    }
                } catch (InterruptedException e) {
                    throw e;
                } catch (FileNotFoundException ex) {
                    LOG.warning("Failed to download " + uri + ", not found" + (redirects == null ? "" : ", redirects: " + redirects), ex);
                    throw toDownloadException(uri, ex, exceptions); // we will not try this URL again
                } catch (Exception ex) {
                    if (exceptions == null)
                        exceptions = new ArrayList<>();

                    exceptions.add(ex);

                    LOG.warning("Failed to download " + uri + ", repeat times: " + retryTime + (redirects == null ? "" : ", redirects: " + redirects), ex);

                    if (retryTime < retryLimit - 1) {
                        // Wait for a while before retrying
                        Thread.sleep(200);
                    }
                }
            }
        } finally {
            if (context != null) {
                try {
                    context.close();
                } catch (IOException e) {
                    LOG.warning("Failed to close context for " + NetworkUtils.dropQuery(uri), e);
                }
            }
        }

        throw toDownloadException(uri, null, exceptions);
    }

    /// Disconnects an HTTP URL connection whose response body will not be consumed.
    ///
    /// @param connection connection to close
    private static void closeHttpConnection(HttpURLConnection connection) {
        IOUtils.closeQuietly(connection.getErrorStream());
        connection.disconnect();
    }

    /// Marks an incomplete output context unsuccessful and closes it quietly.
    ///
    /// @param context context to discard, or `null` when none has been created
    private static void discardContext(@Nullable Context context) {
        if (context != null) {
            context.withResult(false);
            IOUtils.closeQuietly(context);
        }
    }

    /// Downloads a non-HTTP candidate with the configured retry count.
    ///
    /// @param uri non-HTTP candidate URI
    /// @throws DownloadException if all attempts fail
    /// @throws InterruptedException if the task is cancelled
    private void downloadNotHttp(URI uri) throws DownloadException, InterruptedException {
        @Nullable ArrayList<Exception> exceptions = null;
        for (int retryTime = 0; retryTime < retry; retryTime++) {
            if (isCancelled()) {
                throw new InterruptedException();
            }

            try {
                beforeDownload(uri);
                updateProgress(0);

                URLConnection conn = NetworkUtils.createConnection(uri);
                try (Context context = getContext()) {
                    download(context,
                            null, conn.getInputStream(),
                            conn.getContentLengthLong(),
                            ContentEncoding.fromConnection(conn));
                }
                return;
            } catch (InterruptedException e) {
                throw e;
            } catch (FileNotFoundException ex) {
                LOG.warning("Failed to download " + uri + ", not found", ex);

                throw toDownloadException(uri, ex, exceptions); // we will not try this URL again
            } catch (Exception ex) {
                if (exceptions == null)
                    exceptions = new ArrayList<>();

                exceptions.add(ex);
                LOG.warning("Failed to download " + uri + ", repeat times: " + retryTime, ex);
            }
        }

        throw toDownloadException(uri, null, exceptions);
    }

    /// Combines one terminal failure and earlier attempt failures into a single download exception.
    ///
    /// @param uri candidate URI
    /// @param last terminal failure, or `null` when only retry failures are available
    /// @param exceptions earlier retry failures, or `null` when none were collected
    /// @return combined download exception
    private static DownloadException toDownloadException(URI uri, @Nullable Exception last, @Nullable ArrayList<Exception> exceptions) {
        if (exceptions == null || exceptions.isEmpty()) {
            return new DownloadException(uri, last != null
                    ? last
                    : new IOException("No exceptions"));
        } else {
            if (last == null)
                last = exceptions.remove(exceptions.size() - 1);

            for (Exception e : exceptions) {
                last.addSuppressed(e);
            }
            return new DownloadException(uri, last);
        }
    }

    /// Daemon timer that publishes one aggregate download-speed sample per second.
    private static final Timer timer = new Timer("DownloadSpeedRecorder", true);

    /// Bytes downloaded since the most recent speed sample.
    private static final AtomicLong downloadSpeed = new AtomicLong(0L);

    /// Global channel carrying aggregate download-speed samples.
    public static final EventManager<SpeedEvent> SPEED_EVENT = EventBus.EVENT_BUS.channel(SpeedEvent.class);

    static {
        timer.schedule(new TimerTask() {
            /// Publishes the accumulated byte count and starts the next sampling interval.
            @Override
            public void run() {
                SPEED_EVENT.fireEvent(new SpeedEvent(SPEED_EVENT, downloadSpeed.getAndSet(0)));
            }
        }, 0, 1000);
    }

    /// Adds newly transferred bytes to the current global speed interval.
    ///
    /// @param speed newly transferred byte count
    private static void updateDownloadSpeed(long speed) {
        downloadSpeed.addAndGet(speed);
    }

    /// Counts raw encoded bytes read from a response stream.
    @NotNullByDefault
    private static final class CounterInputStream extends FilterInputStream {
        /// Number of raw bytes successfully read through this stream.
        long downloaded;

        /// Creates a counting wrapper.
        ///
        /// @param in wrapped response stream
        CounterInputStream(InputStream in) {
            super(in);
        }

        /// Reads and counts one byte.
        ///
        /// @return byte value, or `-1` at end of stream
        /// @throws IOException if the wrapped stream fails
        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0)
                downloaded++;
            return b;
        }

        /// Reads and counts a byte-array slice.
        ///
        /// @param b destination buffer
        /// @param off destination offset
        /// @param len maximum byte count
        /// @return bytes read, or `-1` at end of stream
        /// @throws IOException if the wrapped stream fails
        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n >= 0)
                downloaded += n;
            return n;
        }
    }

    /// Event carrying the aggregate number of bytes downloaded during the preceding one-second interval.
    @NotNullByDefault
    public static class SpeedEvent extends Event {
        /// Bytes downloaded during the sample interval.
        private final long speed;

        /// Creates a download-speed event.
        ///
        /// @param source event source
        /// @param speed bytes downloaded during the sample interval
        public SpeedEvent(Object source, long speed) {
            super(source);

            this.speed = speed;
        }

        /// Returns the sampled download speed in bytes per second.
        ///
        /// @return sampled byte count
        public long getSpeed() {
            return speed;
        }

        /// Returns a diagnostic representation containing the sampled speed.
        ///
        /// @return diagnostic event string
        @Override
        public String toString() {
            return new ToStringBuilder(this).append("speed", speed).toString();
        }
    }

    /// Receives transfer bytes and commits or discards the concrete task result when closed.
    @NotNullByDefault
    protected static abstract class Context implements Closeable {
        /// Whether the transfer completed successfully.
        private boolean success;

        /// Whether writing failed and the context can no longer be resumed.
        private boolean broken;

        /// Reports whether the transfer completed successfully.
        ///
        /// @return `true` after all expected bytes have been written
        protected final boolean isSuccess() {
            return success;
        }

        /// Records the final transfer result before the context is closed.
        ///
        /// @param success whether the transfer completed successfully
        public void withResult(boolean success) {
            this.success = success;
        }

        /// Resets the destination so a fresh response can replace partial output.
        ///
        /// @throws IOException if the destination cannot be reset
        public abstract void reset() throws IOException;

        /// Writes one decoded byte range to the destination.
        ///
        /// @param buffer source buffer
        /// @param offset first source byte
        /// @param len number of bytes to write
        /// @throws IOException if the destination write fails
        public abstract void write(byte[] buffer, int offset, int len) throws IOException;

        /// Closes the destination and commits or discards it according to [#isSuccess()].
        ///
        /// @throws IOException if closing or committing the destination fails
        @Override
        public abstract void close() throws IOException;
    }

    /// Cache-validator decision returned by concrete fetch tasks.
    @NotNullByDefault
    protected enum EnumCheckETag {
        /// Perform HTTP cache validation and download when the cached entry is stale.
        CHECK_E_TAG,

        /// Download without HTTP ETag validation.
        NOT_CHECK_E_TAG,

        /// A cache-only result has already completed the task, so no transfer is required.
        CACHED
    }


    /// Default global maximum number of concurrently active fetch tasks.
    public static int DEFAULT_CONCURRENCY = Math.min(Runtime.getRuntime().availableProcessors() * 4, 64);

    /// Current configured global download concurrency.
    private static int downloadExecutorConcurrency = DEFAULT_CONCURRENCY;

    // For Java 21 or later, DOWNLOAD_EXECUTOR dispatches tasks to virtual threads, and concurrency is controlled by SEMAPHORE.
    // For versions earlier than Java 21, DOWNLOAD_EXECUTOR is a ThreadPoolExecutor, SEMAPHORE is null, and concurrency is controlled by the thread pool size.

    /// Shared executor used by every fetch task.
    private static final ExecutorService DOWNLOAD_EXECUTOR;

    /// Concurrency gate used with a virtual-thread executor, or `null` for a bounded platform-thread pool.
    private static final @Nullable Semaphore SEMAPHORE;

    static {
        @Nullable ExecutorService executorService = Schedulers.newVirtualThreadPerTaskExecutor("Download");
        if (executorService != null) {
            DOWNLOAD_EXECUTOR = executorService;
            SEMAPHORE = new Semaphore(DEFAULT_CONCURRENCY);
        } else {
            DOWNLOAD_EXECUTOR = threadPool("Download", true, downloadExecutorConcurrency, 10, TimeUnit.SECONDS);
            SEMAPHORE = null;
        }
    }

    /// Updates global download concurrency; callers must serialize configuration writes on the UI executor.
    ///
    /// @param concurrency requested positive concurrency limit
    public static void setDownloadExecutorConcurrency(int concurrency) {
        concurrency = Math.max(concurrency, 1);

        int prevDownloadExecutorConcurrency = downloadExecutorConcurrency;
        int change = concurrency - prevDownloadExecutorConcurrency;
        if (change == 0)
            return;

        downloadExecutorConcurrency = concurrency;
        if (SEMAPHORE != null) {
            if (change > 0) {
                SEMAPHORE.release(change);
            } else {
                int permits = -change;
                if (!SEMAPHORE.tryAcquire(permits)) {
                    Schedulers.io().execute(() -> {
                        try {
                            for (int i = 0; i < permits; i++) {
                                SEMAPHORE.acquire();
                            }
                        } catch (InterruptedException e) {
                            throw new AssertionError("Unreachable", e);
                        }
                    });
                }
            }
        } else {
            var downloadExecutor = (ThreadPoolExecutor) DOWNLOAD_EXECUTOR;

            if (downloadExecutor.getMaximumPoolSize() <= concurrency) {
                downloadExecutor.setMaximumPoolSize(concurrency);
                downloadExecutor.setCorePoolSize(concurrency);
            } else {
                downloadExecutor.setCorePoolSize(concurrency);
                downloadExecutor.setMaximumPoolSize(concurrency);
            }
        }
    }

    /// Returns the current global download concurrency.
    ///
    /// @return positive concurrency limit
    public static int getDownloadExecutorConcurrency() {
        return downloadExecutorConcurrency;
    }

    /// Lifecycle flag set once launcher initialization has completed.
    private static volatile boolean initialized = false;

    /// Marks the fetch subsystem as initialized.
    public static void notifyInitialized() {
        initialized = true;
    }

}
