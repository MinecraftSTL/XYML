// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.theme;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Inheritable title-bar settings.
///
/// @param transparent whether the title area is transparent, or `null` when inherited
@NotNullByDefault
public record ThemeTitleBar(@Nullable Boolean transparent) {
    /// JSON member naming transparency.
    private static final String FIELD_TRANSPARENT = "transparent";

    /// Parses one title-bar patch.
    ///
    /// @param object manifest object
    /// @return parsed patch
    static ThemeTitleBar fromJson(JsonObject object) {
        JsonElement element = object.get(FIELD_TRANSPARENT);
        @Nullable Boolean transparent = element instanceof JsonPrimitive primitive && primitive.isBoolean()
                ? primitive.getAsBoolean()
                : null;
        return new ThemeTitleBar(transparent);
    }

    /// Converts this patch to JSON.
    ///
    /// @return title-bar object
    public JsonObject toJsonObject() {
        JsonObject object = new JsonObject();
        if (transparent != null) {
            object.addProperty(FIELD_TRANSPARENT, transparent);
        }
        return object;
    }

    /// Returns whether this patch has no concrete value.
    ///
    /// @return `true` when inherited
    public boolean isEmpty() {
        return transparent == null;
    }

    /// Applies a newer patch over this patch.
    ///
    /// @param patch newer patch
    /// @return merged patch
    public ThemeTitleBar merge(ThemeTitleBar patch) {
        Objects.requireNonNull(patch, "patch");
        return new ThemeTitleBar(patch.transparent != null ? patch.transparent : transparent);
    }
}
