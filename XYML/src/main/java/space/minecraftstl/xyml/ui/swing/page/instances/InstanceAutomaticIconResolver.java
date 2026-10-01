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
import space.minecraftstl.xyml.game.GameComponentAnalyzer;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.setting.GameInstanceIconType;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;
import space.minecraftstl.xyml.game.GameComponentType;

import java.util.Optional;
import java.util.Objects;

/// Resolves the automatic bundled icon shared by instance-list and management presentations.
@NotNullByDefault
public final class InstanceAutomaticIconResolver {
    /// Prevents utility-class construction.
    private InstanceAutomaticIconResolver() {
    }

    /// Analyzes one resolved manifest and selects its loader-first automatic icon.
    ///
    /// @param resolved resolved XYML instance manifest
    /// @param gameVersion detected Minecraft version, or null when unavailable
    /// @return automatically selected bundled icon type
    public static GameInstanceIconType resolve(
            GameInstanceManifest.Resolved resolved,
            @Nullable String gameVersion) {
        GameComponentAnalyzer analyzer = GameComponentAnalyzer.analyze(
                Objects.requireNonNull(resolved, "resolved"),
                GameVersionNumber.asGameVersion(Optional.ofNullable(gameVersion)));
        return resolve(analyzer, gameVersion);
    }

    /// Selects the loader-first bundled icon from one completed library analysis.
    ///
    /// @param analyzer analyzed instance libraries and patches
    /// @param gameVersion detected Minecraft version, or null when unavailable
    /// @return automatically selected bundled icon type
    static GameInstanceIconType resolve(
            GameComponentAnalyzer analyzer,
            @Nullable String gameVersion) {
        GameComponentAnalyzer validatedAnalyzer = Objects.requireNonNull(analyzer, "analyzer");
        if (validatedAnalyzer.has(GameComponentType.FABRIC)) {
            return GameInstanceIconType.FABRIC;
        }
        if (validatedAnalyzer.has(GameComponentType.QUILT)) {
            return GameInstanceIconType.QUILT;
        }
        if (validatedAnalyzer.has(GameComponentType.LEGACY_FABRIC)) {
            return GameInstanceIconType.LEGACY_FABRIC;
        }
        if (validatedAnalyzer.has(GameComponentType.NEO_FORGE)) {
            return GameInstanceIconType.NEO_FORGE;
        }
        if (validatedAnalyzer.has(GameComponentType.FORGE)) {
            return GameInstanceIconType.FORGE;
        }
        if (validatedAnalyzer.has(GameComponentType.CLEANROOM)) {
            return GameInstanceIconType.CLEANROOM;
        }
        if (validatedAnalyzer.has(GameComponentType.LITELOADER)) {
            return GameInstanceIconType.CHICKEN;
        }
        if (validatedAnalyzer.has(GameComponentType.OPTIFINE)) {
            return GameInstanceIconType.OPTIFINE;
        }
        if (gameVersion == null || gameVersion.isBlank()) {
            return GameInstanceIconType.GRASS;
        }

        GameVersionNumber versionNumber = GameVersionNumber.asGameVersion(gameVersion);
        if (versionNumber.isAprilFools()) {
            return GameInstanceIconType.APRIL_FOOLS;
        }
        if (versionNumber instanceof GameVersionNumber.LegacySnapshot
                || versionNumber instanceof GameVersionNumber.Release release
                && release.getEaType() != GameVersionNumber.Release.ReleaseType.GA) {
            return GameInstanceIconType.COMMAND;
        }
        if (versionNumber instanceof GameVersionNumber.Old) {
            return GameInstanceIconType.CRAFT_TABLE;
        }
        return GameInstanceIconType.GRASS;
    }
}
