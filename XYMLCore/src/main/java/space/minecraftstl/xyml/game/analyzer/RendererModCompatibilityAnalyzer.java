// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.game.analyzer;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Identifies explicit Fabric resolution failures involving Indium, Iris, or Iris Flywheel compatibility.
@NotNullByDefault
public final class RendererModCompatibilityAnalyzer implements Analyzer<LogAnalyzable> {
    /// Fabric's fatal incompatibility headings, optionally preceded by a logger prefix.
    private static final Pattern INCOMPATIBLE_HEADING = Pattern.compile(
            "(?m)^[^\\r\\n]*(?:Incompatible mods found!|Incompatible mod set!|Mod resolution failed!)[ \\t]*$");

    /// A named renderer mod participating directly in a requires, incompatibility, or conflict sentence.
    private static final Pattern COMPATIBILITY_FAILURE = Pattern.compile(
            "(?im)^[ \\t*-]*Mod ['\\\"]?(?<name>Iris Flywheel Compat|IrisFlywheelCompat|Indium|Iris)"
                    + "['\\\"]?[ \\t]*(?:\\((?<id>indium|iris|irisflw|iris[-_]flywheel[-_]compat)\\))?"
                    + "[^\\r\\n]*(?:requires?|is incompatible with|conflicts with)[^\\r\\n]*$");

    /// Fabric's explicit proposed-solution line for installing or replacing a renderer compatibility mod.
    private static final Pattern COMPATIBILITY_SOLUTION = Pattern.compile(
            "(?im)^[ \\t*-]*(?:Install|Replace|Update|Downgrade)(?: mod)? ['\\\"]?"
                    + "(?<name>Iris Flywheel Compat|IrisFlywheelCompat|Indium|Iris)['\\\"]?"
                    + "(?:[ \\t]*\\((?<id>indium|iris|irisflw|iris[-_]flywheel[-_]compat)\\))?[^\\r\\n]*$");

    /// Requires a fatal Fabric heading and at least one explicit compatibility statement or proposed solution.
    ///
    /// @param input immutable launch and log snapshot
    /// @param results mutable diagnosis accumulator
    /// @return `BREAK_OTHER` after a verified match, otherwise `CONTINUE`
    @Override
    public ControlFlow analyze(LogAnalyzable input, List<AnalyzeResult<LogAnalyzable>> results) {
        Matcher heading = INCOMPATIBLE_HEADING.matcher(input.logText());
        if (!heading.find()) {
            return ControlFlow.CONTINUE;
        }

        Set<String> dependencyIds = new LinkedHashSet<>();
        Set<String> evidence = new LinkedHashSet<>();
        collect(COMPATIBILITY_FAILURE.matcher(input.logText()), dependencyIds, evidence);
        collect(COMPATIBILITY_SOLUTION.matcher(input.logText()), dependencyIds, evidence);
        if (dependencyIds.isEmpty()) {
            return ControlFlow.CONTINUE;
        }

        String summary = String.join(", ", dependencyIds);
        evidence.add(heading.group());
        results.add(new AnalyzeResult<>(
                this,
                ResultID.RENDERER_MOD_COMPATIBILITY,
                Solver.ofMissingDependencySearch(
                        input,
                        List.copyOf(dependencyIds),
                        "game.crash.reason.log.renderer_mod_compatibility",
                        List.of(summary),
                        "Install or select compatible renderer mods for: " + summary + "."),
                List.copyOf(evidence)));
        return ControlFlow.BREAK_OTHER;
    }

    /// Collects stable renderer identifiers and exact matching evidence lines.
    ///
    /// @param matcher compatibility statement matcher
    /// @param dependencyIds mutable first-seen renderer identifier set
    /// @param evidence mutable first-seen evidence set
    private static void collect(Matcher matcher, Set<String> dependencyIds, Set<String> evidence) {
        while (matcher.find()) {
            @Nullable String explicitId = matcher.group("id");
            dependencyIds.add(explicitId == null
                    ? identifierForName(matcher.group("name"))
                    : normalizeIdentifier(explicitId));
            evidence.add(matcher.group());
        }
    }

    /// Maps an accepted display name to the provider identifier used by mod search.
    ///
    /// @param name accepted renderer mod name
    /// @return stable provider identifier
    private static String identifierForName(String name) {
        String normalized = name.replace(" ", "").toLowerCase(Locale.ROOT);
        return normalized.startsWith("irisflywheel") ? "irisflw" : normalized;
    }

    /// Normalizes accepted Iris Flywheel identifier spellings to the provider identifier.
    ///
    /// @param identifier accepted identifier spelling
    /// @return stable provider identifier
    private static String normalizeIdentifier(String identifier) {
        String normalized = identifier.toLowerCase(Locale.ROOT);
        return normalized.startsWith("iris-") || normalized.startsWith("iris_") ? "irisflw" : normalized;
    }
}
