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

/// Toolkit-neutral background source contributed by a theme appearance.
@NotNullByDefault
public sealed interface ThemeBackground permits ThemeBackground.Default, ThemeBackground.Builtin,
        ThemeBackground.Image, ThemeBackground.Paint, ThemeBackground.ThemeColorFill {
    /// JSON member naming the source kind.
    String FIELD_TYPE = "type";

    /// JSON member naming a built-in wallpaper.
    String FIELD_ID = "id";

    /// JSON member naming a theme-pack image asset.
    String FIELD_PATH = "path";

    /// JSON member containing a toolkit-neutral CSS-compatible paint string.
    String FIELD_PAINT = "paint";

    /// Adds concrete source members to one JSON object.
    ///
    /// @param object target object
    void addToJsonObject(JsonObject object);

    /// Converts this source to JSON.
    ///
    /// @return source object
    default JsonObject toJsonObject() {
        JsonObject object = new JsonObject();
        addToJsonObject(object);
        return object;
    }

    /// Parses a historical source object.
    ///
    /// @param object source object
    /// @return parsed source, or `null` when only inherited settings are present
    /// @throws JsonParseException when known source members are malformed
    static @Nullable ThemeBackground fromJson(JsonObject object) throws JsonParseException {
        @Nullable String type = readString(object, FIELD_TYPE);
        @Nullable String id = readString(object, FIELD_ID);
        @Nullable String path = readString(object, FIELD_PATH);
        @Nullable String paint = readString(object, FIELD_PAINT);
        if (type == null) {
            int count = (id == null ? 0 : 1) + (path == null ? 0 : 1) + (paint == null ? 0 : 1);
            if (count > 1) {
                throw new JsonParseException("Theme background without type has multiple sources");
            }
            if (id != null) {
                return new Builtin(id);
            }
            if (path != null) {
                return new Image(path);
            }
            return paint != null ? new Paint(paint) : null;
        }
        return switch (type.trim().replace('-', '_').toLowerCase(Locale.ROOT)) {
            case "default" -> new Default();
            case "builtin" -> new Builtin(id);
            case "image" -> new Image(path);
            case "paint" -> new Paint(paint);
            case "theme_color" -> new ThemeColorFill();
            default -> throw new JsonParseException("Unsupported theme background type: " + type);
        };
    }

    /// Reads one optional string field.
    private static @Nullable String readString(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null) {
            return null;
        }
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isString()) {
            throw new JsonParseException("Theme background field must be a string: " + field);
        }
        return primitive.getAsString();
    }

    /// Validates one required string field.
    private static String requireNonBlank(@Nullable String value, String field) {
        if (value == null || value.isBlank()) {
            throw new JsonParseException("Theme background field is missing or blank: " + field);
        }
        return value.trim();
    }

    /// Source delegating to launcher-default background selection.
    @NotNullByDefault
    record Default() implements ThemeBackground {
        /// Adds this source to JSON.
        @Override
        public void addToJsonObject(JsonObject object) {
            object.addProperty(FIELD_TYPE, "default");
        }
    }

    /// Source selecting a bundled wallpaper.
    ///
    /// @param id wallpaper identifier, or `null` for the fallback wallpaper
    @NotNullByDefault
    record Builtin(@Nullable String id) implements ThemeBackground {
        /// Creates a fallback built-in source.
        public Builtin() {
            this(null);
        }

        /// Validates the optional identifier.
        public Builtin {
            if (id != null) {
                id = requireNonBlank(id, FIELD_ID);
            }
        }

        /// Adds this source to JSON.
        @Override
        public void addToJsonObject(JsonObject object) {
            object.addProperty(FIELD_TYPE, "builtin");
            if (id != null) {
                object.addProperty(FIELD_ID, id);
            }
        }
    }

    /// Source selecting an image inside the same theme pack.
    ///
    /// @param path normalized asset entry name
    @NotNullByDefault
    record Image(String path) implements ThemeBackground {
        /// Validates and normalizes the image path.
        public Image {
            path = ThemePackAsset.normalizeEntryName(requireNonBlank(path, FIELD_PATH));
        }

        /// Adds this source to JSON.
        @Override
        public void addToJsonObject(JsonObject object) {
            object.addProperty(FIELD_TYPE, "image");
            object.addProperty(FIELD_PATH, path);
        }
    }

    /// Source carrying a CSS-compatible paint expression for later toolkit adaptation.
    ///
    /// @param paint serialized paint expression
    @NotNullByDefault
    record Paint(String paint) implements ThemeBackground {
        /// Validates the paint expression without interpreting it.
        public Paint {
            paint = requireNonBlank(paint, FIELD_PAINT);
            if (paint.length() > 4_096) {
                throw new IllegalArgumentException("Theme paint value is too long");
            }
        }

        /// Adds this source to JSON.
        @Override
        public void addToJsonObject(JsonObject object) {
            object.addProperty(FIELD_TYPE, "paint");
            object.addProperty(FIELD_PAINT, paint);
        }
    }

    /// Source following the active theme surface color.
    @NotNullByDefault
    record ThemeColorFill() implements ThemeBackground {
        /// Adds this source to JSON.
        @Override
        public void addToJsonObject(JsonObject object) {
            object.addProperty(FIELD_TYPE, "theme_color");
        }
    }
}
