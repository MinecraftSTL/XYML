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
import space.minecraftstl.xyml.game.analyzer.FabricMissingDependencyAnalyzer;
import space.minecraftstl.xyml.game.analyzer.ForgeMissingDependencyAnalyzer;
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

    /// Escapes log-derived unknown keywords before they enter the localized HTML document.
    @Test
    void escapesUnknownCrashKeywords() {
        String message = new GameCrashReasonFormatter().format(
                new GameCrashAnalysis(List.of(), Set.of("<script>alert(1)</script>")));

        assertTrue(message.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
        assertTrue(!message.contains("<script>alert(1)</script>"));
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

    /// Formats both loader-specific dependency diagnoses through the shared Swing presentation path.
    @Test
    void formatsMissingDependencyDiagnoses() {
        AnalyzeResult<LogAnalyzable> forgeResult = new AnalyzeResult<>(
                new ForgeMissingDependencyAnalyzer(),
                ResultID.FORGE_MISSING_DEPENDENCY,
                new TextSolver(
                        "game.crash.reason.log.forge_missing_dependency",
                        List.of("vampirism (required by werewolves)"),
                        "Forge reported a missing dependency."));
        AnalyzeResult<LogAnalyzable> fabricResult = new AnalyzeResult<>(
                new FabricMissingDependencyAnalyzer(),
                ResultID.FABRIC_MISSING_DEPENDENCY,
                new TextSolver(
                        "game.crash.reason.log.fabric_missing_dependency",
                        List.of("fabric-api (required by sodium-extra)"),
                        "Fabric reported a missing dependency."));

        String message = new GameCrashReasonFormatter().format(
                new GameCrashAnalysis(List.of(), List.of(forgeResult, fabricResult), Set.of()));

        assertTrue(message.contains(i18n(
                "game.crash.reason.log.forge_missing_dependency",
                "vampirism (required by werewolves)")));
        assertTrue(message.contains(i18n(
                "game.crash.reason.log.fabric_missing_dependency",
                "fabric-api (required by sodium-extra)")));
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
