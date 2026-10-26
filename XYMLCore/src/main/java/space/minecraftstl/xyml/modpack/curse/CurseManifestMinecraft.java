// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.modpack.curse;

import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.util.StringUtils;
import space.minecraftstl.xyml.util.gson.JsonSerializable;
import space.minecraftstl.xyml.util.gson.Validation;

import java.util.List;

/// Describes the Minecraft version and immutable mod-loader list in a CurseForge manifest.
///
/// @param gameVersion target Minecraft version
/// @param modLoaders ordered non-null mod-loader descriptors
/// @author huangyuhui
@JsonSerializable
@NotNullByDefault
public record CurseManifestMinecraft(@SerializedName("version") String gameVersion,
                                     @SerializedName("modLoaders") @Unmodifiable List<CurseManifestModLoader> modLoaders) implements Validation {

    /// Creates a manifest Minecraft descriptor with an owned immutable loader snapshot.
    ///
    /// @param gameVersion target Minecraft version
    /// @param modLoaders loader descriptors copied and checked for null elements
    public CurseManifestMinecraft(String gameVersion, List<CurseManifestModLoader> modLoaders) {
        this.gameVersion = gameVersion;
        this.modLoaders = List.copyOf(modLoaders);
    }

    /// Returns the immutable mod-loader snapshot.
    ///
    /// @return immutable ordered mod-loader descriptors
    @Override
    public @Unmodifiable List<CurseManifestModLoader> modLoaders() {
        return modLoaders;
    }

    /// {@inheritDoc}
    @Override
    public void validate() throws JsonParseException {
        if (StringUtils.isBlank(gameVersion))
            throw new JsonParseException("CurseForge Manifest.gameVersion cannot be blank.");
    }

}
