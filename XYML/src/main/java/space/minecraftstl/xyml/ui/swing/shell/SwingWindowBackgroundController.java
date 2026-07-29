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
package space.minecraftstl.xyml.ui.swing.shell;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.Metadata;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.theme.BackgroundLoadPolicy;
import space.minecraftstl.xyml.theme.NetworkBackgroundImageCachePolicy;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingBackgroundSource;
import space.minecraftstl.xyml.ui.swing.SwingThemeManager;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingWindowAppearanceRequest;
import space.minecraftstl.xyml.util.io.NetworkUtils;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.random.RandomGenerator;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Loads renderer-ready backgrounds away from the EDT and applies only the newest completed request.
@NotNullByDefault
final class SwingWindowBackgroundController implements AutoCloseable {
    /// Maximum accepted encoded image size.
    private static final long MAXIMUM_IMAGE_BYTES = 32L * 1024L * 1024L;

    /// Maximum accepted decoded image pixel count.
    private static final long MAXIMUM_IMAGE_PIXELS = 64L * 1024L * 1024L;

    /// Supported local image suffixes used for directory discovery.
    private static final @Unmodifiable List<String> IMAGE_SUFFIXES =
            List.of("png", "jpg", "jpeg", "gif", "webp");

    /// Theme manager publishing complete renderer requests.
    private final SwingThemeManager themeManager;

    /// Native frame applying platform transparency.
    private final AppShellFrame frame;

    /// Shell receiving decoded paint state.
    private final AppShellPanel shellPanel;

    /// Caller-owned background executor.
    private final Executor executor;

    /// User-writable network image cache directory.
    private final Path cacheDirectory;

    /// Theme-manager subscription released with this controller.
    private final Subscription appearanceSubscription;

    /// Monotonic request generation rejecting stale decode completions.
    private final AtomicLong generation = new AtomicLong();

