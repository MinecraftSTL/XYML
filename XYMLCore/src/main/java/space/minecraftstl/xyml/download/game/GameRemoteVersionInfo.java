// SPDX-FileCopyrightText: Copyright (C) 2020 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.download.game;

import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import space.minecraftstl.xyml.game.ReleaseType;
import space.minecraftstl.xyml.util.StringUtils;
import space.minecraftstl.xyml.util.gson.JsonSerializable;
import space.minecraftstl.xyml.util.gson.Validation;

import java.time.Instant;

/**
 *
 * @author huangyuhui
 */
@JsonSerializable
public record GameRemoteVersionInfo(
        @SerializedName("id") String gameVersion,
        @SerializedName("time") Instant time,
        @SerializedName("releaseTime") Instant releaseTime,
        @SerializedName("type") ReleaseType type,
        @SerializedName("url") String url) implements Validation {

    @Override
    public void validate() throws JsonParseException {
        if (StringUtils.isBlank(gameVersion))
            throw new JsonParseException("GameRemoteVersion id cannot be blank");
        if (StringUtils.isBlank(url))
            throw new JsonParseException("GameRemoteVersion url cannot be blank");
    }
}
