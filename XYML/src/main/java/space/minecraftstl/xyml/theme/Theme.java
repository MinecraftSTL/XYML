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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.util.i18n.I18n;
import space.minecraftstl.xyml.util.i18n.LocalizedText;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// One selectable theme whose matching overrides are applied in declaration order.
///
/// @param id stable identifier, or `null` for an unnamed single-theme pack
/// @param name localized name, or `null` for an unnamed single-theme pack
/// @param authors theme-specific authors
/// @param description optional localized description
/// @param icon optional normalized asset icon path
/// @param appearance default appearance patch
/// @param overrides conditional patches in declaration order
@NotNullByDefault
public record Theme(
        @Nullable String id,
        @Nullable LocalizedText name,
        @Unmodifiable List<ThemePackAuthor> authors,
        @Nullable LocalizedText description,
        @Nullable String icon,
        ThemeAppearance appearance,
        @Unmodifiable List<ThemeOverride> overrides) {
    /// Maximum overrides accepted from one theme.
    private static final int MAXIMUM_OVERRIDE_COUNT = 128;

    /// Validates and copies theme values.
    public Theme {
        if (id != null) {
            id = ThemePackManifest.requireThemeId(id);
        }
        if (name != null && name.mayBeEmpty()) {
            throw new IllegalArgumentException("Theme name cannot be empty");
        }
        authors = List.copyOf(authors);
        if (description != null && description.mayBeEmpty()) {
            throw new IllegalArgumentException("Theme description cannot be empty");
        }
        if (icon != null) {
            icon = ThemePackAsset.normalizeEntryName(icon);
        }
        Objects.requireNonNull(appearance, "appearance");
        overrides = List.copyOf(overrides);
        if (overrides.size() > MAXIMUM_OVERRIDE_COUNT) {
            throw new IllegalArgumentException("Theme has too many overrides");
        }
    }

    /// Parses one theme object.
    ///
    /// @param object theme object
    /// @param requireIdentity whether ID and name are mandatory
    /// @return parsed theme
    static Theme fromJson(JsonObject object, boolean requireIdentity) {
        @Nullable String id = optionalString(object, "id");
        try {
            if (id != null) {
                id = ThemePackManifest.requireThemeId(id);
            }
        } catch (IllegalArgumentException ignored) {
            id = null;
        }
        @Nullable LocalizedText name = optionalLocalizedText(object.get("name"));
        if (requireIdentity && (id == null || name == null)) {
            throw new JsonParseException("Theme ID and name are required in multi-theme packs");
        }
        @Nullable LocalizedText description = optionalLocalizedText(object.get("description"));
        @Nullable String icon = optionalAsset(object, "icon");
        List<ThemeOverride> overrides = readOverrides(object.get("overrides"));
        return new Theme(
                id,
                name,
                ThemePackAuthor.parseAuthors(object.get("authors")),
                description,
                icon,
                ThemeAppearance.fromJson(object),
                overrides);
    }

    /// Returns the localized name, when declared.
    ///
    /// @return display name or `null`
    public @Nullable String displayName() {
        return name != null ? name.getText(I18n.getLocale().getCandidateLocales()) : null;
    }

    /// Returns the localized description, when declared.
    ///
    /// @return display description or `null`
    public @Nullable String displayDescription() {
        return description != null ? description.getText(I18n.getLocale().getCandidateLocales()) : null;
    }

    /// Applies all matching overrides in declaration order.
    ///
    /// @param context resolution context
    /// @return merged appearance
    public ThemeAppearance resolve(ThemeResolveContext context) {
        ThemeAppearance resolved = appearance;
        for (ThemeOverride override : overrides) {
            if (override.matches(context)) {
                resolved = resolved.merge(override.appearance());
            }
        }
        return resolved;
    }

    /// Converts this theme to JSON.
    ///
    /// @return theme object
    public JsonObject toJsonObject() {
        JsonObject object = appearance.toJsonObject();
        if (id != null) {
            object.addProperty("id", id);
        }
        if (name != null) {
            object.add("name", name.toJsonElement());
        }
        if (!authors.isEmpty()) {
            object.add("authors", ThemePackAuthor.toJson(authors));
        }
        if (description != null) {
            object.add("description", description.toJsonElement());
        }
        if (icon != null) {
            object.addProperty("icon", icon);
        }
        if (!overrides.isEmpty()) {
            JsonArray array = new JsonArray();
            overrides.forEach(override -> array.add(override.toJsonObject()));
            object.add("overrides", array);
        }
        return object;
    }

    /// Parses a bounded override array while ignoring malformed optional entries.
    private static @Unmodifiable List<ThemeOverride> readOverrides(@Nullable JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return List.of();
        }
        if (!(element instanceof JsonArray array) || array.size() > MAXIMUM_OVERRIDE_COUNT) {
            throw new JsonParseException("Theme overrides must be a bounded array");
        }
        List<ThemeOverride> overrides = new ArrayList<>(array.size());
        for (JsonElement item : array) {
            try {
                overrides.add(ThemeOverride.fromJson(item));
            } catch (JsonParseException | IllegalArgumentException ignored) {
                // One optional invalid override must not disable the remaining valid overrides.
            }
        }
        return List.copyOf(overrides);
    }

    /// Reads an optional string member.
    private static @Nullable String optionalString(JsonObject object, String field) {
        JsonElement element = object.get(field);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                ? element.getAsString()
                : null;
    }

    /// Reads a valid optional localized text value.
    private static @Nullable LocalizedText optionalLocalizedText(@Nullable JsonElement element) {
        try {
            @Nullable LocalizedText text = LocalizedText.fromJson(element);
            return text == null || text.mayBeEmpty() ? null : text;
        } catch (JsonParseException | IllegalArgumentException ignored) {
            return null;
        }
    }

    /// Reads a valid optional asset path.
    private static @Nullable String optionalAsset(JsonObject object, String field) {
        @Nullable String value = optionalString(object, field);
        if (value == null) {
            return null;
        }
        try {
            return ThemePackAsset.normalizeEntryName(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
