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
package space.minecraftstl.xyml.ui.swing;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.Metadata;
import space.minecraftstl.xyml.util.i18n.I18n;
import space.minecraftstl.xyml.util.i18n.LocaleUtils;
import space.minecraftstl.xyml.util.io.JarUtils;
import space.minecraftstl.xyml.util.platform.OperatingSystem;
import space.minecraftstl.xyml.util.platform.SystemUtils;

import javax.swing.SwingUtilities;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.GraphicsEnvironment;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Restores the launcher's offline font discovery, registration, and fallback precedence for Swing.
///
/// Startup initialization performs file and `fc-match` access before any Swing window exists. Later theme updates only
/// read the cached family, so resetting the launcher font never performs blocking I/O on the event dispatch thread.
@NotNullByDefault
public final class SwingLauncherFontManager {
    /// Supported local font extensions in legacy precedence order.
    private static final @Unmodifiable List<String> FONT_EXTENSIONS = List.of("ttf", "otf", "woff");

    /// Serializes one-time fallback discovery.
    private static final Object INITIALIZATION_LOCK = new Object();

    /// Family loaded from local files or Linux fontconfig, or `null` for the look-and-feel default.
    private static volatile @Nullable String fallbackFontFamily;

    /// Whether fallback discovery has completed for this process.
    private static volatile boolean initialized;

    /// Prevents construction of the process-wide font manager.
    private SwingLauncherFontManager() {
    }

