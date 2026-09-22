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

import java.util.EnumMap;
import java.util.Map;

/// Adapts established crash-report rules as evidence without duplicating their regular expressions.
@NotNullByDefault
final class CrashReportRuleEvidence {
    /// Prevents construction of this static utility class.
    private CrashReportRuleEvidence() {
    }

    /// Finds the first established result whose rule is accepted.
    ///
    /// @param log complete launch log
    /// @param firstRule first accepted rule
    /// @param additionalRules additional accepted rules
    /// @return matching established result, or null when none matches
    static @Nullable CrashReportAnalyzer.Result find(
            String log,
            CrashReportAnalyzer.Rule firstRule,
            CrashReportAnalyzer.Rule... additionalRules) {
        Map<CrashReportAnalyzer.Rule, CrashReportAnalyzer.Result> results =
                new EnumMap<>(CrashReportAnalyzer.Rule.class);
        for (CrashReportAnalyzer.Result result : CrashReportAnalyzer.analyze(log)) {
            results.put(result.rule(), result);
        }
        @Nullable CrashReportAnalyzer.Result firstResult = results.get(firstRule);
        if (firstResult != null) {
            return firstResult;
        }
        for (CrashReportAnalyzer.Rule rule : additionalRules) {
            @Nullable CrashReportAnalyzer.Result result = results.get(rule);
            if (result != null) {
                return result;
            }
        }
        return null;
    }
}
