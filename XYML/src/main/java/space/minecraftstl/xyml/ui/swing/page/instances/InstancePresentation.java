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
package space.minecraftstl.xyml.ui.swing.page.instances;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.download.LibraryAnalyzer;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.setting.GameInstanceIconType;
import space.minecraftstl.xyml.util.i18n.I18n;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;

import java.util.Objects;
import java.util.regex.Pattern;

/// Immutable display metadata derived from one resolved XYML instance manifest.
///
/// @param detail localized game and loader version detail
/// @param defaultIconType automatically selected bundled icon type
@NotNullByDefault
record InstancePresentation(String detail, GameInstanceIconType defaultIconType) {
    /// Validates derived presentation metadata.
    InstancePresentation {
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(defaultIconType, "defaultIconType");
    }

    /// Derives row detail and the automatic icon from one resolved manifest analysis.
    ///
    /// @param resolved resolved XYML instance manifest
    /// @param gameVersion detected Minecraft version, or null when unavailable
    /// @param unknownVersionDetail localized fallback for an unavailable Minecraft version
    /// @return immutable instance presentation metadata
    static InstancePresentation resolve(
            GameInstanceManifest.Resolved resolved,
            @Nullable String gameVersion,
            String unknownVersionDetail) {
        LibraryAnalyzer analyzer = LibraryAnalyzer.analyze(
                Objects.requireNonNull(resolved, "resolved"),
                gameVersion);
        return new InstancePresentation(
                describe(analyzer, gameVersion, unknownVersionDetail),
                resolveDefaultIconType(analyzer, gameVersion));
    }

    /// Builds localized game and recognized-library version detail in stable library-type order.
    ///
    /// @param analyzer analyzed instance libraries and patches
    /// @param gameVersion detected Minecraft version, or null when unavailable
    /// @param unknownVersionDetail localized fallback for an unavailable Minecraft version
    /// @return localized row detail
    private static String describe(
            LibraryAnalyzer analyzer,
            @Nullable String gameVersion,
            String unknownVersionDetail) {
        String fallback = Objects.requireNonNull(unknownVersionDetail, "unknownVersionDetail");
        StringBuilder detail = new StringBuilder(
                gameVersion == null || gameVersion.isBlank() ? fallback : gameVersion);
        for (LibraryAnalyzer.LibraryType type : LibraryAnalyzer.LibraryType.values()) {
            if (type == LibraryAnalyzer.LibraryType.MINECRAFT || !analyzer.has(type)) {
                continue;
            }
            String translationKey = "install.installer." + type.getPatchId();
            if (!I18n.hasKey(translationKey)) {
                continue;
            }
            detail.append(", ").append(I18n.i18n(translationKey));
            analyzer.getVersion(type)
                    .map(version -> version.replaceAll(
                            "(?i)" + Pattern.quote(type.getPatchId()),
                            "").trim())
                    .filter(version -> !version.isEmpty())
                    .ifPresent(version -> detail.append(' ').append(version));
        }
        return detail.toString();
    }

    /// Selects the loader-first bundled icon with a game-version fallback.
    ///
    /// @param analyzer analyzed instance libraries and patches
    /// @param gameVersion detected Minecraft version, or null when unavailable
    /// @return automatically selected bundled icon type
    private static GameInstanceIconType resolveDefaultIconType(
            LibraryAnalyzer analyzer,
            @Nullable String gameVersion) {
        if (analyzer.has(LibraryAnalyzer.LibraryType.FABRIC)) {
            return GameInstanceIconType.FABRIC;
        }
        if (analyzer.has(LibraryAnalyzer.LibraryType.QUILT)) {
            return GameInstanceIconType.QUILT;
        }
        if (analyzer.has(LibraryAnalyzer.LibraryType.LEGACY_FABRIC)) {
            return GameInstanceIconType.LEGACY_FABRIC;
        }
        if (analyzer.has(LibraryAnalyzer.LibraryType.NEO_FORGE)) {
            return GameInstanceIconType.NEO_FORGE;
        }
        if (analyzer.has(LibraryAnalyzer.LibraryType.FORGE)) {
            return GameInstanceIconType.FORGE;
        }
        if (analyzer.has(LibraryAnalyzer.LibraryType.CLEANROOM)) {
            return GameInstanceIconType.CLEANROOM;
        }
        if (analyzer.has(LibraryAnalyzer.LibraryType.LITELOADER)) {
            return GameInstanceIconType.CHICKEN;
        }
        if (analyzer.has(LibraryAnalyzer.LibraryType.OPTIFINE)) {
            return GameInstanceIconType.OPTIFINE;
        }
        if (gameVersion == null || gameVersion.isBlank()) {
            return GameInstanceIconType.GRASS;
        }

        GameVersionNumber versionNumber = GameVersionNumber.asGameVersion(gameVersion);
        if (versionNumber.isAprilFools()) {
            return GameInstanceIconType.APRIL_FOOLS;
        }
        if (versionNumber instanceof GameVersionNumber.LegacySnapshot) {
            return GameInstanceIconType.COMMAND;
        }
        if (versionNumber instanceof GameVersionNumber.Old) {
            return GameInstanceIconType.CRAFT_TABLE;
        }
        return GameInstanceIconType.GRASS;
    }
}
