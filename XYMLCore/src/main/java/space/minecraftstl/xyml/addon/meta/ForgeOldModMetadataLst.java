// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.addon.meta;

import space.minecraftstl.xyml.util.gson.JsonSerializable;

import java.util.List;

/// @author Glavo
@JsonSerializable
public record ForgeOldModMetadataLst(
        int modListVersion,
        List<ForgeOldModMetadata> modList) {
}
