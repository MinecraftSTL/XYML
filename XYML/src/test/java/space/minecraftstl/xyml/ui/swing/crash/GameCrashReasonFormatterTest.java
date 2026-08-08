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
package space.minecraftstl.xyml.ui.swing.crash;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.game.CrashReportAnalyzer;
import space.minecraftstl.xyml.game.analyzer.AnalyzeResult;
import space.minecraftstl.xyml.game.analyzer.JREVersionAnalyzer;
import space.minecraftstl.xyml.game.analyzer.LogAnalyzable;
import space.minecraftstl.xyml.game.analyzer.ResultID;
import space.minecraftstl.xyml.game.analyzer.TextSolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Verifies rule-specific localization and unknown-crash fallback messages.
@NotNullByDefault
class GameCrashReasonFormatterTest {
    /// Formats the special Java class-major-version group with the analyzer's user-facing Java version.
    @Test
    void formatsTooOldJavaSpecialCase() {
        CrashReportAnalyzer.Result result = onlyResult(
                "java.lang.UnsupportedClassVersionError: example version 61.0",
                CrashReportAnalyzer.Rule.TOO_OLD_JAVA);

        String message = new GameCrashReasonFormatter().format(
                new GameCrashAnalysis(List.of(result), Set.of()));

        assertEquals(i18n(
                "game.crash.reason.too_old_java",
                CrashReportAnalyzer.getJavaVersionFromMajorVersion(61)), message);
    }

    /// Prefixes multiple detected causes with the existing localized explanation.
    @Test
    void prefixesMultipleReasons() {
        List<CrashReportAnalyzer.Result> results = new ArrayList<>(CrashReportAnalyzer.analyze(
                "Open J9 is not supported\njava.lang.OutOfMemoryError"));

        String message = new GameCrashReasonFormatter().format(
                new GameCrashAnalysis(results, Set.of()));

        assertTrue(message.startsWith(i18n("game.crash.reason.multiple")));
        assertTrue(message.contains(i18n("game.crash.reason.openj9")));
        assertTrue(message.contains(i18n("game.crash.reason.out_of_memory")));
    }

    /// Includes sorted stack keywords in the localized unknown-cause guidance.
    @Test
    void formatsUnknownCrashKeywords() {
        String message = new GameCrashReasonFormatter().format(
                new GameCrashAnalysis(List.of(), Set.of("zeta", "alpha")));

        assertEquals(i18n("game.crash.reason.stacktrace", "alpha, zeta"), message);
    }

    /// Formats a limited diagnosis through its solver localization key and immutable arguments.
    @Test
    void formatsLimitedLogDiagnosis() {
        AnalyzeResult<LogAnalyzable> result = new AnalyzeResult<>(
                new JREVersionAnalyzer(),
                ResultID.JRE_VERSION,
                new TextSolver(
                        "game.crash.reason.log.jre_version",
                        List.of(17, 8),
                        "Install Java 17."));

        String message = new GameCrashReasonFormatter().format(
                new GameCrashAnalysis(List.of(), List.of(result), Set.of()));

        assertEquals(i18n("game.crash.reason.log.jre_version", 17, 8), message);
    }

    /// Finds exactly one requested rule in analyzer output.
    ///
    /// @param log analyzer input
    /// @param rule requested rule
    /// @return requested analyzer result
    private static CrashReportAnalyzer.Result onlyResult(String log, CrashReportAnalyzer.Rule rule) {
        return CrashReportAnalyzer.analyze(log).stream()
                .filter(result -> result.rule() == rule)
                .findFirst()
                .orElseThrow();
    }
}
