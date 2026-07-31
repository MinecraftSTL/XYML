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
import com.google.gson.JsonParseException;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Conditional appearance patch applied in declaration order.
///
/// @param condition condition required for the patch
/// @param appearance appearance values applied when matched
@NotNullByDefault
public record ThemeOverride(ThemeCondition condition, ThemeAppearance appearance) {
    /// JSON member naming the condition object.
    private static final String FIELD_CONDITION = "condition";

    /// Validates a non-empty override.
    public ThemeOverride {
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(appearance, "appearance");
        if (appearance.isEmpty()) {
            throw new IllegalArgumentException("Theme override has no appearance fields");
        }
    }

    /// Parses one override object.
    ///
    /// @param element manifest value
    /// @return parsed override
    static ThemeOverride fromJson(JsonElement element) {
        if (!(element instanceof JsonObject object)) {
            throw new JsonParseException("Theme override must be an object");
        }
        if (!(object.get(FIELD_CONDITION) instanceof JsonObject conditionObject)) {
            throw new JsonParseException("Theme override must define an object condition");
        }
        return new ThemeOverride(ThemeCondition.fromJson(conditionObject), ThemeAppearance.fromJson(object));
    }

    /// Tests this override against one context.
    ///
    /// @param context resolution context
    /// @return `true` when matched
    public boolean matches(ThemeResolveContext context) {
        return condition.matches(context);
    }

    /// Converts this override to JSON.
    ///
    /// @return override object
    public JsonObject toJsonObject() {
        JsonObject object = appearance.toJsonObject();
        object.add(FIELD_CONDITION, condition.toJsonObject());
        return object;
    }
}
