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
import com.google.gson.JsonPrimitive;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.util.i18n.I18n;
import space.minecraftstl.xyml.util.i18n.LocalizedText;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Localized author metadata declared by a theme pack or individual theme.
///
/// @param name localized author name
@NotNullByDefault
public record ThemePackAuthor(LocalizedText name) {
    /// Maximum authors retained from one manifest location.
    private static final int MAXIMUM_AUTHOR_COUNT = 64;

    /// Validates non-empty author text.
    public ThemePackAuthor {
        Objects.requireNonNull(name, "name");
        if (name.mayBeEmpty()) {
            throw new IllegalArgumentException("Theme-pack author name cannot be empty");
        }
    }

    /// Parses a bounded author array, ignoring individual malformed optional entries.
    ///
    /// @param element authors value
    /// @return immutable authors
    static @Unmodifiable List<ThemePackAuthor> parseAuthors(@Nullable JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return List.of();
        }
        if (!(element instanceof JsonArray array) || array.size() > MAXIMUM_AUTHOR_COUNT) {
            return List.of();
        }
        List<ThemePackAuthor> authors = new ArrayList<>(array.size());
        for (JsonElement author : array) {
            try {
                authors.add(fromJson(author));
            } catch (JsonParseException | IllegalArgumentException ignored) {
                // Optional malformed author metadata does not invalidate the complete pack.
            }
        }
        return List.copyOf(authors);
    }

    /// Parses a plain author string or object with a name field.
    ///
    /// @param element author value
    /// @return parsed author
    public static ThemePackAuthor fromJson(JsonElement element) {
        if (element instanceof JsonPrimitive primitive && primitive.isString()) {
            return new ThemePackAuthor(LocalizedText.plain(primitive.getAsString()));
        }
        if (!(element instanceof JsonObject object)) {
            throw new JsonParseException("Theme-pack author must be a string or object");
        }
        LocalizedText name = LocalizedText.fromJson(object.get("name"));
        if (name == null) {
            throw new JsonParseException("Theme-pack author is missing name");
        }
        return new ThemePackAuthor(name);
    }

    /// Returns the localized author name.
    ///
    /// @return display name
    public String displayName() {
        return name.getText(I18n.getLocale().getCandidateLocales());
    }

    /// Converts this author to JSON.
    ///
    /// @return author object
    public JsonObject toJsonObject() {
        JsonObject object = new JsonObject();
        object.add("name", name.toJsonElement());
        return object;
    }

    /// Converts an author list to JSON.
    ///
    /// @param authors authors to encode
    /// @return author array
    static JsonArray toJson(@Unmodifiable List<ThemePackAuthor> authors) {
        JsonArray array = new JsonArray();
        authors.forEach(author -> array.add(author.toJsonObject()));
        return array;
    }
}
