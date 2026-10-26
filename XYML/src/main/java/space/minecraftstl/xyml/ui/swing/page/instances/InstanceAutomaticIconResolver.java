// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
