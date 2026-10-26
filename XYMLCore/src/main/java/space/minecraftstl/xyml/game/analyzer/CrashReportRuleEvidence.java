// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
