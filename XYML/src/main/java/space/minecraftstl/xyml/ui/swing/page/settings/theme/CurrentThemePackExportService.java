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
package space.minecraftstl.xyml.ui.swing.page.settings.theme;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.Metadata;
import space.minecraftstl.xyml.theme.Theme;
import space.minecraftstl.xyml.theme.ThemeAppearance;
import space.minecraftstl.xyml.theme.ThemeBackground;
import space.minecraftstl.xyml.theme.ThemeBackgroundSettings;
import space.minecraftstl.xyml.theme.ThemeColorSource;
import space.minecraftstl.xyml.theme.ThemePackArchiveLimits;
import space.minecraftstl.xyml.theme.ThemePackAsset;
import space.minecraftstl.xyml.theme.ThemePackAuthor;
import space.minecraftstl.xyml.theme.ThemePackExporter;
import space.minecraftstl.xyml.theme.ThemePackManifest;
import space.minecraftstl.xyml.theme.ThemePackResource;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingBackgroundSource;
import space.minecraftstl.xyml.ui.swing.SwingWindowAppearanceRequest;
import space.minecraftstl.xyml.util.i18n.LocalizedText;
import space.minecraftstl.xyml.util.io.NetworkUtils;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.stream.Stream;

/// Captures the rendered Swing appearance and exports it through the bounded theme-pack archive writer.
///
/// All filesystem enumeration, local asset access, and network image retrieval run on the caller-owned worker.
/// The EDT only captures immutable appearance and user-confirmed metadata.
@NotNullByDefault
public final class CurrentThemePackExportService {
    /// Version suggested for newly exported theme packages.
    public static final String CURRENT_THEME_PACK_VERSION = "1.0.0";

    /// Time format used by default exported theme names.
    private static final DateTimeFormatter EXPORTED_THEME_NAME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    /// Supported image suffixes used when a configured background points to a directory.
    private static final @Unmodifiable List<String> IMAGE_SUFFIXES =
            List.of("png", "jpg", "jpeg", "gif", "webp");

    /// Immutable renderer-ready appearance source captured on the EDT.
    private final Supplier<CurrentThemePackAppearance> appearanceSupplier;

    /// Current selected-account profile name, or `null` when no account is selected.
    private final Supplier<@Nullable String> selectedAccountNameSupplier;

    /// Current operating-system user name, or `null` when unavailable.
    private final Supplier<@Nullable String> systemUserNameSupplier;

    /// Localized fallback used only when both preferred author sources are blank.
    private final Supplier<String> unknownAuthorSupplier;

    /// Caller-owned executor for all blocking preparation and archive work.
    private final Executor executor;

    /// Clock used for a consistent generated package name.
    private final Clock clock;

    /// Generates a new package identifier for every opened export dialog.
    private final Supplier<String> packIdSupplier;

    /// Creates a production current-theme exporter.
    ///
    /// @param appearanceSupplier current concrete Swing appearance supplier
    /// @param selectedAccountNameSupplier current selected-account name supplier
    /// @param unknownAuthorSupplier localized unknown-author fallback supplier
    /// @param executor caller-owned non-EDT executor
    public CurrentThemePackExportService(
            Supplier<CurrentThemePackAppearance> appearanceSupplier,
            Supplier<@Nullable String> selectedAccountNameSupplier,
            Supplier<String> unknownAuthorSupplier,
            Executor executor) {
        this(
                appearanceSupplier,
                selectedAccountNameSupplier,
                () -> System.getProperty("user.name"),
                unknownAuthorSupplier,
                executor,
                Clock.systemDefaultZone(),
                CurrentThemePackExportService::newPackId);
    }

    /// Creates a deterministic exporter for focused tests.
    ///
    /// @param appearanceSupplier current concrete Swing appearance supplier
    /// @param selectedAccountNameSupplier selected-account name supplier
    /// @param systemUserNameSupplier system-user name supplier
    /// @param unknownAuthorSupplier unknown-author fallback supplier
    /// @param executor caller-owned non-EDT executor
    /// @param clock timestamp source
    /// @param packIdSupplier generated package ID source
    CurrentThemePackExportService(
            Supplier<CurrentThemePackAppearance> appearanceSupplier,
            Supplier<@Nullable String> selectedAccountNameSupplier,
            Supplier<@Nullable String> systemUserNameSupplier,
            Supplier<String> unknownAuthorSupplier,
            Executor executor,
            Clock clock,
            Supplier<String> packIdSupplier) {
        this.appearanceSupplier = Objects.requireNonNull(appearanceSupplier, "appearanceSupplier");
        this.selectedAccountNameSupplier = Objects.requireNonNull(
                selectedAccountNameSupplier,
                "selectedAccountNameSupplier");
        this.systemUserNameSupplier = Objects.requireNonNull(systemUserNameSupplier, "systemUserNameSupplier");
        this.unknownAuthorSupplier = Objects.requireNonNull(unknownAuthorSupplier, "unknownAuthorSupplier");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.packIdSupplier = Objects.requireNonNull(packIdSupplier, "packIdSupplier");
    }

