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

import java.util.Objects;

/// Immutable values submitted by the world-details form for one serialized background write.
///
/// A nullable setting means that the selected world's NBT layout did not expose that field and it
/// must remain untouched. The world name is always required for every readable world.
///
/// @param worldName non-blank stored world name
/// @param settings editable world settings with unsupported values absent
/// @param player optional player scalar update when single-player data exists
@NotNullByDefault
public record WorldDetailsUpdate(
        String worldName,
        WorldCatalogDetails.WorldSettings settings,
        @Nullable PlayerUpdate player) {
    /// Trims and validates submitted values before background work starts.
    public WorldDetailsUpdate {
        worldName = Objects.requireNonNull(worldName, "worldName").trim();
        if (worldName.isBlank()) {
            throw new IllegalArgumentException("worldName must not be blank");
        }
        settings = Objects.requireNonNull(settings, "settings");
    }

    /// Optional editable single-player values.
    ///
    /// @param gameMode selected game mode, or `null` when unsupported
    /// @param health health value, or `null` when unsupported
    /// @param foodLevel hunger value, or `null` when unsupported
    /// @param foodSaturation saturation value, or `null` when unsupported
    /// @param xpLevel experience level, or `null` when unsupported
    @NotNullByDefault
    public record PlayerUpdate(
            @Nullable WorldCatalogDetails.GameMode gameMode,
            @Nullable Float health,
            @Nullable Integer foodLevel,
            @Nullable Float foodSaturation,
            @Nullable Integer xpLevel) {
        /// Rejects non-finite floating-point values that Minecraft cannot use predictably.
        public PlayerUpdate {
            if (health != null && !Float.isFinite(health)) {
                throw new IllegalArgumentException("health must be finite when present");
            }
            if (foodSaturation != null && !Float.isFinite(foodSaturation)) {
                throw new IllegalArgumentException("foodSaturation must be finite when present");
            }
        }
    }
}
