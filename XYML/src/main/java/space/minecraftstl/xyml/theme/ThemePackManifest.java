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
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.annotations.JsonAdapter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.util.gson.JsonSchema;
import space.minecraftstl.xyml.util.gson.JsonSerializable;
import space.minecraftstl.xyml.util.i18n.I18n;
import space.minecraftstl.xyml.util.i18n.LocalizedText;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/// Parsed metadata and selectable themes from one theme-pack manifest.
///
/// @param id stable package identifier
/// @param version package version string
/// @param name localized package name
/// @param authors package authors
/// @param icon optional package icon asset
/// @param description optional localized description
/// @param themes selectable themes
@NotNullByDefault
@JsonSerializable
@JsonAdapter(ThemePackManifest.Adapter.class)
public record ThemePackManifest(
        String id,
        String version,
        LocalizedText name,
        @Unmodifiable List<ThemePackAuthor> authors,
        @Nullable String icon,
        @Nullable LocalizedText description,
        @Unmodifiable List<Theme> themes) {
    /// Current compatible manifest schema.
    public static final JsonSchema CURRENT_SCHEMA =
            new JsonSchema("theme-pack", new JsonSchema.Version(1, 0, 0));

    /// Maximum selectable themes accepted from one manifest.
    public static final int MAXIMUM_THEME_COUNT = 128;

    /// Package and theme identifier grammar.
    private static final Pattern PACKAGE_ID_PATTERN = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9_-]*(\\.[A-Za-z0-9][A-Za-z0-9_-]*)*");

    /// Validates, normalizes, and copies all manifest values.
    public ThemePackManifest {
        id = requirePackageId(id);
        version = requireNonBlank(version, "version", 256);
        name = requireLocalizedText(name, "name");
        authors = List.copyOf(authors);
        if (icon != null) {
            icon = ThemePackAsset.normalizeEntryName(icon);
        }
        if (description != null) {
            description = requireLocalizedText(description, "description");
        }
        themes = List.copyOf(themes);
        if (themes.isEmpty() || themes.size() > MAXIMUM_THEME_COUNT) {
            throw new IllegalArgumentException("Theme pack must declare a bounded non-empty theme list");
        }
        checkThemeIdentities(themes);
    }

    /// Returns whether this manifest uses the unnamed single-theme representation.
    ///
    /// @return `true` for a simple pack
    public boolean isSimpleThemePack() {
        return themes.size() == 1 && themes.get(0).id() == null && themes.get(0).name() == null;
    }

    /// Returns the localized package display name.
    ///
    /// @return localized name
    public String displayName() {
        return name.getText(I18n.getLocale().getCandidateLocales());
    }

    /// Returns the localized optional description.
    ///
    /// @return localized description or `null`
    public @Nullable String displayDescription() {
        return description != null ? description.getText(I18n.getLocale().getCandidateLocales()) : null;
    }

    /// Finds a theme by its persisted reference component.
    ///
    /// @param themeId explicit theme ID, or `null` for a single-theme pack
    /// @return matching theme or `null`
    public @Nullable Theme findTheme(@Nullable String themeId) {
        if (themeId == null) {
            return themes.size() == 1 ? themes.get(0) : null;
        }
        return themes.stream().filter(theme -> themeId.equals(theme.id())).findFirst().orElse(null);
    }

    /// Returns every normalized theme-pack asset referenced by this manifest.
    ///
    /// @return immutable reference set in declaration order
    public @Unmodifiable Set<String> referencedAssets() {
        LinkedHashSet<String> assets = new LinkedHashSet<>();
        if (icon != null) {
            assets.add(icon);
        }
        for (Theme theme : themes) {
            if (theme.icon() != null) {
                assets.add(theme.icon());
            }
            collectAppearanceAssets(theme.appearance(), assets);
            for (ThemeOverride override : theme.overrides()) {
                collectAppearanceAssets(override.appearance(), assets);
            }
        }
        return Collections.unmodifiableSet(assets);
    }

    /// Converts this manifest to its canonical JSON object.
    ///
    /// @return manifest object
    public JsonObject toJsonObject() {
        JsonObject object = new JsonObject();
        object.addProperty(JsonSchema.PROPERTY_SCHEMA, CURRENT_SCHEMA.url());
        object.addProperty("id", id);
        object.addProperty("version", version);
        object.add("name", name.toJsonElement());
        if (!authors.isEmpty()) {
            object.add("authors", ThemePackAuthor.toJson(authors));
        }
        if (description != null) {
            object.add("description", description.toJsonElement());
        }
        if (icon != null) {
            object.addProperty("icon", icon);
        }
        if (isSimpleThemePack()) {
            object.add("theme", themes.get(0).toJsonObject());
        } else {
            JsonArray array = new JsonArray();
            themes.forEach(theme -> array.add(theme.toJsonObject()));
            object.add("themes", array);
        }
        return object;
    }

    /// Adds one appearance's local image source to a reference set.
    private static void collectAppearanceAssets(ThemeAppearance appearance, Set<String> assets) {
        @Nullable ThemeBackgroundSettings background = appearance.background();
        if (background != null && background.source() instanceof ThemeBackground.Image image) {
            assets.add(image.path());
        }
    }

    /// Reads exactly one of the single-theme or multi-theme declarations.
    private static @Unmodifiable List<Theme> readThemes(JsonObject object) {
        boolean hasSingle = object.has("theme");
        boolean hasMultiple = object.has("themes");
        if (hasSingle == hasMultiple) {
            throw new JsonParseException("Theme-pack manifest must declare exactly one of theme or themes");
        }
        if (hasSingle) {
            if (!(object.get("theme") instanceof JsonObject themeObject)) {
                throw new JsonParseException("Theme-pack theme must be an object");
            }
            return List.of(Theme.fromJson(themeObject, false));
        }
        if (!(object.get("themes") instanceof JsonArray array)
                || array.isEmpty()
                || array.size() > MAXIMUM_THEME_COUNT) {
            throw new JsonParseException("Theme-pack themes must be a bounded non-empty array");
        }
        List<Theme> themes = new ArrayList<>(array.size());
        for (JsonElement item : array) {
            if (!(item instanceof JsonObject themeObject)) {
                throw new JsonParseException("Theme-pack theme must be an object");
            }
            themes.add(Theme.fromJson(themeObject, true));
        }
        return List.copyOf(themes);
    }

    /// Rejects missing or duplicate identities in multi-theme packs.
    private static void checkThemeIdentities(@Unmodifiable List<Theme> themes) {
        if (themes.size() <= 1) {
            return;
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Theme theme : themes) {
            if (theme.id() == null || theme.name() == null) {
                throw new IllegalArgumentException("Multi-theme packs require every theme ID and name");
            }
            if (!ids.add(theme.id())) {
                throw new IllegalArgumentException("Duplicate theme ID: " + theme.id());
            }
        }
    }

    /// Reads one required string member.
    private static String requireMemberString(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isString()) {
            throw new JsonParseException("Theme-pack manifest is missing string field: " + field);
        }
        return requireNonBlank(primitive.getAsString(), field, 256);
    }

    /// Validates non-empty localized text.
    static LocalizedText requireLocalizedText(LocalizedText value, String field) {
        Objects.requireNonNull(value, field);
        if (value.mayBeEmpty()) {
            throw new IllegalArgumentException("Theme-pack localized text is empty: " + field);
        }
        return value;
    }

    /// Validates a package identifier.
    static String requirePackageId(String value) {
        return requireId(value, "manifest id");
    }

    /// Validates a theme identifier.
    static String requireThemeId(String value) {
        return requireId(value, "theme id");
    }

    /// Validates one bounded package-format identifier.
    private static String requireId(String value, String field) {
        String id = requireNonBlank(value, field, 160);
        if (!PACKAGE_ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid theme-pack " + field + ": " + value);
        }
        return id;
    }

    /// Validates one bounded non-empty string.
    private static String requireNonBlank(String value, String field, int maximumLength) {
        String trimmed = Objects.requireNonNull(value, field).trim();
        if (trimmed.isEmpty() || trimmed.length() > maximumLength) {
            throw new IllegalArgumentException("Theme-pack field is blank or too long: " + field);
        }
        return trimmed;
    }

    /// Gson adapter for the stable manifest representation.
    @NotNullByDefault
    public static final class Adapter implements JsonSerializer<@Nullable ThemePackManifest>,
            JsonDeserializer<@Nullable ThemePackManifest> {
        /// Parses a compatible current-major manifest.
        @Override
        public @Nullable ThemePackManifest deserialize(
                @Nullable JsonElement json,
                Type typeOfT,
                JsonDeserializationContext context) {
            if (json == null || json instanceof JsonNull) {
                return null;
            }
            if (!(json instanceof JsonObject object)) {
                throw new JsonParseException("Theme-pack manifest must be an object");
            }
            JsonSchema.CompatibilityResult compatibility = JsonSchema.check(object, CURRENT_SCHEMA);
            if (!compatibility.allowSave()) {
                throw new JsonParseException("Unsupported or read-only theme-pack schema: "
                        + object.get(JsonSchema.PROPERTY_SCHEMA));
            }
            LocalizedText name = LocalizedText.fromJson(object.get("name"));
            if (name == null) {
                throw new JsonParseException("Theme-pack manifest is missing name");
            }
            @Nullable LocalizedText description = optionalLocalizedText(object.get("description"));
            @Nullable String icon = optionalAsset(object, "icon");
            return new ThemePackManifest(
                    requireMemberString(object, "id"),
                    requireMemberString(object, "version"),
                    name,
                    ThemePackAuthor.parseAuthors(object.get("authors")),
                    icon,
                    description,
                    readThemes(object));
        }

        /// Serializes one manifest in canonical form.
        @Override
        public JsonElement serialize(
                @Nullable ThemePackManifest source,
                Type typeOfSource,
                JsonSerializationContext context) {
            return source != null ? source.toJsonObject() : JsonNull.INSTANCE;
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
            JsonElement element = object.get(field);
            if (!(element instanceof JsonPrimitive primitive) || !primitive.isString()) {
                return null;
            }
            try {
                return ThemePackAsset.normalizeEntryName(primitive.getAsString());
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }
}
