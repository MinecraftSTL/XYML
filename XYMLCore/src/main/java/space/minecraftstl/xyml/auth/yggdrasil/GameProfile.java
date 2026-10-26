// SPDX-FileCopyrightText: Copyright (C) 2020 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.auth.yggdrasil;

import java.util.Objects;
import java.util.UUID;

import space.minecraftstl.xyml.util.Immutable;
import space.minecraftstl.xyml.util.gson.UnhyphenatedUUIDTypeAdapter;
import space.minecraftstl.xyml.util.gson.Validation;

import com.google.gson.JsonParseException;
import com.google.gson.annotations.JsonAdapter;

/**
 * @author huangyuhui
 */
@Immutable
public class GameProfile implements Validation {

    @JsonAdapter(UnhyphenatedUUIDTypeAdapter.class)
    private final UUID id;

    private final String name;

    public GameProfile(UUID id, String name) {
        this.id = Objects.requireNonNull(id);
        this.name = Objects.requireNonNull(name);
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    @Override
    public void validate() throws JsonParseException {
        Validation.requireNonNull(id, "Game profile id cannot be null");
        Validation.requireNonNull(name, "Game profile name cannot be null");
    }
}