    /// Creates fresh user-facing defaults on the EDT immediately before opening the metadata dialog.
    ///
    /// @return generated ID plus prefilled name, version, and author
    public ThemePackExportDefaults defaults() {
        EdtDispatcher.requireEventDispatchThread();
        Instant now = clock.instant();
        String defaultName = LocalDateTime.ofInstant(now, clock.getZone())
                .format(EXPORTED_THEME_NAME_FORMATTER);
        return new ThemePackExportDefaults(
                packIdSupplier.get(),
                defaultName,
                CURRENT_THEME_PACK_VERSION,
                defaultAuthor());
    }

    /// Captures the current appearance on the EDT and schedules complete export preparation and archive I/O.
    ///
    /// @param request user-confirmed metadata and output path
    /// @return stage containing the atomically published archive
    public CompletionStage<Path> export(ThemePackExportRequest request) {
        EdtDispatcher.requireEventDispatchThread();
        ThemePackExportRequest checkedRequest = Objects.requireNonNull(request, "request");
        CurrentThemePackAppearance appearance = Objects.requireNonNull(
                appearanceSupplier.get(),
                "appearance supplier returned null");
        CompletableFuture<PreparedExport> preparation = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    if (javax.swing.SwingUtilities.isEventDispatchThread()) {
                        throw new IllegalStateException("Theme-pack preparation must not run on the EDT");
                    }
                    preparation.complete(prepare(checkedRequest, appearance));
                } catch (Throwable failure) {
                    preparation.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException failure) {
            preparation.completeExceptionally(failure);
        }
        return preparation.thenCompose(prepared -> ThemePackExporter.export(
                prepared.manifest(),
                prepared.assets(),
                checkedRequest.outputFile(),
                executor));
    }

    /// Builds one simple-theme manifest plus any portable background image asset on a worker thread.
    ///
    /// @param request confirmed export metadata
    /// @param current renderer-ready appearance captured on the EDT
    /// @return prepared manifest and asset list
    /// @throws IOException when a selected file, directory, or network image is unavailable
    private static PreparedExport prepare(
            ThemePackExportRequest request,
            CurrentThemePackAppearance current) throws IOException {
        PreparedBackground background = prepareBackground(current.window());
        ThemeAppearance appearance = new ThemeAppearance(
                ThemeColorSource.custom(current.theme().primaryColorSeed()),
                current.theme().brightness(),
                current.theme().colorStyle(),
                current.theme().contrast(),
                new ThemeBackgroundSettings(background.source(), current.window().opacity()),
                null);
        Theme theme = new Theme(null, null, List.of(), null, null, appearance, List.of());
        ThemePackManifest manifest = new ThemePackManifest(
                request.packId(),
                request.version(),
                LocalizedText.plain(request.name()),
                List.of(new ThemePackAuthor(LocalizedText.plain(request.author()))),
                null,
                null,
                List.of(theme));
        return new PreparedExport(manifest, background.assets());
    }

    /// Converts the current renderer source into a portable theme background and optional archive asset.
    ///
    /// @param request current renderer-ready window request
    /// @return manifest source and required asset list
    /// @throws IOException when the active source cannot be captured
    private static PreparedBackground prepareBackground(SwingWindowAppearanceRequest request) throws IOException {
        SwingBackgroundSource source = Objects.requireNonNull(request, "request").source();
        if (source instanceof SwingBackgroundSource.Builtin builtin) {
            return withoutAsset(new ThemeBackground.Builtin(builtin.background().id()));
        }
        if (source instanceof SwingBackgroundSource.ThemePackImage themeImage) {
            return withAsset(themeImage.resource());
        }
        if (source instanceof SwingBackgroundSource.Local local) {
            return withAsset(new ThemePackResource.File(resolveImagePath(local.path())));
        }
        if (source instanceof SwingBackgroundSource.Network network) {
            ThemePackResource resource = new ThemePackResource.Bytes(
                    downloadNetworkImage(network.url()),
                    network.url());
            return withAsset(resource);
        }
        if (source instanceof SwingBackgroundSource.Paint paint) {
            return paint.expression().isBlank()
                    ? withoutAsset(new ThemeBackground.Default())
                    : withoutAsset(new ThemeBackground.Paint(paint.expression()));
        }
        if (source instanceof SwingBackgroundSource.ThemeColorFill) {
            return withoutAsset(new ThemeBackground.ThemeColorFill());
        }
        if (source instanceof SwingBackgroundSource.DefaultLocal) {
            @Nullable Path localDefault = findDefaultLocalImage();
            return localDefault == null
                    ? withoutAsset(new ThemeBackground.Default())
                    : withAsset(new ThemePackResource.File(localDefault));
        }
        throw new IOException("Unsupported current background source: " + source.getClass().getName());
    }

    /// Creates a background representation with no copied image asset.
    ///
    /// @param source portable non-image source
    /// @return prepared background
    private static PreparedBackground withoutAsset(ThemeBackground source) {
        return new PreparedBackground(source, List.of());
    }

    /// Creates a normalized image entry backed by one reopenable source.
    ///
    /// @param resource source copied by the bounded archive exporter
    /// @return prepared image background
    private static PreparedBackground withAsset(ThemePackResource resource) {
        String entryName = "assets/background" + imageSuffix(resource.name());
        return new PreparedBackground(
                new ThemeBackground.Image(entryName),
                List.of(new ThemePackAsset(resource, entryName)));
    }

    /// Resolves a configured local file or selects the first portable image from a configured directory.
    ///
    /// @param rawPath user-configured path
    /// @return non-symbolic regular image path
    /// @throws IOException when the path is invalid or contains no supported image
    private static Path resolveImagePath(String rawPath) throws IOException {
        if (rawPath.isBlank()) {
            throw new FileNotFoundException("Current local background path is blank");
        }
        final Path path;
        try {
            path = Path.of(rawPath).toAbsolutePath().normalize();
        } catch (InvalidPathException | SecurityException failure) {
            throw new IOException("Current local background path is invalid", failure);
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            @Nullable Path selected = firstDirectoryImage(path);
            if (selected != null) {
                return selected;
            }
            throw new FileNotFoundException("Current local background directory contains no supported image: " + path);
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new FileNotFoundException("Current local background is not a regular file: " + path);
        }
        return path;
    }

    /// Searches launcher-local default locations in renderer priority order.
    ///
    /// @return first exportable default image, or `null` when the renderer will use its fallback
    /// @throws IOException when directory enumeration fails
    private static @Nullable Path findDefaultLocalImage() throws IOException {
        List<Path> candidates = new ArrayList<>();
        candidates.add(Metadata.XYML_LOCAL_HOME.resolve("background"));
        for (String suffix : IMAGE_SUFFIXES) {
            candidates.add(Metadata.XYML_LOCAL_HOME.resolve("background." + suffix));
        }
        candidates.add(Metadata.CURRENT_DIRECTORY.resolve("bg"));
        for (String suffix : IMAGE_SUFFIXES) {
            candidates.add(Metadata.CURRENT_DIRECTORY.resolve("background." + suffix));
        }
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(candidate)) {
                @Nullable Path selected = firstDirectoryImage(candidate);
                if (selected != null) {
                    return selected;
                }
            } else if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    /// Selects the lexicographically first supported non-symbolic image from a directory.
    ///
    /// Deterministic selection makes repeated exports stable even though the live renderer may rotate directory images.
    ///
    /// @param directory direct directory to enumerate
    /// @return selected image or `null`
    /// @throws IOException when the directory cannot be read
    private static @Nullable Path firstDirectoryImage(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(CurrentThemePackExportService::hasImageSuffix)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .map(path -> path.toAbsolutePath().normalize())
                    .findFirst()
                    .orElse(null);
        }
    }

    /// Tests one path against supported renderer image suffixes.
    ///
    /// @param path candidate file
    /// @return whether the filename has a supported suffix
    private static boolean hasImageSuffix(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return IMAGE_SUFFIXES.stream().anyMatch(suffix -> name.endsWith("." + suffix));
    }

    /// Chooses a safe archive suffix from a source name without trusting arbitrary path text.
    ///
    /// @param sourceName diagnostic resource name
    /// @return supported image suffix including the leading dot, or `.image`
    private static String imageSuffix(String sourceName) {
        String lowerName = Objects.requireNonNull(sourceName, "sourceName").toLowerCase(Locale.ROOT);
        for (String suffix : IMAGE_SUFFIXES) {
            if (lowerName.endsWith("." + suffix)) {
                return "." + suffix;
            }
        }
        return ".image";
    }

    /// Downloads one bounded HTTP or HTTPS image on the export worker.
    ///
    /// The archive exporter subsequently validates the encoded image and decoded dimensions before publication.
    ///
    /// @param rawUrl configured network background URL
    /// @return bounded response bytes
    /// @throws IOException when the URL, response, or byte ceiling is invalid
    private static byte @Unmodifiable [] downloadNetworkImage(String rawUrl) throws IOException {
        final URI uri;
        try {
            uri = URI.create(rawUrl);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Current network background URL is invalid", failure);
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IOException("Current network background URL must use HTTP or HTTPS");
        }
        long maximumBytes = ThemePackArchiveLimits.launcherDefaults().maximumSingleAssetBytes();
        URLConnection opened = NetworkUtils.createConnection(uri);
        URLConnection connection = opened instanceof HttpURLConnection http
                ? NetworkUtils.resolveConnection(http)
                : opened;
        try {
            long contentLength = connection.getContentLengthLong();
            if (contentLength > maximumBytes) {
                throw new IOException("Current network background exceeds the theme-pack asset limit");
            }
            try (InputStream input = connection.getInputStream()) {
                return readBounded(input, maximumBytes);
            }
        } finally {
            if (connection instanceof HttpURLConnection http) {
                http.disconnect();
            }
        }
    }

    /// Reads one stream without allowing an unbounded network response body.
    ///
    /// @param input response stream
    /// @param maximumBytes inclusive byte ceiling
    /// @return complete bounded bytes
    /// @throws IOException when the response exceeds the ceiling
    private static byte @Unmodifiable [] readBounded(InputStream input, long maximumBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        long total = 0L;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read == 0) {
                continue;
            }
            total += read;
            if (total > maximumBytes) {
                throw new IOException("Current network background exceeds the theme-pack asset limit");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    /// Resolves the preferred account, operating-system user, and localized fallback author order.
    ///
    /// @return non-empty default author
    private String defaultAuthor() {
        @Nullable String selectedAccount = normalizedOrNull(selectedAccountNameSupplier.get());
        if (selectedAccount != null) {
            return selectedAccount;
        }
        @Nullable String systemUser = normalizedOrNull(systemUserNameSupplier.get());
        if (systemUser != null) {
            return systemUser;
        }
        String fallback = Objects.requireNonNull(unknownAuthorSupplier.get(), "unknown author supplier returned null")
                .trim();
        if (fallback.isEmpty()) {
            throw new IllegalStateException("Unknown-author fallback must not be blank");
        }
        return fallback;
    }

    /// Normalizes one optional author candidate.
    ///
    /// @param value candidate value
    /// @return trimmed value or `null` when blank
    private static @Nullable String normalizedOrNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    /// Generates one XYML-namespaced portable package identifier.
    ///
    /// @return new package ID
    private static String newPackId() {
        return "space.minecraftstl.xyml.theme-pack."
                + UUID.randomUUID().toString().replace("-", "");
    }

    /// Manifest source and copied assets for one current background.
    ///
    /// @param source portable manifest background source
    /// @param assets immutable required archive assets
    @NotNullByDefault
    private record PreparedBackground(
            ThemeBackground source,
            @Unmodifiable List<ThemePackAsset> assets) {
        /// Validates and copies the prepared background.
        private PreparedBackground {
            Objects.requireNonNull(source, "source");
            assets = List.copyOf(assets);
        }
    }

    /// Complete immutable input handed to the bounded archive exporter.
    ///
    /// @param manifest generated simple-theme manifest
    /// @param assets immutable required archive assets
    @NotNullByDefault
    private record PreparedExport(
            ThemePackManifest manifest,
            @Unmodifiable List<ThemePackAsset> assets) {
        /// Validates and copies the prepared export.
        private PreparedExport {
            Objects.requireNonNull(manifest, "manifest");
            assets = List.copyOf(assets);
        }
    }
}
