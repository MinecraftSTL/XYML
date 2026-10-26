// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.game.analyzer;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Identifies the well-known legacy LaunchWrapper failure fixed by Legacy Java Fixer.
@NotNullByDefault
public final class LegacyJavaFixerAnalyzer implements Analyzer<LogAnalyzable> {
    /// Legacy game versions for which the compatibility mod is applicable.
    private static final Pattern LEGACY_GAME_VERSION = Pattern.compile("^1\\.(?:6|7)(?:\\.|$)");

    /// Exact Java 8 collection failure at the top of the legacy launch.
    private static final Pattern CONCURRENT_MODIFICATION = Pattern.compile(
            "(?m)^(?:Exception in thread \\\"main\\\" )?java\\.util\\.ConcurrentModificationException[ \\t]*$");

    /// LaunchWrapper frame tying the collection failure to the legacy boot sequence.
    private static final Pattern LAUNCH_WRAPPER_FRAME = Pattern.compile(
            "(?m)^[ \\t]*at net\\.minecraft\\.launchwrapper\\.Launch\\.launch\\(Launch\\.java:\\d+\\)[ \\t]*$");

    /// Correlates the exact LaunchWrapper stack with Java 8 and a legacy Minecraft version.
    ///
    /// @param input immutable launch and log snapshot
    /// @param results mutable diagnosis accumulator
    /// @return `BREAK_OTHER` after a verified match, otherwise `CONTINUE`
    @Override
    public ControlFlow analyze(LogAnalyzable input, List<AnalyzeResult<LogAnalyzable>> results) {
        @Nullable Integer javaVersion = input.currentJavaVersion();
        @Nullable String gameVersion = input.gameVersion();
        if (javaVersion == null
                || javaVersion != 8
                || gameVersion == null
                || !LEGACY_GAME_VERSION.matcher(gameVersion).find()) {
            return ControlFlow.CONTINUE;
        }

        Matcher failure = CONCURRENT_MODIFICATION.matcher(input.logText());
        Matcher launchFrame = LAUNCH_WRAPPER_FRAME.matcher(input.logText());
        if (!failure.find() || !launchFrame.find()) {
            return ControlFlow.CONTINUE;
        }

        String fallback = "Install Legacy Java Fixer for this legacy Minecraft instance, then launch it with Java 8.";
        results.add(new AnalyzeResult<>(
                this,
                ResultID.LEGACY_JAVA_FIXER,
                Solver.ofMissingDependencySearch(
                        input,
                        List.of("legacyjavafixer"),
                        "game.crash.reason.log.legacy_java_fixer",
                        List.of(),
                        fallback),
                List.of(failure.group(), launchFrame.group())));
        return ControlFlow.BREAK_OTHER;
    }
}
