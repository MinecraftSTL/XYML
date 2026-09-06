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
package space.minecraftstl.xyml.addon.mod;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Identifies a supported mod loader and its launch-process environment marker.
@NotNullByDefault
public enum ModLoaderType {
    /// Unknown or unsupported loader without an environment marker.
    UNKNOWN(null),
    /// Minecraft Forge.
    FORGE("INST_FORGE"),
    /// Cleanroom.
    CLEANROOM("INST_CLEANROOM"),
    /// NeoForge.
    NEO_FORGE("INST_NEOFORGE"),
    /// Fabric.
    FABRIC("INST_FABRIC"),
    /// Quilt.
    QUILT("INST_QUILT"),
    /// LiteLoader.
    LITE_LOADER("INST_LITELOADER"),
    /// Legacy Fabric.
    LEGACY_FABRIC("INST_LEGACYFABRIC");

    /// Environment-variable name exposed to hooks, or `null` when no marker exists.
    private final @Nullable String envVarName;

    /// Creates one loader kind with its optional environment marker.
    ///
    /// @param envVarName environment-variable name, or `null` when the loader has no marker
    ModLoaderType(@Nullable String envVarName) {
        this.envVarName = envVarName;
    }

    /// Returns the environment-variable name exported for this loader.
    ///
    /// @return marker name, or `null` when this loader must not export one
    public @Nullable String getEnvVarName() {
        return envVarName;
    }
}
