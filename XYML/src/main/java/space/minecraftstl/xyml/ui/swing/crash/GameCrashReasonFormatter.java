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
import space.minecraftstl.xyml.game.CrashReportAnalyzer;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Converts analyzer rules into localized explanations used by the Swing crash window.
@NotNullByDefault
final class GameCrashReasonFormatter {
    /// Fabric dependency notation used inside resolution error groups.
    private static final Pattern FABRIC_MOD_ID = Pattern.compile("\\{(?<modid>.*?) @ (?<version>.*?)}");

    /// Formats one complete analysis with stable spacing and unknown-crash keyword guidance.
    ///
    /// @param analysis merged technical diagnosis
    /// @return localized reason text that may contain trusted i18n HTML links
    String format(GameCrashAnalysis analysis) {
        Objects.requireNonNull(analysis, "analysis");
        if (analysis.results().isEmpty()) {
            if (!analysis.keywords().isEmpty()) {
                String keywords = String.join(", ", analysis.keywords());
                LOG.info("Crash reason unknown, but some log keywords have been found: " + keywords);
                return i18n("game.crash.reason.stacktrace", keywords);
            }
            LOG.info("Crash reason unknown");
            return i18n("game.crash.reason.unknown");
        }

        LOG.info("Number of reasons: " + analysis.results().size());
        StringBuilder reasons = new StringBuilder();
        if (analysis.results().size() > 1) {
            reasons.append(i18n("game.crash.reason.multiple"));
        }
        for (CrashReportAnalyzer.Result result : analysis.results()) {
            String message = formatResult(result);
            LOG.info("Crash cause: " + result.rule() + ": " + message);
            reasons.append(message).append("\n\n");
        }
        return reasons.toString().stripTrailing();
    }

    /// Formats one matched rule, including the established cases for Java, Fabric, and OptiFine.
    ///
    /// @param result analyzer result whose matcher contains named groups
    /// @return localized reason for that rule
    private static String formatResult(CrashReportAnalyzer.Result result) {
        return switch (result.rule()) {
            case TOO_OLD_JAVA -> i18n(
                    "game.crash.reason.too_old_java",
                    CrashReportAnalyzer.getJavaVersionFromMajorVersion(
                            Integer.parseInt(result.matcher().group("expected"))));
            case MOD_RESOLUTION_CONFLICT, MOD_RESOLUTION_MISSING, MOD_RESOLUTION_COLLECTION -> i18n(
                    "game.crash.reason." + result.rule().name().toLowerCase(Locale.ROOT),
                    translateFabricModId(result.matcher().group("sourcemod")),
                    parseFabricModId(result.matcher().group("destmod")),
                    parseFabricModId(result.matcher().group("destmod")));
            case MOD_RESOLUTION_MISSING_MINECRAFT -> i18n(
                    "game.crash.reason." + result.rule().name().toLowerCase(Locale.ROOT),
                    translateFabricModId(result.matcher().group("mod")),
                    result.matcher().group("version"));
            case MOD_FOREST_OPTIFINE,
                 TWILIGHT_FOREST_OPTIFINE,
                 PERFORMANT_FOREST_OPTIFINE,
                 JADE_FOREST_OPTIFINE,
                 NEOFORGE_FOREST_OPTIFINE -> i18n("game.crash.reason.mod", "OptiFine");
            default -> i18n(
                    "game.crash.reason." + result.rule().name().toLowerCase(Locale.ROOT),
                    Arrays.stream(result.rule().getGroupNames())
                            .map(groupName -> result.matcher().group(groupName))
                            .toArray());
        };
    }

    /// Converts built-in Fabric identifiers to their user-facing names.
    ///
    /// @param modName Fabric mod identifier or display name
    /// @return stable user-facing name
    private static String translateFabricModId(String modName) {
        return switch (modName) {
            case "fabricloader" -> "Fabric";
            case "fabric" -> "Fabric API";
            case "minecraft" -> "Minecraft";
            default -> modName;
        };
    }

    /// Formats Fabric's `{mod @ version}` dependency notation for localized reason templates.
    ///
    /// @param modName raw dependency notation or mod identifier
    /// @return localized dependency label
    private static String parseFabricModId(String modName) {
        Matcher matcher = FABRIC_MOD_ID.matcher(modName);
        if (!matcher.find()) {
            return translateFabricModId(modName);
        }

        String modId = matcher.group("modid");
        String version = matcher.group("version");
        if ("[*]".equals(version)) {
            return i18n(
                    "game.crash.reason.mod_resolution_mod_version.any",
                    translateFabricModId(modId));
        }
        return i18n(
                "game.crash.reason.mod_resolution_mod_version",
                translateFabricModId(modId),
                version);
    }
}
