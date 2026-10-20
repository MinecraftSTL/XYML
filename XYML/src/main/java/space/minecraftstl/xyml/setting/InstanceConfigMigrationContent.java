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
package space.minecraftstl.xyml.setting;

import org.jetbrains.annotations.NotNullByDefault;

/// Identifies one fixed Minecraft configuration path eligible for instance migration.
@NotNullByDefault
public enum InstanceConfigMigrationContent {
    /// Main Minecraft options file.
    OPTIONS("options.txt", false),

    /// Installed resource-pack directory.
    RESOURCE_PACKS("resourcepacks", true),

    /// Installed shader-pack directory.
    SHADER_PACKS("shaderpacks", true),

    /// Local schematic directory.
    SCHEMATICS("schematics", true),

    /// Saved creative-mode hotbars.
    HOTBARS("hotbar.nbt", false),

    /// Multiplayer server list.
    SERVERS("servers.dat", false),

    /// Mod configuration directory.
    MOD_CONFIG("config", true);

    /// Fixed path relative to a Minecraft running directory.
    private final String relativePath;

    /// Whether this content kind represents a directory tree.
    private final boolean directory;

    /// Creates one fixed migration content declaration.
    ///
    /// @param relativePath fixed relative path
    /// @param directory whether the path is a directory
    InstanceConfigMigrationContent(String relativePath, boolean directory) {
        this.relativePath = relativePath;
        this.directory = directory;
    }

    /// Returns the fixed path relative to a Minecraft running directory.
    ///
    /// @return non-empty single-root relative path
    public String relativePath() {
        return relativePath;
    }

    /// Returns whether this content kind represents a directory tree.
    ///
    /// @return true for directory content
    public boolean directory() {
        return directory;
    }
}
