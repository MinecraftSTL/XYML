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
package space.minecraftstl.xyml.gradle.l10n;

import org.jetbrains.annotations.NotNullByDefault;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.FileTree;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Validates localized resource bundles, duplicate declarations, and crash-analysis source references.
///
/// @author Glavo
@NotNullByDefault
public abstract class CheckTranslations extends DefaultTask {

    private static final Logger LOGGER = Logging.getLogger(CheckTranslations.class);
    private static final Pattern CRASH_MESSAGE_KEY = Pattern.compile(
            "\\\"(game\\.crash\\.[a-z0-9_.-]*[a-z0-9_-])\\\"");

    @InputFile
    public abstract RegularFileProperty getEnglishFile();

    @InputFile
    public abstract RegularFileProperty getSimplifiedChineseFile();

    @InputFile
    public abstract RegularFileProperty getTraditionalChineseFile();

    @InputFile
    public abstract RegularFileProperty getClassicalChineseFile();

    /// Returns Java sources whose complete crash-message key literals must exist in the primary bundles.
    ///
    /// @return source files participating in crash-message key validation
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public FileTree getCrashMessageSources() {
        Path root = getProject().getRootProject().getProjectDir().toPath();
        return getProject().files(
                        root.resolve("XYMLCore/src/main/java/space/minecraftstl/xyml/game/analyzer"),
                        root.resolve("XYML/src/main/java/space/minecraftstl/xyml/ui/swing/crash"))
                .getAsFileTree()
                .matching(pattern -> pattern.include("**/*.java"));
    }

    @TaskAction
    public void run() throws IOException {
        validate(
                getEnglishFile().getAsFile().get().toPath(),
                getSimplifiedChineseFile().getAsFile().get().toPath(),
                getTraditionalChineseFile().getAsFile().get().toPath(),
                getClassicalChineseFile().getAsFile().get().toPath(),
                getCrashMessageSources().getFiles());
    }

    /// Validates four resource bundles and complete crash-message key literals from Java sources.
    ///
    /// @param englishFile English resource bundle
    /// @param simplifiedChineseFile Simplified Chinese resource bundle
    /// @param traditionalChineseFile Traditional Chinese resource bundle
    /// @param classicalChineseFile Classical Chinese resource bundle
    /// @param sourceFiles Java source files whose complete crash keys must be translated
    /// @throws IOException when a resource or source file cannot be read
    static void validate(
            Path englishFile,
            Path simplifiedChineseFile,
            Path traditionalChineseFile,
            Path classicalChineseFile,
            Set<File> sourceFiles) throws IOException {
        Checker checker = new Checker();

        var english = new PropertiesFile(englishFile);
        var simplifiedChinese = new PropertiesFile(simplifiedChineseFile);
        var traditionalChinese = new PropertiesFile(traditionalChineseFile);
        var classicalChinese = new PropertiesFile(classicalChineseFile);

        for (PropertiesFile file : List.of(english, simplifiedChinese, traditionalChinese, classicalChinese)) {
            checker.checkDuplicateKeys(file);
        }

        for (Map.Entry<String, List<Path>> reference : findCrashMessageKeyReferences(sourceFiles).entrySet()) {
            checker.checkReferencedKeyExists(english, reference.getKey(), reference.getValue());
            checker.checkReferencedKeyExists(simplifiedChinese, reference.getKey(), reference.getValue());
            checker.checkReferencedKeyExists(traditionalChinese, reference.getKey(), reference.getValue());
        }

        simplifiedChinese.forEach((key, value) -> {
            checker.checkKeyExists(english, key);
            checker.checkKeyExists(traditionalChinese, key);

            checker.checkMisspelled(simplifiedChinese, key, value, "账户", "帐户");
            checker.checkMisspelled(simplifiedChinese, key, value, "其他", "其它");

            checker.checkMisspelled(simplifiedChinese, key, value, "(", "（");
            checker.checkMisspelled(simplifiedChinese, key, value, ")", "）");
        });

        traditionalChinese.forEach((key, value) -> {
            checker.checkMisspelled(traditionalChinese, key, value, "(", "（");
            checker.checkMisspelled(traditionalChinese, key, value, ")", "）");
        });

        classicalChinese.forEach((key, value) -> {
            checker.checkMisspelled(classicalChinese, key, value, "綫", "線");
            checker.checkMisspelled(classicalChinese, key, value, "爲", "為");
            checker.checkMisspelled(classicalChinese, key, value, "啟", "啓");
        });

        checker.check();
    }

