// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.modpack.curse;

import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import space.minecraftstl.xyml.util.Immutable;
import space.minecraftstl.xyml.util.StringUtils;
import space.minecraftstl.xyml.util.gson.Validation;

/**
 *
 * @author huangyuhui
 */
@Immutable
public record CurseManifestModLoader(@SerializedName("id") String id,
                                     @SerializedName("primary") boolean primary) implements Validation {
    public CurseManifestModLoader() {
        this("", false);
    }

    @Override
    public void validate() throws JsonParseException {
        if (StringUtils.isBlank(id))
            throw new JsonParseException("Curse Forge modpack manifest Mod loader id cannot be blank.");
    }

}
