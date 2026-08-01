/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.setting;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.addon.mod.ModLoaderType;

/// Identifies the bundled icon resource associated with an instance.
@NotNullByDefault
public enum GameInstanceIconType {
    /// Default grass-block icon.
    DEFAULT("/assets/img/grass.png"),

    /// Grass-block icon.
    GRASS("/assets/img/grass.png"),
    /// Chest icon.
    CHEST("/assets/img/chest.png"),
    /// Chicken icon.
    CHICKEN("/assets/img/chicken.png"),
    /// Command-block icon.
    COMMAND("/assets/img/command.png"),
    /// OptiFine icon.
    OPTIFINE("/assets/img/optifine.png"),
    /// Crafting-table icon.
    CRAFT_TABLE("/assets/img/craft_table.png"),
    /// Fabric loader icon.
    FABRIC("/assets/img/fabric.png"),
    /// Forge loader icon.
    FORGE("/assets/img/forge.png"),
    /// NeoForge loader icon.
    NEO_FORGE("/assets/img/neoforge.png"),
    /// Furnace icon.
    FURNACE("/assets/img/furnace.png"),
    /// Quilt loader icon.
    QUILT("/assets/img/quilt.png"),
    /// April Fools icon.
    APRIL_FOOLS("/assets/img/april_fools.png"),
    /// Cleanroom loader icon.
    CLEANROOM("/assets/img/cleanroom.png"),
    /// Legacy Fabric loader icon.
    LEGACY_FABRIC("/assets/img/legacyfabric.png")
    ;

    // Please append new items at last

    /// Maps one Mod loader to its default icon type.
    public static GameInstanceIconType getIconType(ModLoaderType modLoaderType) {
        return switch (modLoaderType) {
            case FORGE -> GameInstanceIconType.FORGE;
            case NEO_FORGE -> GameInstanceIconType.NEO_FORGE;
            case FABRIC -> GameInstanceIconType.FABRIC;
            case QUILT -> GameInstanceIconType.QUILT;
            case LITE_LOADER -> GameInstanceIconType.CHICKEN;
            case CLEANROOM -> GameInstanceIconType.CLEANROOM;
            default -> GameInstanceIconType.COMMAND;
        };
    }

    /// Classpath resource path for this icon.
    private final String resourceUrl;

    /// Creates one icon type.
    GameInstanceIconType(String resourceUrl) {
        this.resourceUrl = resourceUrl;
    }

    /// Returns the classpath resource path for toolkit-specific image loading.
    public String resourcePath() {
        return resourceUrl;
    }
}
