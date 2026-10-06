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
package space.minecraftstl.xyml.ui.swing.page.shaderpacks;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameRepository;
import space.minecraftstl.xyml.util.io.DeletionMode;
import space.minecraftstl.xyml.util.io.FileUtils;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipFile;

/// Filesystem and game-configuration access for locally installed shader packs.
@NotNullByDefault
public final class FileSystemShaderPackCatalogAccess implements ShaderPackCatalogAccess {
    /// Iris/Oculus configuration file name relative to the instance run directory.
    private static final Path IRIS_CONFIG_PATH = Path.of("config", "iris.properties");

    /// OptiFine options file name relative to the instance run directory.
    private static final Path OPTIFINE_OPTIONS_PATH = Path.of("optionsof.txt");

    /// Deterministic direct-child path ordering.
    private static final Comparator<Path> PATH_ORDER = Comparator.comparing(FileSystemShaderPackCatalogAccess::fileName);

    /// Instance run directory containing shaderpacks and runtime configuration.
    private final Path runDirectory;

    /// Managed shaderpacks directory.
    private final Path shaderPackDirectory;

    /// Instance mods directory used for Iris/Oculus installation detection.
    private final Path modsDirectory;

    /// Iris/Oculus configuration path.
    private final Path irisConfig;

    /// OptiFine options path.
    private final Path optifineOptions;

    /// OptiFine installation detector.
    private final BooleanSupplier optifineInstalled;

    /// Creates production access for one repository instance.
    ///
    /// @param repository owning repository
    /// @param instanceId stable instance identifier
    public FileSystemShaderPackCatalogAccess(GameRepository repository, GameInstanceID instanceId) {
        this(
                repository.getRunDirectory(instanceId),
                repository.getModsDirectory(instanceId),
                () -> hasOptifineLibrary(repository, instanceId));
    }

    /// Creates access from explicit directories for tests and alternate repository adapters.
    ///
    /// @param runDirectory instance run directory
    /// @param modsDirectory mods directory used for runtime detection
    /// @param optifineInstalled OptiFine installation detector
    FileSystemShaderPackCatalogAccess(
            Path runDirectory,
            Path modsDirectory,
            BooleanSupplier optifineInstalled) {
        this.runDirectory = Objects.requireNonNull(runDirectory, "runDirectory")
                .toAbsolutePath()
                .normalize();
        this.shaderPackDirectory = this.runDirectory.resolve("shaderpacks").normalize();
        this.modsDirectory = Objects.requireNonNull(modsDirectory, "modsDirectory")
                .toAbsolutePath()
                .normalize();
        this.irisConfig = this.runDirectory.resolve(IRIS_CONFIG_PATH).normalize();
        this.optifineOptions = this.runDirectory.resolve(OPTIFINE_OPTIONS_PATH).normalize();
        this.optifineInstalled = Objects.requireNonNull(optifineInstalled, "optifineInstalled");
    }

    /// Returns the managed shaderpacks directory.
    ///
    /// @return normalized absolute shaderpacks path
    public Path directory() {
        return shaderPackDirectory;
    }

