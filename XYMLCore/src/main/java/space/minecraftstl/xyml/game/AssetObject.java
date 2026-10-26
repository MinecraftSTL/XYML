// SPDX-FileCopyrightText: Copyright (C) 2020 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.game;

import space.minecraftstl.xyml.util.DigestUtils;
import space.minecraftstl.xyml.util.StringUtils;
import space.minecraftstl.xyml.util.gson.JsonSerializable;

import java.io.IOException;
import java.nio.file.Path;

/// @author huangyuhui
@JsonSerializable
public record AssetObject(String hash, long size) {
    public AssetObject {
        if (StringUtils.isBlank(hash) || hash.length() < 2) {
            throw new IllegalArgumentException("Invalid asset hash: " + hash);
        }
    }

    public String getLocation() {
        return hash.substring(0, 2) + "/" + hash;
    }

    public boolean validateChecksum(Path file, boolean defaultValue) throws IOException {
        if (hash == null) return defaultValue;
        return DigestUtils.digestToString("SHA-1", file).equalsIgnoreCase(hash);
    }
}
