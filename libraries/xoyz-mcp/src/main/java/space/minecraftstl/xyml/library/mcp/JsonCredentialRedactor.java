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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Redacts a configured transport credential from JSON strings and object property names without changing JSON shape.
@NotNullByDefault
final class JsonCredentialRedactor {
    /// Human-readable sentinels tried before falling back to an opaque character.
    private static final List<String> REDACTION_MARKERS = List.of(
            "[REDACTED]", "[MASKED]", "[HIDDEN]", "[REMOVED]", "[SANITIZED]", "[CREDENTIAL]");

    /// JSON-RPC and MCP member names whose spelling is part of the wire schema. They remain unchanged even when a
    /// deliberately short credential is a substring, because changing them would make the response unparsable.
    private static final Set<String> PROTOCOL_MEMBER_NAMES = Set.of(
            "jsonrpc", "id", "method", "params", "result", "error", "code", "message", "data",
            "protocolVersion", "capabilities", "serverInfo", "clientInfo", "tools", "resources", "prompts",
            "resourceTemplates", "content", "contents", "structuredContent", "isError", "name", "type",
            "text", "mimeType", "uri", "arguments", "sessionId", "sessionTtl", "version", "listChanged",
            "subscribe");

    /// Prevents construction of this stateless helper.
    private JsonCredentialRedactor() {
    }

    /// Returns a detached JSON tree with credential-bearing string values sanitized.
    ///
    /// Number values and JSON punctuation are never rewritten, even when a token is a short substring such as `i` or
    /// `{`. A non-protocol property name containing the configured credential is rewritten with a collision-free
    /// redacted name. JSON protocol member names remain unchanged, which is important for short opaque credentials
    /// such as `i`.
    ///
    /// @param source response tree to sanitize
    /// @param token configured credential, or null when authentication is disabled
    /// @return detached sanitized response tree
    static JsonElement redact(JsonElement source, @Nullable String token) {
        JsonElement checked = Objects.requireNonNull(source, "source");
        if (token == null || token.isEmpty()) {
            return checked.deepCopy();
        }
        return redact(checked, token, redactionMarker(token));
    }

    /// Returns a detached JSON tree using a marker which cannot contain the configured credential.
    ///
    /// @param source response tree to sanitize
    /// @param token configured credential
    /// @param marker collision-free replacement marker
    /// @return detached sanitized response tree
    private static JsonElement redact(JsonElement source, String token, String marker) {
        if (source.isJsonObject()) {
            JsonObject redacted = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : source.getAsJsonObject().entrySet()) {
                String key = redactPropertyName(entry.getKey(), token, marker);
                if (redacted.has(key)) {
                    int suffix = 1;
                    String candidate;
                    do {
                        candidate = key + "#" + suffix++;
                    } while (redacted.has(candidate));
                    key = candidate;
                }
                redacted.add(key, redact(entry.getValue(), token, marker));
            }
            return redacted;
        }
        if (source.isJsonArray()) {
            JsonArray redacted = new JsonArray();
            for (JsonElement element : source.getAsJsonArray()) {
                redacted.add(redact(element, token, marker));
            }
            return redacted;
        }
        if (source.isJsonPrimitive()) {
            JsonPrimitive primitive = source.getAsJsonPrimitive();
            if (primitive.isString()) {
                return new JsonPrimitive(primitive.getAsString().replace(token, marker));
            }
        }
        return source.deepCopy();
    }

    /// Replaces credential text in one non-protocol property name.
    ///
    /// @param key source property name
    /// @param token configured credential
    /// @return sanitized property name
    private static String redactPropertyName(String key, String token, String marker) {
        if (PROTOCOL_MEMBER_NAMES.contains(key)) {
            return key;
        }
        return key.replace(token, marker);
    }

    /// Selects a readable marker which is neither contained in nor contains the credential.
    ///
    /// @param token configured credential
    /// @return collision-free replacement marker
    private static String redactionMarker(String token) {
        for (String marker : REDACTION_MARKERS) {
            if (!token.contains(marker) && !marker.contains(token)) {
                return marker;
            }
        }
        for (int codePoint = 0xE000; codePoint <= 0xF8FF; codePoint++) {
            String marker = String.valueOf((char) codePoint);
            if (!token.contains(marker)) {
                return marker;
            }
        }
        throw new IllegalArgumentException("Credential cannot be redacted safely");
    }
}