    /// Finds complete crash-message key literals in the supplied Java sources.
    ///
    /// @param sourceFiles Java source files to inspect
    /// @return stable key-to-source mapping in first-seen order
    /// @throws IOException when a source file cannot be read
    static Map<String, List<Path>> findCrashMessageKeyReferences(Set<File> sourceFiles) throws IOException {
        Map<String, List<Path>> references = new LinkedHashMap<>();
        List<Path> sortedSources = sourceFiles.stream()
                .map(File::toPath)
                .map(path -> path.toAbsolutePath().normalize())
                .sorted()
                .toList();
        for (Path source : sortedSources) {
            Matcher matcher = CRASH_MESSAGE_KEY.matcher(Files.readString(source));
            while (matcher.find()) {
                references.computeIfAbsent(matcher.group(1), ignored -> new ArrayList<>()).add(source);
            }
        }
        references.replaceAll((ignored, paths) -> List.copyOf(new LinkedHashSet<>(paths)));
        return Collections.unmodifiableMap(references);
    }

    private static final class PropertiesFile {
        final Path path;
        final TrackingProperties properties = new TrackingProperties();

        PropertiesFile(RegularFileProperty property) throws IOException {
            this(property.getAsFile().get().toPath().toAbsolutePath().normalize());
        }

        PropertiesFile(Path path) throws IOException {
            this.path = path;
            try (var reader = Files.newBufferedReader(path)) {
                properties.load(reader);
            }
        }

        public String getFileName() {
            return path.getFileName().toString();
        }

        public void forEach(BiConsumer<String, String> consumer) {
            properties.forEach((key, value) -> consumer.accept(key.toString(), value.toString()));
        }
    }

    /// Properties implementation that retains keys overwritten by later declarations.
    private static final class TrackingProperties extends Properties {
        private final Set<String> duplicateKeys = new LinkedHashSet<>();

        /// Records a duplicate before preserving the standard last-declaration-wins behavior.
        ///
        /// @param key property key
        /// @param value property value
        /// @return previous value, or null when this is the first declaration
        @Override
        public synchronized Object put(Object key, Object value) {
            if (containsKey(key)) {
                duplicateKeys.add(key.toString());
            }
            return super.put(key, value);
        }
    }

    private static final class Checker {

        private final Map<PropertiesFile, Map<Class<?>, Set<Problem>>> problems = new LinkedHashMap<>();
        private int problemsCount;

        public void checkKeyExists(PropertiesFile file, String key) {
            if (!file.properties.containsKey(key)) {
                onFailure(file, new Problem.MissingKey(key));
            }
        }

        public void checkDuplicateKeys(PropertiesFile file) {
            for (String key : file.properties.duplicateKeys) {
                onFailure(file, new Problem.DuplicateKey(key));
            }
        }

        public void checkReferencedKeyExists(PropertiesFile file, String key, List<Path> sources) {
            if (!file.properties.containsKey(key)) {
                onFailure(file, new Problem.MissingReferencedKey(key, sources));
            }
        }

        public void checkMisspelled(PropertiesFile file, String key, String value,
                                    String correct, String misspelled) {
            if (value.contains(misspelled)) {
                onFailure(file, new Problem.Misspelled(correct, misspelled));
            }
        }

        public void onFailure(PropertiesFile file, Problem problem) {
            problemsCount++;
            problems.computeIfAbsent(file, ignored -> new HashMap<>())
                    .computeIfAbsent(problem.getClass(), ignored -> new LinkedHashSet<>())
                    .add(problem);
        }

        public void check() {
            if (problemsCount > 0) {
                problems.forEach((file, problems) -> {
                    problems.values().stream().flatMap(Collection::stream).forEach(problem ->
                            LOGGER.warn("{}: {}", file.getFileName(), problem.getMessage()));
                });

                throw new GradleException("Failed to check translations, " + problemsCount + " found problems.");
            }
        }
    }

    private static abstract sealed class Problem {
        public abstract String getMessage();

        private static final class MissingKey extends Problem {
            private final String key;

            MissingKey(String key) {
                this.key = key;
            }

            @Override
            public String getMessage() {
                return "missing key '%s'".formatted(key);
            }
        }

        private static final class DuplicateKey extends Problem {
            private final String key;

            DuplicateKey(String key) {
                this.key = key;
            }

            @Override
            public String getMessage() {
                return "duplicate key '%s'".formatted(key);
            }
        }

        private static final class MissingReferencedKey extends Problem {
            private final String key;
            private final List<Path> sources;

            MissingReferencedKey(String key, List<Path> sources) {
                this.key = key;
                this.sources = List.copyOf(sources);
            }

            @Override
            public String getMessage() {
                return "missing source-referenced key '%s' used by %s".formatted(
                        key,
                        sources.stream().map(Path::getFileName).distinct().toList());
            }
        }

        private static final class Misspelled extends Problem {
            private final String correct;
            private final String misspelled;

            Misspelled(String correct, String misspelled) {
                this.correct = correct;
                this.misspelled = misspelled;
            }

            @Override
            public String getMessage() {
                return "misspelled '%s' should be replaced by '%s'".formatted(misspelled, correct);
            }

            @Override
            public int hashCode() {
                return misspelled.hashCode();
            }

            @Override
            public boolean equals(Object obj) {
                return obj instanceof Misspelled that && this.misspelled.equals(that.misspelled);
            }
        }

    }
}
