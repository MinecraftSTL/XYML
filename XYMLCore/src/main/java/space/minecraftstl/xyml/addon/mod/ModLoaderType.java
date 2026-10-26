// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
