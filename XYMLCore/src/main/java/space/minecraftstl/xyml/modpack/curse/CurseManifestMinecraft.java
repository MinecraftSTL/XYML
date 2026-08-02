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
