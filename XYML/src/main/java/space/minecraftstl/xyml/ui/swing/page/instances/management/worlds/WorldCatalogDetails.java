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
package space.minecraftstl.xyml.ui.swing.page.instances.management.worlds;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/// Immutable viewport-loaded detail snapshot for one readable Minecraft world.
///
/// The icon is stored as an immutable Base64 PNG string so no mutable image or byte array crosses
/// the background-loading boundary. Optional values are absent only when the corresponding NBT
/// layout is unsupported or missing.
///
/// @param levelDataPath exact `level.dat` or `special_level.dat` source
/// @param iconPngBase64 normalized icon PNG, or `null` when the world has no readable icon
/// @param seed recorded world seed, or `null` when unavailable
/// @param worldSpawn formatted world spawn, or `null` when unavailable
/// @param playedTimeTicks recorded played time in game ticks, or `null` when unavailable
/// @param settings editable world settings with per-field availability
/// @param player optional single-player data summary
@NotNullByDefault
public record WorldCatalogDetails(
        Path levelDataPath,
        @Nullable String iconPngBase64,
        @Nullable Long seed,
        @Nullable String worldSpawn,
        @Nullable Long playedTimeTicks,
        WorldSettings settings,
        @Nullable PlayerSummary player) {
    /// Normalizes paths and validates immutable optional display values.
    public WorldCatalogDetails {
        levelDataPath = Objects.requireNonNull(levelDataPath, "levelDataPath")
                .toAbsolutePath()
                .normalize();
        iconPngBase64 = optionalNonBlank(iconPngBase64, "iconPngBase64");
        worldSpawn = optionalNonBlank(worldSpawn, "worldSpawn");
        settings = Objects.requireNonNull(settings, "settings");
    }

    /// Reports whether this snapshot contains a decoded world icon.
    ///
    /// @return whether a PNG icon is present
    public boolean hasIcon() {
        return iconPngBase64 != null;
    }

    /// Immutable editable world-setting values.
    ///
    /// @param allowCheats command and cheat permission, or `null` when unsupported
    /// @param generateStructures structure generation, or `null` when unsupported
    /// @param difficulty selected difficulty, or `null` when unsupported
    /// @param difficultyLocked difficulty lock, or `null` when unsupported
    @NotNullByDefault
    public record WorldSettings(
            @Nullable Boolean allowCheats,
            @Nullable Boolean generateStructures,
            @Nullable Difficulty difficulty,
            @Nullable Boolean difficultyLocked) {
    }

    /// Immutable optional single-player summary and editable scalar values.
    ///
    /// @param location current player position, or `null` when unavailable
    /// @param lastDeathLocation last recorded death position, or `null` when unavailable
    /// @param spawn bed or respawn-anchor position, or `null` when unavailable
    /// @param gameMode current game mode, or `null` when unsupported
    /// @param health health value, or `null` when unsupported
    /// @param foodLevel hunger value, or `null` when unsupported
    /// @param foodSaturation saturation value, or `null` when unsupported
    /// @param xpLevel experience level, or `null` when unsupported
    @NotNullByDefault
    public record PlayerSummary(
            @Nullable String location,
            @Nullable String lastDeathLocation,
            @Nullable String spawn,
            @Nullable GameMode gameMode,
            @Nullable Float health,
            @Nullable Integer foodLevel,
            @Nullable Float foodSaturation,
            @Nullable Integer xpLevel) {
        /// Validates optional formatted positions.
        public PlayerSummary {
            location = optionalNonBlank(location, "location");
            lastDeathLocation = optionalNonBlank(lastDeathLocation, "lastDeathLocation");
            spawn = optionalNonBlank(spawn, "spawn");
        }
    }

    /// Supported Minecraft difficulty values in their stable NBT order.
    @NotNullByDefault
    public enum Difficulty {
        /// Peaceful difficulty.
        PEACEFUL,
        /// Easy difficulty.
        EASY,
        /// Normal difficulty.
        NORMAL,
        /// Hard difficulty.
        HARD;

        /// Returns the modern lowercase NBT value.
        ///
        /// @return lowercase stable NBT name
        public String tagValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /// Supported single-player game modes, including the combined hardcore state.
    @NotNullByDefault
    public enum GameMode {
        /// Survival mode.
        SURVIVAL,
        /// Creative mode.
        CREATIVE,
        /// Adventure mode.
        ADVENTURE,
        /// Spectator mode.
        SPECTATOR,
        /// Hardcore survival mode.
        HARDCORE
    }

    /// Rejects blank optional text while preserving explicit absence.
    ///
    /// @param value candidate optional value
    /// @param name logical value name
    /// @return validated value or `null`
    private static @Nullable String optionalNonBlank(@Nullable String value, String name) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank when present");
        }
        return value;
    }
}
