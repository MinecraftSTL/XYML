// SPDX-FileCopyrightText: Copyright (C) 2020 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.auth.yggdrasil;

import com.google.gson.JsonParseException;

import java.util.Map;

import com.google.gson.annotations.JsonAdapter;
import space.minecraftstl.xyml.util.Immutable;
import space.minecraftstl.xyml.util.StringUtils;
import space.minecraftstl.xyml.util.gson.Validation;
import org.jetbrains.annotations.Nullable;

/**
 *
 * @author huang
 */
@Immutable
public record User(String id, @JsonAdapter(PropertyMapSerializer.class) @Nullable Map<String, String> properties) implements Validation {

    public User(String id) {
        this(id, null);
    }

    @Override
    public void validate() throws JsonParseException {
        if (StringUtils.isBlank(id))
            throw new JsonParseException("User id cannot be empty.");
    }
}
