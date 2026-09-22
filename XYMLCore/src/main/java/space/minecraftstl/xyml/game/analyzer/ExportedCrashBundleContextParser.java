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
package space.minecraftstl.xyml.game.analyzer;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.ExportedCrashBundle;
import space.minecraftstl.xyml.game.ExportedCrashBundleText;
import space.minecraftstl.xyml.launch.ProcessListener;
import space.minecraftstl.xyml.util.platform.Bits;
import space.minecraftstl.xyml.util.platform.OperatingSystem;

import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Recovers only high-confidence analyzer metadata from validated exported diagnostic text.
///
/// The parser never opens another file and deliberately leaves paths, code page, main class, memory,
/// platform, and every executable application boundary unavailable. Conflicting evidence fails closed
/// to an unknown value instead of selecting one source arbitrarily.
@NotNullByDefault
public final class ExportedCrashBundleContextParser {
    /// Release and snapshot forms accepted from standard Minecraft crash-report fields.
    private static final String GAME_VERSION_VALUE =
            "(?:\\d+\\.\\d+(?:\\.\\d+)?(?:[-+][0-9A-Za-z._-]+)?|\\d{2}w\\d{2}[a-z])";

    /// Standard crash-report Minecraft version fields.
    private static final Pattern GAME_VERSION_FIELD = Pattern.compile(
            "(?im)^[ \\t]*Minecraft Version(?: ID)?:[ \\t]*(?<version>"
                    + GAME_VERSION_VALUE + ")[ \\t]*$");

    /// Fabric Loader's stable game-version startup line.
    private static final Pattern FABRIC_GAME_VERSION = Pattern.compile(
            "(?im)^.*\\bLoading Minecraft (?<version>" + GAME_VERSION_VALUE
                    + ") with Fabric Loader\\b.*$");

    /// Standard crash-report Java version field.
    private static final Pattern JAVA_VERSION_FIELD = Pattern.compile(
            "(?im)^[ \\t]*Java Version:[ \\t]*(?<version>[0-9][0-9A-Za-z._+-]*)"
                    + "(?:[ \\t]*,.*)?$");

    /// Forge's stable selected-JVM startup line.
    private static final Pattern FORGE_JAVA_VERSION = Pattern.compile(
            "(?im)^.*\\bJava is [^\\r\\n]*?\\bversion[ \\t]+"
                    + "(?<version>[0-9][0-9A-Za-z._+-]*)(?:,|[ \\t]|$).*$");

    /// ModLauncher's stable selected-JVM startup line.
    private static final Pattern MOD_LAUNCHER_JAVA_VERSION = Pattern.compile(
            "(?im)^.*\\bstarting: java version[ \\t]+"
                    + "(?<version>[0-9][0-9A-Za-z._+-]*)(?:[ \\t]|$).*$");

    /// Standard crash-report and HotSpot fatal-log VM-name lines with explicit process bitness.
    private static final Pattern JAVA_VM_BITS = Pattern.compile(
            "(?im)^[ \\t]*(?:Java VM Version:|#[ \\t]*Java VM:)[^\\r\\n]*\\b"
                    + "(?<bits>32|64)-Bit Server VM\\b[^\\r\\n]*$");

    /// Forge's stable selected-JVM line with explicit process bitness.
    private static final Pattern FORGE_JAVA_BITS = Pattern.compile(
            "(?im)^.*\\bJava is [^\\r\\n]*\\b(?<bits>32|64)-Bit Server VM\\b[^\\r\\n]*$");

    /// Explicit class-file mismatch containing the required and optionally current JVM capability.
    private static final Pattern CLASS_VERSION_REQUIREMENT = Pattern.compile(
            "(?im)^.*UnsupportedClassVersionError:[^\\r\\n]*?class file version[ \\t]+"
                    + "(?<required>\\d+)\\.0(?:[^\\r\\n]*?class file versions up to[ \\t]+"
                    + "(?<current>\\d+)\\.0)?[^\\r\\n]*$");

    /// Explicit Java 11 compatibility request rejected by the active runtime or ASM implementation.
    private static final Pattern JAVA_11_COMPATIBILITY_REQUIREMENT = Pattern.compile(
            "(?m)^.*The requested compatibility level JAVA_11 could not be set\\. "
                    + "Level is not supported by the active JRE or ASM version.*$");

    /// Prevents construction of this utility class.
    private ExportedCrashBundleContextParser() {
    }