    /// Enumerates direct-child ZIP files and directories in stable file-name order.
    @Override
    public @Unmodifiable List<Path> loadIndex() throws IOException {
        if (!Files.isDirectory(shaderPackDirectory)) {
            return List.of();
        }
        List<Path> paths = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(shaderPackDirectory)) {
            for (Path child : children) {
                Path normalized = child.toAbsolutePath().normalize();
                if (isCandidate(normalized)) {
                    paths.add(normalized);
                }
            }
        }
        paths.sort(PATH_ORDER);
        return List.copyOf(paths);
    }

    /// Loads one row per supplied path while reading shared configuration once.
    @Override
    public @Unmodifiable List<ShaderPackCatalogItem> loadItems(@Unmodifiable List<Path> paths) throws IOException {
        Objects.requireNonNull(paths, "paths");
        String irisSelection = readSetting(irisConfig, "shaderPack", "OFF");
        boolean optifineEnabled = Boolean.parseBoolean(
                readSetting(optifineOptions, "ofShaders", "false"));
        String optifineSelection = readSetting(optifineOptions, "ofShaderPack", "OFF");
        List<ShaderPackCatalogItem> items = new ArrayList<>(paths.size());
        for (Path path : paths) {
            Path normalized = requireDirectChild(path);
            String exactName = fileName(normalized);
            boolean valid = isValidShaderPack(normalized);
            EnumSet<ShaderPackBackend> enabled = EnumSet.noneOf(ShaderPackBackend.class);
            if (sameSelection(irisSelection, exactName)) {
                enabled.add(ShaderPackBackend.IRIS_OCULUS);
            }
            if (optifineEnabled && sameSelection(optifineSelection, exactName)) {
                enabled.add(ShaderPackBackend.OPTIFINE);
            }
            items.add(new ShaderPackCatalogItem(
                    normalized,
                    exactName,
                    displayName(exactName),
                    valid,
                    enabled));
        }
        return List.copyOf(items);
    }

    /// Detects Iris/Oculus and OptiFine from configuration or installation evidence.
    @Override
    public @Unmodifiable Set<ShaderPackBackend> detectAvailableBackends() throws IOException {
        EnumSet<ShaderPackBackend> backends = EnumSet.noneOf(ShaderPackBackend.class);
        if (Files.isRegularFile(irisConfig) || hasIrisOculusMod()) {
            backends.add(ShaderPackBackend.IRIS_OCULUS);
        }
        if (Files.isRegularFile(optifineOptions) || optifineInstalled.getAsBoolean()) {
            backends.add(ShaderPackBackend.OPTIFINE);
        }
        return Set.copyOf(backends);
    }

    /// Imports every source through a private staging directory without overwriting targets.
    @Override
    public void importShaderPacks(@Unmodifiable List<Path> sources) throws IOException {
        List<Path> checkedSources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        if (checkedSources.isEmpty()) {
            throw new IllegalArgumentException("At least one shader-pack source is required");
        }
        if (Files.exists(shaderPackDirectory, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(shaderPackDirectory)) {
            throw new IOException("Managed shaderpacks path is not a directory: " + shaderPackDirectory);
        }
        Map<Path, Path> targets = new LinkedHashMap<>();
        for (Path source : checkedSources) {
            Path normalizedSource = source.toAbsolutePath().normalize();
            if (!isValidShaderPack(normalizedSource)) {
                throw new IllegalArgumentException("File is not a valid shader pack: " + normalizedSource);
            }
            if (isManagedDirectoryInsideSource(normalizedSource)) {
                throw new IllegalArgumentException("Import source contains the managed shaderpacks directory");
            }
            Path target = requireDirectChild(shaderPackDirectory.resolve(fileName(normalizedSource)));
            if (normalizedSource.equals(target)) {
                throw new IllegalArgumentException("Shader-pack source already is the managed target");
            }
            if (targets.containsValue(target) || Files.exists(target)) {
                throw new IOException("Shader-pack target already exists: " + target);
            }
            targets.put(normalizedSource, target);
        }

        Files.createDirectories(shaderPackDirectory);
        Path staging = Files.createTempDirectory(shaderPackDirectory, ".xyml-shader-import-");
        List<Path> published = new ArrayList<>();
        @Nullable Throwable failure = null;
        try {
            for (Map.Entry<Path, Path> entry : targets.entrySet()) {
                Path staged = staging.resolve(fileName(entry.getValue()));
                copySource(entry.getKey(), staged);
            }
            for (Map.Entry<Path, Path> entry : targets.entrySet()) {
                Files.move(staging.resolve(fileName(entry.getValue())), entry.getValue());
                published.add(entry.getValue());
            }
        } catch (IOException | RuntimeException | Error thrown) {
            failure = thrown;
        } finally {
            try {
                FileUtils.deleteDirectory(staging);
            } catch (IOException cleanupFailure) {
                if (failure == null) {
                    failure = cleanupFailure;
                } else if (failure != cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
        }
        if (failure != null) {
            @Nullable Throwable cleanupFailure = null;
            for (int index = published.size() - 1; index >= 0; index--) {
                Path publishedPath = published.get(index);
                try {
                    if (Files.isDirectory(publishedPath, LinkOption.NOFOLLOW_LINKS)) {
                        FileUtils.deleteDirectory(publishedPath);
                    } else {
                        Files.deleteIfExists(publishedPath);
                    }
                } catch (IOException cleanupFailureItem) {
                    cleanupFailure = combineFailures(cleanupFailure, cleanupFailureItem);
                }
            }
            if (cleanupFailure != null && failure != cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            rethrow(failure);
        }
    }

    /// Updates one pack's selected state in all requested backends.
    @Override
    public void setEnabled(
            Path path,
            @Unmodifiable Set<ShaderPackBackend> backends,
            boolean enabled) throws IOException {
        Path normalized = requireDirectChild(path);
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Shader pack no longer exists: " + normalized);
        }
        Set<ShaderPackBackend> checkedBackends = Set.copyOf(
                Objects.requireNonNull(backends, "backends"));
        if (checkedBackends.isEmpty()) {
            throw new IllegalArgumentException("At least one backend is required");
        }
        String exactName = fileName(normalized);
        Map<Path, ConfigChange> changes = new LinkedHashMap<>();
        if (checkedBackends.contains(ShaderPackBackend.IRIS_OCULUS)) {
            addIrisChange(changes, exactName, enabled);
        }
        if (checkedBackends.contains(ShaderPackBackend.OPTIFINE)) {
            addOptifineChange(changes, exactName, enabled);
        }
        applyChanges(changes);
    }

    /// Clears references to one pack and then deletes it.
    @Override
    public void delete(Path path, DeletionMode mode) throws IOException {
        Path normalized = requireDirectChild(path);
        Objects.requireNonNull(mode, "mode");
        EnumSet<ShaderPackBackend> enabledBackends = EnumSet.noneOf(ShaderPackBackend.class);
        String exactName = fileName(normalized);
        if (sameSelection(readSetting(irisConfig, "shaderPack", "OFF"), exactName)) {
            enabledBackends.add(ShaderPackBackend.IRIS_OCULUS);
        }
        if (Boolean.parseBoolean(readSetting(optifineOptions, "ofShaders", "false"))
                && sameSelection(readSetting(optifineOptions, "ofShaderPack", "OFF"), exactName)) {
            enabledBackends.add(ShaderPackBackend.OPTIFINE);
        }
        setEnabled(normalized, EnumSet.allOf(ShaderPackBackend.class), false);
        try {
            FileUtils.deleteWithMode(normalized, mode);
        } catch (IOException | RuntimeException failure) {
            if (!enabledBackends.isEmpty()) {
                try {
                    setEnabled(normalized, enabledBackends, true);
                } catch (RuntimeException | IOException restoreFailure) {
                    if (failure != restoreFailure) {
                        failure.addSuppressed(restoreFailure);
                    }
                }
            }
            throw failure;
        }
    }

    /// Adds one Iris configuration replacement when it changes the file.
    ///
    /// @param changes mutable change map
    /// @param exactName exact shader-pack file name
    /// @param enabled desired state
    /// @throws IOException when configuration cannot be read
    private void addIrisChange(
            Map<Path, ConfigChange> changes,
            String exactName,
            boolean enabled) throws IOException {
        @Nullable String original = readOptionalText(irisConfig);
        String current = original == null ? "" : original;
        String selected = readSettingFromText(current, "shaderPack", "OFF");
        String replacement;
        if (enabled) {
            replacement = replaceSetting(current, "shaderPack", exactName, '=');
        } else if (sameSelection(selected, exactName)) {
            replacement = replaceSetting(current, "shaderPack", "OFF", '=');
        } else {
            return;
        }
        addChange(changes, irisConfig, original, replacement);
    }

    /// Adds one OptiFine configuration replacement when it changes the file.
    ///
    /// @param changes mutable change map
    /// @param exactName exact shader-pack file name
    /// @param enabled desired state
    /// @throws IOException when configuration cannot be read
    private void addOptifineChange(
            Map<Path, ConfigChange> changes,
            String exactName,
            boolean enabled) throws IOException {
        @Nullable String original = readOptionalText(optifineOptions);
        String current = original == null ? "" : original;
        String selected = readSettingFromText(current, "ofShaderPack", "OFF");
        boolean currentlyEnabled = Boolean.parseBoolean(
                readSettingFromText(current, "ofShaders", "false"));
        String replacement = current;
        if (enabled) {
            replacement = replaceSetting(replacement, "ofShaderPack", exactName, ':');
            replacement = replaceSetting(replacement, "ofShaders", "true", ':');
        } else if (currentlyEnabled && sameSelection(selected, exactName)) {
            replacement = replaceSetting(replacement, "ofShaders", "false", ':');
        } else {
            return;
        }
        addChange(changes, optifineOptions, original, replacement);
    }

    /// Adds one immutable textual change when the replacement differs.
    ///
    /// @param changes mutable change map
    /// @param path configuration path
    /// @param original original content, or null when absent
    /// @param replacement replacement content
    private static void addChange(
            Map<Path, ConfigChange> changes,
            Path path,
            @Nullable String original,
            String replacement) {
        if (!Objects.equals(original, replacement)) {
            changes.put(path, new ConfigChange(path, original, replacement));
        }
    }

    /// Applies all configuration changes with rollback on a later failure.
    ///
    /// @param changes ordered change map
    /// @throws IOException when any write or rollback fails
    private static void applyChanges(Map<Path, ConfigChange> changes) throws IOException {
        List<ConfigChange> applied = new ArrayList<>();
        try {
            for (ConfigChange change : changes.values()) {
                FileUtils.saveSafely(change.path(), change.replacement());
                applied.add(change);
            }
        } catch (IOException | RuntimeException | Error failure) {
            @Nullable Throwable rollbackFailure = null;
            for (int index = applied.size() - 1; index >= 0; index--) {
                ConfigChange change = applied.get(index);
                try {
                    if (change.original() == null) {
                        Files.deleteIfExists(change.path());
                    } else {
                        FileUtils.saveSafely(change.path(), change.original());
                    }
                } catch (IOException | RuntimeException | Error rollbackItemFailure) {
                    rollbackFailure = combineFailures(rollbackFailure, rollbackItemFailure);
                }
            }
            if (rollbackFailure != null && rollbackFailure != failure) {
                failure.addSuppressed(rollbackFailure);
            }
            rethrow(failure);
        }
    }

    /// Tests whether one path is a supported direct-child candidate.
    ///
    /// @param path candidate path
    /// @return whether the path is a ZIP file or directory
    private static boolean isCandidate(Path path) {
        return Files.isDirectory(path) || (Files.isRegularFile(path)
                && fileName(path).toLowerCase(Locale.ROOT).endsWith(".zip"));
    }

    /// Tests whether one source contains a recognizable shaders payload.
    ///
    /// @param path candidate ZIP or directory
    /// @return whether the source is a non-empty shader-pack payload
    private static boolean isValidShaderPack(Path path) {
        try {
            if (Files.isDirectory(path)) {
                return Files.isDirectory(path.resolve("shaders"));
            }
            if (!Files.isRegularFile(path) || !fileName(path).toLowerCase(Locale.ROOT).endsWith(".zip")) {
                return false;
            }
            try (ZipFile zip = new ZipFile(path.toFile())) {
                return zip.stream().anyMatch(entry -> {
                    String name = entry.getName().replace('\\', '/');
                    return name.startsWith("shaders/") && !entry.isDirectory();
                });
            }
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    /// Tests whether a source would recursively contain the managed directory.
    ///
    /// @param source normalized import source
    /// @return whether copying would recurse into managed storage
    /// @throws IOException when paths cannot be resolved
    private boolean isManagedDirectoryInsideSource(Path source) throws IOException {
        if (!Files.isDirectory(source)) {
            return false;
        }
        Path realSource = source.toRealPath();
        Path comparableDirectory = Files.exists(shaderPackDirectory)
                ? shaderPackDirectory.toRealPath()
                : shaderPackDirectory;
        return comparableDirectory.startsWith(realSource);
    }

    /// Copies one validated source into private staging.
    ///
    /// @param source source path
    /// @param target private staging target
    /// @throws IOException when copying fails or a symbolic link is encountered
    private static void copySource(Path source, Path target) throws IOException {
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            copyDirectory(source, target);
        } else {
            Files.copy(
                    source,
                    target,
                    StandardCopyOption.COPY_ATTRIBUTES,
                    LinkOption.NOFOLLOW_LINKS);
        }
    }

    /// Copies one directory without following symbolic links.
    ///
    /// @param source source directory
    /// @param target target directory
    /// @throws IOException when copying fails
    private static void copyDirectory(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            /// Creates the target directory before visiting its children.
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                Objects.requireNonNull(attributes, "attributes");
                Path relative = source.relativize(directory);
                Path destination = target.resolve(relative);
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Shader-pack copy target is not a directory: " + destination);
                    }
                } else {
                    Files.copy(
                            directory,
                            destination,
                            StandardCopyOption.COPY_ATTRIBUTES,
                            LinkOption.NOFOLLOW_LINKS);
                }
                return FileVisitResult.CONTINUE;
            }

            /// Copies one regular file and rejects symbolic links.
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Objects.requireNonNull(attributes, "attributes");
                if (Files.isSymbolicLink(file)) {
                    throw new IOException("Shader-pack source contains a symbolic link: " + file);
                }
                Path relative = source.relativize(file);
                Files.copy(
                        file,
                        target.resolve(relative),
                        StandardCopyOption.COPY_ATTRIBUTES,
                        LinkOption.NOFOLLOW_LINKS);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /// Reads one key from a Java-properties-style file.
    ///
    /// @param file source file
    /// @param key exact key
    /// @param defaultValue value when absent
    /// @return final effective value
    /// @throws IOException when the file cannot be read
    private static String readSetting(Path file, String key, String defaultValue) throws IOException {
        @Nullable String content = readOptionalText(file);
        return content == null ? defaultValue : readSettingFromText(content, key, defaultValue);
    }

    /// Reads one key from text without throwing on malformed unrelated lines.
    ///
    /// @param content complete text
    /// @param key exact key
    /// @param defaultValue value when absent
    /// @return final effective value
    private static String readSettingFromText(String content, String key, String defaultValue) {
        String value = defaultValue;
        for (String line : content.split("\\R", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue;
            }
            int separator = separatorIndex(trimmed);
            if (separator > 0 && trimmed.substring(0, separator).trim().equals(key)) {
                value = trimmed.substring(separator + 1).trim();
            }
        }
        return value;
    }

    /// Replaces the final effective key line or appends a new line while preserving line endings.
    ///
    /// @param content original content
    /// @param key exact key
    /// @param value replacement value
    /// @param separator separator character
    /// @return line-preserving replacement text
    private static String replaceSetting(String content, String key, String value, char separator) {
        String eol = content.contains("\r\n") ? "\r\n" : "\n";
        List<Line> lines = splitLines(content);
        int replacementIndex = -1;
        for (int index = 0; index < lines.size(); index++) {
            String trimmed = lines.get(index).content().trim();
            int separatorIndex = separatorIndex(trimmed);
            if (separatorIndex > 0 && trimmed.substring(0, separatorIndex).trim().equals(key)) {
                replacementIndex = index;
            }
        }
        List<Line> replacement = new ArrayList<>(lines);
        if (replacementIndex >= 0) {
            Line previous = replacement.get(replacementIndex);
            replacement.set(
                    replacementIndex,
                    new Line(key + separator + value, previous.terminator()));
        } else {
            if (!replacement.isEmpty()) {
                int lastIndex = replacement.size() - 1;
                Line last = replacement.get(lastIndex);
                if (last.terminator().isEmpty()) {
                    replacement.set(lastIndex, new Line(last.content(), eol));
                }
            }
            replacement.add(new Line(key + separator + value, ""));
        }
        StringBuilder builder = new StringBuilder();
        for (Line line : replacement) {
            builder.append(line.content()).append(line.terminator());
        }
        return builder.toString();
    }

    /// Splits text while retaining each line terminator exactly.
    ///
    /// @param content complete text
    /// @return immutable line list
    private static List<Line> splitLines(String content) {
        if (content.isEmpty()) {
            return List.of();
        }
        List<Line> lines = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < content.length(); index++) {
            char character = content.charAt(index);
            if (character != '\r' && character != '\n') {
                continue;
            }
            String terminator;
            int end = index;
            if (character == '\r' && index + 1 < content.length() && content.charAt(index + 1) == '\n') {
                terminator = "\r\n";
                index++;
            } else {
                terminator = String.valueOf(character);
            }
            lines.add(new Line(content.substring(start, end), terminator));
            start = index + 1;
        }
        if (start < content.length()) {
            lines.add(new Line(content.substring(start), ""));
        }
        return List.copyOf(lines);
    }

    /// Finds the first key/value separator in one trimmed line.
    ///
    /// @param line trimmed line
    /// @return separator index, or -1
    private static int separatorIndex(String line) {
        int equals = line.indexOf('=');
        int colon = line.indexOf(':');
        if (equals < 0) {
            return colon;
        }
        if (colon < 0) {
            return equals;
        }
        return Math.min(equals, colon);
    }

    /// Tests one exact configured selection against a file name.
    ///
    /// @param selection configured value
    /// @param exactName exact file name
    /// @return whether the selection matches
    private static boolean sameSelection(String selection, String exactName) {
        String trimmed = selection.trim();
        return !trimmed.isEmpty()
                && !trimmed.equalsIgnoreCase("OFF")
                && trimmed.equals(exactName);
    }

    /// Reads optional UTF-8 text without failing for an absent file.
    ///
    /// @param path source path
    /// @return file text, or null when absent
    /// @throws IOException when the file exists but cannot be read
    private static @Nullable String readOptionalText(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Shader-pack configuration is not a regular file: " + path);
        }
        return Files.readString(path);
    }

    /// Returns one normalized direct-child path.
    ///
    /// @param path candidate path
    /// @return normalized direct child
    private Path requireDirectChild(Path path) {
        Path normalized = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        if (!shaderPackDirectory.equals(normalized.getParent())) {
            throw new IllegalArgumentException("Path is not a direct shaderpacks child: " + normalized);
        }
        return normalized;
    }

    /// Tests for Iris or Oculus mod files.
    ///
    /// @return whether a direct mod JAR appears to be Iris/Oculus
    private boolean hasIrisOculusMod() throws IOException {
        if (!Files.isDirectory(modsDirectory)) {
            return false;
        }
        try (DirectoryStream<Path> children = Files.newDirectoryStream(modsDirectory, "*.jar")) {
            for (Path child : children) {
                String name = fileName(child).toLowerCase(Locale.ROOT);
                if (name.startsWith("iris") || name.startsWith("oculus")) {
                    return true;
                }
            }
        }
        return false;
    }

    /// Detects OptiFine from the resolved instance manifest.
    ///
    /// @param repository owning repository
    /// @param instanceId instance identifier
    /// @return whether OptiFine is declared
    private static boolean hasOptifineLibrary(GameRepository repository, GameInstanceID instanceId) {
        try {
            return repository.getInstanceManifest(instanceId).getLibraries().stream()
                    .anyMatch(library -> library.is("optifine", "OptiFine"));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /// Returns one path's exact final component.
    ///
    /// @param path source path
    /// @return exact final component
    private static String fileName(Path path) {
        return Objects.requireNonNull(path.getFileName(), "Shader-pack path has no file name").toString();
    }

    /// Removes one ZIP extension from a display name.
    ///
    /// @param exactName exact file name
    /// @return display name
    private static String displayName(String exactName) {
        return exactName.toLowerCase(Locale.ROOT).endsWith(".zip")
                ? exactName.substring(0, exactName.length() - 4)
                : exactName;
    }

    /// Adds one failure to an existing failure chain.
    ///
    /// @param previous earlier failure, or null
    /// @param current current failure
    /// @return first failure with later failures suppressed
    private static Throwable combineFailures(@Nullable Throwable previous, Throwable current) {
        Objects.requireNonNull(current, "current");
        if (previous == null) {
            return current;
        }
        if (previous != current) {
            previous.addSuppressed(current);
        }
        return previous;
    }

    /// Rethrows one access failure without changing its unchecked type.
    ///
    /// @param failure failure to rethrow
    /// @throws IOException when the failure is checked I/O
    private static void rethrow(Throwable failure) throws IOException {
        if (failure instanceof IOException ioFailure) {
            throw ioFailure;
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IOException("Unexpected shader-pack access failure", failure);
    }

    /// One line content and exact following terminator.
    ///
    /// @param content line content
    /// @param terminator empty, LF, CR, or CRLF
    @NotNullByDefault
    private record Line(String content, String terminator) {
        /// Validates raw line parts.
        private Line {
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(terminator, "terminator");
        }
    }

    /// One original and replacement configuration text pair.
    ///
    /// @param path configuration path
    /// @param original original text, or null when the file was absent
    /// @param replacement replacement text
    @NotNullByDefault
    private record ConfigChange(
            Path path,
            @Nullable String original,
            String replacement) {
        /// Validates one configuration change.
        private ConfigChange {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(replacement, "replacement");
        }
    }
}
