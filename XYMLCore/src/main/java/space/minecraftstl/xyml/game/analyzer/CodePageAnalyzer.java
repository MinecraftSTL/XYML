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
import space.minecraftstl.xyml.game.CrashReportAnalyzer;
import space.minecraftstl.xyml.util.platform.OperatingSystem;

import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Identifies Windows legacy code-page failures only when a launch path contains non-ASCII characters.
@NotNullByDefault
public final class CodePageAnalyzer implements Analyzer<LogAnalyzable> {
    /// Windows UTF-8 system code page.
    private static final int UTF_8_CODE_PAGE = 65001;

    /// English and Simplified Chinese Java launcher main-class diagnostics.
    private static final Pattern MAIN_CLASS_FAILURE = Pattern.compile(
            "(?im)^(?:(?:Error|\u9519\u8bef)[:\uFF1A]\\s*)?"
                    + "(?:Could not find or load main class|\u627e\u4e0d\u5230\u6216\u65e0\u6cd5"
                    + "\u52a0\u8f7d\u4e3b\u7c7b)\\s+(?<className>\\S+)");

    /// Class-not-found lines whose class name must equal the resolved launch main class.
    private static final Pattern CLASS_NOT_FOUND = Pattern.compile(
            "(?m)^(?:(?:Caused by:|Exception in thread \"main\")\\s*)?"
                    + "java\\.lang\\.ClassNotFoundException:\\s+(?<className>\\S+)");

    /// Stable LWJGL native-loading diagnostic.
    private static final String LWJGL_LOAD_FAILURE = "[LWJGL] Failed to load a library";

    /// Exact missing-native evidence required alongside the broader LWJGL diagnostic.
    private static final String LWJGL_LIBRARY_NOT_FOUND = "UnsatisfiedLinkError: Failed to locate library:";

    /// Explicit native architecture mismatch that has a more direct cause than path encoding.
    private static final String LWJGL_ARCHITECTURE_MISMATCH = "Platform/architecture mismatch detected";

    /// Requires Windows, a legacy code page, a non-ASCII launch path, and launch-level failure evidence.
    ///
    /// @param input immutable launch and log snapshot
    /// @param results mutable diagnosis accumulator
    /// @return `BREAK_OTHER` after a verified match, otherwise `CONTINUE`
    @Override
    public ControlFlow analyze(LogAnalyzable input, List<AnalyzeResult<LogAnalyzable>> results) {
        if (input.operatingSystem() != OperatingSystem.WINDOWS
                || input.systemCodePage() <= 0
                || input.systemCodePage() == UTF_8_CODE_PAGE
                || !input.hasNonAsciiPath()
                || !hasUnencodablePath(input)) {
            return ControlFlow.CONTINUE;
        }

        String log = input.logText();
        if (containsJavaVersionEvidence(log) || log.contains(LWJGL_ARCHITECTURE_MISMATCH)) {
            return ControlFlow.CONTINUE;
        }
        boolean verified = containsMainClassFailure(log, input.mainClass())
                || log.contains(LWJGL_LOAD_FAILURE) && log.contains(LWJGL_LIBRARY_NOT_FOUND);
        if (!verified) {
            return ControlFlow.CONTINUE;
        }

        results.add(new AnalyzeResult<>(
                this,
                ResultID.CODE_PAGE,
                new TextSolver(
                        "game.crash.reason.log.code_page",
                        "Enable the Windows UTF-8 system locale, restart Windows, or move the game to an ASCII path.")));
        return ControlFlow.BREAK_OTHER;
    }

    /// Reports whether established rules already prove that Java version, rather than path encoding, is at fault.
    ///
    /// @param log complete launch log
    /// @return true when an established Java-version rule matches
    private static boolean containsJavaVersionEvidence(String log) {
        return CrashReportRuleEvidence.find(
                log,
                CrashReportAnalyzer.Rule.TOO_OLD_JAVA,
                CrashReportAnalyzer.Rule.NEED_JDK11,
                CrashReportAnalyzer.Rule.JAVA_VERSION_IS_TOO_HIGH,
                CrashReportAnalyzer.Rule.JDK_9) != null;
    }

    /// Reports whether a class-not-found line names the resolved launch main class.
    ///
    /// @param log complete launch log
    /// @param mainClass resolved launch main class, or null when unavailable
    /// @return true when a class-not-found line names the launch main class
    private static boolean containsMainClassFailure(String log, @Nullable String mainClass) {
        if (mainClass == null) {
            return false;
        }
        String normalizedMainClass = mainClass.replace('/', '.');
        return containsNamedClass(MAIN_CLASS_FAILURE, log, normalizedMainClass)
                || containsNamedClass(CLASS_NOT_FOUND, log, normalizedMainClass);
    }

    /// Reports whether one diagnostic pattern names the normalized launch main class.
    ///
    /// @param pattern diagnostic pattern exposing a `className` group
    /// @param log complete launch log
    /// @param normalizedMainClass dot-separated launch main class
    /// @return true when the pattern names the launch main class
    private static boolean containsNamedClass(Pattern pattern, String log, String normalizedMainClass) {
        Matcher matcher = pattern.matcher(log);
        while (matcher.find()) {
            if (normalizedMainClass.equals(matcher.group("className").replace('/', '.'))) {
                return true;
            }
        }
        return false;
    }

    /// Reports whether either launch path cannot be represented by the verified Windows ANSI code page.
    ///
    /// Unsupported code-page aliases fail closed because they cannot establish path corruption.
    ///
    /// @param input immutable launch context
    /// @return true when a launch path is not encodable by the system code page
    private static boolean hasUnencodablePath(LogAnalyzable input) {
        Charset systemCharset;
        try {
            systemCharset = Charset.forName("cp" + input.systemCodePage());
        } catch (IllegalCharsetNameException | UnsupportedCharsetException ignored) {
            return false;
        }
        return isUnencodable(input.gameDirectory(), systemCharset)
                || isUnencodable(input.javaPath(), systemCharset);
    }

    /// Reports whether one optional path cannot be encoded with the verified system charset.
    ///
    /// @param path launch path, or null when unavailable
    /// @param systemCharset verified system ANSI charset
    /// @return true when the path contains an unencodable character
    private static boolean isUnencodable(@Nullable Path path, Charset systemCharset) {
        return path != null && !systemCharset.newEncoder().canEncode(path.toString());
    }
}