    /// Loads and registers the complete offline fallback chain before Swing initializes.
    ///
    /// Calling this method repeatedly is harmless. It must not run on the Swing event dispatch thread because local
    /// font files and `fc-match` can perform blocking I/O.
    public static void initialize() {
        if (initialized) {
            return;
        }
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Launcher fallback font discovery must not run on the EDT");
        }
        synchronized (INITIALIZATION_LOCK) {
            if (initialized) {
                return;
            }
            String fontMatchPattern = OperatingSystem.CURRENT_OS.isLinuxOrBSD()
                    ? I18n.getLocale().getFcMatchPattern()
                    : "";
            fallbackFontFamily = discoverFallback(
                    FontDiscoveryPaths.current(),
                    I18n.getLocale().getCandidateLocales(),
                    SwingLauncherFontManager::loadAndRegisterFont,
                    fontMatchPattern,
                    SwingLauncherFontManager::findByFcMatch);
            initialized = true;
            LOG.info("Launcher fallback font: "
                    + (fallbackFontFamily == null ? "Look and feel default" : fallbackFontFamily));
        }
    }

    /// Resolves the launcher family without performing file or process I/O.
    ///
    /// Explicit persisted settings take precedence over process overrides. When neither is present, the cached local
    /// fallback discovered during [#initialize()] is returned.
    ///
    /// @param configuredFamily persisted launcher family, or `null` for automatic fallback
    /// @return effective family, or `null` for the active look-and-feel default
    public static @Nullable String effectiveLauncherFontFamily(@Nullable String configuredFamily) {
        @Nullable String configured = normalizeFamily(configuredFamily);
        if (configured != null) {
            return configured;
        }
        @Nullable String propertyOverride = normalizeFamily(System.getProperty("xyml.font.override"));
        if (propertyOverride != null) {
            return propertyOverride;
        }
        @Nullable String environmentOverride = normalizeFamily(System.getenv("XYML_FONT"));
        return environmentOverride != null ? environmentOverride : fallbackFontFamily;
    }

    /// Discovers the first usable offline fallback in the established launcher order.
    ///
    /// @param paths launcher-local, process-local, user, and optional JAR directories
    /// @param candidateLocales current locale fallback order
    /// @param fontLoader local font registration boundary
    /// @param fontMatchPattern Linux fontconfig match pattern, or blank to disable fontconfig fallback
    /// @param fontMatchResolver Linux fontconfig resolution boundary
    /// @return registered family, fontconfig family, or `null`
    static @Nullable String discoverFallback(
            FontDiscoveryPaths paths,
            @Unmodifiable List<Locale> candidateLocales,
            FontFileLoader fontLoader,
            String fontMatchPattern,
            FontMatchResolver fontMatchResolver) {
        FontDiscoveryPaths validatedPaths = Objects.requireNonNull(paths, "paths");
        @Unmodifiable List<Locale> locales = List.copyOf(
                Objects.requireNonNull(candidateLocales, "candidateLocales"));
        FontFileLoader validatedLoader = Objects.requireNonNull(fontLoader, "fontLoader");
        FontMatchResolver validatedResolver = Objects.requireNonNull(fontMatchResolver, "fontMatchResolver");

        @Nullable String family = tryLoadLocalizedFont(
                validatedPaths.localHome().resolve("font"),
                locales,
                validatedLoader);
        if (family != null) {
            return family;
        }
        family = tryLoadLocalizedFont(
                validatedPaths.userHome().resolve("font"),
                locales,
                validatedLoader);
        if (family != null) {
            return family;
        }

        LinkedHashSet<Path> legacyDirectories = new LinkedHashSet<>();
        legacyDirectories.add(validatedPaths.localHome());
        legacyDirectories.add(validatedPaths.currentDirectory());
        legacyDirectories.add(validatedPaths.userHome());
        @Nullable Path jarDirectory = validatedPaths.jarDirectory();
        if (jarDirectory != null) {
            legacyDirectories.add(jarDirectory);
        }
        for (Path directory : legacyDirectories) {
            family = tryLoadDefaultFont(directory, validatedLoader);
            if (family != null) {
                return family;
            }
        }

        String normalizedPattern = Objects.requireNonNull(fontMatchPattern, "fontMatchPattern").trim();
        return normalizedPattern.isEmpty() ? null : validatedResolver.resolve(normalizedPattern);
    }

    /// Attempts localized `font[_locale].ext` files in locale and extension precedence order.
    ///
    /// @param directory localized font directory
    /// @param candidateLocales current locale fallback order
    /// @param fontLoader local font registration boundary
    /// @return first registered family, or `null`
    private static @Nullable String tryLoadLocalizedFont(
            Path directory,
            @Unmodifiable List<Locale> candidateLocales,
            FontFileLoader fontLoader) {
        Map<String, Map<String, Path>> localizedFiles = LocaleUtils.findAllLocalizedFiles(
                directory,
                "font",
                FONT_EXTENSIONS);
        if (localizedFiles.isEmpty()) {
            return null;
        }
        for (Locale locale : candidateLocales) {
            @Nullable Map<String, Path> byExtension = localizedFiles.get(LocaleUtils.toLanguageKey(locale));
            if (byExtension == null) {
                continue;
            }
            for (String extension : FONT_EXTENSIONS) {
                @Nullable Path file = byExtension.get(extension);
                if (file != null) {
                    @Nullable String family = tryLoadFirstFamily(file, fontLoader);
                    if (family != null) {
                        return family;
                    }
                }
            }
        }
        return null;
    }

    /// Attempts legacy `font.ext` files in extension precedence order.
    ///
    /// @param directory directory containing an optional legacy font file
    /// @param fontLoader local font registration boundary
    /// @return first registered family, or `null`
    private static @Nullable String tryLoadDefaultFont(Path directory, FontFileLoader fontLoader) {
        for (String extension : FONT_EXTENSIONS) {
            Path file = directory.resolve("font." + extension);
            if (java.nio.file.Files.isRegularFile(file)) {
                @Nullable String family = tryLoadFirstFamily(file, fontLoader);
                if (family != null) {
                    return family;
                }
            }
        }
        return null;
    }

    /// Loads one file and returns its first non-blank family.
    ///
    /// @param file local font file
    /// @param fontLoader local font registration boundary
    /// @return first family, or `null` when the file cannot be used
    private static @Nullable String tryLoadFirstFamily(Path file, FontFileLoader fontLoader) {
        @Unmodifiable List<String> families = tryLoadFamilies(file, fontLoader);
        return families.isEmpty() ? null : families.get(0);
    }

    /// Loads one file while converting format and registration failures into a recoverable empty result.
    ///
    /// @param file local font file
    /// @param fontLoader local font registration boundary
    /// @return immutable registered family list
    private static @Unmodifiable List<String> tryLoadFamilies(Path file, FontFileLoader fontLoader) {
        Path normalizedFile = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        LOG.info("Load font file: " + normalizedFile);
        try {
            return List.copyOf(fontLoader.load(normalizedFile));
        } catch (FontFormatException | IOException | RuntimeException failure) {
            LOG.warning("Failed to load font " + normalizedFile, failure);
            return List.of();
        }
    }

    /// Loads a TTF, OTF, TTC, OTC, or WOFF 1 file and registers every contained AWT font.
    ///
    /// @param file local font file
    /// @return immutable distinct family names in file order
    /// @throws IOException when the file cannot be read or decoded
    /// @throws FontFormatException when AWT rejects the decoded font data
    private static @Unmodifiable List<String> loadAndRegisterFont(Path file)
            throws IOException, FontFormatException {
        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        Font @Unmodifiable [] fonts;
        if (fileName.endsWith(".woff")) {
            byte @Unmodifiable [] decoded = WoffFontDecoder.decode(file);
            try (ByteArrayInputStream input = new ByteArrayInputStream(decoded)) {
                fonts = Font.createFonts(input);
            }
        } else {
            fonts = Font.createFonts(file.toFile());
        }

        Set<String> families = new LinkedHashSet<>();
        GraphicsEnvironment graphicsEnvironment = GraphicsEnvironment.getLocalGraphicsEnvironment();
        for (Font font : fonts) {
            graphicsEnvironment.registerFont(font);
            @Nullable String family = normalizeFamily(font.getFamily(Locale.ROOT));
            if (family != null) {
                families.add(family);
            }
        }
        return List.copyOf(families);
    }

    /// Runs local fontconfig and loads the exact font file selected for the current locale.
    ///
    /// @param pattern locale-specific fontconfig match expression
    /// @return matched registered family, or `null`
    private static @Nullable String findByFcMatch(String pattern) {
        @Nullable Path fcMatch = SystemUtils.which("fc-match");
        if (fcMatch == null) {
            return null;
        }
        try {
            String output = SystemUtils.run(
                    fcMatch.toString(),
                    Objects.requireNonNull(pattern, "pattern"),
                    "--format",
                    "%{family}\\n%{file}");
            return selectFcMatchResult(output, SwingLauncherFontManager::loadAndRegisterFont);
        } catch (Exception failure) {
            LOG.warning("Failed to get default font with fc-match", failure);
            return null;
        }
    }

    /// Selects the fontconfig-reported family from the families contained in its returned file.
    ///
    /// @param output two-line `fc-match` family and file output
    /// @param fontLoader local font registration boundary
    /// @return matched family, first loaded family as a fallback, or `null`
    static @Nullable String selectFcMatchResult(String output, FontFileLoader fontLoader) {
        String normalizedOutput = Objects.requireNonNull(output, "output").trim();
        String @Unmodifiable [] lines = normalizedOutput.split("\\R", 3);
        if (lines.length != 2 || lines[0].isBlank() || lines[1].isBlank()) {
            LOG.warning("Unexpected output from fc-match: " + normalizedOutput);
            return null;
        }
        final Path file;
        try {
            file = Path.of(lines[1].trim()).toAbsolutePath().normalize();
        } catch (InvalidPathException failure) {
            LOG.warning("Invalid font path from fc-match: " + lines[1], failure);
            return null;
        }
        @Unmodifiable List<String> loadedFamilies = tryLoadFamilies(file, fontLoader);
        if (loadedFamilies.isEmpty()) {
            return null;
        }
        String @Unmodifiable [] candidates = lines[0].split(",");
        for (String candidate : candidates) {
            String normalizedCandidate = candidate.trim();
            for (String loadedFamily : loadedFamilies) {
                if (loadedFamily.equalsIgnoreCase(normalizedCandidate)) {
                    return loadedFamily;
                }
            }
        }
        LOG.warning("Font family '" + lines[0] + "' was not found in " + file);
        return loadedFamilies.get(0);
    }

    /// Normalizes blank family values to the automatic fallback representation.
    ///
    /// @param family configured family, or `null`
    /// @return trimmed family, or `null`
    private static @Nullable String normalizeFamily(@Nullable String family) {
        if (family == null) {
            return null;
        }
        String normalized = family.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    /// Filesystem roots participating in offline launcher-font discovery.
    ///
    /// @param localHome launcher-local data root
    /// @param currentDirectory process working directory
    /// @param userHome user-level launcher data root
    /// @param jarDirectory directory containing the launcher JAR, or `null`
    @NotNullByDefault
    record FontDiscoveryPaths(
            Path localHome,
            Path currentDirectory,
            Path userHome,
            @Nullable Path jarDirectory) {
        /// Normalizes every supplied path.
        FontDiscoveryPaths {
            localHome = Objects.requireNonNull(localHome, "localHome").toAbsolutePath().normalize();
            currentDirectory = Objects.requireNonNull(
                    currentDirectory,
                    "currentDirectory").toAbsolutePath().normalize();
            userHome = Objects.requireNonNull(userHome, "userHome").toAbsolutePath().normalize();
            if (jarDirectory != null) {
                jarDirectory = jarDirectory.toAbsolutePath().normalize();
            }
        }

        /// Creates discovery roots from the active launcher process.
        ///
        /// @return current process discovery roots
        static FontDiscoveryPaths current() {
            @Nullable Path launcherJar = JarUtils.thisJarPath();
            @Nullable Path jarDirectory = launcherJar == null ? null : launcherJar.getParent();
            return new FontDiscoveryPaths(
                    Metadata.XYML_LOCAL_HOME,
                    Metadata.CURRENT_DIRECTORY,
                    Metadata.XYML_USER_HOME,
                    jarDirectory);
        }
    }

    /// Loads and registers one local font file.
    @FunctionalInterface
    @NotNullByDefault
    interface FontFileLoader {
        /// Loads one file and returns its distinct family names.
        ///
        /// @param file local font file
        /// @return immutable family names in file order
        /// @throws IOException when the file cannot be read
        /// @throws FontFormatException when its font data is unsupported
        @Unmodifiable List<String> load(Path file) throws IOException, FontFormatException;
    }

    /// Resolves one Linux fontconfig pattern to a registered family.
    @FunctionalInterface
    @NotNullByDefault
    interface FontMatchResolver {
        /// Resolves one non-blank locale-specific fontconfig pattern.
        ///
        /// @param pattern fontconfig expression
        /// @return matched family, or `null`
        @Nullable String resolve(String pattern);
    }
}
