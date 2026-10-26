// SPDX-FileCopyrightText: Copyright (C) 2020 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.download.game;

import com.google.gson.annotations.SerializedName;
import space.minecraftstl.xyml.util.Immutable;
import space.minecraftstl.xyml.util.gson.JsonSerializable;

/**
 * @author huangyuhui
 */
@Immutable
@JsonSerializable
public record GameRemoteLatestVersions(
        @SerializedName("snapshot") String snapshot,
        @SerializedName("release") String release) {
}
