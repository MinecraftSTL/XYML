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
import org.jetbrains.annotations.Unmodifiable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Identifies Fabric's hard dependency resolution failures across old and current loader formats.
@NotNullByDefault
public final class FabricMissingDependencyAnalyzer implements Analyzer<LogAnalyzable> {
    /// Old Fabric's single-line missing dependency diagnostic.
    private static final Pattern LEGACY_SINGLE_DEPENDENCY = Pattern.compile(
            "(?im)^.*?ModResolutionException:\\s+Could not find required mod:\\s*"
                    + "(?<requester>[A-Za-z0-9][A-Za-z0-9_.-]*)\\s+requires\\s+\\{"
                    + "(?<dependency>[A-Za-z0-9][A-Za-z0-9_.-]*)\\s+@\\s+[^}\\r\\n]+}");

    /// Old Fabric's multi-entry dependency listing.
    private static final Pattern LEGACY_LIST_DEPENDENCY = Pattern.compile(
            "(?im)^[ \\t]*-\\s*Mod\\s+(?<requester>[A-Za-z0-9][A-Za-z0-9_.-]*)"
                    + "\\s+depends on mod\\s+\\{(?<dependency>[A-Za-z0-9][A-Za-z0-9_.-]*)"
                    + "\\s+@\\s+[^}\\r\\n]+},\\s*which is missing![ \\t]*$");

    /// Current Fabric's human-readable unmet dependency listing.
    private static final Pattern MODERN_LIST_DEPENDENCY = Pattern.compile(
            "(?im)^[ \\t]*-\\s*Mod\\s+'[^']*'\\s*\\((?<requester>"
                    + "[A-Za-z0-9][A-Za-z0-9_.-]*)\\)[^\\r\\n]*?\\brequires\\s+"
                    + "(?:mod\\s+)?(?:version\\s+[^\\r\\n]+?\\s+of\\s+)?"
                    + "(?<dependency>[A-Za-z0-9][A-Za-z0-9_.-]*),\\s*which is missing![ \\t]*$");

    /// Current Fabric's machine-readable hard dependency summary.
    private static final Pattern HARD_DEPENDENCY = Pattern.compile(
            "(?i)\\bHARD_DEP\\s+(?<requester>[A-Za-z0-9][A-Za-z0-9_.-]*)\\s+[^\\r\\n{]+"
                    + "\\{depends\\s+(?<dependency>[A-Za-z0-9][A-Za-z0-9_.-]*)\\s+@");

    /// Current Fabric's machine-readable fix line, used when localized text hides the English listing.
    private static final Pattern FIX_LINE = Pattern.compile(
            "(?im)^.*?\\bFix:\\s+add\\s+\\[(?<dependencies>[^\\r\\n]*?)\\]"
                    + "(?=,\\s*remove\\s*\\[|[ \\t]*$)");

    /// One dependency token inside the machine-readable fix line.
    private static final Pattern FIXED_DEPENDENCY = Pattern.compile(
            "(?i)\\badd:(?<dependency>[A-Za-z0-9][A-Za-z0-9_.-]*)\\b");

    /// Markers proving that Fabric failed dependency resolution rather than merely warning.
    private static final @Unmodifiable Set<String> HARD_FAILURE_MARKERS = Set.of(
            "ModResolutionException:",
            "Incompatible mod set!",
            "Incompatible mods found!",
            "Mod resolution encountered an incompatible mod set!");

    /// Dependencies that are environment requirements, not missing mod packages.
    private static final @Unmodifiable Set<String> NON_MOD_DEPENDENCIES = Set.of("java", "minecraft");

