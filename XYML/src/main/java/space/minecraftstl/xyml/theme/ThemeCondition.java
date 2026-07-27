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
import org.jetbrains.annotations.Unmodifiable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// AND-composed condition whose individual values may each declare an OR-set.
///
/// Unknown keys are retained for forward compatibility and cannot match the current context implementation.
///
/// @param requirements normalized accepted values keyed by condition name
@NotNullByDefault
public record ThemeCondition(@Unmodifiable Map<String, @Unmodifiable Set<String>> requirements) {
    /// Effective brightness condition key.
    static final String KEY_BRIGHTNESS = "brightness";
    /// Current operating-system condition key.
    static final String KEY_OS = "os";
    /// Current language condition key.
    static final String KEY_LANGUAGE = "language";
    /// Maximum condition members accepted from one override.
    private static final int MAXIMUM_MEMBER_COUNT = 32;
    /// Maximum values accepted from one condition member.
    private static final int MAXIMUM_VALUES_PER_MEMBER = 64;
    /// Supported operating-system values.
    private static final @Unmodifiable Set<String> SUPPORTED_OS_VALUES =
            Set.of("windows", "macos", "linux", "freebsd", "unknown");

    /// Creates an immutable normalized condition.
    public ThemeCondition {
        Objects.requireNonNull(requirements, "requirements");
        if (requirements.size() > MAXIMUM_MEMBER_COUNT) {
            throw new IllegalArgumentException("Theme condition has too many members");
        }
        LinkedHashMap<String, Set<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : requirements.entrySet()) {
            String key = normalizeKey(entry.getKey());
            Set<String> values = Objects.requireNonNull(entry.getValue(), "condition values");
            if (values.isEmpty() || values.size() > MAXIMUM_VALUES_PER_MEMBER) {
                throw new IllegalArgumentException("Theme condition value count is invalid: " + key);
            }
            LinkedHashSet<String> valueCopy = new LinkedHashSet<>();
            for (String value : values) {
                valueCopy.add(normalizeValue(key, value));
            }
            copy.put(key, Collections.unmodifiableSet(valueCopy));
        }
        requirements = Collections.unmodifiableMap(copy);
    }

    /// Parses a condition object.
    ///
    /// @param object condition object
    /// @return parsed condition
    public static ThemeCondition fromJson(JsonObject object) {
        if (object.size() > MAXIMUM_MEMBER_COUNT) {
            throw new JsonParseException("Theme condition has too many members");
        }
        LinkedHashMap<String, Set<String>> requirements = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String key = normalizeKey(entry.getKey());
            requirements.put(key, readAcceptedValues(key, entry.getValue()));
        }
        return new ThemeCondition(requirements);
    }

    /// Tests every member against one context.
    ///
    /// @param context resolution context
    /// @return `true` when every member matches
    public boolean matches(ThemeResolveContext context) {
        for (Map.Entry<String, Set<String>> entry : requirements.entrySet()) {
            String value = context.conditionValue(entry.getKey());
            if (value == null || !entry.getValue().contains(value)) {
                return false;
            }
        }
        return true;
    }

    /// Converts this condition to JSON.
    ///
    /// @return condition object
    public JsonObject toJsonObject() {
        JsonObject object = new JsonObject();
        for (Map.Entry<String, Set<String>> entry : requirements.entrySet()) {
            if (entry.getValue().size() == 1) {
                object.addProperty(entry.getKey(), entry.getValue().iterator().next());
            } else {
                JsonArray array = new JsonArray();
                entry.getValue().forEach(array::add);
                object.add(entry.getKey(), array);
            }
        }
        return object;
    }

    /// Reads one string or string-array requirement.
    private static Set<String> readAcceptedValues(String key, JsonElement element) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (element instanceof JsonPrimitive primitive && primitive.isString()) {
            values.add(normalizeValue(key, primitive.getAsString()));
        } else if (element instanceof JsonArray array) {
            if (array.isEmpty() || array.size() > MAXIMUM_VALUES_PER_MEMBER) {
                throw new JsonParseException("Theme condition array size is invalid: " + key);
            }
            for (JsonElement item : array) {
                if (!(item instanceof JsonPrimitive primitive) || !primitive.isString()) {
                    throw new JsonParseException("Theme condition array must contain strings: " + key);
                }
                values.add(normalizeValue(key, primitive.getAsString()));
            }
        } else {
            throw new JsonParseException("Theme condition value must be a string or string array: " + key);
        }
        return values;
    }

    /// Normalizes one non-empty key.
    private static String normalizeKey(String key) {
        String normalized = Objects.requireNonNull(key, "key").trim();
        if (normalized.isEmpty() || normalized.length() > 128) {
            throw new JsonParseException("Theme condition key is blank or too long");
        }
        return normalized;
    }

    /// Normalizes one value according to its known key.
    private static String normalizeValue(String key, String value) {
        String trimmed = Objects.requireNonNull(value, "value").trim();
        if (trimmed.isEmpty() || trimmed.length() > 256) {
            throw new JsonParseException("Theme condition value is blank or too long: " + key);
        }
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        return switch (key) {
            case KEY_BRIGHTNESS -> {
                ThemeBrightness.parse(normalized);
                yield normalized;
            }
            case KEY_OS -> normalizeOperatingSystemValue(normalized, value);
            case KEY_LANGUAGE -> normalized;
            default -> trimmed;
        };
    }

    /// Normalizes operating-system aliases and rejects unsupported systems.
    private static String normalizeOperatingSystemValue(String normalized, String original) {
        String value = switch (normalized) {
            case "win", "windows" -> "windows";
            case "mac", "macos", "osx" -> "macos";
            case "linux" -> "linux";
            case "freebsd" -> "freebsd";
            case "unknown", "universal" -> "unknown";
            default -> normalized;
        };
        if (!SUPPORTED_OS_VALUES.contains(value)) {
            throw new JsonParseException("Unsupported os condition value: " + original);
        }
        return value;
    }
}
