// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.game.analyzer;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Identifies an explicit client-only class loading failure on a dedicated server.
@NotNullByDefault
public final class ClientModOnServerAnalyzer implements Analyzer<LogAnalyzable> {
    /// Forge invalid-distribution exception naming the dedicated-server side.
    private static final Pattern FORGE_INVALID_DISTRIBUTION = Pattern.compile(
            "(?m)^(?:Caused by: )?java\\.lang\\.RuntimeException: Attempted to load class "
                    + "(?<class>[A-Za-z0-9_.$/-]+) for invalid dist DEDICATED_SERVER[ \\t]*$");

    /// Fabric environment exception naming the server environment.
    private static final Pattern FABRIC_INVALID_ENVIRONMENT = Pattern.compile(
            "(?m)^(?:Caused by: )?java\\.lang\\.RuntimeException: Cannot load class "
                    + "(?<class>[A-Za-z0-9_.$/-]+) in environment type SERVER[ \\t]*$");

    /// A fatal launch or crash-report marker excluding isolated, recovered exception text.
    private static final Pattern FATAL_CONTEXT = Pattern.compile(
            "(?m)^(?:---- Minecraft Crash Report ----|Exception in thread \\\"main\\\"|"
                    + "[ \\t]*Failure message:|Description: Mod loading error)[^\\r\\n]*$");

    /// Requires an exact loader-side violation inside fatal crash context.
    ///
    /// @param input immutable launch and log snapshot
    /// @param results mutable diagnosis accumulator
    /// @return `BREAK_OTHER` after a verified match, otherwise `CONTINUE`
    @Override
    public ControlFlow analyze(LogAnalyzable input, List<AnalyzeResult<LogAnalyzable>> results) {
        Matcher fatalContext = FATAL_CONTEXT.matcher(input.logText());
        if (!fatalContext.find()) {
            return ControlFlow.CONTINUE;
        }

        @Nullable Matcher violation = findViolation(input.logText());
        if (violation == null) {
            return ControlFlow.CONTINUE;
        }

        String className = violation.group("class");
        results.add(new AnalyzeResult<>(
                this,
                ResultID.CLIENT_MOD_ON_SERVER,
                new TextSolver(
                        "game.crash.reason.log.client_mod_on_server",
                        List.of(className),
                        "Remove or disable the client-only mod that owns " + className
                                + " from the dedicated server instance."),
                List.of(violation.group(), fatalContext.group())));
        return ControlFlow.BREAK_OTHER;
    }

    /// Returns the first exact Forge or Fabric side violation.
    ///
    /// @param log complete launch log
    /// @return positioned matcher, or null when no supported loader signature exists
    private static @Nullable Matcher findViolation(String log) {
        Matcher forge = FORGE_INVALID_DISTRIBUTION.matcher(log);
        if (forge.find()) {
            return forge;
        }
        Matcher fabric = FABRIC_INVALID_ENVIRONMENT.matcher(log);
        return fabric.find() ? fabric : null;
    }
}