    /// Produces a diagnosis only after a Fabric hard-resolution failure names a missing dependency.
    ///
    /// @param input immutable launch and log snapshot
    /// @param results mutable diagnosis accumulator
    /// @return `BREAK_OTHER` after a verified match, otherwise `CONTINUE`
    @Override
    public ControlFlow analyze(LogAnalyzable input, List<AnalyzeResult<LogAnalyzable>> results) {
        String log = input.logText();
        if (!isFabricResolutionFailure(log)) {
            return ControlFlow.CONTINUE;
        }

        Set<String> dependencies = new LinkedHashSet<>();
        Set<String> dependencyIds = new LinkedHashSet<>();
        Set<String> evidence = new LinkedHashSet<>();
        collect(LEGACY_SINGLE_DEPENDENCY.matcher(log), dependencies, dependencyIds, evidence);
        collect(LEGACY_LIST_DEPENDENCY.matcher(log), dependencies, dependencyIds, evidence);
        collect(MODERN_LIST_DEPENDENCY.matcher(log), dependencies, dependencyIds, evidence);
        collect(HARD_DEPENDENCY.matcher(log), dependencies, dependencyIds, evidence);
        if (dependencies.isEmpty() && log.contains("Fix: add")) {
            collectFixedDependencies(log, dependencies, dependencyIds, evidence);
        }
        if (dependencies.isEmpty()) {
            return ControlFlow.CONTINUE;
        }

        String summary = String.join(", ", dependencies);
        results.add(new AnalyzeResult<>(
                this,
                ResultID.FABRIC_MISSING_DEPENDENCY,
                Solver.ofMissingDependencySearch(
                        input,
                        List.copyOf(dependencyIds),
                        "game.crash.reason.log.fabric_missing_dependency",
                        List.of(summary),
                        "Fabric reported missing required mod dependencies: " + summary),
                List.copyOf(evidence)));
        return ControlFlow.BREAK_OTHER;
    }

    /// Confirms both Fabric ownership and a hard dependency-resolution failure.
    ///
    /// @param log complete launch log
    /// @return true when the log is a Fabric dependency failure
    private static boolean isFabricResolutionFailure(String log) {
        if (!log.contains("net.fabricmc.loader")) {
            return false;
        }
        for (String marker : HARD_FAILURE_MARKERS) {
            if (log.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /// Adds all structured matches to the stable summary set.
    ///
    /// @param matcher structured dependency matcher
    /// @param dependencies mutable summary set
    /// @param dependencyIds mutable raw dependency-ID set
    /// @param evidence mutable exact-evidence set
    private static void collect(
            Matcher matcher,
            Set<String> dependencies,
            Set<String> dependencyIds,
            Set<String> evidence) {
        while (matcher.find()) {
            if (addDependency(
                    dependencies,
                    dependencyIds,
                    matcher.group("requester"),
                    matcher.group("dependency"))) {
                evidence.add(matcher.group());
            }
        }
    }

    /// Adds dependency IDs from a localized loader fix list without trusting arbitrary log text.
    ///
    /// @param log complete launch log
    /// @param dependencies mutable summary set
    /// @param dependencyIds mutable raw dependency-ID set
    /// @param evidence mutable exact-evidence set
    private static void collectFixedDependencies(
            String log,
            Set<String> dependencies,
            Set<String> dependencyIds,
            Set<String> evidence) {
        Matcher fixLine = FIX_LINE.matcher(log);
        while (fixLine.find()) {
            Matcher dependency = FIXED_DEPENDENCY.matcher(fixLine.group("dependencies"));
            boolean retained = false;
            while (dependency.find()) {
                if (addDependency(
                        dependencies,
                        dependencyIds,
                        null,
                        dependency.group("dependency"))) {
                    retained = true;
                }
            }
            if (retained) {
                evidence.add(fixLine.group());
            }
        }
    }

    /// Adds one dependency after excluding environment-only requirements.
    ///
    /// @param dependencies mutable summary set
    /// @param dependencyIds mutable raw dependency-ID set
    /// @param requester requesting mod ID, or null when only a fix-list ID is available
    /// @param dependency required mod ID
    /// @return true when the dependency is a retained mod-package requirement
    private static boolean addDependency(
            Set<String> dependencies,
            Set<String> dependencyIds,
            @Nullable String requester,
            String dependency) {
        if (NON_MOD_DEPENDENCIES.contains(dependency.toLowerCase(Locale.ROOT))) {
            return false;
        }
        dependencyIds.add(dependency);
        if (requester == null || requester.equals(dependency)) {
            dependencies.add(dependency);
        } else {
            dependencies.add(dependency + " (required by " + requester + ")");
        }
        return true;
    }
}
