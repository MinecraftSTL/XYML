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
import com.google.gson.JsonPrimitive;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;

/// Describes how an appearance chooses its seed color without exposing renderer-specific color objects.
@NotNullByDefault
public sealed interface ThemeColorSource
        permits ThemeColorSource.Default, ThemeColorSource.Custom, ThemeColorSource.Wallpaper {
    /// JSON member naming an object color source.
    String FIELD_SOURCE = "source";

    /// Shared launcher-default color source.
    ThemeColorSource DEFAULT = new Default();

    /// Creates a fixed color source.
    ///
    /// @param color fixed color
    /// @return custom source
    static ThemeColorSource custom(ThemeColor color) {
        return new Custom(color);
    }

    /// Creates a wallpaper-derived source.
    ///
    /// @return wallpaper source
    static ThemeColorSource wallpaper() {
        return new Wallpaper();
    }

    /// Parses the historical string or object representation.
    ///
    /// @param element manifest value
    /// @return parsed source
    /// @throws JsonParseException when the representation is unsupported
    static ThemeColorSource fromJson(JsonElement element) throws JsonParseException {
        Objects.requireNonNull(element, "element");
        if (element instanceof JsonPrimitive primitive && primitive.isString()) {
            String value = primitive.getAsString();
            if ("default".equals(value.trim().replace('-', '_').toLowerCase(Locale.ROOT))) {
                return DEFAULT;
            }
            @Nullable ThemeColor color = ThemeColor.of(value);
            if (color == null) {
                throw new JsonParseException("Invalid theme color: " + value);
            }
            return custom(color);
        }
        if (!(element instanceof JsonObject object)) {
            throw new JsonParseException("Theme color must be a string or object");
        }
        JsonElement sourceElement = object.get(FIELD_SOURCE);
        if (!(sourceElement instanceof JsonPrimitive sourcePrimitive) || !sourcePrimitive.isString()) {
            throw new JsonParseException("Theme color source must contain a string source field");
        }
        return switch (sourcePrimitive.getAsString().trim().replace('-', '_').toLowerCase(Locale.ROOT)) {
            case "default" -> DEFAULT;
            case "wallpaper" -> wallpaper();
            default -> throw new JsonParseException("Unsupported theme color source: " + sourcePrimitive);
        };
    }

    /// Converts the source to its manifest representation.
    ///
    /// @return JSON representation
    JsonElement toJsonElement();

    /// Returns a deterministic fallback before wallpaper pixels are available.
    ///
    /// @return fixed or launcher-default color
    ThemeColor resolveFallback();

    /// Launcher-default color source.
    @NotNullByDefault
    record Default() implements ThemeColorSource {
        /// Converts this source to JSON.
        @Override
        public JsonElement toJsonElement() {
            JsonObject object = new JsonObject();
            object.addProperty(FIELD_SOURCE, "default");
            return object;
        }

        /// Returns the launcher default color.
        @Override
        public ThemeColor resolveFallback() {
            return ThemeColor.DEFAULT;
        }
    }

    /// Fixed manifest color source.
    ///
    /// @param color fixed color
    @NotNullByDefault
    record Custom(ThemeColor color) implements ThemeColorSource {
        /// Validates the color.
        public Custom {
            Objects.requireNonNull(color, "color");
        }

        /// Converts this source to JSON.
        @Override
        public JsonElement toJsonElement() {
            return new JsonPrimitive(color.name());
        }

        /// Returns the fixed color.
        @Override
        public ThemeColor resolveFallback() {
            return color;
        }
    }

    /// Source whose final seed is extracted by the presentation layer from the effective wallpaper.
    @NotNullByDefault
    record Wallpaper() implements ThemeColorSource {
        /// Converts this source to JSON.
        @Override
        public JsonElement toJsonElement() {
            JsonObject object = new JsonObject();
            object.addProperty(FIELD_SOURCE, "wallpaper");
            return object;
        }

        /// Returns the deterministic pre-extraction fallback.
        @Override
        public ThemeColor resolveFallback() {
            return ThemeColor.DEFAULT;
        }
    }
}
