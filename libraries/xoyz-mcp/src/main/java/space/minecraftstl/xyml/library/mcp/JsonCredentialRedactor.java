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
package space.minecraftstl.xyml.library.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

/// Redacts a configured transport credential from JSON strings and object property names without changing JSON shape.
@NotNullByDefault
final class JsonCredentialRedactor {
    /// Prevents construction of this stateless helper.
    private JsonCredentialRedactor() {
    }

    /// Returns a detached JSON tree with credential-bearing string values sanitized.
    ///
    /// Number values and JSON punctuation are never rewritten, even when a token is a short substring such as `i` or
    /// `{`. A property name equal to the configured credential is replaced with a collision-free redacted name. JSON
    /// protocol member names remain unchanged, which is important for short opaque credentials such as `i`.
    ///
    /// @param source response tree to sanitize
    /// @param token configured credential, or null when authentication is disabled
    /// @return detached sanitized response tree
    static JsonElement redact(JsonElement source, @Nullable String token) {
        JsonElement checked = Objects.requireNonNull(source, "source");
        if (token == null) {
            return checked.deepCopy();
        }
        if (checked.isJsonObject()) {
            JsonObject redacted = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : checked.getAsJsonObject().entrySet()) {
                String key = entry.getKey().equals(token) ? "[REDACTED]" : entry.getKey();
                if (redacted.has(key)) {
                    int suffix = 1;
                    String candidate;
                    do {
                        candidate = key + "#" + suffix++;
                    } while (redacted.has(candidate));
                    key = candidate;
                }
                redacted.add(key, redact(entry.getValue(), token));
            }
            return redacted;
        }
        if (checked.isJsonArray()) {
            JsonArray redacted = new JsonArray();
            for (JsonElement element : checked.getAsJsonArray()) {
                redacted.add(redact(element, token));
            }
            return redacted;
        }
        if (checked.isJsonPrimitive()) {
            JsonPrimitive primitive = checked.getAsJsonPrimitive();
            if (primitive.isString()) {
                return new JsonPrimitive(primitive.getAsString().replace(token, "[REDACTED]"));
            }
        }
        return checked.deepCopy();
    }
}
