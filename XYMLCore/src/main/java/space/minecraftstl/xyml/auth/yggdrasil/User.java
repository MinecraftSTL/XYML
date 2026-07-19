/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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
