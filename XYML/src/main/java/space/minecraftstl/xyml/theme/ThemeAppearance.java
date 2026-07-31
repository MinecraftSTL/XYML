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

/// Optional appearance values contributed by a theme or one conditional override.
///
/// @param color color source, or `null` when inherited
/// @param brightness controlled brightness, or `null` when inherited
/// @param colorStyle palette style, or `null` when inherited
/// @param contrast contrast, or `null` when inherited
/// @param background background patch, or `null` when inherited
/// @param titleBar title-bar patch, or `null` when inherited
@NotNullByDefault
public record ThemeAppearance(
        @Nullable ThemeColorSource color,
        @Nullable ThemeBrightness brightness,
        @Nullable ThemeColorStyle colorStyle,
        @Nullable ThemeContrast contrast,
        @Nullable ThemeBackgroundSettings background,
        @Nullable ThemeTitleBar titleBar) {
    /// JSON member naming the color source.
    private static final String FIELD_COLOR = "color";
    /// JSON member naming brightness.
    private static final String FIELD_BRIGHTNESS = "brightness";
    /// JSON member naming color style.
    private static final String FIELD_COLOR_STYLE = "colorStyle";
    /// JSON member naming contrast.
    private static final String FIELD_CONTRAST = "contrast";
    /// JSON member naming background settings.
    private static final String FIELD_BACKGROUND = "background";
    /// JSON member naming title-bar settings.
    private static final String FIELD_TITLE_BAR = "titleBar";
    /// Normalizes empty nested patches to inherited values.
    public ThemeAppearance {
        if (background != null && background.isEmpty()) {
            background = null;
        }
        if (titleBar != null && titleBar.isEmpty()) {
            titleBar = null;
        }
    }

    /// Parses known appearance members while ignoring malformed optional fields.
    ///
    /// @param object manifest object
    /// @return parsed appearance patch
    static ThemeAppearance fromJson(JsonObject object) {
        return new ThemeAppearance(
                readColor(object),
                readBrightness(object),
                readColorStyle(object),
                readContrast(object),
                readBackground(object),
                readTitleBar(object));
    }

    /// Returns whether every appearance value is inherited.
    ///
    /// @return `true` for an empty patch
    public boolean isEmpty() {
        return color == null && brightness == null && colorStyle == null && contrast == null
                && background == null && titleBar == null;
    }

    /// Applies a newer appearance patch over this appearance.
    ///
    /// @param patch newer patch
    /// @return merged appearance
    public ThemeAppearance merge(ThemeAppearance patch) {
        Objects.requireNonNull(patch, "patch");
        return new ThemeAppearance(
                patch.color != null ? patch.color : color,
                patch.brightness != null ? patch.brightness : brightness,
                patch.colorStyle != null ? patch.colorStyle : colorStyle,
                patch.contrast != null ? patch.contrast : contrast,
                background != null && patch.background != null
                        ? background.merge(patch.background)
                        : patch.background != null ? patch.background : background,
                titleBar != null && patch.titleBar != null
                        ? titleBar.merge(patch.titleBar)
                        : patch.titleBar != null ? patch.titleBar : titleBar);
    }

    /// Resolves renderer-independent concrete values using context brightness as the fallback.
    ///
    /// @param context resolution context
    /// @return concrete theme values
    public ResolvedTheme toResolvedTheme(ThemeResolveContext context) {
        Objects.requireNonNull(context, "context");
        return new ResolvedTheme(
                color != null ? color.resolveFallback() : ResolvedTheme.DEFAULT.primaryColorSeed(),
                brightness != null ? brightness : context.brightness(),
                colorStyle != null ? colorStyle : ResolvedTheme.DEFAULT.colorStyle(),
                contrast != null ? contrast : ThemeContrast.STANDARD);
    }

    /// Converts this patch to JSON.
    ///
    /// @return appearance object
    public JsonObject toJsonObject() {
        JsonObject object = new JsonObject();
        if (color != null) {
            object.add(FIELD_COLOR, color.toJsonElement());
        }
        if (brightness != null) {
            object.addProperty(FIELD_BRIGHTNESS, brightness.serializedName());
        }
        if (colorStyle != null) {
            object.addProperty(FIELD_COLOR_STYLE, colorStyle.serializedName());
        }
        addContrast(object);
        if (background != null) {
            object.add(FIELD_BACKGROUND, background.toJsonObject());
        }
        if (titleBar != null) {
            object.add(FIELD_TITLE_BAR, titleBar.toJsonObject());
        }
        return object;
    }

    /// Adds the canonical contrast form when one is present.
    private void addContrast(JsonObject object) {
        if (contrast == null) {
            return;
        }
        if (contrast.equals(ThemeContrast.LOW)) {
            object.addProperty(FIELD_CONTRAST, "low");
        } else if (contrast.equals(ThemeContrast.STANDARD)) {
            object.addProperty(FIELD_CONTRAST, "standard");
        } else if (contrast.equals(ThemeContrast.MEDIUM)) {
            object.addProperty(FIELD_CONTRAST, "medium");
        } else if (contrast.equals(ThemeContrast.HIGH)) {
            object.addProperty(FIELD_CONTRAST, "high");
        } else {
            object.addProperty(FIELD_CONTRAST, contrast.value());
        }
    }

    /// Reads an optional color source.
    private static @Nullable ThemeColorSource readColor(JsonObject object) {
        JsonElement element = object.get(FIELD_COLOR);
        if (element == null) {
            return null;
        }
        try {
            return ThemeColorSource.fromJson(element);
        } catch (JsonParseException | IllegalArgumentException ignored) {
            return null;
        }
    }

    /// Reads optional brightness.
    private static @Nullable ThemeBrightness readBrightness(JsonObject object) {
        @Nullable String value = readString(object, FIELD_BRIGHTNESS);
        if (value == null) {
            return null;
        }
        try {
            return ThemeBrightness.parse(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /// Reads optional palette style.
    private static @Nullable ThemeColorStyle readColorStyle(JsonObject object) {
        @Nullable String value = readString(object, FIELD_COLOR_STYLE);
        if (value == null) {
            return null;
        }
        try {
            return ThemeColorStyle.parse(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /// Reads optional contrast from a preset or number.
    private static @Nullable ThemeContrast readContrast(JsonObject object) {
        JsonElement element = object.get(FIELD_CONTRAST);
        if (!(element instanceof JsonPrimitive primitive)) {
            return null;
        }
        try {
            if (primitive.isNumber()) {
                return new ThemeContrast(primitive.getAsDouble());
            }
            if (!primitive.isString()) {
                return null;
            }
            String value = primitive.getAsString().trim().toLowerCase(Locale.ROOT);
            return switch (value) {
                case "low" -> ThemeContrast.LOW;
                case "standard" -> ThemeContrast.STANDARD;
                case "medium" -> ThemeContrast.MEDIUM;
                case "high" -> ThemeContrast.HIGH;
                default -> new ThemeContrast(Double.parseDouble(value));
            };
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /// Reads an optional nested background patch.
    private static @Nullable ThemeBackgroundSettings readBackground(JsonObject object) {
        JsonElement element = object.get(FIELD_BACKGROUND);
        if (!(element instanceof JsonObject backgroundObject)) {
            return null;
        }
        try {
            return ThemeBackgroundSettings.fromJson(backgroundObject);
        } catch (JsonParseException | IllegalArgumentException ignored) {
            return null;
        }
    }

    /// Reads an optional nested title-bar patch.
    private static @Nullable ThemeTitleBar readTitleBar(JsonObject object) {
        JsonElement element = object.get(FIELD_TITLE_BAR);
        return element instanceof JsonObject titleBarObject ? ThemeTitleBar.fromJson(titleBarObject) : null;
    }

    /// Reads an optional string.
    private static @Nullable String readString(JsonObject object, String field) {
        JsonElement element = object.get(field);
        return element instanceof JsonPrimitive primitive && primitive.isString()
                ? primitive.getAsString()
                : null;
    }

}
