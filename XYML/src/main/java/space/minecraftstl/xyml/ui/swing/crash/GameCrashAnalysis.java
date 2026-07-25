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
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.CrashReportAnalyzer;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/// Immutable merged diagnosis produced from captured output and `logs/latest.log`.
@NotNullByDefault
final class GameCrashAnalysis {
    /// Detected rules in declaration order with at most one result per rule.
    private final @Unmodifiable List<CrashReportAnalyzer.Result> results;

    /// Sorted immutable stack-trace keywords used when no rule matches.
    private final @Unmodifiable Set<String> keywords;

    /// Creates one merged diagnosis snapshot.
    ///
    /// @param results detected rules in declaration order
    /// @param keywords stack-trace keywords for unknown crashes
    GameCrashAnalysis(
            List<CrashReportAnalyzer.Result> results,
            Set<String> keywords) {
        EnumMap<CrashReportAnalyzer.Rule, CrashReportAnalyzer.Result> byRule =
                new EnumMap<>(CrashReportAnalyzer.Rule.class);
        for (CrashReportAnalyzer.Result result : Objects.requireNonNull(results, "results")) {
            byRule.put(result.rule(), result);
        }
        this.results = List.copyOf(byRule.values());
        this.keywords = Collections.unmodifiableSet(
                new LinkedHashSet<>(new TreeSet<>(Objects.requireNonNull(keywords, "keywords"))));
    }

    /// Returns detected rules in stable declaration order.
    ///
    /// @return immutable detected-rule snapshot
    @Unmodifiable List<CrashReportAnalyzer.Result> results() {
        return results;
    }

    /// Returns immutable stack-trace keywords.
    ///
    /// @return immutable keyword snapshot
    @Unmodifiable Set<String> keywords() {
        return keywords;
    }
}
