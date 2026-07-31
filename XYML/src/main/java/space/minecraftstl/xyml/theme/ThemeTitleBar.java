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
package space.minecraftstl.xyml.theme;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Inheritable title-bar settings.
///
/// @param transparent whether the title area is transparent, or `null` when inherited
@NotNullByDefault
public record ThemeTitleBar(@Nullable Boolean transparent) {
    /// JSON member naming transparency.
    private static final String FIELD_TRANSPARENT = "transparent";

    /// Parses one title-bar patch.
    ///
    /// @param object manifest object
    /// @return parsed patch
    static ThemeTitleBar fromJson(JsonObject object) {
        JsonElement element = object.get(FIELD_TRANSPARENT);
        @Nullable Boolean transparent = element instanceof JsonPrimitive primitive && primitive.isBoolean()
                ? primitive.getAsBoolean()
                : null;
        return new ThemeTitleBar(transparent);
    }

    /// Converts this patch to JSON.
    ///
    /// @return title-bar object
    public JsonObject toJsonObject() {
        JsonObject object = new JsonObject();
        if (transparent != null) {
            object.addProperty(FIELD_TRANSPARENT, transparent);
        }
        return object;
    }

    /// Returns whether this patch has no concrete value.
    ///
    /// @return `true` when inherited
    public boolean isEmpty() {
        return transparent == null;
    }

    /// Applies a newer patch over this patch.
    ///
    /// @param patch newer patch
    /// @return merged patch
    public ThemeTitleBar merge(ThemeTitleBar patch) {
        Objects.requireNonNull(patch, "patch");
        return new ThemeTitleBar(patch.transparent != null ? patch.transparent : transparent);
    }
}
