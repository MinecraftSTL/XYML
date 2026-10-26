// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.theme;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Inheritable background source and opacity settings.
///
/// @param source background source, or `null` when inherited
/// @param opacity opacity in the inclusive range `0` to `1`, or `null` when inherited
@NotNullByDefault
public record ThemeBackgroundSettings(@Nullable ThemeBackground source, @Nullable Double opacity) {
    /// JSON member naming opacity.
    private static final String FIELD_OPACITY = "opacity";

    /// Validates an appearance background patch.
    public ThemeBackgroundSettings {
        if (opacity != null && (!Double.isFinite(opacity) || opacity < 0.0 || opacity > 1.0)) {
            throw new IllegalArgumentException("Theme background opacity must be between 0 and 1: " + opacity);
        }
    }

    /// Parses a background patch.
    ///
    /// @param object manifest object
    /// @return parsed patch
    static ThemeBackgroundSettings fromJson(JsonObject object) {
        return new ThemeBackgroundSettings(ThemeBackground.fromJson(object), readOpacity(object));
    }

    /// Converts this patch to JSON.
    ///
    /// @return background object
    public JsonObject toJsonObject() {
        JsonObject object = source != null ? source.toJsonObject() : new JsonObject();
        if (opacity != null) {
            object.addProperty(FIELD_OPACITY, opacity);
        }
        return object;
    }

    /// Returns whether every member is inherited.
    ///
    /// @return `true` for an empty patch
    public boolean isEmpty() {
        return source == null && opacity == null;
    }

    /// Applies another patch over this patch.
    ///
    /// @param patch newer patch
    /// @return merged settings
    public ThemeBackgroundSettings merge(ThemeBackgroundSettings patch) {
        Objects.requireNonNull(patch, "patch");
        return new ThemeBackgroundSettings(
                patch.source != null ? patch.source : source,
                patch.opacity != null ? patch.opacity : opacity);
    }

    /// Reads a valid optional opacity while ignoring malformed optional values.
    private static @Nullable Double readOpacity(JsonObject object) {
        JsonElement element = object.get(FIELD_OPACITY);
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
            return null;
        }
        try {
            double value = primitive.getAsDouble();
            return Double.isFinite(value) && value >= 0.0 && value <= 1.0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
