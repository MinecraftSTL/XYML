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

import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.util.gson.JsonSerializable;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/// Toolkit-neutral theme color specification used by persisted appearance settings and resolved themes.
///
/// The Swing theme manager consumes its own design tokens; this value carries the serialized color name or
/// hexadecimal specification without loading a presentation-toolkit color object.
///
/// @param name canonical serialized name
/// @param color canonical hexadecimal color value
@JsonAdapter(ThemeColor.TypeAdapter.class)
@JsonSerializable
@NotNullByDefault
public record ThemeColor(String name, String color) {
    /// Default launcher color.
    public static final ThemeColor DEFAULT = new ThemeColor("blue", "#5555FF");

    /// Built-in named color specifications accepted by current settings.
    public static final @Unmodifiable List<ThemeColor> STANDARD_COLORS = List.of(
            DEFAULT,
            new ThemeColor("darker_blue", "#283593"),
            new ThemeColor("green", "#43A047"),
            new ThemeColor("orange", "#E67E22"),
            new ThemeColor("purple", "#9C27B0"),
            new ThemeColor("red", "#B71C1C"));

    /// Creates a validated color specification.
    public ThemeColor {
        if (name.isBlank() || color.isBlank()) {
            throw new IllegalArgumentException("Theme color fields must not be blank");
        }
    }

    /// Parses a named or hexadecimal color specification.
    ///
    /// @param value serialized value
    /// @return parsed color, or null when malformed
    public static @Nullable ThemeColor of(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (!normalized.startsWith("#")) {
            for (ThemeColor color : STANDARD_COLORS) {
                if (normalized.equalsIgnoreCase(color.name())) {
                    return color;
                }
            }
            return null;
        }
        if (!normalized.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")) {
            return null;
        }
        return new ThemeColor(normalized.toUpperCase(Locale.ROOT), normalized.toUpperCase(Locale.ROOT));
    }

    /// Returns a canonical display value for a serialized color.
    public static @Nullable String getColorDisplayName(@Nullable String value) {
        @Nullable ThemeColor color = of(value);
        return color == null ? null : color.color();
    }

    /// Gson adapter using the current compact string representation.
    @NotNullByDefault
    static final class TypeAdapter extends com.google.gson.TypeAdapter<ThemeColor> {
        /// Writes one color value.
        @Override
        public void write(JsonWriter out, @Nullable ThemeColor value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.name());
            }
        }

        /// Reads one color value.
        @Override
        public @Nullable ThemeColor read(JsonReader in) throws IOException {
            return of(in.nextString());
        }
    }
}