    /// Terminal lifecycle flag shared with background callbacks.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Creates and immediately applies the current theme-manager request on the EDT.
    ///
    /// @param themeManager renderer request source
    /// @param frame native frame transparency target
    /// @param shellPanel decoded background paint target
    /// @param executor caller-owned non-EDT executor
    /// @param cacheDirectory normalized writable network cache directory
    SwingWindowBackgroundController(
            SwingThemeManager themeManager,
            AppShellFrame frame,
            AppShellPanel shellPanel,
            Executor executor,
            Path cacheDirectory) {
        EdtDispatcher.requireEventDispatchThread();
        this.themeManager = Objects.requireNonNull(themeManager, "themeManager");
        this.frame = Objects.requireNonNull(frame, "frame");
        this.shellPanel = Objects.requireNonNull(shellPanel, "shellPanel");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.cacheDirectory = Objects.requireNonNull(cacheDirectory, "cacheDirectory")
                .toAbsolutePath()
                .normalize();
        appearanceSubscription = themeManager.subscribeWindowAppearance(change ->
                SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> begin(change.currentValue())));
        begin(themeManager.windowAppearance());
    }

    /// Stops future application and detaches the theme-manager listener.
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            generation.incrementAndGet();
            appearanceSubscription.unsubscribe();
        }
    }

    /// Starts one primary load and optionally exposes the configured fallback while it runs.
    ///
    /// @param request newest complete renderer request
    private void begin(@Nullable SwingWindowAppearanceRequest request) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || request == null) {
            return;
        }
        long token = generation.incrementAndGet();
        boolean transparencyActive = frame.applyWindowTransparency(request.windowTransparent());
        shellPanel.setWindowTransparency(transparencyActive);
        Color themeSurface = themeSurfaceColor();
        AtomicBoolean primaryPublished = new AtomicBoolean();

        if (request.loadPolicy() == BackgroundLoadPolicy.SHOW_FALLBACK_WHILE_LOADING) {
            load(request.fallback(), request.networkCachePolicy(), themeSurface)
                    .whenComplete((@Nullable BackgroundLayer layer, @Nullable Throwable failure) -> {
                        if (failure == null) {
                            publishFallbackWhilePrimaryPending(
                                    token,
                                    Objects.requireNonNull(layer, "completed fallback layer"),
                                    request.opacity(),
                                    transparencyActive,
                                    primaryPublished);
                        }
                    });
        }

        load(request.source(), request.networkCachePolicy(), themeSurface)
                .whenComplete((@Nullable BackgroundLayer layer, @Nullable Throwable failure) -> {
                    if (failure == null) {
                        primaryPublished.set(true);
                        publish(
                                token,
                                Objects.requireNonNull(layer, "completed primary layer"),
                                request.opacity(),
                                transparencyActive);
                        return;
                    }
                    LOG.warning("Failed to load the selected Swing background", unwrap(failure));
                    load(request.fallback(), request.networkCachePolicy(), themeSurface)
                            .whenComplete((
                                    @Nullable BackgroundLayer fallback,
                                    @Nullable Throwable fallbackFailure) -> {
                                if (fallbackFailure == null) {
                                    publish(
                                            token,
                                            Objects.requireNonNull(fallback, "completed fallback layer"),
                                            request.opacity(),
                                            transparencyActive);
                                } else {
                                    LOG.warning("Failed to load the configured Swing background fallback",
                                            unwrap(fallbackFailure));
                                    publish(
                                            token,
                                            BackgroundLayer.fill(WindowBackgroundPaint.solid(themeSurface)),
                                            request.opacity(),
                                            transparencyActive);
                                }
                            });
                });
    }

    /// Loads one source without blocking the EDT.
    private CompletableFuture<BackgroundLayer> load(
            SwingBackgroundSource source,
            NetworkBackgroundImageCachePolicy cachePolicy,
            Color themeSurface) {
        SwingBackgroundSource checkedSource = Objects.requireNonNull(source, "source");
        if (checkedSource instanceof SwingBackgroundSource.ThemeColorFill) {
            return CompletableFuture.completedFuture(
                    BackgroundLayer.fill(WindowBackgroundPaint.solid(themeSurface)));
        }
        if (checkedSource instanceof SwingBackgroundSource.Paint paint) {
            try {
                return CompletableFuture.completedFuture(BackgroundLayer.fill(parsePaint(paint.expression())));
            } catch (IOException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return BackgroundLayer.image(
                        loadImage(checkedSource, cachePolicy),
                        WindowBackgroundPaint.solid(themeSurface));
            } catch (IOException failure) {
                throw new BackgroundLoadException(failure);
            }
        }, executor);
    }

    /// Decodes an image-producing source on a worker thread.
    private BufferedImage loadImage(
            SwingBackgroundSource source,
            NetworkBackgroundImageCachePolicy cachePolicy) throws IOException {
        if (source instanceof SwingBackgroundSource.Builtin builtin) {
            String resource = "/assets/img/wallpapers/" + builtin.background().id() + ".jpg";
            @Nullable InputStream input = SwingWindowBackgroundController.class.getResourceAsStream(resource);
            if (input == null) {
                throw new FileNotFoundException("Missing bundled wallpaper: " + resource);
            }
            try (InputStream stream = input) {
                return decode(readLimited(stream));
            }
        }
        if (source instanceof SwingBackgroundSource.ThemePackImage themeImage) {
            try (InputStream input = themeImage.resource().openStream()) {
                return decode(readLimited(input));
            }
        }
        if (source instanceof SwingBackgroundSource.Local local) {
            return loadLocal(local.path());
        }
        if (source instanceof SwingBackgroundSource.DefaultLocal) {
            return loadDefaultLocalImage();
        }
        if (source instanceof SwingBackgroundSource.Network network) {
            return loadNetwork(network.url(), cachePolicy);
        }
        throw new IOException("Background source does not produce an image: " + source);
    }

    /// Loads a local regular file or one randomly selected image from a directory.
    private static BufferedImage loadLocal(String rawPath) throws IOException {
        if (rawPath.isBlank()) {
            throw new FileNotFoundException("Local background path is blank");
        }
        Path path;
        try {
            path = Path.of(rawPath).toAbsolutePath().normalize();
        } catch (RuntimeException failure) {
            throw new IOException("Invalid local background path", failure);
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return loadDirectoryImage(path);
        }
        return loadRegularImage(path);
    }

    /// Loads one bounded non-symbolic regular image file.
    ///
    /// @param path normalized candidate path
    /// @return decoded image
    /// @throws IOException when the candidate is unavailable, oversized, or invalid
    private static BufferedImage loadRegularImage(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new FileNotFoundException("Local background is not a regular file: " + path);
        }
        long size = Files.size(path);
        if (size > MAXIMUM_IMAGE_BYTES) {
            throw new IOException("Local background exceeds the encoded size limit");
        }
        return decode(Files.readAllBytes(path));
    }

    /// Resolves the launcher-local default background search order.
    private static BufferedImage loadDefaultLocalImage() throws IOException {
        List<Path> candidates = new ArrayList<>();
        candidates.add(Metadata.XYML_LOCAL_HOME.resolve("background"));
        for (String suffix : IMAGE_SUFFIXES) {
            candidates.add(Metadata.XYML_LOCAL_HOME.resolve("background." + suffix));
        }
        candidates.add(Metadata.CURRENT_DIRECTORY.resolve("bg"));
        for (String suffix : IMAGE_SUFFIXES) {
            candidates.add(Metadata.CURRENT_DIRECTORY.resolve("background." + suffix));
        }

        @Nullable IOException lastFailure = null;
        for (Path candidate : candidates) {
            try {
                if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isSymbolicLink(candidate)) {
                    return loadDirectoryImage(candidate);
                }
                if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isSymbolicLink(candidate)) {
                    return loadRegularImage(candidate);
                }
            } catch (IOException failure) {
                if (lastFailure != null && lastFailure != failure) {
                    failure.addSuppressed(lastFailure);
                }
                lastFailure = failure;
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new FileNotFoundException("No launcher-local default background is available");
    }

    /// Tries supported non-symbolic regular files from one directory in randomized order.
    ///
    /// @param directory non-symbolic image directory
    /// @return first successfully decoded image
    /// @throws IOException when no supported candidate can be decoded
    private static BufferedImage loadDirectoryImage(Path directory) throws IOException {
        List<Path> candidates = new ArrayList<>();
        try (var files = Files.list(directory)) {
            files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(SwingWindowBackgroundController::hasImageSuffix)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .forEach(candidates::add);
        }
        if (candidates.isEmpty()) {
            throw new FileNotFoundException("Background directory contains no supported images: " + directory);
        }
        @Nullable IOException lastFailure = null;
        RandomGenerator random = RandomGenerator.getDefault();
        while (!candidates.isEmpty()) {
            Path candidate = candidates.remove(random.nextInt(candidates.size()));
            try {
                return loadRegularImage(candidate);
            } catch (IOException failure) {
                if (lastFailure != null && lastFailure != failure) {
                    failure.addSuppressed(lastFailure);
                }
                lastFailure = failure;
            }
        }
        throw Objects.requireNonNull(lastFailure, "non-empty directory produced a failure");
    }

    /// Tests one filename against the supported local image suffixes.
    private static boolean hasImageSuffix(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return IMAGE_SUFFIXES.stream().anyMatch(suffix -> name.endsWith("." + suffix));
    }

    /// Loads and optionally caches one HTTP or HTTPS image.
    private BufferedImage loadNetwork(
            String rawUrl,
            NetworkBackgroundImageCachePolicy cachePolicy) throws IOException {
        URI uri;
        try {
            uri = URI.create(rawUrl);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid network background URL", failure);
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IOException("Network background URL must use HTTP or HTTPS");
        }
        Path cacheFile = cacheDirectory.resolve(sha256(uri.toASCIIString()) + ".image");
        if (cachePolicy == NetworkBackgroundImageCachePolicy.ENABLED
                && Files.isRegularFile(cacheFile, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(cacheFile)) {
            try {
                if (Files.size(cacheFile) > MAXIMUM_IMAGE_BYTES) {
                    throw new IOException("Cached background exceeds the encoded size limit");
                }
                return decode(Files.readAllBytes(cacheFile));
            } catch (IOException cachedFailure) {
                LOG.warning("Ignoring an invalid cached Swing background", cachedFailure);
            }
        }

        URLConnection opened = NetworkUtils.createConnection(uri);
        URLConnection connection = opened instanceof HttpURLConnection http
                ? NetworkUtils.resolveConnection(http)
                : opened;
        try {
            long contentLength = connection.getContentLengthLong();
            if (contentLength > MAXIMUM_IMAGE_BYTES) {
                throw new IOException("Network background exceeds the encoded size limit");
            }
            byte @Unmodifiable [] bytes;
            try (InputStream input = connection.getInputStream()) {
                bytes = readLimited(input);
            }
            BufferedImage image = decode(bytes);
            if (cachePolicy == NetworkBackgroundImageCachePolicy.ENABLED) {
                writeCache(cacheFile, bytes);
            }
            return image;
        } finally {
            if (connection instanceof HttpURLConnection http) {
                http.disconnect();
            }
        }
    }

    /// Writes one validated network response through a same-directory temporary file.
    private static void writeCache(Path target, byte @Unmodifiable [] bytes) throws IOException {
        Path directory = Objects.requireNonNull(target.getParent(), "cache parent");
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "background-", ".tmp");
        boolean moved = false;
        try {
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    /// Reads one encoded image without allowing an unbounded response body.
    private static byte @Unmodifiable [] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        long total = 0L;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read == 0) {
                continue;
            }
            total += read;
            if (total > MAXIMUM_IMAGE_BYTES) {
                throw new IOException("Background exceeds the encoded size limit");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    /// Decodes one bounded image after checking dimensions without allocating the full raster first.
    private static BufferedImage decode(byte @Unmodifiable [] bytes) throws IOException {
        if (bytes.length == 0) {
            throw new IOException("Background image is empty");
        }
        try (@Nullable ImageInputStream imageInput = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (imageInput == null) {
                throw new IOException("Background image input is unsupported");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                throw new IOException("Background image format is unsupported");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAXIMUM_IMAGE_PIXELS) {
                    throw new IOException("Background image dimensions exceed the limit");
                }
                @Nullable BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new IOException("Background image could not be decoded");
                }
                return image;
            } finally {
                reader.dispose();
            }
        }
    }

    /// Parses a complete persisted background paint without requiring JavaFX at runtime.
    ///
    /// @param expression solid color or JavaFX-compatible gradient expression
    /// @return immutable renderer paint
    /// @throws IOException when the expression is unsupported or malformed
    static WindowBackgroundPaint parsePaint(String expression) throws IOException {
        return WindowBackgroundPaintParser.parse(expression);
    }

    /// Parses common JavaFX/CSS-compatible solid-color forms used by launcher settings and theme packs.
    ///
    /// @param expression solid color expression
    /// @return parsed AWT color
    /// @throws IOException when the expression is a gradient or malformed color
    static Color parseColor(String expression) throws IOException {
        return WindowBackgroundPaintParser.parseColor(expression);
    }

    /// Computes one stable lowercase SHA-256 cache key.
    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    /// Publishes one current decoded layer on the EDT without an additional eligibility condition.
    ///
    /// @param token request generation
    /// @param layer decoded layer
    /// @param opacity requested layer opacity
    /// @param transparencyActive native transparency state
    private void publish(
            long token,
            BackgroundLayer layer,
            double opacity,
            boolean transparencyActive) {
        publishWhen(token, layer, opacity, transparencyActive, () -> true);
    }

    /// Publishes a loading fallback only if the primary is still pending when the EDT executes the publication.
    ///
    /// @param token request generation
    /// @param layer decoded fallback layer
    /// @param opacity requested layer opacity
    /// @param transparencyActive native transparency state
    /// @param primaryPublished flag set before the successful primary is queued
    private void publishFallbackWhilePrimaryPending(
            long token,
            BackgroundLayer layer,
            double opacity,
            boolean transparencyActive,
            AtomicBoolean primaryPublished) {
        AtomicBoolean checkedPrimary = Objects.requireNonNull(primaryPublished, "primaryPublished");
        publishWhen(token, layer, opacity, transparencyActive, () -> !checkedPrimary.get());
    }

    /// Applies one decoded layer on the EDT when both generation and caller eligibility remain current.
    ///
    /// @param token request generation
    /// @param layer decoded layer
    /// @param opacity requested layer opacity
    /// @param transparencyActive native transparency state
    /// @param eligibility condition evaluated inside the EDT publication point
    private void publishWhen(
            long token,
            BackgroundLayer layer,
            double opacity,
            boolean transparencyActive,
            BooleanSupplier eligibility) {
        BackgroundLayer checkedLayer = Objects.requireNonNull(layer, "layer");
        BooleanSupplier checkedEligibility = Objects.requireNonNull(eligibility, "eligibility");
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (!closed.get() && generation.get() == token) {
                runWhenEligible(checkedEligibility, () ->
                        shellPanel.setWindowBackground(new WindowBackgroundVisual(
                                checkedLayer.image(),
                                checkedLayer.fill(),
                                opacity,
                                transparencyActive)));
            }
        });
    }

    /// Executes one deferred publication only when its current eligibility still holds.
    ///
    /// @param eligibility condition evaluated immediately before publication
    /// @param publication publication side effect
    static void runWhenEligible(BooleanSupplier eligibility, Runnable publication) {
        BooleanSupplier checkedEligibility = Objects.requireNonNull(eligibility, "eligibility");
        Runnable checkedPublication = Objects.requireNonNull(publication, "publication");
        if (checkedEligibility.getAsBoolean()) {
            checkedPublication.run();
        }
    }

    /// Returns the active FlatLaf surface color with a stable light fallback.
    private static Color themeSurfaceColor() {
        @Nullable Color color = UIManager.getColor("Panel.background");
        return color != null ? color : new Color(0xF4F4F6);
    }

    /// Removes asynchronous wrapper exceptions for diagnostic logging.
    private static Throwable unwrap(Throwable failure) {
        Throwable current = Objects.requireNonNull(failure, "failure");
        while (current.getCause() != null
                && (current instanceof java.util.concurrent.CompletionException
                || current instanceof BackgroundLoadException)) {
            current = Objects.requireNonNull(current.getCause(), "checked asynchronous cause");
        }
        return current;
    }

    /// One decoded image or bounds-aware paint before request opacity and native transparency are applied.
    ///
    /// @param image decoded image, or `null` for a paint-backed layer
    /// @param fill stable paint beneath this layer
    @NotNullByDefault
    private record BackgroundLayer(@Nullable BufferedImage image, WindowBackgroundPaint fill) {
        /// Validates one decoded layer.
        private BackgroundLayer {
            Objects.requireNonNull(fill, "fill");
        }

        /// Creates one image-backed layer.
        private static BackgroundLayer image(BufferedImage image, WindowBackgroundPaint fill) {
            return new BackgroundLayer(Objects.requireNonNull(image, "image"), fill);
        }

        /// Creates one paint-backed layer.
        private static BackgroundLayer fill(WindowBackgroundPaint fill) {
            return new BackgroundLayer(null, fill);
        }
    }

    /// Unchecked boundary used only to carry an I/O failure through `CompletableFuture`.
    @NotNullByDefault
    private static final class BackgroundLoadException extends RuntimeException {
        /// Creates one asynchronous load wrapper.
        ///
        /// @param cause exact I/O failure
        private BackgroundLoadException(IOException cause) {
            super(Objects.requireNonNull(cause, "cause"));
        }
    }
}