    /// Builds a non-executable analysis context from already validated diagnostic text.
    ///
    /// @param bundle immutable validated crash-export contents
    /// @return conservative context containing only metadata proved consistently by the text
    public static LogAnalyzable parse(ExportedCrashBundle bundle) {
        ExportedCrashBundle imported = Objects.requireNonNull(bundle, "bundle");
        Consensus<String> gameVersion = new Consensus<>();
        Consensus<Integer> currentJavaVersion = new Consensus<>();
        Consensus<Integer> requiredJavaVersion = new Consensus<>();
        Consensus<Bits> javaBits = new Consensus<>();

        for (ExportedCrashBundleText text : imported.texts()) {
            String content = text.content();
            collectMatches(GAME_VERSION_FIELD, content, "version", gameVersion);
            collectMatches(FABRIC_GAME_VERSION, content, "version", gameVersion);
            collectJavaVersions(JAVA_VERSION_FIELD, content, currentJavaVersion);
            collectJavaVersions(FORGE_JAVA_VERSION, content, currentJavaVersion);
            collectJavaVersions(MOD_LAUNCHER_JAVA_VERSION, content, currentJavaVersion);
            collectBits(JAVA_VM_BITS, content, javaBits);
            collectBits(FORGE_JAVA_BITS, content, javaBits);
            collectClassVersionRequirement(content, requiredJavaVersion, currentJavaVersion);
            if (JAVA_11_COMPATIBILITY_REQUIREMENT.matcher(content).find()) {
                requiredJavaVersion.accept(11);
            }
        }

        return new LogAnalyzable(
                gameVersion.value(),
                null,
                ProcessListener.ExitType.APPLICATION_ERROR,
                OperatingSystem.UNKNOWN,
                -1,
                null,
                null,
                requiredJavaVersion.value(),
                currentJavaVersion.value(),
                javaBits.valueOr(Bits.UNKNOWN),
                null,
                List.of());
    }

    /// Collects every named string match into one conflict-detecting consensus.
    private static void collectMatches(
            Pattern pattern,
            String content,
            String group,
            Consensus<String> consensus) {
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            consensus.accept(matcher.group(group));
        }
    }

    /// Parses every selected-JVM version match into a Java major version.
    private static void collectJavaVersions(
            Pattern pattern,
            String content,
            Consensus<Integer> consensus) {
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            @Nullable Integer major = parseJavaMajor(matcher.group("version"));
            if (major != null) {
                consensus.accept(major);
            }
        }
    }

    /// Parses explicit VM-name bitness without accepting unrelated native-library descriptions.
    private static void collectBits(Pattern pattern, String content, Consensus<Bits> consensus) {
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            consensus.accept("32".equals(matcher.group("bits")) ? Bits.BIT_32 : Bits.BIT_64);
        }
    }

    /// Recovers Java requirements only from an explicit class-file compatibility failure.
    private static void collectClassVersionRequirement(
            String content,
            Consensus<Integer> required,
            Consensus<Integer> current) {
        Matcher matcher = CLASS_VERSION_REQUIREMENT.matcher(content);
        while (matcher.find()) {
            @Nullable Integer requiredMajor = javaMajorForClassVersion(matcher.group("required"));
            if (requiredMajor != null) {
                required.accept(requiredMajor);
            }
            @Nullable String currentClassVersion = matcher.group("current");
            if (currentClassVersion != null) {
                @Nullable Integer currentMajor = javaMajorForClassVersion(currentClassVersion);
                if (currentMajor != null) {
                    current.accept(currentMajor);
                }
            }
        }
    }

    /// Parses legacy `1.x` and modern Java version strings into one bounded major version.
    private static @Nullable Integer parseJavaMajor(String version) {
        String[] parts = Objects.requireNonNull(version, "version").split("[._+-]", 3);
        try {
            int major = parts.length >= 2 && "1".equals(parts[0])
                    ? Integer.parseInt(parts[1])
                    : Integer.parseInt(parts[0]);
            return major > 0 && major <= 100 ? major : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /// Converts a modern JVM class-file major into the corresponding Java major.
    private static @Nullable Integer javaMajorForClassVersion(String classVersion) {
        try {
            int parsed = Integer.parseInt(Objects.requireNonNull(classVersion, "classVersion"));
            return parsed >= 52 && parsed <= 100 ? parsed - 44 : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /// Mutable first-value consensus that permanently becomes unknown after contradictory evidence.
    @NotNullByDefault
    private static final class Consensus<T> {
        /// First consistently observed value, or null before evidence.
        private @Nullable T value;

        /// Whether contradictory evidence made the value unusable.
        private boolean conflicting;

        /// Creates an empty consensus.
        private Consensus() {
        }

        /// Accepts one proved value and fails closed after a disagreement.
        private void accept(T candidate) {
            T checkedCandidate = Objects.requireNonNull(candidate, "candidate");
            if (conflicting) {
                return;
            }
            if (value == null) {
                value = checkedCandidate;
            } else if (!value.equals(checkedCandidate)) {
                value = null;
                conflicting = true;
            }
        }

        /// Returns the consistent value, or null when absent or contradictory.
        private @Nullable T value() {
            return conflicting ? null : value;
        }

        /// Returns the consistent value or a caller-supplied unknown sentinel.
        private T valueOr(T fallback) {
            @Nullable T current = value();
            return current == null ? Objects.requireNonNull(fallback, "fallback") : current;
        }
    }
}
